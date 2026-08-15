# 微信 iLink Bot API 协议规范（精确报文版）

> 本文档从开源 SDK 源码逐字段提取微信 ClawBot 背后的 iLink Bot API 协议。
> 目标读者：需要在 Node 20 上**不引用任何 SDK** 自行实现协议客户端的人。
> 所有字段名大小写、JSON 结构均与源码一致；凡源码中找不到的字段，一律标注「未在源码中找到」，不编造。

## 数据源与真值优先级

| 优先级 | 仓库 | 关键文件（本次核对） |
|---|---|---|
| P0（官方实现） | https://github.com/Tencent/openclaw-weixin | `src/api/api.ts`、`src/api/types.ts`、`src/auth/login-qr.ts`、`src/cdn/*`、`src/media/media-download.ts` |
| P1（生产 SDK） | https://github.com/corespeed-io/wechatbot（@wechatbot/wechatbot） | `nodejs/src/protocol/*`、`nodejs/src/auth/*`、`nodejs/src/media/*`、`nodejs/src/messaging/*` |
| P2（文档） | https://www.wechatbot.dev/zh/protocol | 与 `corespeed-io/wechatbot/docs/protocol.md` 同源 |

**重要结论（先看这里）**：两个仓库对基座与接口路径的描述存在**一处细节差异**，已交叉核对如下：

- 官方插件（Tencent/openclaw-weixin）实际请求路径**带 `/ilink/bot/` 前缀**：
  - `POST {base}/ilink/bot/get_bot_qrcode?bot_type=3`
  - `GET {base}/ilink/bot/get_qrcode_status?qrcode=...`
  - `POST {base}/ilink/bot/getupdates`、`/ilink/bot/sendmessage`、`/ilink/bot/getconfig`、`/ilink/bot/sendtyping`、`/ilink/bot/getuploadurl`、`/ilink/bot/msg/notifystart`、`/ilink/bot/msg/notifystop`
- corespeed SDK 的 `api.ts` 中也**同样是 `/ilink/bot/...`** 前缀。
- 仅 `corespeed/docs/protocol.md` 与 wechatbot.dev 文档页面在「接口列表」小节里省写为 `GET /get_bot_qrcode`、`POST /getupdates` 等 —— 这是文档的**简称写法**，不是真实路径。
- **实现时务必使用 `/ilink/bot/` 前缀**（以两个仓库的 `api.ts` / `login-qr.ts` 实际代码为准）。

基座与常量（两仓库一致）：

```
DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
CDN_BASE_URL     = "https://novac2c.cdn.weixin.qq.com/c2c"
```

---

## 0. 术语与总体流程

协议分三段：

1. **登录**：`get_bot_qrcode`（POST）拿二维码 → `get_qrcode_status`（GET 长轮询）直到 `confirmed`，拿到 `bot_token` / `baseurl` / `ilink_bot_id` / `ilink_user_id`。
2. **消息**：`getupdates` 长轮询收消息 → `sendmessage` 回消息；`getconfig` + `sendtyping` 做「正在输入」。
3. **媒体**：`getuploadurl` 拿上传参数 → AES-128-ECB 加密 → CDN POST 上传；收到媒体消息时 CDN GET 下载 → AES 解密。

核心概念 **`context_token`**：每条入站消息携带，回复时必须原样回传；按 `userId` 缓存、跨重启持久化、会话过期（`errcode: -14`）时清除。

---

## 1. 登录流程

### 1.1 获取二维码 `get_bot_qrcode`

- **方法/路径**：`POST {base}/ilink/bot/get_bot_qrcode?bot_type=3`
  - `bot_type` 固定为 `3`（`DEFAULT_ILINK_BOT_TYPE = "3"`）。SDK 里 `bot_type` 作为查询参数并做 `encodeURIComponent`。
- **基座**：**固定**为 `https://ilinkai.weixin.qq.com`（登录阶段的请求不走 `baseurl`，两仓库都硬编码 `FIXED_BASE_URL`）。
- **请求体**（POST，Content-Type: application/json）：

```json
{
  "local_token_list": []
}
```

`local_token_list`：本地已登录账号的 `bot_token` 列表，**最多 10 个，新的在前**。用于让服务器在「该 bot 已绑定到本客户端」时返回 `binded_redirect` 而不是签发重复会话。无本地凭证时传空数组 `[]`。

- **响应 200**：

```json
{
  "qrcode": "<token 字符串>",
  "qrcode_img_content": "<二维码内容，通常是一个 URL>"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `qrcode` | string | 二维码会话 token，后续轮询状态要用，**不是**登录后的 `bot_token` |
| `qrcode_img_content` | string | 二维码图片内容（SDK 直接当 URL 展示给用户扫码） |

源码出处：
- `Tencent/.../src/auth/login-qr.ts`（`fetchQRCode`、`QRCodeResponse`、`DEFAULT_ILINK_BOT_TYPE`、`getLocalBotTokenList`）
- `corespeed-io/.../nodejs/src/protocol/api.ts`（`getQrCode` → `{ local_token_list }`）
- `corespeed-io/.../nodejs/src/protocol/types.ts`（`QrCodeResponse { qrcode, qrcode_img_content }`）

### 1.2 轮询扫码状态 `get_qrcode_status`

- **方法/路径**：`GET {base}/ilink/bot/get_qrcode_status?qrcode=<token>[&verify_code=<digits>]`
  - `qrcode` 做 `encodeURIComponent`；`verify_code` 仅当服务器返回 `need_verifycode` 后下次轮询才附带。
- **请求头**：见第 2 节通用头（此接口是 GET，只带 `iLink-App-Id` + `iLink-App-ClientVersion`，**不带** `Authorization` / `X-WECHAT-UIN` / `Content-Type`）。
- **这是一个 GET 长轮询**：服务端会挂起连接，客户端超时设为 **35 秒**（`QR_LONG_POLL_TIMEOUT_MS = 35_000`）。

**状态机**（`status` 字段取值，两仓库完全一致）：

```
wait → scaned → confirmed   （成功）
wait → need_verifycode →（带 verify_code 重轮询）→ scaned → confirmed
wait → expired → 重新 get_bot_qrcode
wait → scaned_but_redirect → 切到 https://<redirect_host> 继续轮询
     → binded_redirect → 已绑定，复用本地凭证
     → verify_code_blocked → 换新二维码
```

| status | 含义 | 处理 |
|---|---|---|
| `wait` | 等待扫码 | 继续轮询 |
| `scaned` | 已扫码，等待用户手机确认 | 提示用户；继续轮询 |
| `confirmed` | 登录成功 | 取 `bot_token` 等字段 |
| `expired` | 二维码过期 | 重新 `get_bot_qrcode`（最多刷 3 次后放弃） |
| `scaned_but_redirect` | IDC 重定向 | 改轮询主机为 `https://<redirect_host>` |
| `binded_redirect` | bot 已绑定本客户端 | 视为成功，复用本地凭证 |
| `need_verifycode` | 需要配对码 | 提示用户输入手机微信上的数字，下次轮询带 `&verify_code=` |
| `verify_code_blocked` | 配对码连错太多次 | 此二维码作废，换新 |

