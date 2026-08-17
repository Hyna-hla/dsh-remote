// dsh-remote-access v2.0.0 — 远程互信认证 + 只读辅助路由
// 保留：移动端配对握手（pair/*）、主机设备信息（device/info）、只读目录列举（fs/list）、
//       只读文件预览（fs/read）、MCP 枚举（mcp/list）、远程通道 Bearer token 鉴权（/api 全量门禁，含 WebSocket）。
// 移除：微信 iLink 桥（lib/ilink.js + lib/bridge.js）、cpolar 隧道供应（lib/cpolar.js），
//       对应 wx/*、cpolar/*、/status、/start、/stop、/qr 路由与 qrcode 依赖一并删除。
import { execFile } from "node:child_process";
import { randomBytes } from "node:crypto";
import {
  closeSync,
  fstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  readSync,
  readdirSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { homedir, hostname, networkInterfaces } from "node:os";
import { isAbsolute, join } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export const name = "dsh-remote-access";
// fs / tools 不进 inject 声明，运行时以 ctx.fs / ctx.get("tools") 惰性取用：
// 不可用回退 node:fs / 空工具列表；tools 由 dsh-mcp-client 挂载（可能晚于本插件）。
// 注意：npm 版 @deepseek-ai/dsh 的 Inject.resolve 只认服务名数组/服务名为键的对象，
// 无 { required, optional } 语义——对象写法会被解析成等待名为 required/optional 的服务，插件永远 pending
export const inject = ["webServer"];

const json = (res, code, payload) => {
  res.statusCode = code;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.end(JSON.stringify(payload));
};

const readBody = (req) =>
  new Promise((resolve) => {
    let data = "";
    req.on("data", (c) => {
      data += c;
      if (data.length > 1024 * 1024) req.destroy();
    });
    req.on("end", () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch {
        resolve({});
      }
    });
  });

/** 只读文件头部：返回 {size: 文件总字节数, head: 至多 maxBytes 前缀}（大文件不整读，内存安全） */
function readHeadSync(path, maxBytes) {
  const fd = openSync(path, "r");
  try {
    const size = fstatSync(fd).size;
    const want = Math.min(size, maxBytes + 1);
    const buf = Buffer.allocUnsafe(want);
    let off = 0;
    while (off < want) {
      const n = readSync(fd, buf, off, want - off, off);
      if (n <= 0) break;
      off += n;
    }
    return { size, head: buf.subarray(0, off) };
  } finally {
    closeSync(fd);
  }
}

/** 严格 UTF-8 解码：非法字节序列返回 null（与 DSH fs 层的 TextDecoder fatal 语义一致） */
function decodeUtf8Strict(buf) {
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(buf);
  } catch {
    return null;
  }
}

