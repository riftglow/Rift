package light.rift.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import light.rift.R
import light.rift.databinding.LayoutStunBinding
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.readableMessage
import light.rift.ktx.runOnDefaultDispatcher
import libcore.Libcore

/**
 * STUN / NAT test screen: runs [Libcore.stunTest] against the server typed
 * in the field and shows the result, or an error dialog on failure.
 */
class StunActivity : ThemedActivity() {

    private lateinit var binding: LayoutStunBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutStunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.stun_test)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        binding.stunTest.setOnClickListener { doTest() }
    }

    /** Run one STUN probe on a background thread and report back on the UI thread. */
    fun doTest() {
        binding.waitLayout.isVisible = true
        runOnDefaultDispatcher {
            val outcome = runProbe()
            val error = outcome.exceptionOrNull()
            if (error != null) {
                showProbeError(error)
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                binding.waitLayout.isVisible = false
                binding.natResult.text = outcome.getOrNull()
            }
        }
    }

    /** Run the probe synchronously; failures come back as a failed [Result]. */
    private fun runProbe(): Result<String> {
        return try {
            val probe = Libcore.stunTest(binding.natStunServer.text.toString())
            if (probe!!.success) {
                Result.success(probe.text)
            } else {
                Result.failure(Exception(probe.text))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun showProbeError(error: Throwable) {
        onMainDispatcher {
            AlertDialog.Builder(this@StunActivity)
                .setTitle(R.string.error_title)
                .setMessage(error.readableMessage)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .runCatching { show() }
        }
    }

}
