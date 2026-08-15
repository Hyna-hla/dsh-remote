// dsh-remote-access · cpolar 供应模块（Windows）
// 职责：一键下载/安装 cpolar、注册引导、authtoken 管理、隧道进程启动与状态。
// 用户无需手动下载 cpolar：首次使用点「安装 cpolar」即从官网拉取官方 zip 并解压到插件目录。
import { execFile, spawn } from "node:child_process";
import { once } from "node:events";
import {
  createWriteStream,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
} from "node:fs";
import { homedir } from "node:os";
import { join, dirname } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

// ---- 常量 ----
export const CPOLAR_DOWNLOAD_URL =
  "https://www.cpolar.com/static/downloads/cpolar-stable-windows-amd64.zip";
export const CPOLAR_REGISTER_URL = "https://dashboard.cpolar.com/signup";
export const CPOLAR_TOKEN_URL = "https://dashboard.cpolar.com/get-started";

const DSH_HOME = process.env.DSH_HOME || join(homedir(), ".dsh");
export const CPOLAR_DIR = join(DSH_HOME, "remote-access", "cpolar");
export const CPOLAR_EXE = join(CPOLAR_DIR, "cpolar.exe");
const CPOLAR_YML = join(homedir(), ".cpolar", "cpolar.yml");

// 兼容旧版：用户手工装到 E:\coplar 的情况仍可用
const LEGACY_EXE = "E:\\coplar\\cpolar.exe";

export const URL_RE = /Tunnel established at (https:\/\/[^\s"]+)/;

// ---- 安装状态机（供 UI 轮询进度）----
const installState = { status: "idle", progress: 0, message: "" };
let installPromise = null;

// findCpolar 短缓存：设置页轮询时避免反复 spawn 进程
let cpolarCache = { at: 0, value: null };

// ---- 二进制定位 ----
function exists(path) {
  try {
    return existsSync(path);
  } catch {
    return false;
  }
}

/** 在目录内递归找 cpolar.exe（zip 解压后可能在根或带一层子目录） */
function locateExeInDir(dir, depth = 0) {
  if (depth > 3) return null;
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return null;
  }
  for (const e of entries) {
    const full = join(dir, e);
    try {
      const st = statSync(full);
      if (st.isFile() && e.toLowerCase() === "cpolar.exe") return full;
      if (st.isDirectory()) {
        const found = locateExeInDir(full, depth + 1);
        if (found) return found;
      }
    } catch {}
  }
  return null;
}

/** 定位可用的 cpolar.exe：插件内置目录 → E:\coplar（旧）→ 系统 PATH */
export async function findCpolar() {
  if (Date.now() - cpolarCache.at < 30000) return cpolarCache.value;
  if (exists(CPOLAR_EXE)) {
    cpolarCache = { at: Date.now(), value: CPOLAR_EXE };
    return CPOLAR_EXE;
  }
  const located = locateExeInDir(CPOLAR_DIR);
  if (located) {
    cpolarCache = { at: Date.now(), value: located };
    return located;
  }
  if (exists(LEGACY_EXE)) {
    cpolarCache = { at: Date.now(), value: LEGACY_EXE };
    return LEGACY_EXE;
  }
  try {
    const { stdout } = await execFileAsync("where.exe", ["cpolar"]);
    const first = String(stdout).split(/\r?\n/)[0].trim();
    if (first) {
      cpolarCache = { at: Date.now(), value: first };
      return first;
    }
  } catch {}
  cpolarCache = { at: Date.now(), value: null };
  return null;
}

// ---- 下载 / 解压 ----
async function downloadZip(url, dest, onProgress) {
  const res = await fetch(url, {
    redirect: "follow",
    headers: { "User-Agent": "dsh-remote-access/1.0" },
  });
  if (!res.ok || !res.body) throw new Error(`下载失败 HTTP ${res.status}`);
  const total = Number(res.headers.get("content-length")) || 0;
  const out = createWriteStream(dest);
  const reader = res.body.getReader();
  let received = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.byteLength;
      out.write(Buffer.from(value));
      if (total) {
        // 下载占 0–90%，剩余 10% 留给解压
        onProgress(Math.min(90, Math.round((received / total) * 90)));
      }
    }
    out.end();
    await once(out, "finish");
  } catch (err) {
    out.destroy();
    throw err;
  }
}

