package light.rift.core.utils

import android.os.SystemClock
import light.rift.database.DataStore
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 手动 SOCKS5 隧道（0.2.29 -27）：
 * -26 的三处 OkHttp 诊断（URL 检查、流媒体解锁、出口测速）连着 VPN 时全灭，
 * 报错都是 "SOCKS : authentication failed"。根因：安卓的 SocksSocketImpl
 * 根本不认 java.net.socks.username / java.net.socks.password 这两个系统属性
 * （Android 28 源码的 authenticate() 只试 Authenticator，拿不到就用 user.name
 * 加空密码——等于拿错账号去认证，服务端必然拒绝；桌面 OpenJDK 才读这俩属性）。
 * 同一份账号密码走 libcore 的 Go 握手（TrySocks5Auth）就能通，实锤是这条路的问题。
 * 所以这三处不再走 OkHttp + 系统属性，改走这里的手动握手：直连 TCP 到
 * 127.0.0.1:mixedPort，自己发 greeting、RFC 1929 账号密码、CONNECT，拿到
 * 已连通的 socket 再包 TLS、发 HTTP。账号密码直接来自 DataStore，与生成
 * 配置的是同一份（ConfigBuilder 的 users），不会错配。
 * VPN 没连时直接连目标，不经过代理。
 */
object Socks5Tunnel {

    private const val CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 三段耗时：连接 / TLS / HTTP（只读状态行）。 */
    data class HttpsResult(
        val statusCode: Int,
        val connectMs: Long,
        val tlsMs: Long,
        val httpMs: Long,
    )

