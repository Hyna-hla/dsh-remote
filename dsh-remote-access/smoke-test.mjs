// smoke-test.mjs — dsh-remote-access v2.4.0 冒烟测试（离线、零依赖、可重复运行）
// 用最小 fake webServer 驱动 apply()，经真实 HTTP 服务器走完整链路：
//   配对握手（request → status → respond → check 下发 token → list/revoke）、
//   远程通道 Bearer 门禁（loopback 放行 / XFF 模拟公网隧道 401 / 带 token 放行 / 引导通道豁免）、
//   fs/list + fs/read（只读目录与 1MB 截断二进制识别）、mcp/list 聚合、device/info。
// 运行：node --test smoke-test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { apply } from "./lib/index.js";

const home = mkdtempSync(join(tmpdir(), "dsh-ra-smoke-"));
process.env.DSH_HOME = home;

// —— fake webServer：Map 路由表 + register()（形状对齐 DSH webServer 关键面） ——
function makeWebServer() {
  const server = {
    exact: new Map(),
    prefixes: new Map(),
    upgrades: new Map(),
    register(route) {
      const table = route.kind === "exact" ? server.exact : server.prefixes;
      table.set(route.path, route);
      return () => table.delete(route.path);
    },
  };
  return server;
}

const ctx = {
  webServer: makeWebServer(),
  get(key) {
    if (key === "tools") return null;
    return undefined;
  },
  effect() {
    return () => {};
  },
};

apply(ctx);

const srv = http.createServer((req, res) => {
  const pathname = new URL(req.url, "http://x").pathname;
  const route = ctx.webServer.exact.get(pathname);
  if (!route) {
    res.statusCode = 404;
    res.end(JSON.stringify({ error: "not found" }));
    return;
  }
  route.handler(req, res);
});

let listening = null;
function listen() {
  if (!listening) {
    listening = new Promise((r) => srv.listen(0, "127.0.0.1", r));
  }
  return listening;
}
async function req(path, opts = {}) {
  await listen(); // 幂等：并发测试下等待服务器就绪
  const port = srv.address().port;
  return new Promise((resolve, reject) => {
    const r = http.request({ host: "127.0.0.1", port, path, method: opts.method || "GET", headers: opts.headers || {} }, (res) => {
      let body = "";
      res.on("data", (c) => (body += c));
      res.on("end", () => resolve({ status: res.statusCode, body: JSON.parse(body || "{}") }));
    });
    r.on("error", reject);
    if (opts.body) r.write(JSON.stringify(opts.body));
    r.end();
  });
}

// 在 apply 之后注册一个受保护路由：走 proxy set 陷阱包裹（验证晚注册同样被门禁兜住）
ctx.webServer.exact.set("/api/protected-test", {
  kind: "exact",
  path: "/api/protected-test",
  handler: (_req, res) => {
    res.statusCode = 200;
    res.setHeader("Content-Type", "application/json; charset=utf-8");
    res.end(JSON.stringify({ ok: true }));
  },
});

