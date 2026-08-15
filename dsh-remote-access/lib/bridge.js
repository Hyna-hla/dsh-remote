// bridge.js — 微信 ↔ DSH 会话桥（宿主半）
// 微信消息 → 专用 DSH 会话（session.prompt）→ 流式回复回传微信；审批经微信回复处理
import { randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

const FLUSH_SOFT_LIMIT = 1200; // 流式攒块软上限（低于 4000 分片线，留余量）
const FLUSH_IDLE_MS = 600; // 攒块节流
const APPROVAL_TIMEOUT_MS = 30 * 60 * 1000;

function dshDataDir() {
  const home = process.env.DSH_HOME || join(homedir(), ".dsh");
  return join(home, "remote-access");
}

function sniffImage(buf) {
  if (buf.length > 8 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e) return "image/png";
  if (buf.length > 3 && buf[0] === 0xff && buf[1] === 0xd8) return "image/jpeg";
  if (buf.length > 6 && (buf.slice(0, 6).toString("ascii") === "GIF87a" || buf.slice(0, 6).toString("ascii") === "GIF89a")) return "image/gif";
  if (buf.length > 12 && buf.slice(8, 12).toString("ascii") === "WEBP") return "image/webp";
  return null;
}

const APPROVAL_RE = /^(同意|拒绝|允许|批准|y|yes|n|no)\b[:\s，。:：]*([0-9a-fA-F]{6,})/i;

export function createBridge(ctx, { ilink, log = () => {} }) {
  // 按调用时惰性解析：插件 apply 时 apiProxy 往往尚未挂载（api-gateway 行依赖多、
  // 挂载晚于 webServer），一次性捕获会永久拿到 undefined，导致每条消息都报「apiProxy 服务不可用」。
  const getApiProxy = () => ctx.get("apiProxy");
  const cfgFile = join(dshDataDir(), "wx-config.json");

  const cfg = loadCfg();
  const reply = { active: false, buf: "", timer: null, user: null };
  const pendingApprovals = new Map(); // shortId → { finish, approvalId }
  let lastUser = null;
  let sessionError = "";
  let disposed = false;
  let turnTools = []; // 当前回合的工具名（去重后汇总，避免刷屏）
  let turnHasText = false;
  let toolNotified = false;
  let greeted = false;

  function loadCfg() {
    try {
      const parsed = JSON.parse(readFileSync(cfgFile, "utf8"));
      return { sessionId: parsed.sessionId ?? null, allowlist: Array.isArray(parsed.allowlist) ? parsed.allowlist : [] };
    } catch {
      return { sessionId: null, allowlist: [] };
    }
  }

  function saveCfg() {
    try {
      mkdirSync(dirname(cfgFile), { recursive: true });
      const tmp = `${cfgFile}.tmp`;
      writeFileSync(tmp, JSON.stringify(cfg));
      renameSync(tmp, cfgFile);
    } catch (err) {
      log("bridge: config save failed", err);
    }
  }

  async function callProxy(method, payload) {
    const apiProxy = getApiProxy();
    if (!apiProxy) throw new Error("apiProxy 服务不可用");
    if (typeof apiProxy.sessions?.[method] !== "function") {
      throw new Error(`apiProxy.sessions.${method} 不可用`);
    }
    const res = await apiProxy.sessions[method]({ rpcId: randomUUID(), payload });
    if (!res.result.ok) throw new Error(`${method} 失败: ${res.result.error?.code ?? "unknown"}`);
    return res.result.value;
  }

  // ---- DSH 会话 ----
  async function ensureSession() {
    if (cfg.sessionId) {
      try {
        await callProxy("rename", { sessionId: cfg.sessionId, title: "微信遥控" });
        return cfg.sessionId;
      } catch {
        // 会话可能已被删除 → 重新创建
        cfg.sessionId = null;
      }
    }
    const id = randomUUID();
    try {
      const created = await callProxy("create", { sessionId: id });
      cfg.sessionId = created.sessionId;
      saveCfg();
      try {
        await callProxy("rename", { sessionId: cfg.sessionId, title: "微信遥控" });
      } catch {}
      sessionError = "";
      return cfg.sessionId;
    } catch (err) {
      sessionError = err.message;
      throw err;
    }
  }

  async function prompt(text) {
    const sessionId = await ensureSession();
    await callProxy("prompt", { sessionId, mode: "queue", content: [{ type: "text", text }] });
  }

  async function promptImage(buf, caption) {
    const sessionId = await ensureSession();
    const mediaType = sniffImage(buf);
    if (!mediaType) {
      await prompt(caption || "（微信发来一张图片，但格式无法识别）");
      return;
    }
    const content = [{ type: "image", mediaType, data: buf.toString("base64"), name: `wx-${Date.now()}` }];
    if (caption) content.push({ type: "text", text: caption });
    await callProxy("prompt", { sessionId, mode: "queue", content });
  }

  // ---- 微信发送（节流攒块） ----
  function sendNow(text) {
    const user = lastUser;
    if (!user || !ilink.isOnline()) return Promise.resolve();
    return ilink.sendText(user, text).catch((err) => {
      log("bridge: send failed, retrying", err.message);
      return ilink.sendText(user, text).catch((err2) => log("bridge: resend failed", err2.message));
    });
  }

  function scheduleFlush() {
    if (reply.timer) return;
    reply.timer = setTimeout(() => {
      reply.timer = null;
      if (reply.buf) {
        const chunk = reply.buf;
        reply.buf = "";
        sendNow(chunk);
      }
    }, FLUSH_IDLE_MS);
  }

  function flushNow() {
    if (reply.timer) {
      clearTimeout(reply.timer);
      reply.timer = null;
    }
    if (reply.buf) {
      const chunk = reply.buf;
      reply.buf = "";
      sendNow(chunk);
    }
  }

  async function setTyping(on) {
    const user = lastUser;
    if (user && ilink.isOnline()) await ilink.setTyping(user, on);
  }

  function allowUser(userId) {
    if (cfg.allowlist.length > 0) return cfg.allowlist.includes(userId);
    const bound = ilink.statusInfo().boundUserId;
    return bound ? userId === bound : true;
  }

  // ---- 微信消息处理 ----
  async function handleMessage(msg) {
    const { userId, contextToken, items } = msg;
    if (!allowUser(userId)) {
      try {
        await ilink.sendText(userId, "此微信仅限机主使用（可在设置页配置白名单）。");
      } catch {}
      return;
    }
    lastUser = userId;

    const text = items.filter((i) => i.type === "text").map((i) => i.text).join("\n").trim();
    const image = items.find((i) => i.type === "image");
    const voice = items.find((i) => i.type === "voice");
    const file = items.find((i) => i.type === "file");

    // 审批回复
    if (text) {
      const m = APPROVAL_RE.exec(text);
      if (m) {
        const decision = m[1].toLowerCase();
        const label = m[2];
        const hit = [...pendingApprovals.keys()].find((k) => k.startsWith(label.toLowerCase()));
        if (hit) {
          const entry = pendingApprovals.get(hit);
          pendingApprovals.delete(hit);
          const approve = decision === "同意" || decision === "允许" || decision === "批准" || decision === "y" || decision === "yes";
          entry.finish(approve ? "allowed-once" : "rejected");
          sendNow(approve ? `✔ 已同意审批 [${hit}]` : `✘ 已拒绝审批 [${hit}]`);
          return;
        }
        sendNow("未找到对应的待审批请求。");
        return;
      }
    }

    // 简单命令
    if (text === "/状态" || text === "/status") {
      const st = ilink.statusInfo();
      const approvalCount = pendingApprovals.size;
      sendNow(
        [
          `微信连接: ${st.status}${st.accountId ? ` (${st.accountId})` : ""}`,
          `绑定会话: ${cfg.sessionId ?? "（未创建，发消息后自动创建）"}`,
          `待审批: ${approvalCount} 个`,
        ].join("\n")
      );
      return;
    }

    if (text === "/断开" || text === "/disconnect") {
      ilink.stop();
      sendNow("已断开微信连接。");
      return;
    }

    if (text === "/帮助" || text === "/help") {
      sendNow(
        [
          "📖 微信遥控命令：",
          "· 直接发文字 → 与 DSH 对话",
          "· 发图片 → 给 DSH 看图",
          "· 审批请求 → 回复「同意 xxxxxxxx」或「拒绝 xxxxxxxx」",
          "· /状态 → 查看连接状态",
          "· /断开 → 断开连接",
        ].join("\n")
      );
      return;
    }

    if (voice && !text) {
      if (voice.text) {
        await setTyping(true);
        await prompt(`（微信语音，已转文字）${voice.text}`).catch((err) => sendNow(`发送失败: ${err.message}`));
        return;
      }
      sendNow("收到语音，但未获取到转文字内容（v1 暂不支持语音解码）。");
      return;
    }

    if (file && !text && !image) {
      sendNow(`收到文件「${file.fileName ?? "未命名"}」——v1 暂不处理文件内容，请在电脑端查看或后续版本支持。`);
      return;
    }

    if (image && !text) {
      try {
        const buf = await ilink.downloadItem(image);
        await setTyping(true);
        await promptImage(buf, "（微信发来的图片）").catch((err) => sendNow(`发送失败: ${err.message}`));
      } catch (err) {
        sendNow(`图片下载失败: ${err.message}`);
      }
      return;
    }

    if (!text) {
      sendNow("收到消息，但暂不支持该类型。");
      return;
    }

    // 普通文本 → DSH
    try {
      await setTyping(true);
      await prompt(text);
    } catch (err) {
      sendNow(`发送到 DSH 失败: ${err.message}`);
    }
  }

  // ---- DSH 会话事件 → 微信回传 ----
  function onSessionEvent(session, event) {
    if (!cfg.sessionId || session.id !== cfg.sessionId) return;
    switch (event.type) {
      case "turn/start":
        reply.buf = "";
        reply.active = true;
        turnTools = [];
        turnHasText = false;
        toolNotified = false;
        break;
      case "assistant/chunk": {
        const chunk = event.data.chunk;
        if (chunk.type === "text-delta" && chunk.text) {
          turnHasText = true;
          reply.buf += chunk.text;
          if (reply.buf.length >= FLUSH_SOFT_LIMIT) flushNow();
          else scheduleFlush();
        }
        // reasoning-delta 刻意不回传（移动端只看结果）
        break;
      }
      case "tool/call":
        turnTools.push(event.data.name || "工具");
        if (!toolNotified) {
          toolNotified = true;
          sendNow("🔧 正在执行任务…");
        }
        break;
      case "turn/end": {
        flushNow();
        reply.active = false;
        const reason = event.data.reason;
        if (reason?.kind === "error") {
          sendNow("⚠ 回合出错: " + (reason.error?.message ?? "未知错误"));
        } else if (turnTools.length > 0 && !turnHasText) {
          // 无文字输出的纯工具回合：汇总一次，避免逐条刷屏
          const uniq = [...new Set(turnTools)];
          const head = uniq.slice(0, 8);
          sendNow("🔧 已执行: " + head.join("、") + (uniq.length > head.length ? " 等 " + uniq.length + " 项" : ""));
        }
        setTyping(false);
        break;
      }
      default:
        break;
    }
  }

  // ---- 审批应答器（prepend，先于网关；仅接管绑定会话） ----
  function findApprovalId(req) {
    const events = req.agent?.session?.events;
    if (!Array.isArray(events)) return null;
    const decided = new Set();
    const claimed = new Set([...pendingApprovals.values()].map((e) => e.approvalId).filter(Boolean));
    for (let i = events.length - 1; i >= 0; i--) {
      const e = events[i];
      if (e.type === "approval/decided") decided.add(e.data.id);
      else if (e.type === "approval/asked") {
        if (decided.has(e.data.id) || claimed.has(e.data.id)) continue;
        if ((req.callId ?? null) !== (e.data.callId ?? null)) continue;
        return e.data.id;
      }
    }
    return null;
  }

  function onApprovalRequest(req, next) {
    if (!cfg.sessionId || req.agent?.session?.id !== cfg.sessionId) return next();
    if (!ilink.isOnline() || !lastUser) return next();
    const approvalId = findApprovalId(req) ?? randomUUID().replace(/-/g, "").slice(0, 12);
    const short = approvalId.slice(0, 8);
    const lines = [`🔐 审批请求 [${short}]`, `工具: ${req.toolName}`];
    if (req.reason) lines.push(`原因: ${req.reason}`);
    lines.push(`回复: 同意 ${short} 或 拒绝 ${short}（${Math.round(APPROVAL_TIMEOUT_MS / 60000)} 分钟内有效）`);
    sendNow(lines.join("\n"));

    return new Promise((resolve) => {
      let settled = false;
      const finish = (outcome) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        pendingApprovals.delete(short);
        resolve(outcome);
      };
      const timer = setTimeout(() => finish("unavailable"), APPROVAL_TIMEOUT_MS);
      pendingApprovals.set(short, { finish, approvalId });
      req.signal?.addEventListener("abort", () => finish("cancelled"), { once: true });
    });
  }

  // ---- 生命周期 ----
  const bridge = {
    async init() {
      ilink.handlers.message = handleMessage;
      ilink.handlers.status = (st) => {
        if (st.status === "online") {
          // 登录成功后把扫码者加入默认白名单（若白名单为空）
          if (cfg.allowlist.length === 0 && st.boundUserId) {
            cfg.allowlist = [st.boundUserId];
            saveCfg();
          }
          // 上线后主动问候一次（含快捷命令提示）
          if (!greeted && st.boundUserId) {
            greeted = true;
            ilink.sendText(
              st.boundUserId,
              "✅ 微信遥控已连接。发文字即可对话，发图片给 DSH 看图；审批请求回复「同意/拒绝 xxxxxxxx」；发 /帮助 查看命令。",
            ).catch(() => {});
          }
        }
      };
      ilink.resume(); // 有凭证则自动上线
    },

    getStatus() {
      return {
        ...ilink.statusInfo(),
        sessionId: cfg.sessionId,
        sessionError,
        pendingApprovals: pendingApprovals.size,
        allowlist: [...cfg.allowlist],
      };
    },

    async login(verifyCode) {
      return ilink.startLogin({ verifyCode });
    },

    async stop() {
      ilink.stop();
      return { ok: true };
    },

    async reset() {
      ilink.clearAll();
      return ilink.startLogin();
    },

    dispose() {
      disposed = true;
      ilink.stop();
      if (reply.timer) clearTimeout(reply.timer);
      for (const entry of pendingApprovals.values()) entry.finish("cancelled");
      pendingApprovals.clear();
    },
  };

  return { bridge, onSessionEvent, onApprovalRequest };
}
