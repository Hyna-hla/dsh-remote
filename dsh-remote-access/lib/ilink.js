// ilink.js — 微信 iLink Bot API 客户端（零依赖，Node 20 可用）
// 协议依据：protocol-spec.md（从 Tencent/openclaw-weixin 与 corespeed-io/wechatbot 源码提取）
import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const FIXED_BASE = "https://ilinkai.weixin.qq.com";
const CDN_BASE = "https://novac2c.cdn.weixin.qq.com/c2c";
const APP_ID = "bot";
// iLink-App-ClientVersion = uint32 编码 0x00MMNNPP；2.0.1 → 0x020001 = 131073
const CLIENT_VERSION = String(((2 & 0xff) << 16) | ((0 & 0xff) << 8) | 1);
const CHANNEL_VERSION = "2.0.0";
const BOT_AGENT = "DSH-Remote/1.0.0";
const QR_LONG_POLL_MS = 35000;
const UPDATES_TIMEOUT_MS = 40000;
const UPLOAD_TIMEOUT_MS = 60000;
const MAX_TEXT_LENGTH = 4000;
const TICKET_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_QR_REFRESH = 3;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class ApiError extends Error {
  constructor(message, { ret, errcode, errmsg } = {}) {
    super(message);
    this.name = "ApiError";
    this.ret = ret;
    this.errcode = errcode;
    this.errmsg = errmsg;
  }
  get isSessionExpired() {
    return this.errcode === -14;
  }
}

// ---------- 请求头 ----------
function commonHeaders() {
  return { "iLink-App-Id": APP_ID, "iLink-App-ClientVersion": CLIENT_VERSION };
}

// X-WECHAT-UIN：随机 4 字节 → uint32(大端) → 十进制字符串 → base64(该字符串的 UTF-8)
function randomUin() {
  const u = randomBytes(4).readUInt32BE(0);
  return Buffer.from(String(u), "utf8").toString("base64");
}

function authHeaders(token) {
  return {
    ...commonHeaders(),
    "Content-Type": "application/json",
    AuthorizationType: "ilink_bot_token",
    Authorization: `Bearer ${token}`,
    "X-WECHAT-UIN": randomUin(),
  };
}

function baseInfo() {
  return { base_info: { channel_version: CHANNEL_VERSION, bot_agent: BOT_AGENT } };
}

// ---------- AES-128-ECB（标准 ECB，无 IV，PKCS7 由 final() 处理） ----------
export function aesEncrypt(key, data) {
  if (!Buffer.isBuffer(key) || key.length !== 16) throw new Error("AES key must be 16 bytes");
  const cipher = createCipheriv("aes-128-ecb", key, null);
  return Buffer.concat([cipher.update(data), cipher.final()]);
}

export function aesDecrypt(key, data) {
  if (!Buffer.isBuffer(key) || key.length !== 16) throw new Error("AES key must be 16 bytes");
  const decipher = createDecipheriv("aes-128-ecb", key, null);
  return Buffer.concat([decipher.update(data), decipher.final()]);
}

export function encryptedSize(rawSize) {
  return Math.ceil((rawSize + 1) / 16) * 16;
}

// 三种密钥编码格式：A=base64(原始16字节) B=base64(hex字符串) C=直接hex 32字符
export function decodeAesKey(s) {
  if (typeof s !== "string" || !s) throw new Error("AES key missing");
  if (/^[0-9a-fA-F]{32}$/.test(s)) return Buffer.from(s, "hex");
  const raw = Buffer.from(s, "base64");
  if (raw.length === 16) return raw;
  if (raw.length === 32 && /^[0-9a-fA-F]{32}$/.test(raw.toString("ascii"))) {
    return Buffer.from(raw.toString("ascii"), "hex");
  }
  throw new Error(`Cannot decode AES key: base64-decoded length is ${raw.length} (expected 16 or 32)`);
}

const encodeAesKeyHex = (key) => key.toString("hex");
const encodeAesKeyBase64 = (key) => Buffer.from(key.toString("hex"), "utf8").toString("base64");

