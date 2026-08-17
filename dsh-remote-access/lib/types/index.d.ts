/**
 * dsh-remote-access (v2.4.1) — DSH 远程互信认证插件。
 * 本文件为手写声明；`npm run build`（tsc --allowJs --declaration --emitDeclarationOnly）可据此重新生成同目录 .d.ts。
 */

/** 插件名（须与 package.json name 一致，DSH 据此识别 bundle） */
export const name: "dsh-remote-access";

/**
 * 在 DSH 运行时上下文上应用本插件：注册 /api/remote-access/* 路由、远程通道 Bearer 鉴权门禁、
 * 公网域名白名单与配对码等。ctx 由 DSH 注入（webServer / effect / slots / fs / get("tools")）。
 */
export function apply(ctx: any): void;
