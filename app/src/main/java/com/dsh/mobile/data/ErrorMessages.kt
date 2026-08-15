package com.dsh.mobile.data

object ErrorMessages {
    fun reason(code: ConnectionErrorCode): String = when (code) {
        ConnectionErrorCode.DNS_UNREACHABLE -> "域名无法解析"
        ConnectionErrorCode.PORT_UNREACHABLE -> "端口不可达（连接被拒绝/超时）"
        ConnectionErrorCode.TLS_CERT_FAILED -> "HTTPS 证书校验失败"
        ConnectionErrorCode.AUTH_FAILED -> "前置网关鉴权失败（401/403）"
        ConnectionErrorCode.VERSION_MISMATCH -> "移动端与 PC 端版本不兼容"
        ConnectionErrorCode.PROXY_FAILED -> "代理不可达"
        ConnectionErrorCode.PROTOCOL_ERROR -> "服务响应异常"
        ConnectionErrorCode.UNKNOWN -> "未知错误"
    }

    fun advice(code: ConnectionErrorCode): String = when (code) {
        ConnectionErrorCode.DNS_UNREACHABLE -> "检查地址拼写；局域网场景改用 IP"
        ConnectionErrorCode.PORT_UNREACHABLE -> "确认 PC 端 DSH 已启动、端口正确、防火墙放行"
        ConnectionErrorCode.TLS_CERT_FAILED -> "若为自签名证书，在本主机配置里开启「信任自签名」或导入其 CA"
        ConnectionErrorCode.AUTH_FAILED -> "DSH 本机直连无鉴权；检查自建反代/网关的鉴权配置或凭证"
        ConnectionErrorCode.VERSION_MISMATCH -> "升级 DSH 或本 App"
        ConnectionErrorCode.PROXY_FAILED -> "检查代理地址/端口/账号，或关闭该主机的代理"
        ConnectionErrorCode.PROTOCOL_ERROR -> "确认地址指向 DSH web 服务；导出日志排查"
        ConnectionErrorCode.UNKNOWN -> "导出日志排查"
    }
}
