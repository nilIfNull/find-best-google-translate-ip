package com.github.nilifnull.translateip

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val logger = LoggerFactory.getLogger("GoogleTranslateIpCheck")

fun main(args: Array<String>) = runBlocking {
    if (args.any { it == "-h" || it == "--help" }) {
        printUsage()
        return@runBlocking
    }

    val verbose = args.any { it == "-v" || it == "--verbose" }
    val config = AppConfig.load().let {
        // verbose 模式才允许输出 HTTP 细节日志，默认仅显示进度条
        it.copy(enableHttpLogging = verbose && it.enableHttpLogging)
    }

    val ipv6 = args.any { it == "-6" || it == "--ipv6" }
    val forceScan = args.any { it == "-s" || it == "--scan" }
    val ipFile = Path.of(if (ipv6) "IPv6.txt" else "ip.txt")

    logInfo(verbose, "如果支持 IPv6，可使用 -6 启动 IPv6 模式。")
    logInfo(verbose, "当前模式: {}", if (ipv6) "IPv6" else "IPv4")

    val initialIps = if (forceScan) {
        emptySet()
    } else {
        readIps(ipFile, ipv6, verbose).ifEmpty {
            readRemoteIps(config, ipv6, verbose)
        }
    }

    val ips = initialIps.ifEmpty {
        scanIps(config, ipv6, verbose)
    }

    if (ips.isEmpty()) {
        logWarn(verbose, "未找到待检测 IP。")
        return@runBlocking
    }

    logInfo(verbose, "开始检测 {} 个 IP 的响应时间", ips.size)

    TranslateIpChecker(config).use { checker ->
        // 非 verbose 下使用单行进度条，避免大量日志滚屏
        val progress = ProgressBar(total = ips.size.toLong(), enabled = !verbose)
        val results = progress.use {
            checker.checkAll(ips, ipv6) { ip, result ->
                progress.checked(ip, result != null)
            }
        }
        if (results.isEmpty()) {
            logWarn(verbose, "未找到可用 IP，可使用 -s 直接进入扫描模式。")
            return@runBlocking
        }

        val best = results.first()
        val sortedText = results.joinToString(System.lineSeparator()) { "${it.ip}: 响应时间 ${it.millis} ms" }
        val hostsText = config.hosts.joinToString(System.lineSeparator()) { host -> "${best.ip} $host" }
        if (verbose) {
            logger.info("检测完毕，按响应时间排序:\n{}", sortedText)
            logger.info("最佳 IP: {} 响应时间 {} ms", best.ip, best.millis)
            logger.info("最佳 Hosts 配置:\n{}", hostsText)
        } else {
            System.out.println("检测完毕，按响应时间排序:")
            System.out.println(sortedText)
            System.out.println("最佳 IP: ${best.ip} 响应时间 ${best.millis} ms")
            System.out.println("最佳 Hosts 配置:")
            System.out.println(hostsText)
        }
    }
}

private fun printUsage() {
    logger.info(
        """
        Usage: ./gradlew run --args='[-s] [-6]'

          -s, --scan   跳过本地/远程 IP 文件，直接扫描配置中的 CIDR 段
          -6, --ipv6   使用 IPv6 模式，读取 IPv6.txt 并扫描 ipv6Cidrs
          -v, --verbose 使用日志输出，不显示进度条
          -h, --help   显示帮助
        """.trimIndent(),
    )
}

private fun readIps(file: Path, ipv6: Boolean, verbose: Boolean): Set<String> {
    if (!Files.exists(file)) {
        logInfo(verbose, "未找到本地 IP 文件: {}", file)
        return emptySet()
    }

    val ips = Files.readAllLines(file)
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotBlank() && it.isValidIp(ipv6) }
        .toCollection(linkedSetOf())

    logInfo(verbose, "从 {} 找到 {} 条 IP", file, ips.size)
    return ips
}

private suspend fun readRemoteIps(config: AppConfig, ipv6: Boolean, verbose: Boolean): Set<String> {
    val url = if (ipv6) config.remoteIpv6Url else config.remoteIpUrl
    logInfo(verbose, "尝试从远程获取 IP: {}", url)

    val client = HttpClient(OkHttp) {
        installClientLogging(config.enableHttpLogging)
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }

    return client.use {
        runCatching {
            it.get(url).bodyAsText()
                .lineSequence()
                .flatMap { line -> line.split(",").asSequence() }
                .map { value -> value.trim() }
                .filter { value -> value.isNotBlank() && value.isValidIp(ipv6) }
                .toCollection(linkedSetOf())
                .also { ips -> logInfo(verbose, "远程获取到 {} 条 IP", ips.size) }
        }.getOrElse {
            logWarn(verbose, "远程获取 IP 失败: {}", it.message)
            emptySet()
        }
    }
}

private suspend fun scanIps(config: AppConfig, ipv6: Boolean, verbose: Boolean): Set<String> {
    val cidrs = (if (ipv6) config.ipv6Cidrs else config.ipv4Cidrs).map { Cidr.parse(it) }
    logInfo(verbose, "进入扫描模式，目标数量: {}", config.scanLimit)

    val results = ConcurrentHashMap.newKeySet<String>()
    val stop = AtomicBoolean(false)
    val channel = Channel<String>(config.concurrency * 2)

    TranslateIpChecker(config).use { checker ->
        val progress = ProgressBar(total = cidrs.sumOf { it.size }, enabled = !verbose)
        coroutineScope {
            // 生产者负责展开 CIDR，消费者并发检测并在达到 scanLimit 后提前停止
            val producer = launch {
                try {
                    for (cidr in cidrs) {
                        for (ip in cidr) {
                            if (stop.get()) return@launch
                            try {
                                channel.send(ip)
                            } catch (_: Exception) {
                                return@launch
                            }
                        }
                    }
                } finally {
                    channel.close()
                }
            }

            val workers = List(config.concurrency) {
                launch {
                    for (ip in channel) {
                        if (stop.get()) continue
                        progress.current(ip)
                        val result = checker.check(ip, ipv6)
                        if (result != null && results.add(result.ip)) {
                            progress.checked(ip, true)
                            logInfo(verbose, "找到可用 IP: {} ({} ms)", result.ip, result.millis)
                            if (results.size >= config.scanLimit) {
                                stop.set(true)
                                channel.close()
                            }
                        } else {
                            progress.checked(ip, false)
                        }
                    }
                }
            }

            producer.join()
            workers.joinAll()
        }
        progress.close()
    }

    logInfo(verbose, "扫描找到 {} 条 IP", results.size)
    return results.toSet()
}

private fun logInfo(verbose: Boolean, message: String, vararg arguments: Any?) {
    if (verbose) {
        logger.info(message, *arguments)
    }
}

private fun logWarn(verbose: Boolean, message: String, vararg arguments: Any?) {
    if (verbose) {
        logger.warn(message, *arguments)
    }
}

private fun output(verbose: Boolean, message: String) {
    if (verbose) {
        logger.info(message)
    } else {
        System.out.println(message)
    }
}
