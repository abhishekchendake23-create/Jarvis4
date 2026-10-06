package com.abhynex.jarvis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF00E676)
private val Yellow = Color(0xFFFFD740)
private val Red = Color(0xFFFF5252)
private val Dim = Color(0xAAFFFFFF)

private fun label(s: ConnState, voice: Boolean = false, short: Boolean = false): Pair<String, Color> = when (s) {
    ConnState.NOT_CONFIGURED -> "\uD83D\uDD34 NOT CONFIGURED" to Red
    ConnState.UNTESTED -> "\uD83D\uDFE1 SAVED \u2014 NOT TESTED" to Yellow
    ConnState.CONNECTED -> (if (short) "\uD83D\uDFE2 ONLINE" else "\uD83D\uDFE2 CONNECTED") to Green
    ConnState.FAILED -> (if (voice) "\uD83D\uDD34 UNAVAILABLE" else "\uD83D\uDD34 FAILED") to Red
}

@Composable
fun StatusLine(name: String, text: String, col: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(text, fontSize = 12.sp, color = col, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Dashboard(vm: JarvisViewModel) {
    Glass {
        Text("API STATUS", fontSize = 11.sp, letterSpacing = 2.sp, color = LocalAccent.current, fontWeight = FontWeight.Bold)
        val a = label(vm.state(KeyKind.AI), short = true)
        val v = label(vm.state(KeyKind.VOICE), voice = true, short = true)
        val s = label(vm.state(KeyKind.SEARCH), short = true)
        StatusLine("AI", a.first, a.second)
        StatusLine("INWORLD VOICE", v.first, v.second)
        StatusLine("LIVE SEARCH", s.first, s.second)
        StatusLine("MEMORY", if (vm.cfg.memoryOn) "\uD83D\uDFE2 ACTIVE" else "\u26AA OFF", if (vm.cfg.memoryOn) Green else Dim)
        StatusLine("MOBILE CONTROL", "\uD83D\uDFE2 READY", Green)
    }
}

@Composable
fun KeyCard(
    vm: JarvisViewModel, kind: KeyKind, title: String, hint: String, testLabel: String,
    onTest: () -> Unit, extra: @Composable () -> Unit = {}
) {
    var input by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val st = vm.state(kind)
    val (txt, col) = label(st, voice = kind == KeyKind.VOICE)
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(txt, fontSize = 11.sp, color = col, fontWeight = FontWeight.Bold)
        }
        Text(hint, fontSize = 11.sp, color = Dim, modifier = Modifier.padding(vertical = 6.dp))
        OutlinedTextField(
            value = input, onValueChange = { input = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("API Key") },
            placeholder = { Text(if (st != ConnState.NOT_CONFIGURED) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022 (saved)" else "Paste key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false)
        )
        extra()
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("SAVE KEY", Modifier.weight(1f)) { vm.saveKey(kind, input); input = "" }
            BigButton(if (vm.busy[kind.name] == true) "TESTING..." else testLabel, Modifier.weight(1f), vm.busy[kind.name] != true, onTest)
        }
        if (st != ConnState.NOT_CONFIGURED) {
            TextButton({ confirm = true }) { Text("DELETE KEY", color = Red, fontSize = 12.sp) }
        }
        vm.testMsg[kind.name]?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete $title key?") },
        text = { Text("The saved key will be erased from this device. You can add it again later.") },
        confirmButton = { TextButton({ vm.deleteKey(kind); confirm = false }) { Text("DELETE", color = Red) } },
        dismissButton = { TextButton({ confirm = false }) { Text("CANCEL") } }
    )
}

