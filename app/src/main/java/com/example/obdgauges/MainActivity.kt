package com.example.obdgauges

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Gauge definitions. Add or change gauges here.
// key must match a key handled in ElmSource/DemoSource.
// ---------------------------------------------------------------------------
class GaugeSpec(
    val key: String,
    val label: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val decimals: Int,
    val isWarn: (Float) -> Boolean
)

val GAUGES = listOf(
    GaugeSpec("rpm", "RPM", "rpm", 0f, 6000f, 0) { it > 5000 },
    GaugeSpec("speed", "SPEED", "mph", 0f, 100f, 0) { it > 80 },
    GaugeSpec("coolant", "COOLANT", "°F", 100f, 260f, 0) { it > 225 },
    GaugeSpec("intake", "INTAKE AIR", "°F", 0f, 200f, 0) { it > 150 },
    GaugeSpec("load", "ENGINE LOAD", "%", 0f, 100f, 0) { it > 90 },
    GaugeSpec("volts", "BATTERY", "V", 10f, 16f, 1) { it < 12.2f || it > 15f },
)

// Standard OBD-II mode 01 PIDs
val PIDS = mapOf(
    "rpm" to "0C",
    "speed" to "0D",
    "coolant" to "05",
    "intake" to "0F",
    "load" to "04",
    "throttle" to "11",
)

fun decodePid(key: String, d: IntArray): Float? = when (key) {
    "rpm" -> if (d.size >= 2) (d[0] * 256 + d[1]) / 4f else null
    "speed" -> d.getOrNull(0)?.let { it * 0.621371f }                 // km/h -> mph
    "coolant", "intake" -> d.getOrNull(0)?.let { (it - 40) * 9f / 5f + 32f } // C -> F
    "load", "throttle" -> d.getOrNull(0)?.let { it * 100f / 255f }
    else -> null
}

fun dtcString(v: Int): String {
    val letter = "PCBU"[(v shr 14) and 3]
    val digit = (v shr 12) and 3
    return "$letter$digit" + String.format("%03X", v and 0xFFF)
}

/** Parses a mode 03 (prefix "43") or mode 07 (prefix "47") response on CAN. */
fun parseDtcs(resp: String, prefix: String): List<String> {
    val messages = mutableListOf<StringBuilder>()
    val framePrefix = Regex("^[0-9A-F]:")
    for (raw in resp.split('\r', '\n')) {
        var line = raw.replace(" ", "").uppercase()
        if (line.isEmpty()) continue
        var frameIndex = -1
        if (framePrefix.containsMatchIn(line)) {
            frameIndex = line[0].digitToInt(16)
            line = line.substring(2)
        }
        if (!line.matches(Regex("[0-9A-F]+"))) continue
        if (frameIndex <= 0 && line.startsWith(prefix)) {
            messages.add(StringBuilder(line.substring(prefix.length)))
        } else if (frameIndex > 0 && messages.isNotEmpty()) {
            messages.last().append(line)
        }
    }
    val codes = mutableListOf<String>()
    for (m in messages) {
        val hex = m.toString()
        if (hex.length < 2) continue
        val count = hex.substring(0, 2).toInt(16)
        var i = 2
        var n = 0
        while (i + 4 <= hex.length && n < count) {
            val v = hex.substring(i, i + 4).toInt(16)
            if (v != 0) codes.add(dtcString(v))
            i += 4
            n++
        }
    }
    return codes.distinct()
}

// ---------------------------------------------------------------------------
// Data sources
// ---------------------------------------------------------------------------
interface DataSource {
    fun open()
    fun read(key: String): Float?
    fun readCodes(): List<String>
    fun readPendingCodes(): List<String>
    fun clearCodes(): Boolean
    fun close()
}

/** Talks to an ELM327-compatible adapter (vLinker, OBDLink, etc.) over Bluetooth Classic. */
@SuppressLint("MissingPermission")
class ElmSource(private val device: BluetoothDevice) : DataSource {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open() {
        val s = try {
            device.createRfcommSocketToServiceRecord(sppUuid).also { it.connect() }
        } catch (e: IOException) {
            // Some adapters only accept insecure connections
            device.createInsecureRfcommSocketToServiceRecord(sppUuid).also { it.connect() }
        }
        socket = s
        input = s.inputStream
        output = s.outputStream

