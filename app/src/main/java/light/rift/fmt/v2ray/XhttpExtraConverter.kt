package light.rift.fmt.v2ray

import org.json.JSONObject

/**
 * XHTTP 附加选项在 Xray / sing-box / Clash 三种写法之间的换算。
 *
 * 三种写法说的是同一组选项，只是键名风格不同：
 * Xray 用 camelCase（xPaddingBytes），sing-box 用 snake_case（x_padding_bytes），
 * Clash 用 kebab-case（x-padding-bytes）。本文件只负责键名与结构的搬运，
 * 不解释任何选项的含义，含义由核心（sing-box）决定。
 *
 * 转换是“有损兜底”的：输入不是合法 JSON、或字段不在表里，
 * 一律原样返回输入，不抛异常，不丢数据。
 */
object XhttpExtraConverter {

    // 顶层字段：Xray 键名 -> sing-box 键名（21 个）。
    // 反向（sing-box -> Xray）直接把这张表倒过来用，不另写一份，防两边写岔。
    private val BASE_FIELDS = listOf(
        "xPaddingBytes" to "x_padding_bytes",
        "scMaxEachPostBytes" to "sc_max_each_post_bytes",
        "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
        "scMaxBufferedPosts" to "sc_max_buffered_posts",
        "scStreamUpServerSecs" to "sc_stream_up_server_secs",
        "noGRPCHeader" to "no_grpc_header",
        "noSSEHeader" to "no_sse_header",
        "headers" to "headers",
        "xPaddingObfsMode" to "x_padding_obfs_mode",
        "xPaddingKey" to "x_padding_key",
        "xPaddingHeader" to "x_padding_header",
        "xPaddingPlacement" to "x_padding_placement",
        "xPaddingMethod" to "x_padding_method",
        "uplinkHttpMethod" to "uplink_http_method",
        "sessionIdPosition" to "session_placement",
        "sessionIdName" to "session_key",
        "seqPosition" to "seq_placement",
        "seqName" to "seq_key",
        "dataUpPlacement" to "uplink_data_placement",
        "dataUpName" to "uplink_data_key",
        "dataUpSplitSize" to "uplink_chunk_size",
    )

    // xmux 子块字段：Xray 键名 -> sing-box 键名（6 个），反向同样倒过来用。
    private val XMUX_FIELDS = listOf(
        "maxConcurrency" to "max_concurrency",
        "maxConnections" to "max_connections",
        "cMaxReuseTimes" to "c_max_reuse_times",
        "hMaxRequestTimes" to "h_max_request_times",
        "hMaxReusableSecs" to "h_max_reusable_secs",
        "hKeepAlivePeriod" to "h_keep_alive_period",
    )

    // downloadSettings 里 xhttpSettings 的三个直通字段（两种写法键名相同）。
    private val DOWNLOAD_XHTTP_FIELDS = listOf("mode", "host", "path")

    // Clash 顶层字段：Clash 键名 -> sing-box 键名（21 个，与 BASE_FIELDS 同一组选项）。
    private val CLASH_BASE_KEYS = mapOf(
        "headers" to "headers",
        "x-padding-bytes" to "x_padding_bytes",
        "no-grpc-header" to "no_grpc_header",
        "no-sse-header" to "no_sse_header",
        "sc-max-each-post-bytes" to "sc_max_each_post_bytes",
        "sc-min-posts-interval-ms" to "sc_min_posts_interval_ms",
        "sc-max-buffered-posts" to "sc_max_buffered_posts",
        "sc-stream-up-server-secs" to "sc_stream_up_server_secs",
        "x-padding-obfs-mode" to "x_padding_obfs_mode",
        "x-padding-key" to "x_padding_key",
        "x-padding-header" to "x_padding_header",
        "x-padding-placement" to "x_padding_placement",
        "x-padding-method" to "x_padding_method",
        "uplink-http-method" to "uplink_http_method",
        "session-placement" to "session_placement",
        "session-key" to "session_key",
        "seq-placement" to "seq_placement",
        "seq-key" to "seq_key",
        "uplink-data-placement" to "uplink_data_placement",
        "uplink-data-key" to "uplink_data_key",
        "uplink-chunk-size" to "uplink_chunk_size",
    )

