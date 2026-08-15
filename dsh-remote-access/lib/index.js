// dsh-remote-access：设置页「远程控制」——微信 iLink 桥（主要方式）+ cpolar 隧道（备选）
// 微信桥：扫码登录微信 iLink Bot → 微信里给自己发消息遥控 DSH（会话注入、流式回复、审批、图片）
// cpolar：保留原有公网隧道（网页版备选，需 E:\coplar\cpolar.exe）
import { execFile, spawn } from "node:child_process";
import { existsSync, mkdtempSync } from "node:fs";
import { tmpdir, homedir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { ILinkClient } from "./ilink.js";
import { createBridge } from "./bridge.js";
import QRCode from "qrcode";

const execFileAsync = promisify(execFile);

export const name = "dsh-remote-access";
export const inject = ["webServer"];

const CPOLAR_CANDIDATES = ["E:\\coplar\\cpolar.exe", "cpolar"];
const URL_RE = /Tunnel established at (https:\/\/[^\s"]+)/;

/** cpolar 隧道运行状态（进程级，DSH 重启即重置，cpolar 隧道进程存活） */
const state = {
  status: "idle", // idle | starting | online | error
  url: null,
  port: null,
  pid: null,
  message: "",
  logPath: null,
  buffer: "",
};

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

// 探测结果短缓存：设置页轮询时不重复 spawn 进程，通道生成更快
let cpolarCache = { at: 0, value: null };
let dshPortCache = { at: 0, value: 0 };

async function findCpolar() {
  if (Date.now() - cpolarCache.at < 30000) return cpolarCache.value;
  for (const cand of CPOLAR_CANDIDATES) {
    if (cand.includes("\\") || cand.includes("/")) {
      if (existsSync(cand)) {
        cpolarCache = { at: Date.now(), value: cand };
        return cand;
      }
    } else {
      try {
        const { stdout } = await execFileAsync("where.exe", [cand]);
        const first = String(stdout).split(/\r?\n/)[0].trim();
        if (first) {
          cpolarCache = { at: Date.now(), value: first };
          return first;
        }
      } catch {}
    }
  }
  cpolarCache = { at: Date.now(), value: null };
  return null;
}

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

function stopTunnel() {
  if (state.pid) {
    try {
      process.kill(state.pid, "SIGTERM");
    } catch {}
    // Windows 上 cpolar 有 watchdog 子进程，再补一刀 taskkill
    try {
      execFile("taskkill.exe", ["/PID", String(state.pid), "/T", "/F"]);
    } catch {}
    state.pid = null;
  }
}

async function startTunnel(port) {
  stopTunnel();
  const cpolar = await findCpolar();
  if (!cpolar) return { ok: false, error: "未找到 cpolar（E:\\coplar\\cpolar.exe），请先安装并登录" };

  const logDir = mkdtempSync(join(tmpdir(), "dsh-remote-"));
  const logPath = join(logDir, "cpolar.log");
  state.status = "starting";
  state.url = null;
  state.port = port;
  state.message = "正在建立隧道…";
  state.buffer = "";
  state.logPath = logPath;

  const args = ["http", String(port), `-host-header=localhost:${port}`, "-region=cn", "--log=stdout"];
  const child = spawn(cpolar, args, { stdio: ["ignore", "pipe", "pipe"], detached: true });
  state.pid = child.pid;
  try {
    child.unref();
  } catch {}

  const collect = (chunk) => {
    state.buffer = (state.buffer + chunk.toString("utf8")).slice(-20000);
    const m = state.buffer.match(URL_RE);
    if (m && !state.url) {
      state.url = m[1];
      state.status = "online";
      state.message = "";
    }
  };
  child.stdout.on("data", collect);
  child.stderr.on("data", collect);
  child.on("exit", (code) => {
    if (state.pid === child.pid) {
      state.pid = null;
      if (state.status !== "online") {
        state.status = "error";
        state.message = `cpolar 进程退出（code ${code}），可能未登录 authtoken`;
      }
    }
  });
  child.on("error", (err) => {
    state.status = "error";
    state.message = `启动失败: ${err.message}`;
  });

  // 轮询等待 URL（最多 25 秒）
  const deadline = Date.now() + 25000;
  while (Date.now() < deadline && !state.url) {
    await new Promise((r) => setTimeout(r, 400));
    if (state.status === "error") break;
  }
  if (!state.url && state.status !== "error") {
    state.status = "error";
    state.message = "等待隧道超时（25s）。请确认 cpolar 已登录：cpolar authtoken <token>";
  }
  return state.url ? { ok: true, url: state.url, port } : { ok: false, error: state.message };
}

export const apply = (ctx) => {
  const webServer = ctx.webServer;
  const log = (...args) => console.error("[dsh-remote-access]", ...args);

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

  webServer.register({
    kind: "exact",
    path: "/api/remote-access/wx/status",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      json(res, 200, bridge.getStatus());
    },
  });

  webServer.register({
    kind: "exact",
    path: "/api/remote-access/wx/login",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const body = await readBody(req);
      const result = await bridge.login(body.verifyCode || undefined);
      json(res, 200, { ok: true, ...result });
    },
  });

  webServer.register({
    kind: "exact",
    path: "/api/remote-access/wx/stop",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      json(res, 200, await bridge.stop());
    },
  });

  webServer.register({
    kind: "exact",
    path: "/api/remote-access/wx/reset",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      const result = await bridge.reset();
      json(res, 200, { ok: true, ...result });
    },
  });

  // ---------- cpolar 隧道（备选） ----------
  webServer.register({
    kind: "exact",
    path: "/api/remote-access/status",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const cpolarFound = Boolean(await findCpolar());
      const dshPort = await findDshPort();
      json(res, 200, {
        status: state.status,
        url: state.url,
        port: state.port,
        message: state.message,
        cpolarFound,
        dshPort,
      });
    },
  });

  webServer.register({
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

  webServer.register({
    kind: "exact",
    path: "/api/remote-access/stop",
    handler: async (req, res) => {
      if (req.method !== "POST") return json(res, 405, { error: "method not allowed" });
      stopTunnel();
      state.status = "idle";
      state.url = null;
      state.message = "";
      json(res, 200, { ok: true });
    },
  });

  // 本地生成二维码（不出本机、无第三方依赖、秒出图）
  webServer.register({
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
};
