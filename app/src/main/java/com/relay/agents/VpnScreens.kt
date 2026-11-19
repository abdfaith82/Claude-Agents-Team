package com.relay.agents

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader
import java.net.URL

/** نواة VPN: WireGuard الرسمية (GoBackend). لا تتضمن خوادم؛ تستورد أنت ملف إعداد من مزوّد تثق به. */
object VpnHub {
    var state by mutableStateOf(Tunnel.State.DOWN)
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var exitIp by mutableStateOf("")

    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName(): String = "claude_agents"
        override fun onStateChange(newState: Tunnel.State) { state = newState }
    }

    private fun backend(ctx: Context): GoBackend = backend ?: GoBackend(ctx.applicationContext).also { backend = it }

    /** يضبط نطاق النفق داخل ملف الإعداد: هذا التطبيق فقط (IncludedApplications) أو كل الجهاز. */
    internal fun withScope(text: String, appOnly: Boolean, pkg: String): String {
        val out = mutableListOf<String>()
        var inserted = false
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("IncludedApplications", true) || t.startsWith("ExcludedApplications", true)) continue
            out.add(line)
            if (appOnly && !inserted && t.equals("[Interface]", true)) { out.add("IncludedApplications = $pkg"); inserted = true }
        }
        return out.joinToString("\n")
    }

    suspend fun connect(ctx: Context, configText: String, appOnly: Boolean) {
        busy = true; error = ""
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = Config.parse(BufferedReader(StringReader(withScope(configText, appOnly, ctx.packageName))))
                backend(ctx).setState(tunnel, Tunnel.State.UP, cfg)
            }.onFailure { error = (it.message ?: it.javaClass.simpleName).take(200) }
        }
        busy = false
    }

    suspend fun disconnect(ctx: Context) {
        busy = true
        withContext(Dispatchers.IO) {
            runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
                .onFailure { error = (it.message ?: it.javaClass.simpleName).take(200) }
        }
        exitIp = ""
        busy = false
    }

    /** عنوان IP الذي يراه العالم لهذا التطبيق (يستعلم من api.ipify.org عند الضغط فقط). */
    suspend fun checkIp(): String = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.ipify.org").openConnection()
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.getInputStream().bufferedReader().use { it.readText().trim() }
        }.getOrElse { "—" }
    }
}

@Composable
fun VpnTab(vm: RelayViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { Storage(ctx) }
    var config by remember { mutableStateOf(runCatching { SecretStore.decrypt(storage.setting("vpn_config", "")) }.getOrDefault("")) }
    var appOnly by remember { mutableStateOf(storage.setting("vpn_app_only", "1") == "1") }
    var editing by remember { mutableStateOf(config.isEmpty()) }
    var draft by remember { mutableStateOf("") }
    val up = VpnHub.state == Tunnel.State.UP

    fun saveConfig(text: String) {
        config = text
        storage.saveSetting("vpn_config", if (text.isEmpty()) "" else SecretStore.encrypt(text))
    }

    fun doConnect() { scope.launch { VpnHub.connect(ctx, config, appOnly) } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) doConnect() else VpnHub.error = tr("لم يُمنح إذن VPN")
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) runCatching {
            draft = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }.take(20000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoftCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (up) Brand.Green else MaterialTheme.colorScheme.outline, 12)
                    Text(if (up) tr("متصل") else tr("غير متصل"), style = MaterialTheme.typography.titleMedium)
                }
                if (config.isEmpty()) {
                    Text(tr("لم يُستورد ملف إعداد بعد."), style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(enabled = !VpnHub.busy, onClick = {
                        if (up) scope.launch { VpnHub.disconnect(ctx) }
                        else {
                            val i = VpnService.prepare(ctx)
                            if (i != null) permission.launch(i) else doConnect()
                        }
                    }) { Text(if (up) tr("قطع الاتصال") else tr("اتصال")) }
                }
                if (VpnHub.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (VpnHub.error.isNotEmpty()) Text(VpnHub.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (up) {
                    OutlinedButton(onClick = { scope.launch { VpnHub.exitIp = VpnHub.checkIp() } }) { Text(tr("فحص عنوان IP الظاهر")) }
                    if (VpnHub.exitIp.isNotEmpty()) SelectionContainer { Text("IP: " + VpnHub.exitIp, fontFamily = FontFamily.Monospace) }
                }
            }
        }

        SoftCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("نطاق النفق"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = appOnly, onClick = { appOnly = true; storage.saveSetting("vpn_app_only", "1") }, label = { Text(tr("هذا التطبيق فقط")) })
                    FilterChip(selected = !appOnly, onClick = { appOnly = false; storage.saveSetting("vpn_app_only", "0") }, label = { Text(tr("كل الجهاز")) })
                }
                Text(
                    if (appOnly) tr("يمرّ عبر النفق طلبات الوكلاء والبوابة والمتصفح داخل التطبيق فقط، وباقي هاتفك لا يتأثر.")
                    else tr("يمرّ كل اتصال الهاتف عبر النفق."),
                    style = MaterialTheme.typography.bodySmall
                )
                if (up) Text(tr("غيّر النطاق ثم اقطع الاتصال وأعد الاتصال لتطبيقه."), style = MaterialTheme.typography.labelSmall)
            }
        }

        SoftCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("ملف إعداد WireGuard"), style = MaterialTheme.typography.titleMedium)
                if (!editing && config.isNotEmpty()) {
                    Text(tr("محفوظ ومشفّر على الجهاز (المفتاح الخاص لا يُعرض)."), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { draft = ""; editing = true }) { Text(tr("استبدال")) }
                        OutlinedButton(enabled = !up, onClick = { saveConfig(""); editing = true }) { Text(tr("حذف")) }
                    }
                } else {
                    OutlinedTextField(draft, { draft = it }, label = { Text(tr("الصق محتوى ملف .conf")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { filePicker.launch("*/*") }) { Text(tr("اختيار ملف")) }
                        Button(enabled = draft.contains("[Interface]") && draft.contains("[Peer]"), onClick = {
                            saveConfig(draft.trim()); draft = ""; editing = false
                        }) { Text(tr("حفظ")) }
                    }
                }
            }
        }

        SoftCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("من أين أحصل على إعداد مجاني؟"), style = MaterialTheme.typography.titleMedium)
                Text(tr("التطبيق يحوي المحرك فقط ولا يوفّر خوادم. خياران شائعان مجانيان، تحقّق من شروطهما بنفسك: ١) Proton VPN المجاني: نزّل ملف WireGuard من حسابك. ٢) Cloudflare WARP: تولّد ملف WireGuard بأداة wgcf مفتوحة المصدر."),
                    style = MaterialTheme.typography.bodySmall)
                Text(tr("احذر من خوادم VPN مجانية مجهولة؛ قد تسجّل وتبيع بياناتك. الـ VPN ينقل ثقتك من مزوّد الإنترنت إلى مزوّد الـ VPN."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