test("配对全流程：request → status → respond(approve) → check 下发 token → list → revoke", async () => {
  const port = await listen();
  const deviceId = "device-aaaa-1111";

  // 1) 首次握手 → pending
  let r = await req("/api/remote-access/pair/request", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId, deviceName: "冒烟测试机" },
  });
  assert.equal(r.body.ok, true);
  assert.equal(r.body.state, "pending");

  // 2) 状态轮询 → pending，且带 pendingDevice
  r = await req("/api/remote-access/pair/status");
  assert.equal(r.body.state, "pending");
  assert.equal(r.body.pendingDevice.deviceId, deviceId);
  assert.equal(r.body.pendingDevice.name, "冒烟测试机");

  // 3) 重复请求不丢 pending、覆盖名称
  r = await req("/api/remote-access/pair/request", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId, deviceName: "改名机" },
  });
  assert.equal(r.body.state, "pending");
  r = await req("/api/remote-access/pair/status");
  assert.equal(r.body.pendingDevice.name, "改名机");

  // 4) PC 批准
  r = await req("/api/remote-access/pair/respond", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId, outcome: "approve" },
  });
  assert.equal(r.body.ok, true);
  assert.equal(r.body.state, "approved");

  // 5) pair/check → paired + 通道 token（48 hex）
  r = await req("/api/remote-access/pair/check?deviceId=" + encodeURIComponent(deviceId));
  assert.equal(r.body.paired, true);
  assert.match(r.body.token, /^[0-9a-f]{32,}$/);

  // 6) pair/list 可见
  r = await req("/api/remote-access/pair/list");
  assert.equal(r.body.devices.length, 1);
  assert.equal(r.body.devices[0].deviceId, deviceId);

  // 7) 撤销 → check 未配对、不再下发 token
  r = await req("/api/remote-access/pair/revoke", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId },
  });
  assert.equal(r.body.ok, true);
  r = await req("/api/remote-access/pair/check?deviceId=" + encodeURIComponent(deviceId));
  assert.equal(r.body.paired, false);
  assert.equal(r.body.token, undefined);
});

test("配对拒绝：respond(deny) → status denied，不写入配对表", async () => {
  const deviceId = "device-bbbb-2222";
  let r = await req("/api/remote-access/pair/request", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId, deviceName: "拒绝机" },
  });
  assert.equal(r.body.state, "pending");
  r = await req("/api/remote-access/pair/respond", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId, outcome: "deny" },
  });
  assert.equal(r.body.state, "denied");
  r = await req("/api/remote-access/pair/status");
  assert.equal(r.body.state, "denied");
  r = await req("/api/remote-access/pair/check?deviceId=" + encodeURIComponent(deviceId));
  assert.equal(r.body.paired, false);
  // 错误 respond（无 pending）→ ok:false
  r = await req("/api/remote-access/pair/respond", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId: "ghost", outcome: "approve" },
  });
  assert.equal(r.body.ok, false);
});

test("通道门禁：本机放行 / 公网隧道（XFF）401 / 带 token 放行 / 引导通道豁免", async () => {
  // 本机 loopback 无 XFF → 放行（设置页浏览器）
  let r = await req("/api/protected-test");
  assert.equal(r.status, 200);

  // 模拟公网隧道：loopback 但带 x-forwarded-for → 401
  r = await req("/api/protected-test", { headers: { "x-forwarded-for": "1.2.3.4" } });
  assert.equal(r.status, 401);

  // 带正确 Bearer token → 放行
  const check = await req("/api/remote-access/pair/check?deviceId=whatever"); // 引导通道豁免
  assert.equal(check.status, 200);
  // 先配对拿到 token
  await req("/api/remote-access/pair/request", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId: "device-gate-3333", deviceName: "门禁机" },
  });
  await req("/api/remote-access/pair/respond", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId: "device-gate-3333", outcome: "approve" },
  });
  const c2 = await req("/api/remote-access/pair/check?deviceId=device-gate-3333");
  assert.match(c2.body.token, /^[0-9a-f]{32,}$/);
  r = await req("/api/protected-test", {
    headers: { "x-forwarded-for": "1.2.3.4", authorization: "Bearer " + c2.body.token },
  });
  assert.equal(r.status, 200);

  // 错 token → 401
  r = await req("/api/protected-test", {
    headers: { "x-forwarded-for": "1.2.3.4", authorization: "Bearer deadbeef" },
  });
  assert.equal(r.status, 401);

  // host.describe 豁免（公网隧道下也放行，供连接探测）
  r = await req("/api/host.describe", { headers: { "x-forwarded-for": "1.2.3.4" } });
  assert.notEqual(r.status, 401);

  // 缺 deviceId 的握手 → 400
  r = await req("/api/remote-access/pair/request", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceName: "无名" },
  });
  assert.equal(r.status, 400);
});

