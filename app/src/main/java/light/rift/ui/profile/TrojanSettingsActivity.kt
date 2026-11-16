package light.rift.ui.profile

import light.rift.fmt.trojan.TrojanBean

class TrojanSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity(): TrojanBean {
        return TrojanBean()
    }

}
