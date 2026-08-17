// dsh-remote-access v2.2.0 — 远程互信认证（配对码 + 确认框 + 公网域名白名单）+ 只读辅助路由
// 保留：移动端配对握手（pair/*）、主机设备信息（device/info）、只读目录列举（fs/list）、
//       只读文件预览（fs/read）、MCP 枚举（mcp/list）、远程通道 Bearer token 鉴权（/api 全量门禁，含 WebSocket）。
// 移除：微信 iLink 桥（lib/ilink.js + lib/bridge.js）、cpolar 隧道供应（lib/cpolar.js），
//       对应 wx/*、cpolar/*、/status、/start、/stop、/qr 路由与 qrcode 依赖一并删除。
import { execFile } from "node:child_process";
import { createHash, randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import {
  closeSync,
  fstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  readSync,
  readdirSync,
  renameSync,
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

  // ---------- 配对码（v2.1.0）：PC 生成 → 手机输入 → 立即配对并下发 token ----------
  // 安全属性：一次性（验证成功即作废）；10 分钟过期；最多 5 次错误尝试（超限作废）；
  // 常量时间比较防时序侧信道；verify 全局 1s 节流防高速爆破；
  // generate 不豁免（仅本机设置页可达），verify 豁免（未配对手机的引导通道）。
  const CODE_TTL_MS = 10 * 60 * 1000;
  const CODE_MAX_ATTEMPTS = 5;
  let pairCode = null; // { code, at, expiresAt, attempts }

  const codeDigest = (s) => createHash("sha256").update(String(s), "utf8").digest();

  reg({
    kind: "exact",
    path: "/api/remote-access/pair/code/generate",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const code = randomInt(0, 1000000).toString().padStart(6, "0");
      pairCode = { code, at: Date.now(), expiresAt: Date.now() + CODE_TTL_MS, attempts: 0 };
      log("已生成配对码（10 分钟有效，最多 5 次尝试，一次性）");
      json(res, 200, { ok: true, code, expiresInSec: CODE_TTL_MS / 1000, maxAttempts: CODE_MAX_ATTEMPTS });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/pair/code/verify",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const now = Date.now();
      const body = await readBody(req);
      const deviceId = String(body.deviceId || "").trim();
      const deviceName = String(body.deviceName || "未知设备").trim().slice(0, 64);
      const code = String(body.code || "").trim();
      if (!deviceId) return json(res, 400, { error: "missing deviceId" });
      if (!pairCode || now > pairCode.expiresAt) {
        pairCode = null;
        return json(res, 200, { ok: false, error: "no_active_code", retryable: false });
      }
      if (pairCode.attempts >= CODE_MAX_ATTEMPTS) {
        pairCode = null;
        return json(res, 200, { ok: false, error: "code_locked", retryable: false });
      }
      if (!/^\d{6}$/.test(code) || !timingSafeEqual(codeDigest(code), codeDigest(pairCode.code))) {
        pairCode.attempts += 1;
        const left = CODE_MAX_ATTEMPTS - pairCode.attempts;
        // 码对象保留：后续尝试由上方 code_locked 分支统一拒绝并销毁
        return json(res, 200, { ok: false, error: "code_mismatch", attemptsLeft: Math.max(0, left), retryable: left > 0 });
      }
      // 验证成功：一次性作废 + 直接写入配对表并下发通道 token
      pairCode = null;
      const devices = readPaired().filter((d) => d.deviceId !== deviceId);
      devices.push({ deviceId, name: deviceName, at: now });
      writePaired(devices);
      json(res, 200, { ok: true, state: "approved", token: channelToken() });
    },
  });

  // ---------- 公网域名信任白名单（settings.yaml → client-connection.trustedHosts） ----------
  // DSH 核心 client-connection 对 /api 做 Host 围栏（防 DNS 重绑定）：只放行 loopback 与 trustedHosts。
  // 手机经公网隧道访问时 Host 是公网域名，必须列入白名单（重启 DSH 后生效）。
  // 直接读写 $DSH_HOME/settings.yaml：settings-file 提供者对「外部编辑热发布」，且自身写入时
  // 先 reconcileFromDisk 再做叶级 diff——外部写入的区块会被保留，不会丢。
  const settingsFile = join(home, "settings.yaml");

  /** 校验条目为纯 域名[:端口]（与核心 assertTrustedAuthority 同口径：WHATWG 规范化后必须逐字一致） */
  const validateAuthority = (entry) => {
    const e = String(entry ?? "").trim();
    if (!e) return "请输入域名或 域名:端口";
    if (e.length > 253) return "条目过长";
    let u;
    try {
      u = new URL(`http://${e}`);
    } catch {
      return `无法解析：${e}`;
    }
    const canonical = u.port ? `${u.hostname}:${u.port}` : u.hostname;
    if (canonical !== e.toLowerCase()) {
      return `必须是纯 域名[:端口] 形式（不带协议/路径/用户信息）：${e}`;
    }
    return null;
  };

  /** 读取 settings.yaml 顶层 client-connection 区块的 trustedHosts 列表（无区块/无文件 → []） */
  const readTrustedHosts = () => {
    let text;
    try {
      text = readFileSync(settingsFile, "utf8");
    } catch {
      return [];
    }
    const lines = text.split(/\r?\n/);
    const start = lines.findIndex((l) => /^client-connection:\s*$/.test(l));
    if (start < 0) return [];
    const hosts = [];
    let inList = false;
    for (let j = start + 1; j < lines.length; j++) {
      const line = lines[j];
      if (!/^[ \t]/.test(line) && line.trim() !== "") break; // 区块结束
      const t = line.trim();
      if (/^trustedHosts:/.test(t)) {
        inList = true;
        continue;
      }
      if (inList && /^- /.test(t)) {
        hosts.push(t.replace(/^-\s*/, "").replace(/^["']|["']$/g, "").trim());
        continue;
      }
      if (inList && t !== "" && !/^- /.test(t)) inList = false; // 区块内其他键
    }
    return hosts;
  };

  /** 把 hosts 写回 client-connection 区块（只动 trustedHosts 行，其余内容原样保留）；空列表写 [] */
  const writeTrustedHosts = (hosts) => {
    let text;
    try {
      text = readFileSync(settingsFile, "utf8");
    } catch {
      text = "";
    }
    const nl = text.includes("\r\n") ? "\r\n" : "\n";
    const lines = text.split(/\r?\n/);
    while (lines.length && lines[lines.length - 1].trim() === "") lines.pop();
    const newListLines = hosts.length
      ? ["  trustedHosts:", ...hosts.map((h) => "    - " + h)]
      : ["  trustedHosts: []"];
    const start = lines.findIndex((l) => /^client-connection:\s*$/.test(l));
    if (start < 0) {
      if (lines.length) lines.push("");
      lines.push("client-connection:", ...newListLines);
    } else {
      let end = start + 1;
      while (end < lines.length && (/^[ \t]/.test(lines[end]) || lines[end].trim() === "")) end++;
      const block = lines.slice(start + 1, end);
      const ti = block.findIndex((l) => /^\s*trustedHosts:/.test(l));
      if (ti >= 0) {
        let ei = ti + 1;
        while (ei < block.length && (/^[ \t]+- /.test(block[ei]) || block[ei].trim() === "")) ei++;
        block.splice(ti, ei - ti, ...newListLines);
      } else {
        block.push(...newListLines);
      }
      lines.splice(start + 1, end - start - 1, ...block);
    }
    try {
      const tmp = settingsFile + ".tmp-" + process.pid;
      writeFileSync(tmp, lines.join(nl) + nl, "utf8");
      renameSync(tmp, settingsFile);
      return null;
    } catch (err) {
      return "settings.yaml 写入失败：" + (err?.message || String(err));
    }
  };

  reg({
    kind: "exact",
    path: "/api/remote-access/trusted-hosts",
    handler: async (req, res) => {
      if (req.method === "GET") {
        json(res, 200, { ok: true, hosts: readTrustedHosts(), settingsPath: settingsFile });
        return;
      }
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const action = String(body.action || "");
      if (action !== "add" && action !== "remove") {
        return json(res, 200, { ok: false, error: "action 必须是 add 或 remove" });
      }
      const host = String(body.host || "").trim();
      const invalid = validateAuthority(host);
      if (invalid) return json(res, 200, { ok: false, error: invalid });
      const current = readTrustedHosts();
      const next =
        action === "remove"
          ? current.filter((h) => h.toLowerCase() !== host.toLowerCase())
          : [...current.filter((h) => h.toLowerCase() !== host.toLowerCase()), host];
      const werr = writeTrustedHosts(next);
      if (werr) return json(res, 200, { ok: false, error: werr });
      log("trustedHosts 已更新：", next.join(", ") || "（空）");
      json(res, 200, { ok: true, hosts: next, note: "重启 DSH 后生效" });
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
  // 引导通道豁免仅限「手机侧引导端点」白名单（v2.1.0 起收紧）：
  // pair/request、pair/status、pair/check、pair/code/verify、host.describe。
  // pair/respond、pair/list、pair/revoke、pair/code/generate 不豁免——本机设置页走
  // loopback 放行，远程一律要求通道 token（修复 v1.x 的 self-approve 漏洞）。
  const authExemptSet = new Set([
    "/api/remote-access/pair/request",
    "/api/remote-access/pair/status",
    "/api/remote-access/pair/check",
    "/api/remote-access/pair/code/verify",
    "/api/host.describe",
  ]);
  const authExempt = (path) => authExemptSet.has(path);
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
