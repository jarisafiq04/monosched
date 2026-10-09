package com.example.arabickb

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

// ponytail: tablet pretends to be a Bluetooth keyboard (HID). No server app needed on Linux.
// Supports key hold/repeat: send key-down on press, key-up on release. Linux handles repeat.
class HidKeyboard(private val ctx: Context) {
    var status by mutableStateOf("idle")
        private set
    var connectedName by mutableStateOf<String?>(null)
        private set
    var connectedAddress by mutableStateOf<String?>(null)
        private set
    var registered by mutableStateOf(false)
        private set

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val exec = Executors.newSingleThreadExecutor()

    // Track currently held keys for proper HID state
    private val heldKeys = mutableSetOf<Int>()
    private var heldMods = 0
    var reportsSent by mutableStateOf(0)
        private set

    // Standard boot-keyboard report descriptor (8-byte reports)
    // ponytail: EVERY collection carries an ID (kbd=1, mouse=2, consumer=3).
    // ID-less keyboard reports were misfiled once numbered collections existed.
    private val descriptor = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x06.toByte(), 0xA1.toByte(),
        0x01.toByte(), 0x85.toByte(), 0x01.toByte(), 0x05.toByte(), 0x07.toByte(), 0x19.toByte(), 0xE0.toByte(),
        0x29.toByte(), 0xE7.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(),
        0x01.toByte(), 0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x02.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(),
        0x08.toByte(), 0x81.toByte(), 0x03.toByte(), 0x95.toByte(), 0x05.toByte(),
        0x75.toByte(), 0x01.toByte(), 0x05.toByte(), 0x08.toByte(), 0x19.toByte(),
        0x01.toByte(), 0x29.toByte(), 0x05.toByte(), 0x91.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x03.toByte(), 0x91.toByte(),
        0x03.toByte(), 0x95.toByte(), 0x06.toByte(), 0x75.toByte(), 0x08.toByte(),
        0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x65.toByte(), 0x05.toByte(),
        0x07.toByte(), 0x19.toByte(), 0x00.toByte(), 0x29.toByte(), 0x73.toByte(),
        0x81.toByte(), 0x00.toByte(), 0xC0.toByte(),
        // Consumer (media) collection, report ID 1: mic, vol-, vol+, mute, bri-(0x70!), bri+
        0x05.toByte(), 0x0C.toByte(), 0x09.toByte(), 0x01.toByte(),
        0xA1.toByte(), 0x01.toByte(), 0x85.toByte(), 0x03.toByte(),
        0x09.toByte(), 0xCF.toByte(), 0x09.toByte(), 0xEA.toByte(),
        0x09.toByte(), 0xE9.toByte(), 0x09.toByte(), 0xE2.toByte(),
        0x09.toByte(), 0x70.toByte(), 0x09.toByte(), 0x6F.toByte(),
        0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x06.toByte(),
        0x81.toByte(), 0x02.toByte(), 0x95.toByte(), 0x02.toByte(),
        0x81.toByte(), 0x03.toByte(), 0xC0.toByte(),
        // Mouse collection, report ID 2: buttons, X, Y, wheel
        0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x02.toByte(),
        0xA1.toByte(), 0x01.toByte(), 0x85.toByte(), 0x02.toByte(),
        0x09.toByte(), 0x01.toByte(), 0xA1.toByte(), 0x00.toByte(),
        0x05.toByte(), 0x09.toByte(), 0x19.toByte(), 0x01.toByte(),
        0x29.toByte(), 0x03.toByte(), 0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(), 0x75.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x03.toByte(), 0x81.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x05.toByte(), 0x81.toByte(), 0x03.toByte(),
        0xC0.toByte(),
        0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x30.toByte(),
        0x09.toByte(), 0x31.toByte(), 0x09.toByte(), 0x38.toByte(),
        0x15.toByte(), 0x81.toByte(), 0x25.toByte(), 0x7F.toByte(),
        0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x03.toByte(),
        0x81.toByte(), 0x06.toByte(), 0xC0.toByte()
    )

    // Fn+Fx bitmask (matches descriptor order above)
    object Fn {
        const val MIC = 0x01; const val VOLDN = 0x02; const val VOLUP = 0x04
        const val MUTE = 0x08; const val BRIDN = 0x10; const val BRIUP = 0x20
    }

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(plugged: BluetoothDevice?, registered: Boolean) {
            this@HidKeyboard.registered = registered
            status = if (registered) "registered — pair from Linux Bluetooth, then tap a device below to connect"
                     else "unregistered"
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            @SuppressLint("MissingPermission")
            val name = try { device.name } catch (_: SecurityException) { device.address }
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device; connectedName = name; connectedAddress = device.address
                    ctx.getSharedPreferences("arabickb", android.content.Context.MODE_PRIVATE)
                        .edit().putString("last_dev", device.address).apply()
                    status = "connected to $name — type below"
                }
                BluetoothProfile.STATE_CONNECTING -> status = "connecting to $name…"
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) { host = null; connectedName = null }
                    status = "disconnected"
                }
                else -> {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun init() {
        if (Build.VERSION.SDK_INT < 28) { status = "needs Android 9+"; return }
        if (adapter == null) { status = "no bluetooth adapter"; return }
        status = "binding HID profile…"
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                register()
            }
            override fun onServiceDisconnected(p: Int) { hid = null; registered = false }
        }, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "ArabicKB Tablet", "Tablet as keyboard", "example",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD, descriptor
        )
        try { h.registerApp(sdp, null, null, exec, cb) }
        catch (e: Exception) { status = "register failed: ${e.message}" }
    }

    // ponytail: auto-reconnect target — last device that worked
    @SuppressLint("MissingPermission")
    fun lastDevice(): BluetoothDevice? {
        val addr = ctx.getSharedPreferences("arabickb", android.content.Context.MODE_PRIVATE)
            .getString("last_dev", null) ?: return null
        return try { adapter?.bondedDevices?.firstOrNull { it.address == addr } }
        catch (_: SecurityException) { null }
    }

    @SuppressLint("MissingPermission")
    fun bonded(): List<BluetoothDevice> =
        try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }

    @SuppressLint("MissingPermission")
    fun connect(d: BluetoothDevice): Boolean {
        val h = hid ?: return false.also { status = "HID not ready yet" }
        return try {
            if (h.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) {
                host = d; connectedName = d.name; connectedAddress = d.address; true
            } else h.connect(d)
        } catch (e: Exception) { status = "connect failed: ${e.message}"; false }
    }

    @SuppressLint("MissingPermission")
    fun isAlive(): Boolean {
        return try {
            val h = hid ?: return false
            val dev = host ?: return false
            h.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTED
        } catch (_: Exception) { false }
    }

    fun onLinkLost() {
        releaseAll()
        host = null; connectedName = null; connectedAddress = null
        status = "link lost — tap Setup → Connect, or pair V14 in Bluetooth settings"
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val h = hid ?: return
        releaseAll()
        host?.let { try { h.disconnect(it) } catch (_: Exception) {} }
        host = null; connectedName = null; connectedAddress = null; status = "disconnected"
    }

    // ---- low-level HID ----
    private fun sendReport(mod: Int, keys: ByteArray) {
        val dev = host ?: return
        val h = hid ?: return
        try {
            val report = ByteArray(8)
            report[0] = mod.toByte()
            report[1] = 0
            // Copy keys starting at index 2
            var i = 0
            while (i < keys.size && i + 2 < report.size) {
                report[i + 2] = keys[i]
                i++
            }
            h.sendReport(dev, 1, report)
            reportsSent++
        } catch (_: Exception) {}
    }

    // ponytail: ALL keyboard traffic on one thread, in order. Press/release used
    // to race across pool threads: inverted pairs stuck keys down forever
    // (a stuck Super = every key becomes a bind = "dead keyboard").
    private fun keysSnapshot(): ByteArray {
        val keys = ByteArray(6)
        var i = 0
        heldKeys.forEach { k ->
            if (i < 6) { keys[i] = k.toByte(); i++ }
        }
        return keys
    }

    // Key down (hold) - Linux will auto-repeat
    fun keyDown(mod: Int, code: Int) {
        if (code == 0) return
        exec.execute {
            heldKeys.add(code)
            heldMods = mod
            sendReport(heldMods, keysSnapshot())
        }
    }

    // Key up
    fun keyUp(code: Int) {
        exec.execute {
            heldKeys.remove(code)
            if (heldKeys.isEmpty()) heldMods = 0
            sendReport(heldMods, keysSnapshot())
        }
    }

    // Release all held keys
    fun releaseAll() {
        exec.execute {
            heldKeys.clear()
            heldMods = 0
            sendReport(0, ByteArray(6))
        }
    }

    // Modifier-only press+release (lone Super tap opens Mango launcher)
    fun modTap(mod: Int) {
        exec.execute {
            sendReport(mod, ByteArray(6))
            sendReport(0, ByteArray(6))
        }
    }

    // Consumer/media key press+release (report ID 1)
    fun consumerTap(mask: Int) {
        exec.execute {
            val dev = host ?: return@execute
            val h = hid ?: return@execute
            try {
                h.sendReport(dev, 3, byteArrayOf(mask.toByte()))
                reportsSent++
                Thread.sleep(10)
                h.sendReport(dev, 3, byteArrayOf(0))
                reportsSent++
            } catch (_: Exception) {}
        }
    }

    object Mouse { const val LEFT = 0x01; const val RIGHT = 0x02; const val MID = 0x04 }

    private fun mouseReport(btn: Int, dx: Int, dy: Int, wheel: Int) {
        val dev = host ?: return
        val h = hid ?: return
        try {
            h.sendReport(dev, 2, byteArrayOf(btn.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))
        } catch (_: Exception) {}
    }

    // ponytail: off-main-thread ordered sends — touchpad fires these at 100+Hz
    fun mouseMove(dx: Int, dy: Int, buttons: Int = 0) {
        val x = dx.coerceIn(-127, 127); val y = dy.coerceIn(-127, 127)
        exec.execute { mouseReport(buttons, x, y, 0) }
    }

    fun mouseScroll(d: Int) {
        val w = d.coerceIn(-127, 127)
        exec.execute { mouseReport(0, 0, 0, w) }
    }

    fun mouseClick(btn: Int) {
        exec.execute { mouseReport(btn, 0, 0, 0); mouseReport(0, 0, 0, 0) }
    }

    // Tap (for keys that shouldn't repeat like modifiers)
    fun tap(mod: Int, code: Int) {
        if (code == 0) return
        exec.execute {
            sendReport(mod, byteArrayOf(code.toByte(), 0, 0, 0, 0, 0))
            sendReport(0, ByteArray(6))
        }
    }
}

