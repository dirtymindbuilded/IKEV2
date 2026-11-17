package com.example.ikev2manager.data

data class Credentials(
    val username: String,
    val password: String
)

data class ServerResult(
    val host: String,
    val ip: String? = null,
    val dnsOk: Boolean = false,
    val pingAverageMs: Double? = null,
    val packetLossPercent: Int? = null,
    val pingNote: String = "Not scanned",
    val ikeState: String = "Not tested",
    val connectTimeMs: Long? = null,
    val tunnelLatencyMs: Long? = null,
    val internetOk: Boolean? = null,
    val detail: String? = null
)

data class FastScanSummary(
    val completed: Int,
    val total: Int
)