        command("ATZ", 3000)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) command(c)
        val probe = command("0100", 10000).uppercase()
        if (probe.contains("UNABLE") || probe.contains("ERROR") || !probe.contains("41")) {
            throw IOException("Adapter connected but the truck didn't answer. Is the ignition on?")
        }
    }

    /** Sends a command and returns everything up to the '>' prompt. Returns "" on timeout. */
    private fun command(cmd: String, timeoutMs: Long = 2000): String {
        val inp = input ?: throw IOException("Not connected")
        val out = output ?: throw IOException("Not connected")
        while (inp.available() > 0) inp.read()
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val b = inp.read()
                if (b < 0) throw IOException("Connection closed")
                val ch = b.toChar()
                if (ch == '>') return sb.toString()
                sb.append(ch)
            } else {
                Thread.sleep(5)
            }
        }
        return ""
    }

    private fun dataBytes(resp: String, prefix: String): IntArray? {
        for (raw in resp.split('\r', '\n')) {
            val line = raw.replace(" ", "").uppercase()
            if (line.startsWith(prefix) && line.matches(Regex("[0-9A-F]+"))) {
                val hex = line.substring(prefix.length)
                return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            }
        }
        return null
    }

    override fun read(key: String): Float? {
        if (key == "volts") {
            return command("ATRV").replace(Regex("[^0-9.]"), "").toFloatOrNull()
        }
        val pid = PIDS[key] ?: return null
        val bytes = dataBytes(command("01$pid"), "41$pid") ?: return null
        return decodePid(key, bytes)
    }

    override fun readCodes() = parseDtcs(command("03", 5000), "43")
    override fun readPendingCodes() = parseDtcs(command("07", 5000), "47")
    override fun clearCodes() = command("04", 5000).replace(" ", "").contains("44")

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }
}

/** Fake data so you can test the UI without an adapter. */
class DemoSource : DataSource {
    private var t = 0.0
    override fun open() { Thread.sleep(300) }
    override fun read(key: String): Float? {
        Thread.sleep(40)
        t += 0.03
        return when (key) {
            "rpm" -> (750 + 2800 * (0.5 + 0.5 * sin(t))).toFloat()
            "speed" -> (35 + 30 * sin(t * 0.7)).toFloat()
            "coolant" -> (198 + 6 * sin(t * 0.2)).toFloat()
            "intake" -> (95 + 10 * sin(t * 0.3)).toFloat()
            "load" -> (40 + 35 * sin(t * 1.3)).toFloat()
            "volts" -> (14.1 + 0.2 * sin(t)).toFloat()
            else -> null
        }
    }
    override fun readCodes() = listOf("P0171")
    override fun readPendingCodes() = listOf("P0420")
    override fun clearCodes() = true
    override fun close() {}
}

// ---------------------------------------------------------------------------
// Gauge view
// ---------------------------------------------------------------------------
class GaugeView(ctx: Context, private val spec: GaugeSpec) : View(ctx) {
    private var value: Float? = null
    private val rect = RectF()
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val warnColor = Color.rgb(255, 82, 82)
    private val okColor = Color.rgb(79, 195, 247)

    fun setValue(v: Float?) {
        value = v
        invalidate()
    }

    private fun format(v: Float?): String = when {
        v == null -> "--"
        spec.decimals == 0 -> v.roundToInt().toString()
        else -> String.format("%.1f", v)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w / 2f, h / 1.7f) * 0.78f
        val cx = w / 2f
        val cy = h / 2f + r * 0.12f
        rect.set(cx - r, cy - r, cx + r, cy + r)

        arcPaint.strokeWidth = r * 0.13f
        arcPaint.color = Color.rgb(45, 45, 45)
        c.drawArc(rect, 150f, 240f, false, arcPaint)

        val v = value
        val warn = v != null && spec.isWarn(v)
        if (v != null) {
            val frac = ((v - spec.min) / (spec.max - spec.min)).coerceIn(0f, 1f)
            arcPaint.color = if (warn) warnColor else okColor
            if (frac > 0f) c.drawArc(rect, 150f, 240f * frac, false, arcPaint)
        }

        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.color = if (warn) warnColor else Color.WHITE
        textPaint.textSize = r * 0.42f
        c.drawText(format(v), cx, cy + r * 0.12f, textPaint)