@Composable
fun ApiCenter(vm: JarvisViewModel) {
    Frame("ABHYNEX API CENTER", { vm.screen = Screen.MAIN }) {
        Dashboard(vm)
        KeyCard(
            vm, KeyKind.AI, "AI API",
            "Anthropic API key (console.anthropic.com). Stored encrypted with the Android Keystore.",
            "TEST CONNECTION", { vm.testAi() }
        ) {
            OutlinedTextField(
                value = vm.cfg.aiModel, onValueChange = { v -> vm.upd { it.copy(aiModel = v.trim()) } },
                singleLine = true, label = { Text("Model") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        KeyCard(
            vm, KeyKind.VOICE, "INWORLD VOICE",
            "Paste the Base64 API credential from Inworld Portal \u2192 Settings \u2192 API Keys. Voice ID and model must exist in your Inworld workspace.",
            "TEST VOICE", { vm.testVoice() }
        ) {
            OutlinedTextField(
                value = vm.cfg.inworldVoice, onValueChange = { v -> vm.upd { it.copy(inworldVoice = v.trim()) } },
                singleLine = true, label = { Text("Voice ID") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = vm.cfg.inworldModel, onValueChange = { v -> vm.upd { it.copy(inworldModel = v.trim()) } },
                singleLine = true, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        KeyCard(
            vm, KeyKind.SEARCH, "WEB SEARCH",
            "Brave Search API key (brave.com/search/api). Used for live news, weather, current events.",
            "TEST SEARCH", { vm.testSearch() }
        )
        Text(
            "Keys never leave this phone except to call the provider you configured. They are masked here, never logged, and can be deleted at any time.",
            fontSize = 11.sp, color = Dim
        )
    }
}

@Composable
fun MemoryPanel(vm: JarvisViewModel) {
    Glass {
        Text("\uD83E\uDDE0 MEMORY", fontWeight = FontWeight.Bold)
        SwitchRow("Memory ON/OFF", "Saves recent conversation encrypted on this device only.", vm.cfg.memoryOn) { on ->
            vm.upd { it.copy(memoryOn = on) }
        }
        Text("Stored messages: ${vm.messages.count { !it.isError }}", fontSize = 12.sp, color = Dim)
        Text("Avoid sending passwords or card numbers in chat.", fontSize = 11.sp, color = Dim)
        Spacer(Modifier.height(8.dp))
        BigButton("CLEAR MEMORY", Modifier.fillMaxWidth()) { vm.clearMemory() }
    }
}

@Composable
fun SecurityPanel(vm: JarvisViewModel) {
    var pass by remember { mutableStateOf("") }
    Glass {
        Text("\uD83D\uDD10 OWNER PROTECTION MODE", fontWeight = FontWeight.Bold)
        SwitchRow("OWNER PROTECTION", "Locks the app behind biometric / screen lock when opened or after 30s in background.", vm.cfg.ownerProtection) {
            vm.setOwnerProtection(it)
        }
        SwitchRow("VOICE VERIFICATION", "Voice-issued device commands must include your passphrase. Convenience only \u2014 not secure speaker recognition.", vm.cfg.voiceVerify) {
            vm.setVoiceVerify(it)
        }
        SwitchRow("BIOMETRIC CONFIRMATION", "Require biometric/PIN for sensitive actions (delete memory, change security).", vm.cfg.bioConfirm) {
            vm.setBioConfirm(it)
        }
        SwitchRow("UNAUTHORIZED ACCESS ALERT", "Alert after repeated failed unlock attempts or failed voice verification.", vm.cfg.alertOn) {
            vm.setAlert(it)
        }
        SwitchRow("ALARM", "Sound an alarm with the alert until dismissed.", vm.cfg.alarmOn) { vm.setAlarm(it) }
    }
    Glass {
        Text("OWNER PASSPHRASE", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(if (vm.hasPassphrase()) "A passphrase is saved." else "No passphrase set.", fontSize = 11.sp, color = Dim)
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, singleLine = true, label = { Text("New passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
        Spacer(Modifier.height(8.dp))
        BigButton("SAVE PASSPHRASE", Modifier.fillMaxWidth()) { vm.savePassphrase(pass); pass = "" }
    }
    Text(
        "ABHYNEX never records audio/video, tracks location or uploads anything in the background. Detection is limited to what Android allows: failed biometric attempts inside this app and failed voice verification.",
        fontSize = 11.sp, color = Dim
    )
}

@Composable
fun MobileScreen(vm: JarvisViewModel) {
    Frame("MOBILE CONTROL", { vm.screen = Screen.MAIN }) {
        Glass {
            Text("Allowlisted commands only. Say them (EN / HI / MR) or tap.", fontSize = 12.sp, color = Dim)
            Spacer(Modifier.height(8.dp))
            Cmd.values().forEach { c ->
                OutlinedButton(onClick = { vm.runCmd(c) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(c.label)
                }
            }
        }
        Text(
            "Examples: \u201CAbhynex open camera\u201D \u00B7 \u201CAbhynex camera open kar\u201D \u00B7 \u201CAbhynex \u0915\u0945\u092E\u0947\u0930\u093E \u0909\u0918\u0921\u201D \u00B7 \u201C\u0906\u0935\u093E\u091C \u0935\u093E\u0922\u0935\u201D.\n" +
                "Anything not on this list is rejected. Vibration mode may need Do Not Disturb access; media keys act on whichever media app is active.",
            fontSize = 11.sp, color = Dim
        )
    }
}

@Composable
fun SettingsScreen(vm: JarvisViewModel) {
    Frame("SETTINGS", { vm.screen = Screen.MAIN }) {
        Dashboard(vm)
        BigButton("OPEN API CENTER", Modifier.fillMaxWidth()) { vm.screen = Screen.API }
        Glass {
            Text("LANGUAGE", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ChipRow(listOf("AUTO", "ENGLISH", "HINDI", "MARATHI"), vm.cfg.language) { l -> vm.upd { it.copy(language = l) } }
        }
        Glass {
            Text("INWORLD VOICE", fontWeight = FontWeight.Bold)
            SwitchRow("Voice ON/OFF", null, vm.cfg.voiceOn) { on -> vm.upd { it.copy(voiceOn = on) }; if (!on) vm.stopSpeaking() }
            SwitchRow("Auto Speak", "Speak every reply automatically.", vm.cfg.autoSpeak) { on -> vm.upd { it.copy(autoSpeak = on) } }
            SwitchRow("Hey Abhynex (visible listening)", "Foreground only. Mic stays on while the screen is open; stops when you leave the app.", vm.wake) { vm.toggleWake() }
        }
        Glass {
            Text("SEARCH", fontWeight = FontWeight.Bold)
            SwitchRow("Live Search ON/OFF", "Auto-search for current-events questions.", vm.cfg.searchOn) { on -> vm.upd { it.copy(searchOn = on) } }
        }
        MemoryPanel(vm)
        SecurityPanel(vm)
        Glass {
            Text("APP", fontWeight = FontWeight.Bold)
            SwitchRow("Notifications", "Used for security alerts.", vm.cfg.notifications) { vm.setNotifications(it) }
            Text("Theme accent", fontSize = 12.sp, color = Dim, modifier = Modifier.padding(vertical = 6.dp))
            ChipRow(listOf("CYAN", "VIOLET", "GREEN"), vm.cfg.accent) { a -> vm.upd { it.copy(accent = a) } }
            Spacer(Modifier.height(10.dp))
            Text("About ABHYNEX JARVIS v1.0", fontSize = 12.sp, color = Dim)
        }
    }
}

@Composable
fun AlertOverlay(vm: JarvisViewModel) {
    Box(Modifier.fillMaxSize().background(Color(0xF0300000)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("\u26A0\uFE0F", fontSize = 56.sp)
            Text("ABHYNEX SECURITY ALERT", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Red, textAlign = TextAlign.Center)
            Text(vm.alert ?: "", fontSize = 15.sp, textAlign = TextAlign.Center)
            BigButton("DISMISS", Modifier.fillMaxWidth()) { vm.dismissAlert() }
        }
    }
}

@Composable
fun LockScreen(vm: JarvisViewModel) {
    LaunchedEffect(vm.locked) { if (vm.locked) vm.tryUnlock() }
    Box(Modifier.fillMaxSize().background(Color(0xFF02040A)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AiCore(Core.IDLE, 120.dp)
            Text("ABHYNEX LOCKED", fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = LocalAccent.current)
            BigButton("UNLOCK", Modifier.fillMaxWidth()) { vm.tryUnlock() }
        }
    }
}
