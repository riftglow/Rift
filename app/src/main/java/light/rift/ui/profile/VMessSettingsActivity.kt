package light.rift.ui.profile

import light.rift.fmt.v2ray.VMessBean

class VMessSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity(): VMessBean {
        val bean = VMessBean()
        if (intent?.getBooleanExtra("vless", false) == true) {
            bean.alterId = -1
        }
        return bean
    }

}
