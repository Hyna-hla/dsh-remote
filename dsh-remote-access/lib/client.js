window.__ModuleLoader__.load({
  id: "dsh-remote-access",
  factory: function (require) {
    var module = { exports: {} };
    var exports = module.exports;
    var react = require("react");
    var h = react.createElement;
    var useState = react.useState;
    var useEffect = react.useEffect;
    var useRef = react.useRef;

    var S = {
      root: { display: "flex", flexDirection: "column", gap: "12px", padding: "4px 0" },
      card: {
        border: "1px solid var(--dsw-alias-border-l1)", borderRadius: "10px",
        background: "var(--dsw-alias-bg-layer-1)", padding: "14px",
      },
      row: { display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" },
      title: { fontSize: 13, fontWeight: 600, color: "var(--dsw-alias-label-primary)" },
      label: { fontSize: 12, color: "var(--dsw-alias-label-secondary)", lineHeight: 1.6 },
      sub: { fontSize: 12, color: "var(--dsw-alias-label-secondary)", lineHeight: 1.7 },
      mono: { fontFamily: "ui-monospace, monospace", fontSize: 12, color: "var(--dsw-alias-label-secondary)", wordBreak: "break-all" },
      btn: {
        border: "1px solid var(--dsw-alias-border-l1)", borderRadius: "6px",
        background: "transparent", color: "var(--dsw-alias-label-primary)",
        padding: "5px 12px", fontSize: 12, cursor: "pointer",
      },
      btnPrimary: {
        border: "1px solid var(--dsw-alias-brand-primary)", borderRadius: "6px",
        background: "var(--dsw-alias-brand-primary)", color: "#0D1B2A",
        padding: "6px 14px", fontSize: 12, fontWeight: 600, cursor: "pointer",
      },
      ok: { fontSize: 12, color: "var(--dsw-alias-state-success-primary)", lineHeight: 1.6 },
      badge: {
        display: "inline-block", padding: "2px 8px", borderRadius: "10px", fontSize: 11, fontWeight: 600,
      },
      divider: { border: "none", borderTop: "1px dashed var(--dsw-alias-border-l1)", margin: "4px 0 0" },
    };

    function fetchJson(path, opts) {
      return fetch(path, opts).then(function (r) { return r.json(); });
    }

    // ---------- 移动端 App 配对确认（全局对话框 + 配对管理） ----------
    var pairDialogEl = null;
    var pairDialogShown = false;

    function removePairDialog() {
      if (pairDialogEl && pairDialogEl.parentNode) pairDialogEl.parentNode.removeChild(pairDialogEl);
      pairDialogEl = null;
      pairDialogShown = false;
    }

    function showPairDialog(pendingDevice, respond) {
      removePairDialog();
      pairDialogShown = true;

      var overlay = document.createElement("div");
      overlay.setAttribute("data-dsh-pair-dialog", "1");
      overlay.style.cssText = [
        "position:fixed", "inset:0", "z-index:2147483000",
        "display:flex", "align-items:center", "justify-content:center",
        "background:rgba(13,27,42,.62)", "backdrop-filter:blur(4px)",
        "padding:20px",
      ].join(";");

      var card = document.createElement("div");
      card.style.cssText = [
        "background:var(--dsw-alias-bg-layer-1)", "border:1px solid var(--dsw-alias-border-l1)",
        "border-radius:14px", "padding:24px", "max-width:380px", "width:100%",
        "box-shadow:0 20px 60px rgba(0,0,0,.5)", "color:var(--dsw-alias-label-primary)",
      ].join(";");

      var title = document.createElement("div");
      title.textContent = "🔐 手机设备「" + pendingDevice.name + "」请求首次连接";
      title.style.cssText = "font-size:15px;font-weight:600;line-height:1.5;margin-bottom:8px";

      var sub = document.createElement("div");
      sub.textContent = "允许该设备远程控制这台电脑？";
      sub.style.cssText = "font-size:13px;color:var(--dsw-alias-label-secondary);line-height:1.6;margin-bottom:18px";

      var row = document.createElement("div");
      row.style.cssText = "display:flex;gap:10px;justify-content:flex-end";

      function mkBtn(label, primary) {
        var b = document.createElement("button");
        b.textContent = label;
        b.style.cssText = primary
          ? "border:1px solid var(--dsw-alias-brand-primary);border-radius:6px;background:var(--dsw-alias-brand-primary);color:#0D1B2A;padding:7px 16px;font-size:13px;font-weight:600;cursor:pointer"
          : "border:1px solid var(--dsw-alias-border-l1);border-radius:6px;background:transparent;color:var(--dsw-alias-label-primary);padding:7px 16px;font-size:13px;cursor:pointer";
        return b;
      }

      var allowBtn = mkBtn("允许", true);
      var denyBtn = mkBtn("拒绝", false);

      allowBtn.onclick = function () { allowBtn.disabled = true; denyBtn.disabled = true; respond(pendingDevice.deviceId, "approve"); };
      denyBtn.onclick = function () { allowBtn.disabled = true; denyBtn.disabled = true; respond(pendingDevice.deviceId, "deny"); };

      row.appendChild(allowBtn);
      row.appendChild(denyBtn);
      card.appendChild(title);
      card.appendChild(sub);
      card.appendChild(row);
      overlay.appendChild(card);
      document.body.appendChild(overlay);
      pairDialogEl = overlay;
    }

    function PairSection() {
      var [devices, setDevices] = useState(null);
      var [busyId, setBusyId] = useState(null);
      var pairTimer = useRef(null);

      function refreshList() {
        fetchJson("/api/remote-access/pair/list")
          .then(function (r) { if (r && r.ok) setDevices(r.devices || []); })
          .catch(function () {});
      }

      function respond(deviceId, outcome) {
        fetchJson("/api/remote-access/pair/respond", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ deviceId: deviceId, outcome: outcome }),
        })
          .then(function () { removePairDialog(); refreshList(); })
          .catch(function () { removePairDialog(); });
      }

      function pollStatus() {
        fetchJson("/api/remote-access/pair/status")
          .then(function (r) {
            if (!r || !r.ok) return;
            var pendingDevice = r.pendingDevice || null;
            if (r.state === "pending" && pendingDevice && !pairDialogShown) {
              showPairDialog(pendingDevice, respond);
            } else if (r.state !== "pending" && pairDialogShown) {
              removePairDialog();
            }
          })
          .catch(function () {});
      }

      useEffect(function () {
        refreshList();
        pollStatus();
        pairTimer.current = setInterval(pollStatus, 2000);
        return function () {
          clearInterval(pairTimer.current);
          removePairDialog();
        };
      }, []);

      function revoke(deviceId) {
        setBusyId(deviceId);
        fetchJson("/api/remote-access/pair/revoke", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ deviceId: deviceId }),
        })
          .then(function () { refreshList(); })
          .catch(function () {})
          .finally(function () { setBusyId(null); });
      }

      return h("div", { style: S.root },
        h("div", { style: S.title }, "配对管理（手机 App）"),
        h("div", { style: S.sub }, "手机 App 首次连接本电脑时，需在此确认允许；已允许的设备可随时撤销，撤销后下次连接需重新确认。"),
        h("div", { style: S.card },
          devices === null ? h("div", { style: S.sub }, "加载中…") :
            devices.length === 0 ? h("div", { style: S.sub }, "暂无已配对设备。") :
              devices.map(function (d) {
                return h("div", { key: d.deviceId, style: Object.assign({}, S.row, { justifyContent: "space-between", marginBottom: 8 }) },
                  h("div", { style: S.label }, d.name || d.deviceId),
                  h("button", { style: S.btn, disabled: busyId === d.deviceId, onClick: function () { revoke(d.deviceId); } }, busyId === d.deviceId ? "撤销中…" : "撤销"));
              })
        )
      );
    }

    // ---------- 配对码（v2.1.0）：PC 生成随机码，手机 App 输入即完成配对 ----------
    function PairCodeCard() {
      var [pc, setPc] = useState(null);
      var [busy, setBusy] = useState(false);
      var [left, setLeft] = useState(0);
      var timer = useRef(null);

      function generate() {
        setBusy(true);
        fetchJson("/api/remote-access/pair/code/generate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: "{}",
        })
          .then(function (r) {
            if (r && r.ok && r.code) {
              setPc(r);
              setLeft(Math.floor(r.expiresInSec || 600));
              if (timer.current) clearInterval(timer.current);
              timer.current = setInterval(function () {
                setLeft(function (prev) {
                  if (prev <= 1) {
                    if (timer.current) clearInterval(timer.current);
                    timer.current = null;
                    setPc(null);
                    return 0;
                  }
                  return prev - 1;
                });
              }, 1000);
            }
          })
          .catch(function () {})
          .finally(function () { setBusy(false); });
      }

      useEffect(function () {
        return function () { if (timer.current) clearInterval(timer.current); };
      }, []);

      var mm = String(Math.floor(left / 60)).padStart(2, "0");
      var ss = String(left % 60).padStart(2, "0");

      return h("div", { style: S.card },
        h("div", { style: S.row },
          h("div", { style: S.title }, "配对码（推荐）"),
          pc ? h("span", { style: Object.assign({}, S.badge, { background: "rgba(52,199,89,.15)", color: "var(--dsw-alias-state-success-primary)" }) }, "有效 " + mm + ":" + ss) : null),
        h("div", { style: S.sub }, "生成一个 6 位随机配对码，在手机 App 连接本机后输入即完成配对——不用再守着确认框点允许。"),
        pc ? h("div", { style: Object.assign({}, S.mono, { fontSize: 30, fontWeight: 700, letterSpacing: "0.4em", color: "var(--dsw-alias-brand-primary)", marginTop: 8, marginBottom: 2 }) }, pc.code) : null,
        pc ? h("div", { style: S.sub }, "10 分钟有效 · 最多试错 5 次 · 验证通过立即作废") : null,
        h("div", { style: Object.assign({}, S.row, { marginTop: 10 }) },
          h("button", { style: S.btnPrimary, disabled: busy, onClick: generate }, busy ? "生成中…" : pc ? "重新生成" : "生成配对码"))
      );
    }

    // ---------- 设置页「远程控制」：远程互信认证 ----------
    function TrustSection() {
      return h("div", { style: S.root },
        h("div", { style: S.row },
          h("div", { style: S.title }, "远程互信认证"),
          h("span", { style: Object.assign({}, S.badge, { background: "rgba(52,199,89,.15)", color: "var(--dsw-alias-state-success-primary)" }) }, "● 已启用")),
        h("div", { style: S.sub }, "插件只负责远程互信：手机 App 首次连接后经配对码（或 PC 确认框）完成配对，获得远程通道 token；此后所有 /api 请求与实时通道（WebSocket）都要求该 token，拿到地址的陌生人无法遥控这台电脑。"),
        h("div", { style: S.ok }, "✔ 通道鉴权已挂载：本机浏览器放行；手机引导端点（配对握手、配对码校验、host.describe）豁免；其余 /api（含配对管理操作）一律需要 Bearer token。"),
        h(PairCodeCard, {}),
        h("hr", { style: S.divider }),
        h(PairSection, {})
      );
    }

    function apply(ctx) {
      ctx.slots.inject("settings.section", function () {
        return ctx.slots.register(
          { name: "settings.section", id: "dsh-remote-access", order: 40, label: function () { return "远程控制"; } },
          TrustSection
        );
      });
    }

    exports.apply = apply;
    exports.inject = ["slots"];
    return module.exports;
  }
});
