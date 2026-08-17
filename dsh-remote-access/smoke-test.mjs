// smoke-test.mjs — dsh-remote-access v2.0.0 冒烟测试（离线、零依赖、可重复运行）
// 用最小 fake webServer 驱动 apply()，经真实 HTTP 服务器走完整链路：
//   配对握手（request → status → respond → check 下发 token → list/revoke）、
//   远程通道 Bearer 门禁（loopback 放行 / XFF 模拟公网隧道 401 / 带 token 放行 / 引导通道豁免）、
//   fs/list + fs/read（只读目录与 1MB 截断二进制识别）、mcp/list 聚合、device/info。
// 运行：node --test smoke-test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
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

async function listen() {
  await new Promise((r) => srv.listen(0, "127.0.0.1", r));
  return srv.address().port;
}
function req(path, opts = {}) {
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

test.after(() => {
  srv.close();
  rmSync(home, { recursive: true, force: true });
});
