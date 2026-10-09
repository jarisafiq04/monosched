package com.example.arabickb

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ponytail: single monochrome theme — dark grey keys, no theme switching
// ponytail: Monosched design language (timetable app) — true black/grey/white,
// default font, Bold titles, zero radius. Used by the settings page; keyboard keeps its own look.
private val MsShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp), large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)
private val MsCard = Color(0xFF1B1E1E)
private val MsMuted = Color(0xFF9AA3A3)
private val MsOutline = Color(0xFF3A3F3F)
private val MonoDark = darkColorScheme(
    primary = Color(0xFFE8E8E8),
    onPrimary = Color(0xFF1A1A1C),
    primaryContainer = Color(0xFF3A3A3E),
    onPrimaryContainer = Color(0xFFE8E8E8),
    secondary = Color(0xFFB0B0B5),
    secondaryContainer = Color(0xFF2A2A2E),
    onSecondaryContainer = Color(0xFFE8E8E8),
    tertiary = Color(0xFFB0B0B5),
    background = Color(0xFF101012),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF161618),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFFB0B0B5),
    surfaceContainerHighest = Color(0xFF1E1E22),
    outline = Color(0xFF3F3F45)
)

// ponytail: BT HID keyboard — full screen, hold/repeat works, no USB/server needed
class MainActivity : ComponentActivity() {
    private lateinit var kb: HidKeyboard
    private var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var foundDevices by mutableStateOf<Map<String, BluetoothDevice>>(emptyMap())
    private var discovering by mutableStateOf(false)
    private var hapticStrength = 60