// ---------- 长文本分片（4000 字符，段落→换行→空格→硬切） ----------
export function chunkText(text, limit = MAX_TEXT_LENGTH) {
  if (text.length <= limit) return [text];
  const chunks = [];
  let rest = text;
  while (rest.length > limit) {
    let cut = -1;
    const para = rest.lastIndexOf("\n\n", limit);
    if (para > limit * 0.3) cut = para;
    else {
      const nl = rest.lastIndexOf("\n", limit);
      if (nl > limit * 0.3) cut = nl;
      else {
        const sp = rest.lastIndexOf(" ", limit);
        cut = sp > limit * 0.3 ? sp : limit;
      }
    }
    chunks.push(rest.slice(0, cut));
    rest = rest.slice(cut).replace(/^\s+/, "");
  }
  if (rest) chunks.push(rest);
  return chunks;
}

// ---------- 入站消息解析 ----------
export function parseIncoming(wire) {
  if (!wire || wire.message_type !== 1) return null; // 只处理 USER 消息，2=BOT 回显过滤
  const items = [];
  for (const item of wire.item_list ?? []) {
    switch (item.type) {
      case 1:
        if (item.text_item) items.push({ type: "text", text: item.text_item.text ?? "" });
        break;
      case 2:
        if (item.image_item) {
          items.push({
            type: "image",
            media: item.image_item.media,
            thumb: item.image_item.thumb_media,
            aeskey: item.image_item.aeskey,
            url: item.image_item.url,
          });
        }
        break;
      case 3:
        if (item.voice_item) items.push({ type: "voice", media: item.voice_item.media, text: item.voice_item.text });
        break;
      case 4:
        if (item.file_item) {
          items.push({ type: "file", media: item.file_item.media, fileName: item.file_item.file_name, len: item.file_item.len });
        }
        break;
      case 5:
        if (item.video_item) items.push({ type: "video", media: item.video_item.media });
        break;
      default:
        items.push({ type: "unknown", raw: item });
        break;
    }
  }
  return {
    userId: wire.from_user_id,
    messageId: wire.message_id,
    contextToken: wire.context_token,
    createTimeMs: wire.create_time_ms,
    items,
  };
}

function buildTextMsg(toUser, text, contextToken) {
  return {
    from_user_id: "",
    to_user_id: toUser,
    client_id: randomUUID(),
    message_type: 2,
    message_state: 2,
    context_token: contextToken,
    item_list: [{ type: 1, text_item: { text } }],
  };
}

// ---------- 客户端 ----------
export class ILinkClient {
  constructor({ stateFile, log = () => {} }) {
    this.stateFile = stateFile;
    this.log = log;
    this.store = { credentials: null, cursor: "", contextTokens: {}, typingTickets: {} };
    this.running = false;
    this._loginActive = false;
    this._verifyResolver = null;
    this._tx = Promise.resolve();
    this._pollAbort = null;
    this.status = "offline"; // offline | waiting | scanned | need_verifycode | online | expired | error
    this.message = "";
    this.qrUrl = null;
    this.qrData = null;
    this.handlers = { message: null, status: null };
    this._load();
  }

  // ---- 持久化（原子写） ----
  _load() {
    try {
      const raw = readFileSync(this.stateFile, "utf8");
      const parsed = JSON.parse(raw);
      this.store = {
        credentials: parsed.credentials ?? null,
        cursor: parsed.cursor ?? "",
        contextTokens: parsed.contextTokens ?? {},
        typingTickets: parsed.typingTickets ?? {},
      };
    } catch {
      this.store = { credentials: null, cursor: "", contextTokens: {}, typingTickets: {} };
    }
  }

  _save() {
    try {
      mkdirSync(dirname(this.stateFile), { recursive: true });
      const tmp = `${this.stateFile}.tmp`;
      writeFileSync(tmp, JSON.stringify(this.store));
      renameSync(tmp, this.stateFile);
    } catch (err) {
      this.log("ilink: state save failed", err);
    }
  }

