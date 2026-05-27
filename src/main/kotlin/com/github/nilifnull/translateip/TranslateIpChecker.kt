package com.github.nilifnull.translateip

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Dns
import java.net.InetAddress
import java.time.Duration
import java.time.Instant

data class IpCheckResult(
    val ip: String,
    val millis: Long,
)

class TranslateIpChecker(
    private val config: AppConfig,
) : AutoCloseable {
    // 统一请求域名，结合自定义 DNS 将该域名解析到候选 IP，避免证书域名校验失败
    private val targetHost = config.hosts.first()

    suspend fun checkAll(
        ips: Collection<String>,
        ipv6: Boolean,
        onChecked: (String, IpCheckResult?) -> Unit = { _, _ -> },
    ): List<IpCheckResult> = coroutineScope {
        // 使用信号量限制并发，避免创建过多连接导致本地和远端同时过载
        val semaphore = Semaphore(config.concurrency)
        ips.map { ip ->
            async {
                semaphore.withPermit {
                    check(ip, ipv6).also { onChecked(ip, it) }
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.millis }
    }

    suspend fun check(ip: String, @Suppress("UNUSED_PARAMETER") ipv6: Boolean): IpCheckResult? {
        val url = "https://$targetHost/translate_a/single?client=gtx&sl=zh-CN&tl=en&dt=t&q=%E4%BD%A0%E5%A5%BD"

        return clientFor(ip).use { client ->
            runCatching {
                val started = Instant.now()
                val body = client.get(url).bodyAsText()
                if (body.contains("Hello", ignoreCase = true)) {
                    IpCheckResult(ip, Duration.between(started, Instant.now()).toMillis())
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    override fun close() = Unit

    private fun clientFor(ip: String): HttpClient =
        HttpClient(OkHttp) {
            engine {
                config {
                    // 仅替换目标域名解析，其它域名仍走系统 DNS
                    dns(FixedHostDns(targetHost, ip))
                }
            }
            installClientLogging(config.enableHttpLogging)
            install(HttpTimeout) {
                requestTimeoutMillis = config.timeout.toMillis()
                connectTimeoutMillis = config.timeout.toMillis()
                socketTimeoutMillis = config.timeout.toMillis()
            }
        }

    private class FixedHostDns(
        private val host: String,
        private val ip: String,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!hostname.equals(host, ignoreCase = true)) {
                return Dns.SYSTEM.lookup(hostname)
            }
            return listOf(InetAddress.getByName(ip))
        }
    }
}
