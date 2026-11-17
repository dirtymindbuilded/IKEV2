package com.example.ikev2manager.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnManager
import android.net.VpnProfileState
import android.net.Ikev2VpnProfile
import android.os.SystemClock
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.example.ikev2manager.data.Credentials
import java.net.URL

class NativeIkev2Engine(context: Context) {
    private val appContext = context.applicationContext
    private val vpnManager = appContext.getSystemService(VpnManager::class.java)
    private val packageManager = appContext.packageManager
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    data class Attempt(
        val connected: Boolean,
        val connectTimeMs: Long? = null,
        val tunnelLatencyMs: Long? = null,
        val internetOk: Boolean? = null,
        val detail: String? = null
    )

    fun isSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS)

    suspend fun test(
        host: String,
        credentials: Credentials,
        requestConsent: suspend (Intent) -> Boolean
    ): Attempt {
        val result = connect(host, credentials, requestConsent)
        if (!result.connected) {
            stop()
            delay(700)
            return result
        }

        return try {
            val latency = measureTunnelLatency()
            result.copy(
                tunnelLatencyMs = latency,
                internetOk = latency != null,
                detail = if (latency != null) "IKEv2 connected; HTTPS check passed" else
                    "IKEv2 connected; tunnel internet check did not pass"
            )
        } finally {
            stop()
            delay(700)
        }
    }

    suspend fun connect(
        host: String,
        credentials: Credentials,
        requestConsent: suspend (Intent) -> Boolean
    ): Attempt {
        if (!isSupported()) {
            return Attempt(false, detail = "This device does not advertise Android IPsec tunnel support")
        }
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return Attempt(false, detail = "Enter the VPN username and password first")
        }

        val startedAt = SystemClock.elapsedRealtime()
        return try {
            // This app owns one platform VPN profile. Each candidate replaces the previous one.
            runCatching { vpnManager.stopProvisionedVpnProfile() }

            val profile = Ikev2VpnProfile.Builder(host, host)
                .setAuthUsernamePassword(credentials.username, credentials.password, null)
                .setRequiresInternetValidation(true)
                .build()

            val consentIntent = vpnManager.provisionVpnProfile(profile)
            if (consentIntent != null && !requestConsent(consentIntent)) {
                stop()
                return Attempt(false, detail = "VPN permission was not granted")
            }

            vpnManager.startProvisionedVpnProfileSession()

            val deadline = SystemClock.elapsedRealtime() + 25_000L
            var sawConnecting = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val state = vpnManager.provisionedVpnProfileState?.state
                when (state) {
                    VpnProfileState.STATE_CONNECTED -> {
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        return Attempt(true, connectTimeMs = elapsed)
                    }
                    VpnProfileState.STATE_CONNECTING -> sawConnecting = true
                    VpnProfileState.STATE_FAILED -> {
                        stop()
                        return Attempt(false, connectTimeMs = SystemClock.elapsedRealtime() - startedAt,
                            detail = "IKEv2 negotiation failed (check EAP method, credentials and server identity)")
                    }
                    VpnProfileState.STATE_DISCONNECTED -> {
                        if (sawConnecting && SystemClock.elapsedRealtime() - startedAt > 5_000L) {
                            stop()
                            return Attempt(false, connectTimeMs = SystemClock.elapsedRealtime() - startedAt,
                                detail = "VPN disconnected before reaching CONNECTED")
                        }
                    }
                }
                delay(400)
            }
            stop()
            Attempt(false, connectTimeMs = SystemClock.elapsedRealtime() - startedAt,
                detail = "Connection timed out after 25 seconds")
        } catch (e: CancellationException) {
            stop()
            throw e
        } catch (e: SecurityException) {
            stop()
            Attempt(false, detail = "Android blocked VPN setup: ${e.message ?: "permission or policy error"}")
        } catch (e: Exception) {
            stop()
            Attempt(false, detail = e.message ?: e.javaClass.simpleName)
        }
    }

    fun stop() {
        runCatching { vpnManager.stopProvisionedVpnProfile() }
    }

    fun hasActiveVpnTransport(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    suspend fun measureActiveTunnelLatency(): Long? = measureTunnelLatency()

    private suspend fun measureTunnelLatency(): Long? = withContext(Dispatchers.IO) {
        val active = connectivityManager.activeNetwork
        val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return@withContext null
        }

        val endpoints = listOf(
            "https://www.gstatic.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        for (endpoint in endpoints) {
            var connection: HttpsURLConnection? = null
            try {
                val start = SystemClock.elapsedRealtime()
                connection = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 4_500
                    readTimeout = 4_500
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val code = connection.responseCode
                val elapsed = SystemClock.elapsedRealtime() - start
                if (code in 200..399) return@withContext elapsed
            } catch (_: Exception) {
                // Try the next independent HTTPS endpoint.
            } finally {
                connection?.disconnect()
            }
        }
        null
    }
}