    // Clash xmux 字段：Clash 键名 -> sing-box 键名（6 个）。
    private val CLASH_XMUX_KEYS = mapOf(
        "max-concurrency" to "max_concurrency",
        "max-connections" to "max_connections",
        "c-max-reuse-times" to "c_max_reuse_times",
        "h-max-request-times" to "h_max_request_times",
        "h-max-reusable-secs" to "h_max_reusable_secs",
        "h-keep-alive-period" to "h_keep_alive_period",
    )

    // 判格式用的键名集合：出现任一即认定是该格式。
    private val SING_BOX_KEYS = BASE_FIELDS.map { it.second }.toSet() + "download"
    private val XRAY_KEYS = BASE_FIELDS.map { it.first }.toSet() + "downloadSettings"
    private val SING_BOX_XMUX_KEYS = XMUX_FIELDS.map { it.second }.toSet()
    private val XRAY_XMUX_KEYS = XMUX_FIELDS.map { it.first }.toSet()

    /** Xray 写法 -> sing-box 写法。已是 sing-box 写法则原样返回。 */
    fun xrayToSingBox(xrayExtra: String): String {
        if (xrayExtra.isBlank()) return ""
        return try {
            val xray = JSONObject(xrayExtra)
            if (isSingBoxFormat(xray)) return xrayExtra
            val singBox = JSONObject()

            copyFields(xray, singBox, BASE_FIELDS)
            copyXmux(xray, singBox, XMUX_FIELDS)

            if (xray.has("downloadSettings")) {
                val download = convertDownloadSettings(xray.getJSONObject("downloadSettings"))
                if (download.length() > 0) singBox.put("download", download)
            }

            pretty(singBox)
        } catch (e: Exception) {
            e.printStackTrace()
            xrayExtra
        }
    }

    /** sing-box 写法 -> Xray 写法。已是 Xray 写法则原样返回。 */
    fun singBoxToXray(singBoxExtra: String): String {
        if (singBoxExtra.isBlank()) return ""
        return try {
            val singBox = JSONObject(singBoxExtra)
            if (isXrayFormat(singBox)) return singBoxExtra
            val xray = JSONObject()

            copyFields(singBox, xray, BASE_FIELDS.reversedPairs())
            copyXmux(singBox, xray, XMUX_FIELDS.reversedPairs())

            if (singBox.has("download")) {
                val download = convertDownloadBack(singBox.getJSONObject("download"))
                if (download.length() > 0) xray.put("downloadSettings", download)
            }

            pretty(xray)
        } catch (e: Exception) {
            e.printStackTrace()
            singBoxExtra
        }
    }

    /** Clash 订阅里的 xhttp 选项（Map）-> sing-box 写法 JSON。无有效字段返回空串。 */
    fun clashToSingBox(xhttpOpts: Map<String, Any?>): String {
        val singBox = JSONObject()
        copyClashFields(xhttpOpts, singBox, CLASH_BASE_KEYS)
        copyClashXmux(xhttpOpts["reuse-settings"], singBox)

        (xhttpOpts["download-settings"] as? Map<*, *>)?.let { downloadSettings ->
            val download = JSONObject()
            copyClashFields(downloadSettings, download, CLASH_BASE_KEYS)
            DOWNLOAD_XHTTP_FIELDS.forEach { key ->
                copyClashField(downloadSettings, download, key, key)
            }
            copyClashXmux(downloadSettings["reuse-settings"], download)
            copyClashField(downloadSettings, download, "server", "server")
            copyClashField(downloadSettings, download, "port", "server_port")

            val realityOptions = downloadSettings["reality-opts"] as? Map<*, *>
            if (downloadSettings["tls"]?.toString() == "true" || realityOptions != null) {
                download.put("tls", buildClashTls(downloadSettings, realityOptions))
            }

            if (download.length() > 0) singBox.put("download", download)
        }

        return if (singBox.length() > 0) pretty(singBox) else ""
    }

    // ---- xrayToSingBox 的 downloadSettings ----