    /**
     * 连到 host:port。VPN 连着时走本地 mixed 入站 SOCKS5（带认证），
     * 否则直连。返回已连通的 TCP socket（握手已完成）。
     */
    @Throws(IOException::class)
    fun connect(host: String, port: Int, timeoutMs: Int = 10000): Socket {
        if (!DataStore.serviceState.connected) {
            return Socket().apply {
                connect(InetSocketAddress(host, port), timeoutMs)
                soTimeout = timeoutMs
            }
        }
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", DataStore.mixedPort), timeoutMs)
        socket.soTimeout = timeoutMs
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val auth = DataStore.localProxyAuthEnabled
        // greeting：有认证就同时报无认证和账号密码两种方式，没认证只报无认证。
        if (auth) {
            output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            output.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        output.flush()
        val version = input.read()
        val method = input.read()
        if (version != 0x05) throw IOException("SOCKS bad version: $version")
        when (method) {
            0x00 -> Unit // 无认证，直接发 CONNECT
            0x02 -> {
                if (!auth) throw IOException("SOCKS server requires auth")
                val user = DataStore.mixedInboundUser.toByteArray(Charsets.UTF_8)
                val pass = DataStore.mixedInboundPass.toByteArray(Charsets.UTF_8)
                val authReq = ByteArrayOutputStream().apply {
                    write(0x01)
                    write(user.size)
                    write(user)
                    write(pass.size)
                    write(pass)
                }
                output.write(authReq.toByteArray())
                output.flush()
                val authVersion = input.read()
                val status = input.read()
                if (authVersion != 0x01 || status != 0x00) {
                    throw IOException("SOCKS authentication failed")
                }
            }
            else -> throw IOException("SOCKS no acceptable methods: $method")
        }
        // CONNECT：域名形式（ATYP 0x03），让节点侧解析域名。
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        val connectReq = ByteArrayOutputStream().apply {
            write(0x05)
            write(0x01)
            write(0x00)
            write(0x03)
            write(hostBytes.size)
            write(hostBytes)
            write(port shr 8)
            write(port and 0xFF)
        }
        output.write(connectReq.toByteArray())
        output.flush()
        val replyVersion = input.read()
        val reply = input.read()
        if (replyVersion != 0x05 || reply != 0x00) {
            throw IOException("SOCKS connect failed: $reply")
        }
        input.read() // RSV
        when (val addressType = input.read()) {
            0x01 -> repeat(4) { input.read() }
            0x03 -> {
                val length = input.read()
                repeat(length) { input.read() }
            }
            0x04 -> repeat(16) { input.read() }
            else -> throw IOException("SOCKS bad address type: $addressType")
        }
        input.read()
        input.read() // BND.PORT
        return socket
    }

    /** 在已连通的 socket 上做 TLS 握手（SNI 取 host）。 */
    @Throws(IOException::class)
    fun startTls(socket: Socket, host: String, port: Int, timeoutMs: Int = 10000): SSLSocket {
        // getDefault() 的声明返回类型是 SocketFactory（没有 4 参那个重载），
        // 必须先转成 SSLSocketFactory 才能调 createSocket(Socket, host, port, autoClose)。
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socket, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        return ssl
    }

    /** 发 GET，只读状态行，返回状态码（不读 body，用完直接关 socket）。 */
    @Throws(IOException::class)
    fun getStatus(sslSocket: SSLSocket, host: String, path: String): Int {
        val output = sslSocket.outputStream
        output.write(
            ("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $CHROME_UA\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.UTF_8)
        )
        output.flush()
        val reader = BufferedReader(InputStreamReader(sslSocket.inputStream, Charsets.UTF_8))
        val statusLine = reader.readLine() ?: throw IOException("empty response")
        val parts = statusLine.split(" ")
        return parts.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("bad status line: $statusLine")
    }

    /** 发 GET，读完 body，返回 body 字节数（处理 Content-Length 与 chunked）。 */
    @Throws(IOException::class)
    fun downloadBytes(sslSocket: SSLSocket, host: String, path: String, timeoutMs: Int = 30000): Long {
        sslSocket.soTimeout = timeoutMs
        val input = sslSocket.inputStream
        val output = sslSocket.outputStream
        output.write(
            ("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $CHROME_UA\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.UTF_8)
        )
        output.flush()
        val headers = readHeaders(input)
        val statusLine = headers.firstOrNull() ?: throw IOException("empty response")
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw IOException("bad status line: $statusLine")
        if (status !in 200..299) throw IOException("HTTP $status")
        val headerMap = headers.drop(1).mapNotNull {
            val colon = it.indexOf(':')
            if (colon > 0) it.substring(0, colon).trim().lowercase() to it.substring(colon + 1).trim() else null
        }.toMap()
        if (headerMap["transfer-encoding"]?.contains("chunked") == true) {
            var bytes = 0L
            while (true) {
                val sizeLine = readLine(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: break
                if (size == 0L) {
                    // 尾块：读到空行（trailer 结束）。
                    while (true) {
                        if (readLine(input).isNullOrEmpty()) break
                    }
                    break
                }
                bytes += readExact(input, size)
                readLine(input) // 块后的 CRLF
            }
            return bytes
        }
        val length = headerMap["content-length"]?.toLongOrNull() ?: -1L
        if (length >= 0) return readExact(input, length)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** 一站式：连通 + TLS + GET 状态码，附带三段耗时。 */
    @Throws(IOException::class)
    fun httpsGet(host: String, path: String, timeoutMs: Int = 10000): HttpsResult {
        val connectStart = SystemClock.elapsedRealtime()
        val socket = connect(host, 443, timeoutMs)
        val connectMs = SystemClock.elapsedRealtime() - connectStart
        socket.use {
            val tlsStart = SystemClock.elapsedRealtime()
            val ssl = startTls(it, host, 443, timeoutMs)
            val tlsMs = SystemClock.elapsedRealtime() - tlsStart
            ssl.use {
                val httpStart = SystemClock.elapsedRealtime()
                val code = getStatus(it, host, path)
                val httpMs = SystemClock.elapsedRealtime() - httpStart
                return HttpsResult(code, connectMs, tlsMs, httpMs)
            }
        }
    }

    /** 从原始流读响应头（逐字节读到空行），返回每行（不含 CRLF）。 */
    private fun readHeaders(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            lines.add(line)
        }
        return lines
    }

    /** 读一行（以 LF 结束，去掉尾部 CR）。 */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
            if (b == 10) break // LF
            if (b != 13) buf.write(b) // 丢掉 CR
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    /** 读满 n 字节（丢弃内容，只计数），返回实际读到的字节数。 */
    private fun readExact(input: InputStream, n: Long): Long {
        val buf = ByteArray(64 * 1024)
        var remaining = n
        var total = 0L
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, want)
            if (read < 0) break
            total += read
            remaining -= read
        }
        return total
    }
}