    // ponytail: live discovery — nearby devices stream in while settings is open
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (d?.address != null) foundDevices = foundDevices + (d.address to d)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> discovering = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        try {
            val a = BluetoothAdapter.getDefaultAdapter() ?: return
            if (!a.isEnabled) return
            if (a.isDiscovering) a.cancelDiscovery()
            foundDevices = emptyMap()
            refreshBonded()
            discovering = a.startDiscovery()
        } catch (_: SecurityException) { discovering = false }
    }

    private fun stopScan() {
        try {
            BluetoothAdapter.getDefaultAdapter()?.let { if (it.isDiscovering) it.cancelDiscovery() }
        } catch (_: SecurityException) {}
        discovering = false
    }

    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            val vm = getSystemService(android.os.VibratorManager::class.java)
            try { vm.defaultVibrator } catch (_: Exception) { null }
        } else {
            @Suppress("DEPRECATION") getSystemService(android.os.Vibrator::class.java)
        }
    }

    // ponytail: one tiny tick per keypress, strength from settings
    private fun buzz() {
        try {
            if (hapticStrength <= 0) return
            val v = vibrator ?: return
            val amp = (60 + hapticStrength.coerceIn(1, 100) * 195 / 100).coerceIn(1, 255)
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(25, amp))
            } else {
                @Suppress("DEPRECATION") v.vibrate(25)
            }
        } catch (_: Exception) {}
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    // ponytail: true fullscreen — status + gesture bars hidden, swipe to peek
    private fun goFullscreen() {
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let {
                it.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                it.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        goFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()
        hapticStrength = getSharedPreferences("arabickb", MODE_PRIVATE).getInt("haptic", 60)
        kb = HidKeyboard(this)
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this, scanReceiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                },
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        } catch (_: Exception) {}
        askPerms()
        kb.init()
        refreshBonded()
        setContent { KeyboardScreen() }
    }

    override fun onDestroy() {
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        stopScan()
        super.onDestroy()
    }

    private fun askPerms() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_CONNECT
            need += Manifest.permission.BLUETOOTH_ADVERTISE
            need += Manifest.permission.BLUETOOTH_SCAN
        } else {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun refreshBonded() {
        devices = try { kb.bonded() } catch (_: SecurityException) { emptyList() }
    }

    // Windows 101 / Linux basic mapping: physical qwerty -> Arabic
    private val arMap = mapOf(
        '`' to 'ذ', 'q' to 'ض', 'w' to 'ص', 'e' to 'ث', 'r' to 'ق', 't' to 'ف',
        'y' to 'غ', 'u' to 'ع', 'i' to 'ه', 'o' to 'خ', 'p' to 'ح', '[' to 'ج', ']' to 'د',
        'a' to 'ش', 's' to 'س', 'd' to 'ي', 'f' to 'ب', 'g' to 'ل', 'h' to 'ا',
        'j' to 'ت', 'k' to 'ن', 'l' to 'م', ';' to 'ك', '\'' to 'ط',
        'z' to 'ئ', 'x' to 'ء', 'c' to 'ؤ', 'v' to 'ر', 'b' to 'ﻻ', 'n' to 'ى',
        'm' to 'ة', ',' to 'و', '.' to 'ز', '/' to 'ظ', '\\' to '|'
    )
    private val arShift = mapOf(
        '`' to 'ّ', 'q' to 'َ', 'w' to 'ً', 'e' to 'ُ', 'r' to 'ٌ',
        't' to 'ﻹ', 'y' to 'إ', 'u' to '`', 'i' to '÷', 'o' to '×', 'p' to '؛',
        '[' to '<', ']' to '>', 'a' to 'ِ', 's' to 'ٍ', 'd' to ']', 'f' to '[',
        'g' to 'ﻷ', 'h' to 'أ', 'j' to 'ـ', 'k' to '،', 'l' to '/', ';' to ':',
        '\'' to '"', 'z' to '~', 'x' to 'ْ', 'c' to '}', 'v' to '{', 'b' to 'ﻵ',
        'n' to 'آ', 'm' to '\'', ',' to ',', '.' to '.', '/' to '؟', '\\' to '|'
    )
    // Fn+F1..F6 -> media (matches HidKeyboard.Fn bit order)
    private val fnMedia: Map<Int, Pair<Int, ImageVector>> = mapOf(
        HidKeys.F1 to (HidKeyboard.Fn.MIC to Icons.Filled.Mic),
        HidKeys.F2 to (HidKeyboard.Fn.VOLDN to Icons.Filled.VolumeDown),
        HidKeys.F3 to (HidKeyboard.Fn.VOLUP to Icons.Filled.VolumeUp),
        HidKeys.F4 to (HidKeyboard.Fn.MUTE to Icons.Filled.VolumeMute),
        HidKeys.F5 to (HidKeyboard.Fn.BRIDN to Icons.Filled.BrightnessLow),
        HidKeys.F6 to (HidKeyboard.Fn.BRIUP to Icons.Filled.BrightnessHigh)
    )
    private val usShift = mapOf(
        '`' to '~', '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
        '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')', '-' to '_',
        '=' to '+', '[' to '{', ']' to '}', '\\' to '|', ';' to ':', '\'' to '"',
        ',' to '<', '.' to '>', '/' to '?'
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun KeyboardScreen() {
        val scope = rememberCoroutineScope()
        var arabic by remember { mutableStateOf(getSharedPreferences("arabickb", MODE_PRIVATE).getBoolean("lang_ar", false)) }
        var shift by remember { mutableStateOf(false) }
        var caps by remember { mutableStateOf(false) }
        var ctrl by remember { mutableStateOf(false) }
        var alt by remember { mutableStateOf(false) }
        var win by remember { mutableStateOf(false) }
        var fn by remember { mutableStateOf(false) }
        var fnHeld by remember { mutableStateOf(false) }
        var fnUsed by remember { mutableStateOf(false) }
        var padMode by remember { mutableStateOf(false) }
        var padBtnMask by remember { mutableStateOf(0) }
        var lastTapUp by remember { mutableStateOf(0L) }
        var lastTapX by remember { mutableStateOf(0f) }
        var lastTapY by remember { mutableStateOf(0f) }
        var padSpeed by remember { mutableStateOf(getSharedPreferences("arabickb", MODE_PRIVATE).getInt("padspeed", 100)) }
        var padNatural by remember { mutableStateOf(getSharedPreferences("arabickb", MODE_PRIVATE).getBoolean("padnatural", true)) }
        var settings by remember { mutableStateOf(false) }
        val grotesk = remember { FontFamily(Font(R.font.space_grotesk_family)) }
        val type = remember(grotesk) {
            val t = Typography()
            t.copy(displayLarge = t.displayLarge.copy(fontFamily = grotesk),
                displayMedium = t.displayMedium.copy(fontFamily = grotesk),
                displaySmall = t.displaySmall.copy(fontFamily = grotesk),
                headlineLarge = t.headlineLarge.copy(fontFamily = grotesk),
                headlineMedium = t.headlineMedium.copy(fontFamily = grotesk),
                headlineSmall = t.headlineSmall.copy(fontFamily = grotesk),
                titleLarge = t.titleLarge.copy(fontFamily = grotesk),
                titleMedium = t.titleMedium.copy(fontFamily = grotesk),
                titleSmall = t.titleSmall.copy(fontFamily = grotesk),
                bodyLarge = t.bodyLarge.copy(fontFamily = grotesk),
                bodyMedium = t.bodyMedium.copy(fontFamily = grotesk),
                bodySmall = t.bodySmall.copy(fontFamily = grotesk),
                labelLarge = t.labelLarge.copy(fontFamily = grotesk),
                labelMedium = t.labelMedium.copy(fontFamily = grotesk),
                labelSmall = t.labelSmall.copy(fontFamily = grotesk))
        }

        var haptic by remember { mutableStateOf(hapticStrength) }

        var autoCon by remember { mutableStateOf(getSharedPreferences("arabickb", MODE_PRIVATE).getBoolean("autocon", true)) }
        // ponytail: BlueZ drops the link silently — poll truth, never show stale green
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(3000)
                if (kb.connectedName != null && !kb.isAlive()) kb.onLinkLost()
            }
        }
        DisposableEffect(settings) {
            if (settings) startScan() else stopScan()
            onDispose { stopScan() }
        }
        // ponytail: redial the last working device by ourselves — no manual Connect taps
        LaunchedEffect(autoCon) {
            while (autoCon) {
                kotlinx.coroutines.delay(5000)
                if (kb.connectedName == null) {
                    try { kb.lastDevice()?.let { kb.connect(it) } } catch (_: Exception) {}
                }
            }
        }

        fun modBase(): Int {
            var m = 0
            if (ctrl) m = m or HidKeys.LCTRL
            if (alt) m = m or HidKeys.LALT
            if (win) m = m or HidKeys.LGUI
            return m
        }
        var modUsed by remember { mutableStateOf(false) }
        fun afterTap() {
            // ponytail: all modifiers are physical now — held until finger lifts
        }
        fun label(en: Char): String {
            // ponytail: shift layer first — these keys also live in arMap, which
            // returned early and hid ~ _ : " < > ? (plus [ ] \ |)
            if (!arabic && shift && usShift.containsKey(en)) return usShift[en].toString()
            if (en in 'a'..'z' || arMap.containsKey(en)) {
                val showAr = arabic && arMap.containsKey(en)
                val base = if (showAr) arMap[en].toString() else en.toString()
                return if (!arabic && (caps xor shift)) base.uppercase() else base
            }
            return en.toString()
        }

        // Key press handlers with hold/repeat support
        fun onKeyDown(enChar: Char? = null, hidCode: Int = 0) {
            if (ctrl || alt || win) modUsed = true
            if (fn) { fnUsed = true; if (!fnHeld) fn = false }
            var m = modBase()
            if (enChar != null) {
                val lower = enChar.lowercaseChar()
                val code = HidKeys.ascii[lower]?.second ?: return
                val wantUpper = caps xor shift
                if (lower in 'a'..'z') { if (wantUpper) m = m or HidKeys.LSHIFT }
                else if (shift) m = m or HidKeys.LSHIFT
                kb.keyDown(m, code)
            } else if (hidCode != 0) {
                if (shift) m = m or HidKeys.LSHIFT
                kb.keyDown(m, hidCode)
            }
            afterTap()
        }

        // ponytail: Fn+F1..F6 send consumer usages (uniform path, stock Mango binds).
        // Hold repeats like a laptop (350ms delay, then 8/sec).
        var fnRepeat: kotlinx.coroutines.Job? by remember { mutableStateOf(null) }
        // ponytail: Fn+F1 (mic) and Fn+F5 (brightness-down) go as F13/F17 keys:
        // kernel maps consumer 0xCF->voice-command and drops 0x6E entirely (source-checked),
        // while F13/F17 arrive as XF86Tools/XF86Launch8 which Mango binds above.
        // ponytail: media is primary on F1..F6; holding Fn reveals plain F-keys + shortcuts.
        // Snapshot first — clearing the arm before checking it silently broke tap-then-tap.
        fun onFKey(hidCode: Int) {
            val wasFn = fn
            Log.d("FNBUG", "onFKey code=$hidCode wasFn=$wasFn fnHeld=$fnHeld")
            if (fn) { fnUsed = true; if (!fnHeld) fn = false }
            // ponytail: shortcuts are primary (no Fn needed); Fn-held falls through to plain F-keys.
            // Clearing state here too — leaving the page mid-hold orphans releases and Fn sticks.
            if (!wasFn && hidCode == HidKeys.F7) {
                settings = true; buzz()
                fn = false; fnHeld = false; fnUsed = false
                shift = false; ctrl = false; alt = false; win = false
                kb.releaseAll()
                return
            }
            if (!wasFn && hidCode == HidKeys.F8) {
                arabic = !arabic
                getSharedPreferences("arabickb", MODE_PRIVATE).edit().putBoolean("lang_ar", arabic).apply()
                buzz()
                return
            }
            if (!wasFn && hidCode == HidKeys.F9) {
                padMode = true; buzz()
                fn = false; fnHeld = false; fnUsed = false
                shift = false; ctrl = false; alt = false; win = false
                kb.releaseAll()
                return
            }
            if (wasFn) { kb.keyDown(modBase(), hidCode); return }
            val act: () -> Unit = when (hidCode) {
                HidKeys.F1 -> ({ kb.tap(0, 0x68) })
                HidKeys.F5 -> ({ kb.consumerTap(HidKeyboard.Fn.BRIDN) })
                else -> {
                    val media = fnMedia[hidCode]?.first ?: return kb.keyDown(modBase(), hidCode)
                    ({ kb.consumerTap(media) })
                }
            }
            act()
            fnRepeat?.cancel()
            fnRepeat = scope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(350)
                while (true) { act(); kotlinx.coroutines.delay(120) }
            }
        }

        fun onKeyUp(enChar: Char? = null, hidCode: Int = 0) {
            fnRepeat?.cancel()
            if (enChar != null) {
                val lower = enChar.lowercaseChar()
                val code = HidKeys.ascii[lower]?.second ?: return
                kb.keyUp(code)
            } else if (hidCode != 0) {
                Log.d("FNBUG", "onKeyUp code=$hidCode")
                kb.keyUp(hidCode)
            }
        }

        // ponytail: modifiers hold like hardware — lone tap still clicks (Super opens launcher)
        fun onModDown(setVar: (Boolean) -> Unit) = setVar(true)
        fun onModUp(getVar: () -> Boolean, setVar: (Boolean) -> Unit, modBit: Int) {
            if (!modUsed && getVar()) kb.modTap(modBit)
            setVar(false)
            modUsed = false
        }

        BackHandler(enabled = settings) { settings = false; buzz() }
        BackHandler(enabled = !settings) { moveTaskToBack(true) }
        MaterialTheme(colorScheme = MonoDark, typography = type, shapes = MsShapes) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    // Full-screen keyboard (bigger keys)
                    if (!settings) {
                        if (padMode) {
                            Column(Modifier.weight(1f)) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize().padding(4.dp).background(Color(0xFF1E1E22))
                                            .border(1.dp, MaterialTheme.colorScheme.outline)
                                            .pointerInput(Unit) {
                                                awaitEachGesture {
                                                    var last: Offset? = null
                                                    var startFingers = 0
                                                    var moved = 0f
                                                    val t0 = android.os.SystemClock.uptimeMillis()
                                                    var downX = 0f
                                                    var downY = 0f
                                                    var dragging = false
                                                    var curCount = 0
                                                    var accX = 0f
                                                    var accY = 0f
                                                    var lastSend = 0L
                                                    val sens = padSpeed / 100f * 1.75f
                                                    val sdir = if (padNatural) -1 else 1
                                                    while (true) {
                                                        val now0 = android.os.SystemClock.uptimeMillis()
                                                        val ev = if (!dragging && curCount == 1 && moved < 10 &&
                                                            startFingers == 1 && now0 - t0 < 500
                                                        ) {
                                                            withTimeoutOrNull((500 - (now0 - t0)).coerceAtLeast(0L)) { awaitPointerEvent() }
                                                        } else {
                                                            awaitPointerEvent()
                                                        }
                                                        if (ev == null) {
                                                            if (startFingers == 1) {
                                                                dragging = true
                                                                kb.mouseMove(0, 0, HidKeyboard.Mouse.LEFT)
                                                            }
                                                            continue
                                                        }
                                                        val pressed = ev.changes.filter { it.pressed }
                                                        if (pressed.isEmpty()) break
                                                        curCount = pressed.size
                                                        val cur = Offset(
                                                            pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size,
                                                            pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size
                                                        )
                                                        if (last == null) {
                                                            startFingers = pressed.size
                                                            downX = cur.x
                                                            downY = cur.y
                                                            if (pressed.size == 1 && t0 - lastTapUp < 400 &&
                                                                kotlin.math.abs(downX - lastTapX) + kotlin.math.abs(downY - lastTapY) < 48) {
                                                                dragging = true
                                                                kb.mouseMove(0, 0, HidKeyboard.Mouse.LEFT)
                                                            }
                                                        } else {
                                                            val dx = (cur.x - last.x) * sens
                                                            val dy = (cur.y - last.y) * sens
                                                            moved += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                                                            val now = android.os.SystemClock.uptimeMillis()
                                                            if (pressed.size == 1) {
                                                                accX += dx
                                                                accY += dy
                                                                if (now - lastSend >= 8) {
                                                                    val sx = accX.toInt()
                                                                    val sy = accY.toInt()
                                                                    if (sx != 0 || sy != 0) {
                                                                        kb.mouseMove(sx, sy, padBtnMask or if (dragging) HidKeyboard.Mouse.LEFT else 0)
                                                                        accX -= sx
                                                                        accY -= sy
                                                                        lastSend = now
                                                                    }
                                                                }
                                                            } else if (pressed.size == 2) {
                                                                accY += dy
                                                                if (now - lastSend >= 16) {
                                                                    var w = 0
                                                                    while (accY <= -24) {
                                                                        w += sdir
                                                                        accY += 24
                                                                    }
                                                                    while (accY >= 24) {
                                                                        w -= sdir
                                                                        accY -= 24
                                                                    }
                                                                    if (w != 0) {
                                                                        kb.mouseScroll(w)
                                                                        lastSend = now
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        last = cur
                                                        pressed.forEach { it.consume() }
                                                    }
                                                    val now = android.os.SystemClock.uptimeMillis()
                                                    val lx = last?.x ?: 0f
                                                    val ly = last?.y ?: 0f
                                                    if (dragging) {
                                                        kb.mouseMove(0, 0, 0)
                                                        if (moved < 14 && now - t0 < 400) {
                                                            buzz()
                                                            kb.mouseClick(HidKeyboard.Mouse.LEFT)
                                                            lastTapUp = now
                                                            lastTapX = lx
                                                            lastTapY = ly
                                                        }
                                                    } else if (moved < 14 && now - t0 < 400) {
                                                        val btn = when (startFingers) {
                                                            2 -> HidKeyboard.Mouse.RIGHT
                                                            3 -> HidKeyboard.Mouse.MID
                                                            else -> HidKeyboard.Mouse.LEFT
                                                        }
                                                        buzz()
                                                        kb.mouseClick(btn)
                                                        if (startFingers == 1 && now - lastTapUp < 400 &&
                                                            kotlin.math.abs(lx - lastTapX) + kotlin.math.abs(ly - lastTapY) < 48) {
                                                            kb.mouseClick(HidKeyboard.Mouse.LEFT)
                                                        }
                                                        lastTapUp = now
                                                        lastTapX = lx
                                                        lastTapY = ly
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                        content = { }
                                    )
                                    FilledTonalButton(
                                        shape = RoundedCornerShape(0.dp),
                                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp).size(64.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        onClick = { padMode = false; buzz(); kb.mouseMove(0, 0, 0) },
                                        content = {
                                            Icon(Icons.Filled.Keyboard, contentDescription = "keys")
                                        }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    content = {
                                        K("Left", 1f, active = padBtnMask and HidKeyboard.Mouse.LEFT != 0,
                                            onDown = { padBtnMask = padBtnMask or HidKeyboard.Mouse.LEFT },
                                            onUp = { padBtnMask = padBtnMask and HidKeyboard.Mouse.LEFT.inv() })
                                        K("Right", 1f, active = padBtnMask and HidKeyboard.Mouse.RIGHT != 0,
                                            onDown = { padBtnMask = padBtnMask or HidKeyboard.Mouse.RIGHT },
                                            onUp = { padBtnMask = padBtnMask and HidKeyboard.Mouse.RIGHT.inv() })
                                    }
                                )
                            }
                        } else Column(Modifier.weight(1f).padding(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        KRow {
                            K("Esc", 1f, onDown = { onKeyDown(hidCode = HidKeys.ESC) }, onUp = { onKeyUp(hidCode = HidKeys.ESC) })
                            listOf("F1" to HidKeys.F1, "F2" to HidKeys.F2, "F3" to HidKeys.F3, "F4" to HidKeys.F4,
                                "F5" to HidKeys.F5, "F6" to HidKeys.F6, "F7" to HidKeys.F7, "F8" to HidKeys.F8,
                                "F9" to HidKeys.F9, "F10" to HidKeys.F10, "F11" to HidKeys.F11, "F12" to HidKeys.F12
                            ).forEach { (l, c) ->
                                val m = if (!fn) fnMedia[c] else null
                                if (m != null) K("", icon = m.second, onDown = { onFKey(c) }, onUp = { onKeyUp(hidCode = c) })
                                else if (!fn && c == HidKeys.F7) K("", icon = Icons.Filled.Settings, onDown = { onFKey(c) }, onUp = { onKeyUp(hidCode = c) })
                                else if (!fn && c == HidKeys.F8) K(if (arabic) "ARA" else "ENG", onDown = { onFKey(c) }, onUp = { onKeyUp(hidCode = c) })
                                else if (!fn && c == HidKeys.F9) K("", icon = Icons.Filled.TouchApp, onDown = { onFKey(c) }, onUp = { onKeyUp(hidCode = c) })
                                else K(l, onDown = { onFKey(c) }, onUp = { onKeyUp(hidCode = c) })
                            }
                            K("Del", 1f, onDown = { onKeyDown(hidCode = HidKeys.DEL) }, onUp = { onKeyUp(hidCode = HidKeys.DEL) })
                        }
                        KRow {
                            "`1234567890-=".forEach { c -> K(label(c), onDown = { onKeyDown(enChar = c) }, onUp = { onKeyUp(enChar = c) }) }
                            K("", 2f, icon = Icons.Filled.Backspace, onDown = { onKeyDown(hidCode = HidKeys.BS) }, onUp = { onKeyUp(hidCode = HidKeys.BS) })
                        }
                        KRow {
                            K("Tab", 1.5f, onDown = { onKeyDown(hidCode = HidKeys.TAB) }, onUp = { onKeyUp(hidCode = HidKeys.TAB) })
                            "qwertyuiop[]".forEach { c -> K(label(c), onDown = { onKeyDown(enChar = c) }, onUp = { onKeyUp(enChar = c) }) }
                            K(label('\\'), 1.5f, onDown = { onKeyDown(enChar = '\\') }, onUp = { onKeyUp(enChar = '\\') })
                        }
                        KRow {
                            K("Caps", 1.75f, active = caps, onClick = {
                                scope.launch(Dispatchers.IO) { kb.tap(0, HidKeys.CAPS) }; caps = !caps
                            })
                            "asdfghjkl;'".forEach { c -> K(label(c), onDown = { onKeyDown(enChar = c) }, onUp = { onKeyUp(enChar = c) }) }
                            K("", 2.25f, icon = Icons.Filled.KeyboardReturn, onDown = { onKeyDown(hidCode = HidKeys.ENTER) }, onUp = { onKeyUp(hidCode = HidKeys.ENTER) })
                        }
                        KRow {
                            K("Shift", 2.25f, active = shift, onDown = { onModDown({ shift = it }) }, onUp = { onModUp({ shift }, { shift = it }, HidKeys.LSHIFT) })
                            "zxcvbnm,./".forEach { c -> K(label(c), onDown = { onKeyDown(enChar = c) }, onUp = { onKeyUp(enChar = c) }) }
                            K("Shift", 2.75f, active = shift, onDown = { onModDown({ shift = it }) }, onUp = { onModUp({ shift }, { shift = it }, HidKeys.LSHIFT) })
                        }
                        KRow {
                            K("Ctrl", 1.25f, active = ctrl, onDown = { onModDown({ ctrl = it }) }, onUp = { onModUp({ ctrl }, { ctrl = it }, HidKeys.LCTRL) })
                            K("Win", 1.25f, active = win, onDown = { onModDown({ win = it }) }, onUp = { onModUp({ win }, { win = it }, HidKeys.LGUI) })
                            K("Alt", 1.25f, active = alt, onDown = { onModDown({ alt = it }) }, onUp = { onModUp({ alt }, { alt = it }, HidKeys.LALT) })
                            K("Fn", 1.5f, active = fn,
                                onDown = { Log.d("FNBUG", "fn down"); fn = true; fnHeld = true },
                                onUp = { Log.d("FNBUG", "fn up"); fn = false; fnHeld = false; fnUsed = false })
                            K("Space", 6.25f, onDown = { onKeyDown(enChar = ' ') }, onUp = { onKeyUp(enChar = ' ') })
                            K("", icon = Icons.Filled.KeyboardArrowLeft, onDown = { onKeyDown(hidCode = HidKeys.LEFT) }, onUp = { onKeyUp(hidCode = HidKeys.LEFT) })
                            K("", icon = Icons.Filled.KeyboardArrowRight, onDown = { onKeyDown(hidCode = HidKeys.RIGHT) }, onUp = { onKeyUp(hidCode = HidKeys.RIGHT) })
                            K("", icon = Icons.Filled.KeyboardArrowUp, onDown = { onKeyDown(hidCode = HidKeys.UP) }, onUp = { onKeyUp(hidCode = HidKeys.UP) })
                            K("", icon = Icons.Filled.KeyboardArrowDown, onDown = { onKeyDown(hidCode = HidKeys.DOWN) }, onUp = { onKeyUp(hidCode = HidKeys.DOWN) })
                        }
                    }
                    }
                    // Settings page — Monosched design language (timetable app):
                    // true black, #1B1E1E cards, default font, Bold titles, white actions.
                    if (settings) {
                        val msTitle = MaterialTheme.typography.titleLarge.copy(fontFamily = grotesk, fontWeight = FontWeight.Bold)
                        val msBody = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Default)
                        val msBtn = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium)
                        val msCard = CardDefaults.elevatedCardColors(containerColor = MsCard)
                        val msWhiteBtn = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                        Surface(color = Color.Black, modifier = Modifier.weight(1f)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                FilledTonalButton(
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.size(64.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    onClick = { settings = false; buzz() },
                                    content = {
                                        Icon(Icons.Filled.Keyboard, contentDescription = "keys")
                                    }
                                )
                                Button(
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.size(64.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    onClick = {
                                        autoCon = !autoCon
                                        getSharedPreferences("arabickb", MODE_PRIVATE).edit().putBoolean("autocon", autoCon).apply()
                                        buzz()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (autoCon) Color.White else Color.Black,
                                        contentColor = if (autoCon) Color.Black else Color.White
                                    ),
                                    border = if (autoCon) null else BorderStroke(1.dp, Color.White),
                                    content = {
                                        Text("A", style = msBtn.copy(fontSize = 28.sp, fontWeight = FontWeight.Normal))
                                    }
                                )
                            }
                            ElevatedCard(Modifier.fillMaxWidth(), colors = msCard) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Connected", style = msTitle)
                                    if (kb.connectedName != null) {
                                        Row(Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("● ${kb.connectedName}", style = msBody, color = Color.White)
                                                Text(kb.connectedAddress ?: "", style = msBody, color = MsMuted)
                                            }
                                            OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = {
                                                scope.launch(Dispatchers.IO) { kb.disconnect() }
                                            },
                                                border = BorderStroke(1.dp, Color.White),
                                                modifier = Modifier.width(136.dp)
                                            ) { Text("Disconnect", style = msBtn, color = Color.White) }
                                        }
                                    } else {
                                        Text("○ Not connected", style = msBody, color = MsMuted)
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth(), colors = msCard) {
                                val available = remember(devices, foundDevices, kb.connectedAddress) {
                                    val seen = LinkedHashMap<String, BluetoothDevice>()
                                    (devices + foundDevices.values).forEach { d ->
                                        if (d.address != kb.connectedAddress) seen.putIfAbsent(d.address, d)
                                    }
                                    seen.values.toList()
                                }
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Available devices", style = msTitle, modifier = Modifier.weight(1f))
                                        if (discovering) Text("Scanning…", style = msBody, color = MsMuted)
                                    }
                                    if (available.isEmpty()) Text("Nothing nearby — keep Bluetooth on.", style = msBody, color = MsMuted)
                                    available.forEach { d ->
                                        val name = try { d.name ?: d.address } catch (_: SecurityException) { d.address }
                                        Row(Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(name, style = msBody)
                                                Text(d.address, style = msBody, color = MsMuted)
                                            }
                                            OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = {
                                                scope.launch(Dispatchers.IO) { kb.connect(d) }
                                                settings = false
                                            },
                                                border = BorderStroke(1.dp, Color.White),
                                                modifier = Modifier.width(136.dp)
                                            ) { Text("Connect", style = msBtn, color = Color.White) }
                                        }
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth(), colors = msCard) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Haptic feedback: $haptic%", style = msTitle)
                                    Slider(value = haptic.toFloat(), onValueChange = {
                                        haptic = it.toInt()
                                        hapticStrength = haptic
                                        getSharedPreferences("arabickb", MODE_PRIVATE).edit().putInt("haptic", haptic).apply()
                                        buzz()
                                    }, valueRange = 0f..100f, steps = 9,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = Color.White,
                                            inactiveTrackColor = MsOutline
                                        ))
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth(), colors = msCard) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Touchpad speed: $padSpeed%", style = msTitle)
                                    Slider(value = padSpeed.toFloat(), onValueChange = {
                                        padSpeed = it.toInt()
                                        getSharedPreferences("arabickb", MODE_PRIVATE).edit().putInt("padspeed", padSpeed).apply()
                                    }, valueRange = 50f..200f, steps = 5,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = Color.White,
                                            inactiveTrackColor = MsOutline
                                        ))
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Natural scroll", modifier = Modifier.weight(1f), style = msBody)
                                        Switch(checked = padNatural, onCheckedChange = {
                                            padNatural = it
                                            getSharedPreferences("arabickb", MODE_PRIVATE).edit().putBoolean("padnatural", it).apply()
                                        },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.Black,
                                                checkedTrackColor = Color.White,
                                                checkedBorderColor = Color.White,
                                                uncheckedThumbColor = MsMuted,
                                                uncheckedTrackColor = MsOutline,
                                                uncheckedBorderColor = MsOutline
                                            ))
                                    }
                                }
                        }
                        }
                    }
                }
            }
        }
    }
    }

    @Composable
    private fun ColumnScope.KRow(content: @Composable RowScope.() -> Unit) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), content = content)
    }
@Composable
    private fun RowScope.K(
        label: String, weight: Float = 1f,
        active: Boolean = false,
        onClick: () -> Unit = {},
        onDown: (() -> Unit)? = null,
        onUp: (() -> Unit)? = null,
        icon: ImageVector? = null
    ) {
        var isDown by remember { mutableStateOf(false) }
        val colors = if (isDown || active) ButtonDefaults.buttonColors()
        else ButtonDefaults.filledTonalButtonColors()

        Box(
            modifier = Modifier
                .weight(weight)
                .fillMaxHeight()
                .padding(0.dp)
                .background(colors.containerColor)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        isDown = true
                        buzz()
                        onDown?.invoke()
                        waitForUpOrCancellation()
                        isDown = false
                        onUp?.invoke()
                        onClick()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = label.ifEmpty { null },
                    modifier = Modifier.size(26.dp), tint = colors.contentColor)
            } else {
                Text(label, fontSize = 20.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = colors.contentColor)
            }
        }
    }
}
