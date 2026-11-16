package light.rift.bg.proto

import light.rift.database.DataStore
import light.rift.database.ProxyEntity

/**
 * One-shot latency probe against the URL configured for connection tests.
 * Thin wrapper around [TestInstance]: it spins a throwaway core up for the
 * given profile, runs the probe, and tears the core down again.
 */
class UrlTest {

    /** Probe target, read from the data store at construction time. */
    val link: String = DataStore.connectionTestURL

    /** Probe timeout in milliseconds. */
    private val timeoutMs: Int = 5000

    /** Run the probe for [profile]; returns the measured latency in ms. */
    suspend fun doTest(profile: ProxyEntity): Int =
        TestInstance(profile, link, timeoutMs).doTest()

}
