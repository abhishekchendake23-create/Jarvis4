package com.abhynex.jarvis

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val Bg = Color(0xFF050912)
val LocalAccent = compositionLocalOf { Color(0xFF00E5FF) }
private val Green = Color(0xFF00E676)
private val Yellow = Color(0xFFFFD740)
private val Red = Color(0xFFFF5252)
private val Dim = Color(0xAAFFFFFF)

fun accentOf(n: String) = when (n) {
    "VIOLET" -> Color(0xFFB388FF); "GREEN" -> Color(0xFF69F0AE); else -> Color(0xFF00E5FF)
}

@Composable
fun JarvisApp(vm: JarvisViewModel) {
    val ac = accentOf(vm.cfg.accent)
    CompositionLocalProvider(LocalAccent provides ac) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = ac, onPrimary = Color.Black, background = Bg,
                surface = Color(0xFF0B1426), onSurface = Color(0xFFE6F1FF)
            )
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF02040A), Color(0xFF0A1630), Color(0xFF050912)))
                )
            ) {
                BackHandler(vm.screen != Screen.MAIN && vm.screen != Screen.SPLASH) { vm.screen = Screen.MAIN }
                when (vm.screen) {
                    Screen.SPLASH -> Splash { vm.screen = Screen.MAIN }
                    Screen.MAIN -> MainScreen(vm)
                    Screen.API -> ApiCenter(vm)
                    Screen.SETTINGS -> SettingsScreen(vm)
                    Screen.MEMORY -> Frame("MEMORY", { vm.screen = Screen.MAIN }) { MemoryPanel(vm) }
                    Screen.SECURITY -> Frame("SECURITY", { vm.screen = Screen.MAIN }) { SecurityPanel(vm) }
                    Screen.MOBILE -> MobileScreen(vm)
                }
                vm.banner?.let { b ->
                    LaunchedEffect(b) { delay(4500); if (vm.banner == b) vm.banner = null }
                    Box(
                        Modifier.align(Alignment.TopCenter).padding(12.dp).clip(RoundedCornerShape(14.dp))
                            .background(Color(0xEE0B1426)).border(1.dp, ac, RoundedCornerShape(14.dp))
                            .clickable { vm.banner = null }.padding(14.dp)
                    ) { Text(b, fontSize = 13.sp) }
                }
                if (vm.alert != null) AlertOverlay(vm)
                if (vm.locked) LockScreen(vm)
            }
        }
    }
}

// ---------- building blocks ----------
@Composable
fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ac = LocalAccent.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x14FFFFFF))
            .border(1.dp, ac.copy(alpha = .25f), RoundedCornerShape(18.dp)).padding(14.dp),
        content = content
    )
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (desc != null) Text(desc, fontSize = 11.sp, color = Dim)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val ac = LocalAccent.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (sel) ac.copy(alpha = .25f) else Color(0x14FFFFFF))
                    .border(1.dp, if (sel) ac else Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                    .clickable { onSelect(o) },
                contentAlignment = Alignment.Center
            ) { Text(o, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (sel) ac else Color.White) }
        }
    }
}