test("fs/list + fs/read：目录列举、文本预览、二进制识别、1MB 截断", async () => {
  const dir = join(home, "fs-fixture");
  mkdirSync(join(dir, "sub"), { recursive: true });
  writeFileSync(join(dir, "hello.txt"), "你好，远程工作区");
  writeFileSync(join(dir, "bin.dat"), Buffer.from([0x00, 0x01, 0x02, 0x00, 0xff]));
  writeFileSync(join(dir, "big.txt"), "x".repeat(1024 * 1024 + 8));

  // 相对路径 → 400
  let r = await req("/api/remote-access/fs/list?path=" + encodeURIComponent("relative/dir"));
  assert.equal(r.status, 400);

  r = await req("/api/remote-access/fs/list?path=" + encodeURIComponent(dir));
  assert.equal(r.body.ok, true);
  assert.deepEqual(r.body.dirs, ["sub"]);
  const names = r.body.files.map((f) => f.name).sort();
  assert.deepEqual(names, ["big.txt", "bin.dat", "hello.txt"]);

  // 文本预览
  r = await req("/api/remote-access/fs/read?path=" + encodeURIComponent(join(dir, "hello.txt")));
  assert.equal(r.body.ok, true);
  assert.equal(r.body.isBinary, false);
  assert.equal(r.body.text, "你好，远程工作区");

  // 二进制识别 → base64 data
  r = await req("/api/remote-access/fs/read?path=" + encodeURIComponent(join(dir, "bin.dat")));
  assert.equal(r.body.ok, true);
  assert.equal(r.body.isBinary, true);
  assert.equal(Buffer.from(r.body.data, "base64").length, 5);

  // 超过 1MB → 截断
  r = await req("/api/remote-access/fs/read?path=" + encodeURIComponent(join(dir, "big.txt")));
  assert.equal(r.body.ok, true);
  assert.equal(r.body.truncated, true);
  assert.equal(r.body.text.length, 1024 * 1024);
});

test("mcp/list：按 mcp__<server>__<tool> 聚合；无 tools 服务时空列表", async () => {
  ctx.get = (key) =>
    key === "tools"
      ? {
          schemas: () => [
            { name: "mcp__github__search_repos" },
            { name: "mcp__github__list_issues" },
            { name: "mcp__filesystem__read_file" },
            { name: "plain-tool" },
          ],
        }
      : undefined;
  let r = await req("/api/remote-access/mcp/list");
  assert.equal(r.body.ok, true);
  assert.deepEqual(
    r.body.servers.map((s) => s.serverName).sort(),
    ["filesystem", "github"],
  );
  const gh = r.body.servers.find((s) => s.serverName === "github");
  assert.equal(gh.tools.length, 2);

  ctx.get = () => undefined;
  r = await req("/api/remote-access/mcp/list");
  assert.equal(r.body.ok, true);
  assert.deepEqual(r.body.servers, []);
});

test("device/info：主机名 + MAC + 平台（win32 机型探测失败时降级不报错）", async () => {
  const r = await req("/api/remote-access/device/info");
  assert.equal(r.body.ok, true);
  assert.equal(typeof r.body.name, "string");
  assert.equal(typeof r.body.platform, "string");
  assert.equal(typeof r.body.model, "string");
  assert.ok(r.body.model.length > 0);
});

