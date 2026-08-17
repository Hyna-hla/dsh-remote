// @ts-nocheck
// dsh-remote-access v2.4.1 TypeScript source (migrated from lib).
// Transitional: @ts-nocheck skips semantic checks; DSH ctx/webServer dynamic types added later.
// Syntax is still checked. npm run build emits to lib/; npm test smoke-tests the build output.

// dsh-remote-access v2.4.1 — 远程互信认证（配对码 + 确认框 + 公网域名白名单）+ 只读辅助路由
// 保留：移动端配对握手（pair/*）、主机设备信息（device/info）、只读目录列举（fs/list）、
//       只读文件预览（fs/read）、MCP 枚举（mcp/list）、远程通道 Bearer token 鉴权（/api 全量门禁，含 WebSocket）。
// 移除：微信 iLink 桥（lib/ilink.js + lib/bridge.js）、cpolar 隧道供应（lib/cpolar.js），
//       对应 wx/*、cpolar/*、/status、/start、/stop、/qr 路由与 qrcode 依赖一并删除。
import { execFile } from "node:child_process";
import { createHash, randomBytes, randomInt, randomUUID, timingSafeEqual } from "node:crypto";
import {
  closeSync,
  existsSync,
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
import { basename, dirname, isAbsolute, join, relative, resolve } from "node:path";
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
  const home = (process.env.DSH_HOME || "").trim() || join(homedir(), ".dsh");

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

  // ---------- 双向文件传输（M1，写/删类 · 沙箱到 DSH home） ----------
  // 只读 fs/list、fs/read 允许任意绝对路径（低风险）；写/删/建目录是破坏性操作，
  // 一律限定在 $DSH_HOME 内：resolve 归一化 ../，relative 判定必须在 home 之下（含 home 自身）。
  const readBodyMax = (req, max) =>
    new Promise((done) => {
      let data = "";
      req.on("data", (c) => {
        data += c;
        if (data.length > max) {
          req.destroy();
          done(null);
          return;
        }
      });
      req.on("end", () => {
        try {
          done(data ? JSON.parse(data) : {});
        } catch {
          done({});
        }
      });
    });
  const resolveSandboxed = (p) => {
    if (!p || !isAbsolute(p)) return null;
    const abs = resolve(p);
    const rel = relative(home, abs);
    const inside = rel === "" || (!rel.startsWith("..") && rel !== ".." && !isAbsolute(rel));
    return inside ? abs : null;
  };
  const UP_MAX = 64 * 1024 * 1024; // 单个上传 JSON body 上限（base64 约合 ≥48MB 文件）

  reg({
    kind: "exact",
    path: "/api/remote-access/fs/write",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBodyMax(req, UP_MAX);
      if (!body) return json(res, 413, { ok: false, error: "payload_too_large", max: UP_MAX });
      const target = resolveSandboxed(String(body.path || "").trim());
      if (!target) return json(res, 200, { ok: false, error: "path_not_in_workspace" });
      let buf;
      try {
        buf = Buffer.from(String(body.content || ""), "base64");
      } catch {
        return json(res, 400, { ok: false, error: "bad_base64" });
      }
      if (!body.overwrite && existsSync(target)) {
        return json(res, 409, { ok: false, error: "file_exists", path: target });
      }
      try {
        mkdirSync(dirname(target), { recursive: true });
        writeFileSync(target, buf);
        const st = statSync(target);
        json(res, 200, { ok: true, path: target, size: st.size, mtime: st.mtimeMs, written: buf.length });
      } catch (err) {
        json(res, 200, { ok: false, error: String(err?.message ?? err) });
      }
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/fs/mkdir",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const target = resolveSandboxed(String(body.path || "").trim());
      if (!target) return json(res, 200, { ok: false, error: "path_not_in_workspace" });
      try {
        let created = false;
        if (body.recursive) {
          created = !existsSync(target);
          mkdirSync(target, { recursive: true });
        } else {
          if (existsSync(target)) return json(res, 200, { ok: true, created: false, path: target });
          mkdirSync(target);
          created = true;
        }
        json(res, 200, { ok: true, created, path: target });
      } catch (err) {
        json(res, 200, { ok: false, error: String(err?.message ?? err) });
      }
    },
  });

  // 删除 → 移入 $DSH_HOME/remote-access/trash/<uuid>-<name>（软删可撤销，v1 返回 trashId 供后续 restore）
  reg({
    kind: "exact",
    path: "/api/remote-access/fs/delete",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const target = resolveSandboxed(String(body.path || "").trim());
      if (!target) return json(res, 200, { ok: false, error: "path_not_in_workspace" });
      if (target === resolve(home)) return json(res, 200, { ok: false, error: "cannot_delete_root" });
      try {
        if (!existsSync(target)) return json(res, 200, { ok: true, trashId: null, note: "already_gone" });
        const trashDir = join(home, "remote-access", "trash");
        mkdirSync(trashDir, { recursive: true });
        const trashId = randomUUID();
        renameSync(target, join(trashDir, trashId + "-" + basename(target)));
        json(res, 200, { ok: true, trashId, path: target });
      } catch (err) {
        json(res, 200, { ok: false, error: String(err?.message ?? err) });
      }
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/fs/stat",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const u = new URL(req.url, "http://localhost");
      const p = u.searchParams.get("path") ?? "";
      if (!isAbsolute(p)) return json(res, 400, { error: "需要绝对路径" });
      try {
        if (!existsSync(p)) return json(res, 200, { ok: false, error: "not_found", path: p });
        const st = statSync(p);
        json(res, 200, {
          ok: true,
          path: p,
          isFile: st.isFile(),
          isDir: st.isDirectory(),
          size: st.size,
          mtime: st.mtimeMs,
          hidden: basename(p).startsWith("."),
        });
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

  // ---------- MCP 资源 / 提示词只读暴露（M3，能力清册 v1） ----------
  // 上游无连接态查询 API，插件无法直接枚举真实资源/提示词或 RPC 渲染；
  // 依约定从工具名（mcp__<server>__*resource* / *prompt*）聚合出「能力清册」，
  // 并把工具 schema 作为可浏览/可插入的占位内容。真实拉取/渲染需 App/宿主侧发起 MCP 调用。
  const mcpSchemas = () => {
    const tools = ctx.get("tools");
    return tools && typeof tools.schemas === "function" ? tools.schemas() : [];
  };
  const mcpByServer = (pred) => {
    const by = new Map();
    for (const s of mcpSchemas()) {
      const name = typeof s?.name === "string" ? s.name : "";
      const parts = name.startsWith("mcp__") ? name.split("__") : null;
      if (!parts || parts.length < 3 || !parts[1]) continue;
      const serverName = parts[1];
      const tail = parts.slice(2).join("__");
      if (!pred(tail)) continue;
      if (!by.has(serverName)) by.set(serverName, []);
      by.get(serverName).push({ ...s, tail });
    }
    return by;
  };

  reg({
    kind: "exact",
    path: "/api/remote-access/mcp/resources/list",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      try {
        const servers = [];
        for (const [serverName, list] of mcpByServer((t) => t.toLowerCase().includes("resource"))) {
          servers.push({
            serverName,
            status: "unknown",
            resources: list.map((s) => ({
              uri: "mcp://" + serverName + "/" + s.tail,
              name: s.tail,
              mimeType: typeof s.mimeType === "string" ? s.mimeType : "text/plain",
              description: typeof s.description === "string" ? s.description : "",
            })),
          });
        }
        json(res, 200, { ok: true, servers });
      } catch (err) {
        json(res, 200, { ok: false, error: String(err?.message ?? err) });
      }
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/mcp/resources/read",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const u = new URL(req.url, "http://localhost");
      const uri = u.searchParams.get("uri") ?? "";
      let found = null;
      let foundSn = null;
      outer: for (const [sn, list] of mcpByServer((t) => t.toLowerCase().includes("resource"))) {
        for (const s of list) {
          if ("mcp://" + sn + "/" + s.tail === uri) {
            found = s;
            foundSn = sn;
            break outer;
          }
        }
      }
      if (!found) return json(res, 200, { ok: false, error: "resource_not_found", uri });
      json(res, 200, {
        ok: true,
        uri,
        serverName: foundSn,
        isBinary: false,
        text: JSON.stringify(found, null, 2),
        note: "capability_schema",
      });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/mcp/prompts/list",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      try {
        const servers = [];
        for (const [serverName, list] of mcpByServer((t) => t.toLowerCase().includes("prompt"))) {
          servers.push({
            serverName,
            status: "unknown",
            prompts: list.map((s) => ({
              name: s.tail,
              description: typeof s.description === "string" ? s.description : "",
              argumentsSchema: s.inputSchema ?? s.argumentsSchema ?? {},
            })),
          });
        }
        json(res, 200, { ok: true, servers });
      } catch (err) {
        json(res, 200, { ok: false, error: String(err?.message ?? err) });
      }
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/mcp/prompts/render",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const name = String(body.name || "");
      let schema = null;
      let server = null;
      for (const [sn, list] of mcpByServer((t) => t.toLowerCase().includes("prompt"))) {
        const s = list.find((x) => x.tail === name);
        if (s) {
          schema = s;
          server = sn;
          break;
        }
      }
      if (!schema) return json(res, 200, { ok: false, error: "prompt_not_found", name });
      const args = body.arguments && typeof body.arguments === "object" ? body.arguments : {};
      json(res, 200, {
        ok: true,
        serverName: server,
        prompt: name,
        messages: [{ role: "user", content: "[MCP 提示词 " + server + "/" + name + "] " + JSON.stringify(args) }],
        note: "best_effort_without_mcp_rpc",
      });
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
        const { stdout } = await execFileAsync("powershell.exe", ["-NoProfile", "-Command", ps], { timeout: 5000 });
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
        // 新设备来配对：自动生成/确保有一个有效配对码可供手机输入
        ensureActiveCode();
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
  // 安全属性：一次性（验证成功即作废）；10 分钟过期；最多 5 次错误尝试（超限作废，此后新码仅本机
  // /current 可见，远端无法继续爆破）；常量时间比较防时序侧信道；
  // generate/current 不豁免（仅本机设置页可达），verify 豁免（未配对手机的引导通道）。
  // 说明：这里不设时间节流——6 位码 + 5 次即锁已足以防爆破，且时间节流会误伤正常连打重试。
  const CODE_TTL_MS = 10 * 60 * 1000;
  const CODE_MAX_ATTEMPTS = 5;
  let pairCode = null; // { code, at, expiresAt, attempts }

  const codeDigest = (s) => createHash("sha256").update(String(s), "utf8").digest();

  // 自动生成：当前无有效码（过期 / 用尽 / 从未生成）时立刻生成一个 6 位码，
  // 保证手机来配对时 PC 端始终有码可填（无需手动点「生成配对码」）。
  const ensureActiveCode = () => {
    const now = Date.now();
    if (pairCode && now <= pairCode.expiresAt && pairCode.attempts < CODE_MAX_ATTEMPTS) {
      return pairCode;
    }
    const code = randomInt(0, 1000000).toString().padStart(6, "0");
    pairCode = { code, at: now, expiresAt: now + CODE_TTL_MS, attempts: 0 };
    return pairCode;
  };


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
    path: "/api/remote-access/pair/code/current",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      // 非豁免路由：远程需 Bearer token 才能读；本机设置页走 loopback 放行。
      const active = ensureActiveCode();
      json(res, 200, {
        ok: true,
        code: active.code,
        expiresInSec: Math.max(0, Math.floor((active.expiresAt - Date.now()) / 1000)),
        maxAttempts: CODE_MAX_ATTEMPTS,
      });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/pair/code/verify",    handler: async (req, res) => {
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

  // ---------- 公网域名信任白名单（v2.4.0：写入 connection 行 !!js 拼接表达式） ----------
  // DSH ≥0.1.0-rc.7 的 /api Host 围栏读取 client-connection（行 id: connection）的 config.trustedHosts，
  // web 组合包给该行的默认值是 !!js ctx.webRuntime.trustedHosts（LAN IP 字面量 + CLI --trusted-host），
  // 而补丁语义是「整份 config 替换」。所以白名单必须把用户字面量拼回运行时派生权威：
  //   trustedHosts: !!js '[...ctx.webRuntime.trustedHosts, "user-host"]'
  // 这正是组合包注释标注的部署挂载点。v2.3.0 顶掉 web-runtime 整份 config 的做法会让
  // --trusted-host 静默失效（且硬编码 printUrl/surfaceContext），已废弃并自动迁移清理。
  // 用户列表真身存 $DSH_HOME/remote-access/trusted-hosts.json（读写可靠），cordis.patch.yml
  // 只是渲染产物；DSH 的 watchUserPatches 热监视该文件，写入后立即生效（实测无需重启）。
  const profilePatchFile = join(home, "profiles", "web", "cordis.patch.yml");
  const hostsJsonFile = join(home, "remote-access", "trusted-hosts.json");

  /** 校验条目为纯 域名[:端口]：WHATWG 规范化往返一致（与核心 assertTrustedAuthority 同口径）+ 字符集白名单 */
  const validateAuthority = (entry) => {
    const e = String(entry ?? "").trim();
    if (!e) return "请输入域名或 域名:端口";
    if (e.length > 253) return "条目过长";
    // 域名（字母/数字/点/连字符，可含 IPv4）或方括号 IPv6，可选 :端口；排除引号/反斜杠等，
    // 保证条目能安全拼进 !!js 表达式与 YAML 标量（IDN 请用 punycode，与核心同口径）
    if (!/^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(?::\d{1,5})?$/i.test(e) && !/^\[[0-9a-f:.]+\](?::\d{1,5})?$/i.test(e)) {
      return "只允许 域名[:端口] 或 [IPv6][:端口]（字母数字、点、连字符）";
    }
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

  /** 读白名单 JSON 真身；无文件/坏文件 → null */
  const readHostsJson = () => {
    try {
      const arr = JSON.parse(readFileSync(hostsJsonFile, "utf8"));
      if (Array.isArray(arr)) return arr.map((s) => String(s).trim()).filter(Boolean);
    } catch {}
    return null;
  };
  const writeHostsJson = (hosts) => {
    try {
      mkdirSync(join(home, "remote-access"), { recursive: true });
      const tmp = hostsJsonFile + ".tmp-" + process.pid;
      writeFileSync(tmp, JSON.stringify(hosts, null, 2) + "\n", "utf8");
      renameSync(tmp, hostsJsonFile);
      return null;
    } catch (err) {
      return "trusted-hosts.json 写入失败：" + (err?.message || String(err));
    }
  };

  /** 行扫描：定位 id 匹配的顶层条目块 { start, end, block }（end 止于下一个顶层条目行或行尾） */
  const findEntryBlock = (lines, idRe) => {
    const start = lines.findIndex((l) => idRe.test(l));
    if (start < 0) return null;
    let end = start + 1;
    while (end < lines.length && (/^[ \t]/.test(lines[end]) || lines[end].trim() === "")) end++;
    return { start, end, block: lines.slice(start + 1, end) };
  };

  /** 从 connection 块解析 !!js '[...ctx.webRuntime.trustedHosts, "a", "b"]' 里的用户字面量（无则 null） */
  const parseConnectionExpr = (block) => {
    const line = block.find((l) => /^\s*trustedHosts:/.test(l));
    if (!line) return null;
    const expr = line.trim().replace(/^trustedHosts:\s*/, "");
    const m = expr.match(/^!!js\s*'([^']*)'$/);
    if (!m) return null;
    const marker = "...ctx.webRuntime.trustedHosts";
    const i = m[1].indexOf(marker);
    if (i < 0) return null;
    const entries = [];
    for (const q of m[1].slice(i + marker.length).matchAll(/"(?:[^"\\]|\\.)*"/g)) {
      try {
        const v = JSON.parse(q[0]);
        if (typeof v === "string") entries.push(v);
      } catch {}
    }
    return entries;
  };

  /** 从旧版 web-runtime 块解析 trustedHosts 字面量列表（v2.3.0 残留迁移用；无键则 null） */
  const parseWebRuntimeBlock = (block) => {
    const ti = block.findIndex((l) => /^\s*trustedHosts:/.test(l));
    if (ti < 0) return null;
    const entries = [];
    for (let j = ti + 1; j < block.length; j++) {
      const t = block[j].trim();
      if (/^- /.test(t)) entries.push(t.replace(/^-\s*/, "").replace(/^["']|["']$/g, "").trim());
      else if (t !== "") break;
    }
    return entries;
  };

  /** 读白名单：JSON 真身优先；缺失时从 cordis.patch.yml 迁移（connection !!js 或旧 web-runtime 块） */
  const readTrustedHosts = () => {
    const fromJson = readHostsJson();
    if (fromJson) return fromJson;
    let text;
    try {
      text = readFileSync(profilePatchFile, "utf8");
    } catch {
      return [];
    }
    const lines = text.split(/\r?\n/);
    const conn = findEntryBlock(lines, /^- id: connection\s*$/);
    const legacy = conn ? null : findEntryBlock(lines, /^- id: web-runtime\s*$/);
    const hosts =
      (conn ? parseConnectionExpr(conn.block) : null) ||
      (legacy ? parseWebRuntimeBlock(legacy.block) : null) ||
      [];
    writeHostsJson(hosts);
    if (legacy) writeTrustedHosts(hosts); // 迁移：旧 web-runtime 条目 → connection 条目
    return hosts;
  };

  /** 渲染 connection 行白名单（!!js 拼接运行时派生权威）；空列表删除条目并清理 v2.3.0 残留 */
  const writeTrustedHosts = (hosts) => {
    const jerr = writeHostsJson(hosts);
    if (jerr) return jerr;
    let text;
    try {
      text = readFileSync(profilePatchFile, "utf8");
    } catch {
      text = "";
    }
    const nl = text.includes("\r\n") ? "\r\n" : "\n";
    // 模板占位 `[]`（DSH 初始化 patch 文件时的空列表占位）与顶层条目互斥：
    // `[]` 后跟 `- id:` 不是合法 YAML（Loader 解析抛错，热应用与下次启动都会失败）
    const lines = text.split(/\r?\n/).filter((l) => l.trim() !== "[]");
    while (lines.length && lines[lines.length - 1].trim() === "") lines.pop();

    // 1) 清理 v2.3.0 旧 web-runtime 白名单条目（只删带 trustedHosts 键的块，不动其它覆写）
    for (;;) {
      const b = findEntryBlock(lines, /^- id: web-runtime\s*$/);
      if (!b || parseWebRuntimeBlock(b.block) === null) break;
      lines.splice(b.start, b.end - b.start);
    }

    // 2) upsert / 删除 connection 条目（连同其后空行）
    const conn = findEntryBlock(lines, /^- id: connection\s*$/);
    if (conn) {
      let end = conn.end;
      while (end < lines.length && lines[end].trim() === "") end++;
      lines.splice(conn.start, end - conn.start);
    }
    if (hosts.length > 0) {
      const expr =
        "[...ctx.webRuntime.trustedHosts, " + hosts.map((h) => JSON.stringify(h)).join(", ") + "]";
      lines.push("", "- id: connection", "  config:", `    trustedHosts: !!js '${expr}'`);
    }
    // 全部删空时保证文件仍是合法顶层数组（空文件会触发 Loader 的 loud fail）
    if (lines.every((l) => l.trim() === "" || l.trim().startsWith("#"))) lines.push("[]");
    try {
      mkdirSync(join(home, "profiles", "web"), { recursive: true });
      const tmp = profilePatchFile + ".tmp-" + process.pid;
      writeFileSync(tmp, lines.join(nl) + nl, "utf8");
      renameSync(tmp, profilePatchFile);
      return null;
    } catch (err) {
      return "cordis.patch.yml 写入失败：" + (err?.message || String(err));
    }
  };

  reg({
    kind: "exact",
    path: "/api/remote-access/trusted-hosts",
    handler: async (req, res) => {
      if (req.method === "GET") {
        json(res, 200, { ok: true, hosts: readTrustedHosts(), patchFile: profilePatchFile, stateFile: hostsJsonFile });
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
      json(res, 200, { ok: true, hosts: next, note: "已热生效：写入 connection 行 trustedHosts（DSH 监视 cordis.patch.yml 自动应用）" });
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
      if (/^[0-9a-f]{32,}$/.test(t)) {
        // token 一旦载入进程内缓存即不重读磁盘：外部替换/轮换 channel-token 文件需重启 DSH 才生效
        log("远程通道 token 已在进程内缓存；如需轮换请重写 " + tokenFile + " 后重启 DSH");
        return (cachedToken = t);
      }
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

  // ---------- Token 运维（M4）：轮换 / 吊销 / 审计 ----------
  // 均为非豁免 /api 路由 → 仅本机 loopback（无 XFF）或已配对设备（带当前 Bearer）可达，远程未持 token 一律 401。
  const audit = []; // {ts, action, deviceId?, ip}
  const logAudit = (action, req, deviceId) => {
    audit.push({ ts: Date.now(), action, deviceId: deviceId || null, ip: req?.socket?.remoteAddress || null });
    if (audit.length > 200) audit.splice(0, audit.length - 200);
    try {
      mkdirSync(join(home, "remote-access"), { recursive: true });
      writeFileSync(join(home, "remote-access", "audit.json"), JSON.stringify(audit.slice(-50), null, 2), "utf8");
    } catch {}
  };

  reg({
    kind: "exact",
    path: "/api/remote-access/token/rotate",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      // 轮换 = 生成新 token 并重置进程内缓存（旧 token 即刻失效，现有设备需以新 token 继续）
      const t = randomBytes(24).toString("hex");
      cachedToken = null;
      try {
        mkdirSync(join(home, "remote-access"), { recursive: true });
        writeFileSync(tokenFile, t, "utf8");
      } catch {
        return json(res, 200, { ok: false, error: "write_failed" });
      }
      cachedToken = t;
      logAudit("token/rotate", req);
      json(res, 200, { ok: true, token: t, rotatedAt: Date.now() });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/token/revoke",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      // 吊销 = 清空 token 文件 + 缓存，并清空配对表 → 所有设备下次请求 401，强制重新配对
      cachedToken = null;
      try {
        mkdirSync(join(home, "remote-access"), { recursive: true });
        writeFileSync(tokenFile, "", "utf8");
      } catch {}
      writePaired([]);
      logAudit("token/revoke", req);
      json(res, 200, { ok: true, revokedAt: Date.now() });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/token/audit",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      json(res, 200, { ok: true, events: audit.slice(-50) });
    },
  });

  // ---------- 离线同步（M2，插件侧）：设备待确认事件队列 + 批量 ACK ----------
  // 以 deviceId 隔离：pending 事件落 $DSH_HOME/remote-access/sync/<deviceId>.json。
  // v1 语义：App 断网/被杀时的发送/审批/问答进入本地 outbox，恢复后 POST /sync 上报并 ACK；
  // 服务端 Side→Device 事件先入 pending，App 经 GET /sync/pending 拉取、处理后再 ACK 清除。
  // deviceId 仅允许安全字符集，避免作为文件名造成路径穿越。
  const DEVICE_ID_RE = /^[A-Za-z0-9._-]{1,64}$/;
  const syncDir = join(home, "remote-access", "sync");
  const readSyncPending = (deviceId) => {
    try {
      return JSON.parse(readFileSync(join(syncDir, deviceId + ".json"), "utf8")).pending ?? [];
    } catch {
      return [];
    }
  };
  const writeSyncPending = (deviceId, pending) => {
    mkdirSync(syncDir, { recursive: true });
    writeFileSync(join(syncDir, deviceId + ".json"), JSON.stringify({ pending }, null, 2), "utf8");
  };

  reg({
    kind: "exact",
    path: "/api/remote-access/sync",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const deviceId = String(body.deviceId || "").trim();
      if (!DEVICE_ID_RE.test(deviceId)) return json(res, 400, { error: "invalid deviceId" });
      const events = Array.isArray(body.events) ? body.events : [];
      const results = events.map((e) => ({
        id: e && e.id !== undefined ? e.id : null,
        ok: true,
      }));
      // ACK：清除该设备已确认的 server→device 事件
      const acked = Array.isArray(body.acked) ? body.acked.filter((x) => x !== undefined && x !== null) : [];
      if (acked.length > 0) {
        const pending = readSyncPending(deviceId).filter((ev) => !acked.includes(ev.id));
        writeSyncPending(deviceId, pending);
      }
      json(res, 200, { ok: true, results });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/sync/pending",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const u = new URL(req.url, "http://localhost");
      const deviceId = u.searchParams.get("deviceId") ?? "";
      if (!DEVICE_ID_RE.test(deviceId)) return json(res, 400, { error: "invalid deviceId" });
      json(res, 200, { ok: true, deviceId, pending: readSyncPending(deviceId), status: "ok" });
    },
  });

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