@Composable
fun Frame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("\u2190 BACK") }
            Spacer(Modifier.weight(1f))
            Text(title, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = LocalAccent.current, fontSize = 14.sp)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content(); Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AiCore(state: Core, sz: Dp = 150.dp) {
    val ac = LocalAccent.current
    val col = when (state) {
        Core.IDLE -> ac; Core.LISTENING -> Green; Core.THINKING -> Color(0xFFB388FF)
        Core.SEARCHING -> Color(0xFF40C4FF); Core.SPEAKING -> Yellow; Core.ERROR -> Red
    }
    val busy = state == Core.THINKING || state == Core.SEARCHING
    val lively = state == Core.LISTENING || state == Core.SPEAKING
    val inf = rememberInfiniteTransition(label = "core")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(if (busy) 1800 else 7000, easing = LinearEasing)), label = "spin")
    val pulse by inf.animateFloat(
        0.92f, 1.08f,
        infiniteRepeatable(tween(if (lively) 600 else 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )
    Canvas(Modifier.size(sz)) {
        val c = center
        val r = this.size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = .5f), Color.Transparent), center = c, radius = r * pulse), radius = r * pulse)
        drawCircle(col, radius = r * 0.26f * pulse)
        drawCircle(col.copy(alpha = .5f), radius = r * 0.55f, style = Stroke(2.dp.toPx()))
        rotate(spin, c) {
            val d = r * 1.5f
            drawArc(col, 0f, 100f, false, Offset(c.x - d / 2, c.y - d / 2), Size(d, d), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            drawArc(col.copy(alpha = .6f), 180f, 60f, false, Offset(c.x - d / 2, c.y - d / 2), Size(d, d), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
        rotate(-spin * 1.5f, c) {
            val d = r * 1.85f
            drawArc(col.copy(alpha = .8f), 90f, 40f, false, Offset(c.x - d / 2, c.y - d / 2), Size(d, d), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
fun Splash(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { delay(900); step = 1; delay(900); step = 2; delay(1500); onDone() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AiCore(Core.IDLE, 130.dp)
            Spacer(Modifier.height(28.dp))
            Crossfade(step, label = "splash") { s ->
                Text(
                    when (s) { 0 -> "ABHYNEX"; 1 -> "JARVIS"; else -> "YOUR INTELLIGENT PERSONAL AI ASSISTANT" },
                    fontSize = if (s < 2) 32.sp else 14.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp, color = LocalAccent.current, textAlign = TextAlign.Center
                )
            }
        }
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) { Text("SKIP") }
    }
}

// ---------- main ----------
@Composable
fun MainScreen(vm: JarvisViewModel) {
    val ac = LocalAccent.current
    val ai = vm.state(KeyKind.AI)
    val (statusText, statusCol) = when {
        !vm.online && ai == ConnState.NOT_CONFIGURED -> "\uD83D\uDFE1 OFFLINE / API NOT CONFIGURED" to Yellow
        !vm.online -> "\uD83D\uDFE1 OFFLINE" to Yellow
        ai == ConnState.CONNECTED -> "\uD83D\uDFE2 SYSTEM ONLINE" to Green
        ai == ConnState.FAILED -> "\uD83D\uDD34 AI ERROR" to Red
        ai == ConnState.UNTESTED -> "\uD83D\uDFE1 TEST YOUR AI KEY" to Yellow
        else -> "\uD83D\uDFE1 SETUP REQUIRED" to Yellow
    }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) { if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ABHYNEX JARVIS", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = ac, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(statusText, fontSize = 10.sp, color = statusCol, fontWeight = FontWeight.Bold)
        }
        if (ai == ConnState.NOT_CONFIGURED) {
            Spacer(Modifier.height(8.dp))
            Glass {
                Text("\uD83D\uDFE1 JARVIS SETUP REQUIRED", fontWeight = FontWeight.Bold, color = Yellow)
                Text("Add your AI key in the API Center. Local Android commands work without it.", fontSize = 12.sp, color = Dim)
                Spacer(Modifier.height(8.dp))
                BigButton("OPEN API CENTER", Modifier.fillMaxWidth()) { vm.screen = Screen.API }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AiCore(vm.core, 120.dp)
            Text(
                when {
                    vm.partial.isNotBlank() -> vm.partial
                    vm.coreNote.isNotBlank() -> vm.coreNote
                    vm.core == Core.LISTENING -> "LISTENING..."
                    else -> vm.core.name
                },
                fontSize = 11.sp, letterSpacing = 2.sp, color = ac, textAlign = TextAlign.Center
            )
            if (vm.micOn) Text("\uD83C\uDF99\uFE0F Microphone active", fontSize = 11.sp, color = Green, fontWeight = FontWeight.Bold)
        }
        if (vm.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Ask anything, or try:\n\u201CWhat is C++?\u201D\n\u201CAbhynex open camera\u201D\n\u201CAbhynex camera open kar\u201D\n\u201CAbhynex \u0915\u0945\u092E\u0947\u0930\u093E \u0909\u0918\u0921\u201D",
                    color = Dim, textAlign = TextAlign.Center, fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.messages, key = { it.id }) { MessageItem(vm, it) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            QuickAction("\uD83C\uDF99\uFE0F", "Voice", vm.micOn, Modifier.weight(1f)) { vm.toggleMic() }
            QuickAction("\uD83C\uDF10", "Search", vm.forceSearch, Modifier.weight(1f)) {
                vm.forceSearch = !vm.forceSearch
                vm.note(if (vm.forceSearch) "Live Search armed for your next message." else "Live Search disarmed.")
            }
            QuickAction("\uD83E\uDDE0", "Memory", false, Modifier.weight(1f)) { vm.screen = Screen.MEMORY }
            QuickAction("\uD83D\uDD10", "Security", false, Modifier.weight(1f)) { vm.screen = Screen.SECURITY }
            QuickAction("\uD83D\uDCF1", "Mobile", false, Modifier.weight(1f)) { vm.screen = Screen.MOBILE }
            QuickAction("\u2699\uFE0F", "Settings", false, Modifier.weight(1f)) { vm.screen = Screen.SETTINGS }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(if (vm.micOn) Green.copy(alpha = .35f) else ac.copy(alpha = .2f))
                    .border(1.dp, if (vm.micOn) Green else ac, RoundedCornerShape(26.dp)).clickable { vm.toggleMic() },
                contentAlignment = Alignment.Center
            ) { Text("\uD83C\uDF99\uFE0F", fontSize = 22.sp) }
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                placeholder = { Text("Ask Abhynex...") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.send(input); input = "" })
            )
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(ac)
                    .clickable { vm.send(input); input = "" },
                contentAlignment = Alignment.Center
            ) { Text("\u27A4", fontSize = 20.sp, color = Color.Black) }
        }
    }
}

