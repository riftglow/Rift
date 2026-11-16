package light.rift.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import light.rift.core.utils.SendLog

/**
 * A zero-UI activity that exists only to forward a crash log: when it is
 * launched with a "sendLog" extra it hands the log to [SendLog] and
 * finishes immediately. No layout is ever shown.
 */
class BlankActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra("sendLog")?.let { logPath ->
            SendLog.sendLog(this, logPath)
        }
        finish()
    }

}