- **轮询间隔**：默认 **2 秒**（corespeed `QR_POLL_INTERVAL_MS = 2_000`）；腾讯官方实现里每次轮询后 `await sleep(1000)` 即 **1 秒**，且 `get_qrcode_status` 本身是 35 秒长轮询（服务端挂起，超时后返回 `wait`）。**实现建议：发起 35 秒超时的 GET，服务端正常返回（或 35 秒超时/网络错误）后静默重试，间隔 1~2 秒**。

- **`confirmed` 时的完整响应**：

```json
{
  "status": "confirmed",
  "bot_token": "<登录成功后的 Bearer token>",
  "ilink_bot_id": "<bot 账号 ID，作为 accountId 持久化>",
  "ilink_user_id": "<扫码那个微信用户的 ID，作为后续 to_user_id / from_user_id>",
  "baseurl": "https://ilinkai.weixin.qq.com"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | string | 是 | 见状态机 |
| `bot_token` | string | confirmed 时必填 | Bearer token，后续所有业务请求用 |
| `ilink_bot_id` | string | confirmed 时必填 | bot 账号 ID（腾讯侧登录会判空，缺失即登录失败） |
| `ilink_user_id` | string | confirmed 时必填 | 扫码用户 ID（corespeed 判空，缺失即登录失败） |
| `baseurl` | string | confirmed 时可能返回 | 后续业务请求的基座，可能不同于默认值，**始终用返回值**；缺失则回退默认 |
| `redirect_host` | string | scaned_but_redirect 时返回 | 新轮询主机（不含 `https://` 前缀，需自行拼 `https://`） |

**关于 `confirm` 的 `ret` 字段**：任务清单问 `confirmed` 时响应里是否有 `ret`。**两仓库的 `QrStatusResponse` / `StatusResponse` 类型定义里都没有 `ret` 字段**，判定成功只看 `status === "confirmed"`。`ret` 出现在下面第 3/4/5/6 节的业务接口响应里，不在登录状态响应里。

源码出处：
- `Tencent/.../src/auth/login-qr.ts`（`pollQRStatus`、`StatusResponse`、`waitForWeixinLogin` 状态机、`QR_LONG_POLL_TIMEOUT_MS`）
- `corespeed-io/.../nodejs/src/auth/authenticator.ts`（`qrLogin` 状态机、`QR_POLL_INTERVAL_MS`、`MAX_QR_REFRESH_COUNT`）
- `corespeed-io/.../nodejs/src/protocol/types.ts`（`QrStatusResponse`）

---

## 2. 通用请求头与公共字段

### 2.1 业务 POST 请求头（`buildAuthHeaders`）

除扫码两个接口外，所有业务接口（`getupdates`/`sendmessage`/`getconfig`/`sendtyping`/`getuploadurl`/`notifystart`/`notifystop`）都要带以下请求头：

```
Content-Type: application/json
AuthorizationType: ilink_bot_token
Authorization: Bearer <bot_token>
X-WECHAT-UIN: <base64(String(randomUint32))>
iLink-App-Id: bot
iLink-App-ClientVersion: <uint32 编码的客户端版本号十进制字符串>
```

**`X-WECHAT-UIN` 精确生成规则**（已确认）：
1. `crypto.randomBytes(4).readUInt32BE(0)` → 得到一个无符号 32 位整数（大端读取）。
2. 转成**十进制字符串**（`String(uint32)`）。
3. 对**该十进制字符串本身**做 base64（`Buffer.from(String(value), 'utf-8').toString('base64')`）。

> 已确认：base64 编码的是「十进制字符串的 UTF-8 字节」，**不是**对 4 字节二进制编码。源码见两仓库 `randomWechatUin()`（`api.ts` 里同名函数、`headers.ts` 里同名函数）。每次请求都重新生成。

**`iLink-App-ClientVersion` 生成规则**（重要隐藏细节）：
- `iLink-App-Id` 固定为字符串 `bot`（corespeed `headers.ts` 里 `ILINK_APP_ID = 'bot'`；腾讯插件则读 `package.json` 顶层 `ilink_appid`，缺失时为空字符串）。
- `iLink-App-ClientVersion` = `uint32` 编码 `0x00MMNNPP`，即 `(major & 0xff) << 16 | (minor & 0xff) << 8 | (patch & 0xff)`，把 SDK 的版本号（如 `2.0.1` → `131073`）编码成十进制字符串放在该头。高 8 位固定为 0。
- 例如 corespeed `CHANNEL_VERSION` 从 `package.json` 读取（`2.0.0` 兜底）；腾讯插件 `pkg.version`。

> ⚠️ 这两个头（`iLink-App-Id` / `iLink-App-ClientVersion`）在任务给出的需求清单里没提，但源码里是**确实存在且每个请求都带**的隐藏细节。自实现时建议至少带上 `iLink-App-ClientVersion`（可用固定值，如 `131073` 对应 `2.0.1`），避免被服务端以缺头拒收。

**扫码两个接口的 GET 请求头**（`buildCommonHeaders`）：
```
iLink-App-Id: bot
iLink-App-ClientVersion: <同上>
```
（无 `Authorization` / `AuthorizationType` / `X-WECHAT-UIN` / `Content-Type`。）

可选头（腾讯插件支持，默认不出现）：
- `SKRouteTag`：路由标签，仅当 `openclaw.json` 里配置了 `routeTag` 时附加 —— 普通自实现可忽略。

### 2.2 请求体公共字段 `base_info`

所有**业务 POST** 请求体顶层都包含 `base_info`；`getupdates`/`sendmessage`/`getconfig`/`sendtyping`/`getuploadurl`/`notifystart`/`notifystop` 均如此。**扫码两个接口不带 `base_info`**。