test("配对码流程：generate → 正确码一次配对下发 token → 一次性作废", async () => {
  // generate 不豁免：本机 loopback（无 XFF）放行，模拟设置页浏览器
  let r = await req("/api/remote-access/pair/code/generate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: {},
  });
  assert.equal(r.body.ok, true);
  assert.match(r.body.code, /^\d{6}$/);
  const code = r.body.code;

  // 公网隧道（XFF）调 generate → 401（不可远程生成）
  r = await req("/api/remote-access/pair/code/generate", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-forwarded-for": "1.2.3.4" },
    body: {},
  });
  assert.equal(r.status, 401);

  // 错误码 → code_mismatch + 剩余次数（verify 豁免，XFF 可达）
  const wrong = code === "000000" ? "000001" : "000000";
  r = await req("/api/remote-access/pair/code/verify", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-forwarded-for": "2.3.4.5" },
    body: { code: wrong, deviceId: "device-code-1111", deviceName: "配对码机" },
  });
  assert.equal(r.body.ok, false);
  assert.equal(r.body.error, "code_mismatch");
  assert.equal(r.body.attemptsLeft, 4);

  // 正确码 → 立即配对 + 下发通道 token
  r = await req("/api/remote-access/pair/code/verify", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-forwarded-for": "2.3.4.5" },
    body: { code, deviceId: "device-code-1111", deviceName: "配对码机" },
  });
  assert.equal(r.body.ok, true);
  assert.equal(r.body.state, "approved");
  assert.match(r.body.token, /^[0-9a-f]{32,}$/);

  // 一次性：同码再来 → no_active_code
  r = await req("/api/remote-access/pair/code/verify", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { code, deviceId: "device-code-2222", deviceName: "蹭码机" },
  });
  assert.equal(r.body.ok, false);
  assert.equal(r.body.error, "no_active_code");

  // 已写入配对表（本机 list 放行）
  r = await req("/api/remote-access/pair/list");
  assert.ok(r.body.devices.some((d) => d.deviceId === "device-code-1111"));

  // 无活跃码时的 verify → no_active_code（无爆破面）
  r = await req("/api/remote-access/pair/code/verify", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { code: "123456", deviceId: "device-code-3333", deviceName: "无人码" },
  });
  assert.equal(r.body.error, "no_active_code");
});

test("配对码锁定：连续 5 次错误 → 第 6 次即使用对码也 locked", async () => {
  let r = await req("/api/remote-access/pair/code/generate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: {},
  });
  const code = r.body.code;
  const wrong = code === "000000" ? "000001" : "000000";
  for (let i = 0; i < 5; i++) {
    r = await req("/api/remote-access/pair/code/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: { code: wrong, deviceId: "device-bruteforce", deviceName: "爆破机" },
    });
    assert.equal(r.body.error, "code_mismatch", "第" + (i + 1) + "次应为 mismatch");
  }
  r = await req("/api/remote-access/pair/code/verify", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { code, deviceId: "device-bruteforce", deviceName: "爆破机" },
  });
  assert.equal(r.body.error, "code_locked");
});

test("安全回归：self-approve 漏洞已修复（远程 respond 401，不写入配对表）", async () => {
  // 旧漏洞路径：远程 request 建立 pending 后远程自我 approve
  let r = await req("/api/remote-access/pair/request", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-forwarded-for": "3.4.5.6" },
    body: { deviceId: "device-evil-3333", deviceName: "恶意机" },
  });
  assert.equal(r.body.state, "pending");
  r = await req("/api/remote-access/pair/respond", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-forwarded-for": "3.4.5.6" },
    body: { deviceId: "device-evil-3333", outcome: "approve" },
  });
  assert.equal(r.status, 401);
  // 未写入配对表
  r = await req("/api/remote-access/pair/list");
  assert.ok(!r.body.devices.some((d) => d.deviceId === "device-evil-3333"));
  // 本机清理 pending（loopback 放行）
  r = await req("/api/remote-access/pair/respond", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId: "device-evil-3333", outcome: "deny" },
  });
  assert.equal(r.body.ok, true);
  // 远程 list/revoke 同样 401（设置页管理操作不可远程调用）
  r = await req("/api/remote-access/pair/list", { headers: { "x-forwarded-for": "3.4.5.6" } });
  assert.equal(r.status, 401);
});

