// dsh-remote-access：设置页「远程控制」——微信 iLink 桥（主要方式）+ cpolar 隧道（备选）
// 微信桥：扫码登录微信 iLink Bot → 微信里给自己发消息遥控 DSH（会话注入、流式回复、审批、图片）
// cpolar：网页版备选。内置一键安装（官网自动下载）、注册引导、authtoken 保存，无需手动装 cpolar
import { execFile } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
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

  // 目录浏览（移动端任选 PC 目录作工作区，不限于 DSH 已划定的工作区）：只读列举子目录，限 200 条
  reg({
    kind: "exact",
    path: "/api/remote-access/fs/list",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      const u = new URL(req.url, "http://localhost");
      const p = u.searchParams.get("path") ?? "";
      if (!isAbsolute(p)) return json(res, 400, { error: "需要绝对路径" });
      try {
        const dirs = readdirSync(p, { withFileTypes: true })
          .filter((d) => d.isDirectory())
          .slice(0, 200)
          .map((d) => d.name);
        json(res, 200, { ok: true, path: p, dirs });
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
  let pendingPair = null; // { deviceId, name, at, timer }

  const clearPending = () => {
    if (pendingPair?.timer) clearTimeout(pendingPair.timer);
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
        pendingPair = { deviceId, name: deviceName, at: Date.now(), timer: null };
        pendingPair.timer = setTimeout(() => { pendingPair = null; }, 120000);
      }
      json(res, 200, { ok: true, state: "pending" });
    },
  });

  reg({
    kind: "exact",
    path: "/api/remote-access/pair/status",
    handler: async (req, res) => {
      if (req.method !== "GET") return json(res, 405, { error: "method not allowed" });
      json(res, 200, { ok: true, state: pendingPair ? "pending" : "none", pendingDevice: pendingPair ?? null });
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
      clearPending();
      json(res, 200, { ok: true, state: outcome === "approve" ? "approved" : "denied" });
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
