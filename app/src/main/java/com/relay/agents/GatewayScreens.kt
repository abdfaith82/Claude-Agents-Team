package com.relay.agents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun localIp(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }?.hostAddress
}.getOrNull()

/** البوابة المحلية: نقطة نهاية OpenAI/Anthropic واحدة بمفتاح موحّد فوق مجمّع وكلائك. */
@Composable
fun GatewayTab(vm: RelayViewModel) {
    val gw = vm.gateway
    val clipboard = LocalClipboardManager.current
    var portText by remember { mutableStateOf(vm.gatewayPort.toString()) }
    var showKey by remember { mutableStateOf(false) }
    val host = if (vm.gatewayLan) (localIp() ?: "<device-ip>") else "127.0.0.1"
    val base = "http://$host:${vm.gatewayPort}"
    val keyShown = if (showKey) vm.gatewayKey else "cat-" + "•".repeat(12)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(tr("البوابة المحلية")) {
            Text(
                if (gw.running) trf("● تعمل على %s — %s طلب", base, gw.requests) else tr("متوقفة"),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(tr("تعرض وكلاءك كنقطة نهاية واحدة متوافقة مع OpenAI وAnthropic بالتبديل التلقائي، فتستخدمها تطبيقات أخرى (Termux، Claude Code، عملاء OpenAI) دون مفاتيح المزوّدين."),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(portText, { portText = it.filter { c -> c.isDigit() }.take(5) }, label = { Text(tr("المنفذ")) },
                    singleLine = true, enabled = !gw.running, modifier = Modifier.weight(1f))
                Switch(vm.gatewayLan, { vm.configureGateway(portText.toIntOrNull() ?: 3001, it) }, enabled = !gw.running)
                Text(tr("الشبكة المحلية"), style = MaterialTheme.typography.bodySmall)
            }
            if (vm.gatewayLan) Text(tr("⚠️ أي جهاز على شبكتك يعرف المفتاح يستطيع استخدام وكلائك."), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            if (vm.gatewayError.isNotEmpty()) Text("❌ " + vm.gatewayError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                if (gw.running) vm.stopGateway() else {
                    vm.configureGateway(portText.toIntOrNull() ?: 3001, vm.gatewayLan)
                    vm.startGateway()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (gw.running) tr("إيقاف البوابة") else tr("تشغيل البوابة")) }
        }

        Section(tr("المفتاح الموحّد")) {
            SelectionContainer { Text(keyShown, fontFamily = FontFamily.Monospace) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showKey = !showKey }) { Text(if (showKey) tr("إخفاء") else tr("إظهار")) }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.gatewayKey)) }) { Text(tr("نسخ")) }
                OutlinedButton(onClick = { vm.regenerateGatewayKey() }) { Text(tr("تجديد")) }
            }
            Text(tr("هذا المفتاح يخص بوابتك فقط ويُحفظ مشفّراً. تجديده يُبطل القديم."), style = MaterialTheme.typography.bodySmall)
        }

        Section(tr("طريقة الاستخدام")) {
            val snippet = "OpenAI:\n  base_url = $base/v1\n  api_key  = <key>\n  model    = auto   (auto:fast | auto:reliable | auto:priority | auto:vision | agent name)\n\n" +
                "curl $base/v1/chat/completions \\\n  -H \"Authorization: Bearer <key>\" -H \"Content-Type: application/json\" \\\n  -d '{\"model\":\"auto\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'\n\n" +
                "Claude Code / Anthropic SDK:\n  ANTHROPIC_BASE_URL=$base\n  ANTHROPIC_AUTH_TOKEN=<key>"
            SelectionContainer { Text(snippet, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(snippet.replace("<key>", vm.gatewayKey))) }) { Text(tr("نسخ مع المفتاح")) }
            Text(tr("الترويسة X-Routed-Via في كل رد تُظهر أي وكيل أجاب."), style = MaterialTheme.typography.bodySmall)
        }

        Section(tr("حدود مهمة")) {
            Text(tr("• يعمل أثناء فتح التطبيق أو بوجود إشعار الخدمة. • الاستجابة المتدفقة (stream) تُرسل دفعة واحدة. • استدعاء الأدوات (tools) والصور غير مدعومة في البوابة."),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** التوجيه والتحليلات: استراتيجية الاختيار، ترتيب الأولوية، فحص الصحة، وإحصاءات الاستخدام. */
@Composable
fun AnalyticsTab(vm: RelayViewModel) {
    var tick by remember { mutableIntStateOf(0) }
    var allTime by remember { mutableStateOf(false) }
    val since = if (allTime) 0L else System.currentTimeMillis() - 24 * 3_600_000L
    val stats = remember(tick, allTime, vm.health) { Telemetry.stats(since) }
    val live = remember(tick, vm.health) { vm.pool.router.stats() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(tr("استراتيجية التوجيه")) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentRouter.Strategy.values().forEach { st ->
                    FilterChip(selected = vm.routeStrategy == st, onClick = { vm.selectStrategy(st) }, label = { Text(strategyLabel(st)) })
                }
            }
            Text(
                when (vm.routeStrategy) {
                    AgentRouter.Strategy.BALANCED -> tr("يوزّع الطلبات على الأقل استخداماً مؤخراً فتتوزع الحصص المجانية.")
                    AgentRouter.Strategy.PRIORITY -> tr("يستخدم أول وكيل جاهز حسب الترتيب أدناه، والباقي احتياط.")
                    AgentRouter.Strategy.FASTEST -> tr("يفضّل الوكيل ذا أقل زمن استجابة مقاس.")
                    AgentRouter.Strategy.RELIABLE -> tr("يفضّل الوكيل ذا أعلى نسبة نجاح.")
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(tr("ترتيب الأولوية"), style = MaterialTheme.typography.titleSmall)
            vm.agents.forEachIndexed { i, a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${a.name} (${a.provider.label})" + if (!a.enabled) " ⏸" else "",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    TextButton(enabled = i > 0, onClick = { vm.moveAgent(a.id, -1) }) { Text("▲") }
                    TextButton(enabled = i < vm.agents.size - 1, onClick = { vm.moveAgent(a.id, 1) }) { Text("▼") }
                }
            }
            if (vm.agents.isEmpty()) Text(tr("لا يوجد وكلاء بعد."), style = MaterialTheme.typography.bodySmall)
        }

        Section(tr("فحص صحة المفاتيح")) {
            Button(enabled = !vm.healthBusy && vm.agents.any { it.enabled && !it.provider.manual }, onClick = { vm.checkHealth() }) {
                Text(if (vm.healthBusy) tr("⏳ جارٍ الفحص…") else tr("فحص كل الوكلاء الآن"))
            }
            vm.agents.filter { it.enabled && !it.provider.manual }.forEach { a ->
                Text("${a.name}: ${vm.health[a.id] ?: "—"}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }

        Section(tr("إحصاءات الاستخدام")) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !allTime, onClick = { allTime = false }, label = { Text(tr("آخر 24 ساعة")) })
                FilterChip(selected = allTime, onClick = { allTime = true }, label = { Text(tr("الكل")) })
                TextButton(onClick = { tick++ }) { Text(tr("تحديث")) }
                TextButton(onClick = { Telemetry.clear(); tick++ }) { Text(tr("مسح")) }
            }
            if (stats.isEmpty()) Text(tr("لا توجد طلبات مسجّلة بعد."), style = MaterialTheme.typography.bodySmall)
            else {
                Text(trf("المجموع: %s طلب • نجاح %s٪ • ~%s توكن", stats.sumOf { it.requests },
                    stats.sumOf { it.ok } * 100 / stats.sumOf { it.requests }, stats.sumOf { it.tokens }),
                    style = MaterialTheme.typography.bodyMedium)
                stats.forEach { s ->
                    val l = live[s.agentId]
                    Text("${s.name}: ${s.requests} • ${s.successPct}% • ${s.avgMs} ms • ~${s.tokens} tok" +
                        (if (l != null && l.first > 0) " • EWMA ${l.first.toLong()} ms" else ""),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Text(tr("التوكنز تقديرية (أحرف ÷ 4). الحصص اليومية تُعاد عند منتصف الليل UTC."), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun strategyLabel(s: AgentRouter.Strategy): String = when (s) {
    AgentRouter.Strategy.BALANCED -> tr("متوازن")
    AgentRouter.Strategy.PRIORITY -> tr("أولوية")
    AgentRouter.Strategy.FASTEST -> tr("الأسرع")
    AgentRouter.Strategy.RELIABLE -> tr("الأوثق")
}