    private fun convertDownloadSettings(xrayDown: JSONObject): JSONObject {
        val singBoxDown = JSONObject()

        xrayDown.optJSONObject("xhttpSettings")?.let { xhttpSettings ->
            DOWNLOAD_XHTTP_FIELDS.forEach { key ->
                copyField(xhttpSettings, singBoxDown, key, key)
            }
        }
        copyField(xrayDown, singBoxDown, "address", "server")
        copyField(xrayDown, singBoxDown, "port", "server_port")

        if (xrayDown.has("security")) {
            singBoxDown.put("tls", buildSingBoxTls(xrayDown))
        }

        // downloadSettings.xhttpSettings.extra：顶层字段与 xmux 都可能出现。
        xrayDown.optJSONObject("xhttpSettings")?.optJSONObject("extra")?.let { extra ->
            copyXmux(extra, singBoxDown, XMUX_FIELDS)
            copyFields(extra, singBoxDown, BASE_FIELDS)
        }

        return singBoxDown
    }

    // Xray 的 security（tls / reality）-> sing-box 的 tls 块。
    private fun buildSingBoxTls(xrayDown: JSONObject): JSONObject {
        val tls = JSONObject().apply { put("enabled", true) }
        when (xrayDown.getString("security")) {
            "tls" -> {
                xrayDown.optJSONObject("tlsSettings")?.let { tlsSettings ->
                    copyField(tlsSettings, tls, "serverName", "server_name")
                    copyField(tlsSettings, tls, "alpn", "alpn")
                    copyField(tlsSettings, tls, "allowInsecure", "insecure")
                    putUtls(tls, tlsSettings.optString("fingerprint"))
                }
            }
            "reality" -> {
                xrayDown.optJSONObject("realitySettings")?.let { realitySettings ->
                    copyField(realitySettings, tls, "serverName", "server_name")
                    val reality = JSONObject().apply {
                        put("enabled", true)
                        copyField(realitySettings, this, "publicKey", "public_key")
                        copyField(realitySettings, this, "shortId", "short_id")
                    }
                    tls.put("reality", reality)
                    putUtls(tls, realitySettings.optString("fingerprint"))
                }
            }
        }
        return tls
    }

    // fingerprint 非空才挂 utls 子块；tls 与 reality 两路共用。
    private fun putUtls(tls: JSONObject, fingerprint: String?) {
        if (fingerprint.isNullOrBlank()) return
        tls.put("utls", JSONObject().apply {
            put("enabled", true)
            put("fingerprint", fingerprint)
        })
    }

    // ---- singBoxToXray 的 download ----

    private fun convertDownloadBack(singBoxDown: JSONObject): JSONObject {
        val xrayDown = JSONObject()

        copyField(singBoxDown, xrayDown, "server", "address")
        copyField(singBoxDown, xrayDown, "server_port", "port")
        xrayDown.put("network", "xhttp")

        if (singBoxDown.has("tls")) {
            val tls = singBoxDown.getJSONObject("tls")
            if (tls.has("reality") && tls.getJSONObject("reality").optBoolean("enabled", false)) {
                xrayDown.put("security", "reality")
                xrayDown.put("realitySettings", buildRealitySettings(tls))
            } else {
                xrayDown.put("security", "tls")
                xrayDown.put("tlsSettings", buildTlsSettings(tls))
            }
        }

        val xhttpSettings = JSONObject()
        DOWNLOAD_XHTTP_FIELDS.forEach { key ->
            copyField(singBoxDown, xhttpSettings, key, key)
        }

        // sing-box download 里的顶层字段与 xmux，还原进 xhttpSettings.extra。
        val xhttpExtra = JSONObject()
        copyFields(singBoxDown, xhttpExtra, BASE_FIELDS.reversedPairs())
        copyXmux(singBoxDown, xhttpExtra, XMUX_FIELDS.reversedPairs())
        if (xhttpExtra.length() > 0) xhttpSettings.put("extra", xhttpExtra)
        xrayDown.put("xhttpSettings", xhttpSettings)

        return xrayDown
    }

    private fun buildRealitySettings(tls: JSONObject): JSONObject {
        val reality = tls.getJSONObject("reality")
        return JSONObject().apply {
            copyField(tls, this, "server_name", "serverName")
            copyField(reality, this, "public_key", "publicKey")
            copyField(reality, this, "short_id", "shortId")
            if (tls.has("utls")) {
                copyField(tls.getJSONObject("utls"), this, "fingerprint", "fingerprint")
            }
        }
    }

