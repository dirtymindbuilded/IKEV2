package com.example.ikev2manager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ikev2manager.data.Credentials
import com.example.ikev2manager.data.ServerResult
import com.example.ikev2manager.data.ServerStore
import com.example.ikev2manager.scan.FastScanner
import com.example.ikev2manager.scan.parseServerList
import com.example.ikev2manager.security.CredentialVault
import com.example.ikev2manager.vpn.NativeIkev2Engine
import com.example.ikev2manager.vpn.StrongSwanFallback
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Ikev2ManagerApp() }
    }
}

@Composable
private fun Ikev2ManagerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ServerStore(context) }
    val vault = remember { CredentialVault(context) }
    val scanner = remember { FastScanner() }
    val engine = remember { NativeIkev2Engine(context) }

    var serverText by rememberSaveable { mutableStateOf(store.load()) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var credentialsSaved by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("برای شروع، سرورها و اطلاعات اشتراک را وارد کن.") }
    var scanning by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var scanCompleted by remember { mutableIntStateOf(0) }
    var scanTotal by remember { mutableIntStateOf(0) }
    var activeHost by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<ServerResult>() }
    val pendingConsent = remember { mutableStateOf<CancellableContinuation<Boolean>?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val continuation = pendingConsent.value
        pendingConsent.value = null
        if (continuation?.isActive == true) {
            continuation.resume(result.resultCode == Activity.RESULT_OK)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.onSuccess { imported ->
                if (!imported.isNullOrBlank()) {
                    serverText = (serverText.trimEnd() + "\n" + imported).trim()
                    store.save(serverText)
                    statusMessage = "فهرست سرورها وارد شد."
                }
            }.onFailure {
                statusMessage = "خواندن فایل ناموفق بود: ${it.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        vault.load()?.let {
            username = it.username
            password = it.password
            credentialsSaved = true
        }
    }

    val hosts = remember(serverText) { parseServerList(serverText) }
    val canUseCredentials = username.isNotBlank() && password.isNotBlank()
    val busy = scanning || testing || connecting

    suspend fun requestVpnConsent(intent: Intent): Boolean = suspendCancellableCoroutine { continuation ->
        pendingConsent.value = continuation
        continuation.invokeOnCancellation { pendingConsent.value = null }
        consentLauncher.launch(intent)
    }

    fun updateResult(host: String, transform: (ServerResult) -> ServerResult) {
        val index = results.indexOfFirst { it.host == host }
        if (index >= 0) results[index] = transform(results[index])
    }

    fun persistCredentials(): Credentials? {
        if (!canUseCredentials) return null
        val credentials = Credentials(username.trim(), password)
        return runCatching {
            vault.save(credentials)
            credentialsSaved = true
            credentials
        }.getOrElse {
            statusMessage = "ذخیره امن اطلاعات ناموفق بود: ${it.message}"
            null
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF315E52),
        onPrimary = Color.White,
        secondary = Color(0xFF64756E),
        background = Color(0xFFF6F7F5),
        surface = Color.White,
        surfaceVariant = Color(0xFFECEFEC)
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(modifier = Modifier.padding(top = 22.dp, bottom = 4.dp)) {
                        Text("IKEv2 Manager", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("مدیریت، غربالگری و تست سرورهای VPN", color = MaterialTheme.colorScheme.secondary)
                    }
                }

                item {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("۱. اطلاعات اشتراک", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it; credentialsSaved = false },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Username") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it; credentialsSaved = false },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { persistCredentials() }, enabled = canUseCredentials && !busy) {
                                    Text("ذخیره امن")
                                }
                                if (credentialsSaved) Text("ذخیره‌شده روی دستگاه", color = Color(0xFF28734D), fontSize = 12.sp)
                            }
                            Text("رمز با Android Keystore رمزگذاری می‌شود و به سرور خارجی ارسال نمی‌شود.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }

                item {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("۲. فهرست سرورها", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            OutlinedTextField(
                                value = serverText,
                                onValueChange = { serverText = it; store.save(it) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4,
                                maxLines = 7,
                                label = { Text("هر دامنه در یک خط") },
                                placeholder = { Text("de1.example.com\nnl1.example.com\nfr1.example.com") }
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/plain", "text/csv", "application/octet-stream")) }, enabled = !busy) {
                                    Text("Import TXT / CSV")
                                }
                                Text("${hosts.size} سرور معتبر", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                            }
                            Text("فقط دامنه یا IP وارد کن؛ یوزرنیم و پسورد داخل فایل سرورها قرار نمی‌گیرد.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }

                item {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("۳. غربالگری سریع", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text("ابتدا DNS و ICMP Ping بررسی می‌شوند. بی‌پاسخ‌بودن Ping به معنی خراب‌بودن IKEv2 نیست.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            Button(
                                onClick = {
                                    store.save(serverText)
                                    scope.launch {
                                        if (engine.hasActiveVpnTransport()) {
                                            statusMessage = "برای اسکن مسیر مستقیم، ابتدا VPN فعلی را قطع کن."
                                            return@launch
                                        }
                                        scanning = true
                                        scanCompleted = 0
                                        scanTotal = hosts.size
                                        results.clear()
                                        statusMessage = "در حال بررسی DNS و Ping سرورها..."
                                        try {
                                            val semaphore = Semaphore(6)
                                            coroutineScope {
                                                val jobs = hosts.map { host ->
                                                    async(Dispatchers.IO) { semaphore.withPermit { scanner.scan(host) } }
                                                }
                                                jobs.forEach { job ->
                                                    val result = job.await()
                                                    results.add(result)
                                                    scanCompleted++
                                                }
                                            }
                                            val sorted = results.sortedWith(
                                                compareBy<ServerResult> { !it.dnsOk }
                                                    .thenBy { it.pingAverageMs == null }
                                                    .thenBy { it.pingAverageMs ?: Double.MAX_VALUE }
                                            )
                                            results.clear(); results.addAll(sorted)
                                            statusMessage = "غربالگری تمام شد: ${results.count { it.dnsOk }} دامنه resolve شد."
                                        } catch (e: Exception) {
                                            statusMessage = "اسکن متوقف شد: ${e.message}"
                                        } finally {
                                            scanning = false
                                        }
                                    }
                                },
                                enabled = !busy && activeHost == null && hosts.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (scanning) "در حال اسکن..." else "شروع Fast Scan (${hosts.size})") }
                            if (scanning) {
                                LinearProgressIndicator(
                                    progress = if (scanTotal == 0) 0f else scanCompleted.toFloat() / scanTotal,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("$scanCompleted از $scanTotal", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }

                item {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("۴. تست واقعی IKEv2", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text("برای حداکثر ۵ سرور برتر، تونل واقعی برقرار می‌شود؛ سپس HTTPS از داخل تونل بررسی و اتصال قطع می‌شود. پیش از شروع، VPNهای دیگر را خاموش کن.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            Button(
                                onClick = {
                                    val credentials = persistCredentials()
                                    if (credentials == null) {
                                        statusMessage = "ابتدا Username و Password را وارد کن."
                                    } else {
                                        val candidates = results.filter { it.dnsOk }
                                            .sortedWith(compareBy<ServerResult> { it.pingAverageMs == null }.thenBy { it.pingAverageMs ?: Double.MAX_VALUE })
                                            .take(5)
                                        if (candidates.isEmpty()) {
                                            statusMessage = "ابتدا Fast Scan را اجرا کن."
                                        } else {
                                            scope.launch {
                                                engine.stop()
                                                delay(500)
                                                if (engine.hasActiveVpnTransport()) {
                                                    statusMessage = "ابتدا VPN دیگری را از تنظیمات گوشی قطع کن و دوباره تست بگیر."
                                                    return@launch
                                                }
                                                testing = true
                                                try {
                                                    candidates.forEachIndexed { index, server ->
                                                        updateResult(server.host) { it.copy(ikeState = "Testing", detail = null) }
                                                        statusMessage = "تست IKEv2: ${server.host} (${index + 1}/${candidates.size})"
                                                        val attempt = engine.test(server.host, credentials, ::requestVpnConsent)
                                                        updateResult(server.host) {
                                                            it.copy(
                                                                ikeState = if (attempt.connected) "Connected" else "Failed",
                                                                connectTimeMs = attempt.connectTimeMs,
                                                                tunnelLatencyMs = attempt.tunnelLatencyMs,
                                                                internetOk = attempt.internetOk,
                                                                detail = attempt.detail
                                                            )
                                                        }
                                                    }
                                                    statusMessage = "تست واقعی ${candidates.size} سرور تمام شد."
                                                } finally {
                                                    testing = false
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = !busy && results.any { it.dnsOk } && canUseCredentials,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (testing) "در حال تست تونل‌ها..." else "تست واقعی ۵ سرور برتر") }
                            if (testing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                                Text("تست‌ها پشت‌سرهم انجام می‌شوند؛ هر سرور ممکن است حدود ۳۵ ثانیه زمان ببرد.", fontSize = 12.sp)
                            }
                        }
                    }
                }

                if (activeHost != null) {
                    item {
                        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3ED))) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("اتصال فعال", fontWeight = FontWeight.SemiBold, color = Color(0xFF205B3B))
                                    Text(activeHost ?: "", fontSize = 13.sp)
                                }
                                OutlinedButton(onClick = {
                                    engine.stop()
                                    activeHost = null
                                    statusMessage = "VPN قطع شد."
                                }, enabled = !busy) { Text("قطع اتصال") }
                            }
                        }
                    }
                }

                if (results.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("نتایج سرورها", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("${results.size} مورد", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                        }
                    }
                    items(results, key = { it.host }) { result ->
                        ServerCard(
                            result = result,
                            busy = busy,
                            onConnect = {
                                val credentials = persistCredentials()
                                if (credentials != null) {
                                    scope.launch {
                                        connecting = true
                                        statusMessage = "در حال اتصال به ${result.host}..."
                                        activeHost = null
                                        engine.stop()
                                        delay(500)
                                        if (engine.hasActiveVpnTransport()) {
                                            connecting = false
                                            statusMessage = "ابتدا VPN دیگری را از تنظیمات گوشی قطع کن."
                                            return@launch
                                        }
                                        val attempt = engine.connect(result.host, credentials, ::requestVpnConsent)
                                        connecting = false
                                        if (attempt.connected) {
                                            activeHost = result.host
                                            val tunnelLatency = engine.measureActiveTunnelLatency()
                                            updateResult(result.host) { it.copy(ikeState = "Connected", connectTimeMs = attempt.connectTimeMs, tunnelLatencyMs = tunnelLatency, internetOk = tunnelLatency != null, detail = if (tunnelLatency != null) "Tunnel is active; HTTPS check passed" else "Tunnel is active; HTTPS check failed") }
                                            statusMessage = "اتصال برقرار شد: ${result.host}"
                                        } else {
                                            updateResult(result.host) { it.copy(ikeState = "Failed", connectTimeMs = attempt.connectTimeMs, detail = attempt.detail) }
                                            statusMessage = "اتصال ناموفق بود: ${attempt.detail}"
                                        }
                                    }
                                }
                            },
                            onFallback = {
                                val credentials = persistCredentials()
                                if (credentials != null) {
                                    runCatching { StrongSwanFallback.exportAndOpen(context, result.host, credentials) }
                                        .onSuccess { statusMessage = "پروفایل strongSwan ساخته شد؛ پس از Import فایل Downloads را پاک کن." }
                                        .onFailure { statusMessage = "ساخت/بازکردن پروفایل strongSwan ناموفق بود: ${it.message}. فایل احتمالا در Downloads باقی مانده است." }
                                }
                            }
                        )
                    }
                }

                item {
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("وضعیت", fontWeight = FontWeight.SemiBold)
                            Text(statusMessage, fontSize = 13.sp)
                            Text("نسخه 0.1.0-alpha · موتور Native Android IKEv2", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    result: ServerResult,
    busy: Boolean,
    onConnect: () -> Unit,
    onFallback: () -> Unit
) {
    val stateColor = when (result.ikeState) {
        "Connected" -> Color(0xFF23764A)
        "Failed" -> Color(0xFFB5473C)
        "Testing" -> Color(0xFF9A6A19)
        else -> MaterialTheme.colorScheme.secondary
    }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(result.host, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(result.ikeState, color = stateColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Text("IP: ${result.ip ?: if (result.dnsOk) "—" else "DNS failed"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Ping: ${result.pingAverageMs?.let { "${it.toInt()} ms" } ?: "—"}", fontSize = 12.sp)
                Text("Loss: ${result.packetLossPercent?.let { "$it%" } ?: "—"}", fontSize = 12.sp)
                result.connectTimeMs?.let { Text("IKE: ${it} ms", fontSize = 12.sp) }
                result.tunnelLatencyMs?.let { Text("Tunnel: ${it} ms", fontSize = 12.sp) }
            }
            Text(result.pingNote, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
            result.detail?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (result.ikeState == "Failed") {
                    OutlinedButton(onClick = onFallback, enabled = !busy) { Text("Fallback: strongSwan") }
                }
                OutlinedButton(onClick = onConnect, enabled = !busy && result.dnsOk) { Text("اتصال") }
            }
        }
    }
}
