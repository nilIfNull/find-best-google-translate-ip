package com.github.nilifnull.translateip

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import org.slf4j.LoggerFactory

private val ktorClientLogger = LoggerFactory.getLogger("KtorClient")

fun HttpClientConfig<*>.installClientLogging(enabled: Boolean) {
    // 仅在显式开启时安装，避免默认模式下刷屏影响进度条可读性
    if (enabled) {
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    ktorClientLogger.info(message)
                }
            }
            level = LogLevel.INFO
        }
    }
}
