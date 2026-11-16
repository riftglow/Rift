package light.rift.ui.profile

import light.rift.fmt.http.HttpBean

class HttpSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity(): HttpBean {
        return HttpBean()
    }

}