    private fun buildTlsSettings(tls: JSONObject): JSONObject {
        return JSONObject().apply {
            copyField(tls, this, "server_name", "serverName")
            copyField(tls, this, "alpn", "alpn")
            copyField(tls, this, "insecure", "allowInsecure")
            if (tls.has("utls")) {
                copyField(tls.getJSONObject("utls"), this, "fingerprint", "fingerprint")
            }
        }
    }

    // ---- Clash 的 tls 块 ----

    private fun buildClashTls(
        downloadSettings: Map<*, *>,
        realityOptions: Map<*, *>?,
    ): JSONObject {
        val tls = JSONObject().apply { put("enabled", true) }
        copyClashField(downloadSettings, tls, "servername", "server_name")
        copyClashField(downloadSettings, tls, "alpn", "alpn")
        copyClashField(downloadSettings, tls, "skip-cert-verify", "insecure")
        downloadSettings["client-fingerprint"]?.let { fingerprint ->
            tls.put("utls", JSONObject().apply {
                put("enabled", true)
                put("fingerprint", fingerprint)
            })
        }
        realityOptions?.let { reality ->
            tls.put("reality", JSONObject().apply {
                put("enabled", true)
                copyClashField(reality, this, "public-key", "public_key")
                copyClashField(reality, this, "short-id", "short_id")
            })
        }
        return tls
    }

    // ---- 格式判定与搬运小件 ----

    private fun isSingBoxFormat(json: JSONObject): Boolean {
        return SING_BOX_KEYS.any(json::has) ||
                json.optJSONObject("xmux")?.let { xmux -> SING_BOX_XMUX_KEYS.any(xmux::has) } == true
    }

    private fun isXrayFormat(json: JSONObject): Boolean {
        return XRAY_KEYS.any(json::has) ||
                json.optJSONObject("xmux")?.let { xmux -> XRAY_XMUX_KEYS.any(xmux::has) } == true
    }

    // 按一张（fromKey -> toKey）表把字段从 from 搬到 to，只搬存在的。
    private fun copyFields(from: JSONObject, to: JSONObject, fields: List<Pair<String, String>>) {
        fields.forEach { (fromKey, toKey) -> copyField(from, to, fromKey, toKey) }
    }

    private fun copyField(from: JSONObject, to: JSONObject, fromKey: String, toKey: String) {
        if (from.has(fromKey)) {
            to.put(toKey, from.get(fromKey))
        }
    }

    // from 里的 "xmux" 子块按表搬到 to 的 "xmux" 子块；搬完空着就不挂。
    private fun copyXmux(from: JSONObject, to: JSONObject, fields: List<Pair<String, String>>) {
        if (!from.has("xmux")) return
        val out = JSONObject()
        copyFields(from.getJSONObject("xmux"), out, fields)
        if (out.length() > 0) to.put("xmux", out)
    }

    private fun copyClashFields(from: Map<*, *>, to: JSONObject, keys: Map<String, String>) {
        keys.forEach { (clashKey, singBoxKey) -> copyClashField(from, to, clashKey, singBoxKey) }
    }

    private fun copyClashXmux(value: Any?, to: JSONObject) {
        (value as? Map<*, *>)?.let { reuseSettings ->
            val xmux = JSONObject()
            copyClashFields(reuseSettings, xmux, CLASH_XMUX_KEYS)
            if (xmux.length() > 0) to.put("xmux", xmux)
        }
    }

    private fun copyClashField(from: Map<*, *>, to: JSONObject, fromKey: String, toKey: String) {
        if (from.containsKey(fromKey) && from[fromKey] != null) {
            to.put(toKey, JSONObject.wrap(from[fromKey]))
        }
    }

    // 把一对表倒过来（a->b 变成 b->a），供反向转换共用同一张表。
    private fun List<Pair<String, String>>.reversedPairs(): List<Pair<String, String>> =
        map { (a, b) -> b to a }

    // 缩进 2 格的 JSON 文本，并把 \/ 还原成 /（与原版输出格式一致）。
    private fun pretty(json: JSONObject): String = json.toString(2).replace("\\/", "/")
}
