package light.rift.core.net

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import light.rift.RiftApp
import light.rift.ktx.Logs
import light.rift.ktx.runOnIoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import libcore.ExchangeContext
import libcore.LocalDNSTransport
import java.net.InetAddress
import java.net.UnknownHostException

object LocalResolverImpl : LocalDNSTransport {

    // new local

    private const val RCODE_NXDOMAIN = 3

    override fun raw(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    override fun networkHandle(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return RiftApp.underlyingNetwork?.networkHandle ?: 0
        }
        return 0
    }

    /** 系统 DNS 报错：有 errno 就原样回传，没有就记日志并回一个通用错误码。 */
    private fun reportDnsError(ctx: ExchangeContext, error: DnsResolver.DnsException) {
        val cause = error.cause
        if (cause is ErrnoException) {
            ctx.errnoCode(cause.errno)
        } else {
            Logs.w(error)
            ctx.errnoCode(114514)
        }
    }

    /** 回调里出了意外异常：记日志并回通用错误码。 */
    private fun reportFailure(ctx: ExchangeContext, e: Exception) {
        Logs.w(e)
        ctx.errnoCode(114514)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val signal = CancellationSignal()
        ctx.onCancel(signal::cancel)

        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                ctx.rawSuccess(answer)
            }

            override fun onError(error: DnsResolver.DnsException) {
                reportDnsError(ctx, error)
            }
        }

        DnsResolver.getInstance().rawQuery(
            RiftApp.underlyingNetwork,
            message,
            DnsResolver.FLAG_NO_RETRY,
            Dispatchers.IO.asExecutor(),
            signal,
            callback
        )
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val signal = CancellationSignal()
            ctx.onCancel(signal::cancel)

            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    try {
                        if (rcode == 0) {
                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        } else {
                            ctx.errorCode(rcode)
                        }
                    } catch (e: Exception) {
                        reportFailure(ctx, e)
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    try {
                        reportDnsError(ctx, error)
                    } catch (e: Exception) {
                        reportFailure(ctx, e)
                    }
                }
            }

            val type = when {
                network.endsWith("4") -> DnsResolver.TYPE_A
                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            if (type != null) {
                DnsResolver.getInstance().query(
                    RiftApp.underlyingNetwork,
                    domain,
                    type,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            } else {
                DnsResolver.getInstance().query(
                    RiftApp.underlyingNetwork,
                    domain,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            }
        } else {
            runOnIoDispatcher {
                // 老版本系统，继续用阻塞的 InetAddress
                try {
                    val u = RiftApp.underlyingNetwork
                    val answer = try {
                        u?.getAllByName(domain)
                    } catch (e: UnknownHostException) {
                        null
                    } ?: InetAddress.getAllByName(domain)
                    if (answer != null) {
                        ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                    } else {
                        ctx.errnoCode(114514)
                    }
                } catch (e: UnknownHostException) {
                    ctx.errorCode(RCODE_NXDOMAIN)
                } catch (e: Exception) {
                    reportFailure(ctx, e)
                }
            }
        }
    }

}
