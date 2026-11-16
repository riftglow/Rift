package light.rift.fmt.ssh

import light.rift.core.SingBoxOptions
import light.rift.core.utils.listByLineOrComma

fun buildSingBoxOutboundSSHBean(bean: SSHBean): SingBoxOptions.Outbound_SSHOptions {
    val outbound = SingBoxOptions.Outbound_SSHOptions()
    outbound.type = "ssh"
    outbound.server = bean.serverAddress
    outbound.server_port = bean.serverPort
    outbound.user = bean.username
    if (bean.publicKey.isNotBlank()) {
        outbound.host_key = bean.publicKey.listByLineOrComma().toMutableList()
    }
    if (bean.authType == SSHBean.AUTH_TYPE_PRIVATE_KEY) {
        outbound.private_key = bean.privateKey
        outbound.private_key_passphrase = bean.privateKeyPassphrase
    } else {
        outbound.password = bean.password
    }
    return outbound
}