        textPaint.typeface = Typeface.DEFAULT
        textPaint.color = Color.GRAY
        textPaint.textSize = r * 0.16f
        c.drawText(spec.unit, cx, cy + r * 0.38f, textPaint)

        textPaint.color = Color.LTGRAY
        textPaint.textSize = r * 0.17f
        c.drawText(spec.label, cx, cy + r * 0.85f, textPaint)
    }
}

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val gauges = mutableMapOf<String, GaugeView>()
    private lateinit var status: TextView

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var source: DataSource? = null
    @Volatile private var pendingAction: ((DataSource) -> Unit)? = null
    private var current: DataSource? = null
    private var connectedName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(16, 16, 16, 16)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 18f
            text = "Not connected"
        }
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button("Connect") { pickDevice() })
        bar.addView(button("Demo") { start(DemoSource(), "Demo mode") })
        bar.addView(button("Codes") { readCodes() })
        bar.addView(button("Stop") { stop(); status.text = "Not connected" })
        root.addView(bar)

        val grid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 2
        }
        GAUGES.forEachIndexed { i, spec ->
            val g = GaugeView(this, spec)
            gauges[spec.key] = g
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(i / 3, 1f),
                GridLayout.spec(i % 3, 1f)
            ).apply {
                width = 0
                height = 0
            }
            grid.addView(g, lp)
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        setPadding(40, 20, 40, 20)
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun ui(f: () -> Unit) = runOnUiThread { f() }

    // ---- Bluetooth ----
    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun pickDevice() {
        if (!hasBtPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Turn on Bluetooth first")
            return
        }
        val devices = try { adapter.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            toast("No paired devices. Pair your OBD adapter in Android Bluetooth settings first.")
            return
        }
        val names = devices.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose OBD adapter")
            .setItems(names) { _, which ->
                start(ElmSource(devices[which]), devices[which].name ?: "adapter")
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasBtPermission()) pickDevice() else toast("Bluetooth permission is needed to talk to the adapter")
    }

    // ---- Polling loop ----
    private fun start(src: DataSource, name: String) {
        stop()
        running = true
        val gen = ++generation
        current = src
        connectedName = name
        status.text = "Connecting to $name…"

        Thread {
            try {
                src.open()
                source = src
                ui { if (gen == generation) status.text = "Connected: $name" }
                while (running && gen == generation) {
                    for (spec in GAUGES) {
                        if (!running || gen != generation) break
                        val action = pendingAction
                        if (action != null) {
                            pendingAction = null
                            action(src)
                        }
                        val v = try {
                            src.read(spec.key)
                        } catch (e: IOException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        ui { gauges[spec.key]?.setValue(v) }
                    }
                }
            } catch (e: Exception) {
                ui { if (gen == generation) status.text = "Disconnected: ${e.message}" }
            } finally {
                try { src.close() } catch (_: Exception) {}
                if (gen == generation) source = null
            }
        }.start()
    }

    private fun stop() {
        running = false
        generation++
        pendingAction = null
        try { current?.close() } catch (_: Exception) {}
        current = null
        source = null
        for (g in gauges.values) g.setValue(null)
    }

    // ---- Trouble codes ----
    private fun readCodes() {
        if (source == null) {
            toast("Connect first (or try Demo)")
            return
        }
        status.text = "Reading codes…"
        pendingAction = { src ->
            val stored = src.readCodes()
            val pending = src.readPendingCodes()
            ui { showCodes(stored, pending) }
        }
    }

    private fun showCodes(stored: List<String>, pending: List<String>) {
        status.text = "Connected: $connectedName"
        val msg = buildString {
            append("Stored codes:\n")
            append(if (stored.isEmpty()) "  none\n" else stored.joinToString("\n") { "  $it" } + "\n")
            append("\nPending codes:\n")
            append(if (pending.isEmpty()) "  none" else pending.joinToString("\n") { "  $it" })
        }
        AlertDialog.Builder(this)
            .setTitle("Trouble codes")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear codes") { _, _ -> confirmClear() }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear codes?")
            .setMessage(
                "This turns off the check engine light and erases stored codes. " +
                    "Do it with the key on and engine off. It also resets readiness monitors, " +
                    "which matters if you have an emissions test coming up."
            )
            .setPositiveButton("Clear") { _, _ ->
                pendingAction = { src ->
                    val ok = src.clearCodes()
                    ui { toast(if (ok) "Codes cleared" else "Clear failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
