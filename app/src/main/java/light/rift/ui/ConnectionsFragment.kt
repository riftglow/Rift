package light.rift.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import light.rift.R
import light.rift.database.DataStore
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.runOnLifecycleDispatcher
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

// 实时连接列表：只读本地 Clash API，不对外开端口。
// 入口只在 Clash API 开关打开时出现在抽屉里（见 MainActivity.refreshNavMenu），
// Clash API 本身默认关闭的策略不动；关着时这里只显示提示，不自动替用户打开。
// 数据来自 sing-box 内置 Clash API：GET 127.0.0.1:9090/connections，
// 关闭单条连接：DELETE 127.0.0.1:9090/connections/{id}。
// 注意：必须用 Proxy.NO_PROXY 直连本机，绝不能经 mixed 入站走代理，
// 否则查到的就是代理自己的连接，列表会自指。
class ConnectionsFragment : ToolbarFragment(R.layout.layout_connections) {

    private data class Conn(
        val id: String,
        val dest: String,
        val rule: String,
        val outbound: String,
        val upRate: Long,
        val downRate: Long,
    )

    private lateinit var adapter: ConnAdapter

    private fun onCloseClicked(conn: Conn) {
        // 点关闭后先从列表里拿掉，DELETE 在后台跑；失败时下次轮询会把它带回来，
        // 不会假装关掉了实际没关。
        adapter.remove(conn.id)
        runOnLifecycleDispatcher {
            try {
                withContext(Dispatchers.IO) {
                    val url = URL("http://127.0.0.1:9090/connections/${conn.id}")
                    (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                        requestMethod = "DELETE"
                        // Clash API secret（0.2.29 -26）：9090 现在要 Bearer 认证。
                        setRequestProperty("Authorization", "Bearer ${DataStore.clashApiSecret}")
                        connectTimeout = 3000
                        readTimeout = 3000
                        connect()
                        responseCode
                        disconnect()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun onCloseAllClicked() {
        // 全部断开（2026-11-15，0.2.29）：DELETE /connections（不带 id）是 sing-box
        // Clash API 的关全部接口（experimental/clashapi/connections.go 的
        // closeAllConnections）。注意它除了关连接，还会重置网络，点一下不只是
        // 断开列表里这几条，网络栈会重建一次，可能有短暂抖动。列表先清空，
        // 下次轮询会带回实际还活着的连接，不会假装全关掉了实际没关。
        adapter.submit(emptyList())
        runOnLifecycleDispatcher {
            try {
                withContext(Dispatchers.IO) {
                    val url = URL("http://127.0.0.1:9090/connections")
                    (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                        requestMethod = "DELETE"
                        // Clash API secret（0.2.29 -26）：9090 现在要 Bearer 认证。
                        setRequestProperty("Authorization", "Bearer ${DataStore.clashApiSecret}")
                        connectTimeout = 3000
                        readTimeout = 3000
                        connect()
                        responseCode
                        disconnect()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private val prevTraffic = mutableMapOf<String, Pair<Long, Long>>()
    private var prevTimeMs = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_connections)
        adapter = ConnAdapter(::onCloseClicked)
        val list = view.findViewById<RecyclerView>(R.id.connection_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val closeAll = view.findViewById<TextView>(R.id.close_all)
        closeAll.setOnClickListener { onCloseAllClicked() }
        // L-1: status 只找一次，不要每 2 秒轮询都重新 findViewById。
        val status = view.findViewById<TextView>(R.id.connection_status)
        runOnLifecycleDispatcher {
            while (isAdded) {
                try {
                    when {
                        !DataStore.enableClashAPI -> {
                            onMainDispatcher {
                                if (isAdded) {
                                    status.text = getString(R.string.connections_clash_off)
                                    adapter.submit(emptyList())
                                    closeAll.isVisible = false
                                }
                            }
                        }

                        !DataStore.serviceState.connected -> {
                            onMainDispatcher {
                                if (isAdded) {
                                    status.text = getString(R.string.connections_vpn_off)
                                    adapter.submit(emptyList())
                                    closeAll.isVisible = false
                                }
                            }
                        }

                        else -> {
                            val conns = fetchConnections()
                            onMainDispatcher {
                                if (isAdded) {
                                    status.text = if (conns.isEmpty()) {
                                        getString(R.string.connections_empty)
                                    } else {
                                        getString(R.string.connections_count, conns.size)
                                    }
                                    adapter.submit(conns)
                                    closeAll.isVisible = conns.isNotEmpty()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    onMainDispatcher {
                        if (isAdded) {
                            status.text = getString(R.string.connections_error, e.message ?: "")
                        }
                    }
                }
                delay(2000)
            }
        }
    }

    private suspend fun fetchConnections(): List<Conn> = withContext(Dispatchers.IO) {
        val url = URL("http://127.0.0.1:9090/connections")
        val conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            // Clash API secret（0.2.29 -26）：9090 现在要 Bearer 认证。
            setRequestProperty("Authorization", "Bearer ${DataStore.clashApiSecret}")
            connectTimeout = 3000
            readTimeout = 3000
        }
        val body = try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val nowMs = System.currentTimeMillis()
        val elapsedSec = if (prevTimeMs == 0L) 0.0
            else ((nowMs - prevTimeMs) / 1000.0).coerceAtLeast(0.5)
        prevTimeMs = nowMs
        val arr = JSONObject(body).optJSONArray("connections") ?: return@withContext emptyList()
        val out = mutableListOf<Conn>()
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            seen.add(id)
            val meta = obj.optJSONObject("metadata")
            val host = meta?.optString("host") ?: ""
            val destIp = meta?.optString("destinationIP") ?: ""
            val destPort = meta?.optString("destinationPort") ?: ""
            val dest = when {
                host.isNotBlank() -> if (destPort.isNotBlank()) "$host:$destPort" else host
                destIp.isNotBlank() -> if (destPort.isNotBlank()) "$destIp:$destPort" else destIp
                else -> id
            }
            val chains = obj.optJSONArray("chains")
            val outbound = if (chains != null && chains.length() > 0) {
                chains.optString(chains.length() - 1)
            } else ""
            val up = obj.optLong("upload")
            val down = obj.optLong("download")
            val prev = prevTraffic[id]
            val upRate = if (prev == null || elapsedSec == 0.0) 0L
                else ((up - prev.first) / elapsedSec).toLong().coerceAtLeast(0L)
            val downRate = if (prev == null || elapsedSec == 0.0) 0L
                else ((down - prev.second) / elapsedSec).toLong().coerceAtLeast(0L)
            prevTraffic[id] = up to down
            out.add(Conn(id, dest, obj.optString("rule"), outbound, upRate, downRate))
        }
        prevTraffic.keys.retainAll(seen)
        out.sortedByDescending { it.upRate + it.downRate }
    }

    private inner class ConnAdapter(
        private val onClose: (Conn) -> Unit,
    ) : RecyclerView.Adapter<ConnAdapter.Holder>() {

        private var items: List<Conn> = emptyList()

        fun submit(list: List<Conn>) {
            items = list
            notifyDataSetChanged()
        }

        fun remove(id: String) {
            items = items.filter { it.id != id }
            notifyDataSetChanged()
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val dest: TextView = v.findViewById(R.id.conn_dest)
            val rule: TextView = v.findViewById(R.id.conn_rule)
            val outbound: TextView = v.findViewById(R.id.conn_outbound)
            val up: TextView = v.findViewById(R.id.conn_up)
            val down: TextView = v.findViewById(R.id.conn_down)
            val close: ImageView = v.findViewById(R.id.conn_close)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_connection_item, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context
            holder.dest.text = item.dest
            holder.rule.text = context.getString(
                R.string.connections_rule_badge, item.rule.ifBlank { "-" })
            holder.outbound.text = context.getString(
                R.string.connections_outbound_badge, item.outbound.ifBlank { "-" })
            // 速度着色：正在传的才上色（上行绿、下行琥珀），0 的灰掉。
            holder.up.text = "▲ ${Formatter.formatFileSize(context, item.upRate)}/s"
            holder.up.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (item.upRate > 0) R.color.color_rift_success
                    else android.R.color.darker_gray
                )
            )
            holder.down.text = "▼ ${Formatter.formatFileSize(context, item.downRate)}/s"
            holder.down.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (item.downRate > 0) R.color.color_rift_accent
                    else android.R.color.darker_gray
                )
            )
            holder.close.setOnClickListener { onClose(item) }
        }
    }
}