test("公网域名白名单：校验/增删/去重 + connection 行 !!js 渲染 + 空列表清理", async () => {
  // 初始为空（DSH_HOME 临时目录）
  let r = await req("/api/remote-access/trusted-hosts");
  assert.equal(r.body.ok, true);
  assert.deepEqual(r.body.hosts, []);

  // 远程（XFF）访问 → 401（非豁免，仅本机设置页可达）
  r = await req("/api/remote-access/trusted-hosts", { headers: { "x-forwarded-for": "1.2.3.4" } });
  assert.equal(r.status, 401);

  // 非法条目逐个拒绝（含会破坏 !!js 表达式 / YAML 的引号、反斜杠、用户信息、路径）
  for (const bad of ["https://x.com", "x.com/path", "user@x.com", "", "exa mple.com", "a'b.com", 'a"b.com', "x.com\\y", "例子.测试"]) {
    r = await req("/api/remote-access/trusted-hosts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: { action: "add", host: bad },
    });
    assert.equal(r.body.ok, false, JSON.stringify(bad) + " 应被拒绝");
  }

  // 正常添加 + 大小写去重
  r = await req("/api/remote-access/trusted-hosts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { action: "add", host: "tunnel.example.com" },
  });
  assert.equal(r.body.ok, true);
  assert.deepEqual(r.body.hosts, ["tunnel.example.com"]);
  r = await req("/api/remote-access/trusted-hosts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { action: "add", host: "tunnel2.example.com:8080" },
  });
  assert.deepEqual(r.body.hosts, ["tunnel.example.com", "tunnel2.example.com:8080"]);
  r = await req("/api/remote-access/trusted-hosts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { action: "add", host: "TUNNEL.example.com" },
  });
  assert.deepEqual(r.body.hosts, ["tunnel2.example.com:8080", "TUNNEL.example.com"]);

  // 落盘：cordis.patch.yml 渲染 connection 行（!!js 拼接运行时派生权威），JSON 为真身
  const patchText = readFileSync(join(home, "profiles", "web", "cordis.patch.yml"), "utf8");
  assert.match(patchText, /^- id: connection$/m);
  assert.match(patchText, /trustedHosts: !!js '\[\.\.\.ctx\.webRuntime\.trustedHosts, "tunnel2\.example\.com:8080", "TUNNEL\.example\.com"\]'/);
  const json = JSON.parse(readFileSync(join(home, "remote-access", "trusted-hosts.json"), "utf8"));
  assert.deepEqual(json, ["tunnel2.example.com:8080", "TUNNEL.example.com"]);

  // 删除
  r = await req("/api/remote-access/trusted-hosts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { action: "remove", host: "tunnel2.example.com:8080" },
  });
  assert.deepEqual(r.body.hosts, ["TUNNEL.example.com"]);
  r = await req("/api/remote-access/trusted-hosts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { action: "remove", host: "TUNNEL.example.com" },
  });
  assert.deepEqual(r.body.hosts, []);
  const after = readFileSync(join(home, "profiles", "web", "cordis.patch.yml"), "utf8");
  assert.ok(!/^- id: connection$/m.test(after), "空列表应删除 connection 条目");
  assert.match(after, /\[\]/, "文件必须仍是合法顶层数组（空文件会让 Loader loud fail）");
});

test("公网域名白名单：v2.3.0 旧 web-runtime 块自动迁移到 connection 行", async () => {
  // 模拟 v2.3.0 残留：删掉 JSON 真身，patch 文件里只有旧 web-runtime 字面量块
  rmSync(join(home, "remote-access", "trusted-hosts.json"), { force: true });
  const legacy = [
    "- id: web-runtime",
    "  config:",
    "    printUrl: true",
    "    surfaceContext: true",
    "    trustedHosts:",
    "      - legacy.example.com",
    "      - legacy2.example.com:9090",
    "",
  ].join("\n");
  mkdirSync(join(home, "profiles", "web"), { recursive: true });
  writeFileSync(join(home, "profiles", "web", "cordis.patch.yml"), legacy, "utf8");

  const r = await req("/api/remote-access/trusted-hosts");
  assert.equal(r.body.ok, true);
  assert.deepEqual(r.body.hosts, ["legacy.example.com", "legacy2.example.com:9090"]);

  // 迁移后：JSON 真身写入 + patch 文件换成 connection 行，旧块删除
  const patchText = readFileSync(join(home, "profiles", "web", "cordis.patch.yml"), "utf8");
  assert.ok(!/^- id: web-runtime$/m.test(patchText), "旧 web-runtime 条目应被清理");
  assert.match(patchText, /^- id: connection$/m);
  assert.match(patchText, /legacy2\.example\.com:9090/);
  const json = JSON.parse(readFileSync(join(home, "remote-access", "trusted-hosts.json"), "utf8"));
  assert.deepEqual(json, ["legacy.example.com", "legacy2.example.com:9090"]);

  // 复位：清空白名单，保证后续用例环境干净
  await req("/api/remote-access/trusted-hosts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { action: "remove", host: "legacy.example.com" },
  });
  await req("/api/remote-access/trusted-hosts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { action: "remove", host: "legacy2.example.com:9090" },
  });
});

