// dsh-remote-access：设置页「远程控制」——微信 iLink 桥（主要方式）+ cpolar 隧道（备选）
// 微信桥：扫码登录微信 iLink Bot → 微信里给自己发消息遥控 DSH（会话注入、流式回复、审批、图片）
// cpolar：网页版备选。内置一键安装（官网自动下载）、注册引导、authtoken 保存，无需手动装 cpolar
import { execFile } from "node:child_process";
import {
  closeSync,
  existsSync,
  fstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  readSync,
  readdirSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { homedir } from "node:os";
import { isAbsolute, join } from "node:path";
import { promisify } from "node:util";
import { ILinkClient } from "./ilink.js";
import { createBridge } from "./bridge.js";
import {
  findCpolar,
  installCpolar,
  cpolarStatus,
  setAuthtoken,
  startTunnel,
  stopTunnel,
  tunnel,
} from "./cpolar.js";
import QRCode from "qrcode";

const execFileAsync = promisify(execFile);

export const name = "dsh-remote-access";
// fs / tools 为可选注入：可用时文件读取走注入服务（工作区/sandbox 语义），
// 不可用回退 node:fs / 空工具列表；tools 由 dsh-mcp-client 挂载（可能晚于本插件）
export const inject = { required: ["webServer"], optional: ["fs", "tools"] };

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

// 探测结果短缓存：设置页轮询时不重复 spawn 进程
let dshPortCache = { at: 0, value: 0 };

/** 探测正在运行的桌面版 DSH web 端口（node 进程命令行匹配 dsh bin.js web） */
async function findDshPort() {
  if (Date.now() - dshPortCache.at < 60000) return dshPortCache.value;
  const ps =
    "$ErrorActionPreference='SilentlyContinue'; " +
    "Get-CimInstance Win32_Process -Filter \"Name='node.exe'\" | " +
    "Where-Object { $_.CommandLine -match 'dsh[\\\\/]lib[\\\\/]bin\\.js' -and $_.CommandLine -match '\\bweb\\b' } | " +
    "ForEach-Object { $p = $_; " +
    "Get-NetTCPConnection -State Listen -OwningProcess $p.ProcessId -ErrorAction SilentlyContinue | " +
    "Where-Object { $_.LocalAddress -in @('127.0.0.1','0.0.0.0','::') } | " +
    "Select-Object -First 1 -ExpandProperty LocalPort } | Select-Object -First 1";
  try {
    const { stdout } = await execFileAsync("powershell.exe", ["-NoProfile", "-Command", ps], { timeout: 15000 });
    const port = parseInt(String(stdout).trim(), 10);
    dshPortCache = { at: Date.now(), value: Number.isFinite(port) && port > 0 ? port : 0 };
    return dshPortCache.value;
  } catch {
    dshPortCache = { at: Date.now(), value: 0 };
    return 0;
  }
}

export const apply = (ctx) => {
  const webServer = ctx.webServer;
  const log = (...args) => console.error("[dsh-remote-access]", ...args);

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

  // ---------- 微信 iLink 桥 ----------
  const home = process.env.DSH_HOME || join(homedir(), ".dsh");
  const ilink = new ILinkClient({ stateFile: join(home, "remote-access", "wx-state.json"), log });
  const { bridge, onSessionEvent, onApprovalRequest } = createBridge(ctx, { ilink, log });

  // 会话事件流（助手文本 → 微信）
  ctx.on("session/event", onSessionEvent);
  // 审批应答器：prepend 先于 API 网关，仅接管微信绑定会话
  ctx.on("approval/request", onApprovalRequest, true);

  bridge.init().catch((err) => log("init failed", err));
  ctx.effect(() => () => bridge.dispose(), "dsh-remote-access: wechat bridge");

  // 装机自检：cpolar 未安装时后台预下载（延迟 10s，避开启动高峰），
  // 用户打开设置页时大概率已就绪；本机已有 cpolar（E:\coplar / PATH）则跳过。
  setTimeout(() => {
    findCpolar()
      .then((exe) => {
        if (!exe) {
          log("cpolar 未安装，后台预下载（装机自检）…");
          installCpolar();
        }
      })
      .catch(() => {});
  }, 10000);

  reg({
    kind: "exact",
    path: "/api/remote-access/wx/status",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      json(res, 200, bridge.getStatus());
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/wx/login",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const result = await bridge.login(body.verifyCode || undefined);
      json(res, 200, { ok: true, ...result });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/wx/stop",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      json(res, 200, await bridge.stop());
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/wx/reset",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const result = await bridge.reset();
      json(res, 200, { ok: true, ...result });
    },
  });

  // ---------- cpolar 隧道（备选） ----------
  reg({
    kind: "exact",
    path: "/api/remote-access/status",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const cpolarFound = Boolean(await findCpolar());
      const dshPort = await findDshPort();
      json(res, 200, {
        status: tunnel.status,
        url: tunnel.url,
        port: tunnel.port,
        message: tunnel.message,
        cpolarFound,
        dshPort,
      });
    },
  });

  // cpolar 供应状态：是否已安装、安装进度、登录态、注册/token 链接
  reg({
    kind: "exact",
    path: "/api/remote-access/cpolar/status",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      json(res, 200, await cpolarStatus());
    },
  });

  // 一键安装：后台下载+解压，前端轮询 /cpolar/status 看进度
  reg({
    kind: "exact",
    path: "/api/remote-access/cpolar/install",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      installCpolar();
      json(res, 200, { ok: true, installing: true });
    },
  });

  // 保存 authtoken（等价 cpolar authtoken <token>）
  reg({
    kind: "exact",
    path: "/api/remote-access/cpolar/authtoken",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const exe = await findCpolar();
      if (!exe) return json(res, 200, { ok: false, error: "cpolar 尚未安装" });
      try {
        await setAuthtoken(exe, body.token);
        json(res, 200, { ok: true });
      } catch (err) {
        json(res, 200, { ok: false, error: err?.message || String(err) });
      }
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/start",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      let port = Number(body.port) || 0;
      if (!port) port = await findDshPort();
      if (!port) {
        return json(res, 200, {
          ok: false,
          error: "未检测到正在运行的 DSH 桌面实例，请先启动 DeepSeek Harness",
        });
      }
      const result = await startTunnel(port);
      json(res, 200, result);
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/stop",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      stopTunnel();
      tunnel.status = "idle";
      tunnel.url = null;
      tunnel.message = "";
      json(res, 200, { ok: true });
    },
  });

  // 本地生成二维码（不出本机、无第三方依赖、秒出图）
  reg({
    kind: "exact",
    path: "/api/remote-access/qr",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const u = new URL(req.url, "http://localhost");
      const data = u.searchParams.get("data") ?? "";
      if (!data) return json(res, 400, { error: "missing data" });
      if (data.length > 2048) return json(res, 400, { error: "data too long" });
      try {
        const dataUrl = await QRCode.toDataURL(data, {
          width: 336,
          margin: 2,
          errorCorrectionLevel: "M",
          color: { dark: "#0D1B2A", light: "#FFFFFF" },
        });
        json(res, 200, { ok: true, dataUrl });
      } catch (err) {
        json(res, 500, { error: String(err?.message ?? err) });
      }
    },
  });

  // 目录浏览（移动端任选 PC 目录作工作区，不限于 DSH 已划定的工作区）：只读列举子目录 + 文件
  // dirs[] 仅目录名（兼容旧 App）；files[] = {name, path, size, hidden}（S6 文件预览用）
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

  // 文件内容只读预览（S6）：1MB 截断（truncated: true）+ 二进制识别（非 UTF-8 / 含 NUL → isBinary + base64 data）
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

  // ---------- MCP 服务列表（S5） ----------
  // 上游 dsh-mcp-client 只把工具以 mcp__<server>__<tool> 注册进 ctx.tools，无连接态查询 API
  // （侦察 §2.2）：按 mcp__ 前缀聚合 serverName → tools[]，status 恒 "unknown"。
  // ctx.get 惰性读取：tools 由 mcp-client 挂载（可能晚于本插件），未挂载/异常 → 空 servers。
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
      const deviceName = String(body.deviceName || "未知设备").trim();
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
      json(res, 200, { ok: true, paired: readPaired().some((d) => d.deviceId === deviceId) });
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
};
