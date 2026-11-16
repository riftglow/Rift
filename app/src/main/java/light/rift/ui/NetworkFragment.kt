package light.rift.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import light.rift.R
import light.rift.database.DataStore
import light.rift.database.RiftDatabase
import light.rift.databinding.LayoutNetworkBinding
import light.rift.ktx.app
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.readableMessage
import light.rift.ktx.runOnLifecycleDispatcher
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    override fun name0() = app.getString(R.string.tools_network)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutNetworkBinding.bind(view)
        binding.stunTest.setOnClickListener {
            startActivity(Intent(requireContext(), StunActivity::class.java))
        }
        binding.egressCheck.setOnClickListener {
            doEgressCheck(binding)
        }
        binding.dnsLeakTest.setOnClickListener {
            doDnsLeakCheck(binding)
        }
        binding.urlCheckTest.setOnClickListener {
            doUrlCheck(binding)
        }
    }

    // “开始”已改成 TextView 药丸（2026-11-15，0.2.29）：TextView 禁用时字色不会自动变灰，
    // 这里手动换色（启用琥珀、禁用灰），不然检测中按钮看着还能点。
    private fun setStartEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (enabled) R.color.color_rift_accent else android.R.color.darker_gray
            )
        )
    }

    // 出口概览卡：与旧版同一份查询逻辑（EgressIp.queryJson），只是结果
    // 从纯文本改成结构化展示（国旗 + IP + 属地/ISP/模式），OwnBox 诊断页形态。
    private data class EgressCheckResult(
        val ip: String,
        val countryCode: String,
        val place: String,
        val isp: String,
        val mode: String,
        val elapsedMs: Long,
    )

    private fun doEgressCheck(binding: LayoutNetworkBinding) {
        setStartEnabled(binding.egressCheck, false)
        binding.egressOverview.isVisible = false
        binding.egressResult.isVisible = true
        binding.egressResult.text = getString(R.string.egress_check_testing)
        runOnLifecycleDispatcher {
            val result: Pair<EgressCheckResult?, String?> = try {
                checkEgress() to null
            } catch (e: Exception) {
                null to getString(R.string.egress_check_error, e.readableMessage)
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                setStartEnabled(binding.egressCheck, true)
                val (info, error) = result
                if (info != null) {
                    binding.egressResult.isVisible = false
                    binding.egressOverview.isVisible = true
                    binding.egressFlag.text = EgressIp.flagEmoji(info.countryCode)
                    binding.egressIp.text = info.ip
                    binding.egressIpVersion.text =
                        if (info.ip.contains(':')) "IPv6" else "IPv4"
                    binding.egressPlace.text =
                        info.place.ifBlank { getString(R.string.egress_check_unknown) }
                    binding.egressIsp.text =
                        info.isp.ifBlank { getString(R.string.egress_check_unknown) }
                    binding.egressMode.text = info.mode
                    binding.egressSource.text = getString(
                        R.string.egress_check_source_elapsed, info.elapsedMs
                    )
                } else {
                    binding.egressOverview.isVisible = false
                    binding.egressResult.isVisible = true
                    binding.egressResult.text = error
                }
            }
        }
    }

    // 查询本身见 EgressIp（底栏出口 IP 与这里共用同一份查询逻辑）。
    private fun checkEgress(): EgressCheckResult {
        val connected = DataStore.serviceState.connected
        val profileName = try {
            RiftDatabase.proxyDao.getById(DataStore.currentProfile)?.displayName()
        } catch (_: Exception) {
            null
        }
        val start = SystemClock.elapsedRealtime()
        val json = EgressIp.queryJson()
        val elapsed = SystemClock.elapsedRealtime() - start
        val ip = json.optString("query")
        val place = listOf(
            json.optString("country"),
            json.optString("regionName"),
            json.optString("city")
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val mode = when {
            !connected -> app.getString(R.string.egress_check_direct)
            !profileName.isNullOrBlank() ->
                app.getString(R.string.egress_check_via_node, profileName)
            else -> app.getString(R.string.egress_check_via_node_plain)
        }
        return EgressCheckResult(
            ip = ip,
            countryCode = json.optString("countryCode"),
            place = place,
            isp = json.optString("isp"),
            mode = mode,
            elapsedMs = elapsed,
        )
    }

    // DNS 泄露检测：只查 DNS 是否走了 VPN 的 Fake-IP 解析，并对照出口 IP。
    // 不是完整的泄露测试（不跑第三方泄露检测服务），结论按实际能力写。
    // 做法：系统解析 www.google.com，FakeDNS 开着时应落在 198.18.0.0/15
    // （VpnService 里的 FAKEDNS_VLAN4_CLIENT）；再用同一份出口查询
    // （EgressIp.queryJson）拿出口 IP 对照，不新增请求端点。
    private fun doDnsLeakCheck(binding: LayoutNetworkBinding) {
        setStartEnabled(binding.dnsLeakTest, false)
        binding.dnsLeakResult.isVisible = true
        binding.dnsLeakResult.text = getString(R.string.egress_check_testing)
        runOnLifecycleDispatcher {
            val result = try {
                checkDnsLeak()
            } catch (e: Exception) {
                getString(R.string.egress_check_error, e.readableMessage)
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                setStartEnabled(binding.dnsLeakTest, true)
                binding.dnsLeakResult.text = result
            }
        }
    }

    private fun checkDnsLeak(): String {
        val connected = DataStore.serviceState.connected
        val fakeDnsOn = DataStore.enableFakeDns
        val host = "www.google.com"
        // 优先取 IPv4：Fake-IP 只对 A 记录返回 198.18.x.x，AAAA 会干扰判定。
        var resolvedIp: String? = null
        var resolveError: String? = null
        try {
            val addresses = InetAddress.getAllByName(host)
            resolvedIp = addresses.firstOrNull { it is Inet4Address }?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress
        } catch (e: Exception) {
            resolveError = e.readableMessage
        }
        // 出口对照：复用同一份出口查询（走 mixed 入站，未连接时直连）。
        // 失败不致命，DNS 结论照常给。
        val egressIp = try {
            EgressIp.queryJson().optString("query").ifBlank { null }
        } catch (_: Exception) {
            null
        }
        val isFakeIp = resolvedIp != null &&
            (resolvedIp.startsWith("198.18.") || resolvedIp.startsWith("198.19."))
        return buildString {
            if (resolvedIp != null) {
                appendLine(
                    getString(
                        R.string.dns_leak_resolved,
                        host, resolvedIp,
                        getString(
                            if (isFakeIp) R.string.dns_leak_fake_ip_on
                            else R.string.dns_leak_fake_ip_off
                        )
                    )
                )
            } else {
                appendLine(
                    getString(
                        R.string.dns_leak_resolve_failed,
                        host, resolveError ?: getString(R.string.egress_check_unknown)
                    )
                )
            }
            if (egressIp != null) {
                appendLine(getString(R.string.dns_leak_egress, egressIp))
            }
            val conclusion = when {
                !connected -> R.string.dns_leak_conclusion_direct
                !fakeDnsOn -> R.string.dns_leak_conclusion_no_fakedns
                resolvedIp == null -> R.string.dns_leak_conclusion_unknown
                isFakeIp -> R.string.dns_leak_conclusion_safe
                else -> R.string.dns_leak_conclusion_maybe
            }
            append(getString(conclusion))
        }
    }

    // URL Check 诊断：真实请求全链路追踪（DNS → 连接 → TLS → HTTP），
    // 标出在哪一步失败。DNS 用系统解析单独测（与 DNS 泄露检测同一做法，
    // FakeDNS 开时应返回 198.18.x.x）；连接/TLS/HTTP 用 OkHttp +
    // EventListener 分步计时，VPN 开时经 mixed 入站 SOCKS5，关时直连。
    // 目标是 https://www.youtube.com/generate_204（Android 探活端点，
    // 204 空响应，不下载整页），与首页同属一个 CDN。
    // 注意：App 自身流量默认不进 TUN，所以“连接”这一步不能拿裸 Socket
    // 直连目标 IP 测（那样走直连，还会被 Fake-IP 误导），必须经代理。
    private fun doUrlCheck(binding: LayoutNetworkBinding) {
        setStartEnabled(binding.urlCheckTest, false)
        binding.urlCheckResult.isVisible = true
        binding.urlCheckResult.text = getString(R.string.egress_check_testing)
        runOnLifecycleDispatcher {
            val result = try {
                checkUrlCheck()
            } catch (e: Exception) {
                getString(R.string.egress_check_error, e.readableMessage)
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                setStartEnabled(binding.urlCheckTest, true)
                binding.urlCheckResult.text = result
            }
        }
    }

    // OkHttp EventListener：只记各步时间戳，成败由调用方按走到哪一步判定。
    private class UrlCheckTrace : EventListener() {
        @Volatile var connectStartMs: Long = 0
        @Volatile var connectEndMs: Long = 0
        @Volatile var secureConnectStartMs: Long = 0
        @Volatile var secureConnectEndMs: Long = 0
        @Volatile var requestHeadersStartMs: Long = 0
        @Volatile var responseHeadersEndMs: Long = 0

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStartMs = SystemClock.elapsedRealtime()
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connectEndMs = SystemClock.elapsedRealtime()
        }

        override fun secureConnectStart(call: Call) {
            secureConnectStartMs = SystemClock.elapsedRealtime()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            secureConnectEndMs = SystemClock.elapsedRealtime()
        }

        override fun requestHeadersStart(call: Call) {
            requestHeadersStartMs = SystemClock.elapsedRealtime()
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            responseHeadersEndMs = SystemClock.elapsedRealtime()
        }
    }

    private fun checkUrlCheck(): String {
        val host = "www.youtube.com"
        val url = "https://$host/generate_204"
        val connected = DataStore.serviceState.connected
        val mode = getString(if (connected) R.string.url_check_via_node else R.string.url_check_direct)
        val out = StringBuilder()
        out.appendLine(getString(R.string.url_check_target, url))
        val totalStart = SystemClock.elapsedRealtime()

        // 1. DNS 解析（系统解析，优先 IPv4；与 DNS 泄露检测同一做法）。
        val dnsStart = SystemClock.elapsedRealtime()
        var resolvedIp: String? = null
        var dnsError: String? = null
        try {
            val addresses = InetAddress.getAllByName(host)
            resolvedIp = addresses.firstOrNull { it is Inet4Address }?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress
        } catch (e: Exception) {
            dnsError = e.readableMessage
        }
        val dnsMs = SystemClock.elapsedRealtime() - dnsStart
        if (resolvedIp == null) {
            out.appendLine(getString(R.string.url_check_step_dns_failed, dnsError ?: getString(R.string.egress_check_unknown)))
            out.append(getString(R.string.url_check_conclusion_dns))
            return out.toString()
        }
        val isFakeIp = resolvedIp.startsWith("198.18.") || resolvedIp.startsWith("198.19.")
        val dnsShown = if (isFakeIp) {
            "$resolvedIp（${getString(R.string.dns_leak_fake_ip_on)}）"
        } else {
            resolvedIp
        }
        out.appendLine(getString(R.string.url_check_step_dns_ok, dnsShown, dnsMs))

        // 2. 连接前先探 mixed 入站（VPN 开时），沿用 EgressIp 的做法：
        // 入站没起来就直接报连接失败，不把直连结果误标为节点结果。
        if (connected) {
            try {
                Socket().use {
                    it.connect(InetSocketAddress("127.0.0.1", DataStore.mixedPort), 2000)
                }
            } catch (_: Exception) {
                out.appendLine(
                    getString(
                        R.string.url_check_step_connect_failed,
                        mode,
                        getString(R.string.egress_check_mixed_unready)
                    )
                )
                out.append(getString(R.string.url_check_conclusion_connect))
                return out.toString()
            }
        }

        // 3. 连接 / TLS / HTTP：OkHttp + EventListener 分步计时。
        // 诊断用客户端每测一次新建，用完关掉线程池与连接池，不常驻。
        val trace = UrlCheckTrace()
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .eventListener(trace)
            .apply {
                if (connected) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", DataStore.mixedPort)))
                }
            }
            .build()
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (trace.connectEndMs > 0) {
                    out.appendLine(
                        getString(
                            R.string.url_check_step_connect_ok,
                            mode,
                            trace.connectEndMs - trace.connectStartMs
                        )
                    )
                }
                if (trace.secureConnectEndMs > 0) {
                    out.appendLine(
                        getString(
                            R.string.url_check_step_tls_ok,
                            trace.secureConnectEndMs - trace.secureConnectStartMs
                        )
                    )
                }
                val httpMs = maxOf(0L, trace.responseHeadersEndMs - trace.requestHeadersStartMs)
                if (response.code in 200..399) {
                    out.appendLine(getString(R.string.url_check_step_http_ok, response.code, httpMs))
                    out.append(
                        getString(
                            R.string.url_check_conclusion_ok,
                            SystemClock.elapsedRealtime() - totalStart
                        )
                    )
                } else {
                    out.appendLine(getString(R.string.url_check_step_http_failed, "HTTP ${response.code}"))
                    out.append(getString(R.string.url_check_conclusion_http))
                }
            }
        } catch (e: Exception) {
            // 按 EventListener 走到哪一步来定失败步骤。
            if (trace.connectEndMs <= 0) {
                out.appendLine(getString(R.string.url_check_step_connect_failed, mode, e.readableMessage))
                out.append(getString(R.string.url_check_conclusion_connect))
            } else {
                out.appendLine(
                    getString(
                        R.string.url_check_step_connect_ok,
                        mode,
                        trace.connectEndMs - trace.connectStartMs
                    )
                )
                if (trace.secureConnectEndMs <= 0) {
                    out.appendLine(getString(R.string.url_check_step_tls_failed, e.readableMessage))
                    out.append(getString(R.string.url_check_conclusion_tls))
                } else {
                    out.appendLine(
                        getString(
                            R.string.url_check_step_tls_ok,
                            trace.secureConnectEndMs - trace.secureConnectStartMs
                        )
                    )
                    out.appendLine(getString(R.string.url_check_step_http_failed, e.readableMessage))
                    out.append(getString(R.string.url_check_conclusion_http))
                }
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        return out.toString()
    }

}