test("公网域名白名单：DSH 模板占位 [] 会被剥离（不产生非法 YAML）", async () => {
  // 模拟 DSH 初始化的 profile patch：模板注释 + `[]` 占位（真实 web profile 的初始内容）
  const template = [
    "# Your patch layer for this dsh profile, applied after every bundle layer:",
    "# a top-level YAML array of loader patch entries (id-targeted config",
    "# overrides, disables, and insert lists; `!!js` expressions allowed).",
    "[]",
    "",
  ].join("\n");
  writeFileSync(join(home, "profiles", "web", "cordis.patch.yml"), template, "utf8");
  rmSync(join(home, "remote-access", "trusted-hosts.json"), { force: true });

  let r = await req("/api/remote-access/trusted-hosts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { action: "add", host: "placeholder.example.com" },
  });
  assert.equal(r.body.ok, true);
  const text = readFileSync(join(home, "profiles", "web", "cordis.patch.yml"), "utf8");
  assert.ok(!/^\s*\[\]\s*$/m.test(text), "模板 [] 占位必须被剥离，否则文件非法 YAML");
  assert.match(text, /^- id: connection$/m);
  assert.match(text, /placeholder\.example\.com/);

  // 恢复空状态：条目删除、文件回到模板占位
  r = await req("/api/remote-access/trusted-hosts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { action: "remove", host: "placeholder.example.com" },
  });
  assert.deepEqual(r.body.hosts, []);
  const after = readFileSync(join(home, "profiles", "web", "cordis.patch.yml"), "utf8");
  assert.match(after, /^\[\]$/m);
});

test("M1 双向文件：fs/write → stat → read 回读；沙箱拒穿越；overwrite 409；远程 401", async () => {
  const target = join(home, "remote-access", "uploads", "shot.txt");
  const text = "你好，DSH 从手机上传";
  const content = Buffer.from(text, "utf8").toString("base64");

  // 沙箱外（home 之外）→ 拒绝
  let r = await req("/api/remote-access/fs/write", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { path: join(tmpdir(), "escape.txt"), content },
  });
  assert.equal(r.body.ok, false);
  assert.equal(r.body.error, "path_not_in_workspace");

  // 路径穿越（../ 想逃出 home）→ 拒绝
  r = await req("/api/remote-access/fs/write", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { path: join(home, "..", "..", "evil.txt"), content },
  });
  assert.equal(r.body.ok, false);
  assert.equal(r.body.error, "path_not_in_workspace");

  // 远程（XFF）无 token → 401
  r = await req("/api/remote-access/fs/write", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-forwarded-for": "9.9.9.9" },
    body: { path: target, content },
  });
  assert.equal(r.status, 401);

  // 写入（自动建父目录）→ 200
  r = await req("/api/remote-access/fs/write", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { path: target, content },
  });
  assert.equal(r.body.ok, true);
  assert.equal(r.body.size, Buffer.byteLength(text));
  assert.equal(r.body.path, target);

  // overwrite=false 同名 → 409
  r = await req("/api/remote-access/fs/write", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { path: target, content },
  });
  assert.equal(r.status, 409);

  // stat
  r = await req("/api/remote-access/fs/stat?path=" + encodeURIComponent(target));
  assert.equal(r.body.ok, true);
  assert.equal(r.body.isFile, true);
  assert.equal(r.body.size, Buffer.byteLength(text));

  // 回读一致
  r = await req("/api/remote-access/fs/read?path=" + encodeURIComponent(target));
  assert.equal(r.body.isBinary, false);
  assert.equal(r.body.text, text);

  // App 友好：只给 name → 自动落入 uploads/ 且 basename 去路径（../ 被剥离）
  r = await req("/api/remote-access/fs/write", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { name: "../evil-name.txt", content: Buffer.from("by name").toString("base64") },
  });
  assert.equal(r.body.ok, true);
  assert.match(r.body.path, /[\\/]uploads[\\/]evil-name\.txt$/);
  r = await req("/api/remote-access/fs/read?path=" + encodeURIComponent(r.body.path));
  assert.equal(r.body.text, "by name");
});

