package light.rift.bg

import java.io.Closeable

/**
 * Contract for a runnable core component: something that owns a running
 * sing-box (or a stand-in process) and can be started and torn down.
 *
 * Implementations bring the core up with [launch] and release everything
 * with [Closeable.close].
 */
interface AbstractInstance : Closeable {

    /** Bring the underlying core up. Callers must eventually [close] it. */
    fun launch()

}