  _setStatus(status, message) {
    this.status = status;
    this.message = message || "";
    try {
      this.handlers.status?.(this.statusInfo());
    } catch (err) {
      this.log("ilink: status handler error", err);
    }
  }

  statusInfo() {
    return {
      status: this.status,
      message: this.message,
      qrUrl: this.qrUrl,
      qrData: this.qrData,
      accountId: this.store.credentials?.accountId ?? null,
      userId: this.store.credentials?.userId ?? null,
      loggedIn: Boolean(this.store.credentials?.token),
      boundUserId: this.store.credentials?.userId ?? null,
    };
  }

  isOnline() {
    return this.status === "online" && Boolean(this.store.credentials?.token);
  }

  _requireCreds() {
    if (!this.store.credentials?.token) throw new Error("微信未登录");
    return this.store.credentials;
  }

  // ---- HTTP 基础 ----
  async _fetchJson(url, init, timeoutMs, retries = 0) {
    let lastErr;
    for (let attempt = 0; attempt <= retries; attempt++) {
      try {
        const res = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
        if (res.status >= 500) {
          lastErr = new Error(`HTTP ${res.status}`);
          await sleep(Math.min(1000 * 2 ** attempt, 10000));
          continue;
        }
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}`);
        }
        return await res.json();
      } catch (err) {
        if (err?.name === "TimeoutError" || err?.name === "AbortError") {
          lastErr = err;
          if (attempt < retries) continue;
        }
        throw err;
      }
    }
    throw lastErr;
  }

  _checkBiz(json) {
    if (json?.errcode != null && json.errcode !== 0) {
      throw new ApiError(json.errmsg || `errcode ${json.errcode}`, { ret: json.ret, errcode: json.errcode, errmsg: json.errmsg });
    }
    if (json?.ret != null && json.ret !== 0) {
      throw new ApiError(json.errmsg || `ret ${json.ret}`, { ret: json.ret, errcode: json.errcode, errmsg: json.errmsg });
    }
    return json;
  }

  async _postJson(creds, path, body) {
    const json = await this._fetchJson(
      `${creds.baseUrl}${path}`,
      { method: "POST", headers: authHeaders(creds.token), body: JSON.stringify(body) },
      UPDATES_TIMEOUT_MS,
      2
    );
    return this._checkBiz(json);
  }

  // 串行发送队列：避免多条消息交错
  _enqueue(fn) {
    const run = this._tx.then(fn, fn);
    this._tx = run.catch(() => {});
    return run;
  }

  // ---- 登录 ----
  async _requestQr(localTokens) {
    const url = `${FIXED_BASE}/ilink/bot/get_bot_qrcode?bot_type=3`;
    const json = await this._fetchJson(
      url,
      {
        method: "POST",
        headers: { ...commonHeaders(), "Content-Type": "application/json" },
        body: JSON.stringify({ local_token_list: localTokens.slice(0, 10) }),
      },
      20000,
      2
    );
    if (!json?.qrcode) throw new Error("获取二维码失败");
    return json;
  }

  async _pollStatus(qrcode, verifyCode, host) {
    const base = host ? `https://${host}` : FIXED_BASE;
    let url = `${base}/ilink/bot/get_qrcode_status?qrcode=${encodeURIComponent(qrcode)}`;
    if (verifyCode) url += `&verify_code=${encodeURIComponent(verifyCode)}`;
    const res = await fetch(url, { method: "GET", headers: commonHeaders(), signal: AbortSignal.timeout(QR_LONG_POLL_MS) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  }

  startLogin({ verifyCode } = {}) {
    if (this._loginActive) {
      if (verifyCode && this._verifyResolver) {
        this._verifyResolver(verifyCode);
        return Promise.resolve(this.statusInfo());
      }
      return Promise.resolve(this.statusInfo());
    }
    if (verifyCode && this._verifyResolver) {
      this._verifyResolver(verifyCode);
      return Promise.resolve(this.statusInfo());
    }
    this._loginActive = true;
    return this._loginFlow(verifyCode).finally(() => {
      this._loginActive = false;
    });
  }

  async _loginFlow(initialVerify) {
    this.stopPolling();
    this._setStatus("waiting", "请用微信扫描下方二维码");
    let verify = initialVerify || null;
    let host = null;
    let refresh = 0;

    while (this._loginActive) {
      let qr;
      try {
        const localTokens = this.store.credentials?.token ? [this.store.credentials.token] : [];
        qr = await this._requestQr(localTokens);
      } catch (err) {
        this._setStatus("error", `获取二维码失败: ${err.message}`);
        return this.statusInfo();
      }
      this.qrUrl = typeof qr.qrcode_img_content === "string" ? qr.qrcode_img_content : null;
      this.qrData = this.qrUrl ?? qr.qrcode ?? null;
      const qrcode = qr.qrcode;
      this._setStatus("waiting", "请用微信扫描下方二维码");

      while (this._loginActive) {
        let st;
        try {
          st = await this._pollStatus(qrcode, verify, host);
        } catch (err) {
          await sleep(1000);
          continue;
        }
        verify = null;
        switch (st.status) {
          case "wait":
            await sleep(1000);
            continue;
          case "scaned":
            this._setStatus("scanned", "已扫码，请在手机微信上确认登录");
            await sleep(1000);
            continue;
          case "need_verifycode": {
            this._setStatus("need_verifycode", "请输入手机微信上显示的配对码");
            verify = await new Promise((resolve) => {
              this._verifyResolver = resolve;
              setTimeout(() => {
                if (this._verifyResolver === resolve) {
                  this._verifyResolver = null;
                  resolve(null);
                }
              }, 10 * 60 * 1000);
            });
            if (verify == null) {
              this._setStatus("expired", "配对码输入超时，已生成新二维码");
              break;
            }
            continue;
          }
          case "confirmed": {
            const token = st.bot_token;
            const accountId = st.ilink_bot_id;
            const userId = st.ilink_user_id;
            if (!token || !accountId || !userId) {
              this._setStatus("error", "登录响应缺少必要字段");
              return this.statusInfo();
            }
            this.store.credentials = {
              token,
              baseUrl: st.baseurl || FIXED_BASE,
              accountId,
              userId,
              savedAt: Date.now(),
            };
            this.store.cursor = "";
            this.store.contextTokens = {};
            this._save();
            this.qrUrl = null;
            this.qrData = null;
            this._setStatus("online", "微信已连接");
            this._startPollLoop();
            return this.statusInfo();
          }
          case "binded_redirect": {
            if (this.store.credentials?.token) {
              this.qrUrl = null;
              this.qrData = null;
              this._setStatus("online", "微信已连接（复用本地凭证）");
              this._startPollLoop();
              return this.statusInfo();
            }
            this._setStatus("error", "服务器要求复用凭证，但本地无凭证");
            return this.statusInfo();
          }
          case "scaned_but_redirect":
            host = String(st.redirect_host || "").replace(/^https?:\/\//, "");
            this._setStatus("scanned", "已扫码，正在切换线路…");
            continue;
          case "expired":
          case "verify_code_blocked":
            refresh++;
            if (refresh > MAX_QR_REFRESH) {
              this._setStatus("error", "二维码多次过期，请重试");
              return this.statusInfo();
            }
            this._setStatus("waiting", "二维码已过期，正在生成新二维码…");
            break;
          default:
            this._setStatus("error", `未知状态: ${st.status}`);
            return this.statusInfo();
        }
        break; // 换新二维码
      }
    }
    return this.statusInfo();
  }

  // ---- 消息长轮询 ----
  async _getUpdates() {
    const creds = this._requireCreds();
    const json = await this._fetchJson(
      `${creds.baseUrl}/ilink/bot/getupdates`,
      {
        method: "POST",
        headers: authHeaders(creds.token),
        body: JSON.stringify({ get_updates_buf: this.store.cursor ?? "", ...baseInfo() }),
      },
      UPDATES_TIMEOUT_MS,
      0
    );
    return this._checkBiz(json);
  }

  _startPollLoop() {
    if (this.running) return;
    this.running = true;
    this._bestEffort("/ilink/bot/msg/notifystart");
    this._pollLoop().catch((err) => {
      this.log("ilink: poll loop crashed", err);
      this.running = false;
    });
  }

  async _pollLoop() {
    let backoff = 1000;
    while (this.running && this.store.credentials?.token) {
      try {
        const resp = await this._getUpdates();
        backoff = 1000;
        this.store.cursor = resp.get_updates_buf ?? this.store.cursor ?? "";
        this._save();
        for (const wire of resp.msgs ?? []) {
          const msg = parseIncoming(wire);
          if (!msg) continue;
          this.store.contextTokens[msg.userId] = msg.contextToken;
          this._save();
          if (this.handlers.message) {
            try {
              await this.handlers.message(msg);
            } catch (err) {
              this.log("ilink: message handler error", err);
            }
          }
        }
      } catch (err) {
        if (!this.running) break;
        if (err instanceof ApiError && err.isSessionExpired) {
          this.log("ilink: session expired (-14), re-login");
          this._clearCredentials();
          this._setStatus("expired", "微信登录已过期，正在生成新二维码…");
          this._loginActive = true;
          this._loginFlow().finally(() => {
            this._loginActive = false;
          });
          return;
        }
        if (err instanceof ApiError) {
          this.log("ilink: api error", err.message);
          await sleep(backoff);
          backoff = Math.min(backoff * 2, 10000);
          continue;
        }
        if (err?.name === "TimeoutError" || err?.name === "AbortError") {
          // 35s 长轮询正常超时，继续下一轮
          continue;
        }
        this.log("ilink: network error", err?.message);
        await sleep(backoff);
        backoff = Math.min(backoff * 2, 10000);
      }
    }
    this.running = false;
  }

  stopPolling() {
    this.running = false;
    if (this.store.credentials?.token) this._bestEffort("/ilink/bot/msg/notifystop");
  }

  async _bestEffort(path) {
    try {
      const creds = this._requireCreds();
      await this._postJson(creds, path, { ...baseInfo() });
    } catch {
      /* 失败非致命 */
    }
  }

  _clearCredentials() {
    this.store = { credentials: null, cursor: "", contextTokens: {}, typingTickets: {} };
    this._save();
  }

  // ---- 发送 ----
  async sendText(userId, text) {
    const creds = this._requireCreds();
    const contextToken = this.store.contextTokens[userId];
    if (!contextToken) throw new Error("缺少 context_token（该用户尚无历史消息）");
    const chunks = chunkText(text);
    return this._enqueue(async () => {
      for (const chunk of chunks) {
        await this._postJson(creds, "/ilink/bot/sendmessage", { msg: buildTextMsg(userId, chunk, contextToken), ...baseInfo() });
      }
    });
  }

  async sendMedia(userId, item) {
    const creds = this._requireCreds();
    const contextToken = this.store.contextTokens[userId];
    if (!contextToken) throw new Error("缺少 context_token");
    return this._enqueue(async () => {
      await this._postJson(creds, "/ilink/bot/sendmessage", {
        msg: {
          from_user_id: "",
          to_user_id: userId,
          client_id: randomUUID(),
          message_type: 2,
          message_state: 2,
          context_token: contextToken,
          item_list: [item],
        },
        ...baseInfo(),
      });
    });
  }

  // ---- 输入状态 ----
  async setTyping(userId, on) {
    try {
      const creds = this._requireCreds();
      const now = Date.now();
      const cached = this.store.typingTickets[userId];
      let ticket = cached && now - cached.ts < TICKET_TTL_MS ? cached.ticket : null;
      if (on && !ticket) {
        const body = {
          ilink_user_id: userId,
          ...(this.store.contextTokens[userId] ? { context_token: this.store.contextTokens[userId] } : {}),
          ...baseInfo(),
        };
        const resp = await this._postJson(creds, "/ilink/bot/getconfig", body);
        ticket = resp.typing_ticket;
        if (!ticket) return false;
        this.store.typingTickets[userId] = { ticket, ts: now };
        this._save();
      }
      if (!ticket) return false;
      await this._postJson(creds, "/ilink/bot/sendtyping", {
        ilink_user_id: userId,
        typing_ticket: ticket,
        status: on ? 1 : 2,
        ...baseInfo(),
      });
      return true;
    } catch {
      return false; // typing 失败不致命
    }
  }

  // ---- 媒体上传 ----
  async uploadMedia(userId, data, mediaType) {
    const creds = this._requireCreds();
    const key = randomBytes(16);
    const rawMd5 = createHash("md5").update(data).digest("hex");
    const encrypted = aesEncrypt(key, data);
    const req = {
      filekey: randomBytes(16).toString("hex"),
      media_type: mediaType, // 1=IMAGE 2=VIDEO 3=FILE 4=VOICE
      to_user_id: userId,
      rawsize: data.length,
      rawfilemd5: rawMd5,
      filesize: encrypted.length,
      no_need_thumb: true,
      aeskey: encodeAesKeyHex(key),
      ...baseInfo(),
    };
    const resp = await this._postJson(creds, "/ilink/bot/getuploadurl", req);
    const uploadUrl =
      resp.upload_full_url ||
      `${CDN_BASE}/upload?encrypted_query_param=${encodeURIComponent(resp.upload_param)}&filekey=${encodeURIComponent(req.filekey)}`;
    let encParam = null;
    for (let attempt = 0; attempt < 3 && !encParam; attempt++) {
      try {
        const res = await fetch(uploadUrl, {
          method: "POST",
          headers: { "Content-Type": "application/octet-stream" },
          body: new Uint8Array(encrypted),
          signal: AbortSignal.timeout(UPLOAD_TIMEOUT_MS),
        });
        if (res.status >= 500) throw new Error(`HTTP ${res.status}`);
        encParam = res.headers.get("x-encrypted-param");
        if (!encParam) throw new Error("上传响应缺少 x-encrypted-param");
      } catch (err) {
        this.log("ilink: cdn upload attempt failed", err.message);
        await sleep(1000 * 2 ** attempt);
      }
    }
    if (!encParam) throw new Error("CDN 上传失败");
    return { encrypt_query_param: encParam, aes_key: encodeAesKeyBase64(key), encrypt_type: 1 };
  }

  async sendImage(userId, buf, caption) {
    const media = await this.uploadMedia(userId, buf, 1);
    await this.sendMedia(userId, { type: 2, image_item: { media } });
    if (caption) await this.sendText(userId, caption);
  }

  // ---- 媒体下载 ----
  async downloadItem(item) {
    const media = item.media;
    if (!media) return null;
    const url =
      media.full_url || `${CDN_BASE}/download?encrypted_query_param=${encodeURIComponent(media.encrypt_query_param ?? "")}`;
    const res = await fetch(url, { signal: AbortSignal.timeout(UPLOAD_TIMEOUT_MS) });
    if (!res.ok) throw new Error(`CDN 下载失败 HTTP ${res.status}`);
    const buf = Buffer.from(await res.arrayBuffer());
    let key = null;
    if (typeof item.aeskey === "string" && /^[0-9a-fA-F]{32}$/.test(item.aeskey)) {
      key = Buffer.from(item.aeskey, "hex"); // image_item.aeskey 优先（格式 C）
    } else if (media.aes_key) {
      key = decodeAesKey(media.aes_key);
    }
    return key ? aesDecrypt(key, buf) : buf; // 无 key 视为明文
  }

  // ---- 生命周期 ----
  resume() {
    if (this.store.credentials?.token) {
      this._setStatus("online", "微信已连接（恢复会话）");
      this._startPollLoop();
      return true;
    }
    return false;
  }

  stop() {
    this.stopPolling();
    this._loginActive = false;
    if (this._verifyResolver) {
      this._verifyResolver(null);
      this._verifyResolver = null;
    }
    this._setStatus("offline", "已断开");
  }

  clearAll() {
    this.stopPolling();
    this._loginActive = false;
    this._clearCredentials();
    this.qrUrl = null;
    this.qrData = null;
    this._setStatus("offline", "未连接");
  }
}