async function extractZip(zip, destDir) {
  // 优先 PowerShell Expand-Archive；失败则回退到 Windows 自带 bsdtar
  const ps = `Expand-Archive -LiteralPath '${zip.replace(/'/g, "''")}' -DestinationPath '${destDir.replace(/'/g, "''")}' -Force`;
  try {
    await execFileAsync("powershell.exe", ["-NoProfile", "-Command", ps], { timeout: 180000 });
  } catch {
    await execFileAsync("tar.exe", ["-xf", zip, "-C", destDir], { timeout: 180000 });
  }
}

function doInstall() {
  installState.status = "downloading";
  installState.progress = 0;
  installState.message = "";
  const setProgress = (p) => {
    installState.progress = p;
  };

  return (async () => {
    try {
      mkdirSync(CPOLAR_DIR, { recursive: true });
      const zip = join(CPOLAR_DIR, "cpolar-download.zip");
      installState.message = "正在下载 cpolar…";
      await downloadZip(CPOLAR_DOWNLOAD_URL, zip, setProgress);

      installState.status = "extracting";
      installState.progress = 92;
      installState.message = "正在解压…";
      await extractZip(zip, CPOLAR_DIR);

      const exe = locateExeInDir(CPOLAR_DIR);
      if (!exe) throw new Error("解压后未找到 cpolar.exe");

      installState.status = "done";
      installState.progress = 100;
      installState.message = "";
      cpolarCache = { at: 0, value: exe }; // 清缓存，下次 findCpolar 重新探测
      return exe;
    } catch (err) {
      installState.status = "error";
      installState.message = err?.message || String(err);
      return null;
    } finally {
      try {
        rmSync(join(CPOLAR_DIR, "cpolar-download.zip"), { force: true });
      } catch {}
    }
  })();
}

/** 幂等安装：已在装/已装好则直接返回，不重复下载 */
export function installCpolar() {
  if (installPromise) return installPromise;
  if (exists(CPOLAR_EXE) || locateExeInDir(CPOLAR_DIR)) {
    installState.status = "done";
    installState.progress = 100;
    installState.message = "";
    return Promise.resolve(CPOLAR_EXE);
  }
  installPromise = doInstall().finally(() => {
    installPromise = null;
  });
  return installPromise;
}

// ---- 版本 / 登录态 ----
export async function getCpolarVersion(exe) {
  try {
    const { stdout } = await execFileAsync(exe, ["version"], { timeout: 10000 });
    const line = String(stdout).split(/\r?\n/).map((s) => s.trim()).filter(Boolean)[0];
    return line || null;
  } catch {
    return null;
  }
}

/** 读取 ~/.cpolar/cpolar.yml 判断是否已配置 authtoken 及账号邮箱 */
export function getAuth() {
  try {
    const text = readFileSync(CPOLAR_YML, "utf8");
    const authed = /^\s*authtoken\s*:\s*\S+/m.test(text);
    const email = (text.match(/^\s*email\s*:\s*(.+)$/m) || [])[1]?.trim() || null;
    return { authed, email: email || null };
  } catch {
    return { authed: false, email: null };
  }
}

/** 写入 authtoken（cpolar 会把 token 保存到 ~/.cpolar/cpolar.yml） */
export async function setAuthtoken(exe, token) {
  const t = String(token || "").trim();
  if (!/^\S{10,}$/.test(t)) throw new Error("token 格式不正确（应为 cpolar 后台的认证 token）");
  await execFileAsync(exe, ["authtoken", t], { timeout: 20000 });
}

