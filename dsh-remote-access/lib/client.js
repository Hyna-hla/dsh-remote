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
      url: {
        fontSize: 13, fontWeight: 600, color: "var(--dsw-alias-brand-primary)",
        wordBreak: "break-all", lineHeight: 1.6,
      },
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
      input: {
        border: "1px solid var(--dsw-alias-border-l1)", borderRadius: "6px",
        background: "var(--dsw-alias-bg-layer-1)", color: "var(--dsw-alias-label-primary)",
        padding: "5px 10px", fontSize: 12, width: 140,
      },
      err: { fontSize: 12, color: "var(--dsw-alias-state-error-primary)", lineHeight: 1.6 },
      ok: { fontSize: 12, color: "var(--dsw-alias-state-success-primary)", lineHeight: 1.6 },
      warn: { fontSize: 12, color: "var(--dsw-alias-state-warning-primary)", lineHeight: 1.6 },
      qr: { display: "block", width: 168, height: 168, margin: "8px 0", border: "1px solid var(--dsw-alias-border-l1)", borderRadius: "8px", background: "#fff" },
      badge: {
        display: "inline-block", padding: "2px 8px", borderRadius: "10px", fontSize: 11, fontWeight: 600,
      },
      divider: { border: "none", borderTop: "1px dashed var(--dsw-alias-border-l1)", margin: "4px 0 0" },
    };

    function fetchJson(path, opts) {
      return fetch(path, opts).then(function (r) { return r.json(); });
    }

    function WxCard() {
      var [wx, setWx] = useState(null);
      var [busy, setBusy] = useState(false);
      var [verifyCode, setVerifyCode] = useState("");
      var timer = useRef(null);

      function refresh() {
        fetchJson("/api/remote-access/wx/status")
          .then(function (r) { setWx(r); })
          .catch(function () { setWx({ status: "error", message: "无法连接插件后端（重启 DSH 后生效）" }); });
      }
      useEffect(function () {
        refresh();
        timer.current = setInterval(refresh, 2500);
        return function () { clearInterval(timer.current); };
      }, []);

      function post(path, body) {
        setBusy(true);
        return fetchJson(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body || {}) })
          .then(function (r) { refresh(); return r; })
          .catch(function (e) { setWx({ status: "error", message: String(e) }); })
          .finally(function () { setBusy(false); });
      }

      if (!wx) return h("div", { style: S.root }, "加载中…");

      var waiting = wx.status === "waiting" || wx.status === "scanned" || wx.status === "need_verifycode";
      var online = wx.status === "online";
      var showQr = waiting || wx.status === "expired";

      var statusBadge = null;
      if (online) statusBadge = h("span", { style: Object.assign({}, S.badge, { background: "rgba(52,199,89,.15)", color: "var(--dsw-alias-state-success-primary)" }) }, "● 已连接");
      else if (waiting) statusBadge = h("span", { style: Object.assign({}, S.badge, { background: "rgba(255,149,0,.15)", color: "var(--dsw-alias-state-warning-primary)" }) }, "◌ 等待扫码");
      else if (wx.status === "error" || wx.status === "expired") statusBadge = h("span", { style: Object.assign({}, S.badge, { background: "rgba(255,59,48,.12)", color: "var(--dsw-alias-state-error-primary)" }) }, "✘ 未连接");
      else statusBadge = h("span", { style: Object.assign({}, S.badge, { background: "rgba(142,142,147,.15)", color: "var(--dsw-alias-label-secondary)" }) }, "○ 未开启");

      return h("div", { style: S.root },
        h("div", { style: S.row },
          h("div", { style: S.title }, "微信遥控（主要方式）"), statusBadge),
        h("div", { style: S.sub }, "扫码登录微信 iLink 后，直接在微信里给这个 bot 发消息即可遥控 DSH：无需公网隧道、地址永不变化、重启自动恢复。"),

        h("div", { style: S.card },
          h("div", { style: S.row },
            h("div", { style: Object.assign({}, S.label, { width: "100%" }) },
              wx.message ? h("div", { style: wx.status === "error" ? S.err : S.sub }, wx.message) : null,
              wx.accountId ? h("div", { style: S.mono, marginTop: 4 }, "bot 账号: " + wx.accountId) : null,
              wx.userId ? h("div", { style: S.mono, marginTop: 2 }, "绑定微信: " + wx.userId + "（仅此微信号可用）") : null,
              wx.sessionId ? h("div", { style: S.mono, marginTop: 2 }, "DSH 会话: " + wx.sessionId + "（会话名「微信遥控」）") : null,
              typeof wx.pendingApprovals === "number" && wx.pendingApprovals > 0 ? h("div", { style: S.warn, marginTop: 2 }, "待审批: " + wx.pendingApprovals + " 个（在微信回复 同意/拒绝）") : null,
              wx.sessionError ? h("div", { style: S.err, marginTop: 2 }, wx.sessionError) : null)
          ),

          showQr ? h("div", null,
            h(WxQr, { wx: wx }),
            h("div", { style: S.sub }, "打开手机微信 → 扫一扫 → 确认登录。")
          ) : null,

          wx.status === "need_verifycode" ? h("div", { style: S.row, marginTop: 6 },
            h("input", { style: S.input, placeholder: "手机微信上的配对码", value: verifyCode, onChange: function (e) { setVerifyCode(e.target.value); } }),
            h("button", { style: S.btn, disabled: busy || !verifyCode, onClick: function () { post("/api/remote-access/wx/login", { verifyCode: verifyCode }); } }, "提交配对码")
          ) : null,

          h("div", { style: S.row, marginTop: 10 },
            online || waiting ? h("button", { style: S.btn, disabled: busy, onClick: function () { post("/api/remote-access/wx/stop"); } }, busy ? "处理中…" : "断开") :
              h("button", { style: S.btnPrimary, disabled: busy, onClick: function () { post("/api/remote-access/wx/login"); } }, busy ? "生成中…" : "连接微信"),
            online || waiting ? h("button", { style: S.btn, disabled: busy, onClick: function () { post("/api/remote-access/wx/reset"); } }, "重新扫码") : null,
            h("button", { style: S.btn, onClick: refresh }, "刷新")
          ),

          h("div", { style: S.sub, marginTop: 10 },
            "使用说明：1) 点「连接微信」扫码，手机微信确认；2) 在微信里找到这个 bot（自己给自己发消息），直接发文字即可遥控 DSH；3) 发图片会自动转给 DSH 看图；4) 工具需要审批时，微信会收到请求，回复「同意 xxxxxxxx」或「拒绝 xxxxxxxx」；5) 发 /状态 查看连接信息，/断开 断开连接。")
        ),

        h("hr", { style: S.divider }),
        h("div", { style: S.title }, "网页版隧道（备选）"),
        h("div", { style: S.sub }, "需要完整网页版 UI 时使用（cpolar 内网穿透，免费版地址每次会变）。"),
        h(CpolarCard, {})
      );
    }

    // S.label2 保留兼容（供下面 CpolarCard 使用）
    S.label2 = function () { return S.label; };

    // 本地二维码：数据不出本机（宿主端 qrcode 库生成），无第三方、秒出图
    function LocalQr(props) {
      var [dataUrl, setDataUrl] = useState(null);
      var [err, setErr] = useState(false);
      useEffect(function () {
        if (!props.data) { setDataUrl(null); setErr(false); return; }
        var alive = true;
        fetch("/api/remote-access/qr?data=" + encodeURIComponent(props.data))
          .then(function (r) { return r.json(); })
          .then(function (r) {
            if (!alive) return;
            if (r.ok && r.dataUrl) setDataUrl(r.dataUrl);
            else setErr(true);
          })
          .catch(function () { if (alive) setErr(true); });
        return function () { alive = false; };
      }, [props.data]);
      if (err) return h("div", { style: Object.assign({}, S.qr, { display: "flex", alignItems: "center", justifyContent: "center", color: "#999" }) }, "二维码生成失败");
      if (!dataUrl) return h("div", { style: Object.assign({}, S.qr, { display: "flex", alignItems: "center", justifyContent: "center", color: "#999" }) }, "生成二维码…");
      return h("img", { src: dataUrl, alt: "二维码", style: S.qr });
    }

    function WxQr(props) {
      var wx = props.wx;
      if (!wx.qrUrl && !wx.qrData) return h("div", { style: S.sub }, "正在生成二维码…（几秒后刷新）");
      return h(LocalQr, { data: wx.qrData || wx.qrUrl });
    }

    function CpolarCard() {
      var [info, setInfo] = useState(null);
      var [busy, setBusy] = useState(false);
      var [copied, setCopied] = useState(false);
      var [qrFailed, setQrFailed] = useState(false);

      function refresh() {
        fetchJson("/api/remote-access/status")
          .then(setInfo)
          .catch(function () { setInfo({ status: "error", message: "无法连接插件后端（重启 DSH 后生效）" }); });
      }
      useEffect(function () { refresh(); }, []);

      function start() {
        setBusy(true);
        fetchJson("/api/remote-access/start", { method: "POST" })
          .then(function (r) {
            if (r.ok) setInfo({ status: "online", url: r.url, port: r.port });
            else setInfo({ status: "error", message: r.error });
          })
          .catch(function (e) { setInfo({ status: "error", message: String(e) }); })
          .finally(function () { setBusy(false); });
      }

      function stop() {
        setBusy(true);
        fetchJson("/api/remote-access/stop", { method: "POST" })
          .then(function () { refresh(); })
          .finally(function () { setBusy(false); });
      }

      function copy() {
        if (!info || !info.url) return;
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(info.url).then(function () {
            setCopied(true);
            setTimeout(function () { setCopied(false); }, 2000);
          });
        }
      }

      if (!info) return h("div", { style: S.root }, "加载中…");

      var url = info.url;
      var running = info.status === "online" || info.status === "starting";

      return h("div", { style: S.root },
        h("div", { style: S.card },
          h("div", { style: S.label2() }, "当前状态："),
          info.status === "online" ? h("div", { style: S.ok }, "✔ 在线 — 隧道运行中") :
            info.status === "starting" ? h("div", { style: S.label2() }, "⏳ 正在建立隧道…") :
              info.status === "error" ? h("div", { style: S.err }, "✘ " + (info.message || "失败")) :
                h("div", { style: S.label2() }, "○ 未开启"),
          url ? h("div", { style: { marginTop: 8 } },
            h("div", { style: S.label2() }, "手机端连接地址："),
            h("a", { href: url, target: "_blank", rel: "noreferrer", style: S.url }, url)) : null,
          info.port ? h("div", { style: { marginTop: 4 } }, h("span", { style: S.mono }, "目标端口: " + info.port)) : null,
          info.cpolarFound === false ? h("div", { style: { marginTop: 6 } }, h("span", { style: S.err }, "⚠ 未检测到 cpolar（E:\\coplar\\cpolar.exe）")) : null,
          info.dshPort ? h("div", { style: { marginTop: 4 } }, h("span", { style: S.mono }, "检测到 DSH 桌面实例端口: " + info.dshPort)) : null
        ),

        url ? h("div", { style: S.card },
          h("div", { style: S.label2() }, "手机扫码获取地址（本机生成二维码，数据不经过第三方）："),
          h(LocalQr, { data: url })) : null,

        h("div", { style: S.row },
          running ? null : h("button", { style: S.btnPrimary, disabled: busy, onClick: start }, busy ? "生成中…" : "生成地址"),
          running ? h("button", { style: S.btn, disabled: busy, onClick: stop }, busy ? "停止中…" : "停止隧道") : null,
          url ? h("button", { style: S.btn, onClick: copy }, copied ? "已复制 ✔" : "复制地址") : null,
          h("button", { style: S.btn, onClick: refresh }, "刷新")
        ),

        h("div", { style: { marginTop: 4 } },
          h("div", { style: S.label2() },
            "使用说明：1) 点「生成地址」；2) 手机打开 DSH Remote，把地址粘贴到服务器地址；3) 免费版地址每次生成都会变，重新生成后需重新填写。"),
          h("div", { style: S.label2() }, "（本插件为个人自用，非官方功能。）")
        )
      );
    }

    function apply(ctx) {
      ctx.slots.inject("settings.section", function () {
        return ctx.slots.register(
          { name: "settings.section", id: "dsh-remote-access", order: 40, label: function () { return "远程控制"; } },
          WxCard
        );
      });
    }

    exports.apply = apply;
    exports.inject = ["slots"];
    return module.exports;
  }
});
