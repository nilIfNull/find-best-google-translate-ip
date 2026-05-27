package com.github.nilifnull.translateip

class ProgressBar(
    private val total: Long,
    private val enabled: Boolean,
) : AutoCloseable {
    private val width = 28
    private var success = 0L
    private var failure = 0L
    private var closed = false
    private var lastLength = 0

    @Synchronized
    fun checked(ip: String, succeeded: Boolean) {
        // 多协程并发更新进度，必须串行刷新终端输出，避免单行被打乱
        if (!enabled || closed) return
        if (succeeded) {
            success += 1
        } else {
            failure += 1
        }
        render(ip)
    }

    @Synchronized
    fun current(ip: String) {
        if (!enabled || closed) return
        render(ip)
    }

    @Synchronized
    override fun close() {
        if (enabled && !closed) {
            closed = true
            System.err.println()
        }
    }

    private fun render(ip: String) {
        val checked = success + failure
        val percent = if (total <= 0) 0 else ((checked.toDouble() / total) * 100).toInt().coerceIn(0, 100)
        val done = ((percent / 100.0) * width).toInt().coerceIn(0, width)
        val bar = "█".repeat(done) + "-".repeat(width - done)
        val text = "[$bar] $percent% 成功:$success 失败:$failure 总:$total 当前: $ip"
        val padding = if (lastLength > text.length) " ".repeat(lastLength - text.length) else ""
        System.err.print("\r$text$padding")
        lastLength = text.length
    }
}