/** 汇总 cpolar 供应状态（供 /cpolar/status 返回） */
export async function cpolarStatus() {
  const exe = await findCpolar();
  const installed = Boolean(exe);
  const version = installed ? await getCpolarVersion(exe) : null;
  const auth = getAuth();
  return {
    installed,
    installStatus: installState.status,
    installProgress: installState.progress,
    installMessage: installState.message,
    exePath: exe,
    version,
    authed: auth.authed,
    email: auth.email,
    registerUrl: CPOLAR_REGISTER_URL,
    tokenUrl: CPOLAR_TOKEN_URL,
  };
}

// ---- 隧道进程 ----
export const tunnel = {
  status: "idle", // idle | starting | online | error
  url: null,
  port: null,
  pid: null,
  message: "",
  buffer: "",
};

export function stopTunnel() {
  if (tunnel.pid) {
    try {
      process.kill(tunnel.pid, "SIGTERM");
    } catch {}
    // Windows 上 cpolar 有 watchdog 子进程，再补一刀 taskkill
    try {
      execFile("taskkill.exe", ["/PID", String(tunnel.pid), "/T", "/F"]);
    } catch {}
    tunnel.pid = null;
  }
}

/** cpolar 进程输出尾部（去重截断），失败时附进错误信息便于排障 */
function bufferTail() {
  const lines = tunnel.buffer.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
  const uniq = [...new Set(lines)];
  return uniq.slice(-6).join(" | ").slice(-400) || "无输出";
}

/** 启动 HTTP 隧道，成功返回 { ok:true, url }，失败返回 { ok:false, error } */
export async function startTunnel(port) {
  stopTunnel();
  const cpolar = await findCpolar();
  if (!cpolar) return { ok: false, error: "未安装 cpolar，请先点「安装 cpolar」" };
  if (!getAuth().authed) return { ok: false, error: "cpolar 尚未登录，请先注册并设置 authtoken" };

  tunnel.status = "starting";
  tunnel.url = null;
  tunnel.port = port;
  tunnel.message = "正在建立隧道…";
  tunnel.buffer = "";

  const args = ["http", String(port), `-host-header=localhost:${port}`, "-region=cn", "--log=stdout"];
  const child = spawn(cpolar, args, { stdio: ["ignore", "pipe", "pipe"], detached: true });
  tunnel.pid = child.pid;
  try {
    child.unref();
  } catch {}

  const collect = (chunk) => {
    tunnel.buffer = (tunnel.buffer + chunk.toString("utf8")).slice(-20000);
    const m = tunnel.buffer.match(URL_RE);
    if (m && !tunnel.url) {
      tunnel.url = m[1];
      tunnel.status = "online";
      tunnel.message = "";
    }
  };
  child.stdout.on("data", collect);
  child.stderr.on("data", collect);
  child.on("exit", (code) => {
    if (tunnel.pid === child.pid) {
      tunnel.pid = null;
      if (tunnel.status !== "online") {
        tunnel.status = "error";
        tunnel.message = `cpolar 进程退出（code ${code}）：${bufferTail()}`;
      }
    }
  });
  child.on("error", (err) => {
    tunnel.status = "error";
    tunnel.message = `启动失败: ${err.message}`;
  });

  const deadline = Date.now() + 25000;
  while (Date.now() < deadline && !tunnel.url) {
    await new Promise((r) => setTimeout(r, 400));
    if (tunnel.status === "error") break;
  }
  if (!tunnel.url && tunnel.status !== "error") {
    tunnel.status = "error";
    tunnel.message = `等待隧道超时（25s）：${bufferTail()}。请确认 cpolar 已登录：cpolar authtoken <token>`;
  }
  return tunnel.url ? { ok: true, url: tunnel.url, port } : { ok: false, error: tunnel.message };
}