```json
{
  "base_info": {
    "channel_version": "2.0.0",
    "bot_agent": "WeChatBot/2.0.0"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `channel_version` | string | 是（SDK 恒带） | SDK 版本号字符串，corespeed 从 `package.json` 读、腾讯从 `pkg.version` 读 |
| `bot_agent` | string | 否（缺省即 fallback） | UA 风格应用标识，仅用于日志/监控，不参与鉴权/路由 |

`bot_agent` 语法（UA 风格，两仓库一致，腾讯侧有完整 sanitize 实现）：
```
bot_agent = product *( SP product )
product   = name "/" version [ SP "(" comment ")" ]
name      = 1*32( ALPHA / DIGIT / "_" / "." / "-" )
version   = 1*32( ALPHA / DIGIT / "_" / "." / "+" / "-" )
comment   = 1*64( 可打印 ASCII，不含 "(" ")" )
```
- 缺失/非法时回落：corespeed 默认 `WeChatBot/<version>`，腾讯默认 `OpenClaw`。
- 总长度 ≤ 256 字节；超长时从尾部丢弃 token。
- 合法示例：`MyBot/1.2.0`、`MyBot/1.2.0 (region=cn;env=prod)`、`MyBot/1.2.0 LangChain/0.3.5`。

> ⚠️ 两仓库对 `channel_version` 的兜底值不同：corespeed 是 `2.0.0`，腾讯是 `unknown`。自实现填任意非空字符串即可（如 `2.0.0`）。

源码出处：
- `corespeed-io/.../nodejs/src/protocol/headers.ts`（`randomWechatUin`、`ILINK_APP_ID`、`buildClientVersion`、`buildAuthHeaders`、`buildCommonHeaders`）
- `Tencent/.../src/api/api.ts`（`randomWechatUin`、`buildClientVersion`、`buildCommonHeaders`、`buildHeaders`、`sanitizeBotAgent`、`buildBaseInfo`）
- `corespeed-io/.../nodejs/src/protocol/types.ts`（`BaseInfo`）

---

## 3. 长轮询收消息 `getupdates`

- **方法/路径**：`POST {base}/ilink/bot/getupdates`
- **请求头**：第 2.1 节全部头（含 `Authorization: Bearer <token>`）。
- **请求体**（最小）：

```json
{
  "get_updates_buf": "",
  "base_info": {
    "channel_version": "2.0.0",
    "bot_agent": "WeChatBot/2.0.0"
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `get_updates_buf` | string | 游标。首次请求（或重置后）传**空字符串 `""`**；之后每次传上轮响应返回的新游标 |
| `base_info` | object | 见第 2.2 节 |

> 遗留字段：`GetUpdatesReq` 里还定义了已废弃的 `sync_buf`（`@deprecated compat only`），新实现**不要用**，只用 `get_updates_buf`。

- **响应 200**（成功，无新消息）：

```json
{
  "ret": 0,
  "msgs": [],
  "get_updates_buf": "<新游标>"
}
```

- **响应 200**（有新消息，见 3.2 节完整示例）：

```json
{
  "ret": 0,
  "msgs": [ { "..." : "见下" } ],
  "get_updates_buf": "<新游标>",
  "longpolling_timeout_ms": 35000
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `ret` | number | 0 = 成功。**注意**：会话过期不是放在 `ret`，而是放在 `errcode`（见下） |
| `msgs` | WeixinMessage[] | 消息数组，字段见 3.2 节 |
| `get_updates_buf` | string | 新游标，下次请求原样回传，并持久化 |
| `longpolling_timeout_ms` | number（可选） | 服务端建议的下一轮长轮询超时（ms），通常 35000 |
| `errcode` | number（可选） | 错误码，`-14` = 会话过期 |
| `errmsg` | string（可选） | 错误描述 |

**`ret` 与 `errcode` 的确切位置（关键澄清）**：
- **`ret: 0` 表示成功**，业务错误时 `ret != 0`。
- **会话过期是 `errcode: -14`**，不是 `ret`，也不是套在 `ret` 里。`GetUpdatesResp` 类型里 `ret` 和 `errcode` 是**两个并列的顶层字段**。
- corespeed 的 HTTP 层判定逻辑（`transport/http.ts`）：只要 `ret !== 0` **或** `errcode !== 0` 就抛 `ApiError`，其中 `isSessionExpired` 判定的就是 `errcode === -14`。
- **`ret: -2` = 参数错误**（文档如此声明），出现在 `ret` 字段（也见第 9 节）。
- 例如会话过期响应大致形如（字段结构来自类型定义，示例值来自文档约定）：
  ```json
  { "ret": 0, "errcode": -14, "errmsg": "session expired" }
  ```
  （注意：源码里 `ret` 与 `errcode` 并存；`errcode` 才是会话过期的判定依据。）

- **长轮询超时**：客户端 `timeoutMs` 默认 **40 秒**（corespeed `getUpdates` 传 `40_000`）、**35 秒**（腾讯 `DEFAULT_LONG_POLL_TIMEOUT_MS = 35_000`）。服务端会挂起连接直到有新消息或超时；客户端超时属正常控制流，抓到 `AbortError` 后以 `ret:0, msgs:[], 原游标` 继续下一轮即可。

### 3.1 发送状态通知（收消息循环的伴生接口）

SDK/poll 循环在开始/停止轮询时会通知服务端，用于服务端掌握账号在线状态。**失败非致命**（记日志继续）：

- `POST {base}/ilink/bot/msg/notifystart`，body `{ "base_info": {...} }` → `{ "ret": 0 }`
- `POST {base}/ilink/bot/msg/notifystop`，body `{ "base_info": {...} }` → `{ "ret": 0 }`

> 这是需求清单之外的隐藏接口。自实现可先忽略，不影响收发消息。

### 3.2 消息 Schema（`WeixinMessage` / `MessageItem`，覆盖所有消息类型）

顶层 `WeixinMessage`（`msgs` 数组元素）字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `seq` | number（可选） | 消息序号 |
| `message_id` | number（可选） | 消息唯一 ID |
| `from_user_id` | string | **发送者 ID**（即需求的「userId」） |
| `to_user_id` | string | 接收者 ID（bot 侧） |
| `client_id` | string | 客户端 ID（出站消息用，入站也可能有） |
| `create_time_ms` | number | 创建时间戳（**毫秒**，`send_time` 的对应物） |
| `update_time_ms` | number（可选） | 更新时间（毫秒） |
| `delete_time_ms` | number（可选） | 删除时间（毫秒） |
| `session_id` | string（可选） | 会话 ID |
| `group_id` | string（可选） | 群组 ID |
| `message_type` | number | 1=USER（用户发来），2=BOT（bot 自己的消息回显） |
| `message_state` | number | 0=NEW，1=GENERATING，2=FINISH |
| `item_list` | MessageItem[] | 内容列表（见下） |
| `context_token` | string | **会话上下文 token，回复必须原样回传** |
| `run_id` | string（可选） | 运行 ID（腾讯 types.ts 独有字段，corespeed 无） |

> ⚠️ **关于字段名的精确澄清**：需求清单里问「userId 精确字段名」「msgId」「send_time」。实际报文里：
> - 「userId」= **`from_user_id`**（发送者）；接收侧是 `to_user_id`。
> - 「msgId」对应 `message_id`；另注意每个 item 里还有 `msg_id`（见 `MessageItem`）。
> - 「send_time」对应 **`create_time_ms`**（毫秒时间戳）。源码里**没有**名为 `send_time` 或 `msgId`（驼峰连写）的字段。
> - **没有**名为 `updates` 的数组字段 —— 消息数组字段名是 **`msgs`**（需求里写的「updates 数组」实际叫 `msgs`）。

枚举常量（两仓库一致的数值）：

| 枚举 | 值 |
|---|---|
| `MessageItemType`: TEXT / IMAGE / VOICE / FILE / VIDEO | 1 / 2 / 3 / 4 / 5 |
| `MessageType`: USER / BOT | 1 / 2 |
| `MessageState`: NEW / GENERATING / FINISH | 0 / 1 / 2 |
| `UploadMediaType`(原图/视频/文件/语音) IMAGE / VIDEO / FILE / VOICE | 1 / 2 / 3 / 4 |

> 腾讯 `types.ts` 额外定义了 `MessageItemType.TOOL_CALL_START = 11`、`TOOL_CALL_RESULT = 12`，以及对应的 `tool_call_start_item` / `tool_call_result_item`。这属于较新的流式能力，corespeed 未实现；普通文本/媒体收发可不关注，但入站解析时应容错未知 type。

`MessageItem`（`item_list` 元素）字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | number | 1=TEXT / 2=IMAGE / 3=VOICE / 4=FILE / 5=VIDEO |
| `create_time_ms` | number（可选） | 该 item 创建时间 |
| `update_time_ms` | number（可选） | 该 item 更新时间 |
| `is_completed` | boolean（可选） | 是否完成（流式） |
| `msg_id` | string（可选） | **item 级消息 ID**（与顶层 `message_id` 不同） |
| `ref_msg` | RefMessage（可选） | 引用的消息（回复引用） |
| `text_item` | TextItem | 文本 |
| `image_item` | ImageItem | 图片 |
| `voice_item` | VoiceItem | 语音 |
| `file_item` | FileItem | 文件 |
| `video_item` | VideoItem | 视频 |
| `tool_call_start_item` / `tool_call_result_item` | （可选） | 工具调用（腾讯独有，类型 11/12） |

各 item 结构：

**TextItem**：`{ "text": "<文本内容>" }`

**CDNMedia**（所有媒体共用的 CDN 引用，注意这是**出站/入站媒体块的核心**）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `encrypt_query_param` | string | CDN 下载/上传的加密参数（上传成功响应的 `x-encrypted-param` 头就是它） |
| `aes_key` | string | AES-128 密钥，base64 编码（格式 A 或 B，见第 7 节） |
| `encrypt_type` | number（可选） | 0=只加密 fileid，1=打包缩略图/中图等信息（corespeed 上传时恒写 `1`） |
| `full_url` | string（可选） | 服务端直接返回的完整下载 URL，有则直接用，不再拼 `encrypt_query_param` |

**ImageItem**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `media` | CDNMedia | 原图 CDN 引用 |
| `thumb_media` | CDNMedia（可选） | 缩略图 CDN 引用 |
| `aeskey` | string | **直接十六进制 32 字符**的 AES key（与 `media.aes_key` 的 base64 不同，优先用它解密入站图） |
| `url` | string（可选） | 图片 URL（纯文本回退展示用） |
| `mid_size` | number\|string（可选） | 中图大小 |
| `thumb_size` | number\|string（可选） | 缩略图大小 |
| `thumb_height` / `thumb_width` | number（可选） | 缩略图尺寸 |
| `hd_size` | number\|string（可选） | 高清图大小 |

**VoiceItem**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `media` | CDNMedia | 语音 CDN 引用（SILK 编码） |
| `encode_type` | number | 1=pcm 2=adpcm 3=feature 4=speex 5=amr 6=silk 7=mp3 8=ogg-speex |
| `bits_per_sample` | number（可选） | 位深 |
| `sample_rate` | number（可选） | 采样率 Hz |
| `playtime` | number（可选） | 语音时长（毫秒） |
| `text` | string（可选） | 语音转文字内容 |

**FileItem**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `media` | CDNMedia | 文件 CDN 引用 |
| `file_name` | string | 文件名 |
| `md5` | string | 文件 MD5 |
| `len` | string | 文件大小（**字符串**，注意不是 number） |

**VideoItem**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `media` | CDNMedia | 视频 CDN 引用 |
| `video_size` | number\|string（可选） | 视频大小 |
| `play_length` | number（可选） | 播放时长 |
| `video_md5` | string（可选） | 视频 MD5 |
| `thumb_media` | CDNMedia（可选） | 缩略图 CDN 引用 |
| `thumb_size` / `thumb_height` / `thumb_width` | number 等（可选） | 缩略图信息 |

**RefMessage**（回复引用）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `title` | string（可选） | 摘要 |
| `message_item` | MessageItem（可选） | 被引用的 item |

#### 3.2.1 一条文本消息完整示例（真实字段，值已脱敏）

```json
{
  "seq": 123,
  "message_id": 987654321,
  "from_user_id": "wxid_abc123",
  "to_user_id": "b0f5860fdecb@im.bot",
  "client_id": "",
  "create_time_ms": 1735689600123,
  "update_time_ms": 1735689600123,
  "session_id": "session-xyz",
  "message_type": 1,
  "message_state": 2,
  "item_list": [
    {
      "type": 1,
      "text_item": { "text": "你好，机器人" }
    }
  ],
  "context_token": "eyJhbGciOiJIUzI1NiJ9.example_context_token"
}
```

字段说明：
- `message_type: 1`（USER）—— 只能处理该类型，`2` 是 bot 自己的消息回显，SDK 的 parser 会过滤掉（`if wire.message_type !== USER return null`）。
- `context_token`：回复时**必须原样回传**到 `sendmessage` 的 `msg.context_token`。

#### 3.2.2 一条图片消息完整示例（值已脱敏）

```json
{
  "seq": 124,
  "message_id": 987654322,
  "from_user_id": "wxid_abc123",
  "to_user_id": "b0f5860fdecb@im.bot",
  "create_time_ms": 1735689605000,
  "session_id": "session-xyz",
  "message_type": 1,
  "message_state": 2,
  "item_list": [
    {
      "type": 2,
      "image_item": {
        "media": {
          "encrypt_query_param": "<加密下载参数>",
          "aes_key": "ABEiM0RVZneImaq7zN3u/w==",
          "encrypt_type": 1
        },
        "thumb_media": {
          "encrypt_query_param": "<缩略图加密参数>",
          "aes_key": "MDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmY="
        },
        "aeskey": "00112233445566778899aabbccddeeff",
        "mid_size": 45678,
        "thumb_size": 2048,
        "thumb_height": 200,
        "thumb_width": 200
      }
    }
  ],
  "context_token": "eyJhbGciOiJIUzI1NiJ9.example_context_token"
}
```

字段说明：
- 图片**原图可能加密也可能明文**：`media.aes_key` 存在则 AES 解密；腾讯实现里若 `image_item.aeskey`（直接 hex 32 字符）存在，会**优先转成 base64 后当 aes_key 用**（`Buffer.from(img.aeskey,'hex').toString('base64')`），否则用 `media.aes_key`。两者都没有则按**明文**直接下载（`downloadPlainCdnBuffer`）。
- 下载优先用 `media.full_url`，其次拼 `{cdnBase}/download?encrypted_query_param=...`。

源码出处（全部 schema）：
- `Tencent/.../src/api/types.ts`（`WeixinMessage`、`MessageItem`、各 `*Item`、`CDNMedia`、全部枚举）
- `corespeed-io/.../nodejs/src/protocol/types.ts`（`WireMessage`、`WireMessageItem`、各 item、`CDNMedia`）
- `corespeed-io/.../nodejs/src/message/parser.ts`（入站解析、`message_type` 过滤、`from_user_id`/`context_token` 用法）
- `Tencent/.../src/media/media-download.ts`（图片 aeskey 优先级、各媒体下载/解密）

---

## 4. 发送消息 `sendmessage`

- **方法/路径**：`POST {base}/ilink/bot/sendmessage`
- **请求头**：第 2.1 节全部头。
- **响应 200**：`{ "ret": 0 }`（或业务错误时 `ret != 0` + `errmsg`）。corespeed 的 `sendMessage` 在 `ret && ret !== 0` 时抛错。

### 4.1 纯文本发送的最小可用 body

```json
{
  "msg": {
    "from_user_id": "",
    "to_user_id": "<对方 userId，即入站消息的 from_user_id>",
    "client_id": "<随机 UUID>",
    "message_type": 2,
    "message_state": 2,
    "context_token": "<入站消息里的 context_token>",
    "item_list": [
      { "type": 1, "text_item": { "text": "你好" } }
    ]
  },
  "base_info": {
    "channel_version": "2.0.0",
    "bot_agent": "WeChatBot/2.0.0"
  }
}
```

字段说明（`SendMessageReq.msg` = 一个 `WeixinMessage`）：

| 字段 | 值/类型 | 说明 |
|---|---|---|
| `from_user_id` | `""` | 出站恒为空字符串 |
| `to_user_id` | string | 目标用户（入站消息的 `from_user_id`） |
| `client_id` | string | 随机 UUID（`randomUUID()`） |
| `message_type` | 2 | BOT |
| `message_state` | 2 | FINISH |
| `context_token` | string | **必填**，入站消息原样回传；无 token 无法路由 |
| `item_list` | MessageItem[] | 内容列表，见下 |

> ⚠️ **回复引用请求体结构澄清**：需求里问「`msg_id` 回复引用」。SDK 里**发送消息没有单独的 `msg_id` 顶层字段**（`WireMessage`/`WeixinMessage` 里 `message_id` 有，但发文本时 corespeed 的 `buildTextMessagePayload` 并不填它）。引用回复是通过 item 的 **`ref_msg`** 表达（`MessageItem.ref_msg`），例如：
> ```json
> { "type": 1, "text_item": {"text":"回复内容"}, "ref_msg": { "title":"被引摘要", "message_item": {"type":1,"text_item":{"text":"被引文本"}} } }
> ```
> 普通收发无需引用即可工作。

媒体消息的 `item_list` 用第 3.2 节对应的 `image_item` / `file_item` / `video_item` / `voice_item` 结构，其中媒体块填 `CDNMedia`（`encrypt_query_param` + `aes_key`，来自第 6 节上传结果）。corespeed `MessageBuilder` 展示的媒体组装示例：
- 图片：`{ type: 2, image_item: { media: <CDNMedia>, mid_size?, thumb_media?, thumb_size?, thumb_width?, thumb_height? } }`
- 文件：`{ type: 4, file_item: { media: <CDNMedia>, file_name, md5?, len: String(size) } }`
- 视频：`{ type: 5, video_item: { media: <CDNMedia>, video_size?, play_length?, thumb_media? } }`

源码出处：
- `corespeed-io/.../nodejs/src/protocol/api.ts`（`sendMessage`、`buildTextMessagePayload`、`buildMediaMessagePayload`）
- `corespeed-io/.../nodejs/src/message/builder.ts`（`MessageBuilder` 各种 item 组装）
- `Tencent/.../src/api/api.ts`（`sendMessage`）、`src/api/types.ts`（`SendMessageReq`）

---

## 5. 输入状态（typing）

### 5.1 `getconfig` 取 typing_ticket

- **方法/路径**：`POST {base}/ilink/bot/getconfig`
- **请求体**：

```json
{
  "ilink_user_id": "<用户 ID>",
  "context_token": "<该用户的 context_token，可选但推荐>",
  "base_info": { "channel_version": "2.0.0", "bot_agent": "WeChatBot/2.0.0" }
}
```

- **响应 200**：

```json
{
  "ret": 0,
  "typing_ticket": "<base64 编码的 typing ticket>"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `ret` | number | 0 = 成功 |
| `typing_ticket` | string | base64 编码的 ticket，用于 `sendtyping` |
| `errcode` / `errmsg` | number/string（可选） | 错误码/描述 |

> **typing_ticket 在哪**：在 `getconfig` 的**响应**里的 `typing_ticket` 字段，**不在** `sendtyping` 请求之前需要任何其他接口。按 `userId` 缓存，有效期约 **24 小时**（`TICKET_TTL_MS = 24 * 60 * 60 * 1000`）。注意：获取 ticket 需要该用户的 `context_token`（无 token 则拿不到）。

### 5.2 `sendtyping` 显示/隐藏输入状态

- **方法/路径**：`POST {base}/ilink/bot/sendtyping`
- **请求体**：

```json
{
  "ilink_user_id": "<用户 ID>",
  "typing_ticket": "<上一步拿到的 ticket，原样回传>",
  "status": 1,
  "base_info": { "channel_version": "2.0.0", "bot_agent": "WeChatBot/2.0.0" }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `ilink_user_id` | string | 目标用户 |
| `typing_ticket` | string | 从 `getconfig` 拿到的 ticket，**原样回传** |
| `status` | number | **1 = 开始输入，2 = 停止输入** |

- **响应 200**：`{ "ret": 0 }`（腾讯实现不解析业务错误；corespeed 亦类似）。

源码出处：
- `Tencent/.../src/api/api.ts`（`getConfig`、`sendTyping`）
- `corespeed-io/.../nodejs/src/protocol/api.ts`（`getConfig`、`sendTyping`）、`nodejs/src/messaging/typing.ts`（ticket 缓存 24h、status 1/2）
- `corespeed-io/.../nodejs/src/protocol/types.ts`（`TypingStatus`）

---

## 6. 媒体上传/下载

### 6.1 `getuploadurl` 取上传参数

- **方法/路径**：`POST {base}/ilink/bot/getuploadurl`
- **请求体**（最小可用，无缩略图，注意 `aeskey` 是**十六进制字符串**）：

```json
{
  "filekey": "<随机 16 字节的 hex，即 randomBytes(16).toString('hex')>",
  "media_type": 1,
  "to_user_id": "<用户 ID>",
  "rawsize": 12345,
  "rawfilemd5": "<明文文件的 MD5 十六进制>",
  "filesize": 12352,
  "no_need_thumb": true,
  "aeskey": "00112233445566778899aabbccddeeff",
  "base_info": { "channel_version": "2.0.0", "bot_agent": "WeChatBot/2.0.0" }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `filekey` | string | 文件标识，SDK 用 `randomBytes(16).toString('hex')` |
| `media_type` | number | 1=IMAGE / 2=VIDEO / 3=FILE / 4=VOICE |
| `to_user_id` | string | 目标用户 ID |
| `rawsize` | number | **明文**大小（字节） |
| `rawfilemd5` | string | **明文** MD5（十六进制小写） |
| `filesize` | number | **密文**大小（AES-128-ECB + PKCS7 之后，见第 7 节公式） |
| `thumb_rawsize` / `thumb_rawfilemd5` / `thumb_filesize` | number/string/number | 缩略图信息，仅 IMAGE/VIDEO 需要；`no_need_thumb: true` 时可省略 |
| `no_need_thumb` | boolean | true = 不需要缩略图上传 URL（corespeed 上传器恒置 true） |
| `aeskey` | string | **加密 key，十六进制字符串**（`key.toString('hex')`） |

- **响应 200**：

```json
{
  "upload_param": "<原图上传加密参数>",
  "thumb_upload_param": "<缩略图上传加密参数，无缩略图时为空/缺失>",
  "upload_full_url": "<完整上传 URL（可选，服务端直接返回）>"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `upload_param` | string | 原图上传加密参数 |
| `thumb_upload_param` | string（可选） | 缩略图上传加密参数 |
| `upload_full_url` | string（可选） | 完整上传 URL；有则直接 POST 到此，否则拼 CDN URL |

### 6.2 CDN 上传（POST，AES 密文作为 body）

- **方法**：**POST**（不是 PUT）。
- **目标 URL**（优先 `upload_full_url`，否则拼）：
  ```
  {cdnBaseUrl}/upload?encrypted_query_param=<encodeURIComponent(upload_param)>&filekey=<encodeURIComponent(filekey)>
  ```
  其中 `cdnBaseUrl = https://novac2c.cdn.weixin.qq.com/c2c`。
- **请求头**：仅 `Content-Type: application/octet-stream`（**不带** JSON 业务头，**不带** Authorization）。
- **请求体**：AES-128-ECB 加密后的**原始字节**（`new Uint8Array(ciphertext)`）。
- **成功（HTTP 200）** 时从**响应头 `x-encrypted-param`** 取下载加密参数（这就是 `CDNMedia.encrypt_query_param`）。
- **错误处理**：4xx 为客户端错误（读 `x-error-message` 头，不重试）；5xx/网络错误重试，最多 3 次。
- **超时**：corespeed 用 `AbortSignal.timeout(60_000)`。
- **MD5 校验方式**：`rawfilemd5` 是**明文**的 MD5（`createHash('md5').update(data).digest('hex')`）；`filesize` 是**密文**的大小。上传本身返回的是 `x-encrypted-param` 头，**源码中未发现服务端回传 MD5 做校验的字段**（「md5 校验方式」即：`rawfilemd5` 填明文 MD5，`filesize` 填密文大小，二者严格对应）。

### 6.3 上传成功后组装 sendmessage 的媒体块

上传结束后得到 `CDNMedia`（corespeed `UploadResult.media`）：

```json
{
  "encrypt_query_param": "<上传响应头 x-encrypted-param 的值>",
  "aes_key": "<base64(hex字符串形式的 key)>",
  "encrypt_type": 1
}
```

- `aes_key` 这里用 **`encodeAesKeyBase64`** = `Buffer.from(key.toString('hex'),'utf8').toString('base64')`，即 **base64(十六进制字符串)**（格式 B）。
- `encrypt_type` 恒为 `1`。
- 然后把这个 `CDNMedia` 填进对应 item（图片 `image_item.media`、文件 `file_item.media`、视频 `video_item.media`），再加 `item_list` 里通过 `sendmessage` 发出（见第 4 节）。

### 6.4 CDN 下载（GET，AES 解密）

- **方法**：**GET**。
- **URL**（优先 `media.full_url`，否则拼）：
  ```
  {cdnBaseUrl}/download?encrypted_query_param=<encodeURIComponent(encrypt_query_param)>
  ```
- **响应体**：AES-128-ECB 加密的原始字节 → 用 `aes_key`（`image_item.aeskey` 优先，其次 `media.aes_key`）解密得明文。
- 图片若无任何 aes_key，则按**明文**直接下载（`downloadPlainCdnBuffer`）。
- 超时：corespeed `AbortSignal.timeout(60_000)`。

源码出处：
- `Tencent/.../src/cdn/cdn-url.ts`（`buildCdnUploadUrl`、`buildCdnDownloadUrl`）
- `Tencent/.../src/cdn/cdn-upload.ts`（POST、octet-stream、`x-encrypted-param`、重试）
- `Tencent/.../src/cdn/pic-decrypt.ts`（`parseAesKey`、下载解密）
- `corespeed-io/.../nodejs/src/media/uploader.ts`（`filekey`、`rawMd5`、`encodeAesKeyHex`、`encodeAesKeyBase64`、`encrypt_type:1`）
- `corespeed-io/.../nodejs/src/media/downloader.ts`
- `corespeed-io/.../nodejs/src/protocol/types.ts`（`GetUploadUrlRequest/Response`）

---

## 7. AES-128-ECB 加解密

**核心结论：微信 CDN 的 AES 就是标准 AES-128-ECB，无 IV、无自定义前缀，PKCS7 填充。** 两仓库都用 `createCipheriv('aes-128-ecb', key, null)`（iv 传 `null`，即标准 ECB，无 IV）。

### 7.1 加解密（Node crypto）

```js
const cipher = createCipheriv('aes-128-ecb', key /* 16 bytes */, null)
const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()])

const decipher = createDecipheriv('aes-128-ecb', key, null)
const plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()])
```

- key 必须**恰好 16 字节**，否则 SDK 抛错（`AES key must be 16 bytes`）。
- `final()` 自动做/去 **PKCS7** 填充 —— **无需手动填充**。
- **加密前无需额外 16 字节对齐或前 16 字节特殊处理**：直接对完整明文 `update` + `final` 即可。源码里没有「先处理前 16 字节」的逻辑。

### 7.2 密文大小公式（`encryptedSize`）

```
ciphertextSize = ceil((rawsize + 1) / 16) * 16
```

例如明文 12345 字节 → 密文 12352 字节。此值填 `getuploadurl` 的 `filesize`。

### 7.3 三种密钥编码格式的判别与解析（`decodeAesKey`）

| 格式 | 形态 | 示例 | 来源 |
|---|---|---|---|
| A | base64(原始 16 字节) | `ABEiM0RVZneImaq7zN3u/w==` | `CDNMedia.aes_key`（图） |
| B | base64(十六进制字符串 32 字符) | `MDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmY=` | `CDNMedia.aes_key`（文件/语音/视频） |
| C | 直接十六进制 32 字符 | `00112233445566778899aabbccddeeff` | `image_item.aeskey` |

**解析逻辑**（corespeed `decodeAesKey`，与腾讯 `parseAesKey` 等价）：

1. 若输入匹配 `/^[0-9a-fA-F]{32}$/` → 直接当格式 C，`Buffer.from(s, 'hex')` 得 16 字节。（corespeed 先判这个，用于 `image_item.aeskey`）
2. 否则 `Buffer.from(s, 'base64')`：
   - base64 解出长度 **16** → 格式 A，直接当 key。
   - base64 解出长度 **32** 且 32 字节是 ASCII 且匹配 `/^[0-9a-fA-F]{32}$/` → 格式 B，把该 ASCII hex 再 `Buffer.from(hex,'hex')` 得 16 字节 key。
   - 否则抛错 `Cannot decode AES key: base64-decoded length is N (expected 16 or 32)`。

> 腾讯 `parseAesKey` 不判「直接 hex」分支（因为入参已是 `aes_key` 字段），但 `media-download.ts` 里对 `image_item.aeskey` 的处理是 `Buffer.from(aeskey,'hex').toString('base64')` 先转成 base64 再走 `parseAesKey` —— 语义一致。

**编码方向**（出站组装媒体块时）：
- 给 `getuploadurl` 的 `aeskey` 字段：`encodeAesKeyHex` = `key.toString('hex')`（格式 C）。
- 给 `sendmessage` 的 `CDNMedia.aes_key`：`encodeAesKeyBase64` = `Buffer.from(key.toString('hex'),'utf8').toString('base64')`（格式 B）。

### 7.4 是否有自定义 IV / 前缀

**无**。`createCipheriv('aes-128-ecb', key, null)` 即标准 ECB；无 IV、无自定义前缀/校验头。两仓库实现完全一致。

源码出处：
- `corespeed-io/.../nodejs/src/media/crypto.ts`（`encryptAesEcb`、`decryptAesEcb`、`encryptedSize`、`decodeAesKey`、`encodeAesKeyHex`、`encodeAesKeyBase64`、`generateAesKey`）
- `Tencent/.../src/cdn/aes-ecb.ts`（`encryptAesEcb`、`decryptAesEcb`、`aesEcbPaddedSize`）
- `Tencent/.../src/cdn/pic-decrypt.ts`（`parseAesKey`）

---

## 8. 消息发送分片（长文本）

- **分片阈值**：`MAX_TEXT_LENGTH = 4000`（`sender.ts`）。文本长度 > 4000 时按 4000 字符切分。

> ⚠️ **需求里的「按多少字符分片」有两个数字，已澄清**：
> - `sender.ts` 顶部的注释写「chunked at 2000 character boundaries」是**过时注释**；
> - 实际代码 `const chunks = chunkText(text, MAX_TEXT_LENGTH)`，而 `MAX_TEXT_LENGTH = 4_000`。
> - 因此**真实分片阈值是 4000 字符**（以代码为准，注释与 wechatbot.dev 文档都未再细述 2000）。

- **切分策略**（`chunkText`，按自然边界优先）：
  1. 段落断点 `\n\n`（位置 > limit*0.3 才采用）
  2. 换行 `\n`
  3. 空格 ` `
  4. 硬切（前三级都找不到合适断点时，直接在第 `limit` 字符处切）
- **分片消息体怎么拼**：**每个分片独立发一条 `sendmessage`**，即对每个 chunk 各构造一个完整 `msg`（`to_user_id` / `client_id` 各自新 UUID / `context_token` 相同 / `item_list:[{type:1,text_item:{text:chunk}}]`），串行依次 `POST /ilink/bot/sendmessage`。**没有**「多条 text_item 合并进同一个 item_list 一次性多发」的逻辑。

源码出处：
- `corespeed-io/.../nodejs/src/messaging/sender.ts`（`MAX_TEXT_LENGTH`、`chunkText`、`sendText` 循环发送）

---

## 9. 错误处理约定

### 9.1 业务错误码（顶层 `ret` / `errcode`）

| 字段 | 值 | 含义 | 处理 |
|---|---|---|---|
| `ret` | 0 | 成功 | — |
| `ret` | -2 | 参数错误（文档声明） | 检查请求体 |
| `errcode` | -14 | 会话过期 | 清除 token/游标/context → 重新扫码登录 |
| `ret`（非 0） | 其他 | 业务失败 | 读 `errmsg`，抛错 |

> **判定约定**（corespeed `transport/http.ts`）：`ret` 或 `errcode` 任一 != 0 即抛 `ApiError`，`errcode === -14` 判定为会话过期。`errcode`/`ret` 是**响应体顶层并列字段**（不是嵌套在某个 object 内）。

### 9.2 HTTP 状态码

| 状态 | 处理 |
|---|---|
| 4xx | 请求/认证错误。SDK 抛错不重试；日志/提示检查 token |
| 5xx | 服务端错误。**指数退避重试**（初始 1s，翻倍，上限 10s；`retryDelayMs = Math.min(retryDelayMs*2, 10_000)`） |
| 长轮询客户端超时/网关超时 | 属正常控制流，返回空结果继续下一轮 |

> 业务层 `ApiError`（ret/errcode != 0）是**定论性错误**，不重试（corespeed `isRetryable` 对 `ApiError` 返回 false）。网络层 `AbortError`/`TimeoutError` 可重试（最多 2 次）。

源码出处：
- `corespeed-io/.../nodejs/src/transport/http.ts`（ret/errcode 判定、重试策略）
- `corespeed-io/.../nodejs/src/core/errors.ts`（`ApiError.isSessionExpired` = `errcode === -14`）
- `corespeed-io/.../nodejs/src/messaging/poller.ts`（指数退避、session:expired 处理）
- `corespeed-io/.../docs/protocol.md`、`wechatbot.dev/zh/protocol`（错误码表）

---

## 10. 会话恢复与凭证持久化

### 10.1 需要持久化的字段

corespeed `Credentials`（`auth/types.ts`）与腾讯 `WeixinAccountData` 合并后，需要保存：

| 字段 | 来源 | 说明 |
|---|---|---|
| `token` | `confirmed` 响应 `bot_token` | Bearer 凭证，**必需** |
| `baseUrl` | `confirmed` 响应 `baseurl`（缺失回退默认） | 业务请求基座，**必需持久化** |
| `accountId` | `confirmed` 响应 `ilink_bot_id` | 账号 ID，必需 |
| `userId` | `confirmed` 响应 `ilink_user_id` | 扫码用户 ID |
| `savedAt` | 本地时间 | 记录时间 |
| `get_updates_buf`（游标） | `getupdates` 响应的 `get_updates_buf` | **另存**（corespeed 存为 `CURSOR` 键） |
| `context_token` 表 | 入站消息 `context_token`，按 `userId` | **另存**（corespeed `CONTEXT_TOKENS`） |
| `typing_ticket` 表 | `getconfig` 响应，按 `userId` | 可缓存 24h（非必需持久化） |

> corespeed 用 Storage 四个键：`CREDENTIALS`、`CURSOR`、`CONTEXT_TOKENS`、`TYPING_TICKETS`。腾讯把 `token/baseUrl/userId` 存在 `accounts/{accountId}.json`，游标存 `{accountId}.sync.json`，context 存 `{accountId}.context-tokens.json`。

### 10.2 重启后跳过扫码直接长轮询

1. 启动时 `loadCredentials()` 读到本地 `token`/`baseUrl` 即跳过扫码。
2. 用 `loadCursor()` 读回上次的 `get_updates_buf`（有则断点续传，无则传 `""`）。
3. 直接 `POST {baseUrl}/ilink/bot/getupdates` 开始长轮询。

> 注意：登录阶段的 `get_bot_qrcode`/`get_qrcode_status` **始终走固定 `https://ilinkai.weixin.qq.com`**；只有业务接口走持久化的 `baseUrl`。

### 10.3 登录态失效如何检测

- **任何接口**返回 `errcode === -14`（或业务 `ret` 超时后）即会话失效，主要发生在 `getupdates` 长轮询。
- corespeed 监测到 `ApiError.isSessionExpired`（`errcode === -14`）→ 发 `session:expired` 事件 → 客户端 `clearAll()`（删 `CREDENTIALS`/`CURSOR`/`CONTEXT_TOKENS`/`TYPING_TICKETS`）→ 重新 `qrLogin` 扫码。
- 登录成功后重新建立 context_token 表、游标置空重来。

源码出处：
- `corespeed-io/.../nodejs/src/auth/types.ts`（`Credentials`）
- `corespeed-io/.../nodejs/src/auth/authenticator.ts`（`login`、`clearAll`、`qrLogin`）
- `corespeed-io/.../nodejs/src/messaging/poller.ts`（`loadCursor`、`session:expired`）
- `Tencent/.../src/auth/accounts.ts`（`saveWeixinAccount`、`loadWeixinAccount`、`clearWeixinAccount`）

---

## 附录 A：接口速查表（真实路径，带 `/ilink/bot/` 前缀）

| 接口 | 方法 | 路径（相对基座） | 鉴权头 | 说明 |
|---|---|---|---|---|
| 获取二维码 | POST | `/ilink/bot/get_bot_qrcode?bot_type=3` | 无 Authorization | body `{local_token_list:[]}` |
| 轮询状态 | GET | `/ilink/bot/get_qrcode_status?qrcode=...` | 无 Authorization | 35s 长轮询 |
| 长轮询收消息 | POST | `/ilink/bot/getupdates` | Bearer | body `{get_updates_buf, base_info}` |
| 发送消息 | POST | `/ilink/bot/sendmessage` | Bearer | body `{msg, base_info}` |
| 取配置/ticket | POST | `/ilink/bot/getconfig` | Bearer | body `{ilink_user_id, context_token?, base_info}` |
| 输入状态 | POST | `/ilink/bot/sendtyping` | Bearer | body `{ilink_user_id, typing_ticket, status, base_info}` |
| 取上传参数 | POST | `/ilink/bot/getuploadurl` | Bearer | body 见 6.1 |
| 通知开始 | POST | `/ilink/bot/msg/notifystart` | Bearer | body `{base_info}` |
| 通知停止 | POST | `/ilink/bot/msg/notifystop` | Bearer | body `{base_info}` |
| CDN 上传 | POST | `{cdn}/upload?encrypted_query_param=..&filekey=..` | 无（octet-stream） | 响应头 `x-encrypted-param` |
| CDN 下载 | GET | `{cdn}/download?encrypted_query_param=..` | 无 | AES 密文响应体 |

## 附录 B：最小可用实现检查清单（从扫码到收发一条文本）

1. **取二维码**：`POST https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3`，body `{"local_token_list":[]}`，带 `iLink-App-Id: bot` + `iLink-App-ClientVersion` 头；解析 `qrcode`、`qrcode_img_content`，把 `qrcode_img_content` 展示给用户扫。
2. **轮询状态**：`GET .../ilink/bot/get_qrcode_status?qrcode=<qrcode>`（35s 超时，1~2s 间隔重试），直到 `status==="confirmed"`。
3. **保存凭证**：持久化 `bot_token`、`baseurl`（缺省 `https://ilinkai.weixin.qq.com`）、`ilink_bot_id`、`ilink_user_id`。
4. **第一次长轮询**：`POST {baseurl}/ilink/bot/getupdates`，body `{"get_updates_buf":"","base_info":{...}}`，带完整鉴权头（含 `X-WECHAT-UIN`=base64(十进制随机 u32)、`Authorization: Bearer <token>`、`AuthorizationType: ilink_bot_token`）。
5. **收消息**：解析 `ret`、`msgs[]`、`get_updates_buf`（持久化，下轮回传）；对 `msgs` 里 `message_type===1` 的每条，记录 `from_user_id` 与 `context_token`（按 userId 缓存）。
6. **回复文本**：构造 + `POST {baseurl}/ilink/bot/sendmessage`：
   - `msg.from_user_id=""`、`to_user_id=<from_user_id>`、`client_id=<UUID>`、`message_type=2`、`message_state=2`、`context_token=<收到的 token>`、`item_list=[{type:1,text_item:{text:"你好"}}]`；顶层带 `base_info`。
   - 响应 `ret===0` 即成功。
7. **长文本分片**：文本 > 4000 字符时按 4000 切（段落→换行→空格→硬切），每个分片独立发一条 `sendmessage`。
8. **会话失效处理**：任何接口响应 `errcode===-14` → 清凭证/游标/context → 回到步骤 1 重新扫码。
9. **（可选）输入状态**：`POST getconfig`（带 `ilink_user_id` + `context_token`）拿 `typing_ticket` → `POST sendtyping` `{status:1}` 开始 / `{status:2}` 停止。
10. **（媒体，可选）**：生成 16 字节 AES key → `getuploadurl`（`rawsize`/明文 md5/`filesize`=密文大小/`aeskey`=hex）→ CDN POST 密文拿 `x-encrypted-param` → 组 `CDNMedia{encrypt_query_param, aes_key:base64(hex), encrypt_type:1}` 填 item → `sendmessage`。

---

## 附录 C：需求清单里「未在源码中找到」的字段汇总

| 需求问到的 | 源码实际情况 |
|---|---|
| 登录 `confirmed` 响应的 `ret` | **未在源码类型定义中找到**；判定成功只看 `status === "confirmed"`。`ret` 只出现在业务接口（getupdates/sendmessage/getconfig/getuploadurl/notify*）响应里 |
| `updates` 数组 | **无此字段**；消息数组名为 **`msgs`** |
| `userId` | **无此字段名**；发送者用 `from_user_id`，接收者用 `to_user_id` |
| `msgId`（驼峰） | **无此字段名**；对应 `message_id`（顶层）/ `msg_id`（item 级，string） |
| `send_time` | **无此字段名**；对应 `create_time_ms`（毫秒） |
| `msg_id 回复引用`（发送请求里的字段） | SDK 发文本消息**不填** `message_id`/`msg_id`；引用回复通过 item 的 `ref_msg`（含 `title` + `message_item`）表达 |
| AES 自定义 IV / 前 16 字节特殊处理 | **无**；标准 `aes-128-ecb` + PKCS7，iv 传 `null`，加密前不额外处理 |
| 「2000 字符」分片 | 源码注释里的过时说法；**实际 `MAX_TEXT_LENGTH = 4000`** |
| 上传 PUT | **无**；CDN 上传是 **POST**（`Content-Type: application/octet-stream`），下载是 GET |

## 附录 D：隐藏细节（超出需求清单，实现务必注意）

1. **路径带 `/ilink/bot/` 前缀**（文档页省写，源码才是真相）。
2. **`iLink-App-Id` / `iLink-App-ClientVersion` 两个头**每个请求都带：Id 固定 `bot`（或读 `package.json` 的 `ilink_appid`），ClientVersion 是 `0x00MMNNPP` 编码的 uint32 十进制字符串。
3. **`X-WECHAT-UIN` 是 base64(十进制字符串)**，每次请求重新生成，不是「base64 编码 4 字节」。
4. **`message_type` 过滤**：只处理 `message_type===1`（USER），`2`（BOT）是自身消息回显要忽略。
5. **`local_token_list`**（最多 10 个、新在前）用于 `binded_redirect` 免重复登录。
6. **`scaned_but_redirect` IDC 重定向**：改轮询主机为 `https://<redirect_host>`。
7. **扫码阶段 GET 也是长轮询**（35s 超时），不是固定间隔短轮询。
8. **`notifystart`/`notifystop`** 在线状态通知接口（失败非致命，可略）。
9. **图片双 AES 来源**：`image_item.aeskey`（hex）优先于 `media.aes_key`（base64）；都无则明文下载。
10. **`filesize` / `len` 的类型**：`filesize` 是 number，但 `FileItem.len` 是 **string**（大小），`thumb_size`/`mid_size`/`hd_size`/`video_size` 是 number|string。
11. CDN 上传成功后下载参数在**响应头 `x-encrypted-param`**，不在响应体。