test("M1 双向文件：fs/mkdir（recursive）+ fs/delete 进回收站（trash）", async () => {
  const dir = join(home, "remote-access", "uploads", "d");
  let r = await req("/api/remote-access/fs/mkdir", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { path: dir, recursive: true },
  });
  assert.equal(r.body.ok, true);
  assert.equal(r.body.created, true);
  r = await req("/api/remote-access/fs/mkdir", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { path: dir, recursive: true },
  });
  assert.equal(r.body.created, false);

  // 写一个文件再删除 → 进回收站（trashId）
  const f = join(dir, "a.txt");
  await req("/api/remote-access/fs/write", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { path: f, content: Buffer.from("x").toString("base64") },
  });
  r = await req("/api/remote-access/fs/delete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { path: f },
  });
  assert.equal(r.body.ok, true);
  assert.ok(r.body.trashId);
  r = await req("/api/remote-access/fs/stat?path=" + encodeURIComponent(f));
  assert.equal(r.body.ok, false);
  assert.equal(r.body.error, "not_found");

  // 删除 home 根 → 拒绝（保护性）
  r = await req("/api/remote-access/fs/delete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { path: home },
  });
  assert.equal(r.body.error, "cannot_delete_root");
});

test("M4 token 运维：rotate 换新 / 旧失效 / revoke 清配对 / audit 有记录 / 远程 401", async () => {
  await req("/api/remote-access/pair/request", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId: "dev-tok-1", deviceName: "令牌机" },
  });
  await req("/api/remote-access/pair/respond", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId: "dev-tok-1", outcome: "approve" },
  });
  const c = await req("/api/remote-access/pair/check?deviceId=dev-tok-1");
  const oldToken = c.body.token;
  assert.match(oldToken, /^[0-9a-f]{32,}$/);

  // 旧 token 当前有效
  let r = await req("/api/protected-test", {
    headers: { "x-forwarded-for": "1.2.3.4", authorization: "Bearer " + oldToken },
  });
  assert.equal(r.status, 200);

  // 本机轮换 → 新 token ≠ 旧
  r = await req("/api/remote-access/token/rotate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: {},
  });
  assert.equal(r.body.ok, true);
  const newToken = r.body.token;
  assert.ok(newToken && newToken !== oldToken);

  // 旧 token 401，新 token 放行
  r = await req("/api/protected-test", {
    headers: { "x-forwarded-for": "1.2.3.4", authorization: "Bearer " + oldToken },
  });
  assert.equal(r.status, 401);
  r = await req("/api/protected-test", {
    headers: { "x-forwarded-for": "1.2.3.4", authorization: "Bearer " + newToken },
  });
  assert.equal(r.status, 200);

  // audit 有 rotate 记录（本机可查）
  r = await req("/api/remote-access/token/audit");
  assert.ok(r.body.events.some((e) => e.action === "token/rotate"));

  // 远程未授权调 rotate → 401
  r = await req("/api/remote-access/token/rotate", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-forwarded-for": "8.8.8.8" },
    body: {},
  });
  assert.equal(r.status, 401);

  // 吊销（本机）→ 清空配对表；新 token 也被吊销
  r = await req("/api/remote-access/token/revoke", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: {},
  });
  assert.equal(r.body.ok, true);
  r = await req("/api/remote-access/pair/list");
  assert.equal(r.body.devices.length, 0);
  r = await req("/api/protected-test", {
    headers: { "x-forwarded-for": "1.2.3.4", authorization: "Bearer " + newToken },
  });
  assert.equal(r.status, 401);
});

