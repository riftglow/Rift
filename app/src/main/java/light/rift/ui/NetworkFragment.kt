package light.rift.ui

import android.content.Intent
import android.graphics.Paint
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
        binding.streamingTest.setOnClickListener {
            doStreamingCheck(binding)
        }
        binding.ipPurityTest.setOnClickListener {
            doIpPurityCheck(binding)
        }
        // 点出口 IP 测速（0.2.29 -26）：IP 下划线表示可点。
        binding.egressIp.paintFlags = binding.egressIp.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.egressIp.setOnClickListener {
            doSpeedTest(binding)
        }
    }

    private var speedTesting = false

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
                    // 点 IP 测速的提示（0.2.29 -26）：测速结果复用这行。
                    binding.egressSpeed.isVisible = true
                    binding.egressSpeed.text = getString(R.string.egress_speed_tap_hint)
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
                    // 本地代理认证（0.2.29 -26）：OkHttp 不做 SOCKS5 认证（它的
                    // proxyAuthenticator 只对 HTTP 代理生效）；SOCKS 握手是 Android 的
                    // java.net.Socket（SocksSocketImpl）做的，账号密码走
                    // java.net.socks.username / java.net.socks.password 两个系统属性。
                    // 本进程唯一的 SOCKS 消费者就是这里。实机验：连着 VPN 时 URL 检查出结果。
                    if (DataStore.localProxyAuthEnabled) {
                        System.setProperty("java.net.socks.username", DataStore.mixedInboundUser)
                        System.setProperty("java.net.socks.password", DataStore.mixedInboundPass)
                    } else {
                        System.clearProperty("java.net.socks.username")
                        System.clearProperty("java.net.socks.password")
                    }
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

    // 流媒体解锁（0.2.29 -26）：11 个服务并行探，结果按表顺序排。
    private fun doStreamingCheck(binding: LayoutNetworkBinding) {
        setStartEnabled(binding.streamingTest, false)
        binding.streamingResult.isVisible = true
        binding.streamingResult.text = getString(R.string.egress_check_testing)
        binding.streamingResultList.isVisible = false
        binding.streamingResultList.removeAllViews()
        runOnLifecycleDispatcher {
            val result: Pair<List<StreamingUnlock.Result>?, String?> = try {
                StreamingUnlock.checkAll() to null
            } catch (e: Exception) {
                null to getString(R.string.egress_check_error, e.readableMessage)
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                setStartEnabled(binding.streamingTest, true)
                val (results, error) = result
                if (results == null) {
                    binding.streamingResult.text = error
                    return@onMainDispatcher
                }
                val available = results.count { it.state == StreamingUnlock.State.AVAILABLE }
                val restricted = results.count { it.state == StreamingUnlock.State.RESTRICTED }
                val unavailable = results.size - available - restricted
                val mode = getString(
                    if (DataStore.serviceState.connected) R.string.url_check_via_node else R.string.url_check_direct
                )
                binding.streamingResult.text = getString(
                    R.string.streaming_summary, available, restricted, unavailable, mode
                )
                binding.streamingResultList.isVisible = true
                for (r in results) {
                    val row = TextView(requireContext()).apply {
                        text = getString(
                            R.string.streaming_row,
                            r.service.name,
                            stateText(r.state),
                            r.latencyMs,
                            r.detail,
                        )
                        textSize = 13f
                        setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                when (r.state) {
                                    StreamingUnlock.State.AVAILABLE -> R.color.color_rift_success
                                    StreamingUnlock.State.RESTRICTED -> R.color.color_rift_accent
                                    StreamingUnlock.State.UNAVAILABLE -> R.color.color_rift_danger
                                }
                            )
                        )
                    }
                    binding.streamingResultList.addView(row)
                }
            }
        }
    }

    private fun stateText(state: StreamingUnlock.State): String {
        return getString(
            when (state) {
                StreamingUnlock.State.AVAILABLE -> R.string.streaming_available
                StreamingUnlock.State.RESTRICTED -> R.string.streaming_restricted
                StreamingUnlock.State.UNAVAILABLE -> R.string.streaming_unavailable
            }
        )
    }

    // IP 纯净度（0.2.29 -26）：先拿出口 IP（复用出口自检的查询），再查质量。
    private fun doIpPurityCheck(binding: LayoutNetworkBinding) {
        setStartEnabled(binding.ipPurityTest, false)
        binding.ipPurityResult.isVisible = true
        binding.ipPurityResult.text = getString(R.string.egress_check_testing)
        runOnLifecycleDispatcher {
            val result: Pair<IpPurity.Result?, String?> = try {
                val ip = EgressIp.queryJson().optString("query")
                if (ip.isBlank()) {
                    null to getString(R.string.ip_purity_no_ip)
                } else {
                    IpPurity.check(ip) to null
                }
            } catch (e: Exception) {
                null to getString(R.string.egress_check_error, e.readableMessage)
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                setStartEnabled(binding.ipPurityTest, true)
                val (info, error) = result
                binding.ipPurityResult.text = if (info != null) {
                    getString(
                        R.string.ip_purity_result,
                        info.ip,
                        listOf(info.country, info.city).filter { it.isNotBlank() }.joinToString(" · "),
                        info.isp,
                        typeText(info.type),
                        gradeText(info.grade),
                        info.riskScore,
                        info.source,
                    )
                } else {
                    error ?: getString(R.string.ip_purity_quality_unavailable)
                }
            }
        }
    }

    private fun typeText(type: IpPurity.Type): String {
        return getString(
            when (type) {
                IpPurity.Type.RESIDENTIAL -> R.string.ip_purity_type_residential
                IpPurity.Type.DATACENTER -> R.string.ip_purity_type_datacenter
                IpPurity.Type.MOBILE -> R.string.ip_purity_type_mobile
                IpPurity.Type.BUSINESS -> R.string.ip_purity_type_business
                IpPurity.Type.UNKNOWN -> R.string.ip_purity_type_unknown
            }
        )
    }

    private fun gradeText(grade: IpPurity.Grade): String {
        return getString(
            when (grade) {
                IpPurity.Grade.GOOD -> R.string.ip_purity_grade_good
                IpPurity.Grade.NORMAL -> R.string.ip_purity_grade_normal
                IpPurity.Grade.RISK -> R.string.ip_purity_grade_risk
            }
        )
    }

    // 点出口 IP 测速（0.2.29 -26）：下 5MB 测下行，Cloudflare 测速端点。
    // 走 SOCKS（连着 VPN 时）或直连，与 URL 检查同一做法。
    private fun doSpeedTest(binding: LayoutNetworkBinding) {
        if (speedTesting) return
        speedTesting = true
        binding.egressSpeed.isVisible = true
        binding.egressSpeed.text = getString(R.string.egress_speed_testing)
        runOnLifecycleDispatcher {
            val result = try {
                checkSpeed()
            } catch (e: Exception) {
                getString(R.string.egress_check_error, e.readableMessage)
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                speedTesting = false
                binding.egressSpeed.text = result
            }
        }
    }

    private fun checkSpeed(): String {
        val connected = DataStore.serviceState.connected
        val mode = getString(if (connected) R.string.url_check_via_node else R.string.url_check_direct)
        val url = "https://speed.cloudflare.com/__down?bytes=5000000"
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (connected) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", DataStore.mixedPort)))
                    if (DataStore.localProxyAuthEnabled) {
                        System.setProperty("java.net.socks.username", DataStore.mixedInboundUser)
                        System.setProperty("java.net.socks.password", DataStore.mixedInboundPass)
                    } else {
                        System.clearProperty("java.net.socks.username")
                        System.clearProperty("java.net.socks.password")
                    }
                }
            }
            .build()
        try {
            val request = Request.Builder().url(url).get().build()
            val start = SystemClock.elapsedRealtime()
            var bytes = 0L
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    return getString(R.string.egress_check_error, "HTTP ${response.code}")
                }
                val stream = response.body?.byteStream()
                    ?: return getString(R.string.egress_check_error, "empty body")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    bytes += n
                }
            }
            val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
            if (seconds <= 0 || bytes <= 0) return getString(R.string.egress_check_error, "no data")
            val mbps = bytes * 8.0 / seconds / 1_000_000.0
            return getString(
                R.string.egress_speed_result,
                "%.1f Mbps".format(mbps),
                "${bytes / 1024 / 1024}MB",
                "%.1f s".format(seconds),
            ) + "（$mode）"
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

}
