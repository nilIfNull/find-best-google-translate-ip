package com.github.nilifnull.translateip

import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class Cidr private constructor(
    private val first: BigInteger,
    private val last: BigInteger,
    private val bits: Int,
    private val ipv6: Boolean,
) : Iterable<String> {
    val size: Long
        get() {
            // IPv4 跳过网络地址/广播地址，进度统计也按可用地址数计算
            val start = if (!ipv6 && last > first) first + BigInteger.ONE else first
            val end = if (!ipv6 && last > first) last - BigInteger.ONE else last
            return end.subtract(start).add(BigInteger.ONE).coerceAtLeast(BigInteger.ZERO).toLong()
        }

    override fun iterator(): Iterator<String> {
        val start = if (!ipv6 && last > first) first + BigInteger.ONE else first
        val end = if (!ipv6 && last > first) last - BigInteger.ONE else last

        return object : Iterator<String> {
            private var cursor = start

            override fun hasNext(): Boolean = cursor <= end

            override fun next(): String {
                val value = cursor
                cursor += BigInteger.ONE
                return value.toInetAddress(bits).hostAddress
            }
        }
    }

    companion object {
        fun parse(value: String): Cidr {
            val parts = value.trim().split("/")
            require(parts.size == 2) { "Invalid CIDR: $value" }

            val address = InetAddress.getByName(parts[0])
            val bits = when (address) {
                is Inet4Address -> 32
                is Inet6Address -> 128
                else -> error("Unsupported address type: $value")
            }
            val prefix = parts[1].toInt()
            require(prefix in 0..bits) { "Invalid prefix length: $value" }

            val raw = BigInteger(1, address.address)
            val hostBits = bits - prefix
            val mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                .xor(BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE))
            val network = raw.and(mask)
            val broadcast = network + BigInteger.ONE.shiftLeft(hostBits) - BigInteger.ONE

            return Cidr(network, broadcast, bits, address is Inet6Address)
        }
    }
}

fun String.isValidIp(ipv6: Boolean): Boolean =
    runCatching {
        val address = InetAddress.getByName(trim())
        if (ipv6) address is Inet6Address else address is Inet4Address
    }.getOrDefault(false)

private fun BigInteger.toInetAddress(bits: Int): InetAddress {
    val bytes = toByteArray()
    val targetSize = bits / 8
    val normalized = ByteArray(targetSize)
    val copyStart = maxOf(0, bytes.size - targetSize)
    val copyLength = bytes.size - copyStart
    System.arraycopy(bytes, copyStart, normalized, targetSize - copyLength, copyLength)
    return InetAddress.getByAddress(normalized)
}