export const apply = (ctx) => {
  const webServer = ctx.webServer;
  const log = (...args) => console.error("[dsh-remote-access]", ...args);
  const home = process.env.DSH_HOME || join(homedir(), ".dsh");

  // 路由注册统一收集 disposer，随 fiber 卸载自动清理（热重载不再残留路由）
  const disposers = [];
  const reg = (route) => {
    disposers.push(webServer.register(route));
  };
  ctx.effect(
    () => () => {
      for (const d of disposers) {
        try {
          d();
        } catch {}
      }
    },
    "dsh-remote-access: web routes"
  );

  // ---------- 目录浏览（移动端任选 PC 目录作工作区）：只读列举子目录 + 文件 ----------
  // dirs[] 仅目录名（兼容旧 App）；files[] = {name, path, size, hidden}
  reg({
    kind: "exact",
    path: "/api/remote-access/fs/list",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const u = new URL(req.url, "http://localhost");
      const p = u.searchParams.get("path") ?? "";
      if (!isAbsolute(p)) return json(res, 400, { error: "需要绝对路径" });
      try {
        const dirs = [];
        const files = [];
        for (const d of readdirSync(p, { withFileTypes: true })) {
          if (d.isDirectory()) {
            if (dirs.length < 200) dirs.push(d.name);
          } else if (d.isFile()) {
            if (files.length >= 200) continue;
            let size = 0;
            try {
              size = statSync(join(p, d.name)).size;
            } catch {}
            files.push({ name: d.name, path: join(p, d.name), size, hidden: d.name.startsWith(".") });
          }
        }
        json(res, 200, { ok: true, path: p, dirs, files });
      } catch (err) {
        json(res, 200, { ok: false, error: String(err?.message ?? err) });
      }
    },
  });

  // ---------- 文件内容只读预览：1MB 截断（truncated: true）+ 二进制识别 ----------
  // 读取优先注入的 fs 服务（工作区/sandbox 语义）：小文本走 ctx.fs.readText；超过 1MB / 二进制回退 node:fs 读头部
  reg({
    kind: "exact",
    path: "/api/remote-access/fs/read",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const u = new URL(req.url, "http://localhost");
      const p = u.searchParams.get("path") ?? "";
      if (!isAbsolute(p)) return json(res, 400, { error: "需要绝对路径" });
      const MAX = 1024 * 1024; // 1MB
      try {
        const fsApi = ctx.fs;
        let raw = null; // Buffer（头部或全量）
        let size = 0;
        if (fsApi && typeof fsApi.readText === "function") {
          const target = await fsApi.resolve(p);
          const info = typeof fsApi.stat === "function" ? await fsApi.stat(target) : null;
          const statSize = typeof info?.size === "number" ? info.size : 0;
          if (statSize > MAX) {
            // 超大文件：注入 fs 的 readBytes 超限即抛错、readText 整读有内存风险 → node:fs 读 1MB 前缀
            const got = readHeadSync(p, MAX);
            raw = got.head;
            size = got.size;
          } else {
            try {
              raw = Buffer.from(await fsApi.readText(target), "utf8");
              size = statSize || raw.length;
            } catch (err) {
              if (err?.code === "FS_NOT_TEXT") {
                // 二进制：注入 fs 不提供原始字节 → node:fs 回退
                const got = readHeadSync(p, MAX);
                raw = got.head;
                size = got.size;
              } else {
                throw err;
              }
            }
          }
        } else {
          const got = readHeadSync(p, MAX);
          raw = got.head;
          size = got.size;
        }
        const truncated = size > MAX;
        const slice = truncated ? raw.subarray(0, MAX) : raw;
        const isBinary = slice.includes(0) || decodeUtf8Strict(slice) === null;
        if (isBinary) {
          json(res, 200, { ok: true, path: p, size, truncated, isBinary: true, data: slice.toString("base64") });
        } else {
          json(res, 200, { ok: true, path: p, size, truncated, isBinary: false, text: decodeUtf8Strict(slice) });
        }
      } catch (err) {
        json(res, 200, { ok: false, error: String(err?.message ?? err) });
      }
    },
  });

  // ---------- MCP 服务列表 ----------
  // 上游 dsh-mcp-client 只把工具以 mcp__<server>__<tool> 注册进 ctx.tools，无连接态查询 API：
  // 按 mcp__ 前缀聚合 serverName → tools[]，status 恒 "unknown"。
  reg({
    kind: "exact",
    path: "/api/remote-access/mcp/list",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      try {
        const tools = ctx.get("tools");
        const schemas = tools && typeof tools.schemas === "function" ? tools.schemas() : [];
        const byServer = new Map();
        for (const s of schemas) {
          const name = typeof s?.name === "string" ? s.name : "";
          // mcp__<server>__<tool>：server 取第一段（工具原始名可能含更多下划线）
          const parts = name.startsWith("mcp__") ? name.split("__") : null;
          if (!parts || parts.length < 3 || !parts[1]) continue;
          const serverName = parts[1];
          if (!byServer.has(serverName)) byServer.set(serverName, []);
          byServer.get(serverName).push(name);
        }
        const servers = [...byServer.entries()].map(([serverName, toolsList]) => ({
          serverName,
          tools: toolsList,
          status: "unknown",
        }));
        json(res, 200, { ok: true, servers });
      } catch (err) {
        json(res, 200, { ok: false, error: String(err?.message ?? err) });
      }
    },
  });

  // ---------- 主机设备信息（App 设备记录：机型展示 + MAC 重连校验） ----------
  // MAC：优先带 IPv4 的物理网卡；本地管理位（随机化/虚拟网卡）的接口靠后
  const isLocalAdminMac = (mac) => /^[0-9a-f][26ae]:/i.test(mac);
  const primaryMac = () => {
    const entries = Object.values(networkInterfaces()).flat().filter(
      (a) => a && !a.internal && a.mac && a.mac !== "00:00:00:00:00:00",
    );
    const pick =
      entries.find((a) => a.family === "IPv4" && !isLocalAdminMac(a.mac)) ||
      entries.find((a) => !isLocalAdminMac(a.mac)) ||
      entries.find((a) => a.family === "IPv4") ||
      entries[0];
    return pick?.mac ?? "";
  };
  // 机型：Win32_ComputerSystem 厂商+型号（10 分钟缓存，避免每次连接都 spawn PowerShell）
  let hostModelCache = { at: 0, value: "" };
  const hostModel = async () => {
    if (hostModelCache.value && Date.now() - hostModelCache.at < 600000) return hostModelCache.value;
    let model = "";
    if (process.platform === "win32") {
      try {
        const ps =
          "$ErrorActionPreference='SilentlyContinue'; " +
          "$c = Get-CimInstance Win32_ComputerSystem | Select-Object -First 1; " +
          "@(($c.Manufacturer -replace '\\s+$',''), ($c.Model -replace '\\s+$','')) -join ' '";
        const { stdout } = await execFileAsync("powershell.exe", ["-NoProfile", "-Command", ps], { timeout: 15000 });
        model = String(stdout).trim();
      } catch {}
    }
    if (!model) model = `${hostname()} (${process.platform})`;
    hostModelCache = { at: Date.now(), value: model };
    return model;
  };
  reg({
    kind: "exact",
    path: "/api/remote-access/device/info",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      json(res, 200, {
        ok: true,
        name: hostname(),
        model: await hostModel(),
        mac: primaryMac(),
        platform: process.platform,
      });
    },
  });

  // ---------- 移动端首次配对（一次性握手确认） ----------
  const pairFile = join(home, "remote-access", "paired.json");
  const readPaired = () => {
    try { return JSON.parse(readFileSync(pairFile, "utf8")).devices ?? []; }
    catch { return []; }
  };
  const writePaired = (devices) => {
    try {
      mkdirSync(join(home, "remote-access"), { recursive: true });
      writeFileSync(pairFile, JSON.stringify({ devices }, null, 2), "utf8");
    } catch (e) { log("paired.json 写入失败", e); }
  };
  let pendingPair = null; // { deviceId, name, at, outcome }（JSON 安全；120s 定时器单独持有）
  let pendingTimer = null;

  const clearPending = () => {
    if (pendingTimer) clearTimeout(pendingTimer);
    pendingTimer = null;
    pendingPair = null;
  };

  reg({
    kind: "exact",
    path: "/api/remote-access/pair/request",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const deviceId = String(body.deviceId || "").trim();
      const deviceName = String(body.deviceName || "未知设备").trim().slice(0, 64);
      if (!deviceId) return json(res, 400, { error: "missing deviceId" });
      if (readPaired().some((d) => d.deviceId === deviceId)) {
        return json(res, 200, { ok: true, state: "paired" });
      }
      if (!pendingPair || pendingPair.deviceId !== deviceId) {
        clearPending();
        pendingPair = { deviceId, name: deviceName, at: Date.now(), outcome: null };
        pendingTimer = setTimeout(() => { pendingPair = null; pendingTimer = null; }, 120000);
      } else {
        // 同一设备重新发起握手：清除上一次 approve/deny 结果，恢复 pending
        pendingPair.outcome = null;
        pendingPair.name = deviceName;
      }
      json(res, 200, { ok: true, state: "pending" });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/pair/status",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      json(res, 200, { ok: true, state: pendingPair?.outcome ?? (pendingPair ? "pending" : "none"), pendingDevice: pendingPair ?? null });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/pair/respond",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const deviceId = String(body.deviceId || "").trim();
      const outcome = String(body.outcome || "");
      if (!pendingPair || pendingPair.deviceId !== deviceId) {
        return json(res, 200, { ok: false, error: "no pending request" });
      }
      if (outcome === "approve") {
        const devices = readPaired().filter((d) => d.deviceId !== deviceId);
        devices.push({ deviceId, name: pendingPair.name, at: Date.now() });
        writePaired(devices);
      }
      pendingPair.outcome = outcome === "approve" ? "approved" : "denied";
      json(res, 200, { ok: true, state: pendingPair.outcome });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/pair/list",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      json(res, 200, { ok: true, devices: readPaired() });
    },
  });

  // 回查某设备是否仍配对（App 撤销后重新握手用）
  reg({
    kind: "exact",
    path: "/api/remote-access/pair/check",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const u = new URL(req.url, "http://localhost");
      const deviceId = u.searchParams.get("deviceId") ?? "";
      const paired = readPaired().some((d) => d.deviceId === deviceId);
      // 已配对设备回查时下发通道 token（App 存入 HostProfile，此后所有请求带 Bearer 头）
      json(res, 200, { ok: true, paired, token: paired ? channelToken() : undefined });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/pair/revoke",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const deviceId = String(body.deviceId || "").trim();
      writePaired(readPaired().filter((d) => d.deviceId !== deviceId));
      json(res, 200, { ok: true });
    },
  });

  // ---------- 远程通道 token 鉴权 ----------
  // /api 全量 Bearer 门禁（含 WebSocket 升级），规则：
  //   - 本机浏览器放行：loopback 远端且无 X-Forwarded-For（公网隧道虽从 127.0.0.1
  //     连入，但携带 XFF/x-real-ip 头，据此与真本机请求区分）；
  //   - 局域网直连（非 loopback 远端）与任何公网隧道一律要求 token；
  //   - 引导通道豁免：/api/remote-access/pair/*（配对握手）与 /api/host.describe（连接探测）；
  //   - token 首次加载时自动生成，存放 $DSH_HOME/remote-access/channel-token。
  const tokenFile = join(home, "remote-access", "channel-token");
  let cachedToken = null;
  const channelToken = () => {
    if (cachedToken) return cachedToken;
    try {
      const t = readFileSync(tokenFile, "utf8").trim();
      if (/^[0-9a-f]{32,}$/.test(t)) return (cachedToken = t);
    } catch {}
    try {
      mkdirSync(join(home, "remote-access"), { recursive: true });
      const t = randomBytes(24).toString("hex");
      writeFileSync(tokenFile, t, "utf8");
      log("已生成远程通道 token（remote-access/channel-token）");
      return (cachedToken = t);
    } catch (e) {
      log("channel-token 生成失败，鉴权不启用", e);
      return null;
    }
  };

  const isLoopback = (addr) =>
    addr === "127.0.0.1" || addr === "::1" || addr === "::ffff:127.0.0.1" || addr === "::ffff:7f00:1";
  const authExempt = (path) =>
    path.startsWith("/api/remote-access/pair/") || path === "/api/host.describe";
  const unauthorized = (req) => {
    const tok = channelToken();
    if (!tok) return false; // token 不可用时不设防（降级可用性优先）
    const p = String(req.url ?? "").split("?")[0];
    if (!p.startsWith("/api") || authExempt(p)) return false;
    if (isLoopback(req.socket?.remoteAddress ?? "") && !req.headers?.["x-forwarded-for"]) return false;
    return String(req.headers?.authorization ?? "") !== `Bearer ${tok}`;
  };

  const rejectHttp = (res) => {
    res.statusCode = 401;
    res.setHeader("Content-Type", "application/json; charset=utf-8");
    res.end(JSON.stringify({ error: "unauthorized", hint: "channel token required" }));
  };
  const rejectUpgrade = (socket) => {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
  };

  // 包裹既有与未来注册的 /api 路由与 WS 升级：核心 /api（client-connection）在本插件
  // 之后惰性挂载，靠 proxy set 陷阱兜住晚注册；分发侧 match() 动态读表属性，换表即生效。
  // 注意：ctx.effect(fn) 是「立即执行 fn、返回值作清理器」——恢复逻辑必须以返回值形式
  // 注册（写成立即执行会在挂载瞬间就把代理表撤掉）。
  const wrapHttp = (route) => ({
    ...route,
    handler: (req, res) => {
      if (unauthorized(req)) return rejectHttp(res);
      return route.handler(req, res);
    },
  });
  const wrapUpgrade = (route) => ({
    ...route,
    handler: (req, socket, head) => {
      if (unauthorized(req)) return rejectUpgrade(socket);
      return route.handler(req, socket, head);
    },
  });
  const proxyTable = (table, wrap) =>
    new Proxy(table, {
      get(target, key) {
        if (key === "set") return (path, route) => target.set(path, wrap(route));
        const value = Reflect.get(target, key);
        return typeof value === "function" ? value.bind(target) : value;
      },
    });
  if (typeof webServer.exact?.set === "function" && typeof webServer.prefixes?.set === "function") {
    const table0exact = webServer.exact;
    const table0prefixes = webServer.prefixes;
    const table0upgrades = webServer.upgrades;
    for (const [path, route] of [...webServer.exact]) webServer.exact.set(path, wrapHttp(route));
    for (const [path, route] of [...webServer.prefixes]) webServer.prefixes.set(path, wrapHttp(route));
    if (typeof webServer.upgrades?.set === "function") {
      for (const [path, route] of [...webServer.upgrades]) webServer.upgrades.set(path, wrapUpgrade(route));
      webServer.upgrades = proxyTable(webServer.upgrades, wrapUpgrade);
    }
    webServer.exact = proxyTable(webServer.exact, wrapHttp);
    webServer.prefixes = proxyTable(webServer.prefixes, wrapHttp);
    ctx.effect(
      () => () => {
        // 恢复原始表引用（已包裹的 handler 随各自路由注销清理）
        webServer.exact = table0exact;
        webServer.prefixes = table0prefixes;
        if (typeof table0upgrades?.set === "function") webServer.upgrades = table0upgrades;
      },
      "dsh-remote-access: channel auth"
    );
    log("远程通道 token 鉴权已挂载");
  } else {
    log("webServer 路由表结构未知，token 鉴权未挂载");
  }
};
