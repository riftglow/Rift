package light.rift.ui

import android.content.Intent
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import light.rift.R
import light.rift.core.utils.Socks5Tunnel
import light.rift.database.DataStore
import light.rift.database.RiftDatabase
import light.rift.databinding.LayoutNetworkBinding
import light.rift.ktx.app
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.readableMessage
import light.rift.ktx.runOnLifecycleDispatcher
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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
        // 点出口 IP 测速（0.2.29 -26）：IP 下划线表示可点。
        binding.egressIp.paintFlags = binding.egressIp.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.egressIp.setOnClickListener {
            doSpeedTest(binding)
        }
    }

    private var speedTesting = false

    // 诊断结果填进去、卡片变高后，把卡片底部滚到可见区（2026-11-21 -44，2026-11-22 -49/-50）：
    // 测试跑完文字变长，卡片底部会滑出屏幕，之前得用户手动追着滑。
    // -49 改动：只滚结果文字不够，卡片最底下的"开始"按钮还在屏外，
    // 所以滚的目标改成卡片底部的"开始"按钮（卡片里最低的元素），
    // 滚它进来，整张卡片（结果＋按钮）就都可见了。
    // -50 教训：-49 用 doOnLayout 滚按钮是错的——按钮本身没变高，
    // doOnLayout 在它已布局好时立刻执行，拿的是结果还没长出来之前
    // 的旧位置，滚得不够，反而比 -44 露得更少。改成 post，等这一帧
    // 布局量完（按钮落到最终位置）再滚。
    // requestRectangleOnScreen 只在目标被挡住时才滚，已经在屏里的不动，不会乱跳。
    private fun scrollCardBottomIntoView(buttonView: View) {
        buttonView.post {
            // 按钮下方再多留 24dp，让按钮不贴着屏幕底边（2026-11-22 -51）：
            // -50 只把按钮滚到刚好可见，按钮底边贴着屏边，看着还差一点点。
            val extra = (24 * buttonView.resources.displayMetrics.density).toInt()
            buttonView.requestRectangleOnScreen(
                Rect(0, 0, buttonView.width, buttonView.height + extra), false
            )
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
                    // 点 IP 测速的提示（0.2.29 -26）：测速结果复用这行。
                    binding.egressSpeed.isVisible = true
                    binding.egressSpeed.text = getString(R.string.egress_speed_tap_hint)
                    scrollCardBottomIntoView(binding.egressCheck)
                } else {
                    binding.egressOverview.isVisible = false
                    binding.egressResult.isVisible = true
                    binding.egressResult.text = error
                    scrollCardBottomIntoView(binding.egressCheck)
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
                scrollCardBottomIntoView(binding.dnsLeakTest)
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
    // FakeDNS 开时应返回 198.18.x.x）；连接/TLS/HTTP 用 Socks5Tunnel 手动握手
    // 分步计时，VPN 开时经 mixed 入站 SOCKS5，关时直连。
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
                scrollCardBottomIntoView(binding.urlCheckTest)
            }
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

        // 3. 连接 / TLS / HTTP：Socks5Tunnel 手动握手，分步计时。
        // -26 的 OkHttp + java.net.socks.* 系统属性在安卓上不工作
        // （SocksSocketImpl 不读这俩属性，拿 user.name 加空密码去认证，
        // 连着 VPN 时连接步直接报 "SOCKS : authentication failed"），改手动握手。
        val connectStart = SystemClock.elapsedRealtime()
        val socket = try {
            Socks5Tunnel.connect(host, 443, 10000)
        } catch (e: Exception) {
            out.appendLine(getString(R.string.url_check_step_connect_failed, mode, e.readableMessage))
            out.append(getString(R.string.url_check_conclusion_connect))
            return out.toString()
        }
        val connectMs = SystemClock.elapsedRealtime() - connectStart
        out.appendLine(getString(R.string.url_check_step_connect_ok, mode, connectMs))
        var tlsDone = false
        try {
            socket.use {
                val tlsStart = SystemClock.elapsedRealtime()
                val ssl = Socks5Tunnel.startTls(it, host, 443, 10000)
                val tlsMs = SystemClock.elapsedRealtime() - tlsStart
                tlsDone = true
                out.appendLine(getString(R.string.url_check_step_tls_ok, tlsMs))
                ssl.use {
                    val httpStart = SystemClock.elapsedRealtime()
                    val code = Socks5Tunnel.getStatus(it, host, "/generate_204")
                    val httpMs = SystemClock.elapsedRealtime() - httpStart
                    if (code in 200..399) {
                        out.appendLine(getString(R.string.url_check_step_http_ok, code, httpMs))
                        out.append(
                            getString(
                                R.string.url_check_conclusion_ok,
                                SystemClock.elapsedRealtime() - totalStart
                            )
                        )
                    } else {
                        out.appendLine(getString(R.string.url_check_step_http_failed, "HTTP $code"))
                        out.append(getString(R.string.url_check_conclusion_http))
                    }
                }
            }
        } catch (e: Exception) {
            // 按走到哪一步来定失败步骤。
            if (!tlsDone) {
                out.appendLine(getString(R.string.url_check_step_tls_failed, e.readableMessage))
                out.append(getString(R.string.url_check_conclusion_tls))
            } else {
                out.appendLine(getString(R.string.url_check_step_http_failed, e.readableMessage))
                out.append(getString(R.string.url_check_conclusion_http))
            }
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
                    scrollCardBottomIntoView(binding.streamingTest)
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
                scrollCardBottomIntoView(binding.streamingTest)
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
                // 测速结果滚进可见区（2026-11-22 -48/-49/-51）：egressSpeed 从"测速中…"到结果
                // 高度基本不变，doOnLayout 不触发新布局，平滑滚动又常被触摸打断，
                // 所以 post 到下一帧、直接瞬时滚。-49：滚的目标改成出口卡片底部的
                // "开始"按钮（卡片里最低的元素），测速结果在它上面，滚它进来整张卡都可见。
                // -51：按钮下方再多留 24dp，不贴着屏边。
                binding.egressCheck.post {
                    val extra = (24 * binding.egressCheck.resources.displayMetrics.density).toInt()
                    binding.egressCheck.requestRectangleOnScreen(
                        Rect(0, 0, binding.egressCheck.width, binding.egressCheck.height + extra), true
                    )
                }
            }
        }
    }

    private fun checkSpeed(): String {
        val connected = DataStore.serviceState.connected
        val mode = getString(if (connected) R.string.url_check_via_node else R.string.url_check_direct)
        val host = "speed.cloudflare.com"
        val path = "/__down?bytes=5000000"
        val start = SystemClock.elapsedRealtime()
        val socket = Socks5Tunnel.connect(host, 443, 10000)
        socket.use {
            val ssl = Socks5Tunnel.startTls(it, host, 443, 10000)
            ssl.use {
                val bytes = Socks5Tunnel.downloadBytes(it, host, path, 30000)
                val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
                if (seconds <= 0 || bytes <= 0) return getString(R.string.egress_check_error, "no data")
                val mbps = bytes * 8.0 / seconds / 1_000_000.0
                return getString(
                    R.string.egress_speed_result,
                    "%.1f Mbps".format(mbps),
                    "${bytes / 1024 / 1024}MB",
                    "%.1f s".format(seconds),
                ) + "（$mode）"
            }
        }
    }

}
