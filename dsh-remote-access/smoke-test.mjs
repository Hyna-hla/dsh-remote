// smoke-test.mjs — iLink 客户端冒烟测试：真实请求腾讯服务器
// 验证：登录二维码接口（路径/头/报文）、扫码状态轮询、AES、分片
import { ILinkClient, aesEncrypt, aesDecrypt, encryptedSize, decodeAesKey, chunkText } from "./lib/ilink.js";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));

// 1) AES 自测（用规范里的三种 key 格式）
const key = Buffer.from("00112233445566778899aabbccddeeff", "hex");
const plain = Buffer.from("hello wechat cdn, 你好微信");
const enc = aesEncrypt(key, plain);
const dec = aesDecrypt(key, enc);
console.log("AES roundtrip:", dec.equals(plain) ? "PASS" : "FAIL");
console.log("encryptedSize(12345):", encryptedSize(12345), encryptedSize(12345) === 12352 ? "PASS" : "FAIL");
console.log("decodeAesKey fmtA:", decodeAesKey("ABEiM0RVZneImaq7zN3u/w==").toString("hex") === "00112233445566778899aabbccddeeff" ? "PASS" : "FAIL");
console.log("decodeAesKey fmtB:", decodeAesKey("MDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmY=").toString("hex") === "00112233445566778899aabbccddeeff" ? "PASS" : "FAIL");
console.log("decodeAesKey fmtC:", decodeAesKey("00112233445566778899aabbccddeeff").toString("hex") === "00112233445566778899aabbccddeeff" ? "PASS" : "FAIL");
const long = "甲".repeat(9500);
console.log("chunkText:", chunkText(long).map((c) => c.length), chunkText(long).every((c) => c.length <= 4000) ? "PASS" : "FAIL");

// 2) 真实请求腾讯：取二维码
const client = new ILinkClient({ stateFile: join(here, ".smoke-state.json"), log: (m, e) => console.log("  [log]", m, e?.message ?? "") });

console.log("\n=== 登录二维码接口 ===");
try {
  const qr = await client._requestQr([]);
  console.log("get_bot_qrcode PASS");
  console.log("  qrcode:", String(qr.qrcode).slice(0, 24) + "...");
  console.log("  qrcode_img_content:", String(qr.qrcode_img_content).slice(0, 80) + (String(qr.qrcode_img_content).length > 80 ? "..." : ""));
} catch (err) {
  console.log("get_bot_qrcode FAIL:", err.message);
}

console.log("\n=== 扫码状态轮询（期望 wait，二维码未扫） ===");
try {
  const qr = await client._requestQr([]);
  const st = await client._pollStatus(qr.qrcode, null, null);
  console.log("get_qrcode_status PASS, status =", st.status);
} catch (err) {
  console.log("get_qrcode_status FAIL:", err.message);
}

console.log("\n=== 状态快照 ===");
console.log(JSON.stringify(client.statusInfo(), null, 2));
console.log("\n冒烟测试完成（注意：不要扫这个二维码——它会登录一个测试 bot 会话，直接过期即可）");