@Composable
fun QuickAction(icon: String, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ac = LocalAccent.current
    Column(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) ac.copy(alpha = .3f) else Color(0x14FFFFFF))
            .border(1.dp, if (active) ac else Color(0x22FFFFFF), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 18.sp)
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun MessageItem(vm: JarvisViewModel, m: ChatMsg) {
    val clip = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val ac = LocalAccent.current
    val isUser = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(if (isUser) 0.85f else 1f).clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        m.isError -> Red.copy(alpha = .2f); isUser -> ac.copy(alpha = .18f); else -> Color(0x14FFFFFF)
                    }
                ).padding(12.dp)
        ) {
            if (isUser || m.isError) Text(m.text, fontSize = 14.sp)
            else Md(m.text) { clip.setText(AnnotatedString(it)) }
            if (m.sources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("\uD83C\uDF10 SOURCES", fontSize = 10.sp, color = ac, fontWeight = FontWeight.Bold)
                m.sources.forEachIndexed { i, s ->
                    Text(
                        "[${i + 1}] ${s.title.ifBlank { s.url }}", fontSize = 12.sp, color = ac,
                        modifier = Modifier.padding(vertical = 3.dp).clickable { uri.openUri(s.url) }
                    )
                }
            }
            if (m.action != null) {
                Spacer(Modifier.height(6.dp))
                BigButton(if (m.action == "SEARCH") "CONFIGURE SEARCH" else "OPEN API CENTER") { vm.screen = Screen.API }
            }
            if (!isUser && !m.isError) {
                Row {
                    val pad = PaddingValues(horizontal = 8.dp)
                    TextButton({ clip.setText(AnnotatedString(m.text)) }, contentPadding = pad) { Text("COPY", fontSize = 11.sp) }
                    TextButton({ vm.speakOut(m.text) }, contentPadding = pad) { Text("SPEAK", fontSize = 11.sp) }
                    TextButton({ vm.regenerate(m.id) }, contentPadding = pad) { Text("REGENERATE", fontSize = 11.sp) }
                }
            }
        }
    }
}

// ---------- markdown ----------
sealed interface Block {
    data class Para(val t: String) : Block
    data class Head(val level: Int, val t: String) : Block
    data class Bullet(val marker: String, val t: String) : Block
    data class Code(val lang: String, val code: String) : Block
    data class Table(val rows: List<List<String>>) : Block
}

fun parseMd(text: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        val tl = l.trim()
        when {
            tl.startsWith("```") -> {
                val lang = tl.removePrefix("```").trim()
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { sb.appendLine(lines[i]); i++ }
                out.add(Block.Code(lang, sb.toString().trimEnd()))
            }
            tl.startsWith("|") -> {
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                    if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows.add(cells)
                    i++
                }
                i--
                out.add(Block.Table(rows))
            }
            tl.startsWith("#") -> {
                val lv = tl.takeWhile { it == '#' }.length
                out.add(Block.Head(lv, tl.drop(lv).trim()))
            }
            tl.startsWith("- ") || tl.startsWith("* ") -> out.add(Block.Bullet("\u2022", tl.drop(2)))
            Regex("^\\d+[.)] .*").matches(tl) -> out.add(Block.Bullet(tl.substringBefore(' '), tl.substringAfter(' ')))
            tl.isNotEmpty() -> out.add(Block.Para(tl))
        }
        i++
    }
    return out
}

fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`").findAll(s)) {
        append(s.substring(i, m.range.first))
        if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33FFFFFF))) { append(m.groupValues[2]) }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

@Composable
fun Md(text: String, copy: (String) -> Unit) {
    val blocks = remember(text) { parseMd(text) }
    val ac = LocalAccent.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Para -> Text(inline(b.t), fontSize = 14.sp)
                is Block.Head -> Text(inline(b.t), fontSize = (20 - b.level * 2).coerceAtLeast(15).sp, fontWeight = FontWeight.Bold, color = ac)
                is Block.Bullet -> Row { Text(b.marker + "  ", fontSize = 14.sp, color = ac); Text(inline(b.t), fontSize = 14.sp) }
                is Block.Code -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.lang.ifBlank { "code" }, fontSize = 10.sp, color = Dim, modifier = Modifier.weight(1f))
                        TextButton({ copy(b.code) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("COPY CODE", fontSize = 11.sp) }
                    }
                    Text(b.code, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                is Block.Table -> Column(Modifier.fillMaxWidth().border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))) {
                    b.rows.forEachIndexed { ri, r ->
                        Row(Modifier.fillMaxWidth().background(if (ri == 0) ac.copy(alpha = .15f) else Color.Transparent)) {
                            r.forEach { c ->
                                Text(
                                    inline(c), fontSize = 12.sp, modifier = Modifier.weight(1f).padding(6.dp),
                                    fontWeight = if (ri == 0) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
