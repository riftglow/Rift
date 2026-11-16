package light.rift.core.proxy.shadowtls

import light.rift.fmt.v2ray.buildSingBoxOutboundTLS
import light.rift.core.SingBoxOptions

/**
 * Translate a ShadowTLS profile bean into the sing-box outbound options the
 * core understands. Every field is copied verbatim; TLS settings come from
 * the shared TLS builder.
 */
fun buildSingBoxOutboundShadowTLSBean(bean: ShadowTLSBean): SingBoxOptions.Outbound_ShadowTLSOptions {
    val options = SingBoxOptions.Outbound_ShadowTLSOptions()
    options.type = "shadowtls"
    options.server = bean.serverAddress
    options.server_port = bean.serverPort
    options.version = bean.version
    options.password = bean.password
    options.tls = buildSingBoxOutboundTLS(bean)
    return options
}