object HidKeys {
    const val LCTRL = 0x01; const val LSHIFT = 0x02; const val LALT = 0x04; const val LGUI = 0x08
    const val ENTER = 0x28; const val ESC = 0x29; const val BS = 0x2A; const val TAB = 0x2B; const val SPACE = 0x2C
    const val LEFT = 0x50; const val RIGHT = 0x4F; const val UP = 0x52; const val DOWN = 0x51
    const val HOME = 0x4A; const val END = 0x4D; const val DEL = 0x4C
    const val CAPS = 0x39
    const val F1 = 0x3A; const val F2 = 0x3B; const val F3 = 0x3C; const val F4 = 0x3D
    const val F5 = 0x3E; const val F6 = 0x3F; const val F7 = 0x40; const val F8 = 0x41
    const val F9 = 0x42; const val F10 = 0x43; const val F11 = 0x44; const val F12 = 0x45
    const val PGUP = 0x4B; const val PGDN = 0x4E

    // char -> (modifier, hidCode)
    val ascii: Map<Char, Pair<Int, Int>> by lazy {
        val m = mutableMapOf<Char, Pair<Int, Int>>()
        ('a'..'z').forEachIndexed { i, c -> m[c] = 0 to (0x04 + i) }
        ('A'..'Z').forEachIndexed { i, c -> m[c] = LSHIFT to (0x04 + i) }
        "1234567890".forEachIndexed { i, c ->
            m[c] = 0 to (if (i < 9) 0x1E + i else 0x27)
        }
        m[' '] = 0 to SPACE; m['\n'] = 0 to ENTER; m['\t'] = 0 to TAB
        m['-'] = 0 to 0x2D; m['_'] = LSHIFT to 0x2D
        m['='] = 0 to 0x2E; m['+'] = LSHIFT to 0x2E
        m['['] = 0 to 0x2F; m['{'] = LSHIFT to 0x2F
        m[']'] = 0 to 0x30; m['}'] = LSHIFT to 0x30
        m['\\'] = 0 to 0x31; m['|'] = LSHIFT to 0x31
        m[';'] = 0 to 0x33; m[':'] = LSHIFT to 0x33
        m['\''] = 0 to 0x34; m['"'] = LSHIFT to 0x34
        m['`'] = 0 to 0x35; m['~'] = LSHIFT to 0x35
        m[','] = 0 to 0x36; m['<'] = LSHIFT to 0x36
        m['.'] = 0 to 0x37; m['>'] = LSHIFT to 0x37
        m['/'] = 0 to 0x38; m['?'] = LSHIFT to 0x38
        m['!'] = LSHIFT to 0x1E; m['@'] = LSHIFT to 0x1F
        m['#'] = LSHIFT to 0x20; m['$'] = LSHIFT to 0x21
        m['%'] = LSHIFT to 0x22; m['^'] = LSHIFT to 0x23
        m['&'] = LSHIFT to 0x24; m['*'] = LSHIFT to 0x25
        m['('] = LSHIFT to 0x26; m[')'] = LSHIFT to 0x27
        m
    }
}