test("M3 MCP 资源/提示词：resources/list + read / prompts/list + render（能力清册）", async () => {
  ctx.get = (key) =>
    key === "tools"
      ? {
          schemas: () => [
            { name: "mcp__github__resources__list", description: "列出 GitHub 资源" },
            { name: "mcp__github__resources__read", description: "读取 GitHub 资源", mimeType: "text/markdown" },
            { name: "mcp__github__prompts__code_review", description: "代码评审", inputSchema: { type: "object", properties: { pr: { type: "string" } } } },
            { name: "mcp__filesystem__resources__read" },
            { name: "plain" },
          ],
        }
      : undefined;

  let r = await req("/api/remote-access/mcp/resources/list");
  assert.equal(r.body.ok, true);
  const github = r.body.servers.find((s) => s.serverName === "github");
  assert.equal(github.resources.length, 2);
  assert.ok(github.resources.some((x) => x.uri === "mcp://github/resources__list"));

  r = await req("/api/remote-access/mcp/resources/read?uri=" + encodeURIComponent("mcp://github/resources__list"));
  assert.equal(r.body.ok, true);
  assert.equal(r.body.note, "capability_schema");

  r = await req("/api/remote-access/mcp/prompts/list");
  const g2 = r.body.servers.find((s) => s.serverName === "github");
  assert.equal(g2.prompts.length, 1);
  // 能力清册：条目 = 能力工具全名（prompts__code_review）
  assert.equal(g2.prompts[0].name, "prompts__code_review");
  assert.ok(g2.prompts[0].argumentsSchema.properties.pr);

  r = await req("/api/remote-access/mcp/prompts/render", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { name: "prompts__code_review", arguments: { pr: "https://github.com/x/y/pull/1" } },
  });
  assert.equal(r.body.ok, true);
  assert.equal(r.body.messages[0].role, "user");

  r = await req("/api/remote-access/mcp/prompts/render", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { name: "ghost_prompt", arguments: {} },
  });
  assert.equal(r.body.error, "prompt_not_found");

  ctx.get = () => undefined;
});

test("M2 离线同步：POST /sync 批量 ACK + 非法 deviceId 400", async () => {
  let r = await req("/api/remote-access/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId: "dev-sync-1", events: [{ id: 1, type: "chat" }, { id: 2, type: "approval" }] },
  });
  assert.equal(r.body.ok, true);
  assert.equal(r.body.results.length, 2);
  assert.ok(r.body.results.every((x) => x.ok === true));

  // deviceId 非白名单（路径穿越）→ 400
  r = await req("/api/remote-access/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId: "../evil", events: [] },
  });
  assert.equal(r.status, 400);
});

test("M2 离线同步：sync/pending 拉取 + ACK 清除", async () => {
  // 手工写入该设备一条 server→device 待确认事件（模拟服务端入队）
  const f = join(home, "remote-access", "sync", "dev-sync-2.json");
  mkdirSync(join(home, "remote-access", "sync"), { recursive: true });
  writeFileSync(f, JSON.stringify({ pending: [{ id: 101, type: "approval", payload: "问题升级" }] }), "utf8");

  let r = await req("/api/remote-access/sync/pending?deviceId=dev-sync-2");
  assert.equal(r.body.ok, true);
  assert.equal(r.body.pending.length, 1);
  assert.equal(r.body.pending[0].id, 101);

  // ACK 清除
  r = await req("/api/remote-access/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: { deviceId: "dev-sync-2", events: [], acked: [101] },
  });
  assert.equal(r.body.ok, true);

  r = await req("/api/remote-access/sync/pending?deviceId=dev-sync-2");
  assert.equal(r.body.pending.length, 0);
});

test.after(() => {
  srv.close();
  rmSync(home, { recursive: true, force: true });
});
