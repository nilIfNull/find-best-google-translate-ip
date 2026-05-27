package com.github.nilifnull.translateip

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.serializer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@Serializable
data class AppConfigFile(
    // 保持与 application.conf 的顶层节点一致，便于直接序列化/反序列化
    val googleTranslateIpCheck: AppConfig,
)

@Serializable
data class AppConfig(
    val remoteIpUrl: String,
    val remoteIpv6Url: String,
    val scanLimit: Int,
    val timeoutSeconds: Long,
    val concurrency: Int,
    val enableHttpLogging: Boolean = false,
    val hosts: List<String>,
    val ipv4Cidrs: List<String>,
    val ipv6Cidrs: List<String>,
) {
    val timeout: Duration
        get() = Duration.ofSeconds(timeoutSeconds)

    @OptIn(ExperimentalSerializationApi::class)
    fun save(path: Path = defaultPath) {
        val config = Hocon.encodeToConfig(serializer<AppConfigFile>(), AppConfigFile(this))
        val rendered = config.root().render(
            ConfigRenderOptions.defaults()
                .setOriginComments(false)
                .setComments(false)
                .setJson(false),
        )
        Files.writeString(path, rendered + System.lineSeparator(), StandardCharsets.UTF_8)
    }

    companion object {
        private val defaultPath: Path = Path.of("application.conf")

        @OptIn(ExperimentalSerializationApi::class)
        fun load(path: Path = defaultPath): AppConfig {
            // 优先读取当前工作目录配置，缺失时回落到 classpath 默认配置
            val config = if (Files.exists(path)) {
                ConfigFactory.parseFile(path.toFile())
            } else {
                ConfigFactory.load()
            }.resolve()

            return Hocon.decodeFromConfig(
                deserializer = serializer<AppConfigFile>(),
                config = config,
            ).googleTranslateIpCheck
        }
    }
}
