package com.example.ikev2manager.scan

import com.example.ikev2manager.data.ServerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

class FastScanner {
    suspend fun scan(host: String): ServerResult = withContext(Dispatchers.IO) {
        val address = try {
            InetAddress.getAllByName(host)
                .firstOrNull { it is Inet4Address }
                ?: InetAddress.getAllByName(host).firstOrNull()
        } catch (e: Exception) {
            return@withContext ServerResult(
                host = host,
                dnsOk = false,
                pingNote = "DNS lookup failed",
                detail = e.message ?: "Could not resolve hostname"
            )
        }

        if (address == null) {
            return@withContext ServerResult(host, dnsOk = false, pingNote = "DNS lookup failed")
        }

        val ping = runPing(address.hostAddress ?: host)
        ServerResult(
            host = host,
            ip = address.hostAddress,
            dnsOk = true,
            pingAverageMs = ping.averageMs,
            packetLossPercent = ping.lossPercent,
            pingNote = ping.note
        )
    }

    private data class PingResult(
        val averageMs: Double?,
        val lossPercent: Int?,
        val note: String
    )

    private fun runPing(ip: String): PingResult {
        return try {
            val process = ProcessBuilder(
                "/system/bin/ping", "-c", "3", "-W", "1", ip
            ).redirectErrorStream(true).apply {
                environment()["LC_ALL"] = "C"
            }.start()

            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return PingResult(null, null, "Ping timed out")
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val times = Regex("time[=<]([0-9]+(?:\\.[0-9]+)?)\\s*ms", RegexOption.IGNORE_CASE)
                .findAll(output)
                .mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }
                .toList()
            val loss = Regex("([0-9]+(?:\\.[0-9]+)?)%\\s*packet loss", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()

            if (times.isEmpty()) {
                PingResult(null, loss ?: 100, "No ICMP reply")
            } else {
                PingResult(times.average(), loss ?: ((3 - times.size).coerceAtLeast(0) * 100 / 3), "ICMP reply")
            }
        } catch (_: Exception) {
            // ICMP may be unavailable on a particular Android build. Do not treat this as server failure.
            PingResult(null, null, "ICMP unavailable")
        }
    }
}

fun parseServerList(raw: String): List<String> {
    val unique = LinkedHashSet<String>()
    raw.lineSequence().forEach { line ->
        val value = line.substringBefore(",").substringBefore(";").trim()
        if (value.isNotBlank() && !value.startsWith("#")) {
            val cleaned = value.removePrefix("https://").removePrefix("http://").trimEnd('/')
                .substringBefore(":")
            if (cleaned.isNotBlank() && cleaned.length <= 253 &&
                cleaned.matches(Regex("[A-Za-z0-9.-]+")) &&
                !cleaned.startsWith(".") && !cleaned.endsWith(".")) {
                unique.add(cleaned.lowercase(Locale.ROOT))
            }
        }
    }
    return unique.toList()
}
