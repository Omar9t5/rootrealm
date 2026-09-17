package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager
import com.faroquetech.theme.RootRealmGlobalTheme

import com.faroquetech.rootrealm.core.ChargingController
import android.content.*
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import android.view.ViewGroup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class BatteryManagerActivity : BaseActivity() {
    private val bg get() = ThemeManager.current(this).background
    private val card get() = ThemeManager.current(this).surface
    private val white get() = ThemeManager.current(this).text
    private val gray get() = ThemeManager.current(this).secondary
    private val green get() = ThemeManager.current(this).success
    private val red get() = ThemeManager.current(this).error
    private lateinit var rootLayout: LinearLayout
    private lateinit var terminalOutput: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var pageScroll: ScrollView
    private lateinit var chargingControlDetection: TextView
    private var usbOnlineDiagnosticValue: TextView? = null
    private var wirelessOnlineDiagnosticValue: TextView? = null
    private val powerDiagnosticHandler = Handler(Looper.getMainLooper())
    private val powerDiagnosticRefreshRunnable = Runnable { refreshPowerDiagnostics() }
    // Coalesce charger broadcasts so connection handling cannot cause repeated redraws.
    private val chargingEventHandler = Handler(Looper.getMainLooper())
    private var pendingChargingEvent: Boolean? = null
    private val chargingEventRunnable = Runnable {
        val connected = pendingChargingEvent
        pendingChargingEvent = null
        if (connected != null) handleChargingConnectionEvent(connected)
    }
    private var usbFastChargingValueField: EditText? = null
    private var usbFastChargingStatusValue: TextView? = null
    private var usbFastChargingPath: String? = null
    private var pendingPageScrollY = 0
    private var pendingChargeRoastScrollY = 0
    private var chargingSupportLine = "> Charging control support: checking…"
    private val logBuffer = StringBuilder()
    private val wattageLockHandler = Handler(Looper.getMainLooper())
    private var wattageLockEnabled = false
    private var wattageLockWatts = 0
    private var wattageLockPath: String? = null
    private var wattageLockLastToast = 0L

    // Charging-personality terminal messages: tracks the last announced state so the
    // same message isn't reprinted on every broadcast/resume, only on actual changes.
    private enum class ChargeState { NOT_CHARGING, SLOW, NORMAL, FAST }
    private var lastChargeState: ChargeState? = null
    private var lastChargeWatts: Double? = null
    private var chargeStateReceiver: BroadcastReceiver? = null
    private val SLOW_CHARGE_THRESHOLD_W = 5.0
    private val FAST_CHARGE_THRESHOLD_W = 15.0

    // Upper charging monitor: the status indicator is separate from the roast terminal.
    // Only the emoji blinks; the terminal itself remains static and touch-scrollable.
    private lateinit var chargeStatusEmoji: TextView
    private lateinit var chargeStatusText: TextView
    private var chargeStatusAnimator: ObjectAnimator? = null
    private lateinit var chargeRoastOutput: TextView
    private lateinit var chargeRoastScroll: ScrollView
    private val chargeRoastHistory = mutableListOf<String>()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPageScrollY = savedInstanceState?.getInt("battery_manager_scroll_y", 0) ?: 0
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::pageScroll.isInitialized) outState.putInt("battery_manager_scroll_y", pageScroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        registerChargeStateReceiver()
        updateChargeStateMessage()
    }

    override fun onPause() {
        stopChargeStatusBlink()
        unregisterChargeStateReceiver()
        super.onPause()
    }

    override fun onDestroy(){
        wattageLockEnabled=false
        wattageLockHandler.removeCallbacksAndMessages(null)
        stopChargeStatusBlink()
        unregisterChargeStateReceiver()
        super.onDestroy()
    }

    /** Registers for charger-connect/disconnect and battery-state broadcasts so the
     *  terminal's charging-personality message updates automatically, without polling. */
    private fun registerChargeStateReceiver() {
        if (chargeStateReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> queueChargingConnectionEvent(true)
                    Intent.ACTION_POWER_DISCONNECTED -> queueChargingConnectionEvent(false)
                }
            }
        }
        chargeStateReceiver = receiver
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (_: Exception) { chargeStateReceiver = null }
    }

    private fun queueChargingConnectionEvent(connected: Boolean) {
        pendingChargingEvent = connected
        chargingEventHandler.removeCallbacks(chargingEventRunnable)
        chargingEventHandler.postDelayed(chargingEventRunnable, 120L)
    }

    private fun handleChargingConnectionEvent(connected: Boolean) {
        // Root/sysfs reads can block. Do them off the UI thread so the Battery Manager
        // never freezes or flickers while the charger is being connected.
        Thread {
            if (connected) {
                chargeRoastHistory.clear()
                lastChargeState = null
                lastChargeWatts = null
                updateChargeStateMessage()
                runOnUiThread { updateChargingControlDetection(null, null, null, null) }
                detectChargingControlOnPlug()
                refreshUsbFastChargingControl()
                powerDiagnosticHandler.postDelayed({ refreshUsbFastChargingControl() }, 350L)
                refreshBypassChargingInfo(true)
                schedulePowerDiagnosticsRefresh(180L)
            } else {
                chargeRoastHistory.clear()
                lastChargeState = null
                lastChargeWatts = null
                updateChargeStateMessage()
                runOnUiThread { updateChargingControlDetection(false, null, null) }
                refreshUsbFastChargingControl()
                refreshBypassChargingInfo(false)
                schedulePowerDiagnosticsRefresh(0L)
            }
        }.start()
    }

    private fun unregisterChargeStateReceiver() {
        chargeStateReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        chargeStateReceiver = null
        powerDiagnosticHandler.removeCallbacks(powerDiagnosticRefreshRunnable)
        chargingEventHandler.removeCallbacks(chargingEventRunnable)
        pendingChargingEvent = null
    }

    private fun schedulePowerDiagnosticsRefresh(delayMs: Long = 0L) {
        if (usbOnlineDiagnosticValue == null && wirelessOnlineDiagnosticValue == null) return
        powerDiagnosticHandler.removeCallbacks(powerDiagnosticRefreshRunnable)
        powerDiagnosticHandler.postDelayed(powerDiagnosticRefreshRunnable, delayMs)
    }

    /** Re-reads the live USB/wireless online nodes without rebuilding Battery Manager. */
    private fun refreshPowerDiagnostics() {
        if (usbOnlineDiagnosticValue == null && wirelessOnlineDiagnosticValue == null) return
        Thread {
            val usb = readRoot("/sys/class/power_supply/usb/online")
            val wireless = readRoot("/sys/class/power_supply/wireless/online")
            runOnUiThread {
                usbOnlineDiagnosticValue?.text = usb?.let { formatDiagnosticValue("USB Online", it) } ?: "Unavailable"
                wirelessOnlineDiagnosticValue?.text = wireless?.let { formatDiagnosticValue("Wireless Online", it) } ?: "Unavailable"
            }
        }.start()
    }

    private val connectedRoasts = listOf(
        "> Charger connected. Battery came back from the dead like a bad horror sequel. 🔌",
        "> Power detected. Battery: \"Oh NOW you show up.\"",
        "> Cable's in. Let the healing begin. ⚡",
        "> External power found. Battery stopped drafting its will.",
        "> Charger connected. Cavalry arrived fashionably late.",
        "> Plugged in. Battery just got bailed out of debt.",
        "> Power restored. Somebody remembered you exist.",
        "> Cable detected. Battery: \"I forgive you. For now.\"",
        "> Juice incoming. The IV drip of modern life. 🔋"
    )
    private val disconnectedRoasts = listOf(
        "> Charger yanked. Battery: \"Cool, just abandon me mid-sentence.\" 💀",
        "> Power gone. Back to rationing like it's the apocalypse.",
        "> Cable pulled. The honeymoon is over.",
        "> Unplugged. Battery is now aggressively conserving snacks.",
        "> Charger disconnected. Battery: \"We had ONE JOB.\"",
        "> Power yanked. Battery's back to budgeting like it's rent week.",
        "> Cable gone. This is the part of the movie where things go wrong.",
        "> Disconnected. Battery just switched to survival mode and a bad attitude."
    )
    private val fastRoasts = listOf(
        "> FAST CHARGE ACTIVE. Somebody floor it. 🔥",
        "> High-power mode. Battery's chugging electrons like it's happy hour.",
        "> Turbo charging. Skip-the-line energy.",
        "> FAST CHARGE: the charger finally stopped holding back.",
        "> Warp speed engaged. Battery's not even mad.",
        "> Ludicrous speed. Battery didn't even ask for this much.",
        "> Charging like it owes the battery money.",
        "> FAST CHARGE: the electrons are sprinting now."
    )
    private val normalRoasts = listOf(
        "> Normal charging. Perfectly, aggressively fine.",
        "> Standard charge. Beige energy, but it works.",
        "> Charging at a respectable, forgettable pace.",
        "> Regular charge. Nobody's writing a movie about this.",
        "> Normal speed. Battery shrugs and gets on with it.",
        "> Middle-of-the-road charging. Reliable, unbothered, mildly boring.",
        "> Charging normally. The plain oatmeal of power delivery.",
        "> Standard rate. Neither hero nor villain, just here."
    )
    private val slowRoasts = listOf(
        "> SLOW CHARGE. This charger runs on vibes alone. 🐌",
        "> Trickle charging. Glaciers are filing a complaint about the pace.",
        "> Charging so slow it's basically a tribute to patience.",
        "> Slow power. The battery's aging in real time.",
        "> SLOW CHARGE: dial-up internet called, wants its speed back.",
        "> This charger negotiates each electron individually.",
        "> Slow charge. Somebody left the parking brake on.",
        "> Trickling in. The battery's watching paint dry, but with more suffering."
    )
    private val speedUpRoasts = listOf(
        "> Speed jumped. Somebody found the extra gear.",
        "> More juice flowing. Charger just remembered its job.",
        "> Charge boosted. Battery's getting spoiled.",
        "> Power ramped up. Respect earned.",
        "> Speed increased. The charger heard the complaints.",
        "> Picking up the pace. Somebody's trying to make up for lost time."
    )
    private val speedDownRoasts = listOf(
        "> Speed dropped. Charger's losing motivation.",
        "> Power eased off. Battery noticed immediately.",
        "> Charge rate slipped. Somebody's phoning it in.",
        "> Slower now. The moment has passed.",
        "> Power backing off. The charger's taking a breather nobody asked for.",
        "> Easing up. Battery's filing a complaint with management."
    )
    private val speedDownSlowRoasts = listOf(
        "> Fell all the way to SLOW. Rock bottom, charging edition. 🐌",
        "> Speed collapsed. Fast charging is now a fond memory.",
        "> Slow mode hit. This is a cry for help.",
        "> Charging crawled to a stop. Somebody check the charger's pulse.",
        "> Bottomed out at SLOW. The charger gave up without telling anyone.",
        "> Dropped to a crawl. This is the charging equivalent of a shrug."
    )


    private fun randomRoast(list: List<String>) = list[(Math.random() * list.size).toInt()]

    private fun updateChargeStateMessage() {
        val state = determineChargeState()
        val watts = reliableChargingWatts(currentPluggedType())
        val previous = lastChargeState
        val previousWatts = lastChargeWatts
        lastChargeState = state
        lastChargeWatts = watts

        if (previous == null) {
            if (state == ChargeState.NOT_CHARGING) {
                chargeRoastHistory.clear()
                chargeRoastLog("> Not charging. Plug in a charger to start charging.")
            } else {
                chargeRoastLog(randomRoast(connectedRoasts))
                chargeRoastLog(randomRoast(roastFor(state)))
            }
        } else if (state != previous) {
            when {
                state == ChargeState.NOT_CHARGING -> {
                    chargeRoastHistory.clear()
                    chargeRoastLog("> Not charging. Charger disconnected.")
                }
                previous == ChargeState.NOT_CHARGING -> {
                    chargeRoastHistory.clear()
                    chargeRoastLog(randomRoast(connectedRoasts))
                    chargeRoastLog(randomRoast(roastFor(state)))
                }
                else -> chargeRoastLog(randomRoast(roastFor(state)))
            }
        } else if (watts != null && previousWatts != null && kotlin.math.abs(watts - previousWatts) >= 1.0) {
            when {
                state == ChargeState.SLOW && watts < previousWatts -> chargeRoastLog(randomRoast(speedDownSlowRoasts))
                watts > previousWatts -> chargeRoastLog(randomRoast(speedUpRoasts))
                watts < previousWatts -> chargeRoastLog(randomRoast(speedDownRoasts))
            }
        }
        runOnUiThread { updateChargeStatusHeader(state) }
    }

    private fun roastFor(state: ChargeState): List<String> = when (state) {
        ChargeState.NOT_CHARGING -> disconnectedRoasts
        ChargeState.SLOW -> slowRoasts
        ChargeState.NORMAL -> normalRoasts
        ChargeState.FAST -> fastRoasts
    }

    private fun currentPluggedType(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    }

    private fun chargeRoastLog(message: String) {
        runOnUiThread {
            if (!::chargeRoastOutput.isInitialized) return@runOnUiThread
            // Only follow a NEW roast if the user was already at the bottom.
            // Animation frames never change the scroll position.
            val wasNearBottom = if (chargeRoastScroll.childCount > 0) {
                val child = chargeRoastScroll.getChildAt(0)
                child.bottom - (chargeRoastScroll.scrollY + chargeRoastScroll.height) <= dp(24)
            } else true
            chargeRoastHistory += message
            if (chargeRoastHistory.size > 16) chargeRoastHistory.removeAt(0)
            renderChargeRoastTerminal(autoScroll = wasNearBottom)
        }
    }

    private fun renderChargeRoastTerminal(autoScroll: Boolean) {
        if (!::chargeRoastOutput.isInitialized) return
        val base = if (chargeRoastHistory.isEmpty()) {
            if (currentPluggedType() != 0) "> Charger connected. Monitoring charging…"
            else "> Not charging. Plug in a charger to start charging."
        } else {
            chargeRoastHistory.joinToString("\n")
        }
        // The roast terminal contains roast messages only. Status/support information
        // belongs to the separate UI above it.
        chargeRoastOutput.text = base
        if (autoScroll) {
            chargeRoastScroll.post { chargeRoastScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun updateChargeStatusHeader(state: ChargeState) {
        if (!::chargeStatusText.isInitialized || !::chargeStatusEmoji.isInitialized) return
        stopChargeStatusBlink()
        when (state) {
            ChargeState.FAST -> {
                chargeStatusEmoji.text = "⚡"
                chargeStatusText.text = "Fast charging"
                startChargeStatusBlink()
            }
            ChargeState.NORMAL -> {
                chargeStatusEmoji.text = "⚡"
                chargeStatusText.text = "Normal charging"
                startChargeStatusBlink()
            }
            ChargeState.SLOW -> {
                chargeStatusEmoji.text = "🐌"
                chargeStatusText.text = "Slow charging"
                startChargeStatusBlink()
            }
            ChargeState.NOT_CHARGING -> {
                chargeStatusEmoji.text = "🔌"
                chargeStatusText.text = "Not charging"
                chargeStatusEmoji.alpha = 1f
            }
        }
    }

    private fun startChargeStatusBlink() {
        if (!::chargeStatusEmoji.isInitialized) return
        chargeStatusAnimator = ObjectAnimator.ofFloat(chargeStatusEmoji, "alpha", 1f, 0.25f, 1f).apply {
            duration = 900L
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopChargeStatusBlink() {
        chargeStatusAnimator?.cancel()
        chargeStatusAnimator = null
        if (::chargeStatusEmoji.isInitialized) chargeStatusEmoji.alpha = 1f
    }

    private fun determineChargeState(): ChargeState {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ChargeState.NOT_CHARGING
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0 && status != BatteryManager.BATTERY_STATUS_NOT_CHARGING && status != BatteryManager.BATTERY_STATUS_DISCHARGING
        if (!isCharging) return ChargeState.NOT_CHARGING
        val watts = reliableChargingWatts(plugged)
        return when {
            watts == null -> ChargeState.NORMAL
            watts < SLOW_CHARGE_THRESHOLD_W -> ChargeState.SLOW
            watts >= FAST_CHARGE_THRESHOLD_W -> ChargeState.FAST
            else -> ChargeState.NORMAL
        }
    }

    /** Returns actual measured charging wattage from real sysfs electrical readings only
     *  (never the 5V/estimated fallback), so fast charging is never falsely reported when
     *  the device doesn't expose enough information to reliably determine it. Returns null
     *  when a trustworthy reading isn't available, in which case the normal-charger
     *  message is used rather than guessing. */
    private fun reliableChargingWatts(plugged: Int): Double? {
        return if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            if (!ChargingController.isWirelessChargingSupported()) return null
            val wireless = ChargingController.readWirelessSupplyValues(WIRELESS_MEASUREMENT_ATTRIBUTES)
            val raw = wirelessInputPowerRaw(wireless)
            if (raw != null) {
                val uw = if (kotlin.math.abs(raw) > 100_000L) raw else raw * 1000L
                return kotlin.math.abs(uw) / 1_000_000.0
            }
            val current = readWirelessInputCurrentUa(wireless)
            val voltage = readWirelessInputVoltageMv(wireless)
            if (current != null && voltage != null) current.toDouble() * voltage.toDouble() / 1_000_000_000.0 else null
        } else {
            val current = getBatteryProperty("/sys/class/power_supply/battery/current_now")?.toLongOrNull() ?: return null
            val (voltageMv, usedFallback) = readNegotiatedVoltageMv()
            if (usedFallback) return null
            kotlin.math.abs(current).toDouble() * voltageMv.toDouble() / 1_000_000_000.0
        }
    }

    private fun build() {
        rootLayout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(18),dp(15),dp(18),dp(25)) }
        title(rootLayout,"Battery Manager")
        val scroll=ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
        };
        // Terminal + Charger Control form the primary control area and stay at the
        // top; informational battery details/diagnostics move below them.
        // Keep the charging monitor immediately below the Battery Manager title,
        // with a small visual gap so it feels attached to the page header.
        addChargingRoastTerminal(content)
        addChargingControls(content)
        addBatteryInfo(content)
        addHealthDiagnostics(content)
        pageScroll = scroll
        scroll.addView(content)
        rootLayout.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(rootLayout)
        RootRealmGlobalTheme.applyTheme(this)
        updateChargeStateMessage()
        schedulePowerDiagnosticsRefresh(0L)
        if (pendingPageScrollY > 0) {
            scroll.post { scroll.scrollTo(0, pendingPageScrollY) }
        }
    }

    /** Small touch-scrollable charging personality terminal. It has no controls and is
     * intentionally separate from the advanced kernel/log terminal. */
    private fun addChargingRoastTerminal(p: LinearLayout) {
        p.addView(Space(this), LinearLayout.LayoutParams(1, dp(16)))
        section(p, "CHARGING MONITOR")

        // Separate status indicator: only this emoji blinks. No charging progress
        // animation is rendered inside the roast terminal.
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(card, 18)
        }
        chargeStatusEmoji = TextView(this).apply {
            text = "🔌"
            textSize = 27f
            gravity = Gravity.CENTER
        }
        chargeStatusText = TextView(this).apply {
            text = "Checking charging status…"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            setPadding(dp(12), 0, 0, 0)
        }
        statusCard.addView(chargeStatusEmoji, LinearLayout.LayoutParams(dp(48), dp(48)))
        statusCard.addView(chargeStatusText, LinearLayout.LayoutParams(0, -2, 1f))
        p.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })

        // Separate roast terminal. It displays only charger-dependent roast messages.
        val terminalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(card, 18)
        }
        chargeRoastOutput = TextView(this).apply {
            text = "> Waiting for charger status…"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#A8FF60"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextIsSelectable(true)
        }
        chargeRoastScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
            isFillViewport = true
            // Let the outer Battery Manager handle page scrolling naturally.
            // The roast terminal itself remains touch-scrollable.
            isNestedScrollingEnabled = true
            addView(chargeRoastOutput, ViewGroup.LayoutParams(-1, -2))
        }
        terminalCard.addView(chargeRoastScroll, LinearLayout.LayoutParams(-1, dp(132)))
        p.addView(terminalCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
    }

    private fun addTerminal(p:LinearLayout) {
        section(p, "ADVANCED TERMINAL / KERNEL LOG")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(card, 18)
        }
        terminalOutput = TextView(this).apply {
            text = if (logBuffer.isNotEmpty()) logBuffer.toString()
                   else "Ready.\nWhen you change charging speed, results appear here in simple words.\n"
            setTextColor(Color.parseColor("#A8FF60"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        terminalScroll = ScrollView(this).apply {
            isFillViewport = true
            isNestedScrollingEnabled = false
            setBackgroundColor(Color.parseColor("#0D1117"))
            addView(terminalOutput, ViewGroup.LayoutParams(-1, -2))
        }
        card.addView(terminalScroll, LinearLayout.LayoutParams(-1, dp(190)))
        val scrollRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun sb(t: String) = Button(this).apply { text = t; textSize = 11f; styleButton(this) }
        val top = sb("▲ TOP"); val up = sb("▲ UP"); val down = sb("▼ DOWN"); val end = sb("▼ END"); val clear = sb("CLEAR")
        listOf(top, up, down, end, clear).forEachIndexed { i, b ->
            scrollRow.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i < 4) marginEnd = dp(4) })
        }
        card.addView(scrollRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        top.setOnClickListener { terminalScroll.post { terminalScroll.fullScroll(android.view.View.FOCUS_UP) } }
        up.setOnClickListener { terminalScroll.smoothScrollBy(0, -dp(120)) }
        down.setOnClickListener { terminalScroll.smoothScrollBy(0, dp(120)) }
        end.setOnClickListener { terminalScroll.post { terminalScroll.fullScroll(android.view.View.FOCUS_DOWN) } }
        clear.setOnClickListener { logBuffer.clear(); terminalOutput.text = "Log cleared.\n" }

        // Log-capture row: pulls the two most useful root-accessible logs for
        // diagnosing charging issues (userspace logcat + kernel dmesg), filters
        // them down to charging/power related lines, and explains what's found
        // in plain words underneath.
        val logRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val takeLog = sb("TAKE LOG"); val kernelLog = sb("KERNEL LOG")
        logRow.addView(takeLog, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        logRow.addView(kernelLog, LinearLayout.LayoutParams(0, dp(40), 1f))
        card.addView(logRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        takeLog.setOnClickListener { takeLogcat() }
        kernelLog.setOnClickListener { takeDmesg() }

        p.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    // Keywords used to filter raw logs down to lines actually relevant to charging/battery,
    // so the terminal isn't flooded with unrelated system noise.
    private val LOG_KEYWORDS = listOf(
        "batt","charg","power","usb","typec","pmic","smb","qcom","mtk","otg",
        "thermal","voltage","current_now","vbus","wireless","wls","wlc"
    )

    /** Runs a command as root with a timeout, returning (stdout, stderr).
     *  Mirrors the timeout-and-destroy pattern used elsewhere in this file so a
     *  stuck/blocking command (e.g. a device with no logcat buffer) can't hang the UI. */
    private fun runSu(cmd: String, timeoutMs: Long = 6000): Pair<String, String> = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            proc.destroy()
            "" to "Timed out waiting for the command"
        } else {
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(proc.errorStream)).readText().trim()
            out to err
        }
    } catch (e: Exception) {
        "" to (e.message ?: "unknown error")
    }

    private fun takeLogcat() {
        termLog("── LOGCAT (charging/battery lines) ──")
        termLog("Capturing recent system log…")
        Thread {
            // Wrapped in the shell's own `timeout` so a stuck process is killed by the
            // shell itself, not just abandoned - su -c spawns a child process that
            // proc.destroy() on the Java side cannot reach, so a hang there would
            // otherwise keep running invisibly even after we report a timeout.
            var (out, err) = runSu("timeout 5 logcat -d -t 800", timeoutMs = 8000)
            if (out.isBlank() && err.contains("not found", true)) {
                val fallback = runSu("logcat -d -t 800", timeoutMs = 8000)
                out = fallback.first; err = fallback.second
            }
            runOnUiThread {
                if (out.isBlank()) {
                    termLog("No logcat output" + (err.takeIf { it.isNotBlank() }?.let { ": $it" }
                        ?: " (logcat may be restricted or empty on this device)."))
                    return@runOnUiThread
                }
                val relevant = out.lineSequence()
                    .filter { line -> LOG_KEYWORDS.any { line.contains(it, ignoreCase = true) } }
                    .toList()
                    .takeLast(60)
                if (relevant.isEmpty()) {
                    termLog("No charging/battery related lines found in recent logcat.")
                } else {
                    relevant.forEach { termLog(it) }
                }
                termLog("── In simple words ──")
                explainLog(relevant).forEach { termLog("• $it") }
            }
        }.start()
    }

    private fun takeDmesg() {
        termLog("── KERNEL LOG (dmesg, charging/battery lines) ──")
        termLog("Reading kernel messages…")
        Thread {
            // Same shell-side `timeout` wrapping as logcat above - dmesg is the more
            // likely of the two to hang, since some ROMs leave /dev/kmsg open in a way
            // that blocks a plain read even though the process technically has access.
            var (out, err) = runSu("timeout 5 dmesg", timeoutMs = 8000)
            if (out.isBlank() && err.contains("not found", true)) {
                val fallback = runSu("dmesg", timeoutMs = 8000)
                out = fallback.first; err = fallback.second
            }
            runOnUiThread {
                if (out.isBlank()) {
                    termLog("No kernel log output" + (err.takeIf { it.isNotBlank() }?.let { ": $it" }
                        ?: " (dmesg is blocked or the read hung on this device - common on newer Android builds)."))
                    return@runOnUiThread
                }
                val relevant = out.lineSequence()
                    .filter { line -> LOG_KEYWORDS.any { line.contains(it, ignoreCase = true) } }
                    .toList()
                    .takeLast(60)
                if (relevant.isEmpty()) {
                    termLog("No charging/battery related lines found in the kernel log.")
                } else {
                    relevant.forEach { termLog(it) }
                }
                termLog("── In simple words ──")
                explainLog(relevant).forEach { termLog("• $it") }
            }
        }.start()
    }

    /** Turns raw log lines into a short plain-English explanation by matching
     *  known charging-related patterns. Not exhaustive - just the common causes. */
    private fun explainLog(lines: List<String>): List<String> {
        if (lines.isEmpty()) {
            return listOf("Nothing charging-related showed up. If charging still feels slow, that usually points to the charger or cable's own limit rather than something the phone is blocking.")
        }
        val text = lines.joinToString("\n").lowercase()
        val notes = mutableListOf<String>()
        if (text.contains("avc:") && text.contains("denied"))
            notes += "Android's built-in security system (SELinux) blocked one of the actions. This is why some settings won't change even with root access."
        if (text.contains("thermal") || text.contains("throttl"))
            notes += "The phone is deliberately slowing down charging because it's getting warm. This is normal and protects the battery."
        if (text.contains("over-voltage") || text.contains("over_voltage") || text.contains("ovp"))
            notes += "The charger tried to send more voltage than expected, and the phone cut it back to protect itself."
        if (text.contains("overheat"))
            notes += "The battery got too hot and charging paused for safety."
        if (text.contains("taper") || text.contains("termination"))
            notes += "The battery is close to full, so charging is intentionally slowing down - this is normal and protects long-term battery health."
        if (text.contains("input_suspend") || text.contains("input suspend"))
            notes += "Incoming charging power was suspended by something - a setting, the system, or an app (possibly this one)."
        if (text.contains("disconnect"))
            notes += "A connection disconnect was logged - the cable or charger connection may be loose or unstable."
        if (text.contains("otg"))
            notes += "OTG (reverse-power/accessory) mode appeared in the log, which can change how the phone handles charging."
        if (text.contains("undercurrent") || text.contains("weak charger") || text.contains("weak_input"))
            notes += "The phone detected a weak charger or low-current power source."
        if (notes.isEmpty())
            notes += "No known problem pattern matched these lines. If charging still seems slow, it's most likely a limit set by the charger or cable, not something being blocked in software."
        return notes
    }

    private fun termLog(msg: String) {
        runOnUiThread {
            if (!::terminalOutput.isInitialized) return@runOnUiThread
            if (logBuffer.isNotEmpty()) logBuffer.append('\n')
            logBuffer.append(msg.trimEnd())
            if (logBuffer.length > 12000) logBuffer.delete(0, logBuffer.length - 10000)
            terminalOutput.text = logBuffer.toString()
            terminalScroll.post { terminalScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun addBatteryInfo(p:LinearLayout) {
        section(p,"Battery Status")
        val i=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1); val scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,-1)
        val temp=i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0); val voltage=i.getIntExtra(BatteryManager.EXTRA_VOLTAGE,0)
        val status=i.getIntExtra(BatteryManager.EXTRA_STATUS,-1); val plugged=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)
        val health=i.getIntExtra(BatteryManager.EXTRA_HEALTH,-1)
        item(p,"Battery Level",if(scale>0) "${level*100/scale}%" else "Unknown")
        item(p,"Status",batteryStatus(status)); item(p,"Power Source",powerSource(plugged))
        item(p,"Temperature","${temp/10.0} °C"); item(p,"Voltage","$voltage mV"); item(p,"Health",health(health))
        val current=getBatteryProperty("/sys/class/power_supply/battery/current_now")
        val chargeFull=findBatteryProperty(listOf(
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full",
            "/sys/class/power_supply/main/charge_full"
        ))
        val chargeDesign=findBatteryProperty(listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/main/charge_full_design"
        ))
        val energyFull=findBatteryProperty(listOf(
            "/sys/class/power_supply/battery/energy_full",
            "/sys/class/power_supply/bms/energy_full"
        ))
        val energyDesign=findBatteryProperty(listOf(
            "/sys/class/power_supply/battery/energy_full_design",
            "/sys/class/power_supply/bms/energy_full_design"
        ))
        val chargeCounter=getBatteryProperty("/sys/class/power_supply/battery/charge_counter")
        if(current!=null) item(p,"Current",formatCurrent(current))
        if(current!=null && plugged!=0) item(p,"Charger Power (approx.)",formatChargingPower(current))

        val capacity=chargeFull?.toLongOrNull()
        val design=chargeDesign?.toLongOrNull()
        val energyF=energyFull?.toLongOrNull()
        val energyD=energyDesign?.toLongOrNull()

        when {
            capacity!=null && capacity>0 -> item(p,"Battery Capacity",formatCapacity(capacity))
            energyF!=null && energyF>0 -> item(p,"Battery Capacity",formatEnergyAsCapacity(energyF))
            chargeCounter?.toLongOrNull()?.let{it>0}==true -> item(p,"Battery Capacity",formatCapacity(chargeCounter!!.toLong()))
        }

        when {
            capacity!=null && design!=null && design>0 -> item(p,"Battery Health",healthPercent(capacity,design))
            energyF!=null && energyD!=null && energyD>0 -> item(p,"Battery Health",healthPercent(energyF,energyD))
            else -> item(p,"Battery Health",estimateHealthFromBatteryManager())
        }
    }

    private fun addChargingControls(p:LinearLayout) {
        // Wireless charging is shown only when the device supports it AND a wireless charger is currently connected.
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val wirelessPlugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_WIRELESS
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        if (ChargingController.isWirelessChargingSupported()) {
            val wirelessState = ChargingController.readWirelessSupplyValues(listOf("online", "status", "present"))
            val wirelessConnected = wirelessPlugged || wirelessState["online"] == "1" || wirelessState["status"].equals("Charging", true)
            if (wirelessConnected) addWirelessCharging(p, true, batteryStatus)
        }

        section(p,"Charging Control")

        chargingControlDetection = TextView(this).apply {
            text = if (isCurrentlyPlugged()) "Auto-detection: checking charging control…" else "Auto-detection: waiting for charger…"
            textSize = 13f
            setTextColor(gray)
            setPadding(dp(5), dp(4), dp(5), dp(12))
        }
        p.addView(chargingControlDetection, LinearLayout.LayoutParams(-1, -2))

        if(!ChargingController.isRootAvailable()){
            item(p,"Root Access","Not available")
            val msg=TextView(this).apply{text="Root is required for charging control.";textSize=13f;setTextColor(red);setPadding(dp(5),dp(4),dp(5),dp(12))}
            p.addView(msg)
            return
        }
        item(p,"Root Access","Available")
        item(p,"SELinux",ChargingController.getSELinuxStatus())

        // USB Fast Charging / Charging Enable: unchanged, still driven by their
        // existing fixed candidate lists (these are simple on/off toggles, not the
        // current-control node the new automatic discovery below targets).
        val paths=listOf(
            "USB Fast Charging" to listOf("/sys/class/power_supply/usb/real_type","/sys/class/power_supply/usb/online","/sys/module/qpnp_smb2/parameters/usb_icl_override"),
            "Charging Enable" to listOf("/sys/class/power_supply/battery/charging_enabled","/sys/class/power_supply/battery/charge_disable","/sys/class/power_supply/battery/input_suspend")
        )
        for((label,candidates) in paths){
            val path=candidates.firstOrNull{readRoot(it)!=null && writable(it)} ?: candidates.firstOrNull{readRoot(it)!=null}
            if(path!=null){
                item(p,label,formatChargingControlValue(label, readRoot(path) ?: "Unknown")) { status ->
                    if (label == "USB Fast Charging") usbFastChargingStatusValue = status
                }
                if(writable(path)){
                    val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
                    val rawValue=readRoot(path) ?: "Unknown"
                    val value=EditText(this).apply{
                        setText(formatChargingControlValue(label,rawValue))
                        setTextColor(white);setHintTextColor(gray)
                        hint="value"
                        setSingleLine(true);setPadding(dp(12),0,dp(12),0)
                    }
                    val apply=Button(this).apply{text="Apply";setOnClickListener{
                        val input=value.text.toString().trim()
                        val raw=parseChargingControlValue(label,input)
                        if(raw==null){ toast("Enter a valid value") }
                        else {
                            val(ok,err)=writeRootVerbose(path,raw)
                            toast(if(ok) "Applied" else "Write failed: ${err ?: "unknown error"}")
                        }
                    }}
                    styleButton(apply)
                    row.addView(value,LinearLayout.LayoutParams(0,dp(52),1f)); row.addView(apply,LinearLayout.LayoutParams(dp(105),dp(52)).apply{marginStart=dp(8)})
                    p.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
                    if(label=="USB Fast Charging") {
                        usbFastChargingPath = path
                        usbFastChargingValueField = value
                        addEnableDisableToggle(p,path,value)
                    }
                }
            }
        }

        // Charge Current Limit: no fixed/vendor path. The node is discovered at
        // runtime by scanning every /sys/class/power_supply/*/ entry for a curated
        // set of known charging-current-control node names.
        addAutoChargeCurrentControl(p)

        addBypassChargingInfo(p)

        val hint=TextView(this).apply{text="Charging controls depend on your device kernel. Unsupported controls are hidden.";textSize=13f;setTextColor(gray);setPadding(dp(5),dp(4),dp(5),dp(12))}; p.addView(hint)

        // Advanced terminal belongs to Charging Control: it is the real log/diagnostic
        // console and is intentionally independent from the small roast monitor above.
        addTerminal(p)
        detectChargingControlOnPlug()
        refreshUsbFastChargingControl()
        refreshBypassChargingInfo(isCurrentlyPlugged())
    }

    /** Re-reads the existing USB Fast Charging control in-place when the charger state changes.
     *  This intentionally does NOT rebuild Battery Manager, so the user's page scroll position
     *  and the current Charging Control UI remain intact. */
    private fun refreshUsbFastChargingControl() {
        val path = usbFastChargingPath ?: return
        Thread {
            val current = readRoot(path)
            if (current != null) {
                val shown = formatChargingControlValue("USB Fast Charging", current)
                runOnUiThread {
                    usbFastChargingValueField?.setText(shown)
                    usbFastChargingStatusValue?.text = shown
                }
            }
        }.start()
    }

    // Bypass/pass-through charging is reported in its own Charging Control card. A positive result requires a
    // hardware-name hints are used only to explain a missing software control, never as proof
    // that the device can actually perform bypass charging.
    private val BYPASS_NODE_NAMES = listOf(
        "charge_bypass", "bypass_charging", "charging_bypass", "battery_bypass",
        "bypass_charger_mode", "usb_bypass", "smart_bypass_charging"
    )
    private val BYPASS_HARDWARE_HINTS = listOf(
        "sc8551", "sc8571", "sc8541", "sc8555", "sc8561", "sc8562",
        "bq2597", "bq2596", "bq2598", "sy6970", "sy7636", "cps2021",
        "charge pump", "charge_pump"
    )
    private data class BypassNode(val path:String, val value:String?, val readable:Boolean, val writable:Boolean)

    private fun findBypassNode(): BypassNode? {
        for (name in BYPASS_NODE_NAMES) {
            val path = "/sys/class/power_supply/battery/$name"
            val value = readRoot(path)
            if (value != null || writable(path)) return BypassNode(path, value, value != null, writable(path))
            val usbPath = "/sys/class/power_supply/usb/$name"
            val usbValue = readRoot(usbPath)
            if (usbValue != null || writable(usbPath)) return BypassNode(usbPath, usbValue, usbValue != null, writable(usbPath))
        }
        return null
    }

    private fun detectBypassCapableHardware(): Boolean {
        val haystack = buildString {
            append(readRoot("/sys/class/power_supply/battery/type") ?: "")
            append(' ')
            append(readRoot("/sys/class/power_supply/usb/real_type") ?: "")
            append(' ')
            append(readRoot("/sys/devices/soc0/machine") ?: "")
            append(' ')
            append(readRoot("/sys/devices/soc0/serial_number") ?: "")
        }.lowercase()
        return BYPASS_HARDWARE_HINTS.any { haystack.contains(it) }
    }

    private var bypassStatusValue: TextView? = null

    /** Bypass charging is shown separately from the roast terminal. */
    private fun addBypassChargingInfo(p: LinearLayout) {
        section(p, "Bypass Charging")
        item(p, "Support", "Checking…") { bypassStatusValue = it }
    }

    private fun refreshBypassChargingInfo(plugged: Boolean) {
        val target = bypassStatusValue ?: return
        if (!plugged) {
            target.text = "Not connected — plug in charger to detect"
            return
        }
        target.text = "Checking support…"
        Thread {
            val node = try { findBypassNode() } catch (_: Exception) { null }
            val hardwareHint = try { detectBypassCapableHardware() } catch (_: Exception) { false }
            val line = when {
                node != null && node.readable && node.writable ->
                    "SUPPORTED"
                node != null || hardwareHint ->
                    "NOT SUPPORTED — Software/kernel level."
                else ->
                    "NOT SUPPORTED — Hardware level."
            }
            runOnUiThread { bypassStatusValue?.text = line }
        }.start()
    }

    private fun isCurrentlyPlugged(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** Re-check charging-control support only when the charger is plugged in.
     *  This updates the Charging Control section in place; it never rebuilds the page. */
    private fun detectChargingControlOnPlug() {
        if (!::chargingControlDetection.isInitialized) return
        if (!isCurrentlyPlugged()) {
            updateChargingControlDetection(false, null, null, null)
            return
        }
        updateChargingControlDetection(null, null, null, null)
        Thread {
            val node = try { ChargingController.findControlNode() } catch (_: Exception) { null }
            val supported = node != null
            val readable = node?.readable == true
            val writable = node?.writable == true
            runOnUiThread {
                if (!::chargingControlDetection.isInitialized) return@runOnUiThread
                if (!isCurrentlyPlugged()) {
                    updateChargingControlDetection(false, null, null)
                    return@runOnUiThread
                }
                updateChargingControlDetection(true, supported, readable, writable)
            }
        }.start()
    }

    private fun updateChargingControlDetection(
        plugged: Boolean?,
        supported: Boolean?,
        readable: Boolean? = null,
        writable: Boolean? = null
    ) {
        if (!::chargingControlDetection.isInitialized) return
        chargingControlDetection.text = when {
            plugged == false -> "Auto-detection: charger not connected. Plug in a charger to detect support."
            plugged == null -> "Auto-detection: Checking charging-control support…"
            supported != true -> "Auto-detection: Not supported — this kernel does not expose a compatible charging-control node."
            readable != true -> "Auto-detection: Detected, but the control cannot be read on this kernel."
            writable != true -> "Auto-detection: Supported, but the detected control is read-only."
            else -> "Auto-detection: Supported — charging control detected."
        }
        chargingSupportLine = when {
            plugged == false -> "> Charging control: NOT AVAILABLE — plug in a charger to detect support."
            plugged == null -> "> Charging control: CHECKING SUPPORT…"
            supported != true -> "> Charging control: NOT SUPPORTED — kernel does not expose a compatible control node."
            readable != true -> "> Charging control: DETECTED, BUT UNREADABLE — kernel denied/read does not expose the control."
            writable != true -> "> Charging control: SUPPORTED — detected control is read-only."
            else -> "> Charging control: SUPPORTED — compatible writable control detected."
        }
        chargingControlDetection.setTextColor(
            when {
                plugged == false || plugged == null -> gray
                supported == true && readable == true && writable == true -> green
                else -> red
            }
        )
    }

    /** Automatically discovers, and if writable exposes, this kernel's charging-current
     *  control node. Never assumes a fixed vendor path - see ChargingController. */
    private fun addAutoChargeCurrentControl(p:LinearLayout){
        val label="Charge Current Limit"
        val node=ChargingController.findControlNode()
        when {
            node==null -> {
                item(p,label,"Not supported")
                val msg=TextView(this).apply{text="Charging control is not supported by this kernel.";textSize=13f;setTextColor(gray);setPadding(dp(5),dp(4),dp(5),dp(12))}
                p.addView(msg)
            }
            !(node.writable && node.readable) -> {
                item(p,label,formatChargingControlValue(label, node.value ?: "Unknown"))
                val msg=TextView(this).apply{text="Charging control is not writable on this kernel.";textSize=13f;setTextColor(gray);setPadding(dp(5),dp(4),dp(5),dp(12))}
                p.addView(msg)
            }
            else -> {
                val path=node.path
                item(p,label,formatChargingControlValue(label, node.value ?: "Unknown"))
                val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
                val value=EditText(this).apply{
                    setText(formatChargingControlValue(label, node.value ?: ""))
                    setTextColor(white);setHintTextColor(gray)
                    hint="amps (e.g. 1.60 A)"
                    setSingleLine(true);setPadding(dp(12),0,dp(12),0)
                }
                val apply=Button(this).apply{text="Apply";setOnClickListener{
                    val input=value.text.toString().trim()
                    val raw=parseChargingControlValue(label,input)
                    if(raw==null){ toast("Enter a valid current (A or mA)") }
                    else applyChargeCurrentWrite(path,raw,value)
                }}
                styleButton(apply)
                row.addView(value,LinearLayout.LayoutParams(0,dp(52),1f)); row.addView(apply,LinearLayout.LayoutParams(dp(105),dp(52)).apply{marginStart=dp(8)})
                p.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
                addWattagePresets(p,path,value)
            }
        }
    }

    /** Writes the new current value via root, reads it back, and only reports success
     *  if the kernel actually accepted the exact value written (see ChargingController). */
    private fun applyChargeCurrentWrite(path:String,raw:String,valueField:EditText){
        termLog("Trying to change charging speed…")
        val result=ChargingController.writeAndVerify(path,raw)
        when(result.outcome){
            ChargingController.WriteOutcome.CONFIRMED -> {
                val shown=formatChargingControlValue("Charge Current Limit",result.after!!)
                valueField.setText(shown)
                termLog("Success. New limit: $shown")
                termLog("If charging is still slow, the charger or cable may be the limit.")
                toast("Applied — $shown")
            }
            ChargingController.WriteOutcome.ADJUSTED -> {
                val shown=formatChargingControlValue("Charge Current Limit",result.after!!)
                valueField.setText(shown)
                termLog("Phone changed your request to: $shown")
                termLog("This device does not allow the exact speed you asked for.")
                toast("Phone adjusted to $shown")
            }
            ChargingController.WriteOutcome.REJECTED -> {
                termLog("Failed. Phone blocked the change.")
                termLog("Common reasons: security (SELinux), or this control is read-only.")
                termLog("That can keep charging slow even with a strong charger.")
                toast("Blocked — charging speed not changed")
            }
            ChargingController.WriteOutcome.ERROR -> {
                termLog("Error: ${result.error ?: "unknown"}")
                termLog("Could not change charging speed.")
                toast("Write failed: ${result.error ?: "unknown error"}")
            }
        }
    }

    // The 45W+ row assumes the charger has negotiated a higher voltage via PD/QC;
    // this app only caps current from userspace and cannot force that voltage negotiation.
    private val PRESET_WATTS=listOf(5,10,15,18,20,25,30,33,40,45,50,55,60,65,67,80,90,100,120)
    private val PRESET_ROW_LOW=listOf(5,10,15,18,20,25,30)
    private val PRESET_ROW_HIGH=listOf(45,60,65,90,120)
    private val MAX_CURRENT_UA=6_000_000L // 6A safety ceiling

    private fun addEnableDisableToggle(p:LinearLayout,path:String,valueField:EditText){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val enable=Button(this).apply{text="Enable";setOnClickListener{
            val(ok,err)=writeRootVerbose(path,"1")
            if(ok){valueField.setText(readRoot(path) ?: "1"); usbFastChargingStatusValue?.text = valueField.text.toString(); toast("Fast charging enabled")} else toast("Write failed: ${err ?: "unknown error"}")
        }}
        val disable=Button(this).apply{text="Disable";setOnClickListener{
            val(ok,err)=writeRootVerbose(path,"0")
            if(ok){valueField.setText(readRoot(path) ?: "0"); usbFastChargingStatusValue?.text = valueField.text.toString(); toast("Fast charging disabled")} else toast("Write failed: ${err ?: "unknown error"}")
        }}
        styleButton(enable,textColor=green)
        styleButton(disable,textColor=red)
        row.addView(enable,LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(4)})
        row.addView(disable,LinearLayout.LayoutParams(0,dp(48),1f))
        p.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
    }

    private fun addWattagePresets(p:LinearLayout,path:String,valueField:EditText){
        // Show wattage requests only while a wired charger is actually connected.
        if (!isWiredCharging()) return

        val kernelMax = ChargingController.getKernelRequestableCurrentUa(path) ?: return
        val (voltageMv, _) = readNegotiatedVoltageMv()
        if (voltageMv <= 0L) return

        val requestable = PRESET_WATTS.filter { watts ->
            val requiredUa = (watts.toDouble() * 1_000_000_000.0 / voltageMv.toDouble()).toLong()
            requiredUa > 0L && requiredUa <= kernelMax && requiredUa <= MAX_CURRENT_UA
        }
        if (requestable.isEmpty()) return

        p.addView(TextView(this).apply {
            text="Requestable Wattage"; textSize=13f; setTextColor(gray);
            setPadding(dp(5),dp(2),dp(5),dp(6))
        })
        p.addView(presetGrid(requestable,path,valueField),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
        addWattageLock(p,path)
    }

    private fun addWattageLock(p:LinearLayout,path:String){
        val label=TextView(this).apply{
            text="Wattage Lock"
            textSize=13f
            setTextColor(gray)
            setPadding(dp(5),dp(2),dp(5),dp(6))
        }
        p.addView(label)

        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val watts=EditText(this).apply{
            hint="Target watts (e.g. 30)"
            setTextColor(white);setHintTextColor(gray)
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true);setPadding(dp(12),0,dp(12),0)
        }
        val lock=Button(this).apply{text=if(wattageLockEnabled) "Unlock" else "Lock"}
        styleButton(lock,textColor=if(wattageLockEnabled) red else green)
        row.addView(watts,LinearLayout.LayoutParams(0,dp(52),1f))
        row.addView(lock,LinearLayout.LayoutParams(dp(105),dp(52)).apply{marginStart=dp(8)})
        p.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})

        val status=TextView(this).apply{
            text=if(wattageLockEnabled) "Wattage Lock: ${wattageLockWatts}W active" else "Wattage Lock: Off"
            textSize=13f;setTextColor(gray)
            setPadding(dp(5),dp(2),dp(5),dp(10))
        }
        p.addView(status)

        lock.setOnClickListener{
            if(wattageLockEnabled){
                stopWattageLock(path,status,lock)
                return@setOnClickListener
            }
            val target=watts.text.toString().trim().toDoubleOrNull()
            if(target==null || target<=0.0 || target>120.0){
                toast("Enter a target between 1W and 120W")
                return@setOnClickListener
            }
            wattageLockWatts=target.toInt()
            if(kotlin.math.abs(target-wattageLockWatts)>0.001){
                toast("Use a whole-number watt target")
                return@setOnClickListener
            }
            wattageLockPath=path
            wattageLockEnabled=true
            lock.text="Unlock"
            styleButton(lock,textColor=red)
            status.text="Wattage Lock: ${wattageLockWatts}W active"
            toast("Wattage Lock enabled: ${wattageLockWatts}W")
            enforceWattageLock(showToast=true)
            wattageLockHandler.post(wattageLockRunnable)
        }
    }

    private val wattageLockRunnable=object:Runnable{
        override fun run(){
            if(!wattageLockEnabled) return
            enforceWattageLock(showToast=false)
            wattageLockHandler.postDelayed(this,2000L)
        }
    }

    private fun stopWattageLock(path:String,status:TextView,lock:Button){
        wattageLockEnabled=false
        wattageLockPath=null
        wattageLockHandler.removeCallbacks(wattageLockRunnable)
        status.text="Wattage Lock: Off"
        lock.text="Lock"
        styleButton(lock,textColor=green)
        toast("Wattage Lock disabled")
    }

    private fun isWiredCharging():Boolean{
        val i=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status=i.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
        val plugged=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)
        return status==BatteryManager.BATTERY_STATUS_CHARGING &&
            (plugged==BatteryManager.BATTERY_PLUGGED_USB || plugged==BatteryManager.BATTERY_PLUGGED_AC)
    }

    private fun enforceWattageLock(showToast:Boolean){
        val path=wattageLockPath ?: return
        if(!isWiredCharging()) return
        val(voltageMv,usedFallback)=readNegotiatedVoltageMv()
        if(voltageMv<=0L) return
        val rawUa=(wattageLockWatts.toDouble()*1_000_000_000.0/voltageMv.toDouble()).toLong()
        val finalUa=rawUa.coerceAtMost(MAX_CURRENT_UA)
        val result=ChargingController.writeAndVerify(path,finalUa.toString())
        val now=System.currentTimeMillis()
        when(result.outcome){
            ChargingController.WriteOutcome.CONFIRMED -> {
                if(showToast && now-wattageLockLastToast>1500L){
                    val basis=if(usedFallback) "5V (basic USB)" else "${"%.1f".format(voltageMv/1000.0)}V"
                    termLog("Wattage Lock: holding ${wattageLockWatts}W at $basis")
                    toast("Holding ${wattageLockWatts}W at $basis")
                    wattageLockLastToast=now
                }
            }
            ChargingController.WriteOutcome.ADJUSTED -> {
                if(showToast && now-wattageLockLastToast>1500L){
                    termLog("Wattage Lock: phone changed the current — retrying")
                    toast("Phone adjusted lock; retrying")
                    wattageLockLastToast=now
                }
            }
            ChargingController.WriteOutcome.REJECTED, ChargingController.WriteOutcome.ERROR -> {
                if(showToast && now-wattageLockLastToast>1500L){
                    termLog("Wattage Lock failed — phone blocked the speed limit")
                    toast("Could not hold wattage lock")
                    wattageLockLastToast=now
                }
            }
        }
    }


    private fun presetGrid(watts:List<Int>,path:String,valueField:EditText):android.widget.GridLayout{
        val grid=android.widget.GridLayout(this).apply{columnCount=4}
        for(w in watts){
            val btn=Button(this).apply{text="${w}W";textSize=12f;setOnClickListener{applyWattagePreset(w,path,valueField)}}
            styleButton(btn)
            grid.addView(btn,android.widget.GridLayout.LayoutParams().apply{
                width=0
                height=dp(48)
                columnSpec=android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1f)
                setMargins(dp(3),dp(3),dp(3),dp(3))
            })
        }
        return grid
    }

    /** Reads the charger's actual negotiated voltage from usb/voltage_now.
     *  Returns (millivolts, usedFallback) — falls back to 5000mV if the node isn't readable. */
    private fun readNegotiatedVoltageMv():Pair<Long,Boolean>{
        val raw=readRoot("/sys/class/power_supply/usb/voltage_now")?.toLongOrNull()
        if(raw!=null && raw>0){
            val mv=if(raw>1_000_000) raw/1000 else raw // node is usually microvolts; normalize to mV
            return mv to false
        }
        return 5000L to true
    }

    private fun applyWattagePreset(watts:Int,path:String,valueField:EditText){
        val(voltageMv,usedFallback)=readNegotiatedVoltageMv()
        // A = W / V; current node is in microamps, so uA = watts * 1e9 / voltageMv.
        // NOTE: this sets a *current limit* only. It is not a guarantee the charger
        // will actually deliver this wattage - that also depends on the charger
        // having negotiated a matching voltage via PD/QC, which this app cannot force.
        val rawUa=(watts.toDouble()*1_000_000_000.0/voltageMv.toDouble()).toLong()
        val capped=rawUa>MAX_CURRENT_UA
        val finalUa=if(capped) MAX_CURRENT_UA else rawUa
        val basis=if(usedFallback) "5V fallback, voltage_now unreadable" else "${"%.1f".format(voltageMv/1000.0)}V negotiated"
        val result=ChargingController.writeAndVerify(path,finalUa.toString())
        when(result.outcome){
            ChargingController.WriteOutcome.CONFIRMED -> {
                valueField.setText(formatChargingControlValue("Charge Current Limit", result.after!!))
                toast(if(capped)
                    "Capped at 6A ($basis) — ${watts}W needs the charger to negotiate more voltage, not more current"
                else
                    "Current limit set for ${watts}W using $basis (not a guaranteed wattage)")
            }
            ChargingController.WriteOutcome.ADJUSTED -> {
                valueField.setText(formatChargingControlValue("Charge Current Limit", result.after!!))
                toast("Kernel adjusted value to ${formatChargingControlValue("Charge Current Limit",result.after)}")
            }
            ChargingController.WriteOutcome.REJECTED -> {
                toast("Kernel did not accept the new value" + (result.error?.let{": $it"} ?: " (possible SELinux denial)"))
            }
            ChargingController.WriteOutcome.ERROR -> {
                toast("Write failed: ${result.error ?: "unknown error"}")
            }
        }
    }

    private fun addWirelessCharging(p:LinearLayout, wirelessPlugged:Boolean, batteryStatus:Int) {
        val node = ChargingController.findWirelessControlNode()
        val wireless = ChargingController.readWirelessSupplyValues(WIRELESS_MEASUREMENT_ATTRIBUTES)
        val online = wireless["online"]
        val status = wireless["status"]
        val supply = wireless["__supply__"]
        val inputVoltageMv = readWirelessInputVoltageMv(wireless)
        section(p,"Wireless Charging")
        supply?.let {
            val raw = it.substringBeforeLast('|')
            item(p,"Wireless Power Supply",raw.substringAfterLast('/'))
        }

        val statusText = when {
            wirelessPlugged && batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            status.equals("Charging",true) -> "Charging"
            wirelessPlugged -> "Connected (not charging)"
            online == "1" -> "Connected (not charging)"
            online == "0" || status.equals("Not charging",true) -> "Not charging"
            else -> "Detected"
        }
        item(p,"Wireless Status",statusText)

        item(p,"Actual Wireless Charging Power",readWirelessActualPower(wireless))
        item(p,"Wireless Input Voltage",inputVoltageMv?.let {
            String.format(java.util.Locale.US,"%.2f V",it/1000.0)
        } ?: "Unavailable (wireless input voltage not exposed)")

        val currentUa = readWirelessInputCurrentUa(wireless)
        if (currentUa != null) {
            item(p,"Wireless Input Current",formatCurrentUa(currentUa))
        } else {
            item(p,"Wireless Input Current","Unavailable (wireless input current not exposed)")
        }

        if (node == null) {
            item(p,"Wireless Charge Current Limit","Unavailable (no wireless control node)")
            item(p,"Wireless Kernel Requestable Current","Unknown (no wireless control node)")
            item(p,"Estimated Wireless Requestable Power","Unknown (wireless kernel limit unavailable)")
            return
        }

        if (!(node.readable && node.writable)) {
            item(p,"Wireless Charge Current Limit",formatChargingControlValue("Charge Current Limit",node.value ?: "Unknown"))
            item(p,"Wireless Kernel Requestable Current","Unknown (control not writable)")
            item(p,"Estimated Wireless Requestable Power","Unknown (control not writable)")
            return
        }

        val path=node.path
        item(p,"Wireless Charge Current Limit",formatChargingControlValue("Charge Current Limit",node.value ?: "Unknown"))
        val kernelMax=ChargingController.getKernelRequestableCurrentUa(path)
        item(p,"Wireless Kernel Requestable Current",kernelMax?.let{formatCurrentUa(it)} ?: "Unknown (no kernel max node)")

        if (kernelMax != null && inputVoltageMv != null) {
            val watts=kernelMax.toDouble()*inputVoltageMv.toDouble()/1_000_000_000.0
            item(p,"Estimated Wireless Requestable Power",String.format(java.util.Locale.US,"%.1f W",watts))
        } else {
            item(p,"Estimated Wireless Requestable Power","Unknown (wireless input voltage or kernel max unavailable)")
        }

        val requested=TextView(this).apply {
            text="No wireless wattage request selected"; textSize=14f; setTextColor(gray);
            setPadding(dp(5),dp(4),dp(5),dp(4))
        }
        p.addView(requested)
        addWirelessWattagePresets(p,path,requested,kernelMax,inputVoltageMv)
    }

    private fun addWirelessWattagePresets(p:LinearLayout,path:String,requested:TextView,kernelMaxUa:Long?,voltageMv:Long?) {
        p.addView(TextView(this).apply {
            text="Wireless Wattage Presets — requestable only"; textSize=13f; setTextColor(gray);
            setPadding(dp(5),dp(2),dp(5),dp(6))
        })
        if(kernelMaxUa == null) {
            p.addView(TextView(this).apply {
                text="Hidden: wireless kernel requestable-current maximum is not exposed."; textSize=13f; setTextColor(gray);
                setPadding(dp(5),dp(2),dp(5),dp(8))
            }); return
        }
        if(voltageMv == null) {
            p.addView(TextView(this).apply {
                text="Hidden: trustworthy wireless input voltage is not exposed."; textSize=13f; setTextColor(gray);
                setPadding(dp(5),dp(2),dp(5),dp(8))
            }); return
        }
        val available=PRESET_WATTS.filter {
            val required=(it.toDouble()*1_000_000_000.0/voltageMv.toDouble()).toLong()
            required>0 && required<=kernelMaxUa && required<=MAX_CURRENT_UA
        }
        if(available.isEmpty()) {
            p.addView(TextView(this).apply {
                text="None requestable at the current wireless input voltage."; textSize=13f; setTextColor(gray);
                setPadding(dp(5),dp(2),dp(5),dp(8))
            }); return
        }
        val grid=android.widget.GridLayout(this).apply { columnCount=4 }
        for(w in available) {
            val btn=Button(this).apply {
                text="${w}W"; textSize=12f; styleButton(this)
                setOnClickListener { applyWirelessWattagePreset(w,path,requested,kernelMaxUa,voltageMv) }
            }
            grid.addView(btn,android.widget.GridLayout.LayoutParams().apply {
                width=0; height=dp(48); columnSpec=android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1f);
                setMargins(dp(3),dp(3),dp(3),dp(3))
            })
        }
        p.addView(grid,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
    }

    private fun applyWirelessWattagePreset(watts:Int,path:String,requested:TextView,kernelMaxUa:Long,voltageMv:Long) {
        val required=(watts.toDouble()*1_000_000_000.0/voltageMv.toDouble()).toLong()
        if(required<=0 || required>kernelMaxUa || required>MAX_CURRENT_UA) { toast("Not requestable at the current wireless kernel limit"); return }
        val result=ChargingController.writeAndVerify(path,required.toString())
        when(result.outcome) {
            ChargingController.WriteOutcome.CONFIRMED -> { requested.text="${watts} W wireless request"; toast("Wireless current limit set for ${watts}W request (not guaranteed delivered power)") }
            ChargingController.WriteOutcome.ADJUSTED -> { requested.text="Kernel adjusted ${watts} W wireless request"; toast("Wireless kernel adjusted the requested current") }
            ChargingController.WriteOutcome.REJECTED -> toast("Wireless kernel did not accept the wattage request")
            ChargingController.WriteOutcome.ERROR -> toast("Wireless write failed: ${result.error ?: "unknown error"}")
        }
    }

    private val WIRELESS_MEASUREMENT_ATTRIBUTES = listOf(
        "online", "status", "present", "type", "scope",
        "input_voltage_now", "input_voltage", "bus_voltage_now", "bus_voltage",
        "vbus_voltage_now", "vbus_voltage", "rx_voltage_now", "rx_voltage",
        "rect_voltage_now", "rect_voltage", "vrect_now", "vrect",
        "wireless_voltage_now", "wireless_voltage", "wls_voltage_now", "wls_voltage",
        "wlc_voltage_now", "wlc_voltage", "wpc_voltage_now", "wpc_voltage",
        "wireless_input_voltage_now", "wireless_input_voltage",
        "input_current_now", "input_current", "ibus_current_now", "ibus_current",
        "bus_current_now", "bus_current", "vbus_current_now", "vbus_current",
        "rx_current_now", "rx_current", "rect_current_now", "rect_current",
        "wireless_current_now", "wireless_current", "wls_current_now", "wls_current",
        "wlc_current_now", "wlc_current", "wpc_current_now", "wpc_current",
        "wireless_input_current_now", "wireless_input_current",
        "input_power_now", "input_power", "ibus_power_now", "ibus_power",
        "bus_power_now", "bus_power", "rx_power_now", "rx_power",
        "rect_power_now", "rect_power", "wireless_power_now", "wireless_power",
        "wls_power_now", "wls_power", "wlc_power_now", "wlc_power",
        "wpc_power_now", "wpc_power", "wireless_input_power_now", "wireless_input_power",
        "voltage_now", "current_now", "power_now"
    )

    private fun normalizePowerSupplyVoltage(raw:Long):Long? {
        if(raw<=0L) return null
        val mv=when {
            raw>1_000_000L -> raw/1000L
            raw>10_000L -> raw/1000L
            else -> raw
        }
        return mv.takeIf { it in 4500L..30_000L }
    }

    private fun readWirelessInputVoltageMv(values:Map<String,String>):Long? {
        // Dedicated wireless input attributes are preferred. Some vendor kernels expose
        // only the standard voltage_now/current_now/power_now on the *wireless*
        // power_supply. Because the source directory has already been confirmed as
        // the wireless supply, those standard attributes are safe as a fallback here.
        // We still never read these attributes from the battery supply.
        val explicitNames=listOf(
            "input_voltage_now", "input_voltage", "bus_voltage_now", "bus_voltage",
            "vbus_voltage_now", "vbus_voltage", "rx_voltage_now", "rx_voltage",
            "rect_voltage_now", "rect_voltage", "vrect_now", "vrect",
            "wireless_voltage_now", "wireless_voltage", "wls_voltage_now", "wls_voltage",
            "wlc_voltage_now", "wlc_voltage", "wpc_voltage_now", "wpc_voltage",
            "wireless_input_voltage_now", "wireless_input_voltage",
            // Fallback for kernels whose dedicated wireless supply only exposes the
            // standard power_supply voltage_now attribute. This is read from the
            // confirmed wireless supply, never from the battery supply.
            "voltage_now"
        )
        for(name in explicitNames) {
            val raw=values[name]?.toLongOrNull()
            normalizePowerSupplyVoltage(raw ?: 0L)?.let { return it }
        }
        return null
    }

    private fun wirelessInputPowerRaw(values:Map<String,String>):Long? {
        // Prefer explicit wireless input/RX power attributes. If the confirmed wireless
        // power_supply exposes only standard power_now, use that as the fallback.
        val names=listOf(
            "input_power_now", "input_power", "ibus_power_now", "ibus_power",
            "bus_power_now", "bus_power", "rx_power_now", "rx_power",
            "rect_power_now", "rect_power", "wireless_power_now", "wireless_power",
            "wls_power_now", "wls_power", "wlc_power_now", "wlc_power",
            "wpc_power_now", "wpc_power", "wireless_input_power_now", "wireless_input_power",
            // Fallback: standard power_now on a confirmed wireless power_supply.
            "power_now"
        )
        for(name in names) {
            val raw=values[name]?.toLongOrNull()
            if(raw!=null && raw!=0L) return raw
        }
        return null
    }

    private fun readWirelessInputCurrentUa(values:Map<String,String>):Long? {
        // Prefer explicit wireless input/RX current attributes. If the confirmed wireless
        // power_supply exposes only standard current_now, use that as the fallback.
        val names=listOf(
            "input_current_now", "input_current", "ibus_current_now", "ibus_current",
            "bus_current_now", "bus_current", "vbus_current_now", "vbus_current",
            "rx_current_now", "rx_current", "rect_current_now", "rect_current",
            "wireless_current_now", "wireless_current", "wls_current_now", "wls_current",
            "wlc_current_now", "wlc_current", "wpc_current_now", "wpc_current",
            "wireless_input_current_now", "wireless_input_current",
            // Fallback: standard current_now on the confirmed wireless supply.
            "current_now"
        )
        for(name in names) {
            val raw=values[name]?.toLongOrNull()
            if(raw!=null && raw!=0L) return kotlin.math.abs(raw)
        }
        return null
    }

    private fun readWirelessActualPower(values:Map<String,String>):String {
        val raw=wirelessInputPowerRaw(values)
        if(raw!=null) {
            val uw=if(kotlin.math.abs(raw)>100_000L) raw else raw*1000L
            return String.format(java.util.Locale.US,"%.1f W",kotlin.math.abs(uw)/1_000_000.0)
        }
        val current=readWirelessInputCurrentUa(values)
        val voltage=readWirelessInputVoltageMv(values)
        if(current!=null && voltage!=null) {
            val watts=current.toDouble()*voltage.toDouble()/1_000_000_000.0
            return String.format(java.util.Locale.US,"%.1f W",watts)
        }
        return "Unavailable (wireless input power not exposed)"
    }


    private fun addHealthDiagnostics(p:LinearLayout) {
        section(p,"Power Diagnostics")
        val usb=readRoot("/sys/class/power_supply/usb/online") ?: "Unavailable"
        val wireless=readRoot("/sys/class/power_supply/wireless/online") ?: "Unavailable"
        item(p,"USB Online",formatDiagnosticValue("USB Online",usb)) { usbOnlineDiagnosticValue=it }
        item(p,"Wireless Online",formatDiagnosticValue("Wireless Online",wireless)) { wirelessOnlineDiagnosticValue=it }
        schedulePowerDiagnosticsRefresh(0L)
    }

    private fun actionButton(label:String):Button{
        return Button(this).apply{
            text=label
            textSize=16f
            setTextColor(white)
            typeface=Typeface.DEFAULT_BOLD
            gravity=Gravity.CENTER
            isAllCaps=false
            minHeight=0
            minimumHeight=0
            setPadding(dp(10),0,dp(10),0)
            background=android.graphics.drawable.GradientDrawable().apply{
                setColor(ThemeManager.current(this@BatteryManagerActivity).surface2)
                setStroke(dp(1),ThemeManager.borderColor(ThemeManager.current(this@BatteryManagerActivity)))
                cornerRadius=dp(18).toFloat()
            }
            stateListAnimator=null
        }.also{animatePress(it)}
    }

    /** Quick scale-down/scale-up press feedback for buttons. Runs alongside whatever
     *  onClickListener is already set, so it never interferes with click handling. */
    private fun animatePress(v:android.view.View){
        v.setOnTouchListener{view,event->
            when(event.action){
                android.view.MotionEvent.ACTION_DOWN->view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL->view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    /** Shared rounded-pill styling for buttons, replacing the default platform chrome,
     *  plus the press animation above. */
    private fun styleButton(b:Button,bgColor:Int=ThemeManager.current(this).surface2,borderColor:Int=ThemeManager.borderColor(ThemeManager.current(this@BatteryManagerActivity)),textColor:Int=white){
        b.apply{
            isAllCaps=false
            setTextColor(textColor)
            typeface=Typeface.DEFAULT_BOLD
            stateListAnimator=null
            setPadding(dp(8),0,dp(8),0)
            background=android.graphics.drawable.GradientDrawable().apply{
                setColor(bgColor)
                setStroke(dp(1),borderColor)
                cornerRadius=dp(14).toFloat()
            }
        }
        animatePress(b)
    }

    private fun section(p:LinearLayout,t:String){val v=TextView(this).apply{text=t;textSize=18f;setTextColor(white);typeface=Typeface.DEFAULT_BOLD;setPadding(dp(3),dp(14),0,dp(9))};p.addView(v)}
    private fun item(p:LinearLayout,n:String,v:String,valueSink:((TextView)->Unit)?=null){
        val grid=currentGrid(p)
        val b=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(14),dp(12),dp(14),dp(12))
            minimumHeight=dp(78)
            background=rounded(card,18)
        }
        val a=TextView(this).apply{
            text=n
            textSize=14f
            setTextColor(gray)
            maxLines=2
            ellipsize=android.text.TextUtils.TruncateAt.END
        }
        val c=TextView(this).apply{
            text=v
            textSize=16f
            setTextColor(white)
            setPadding(0,dp(5),0,0)
            maxLines=2
            ellipsize=android.text.TextUtils.TruncateAt.END
        }
        b.addView(a,LinearLayout.LayoutParams(-1,-2))
        b.addView(c,LinearLayout.LayoutParams(-1,-2))
        valueSink?.invoke(c)
        grid.addView(b,android.widget.GridLayout.LayoutParams().apply{
            width=0
            height=android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            columnSpec=android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1f)
            setMargins(dp(4),dp(4),dp(4),dp(4))
        })
    }
    private fun currentGrid(p:LinearLayout):android.widget.GridLayout{
        val last=p.getChildAt(p.childCount-1)
        if(last is android.widget.GridLayout && last.tag=="ROOTREALM_GRID") return last
        val g=android.widget.GridLayout(this).apply{columnCount=2;tag="ROOTREALM_GRID"}
        p.addView(g,LinearLayout.LayoutParams(-1,-2)); return g
    }
    private fun title(p:LinearLayout,t:String){val v=TextView(this).apply{text=t;textSize=26f;setTextColor(white);typeface=Typeface.DEFAULT_BOLD;setPadding(0,0,0,dp(20))};p.addView(v)}
    private fun readRoot(path:String):String?=try{val p=Runtime.getRuntime().exec(arrayOf("su","-c","cat '${path.replace("'","")}' 2>/dev/null"));p.waitFor();BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()?.takeIf{it.isNotEmpty()}}catch(_:Exception){null}
    private fun writable(path:String)=try{val p=Runtime.getRuntime().exec(arrayOf("su","-c","test -w '$path'"));p.waitFor()==0}catch(_:Exception){false}
    private fun writeRoot(path:String,value:String)=writeRootVerbose(path,value).first
    // Same write as before, but also captures the su/shell process's stderr so a
    // failure can say *why* (e.g. "Permission denied" from an SELinux denial) instead
    // of just "Write failed". Note: 'test -w' above only checks DAC permission bits;
    // it does NOT know about SELinux, so a path can report writable() == true and
    // still be blocked here by the kernel's MAC policy - that's the #1 real-world
    // cause of writable-but-fails-to-write charging control nodes on stock kernels.
    private fun writeRootVerbose(path:String,value:String):Pair<Boolean,String?> = try {
        val shortName = path.substringAfterLast('/')
        termLog("Changing setting: $shortName → $value")
        val safe=value.replace("'","'\\''")
        val proc=Runtime.getRuntime().exec(arrayOf("su","-c","printf '%s' '$safe' > '$path'"))
        val exit=proc.waitFor()
        val err=BufferedReader(InputStreamReader(proc.errorStream)).readText().trim()
        val ok = exit==0
        if (ok) {
            termLog("Done.")
        } else {
            val reason = when {
                err.contains("Permission", true) || err.contains("denied", true) ->
                    "Phone security blocked it (permission denied)."
                err.isNotBlank() -> err
                else -> "Write failed (exit $exit)."
            }
            termLog("Failed: $reason")
            termLog("Tip: this can make charging stay slow.")
        }
        ok to err.takeIf{it.isNotEmpty()}
    } catch(e:Exception){
        termLog("Failed: ${e.message ?: "unknown error"}")
        false to e.message
    }
    private fun getBatteryProperty(path:String)=readRoot(path)
    private fun findBatteryProperty(paths:List<String>):String? {
        val direct=paths.firstNotNullOfOrNull{readRoot(it)}
        if(direct!=null) return direct
        return try {
            val names=paths.map{it.substringAfterLast('/')}.distinct()
            for(name in names){
                val cmd="for f in /sys/class/power_supply/*/${name}; do [ -r \"${'$'}f\" ] && cat \"${'$'}f\" && exit; done"
                val proc=Runtime.getRuntime().exec(arrayOf("su","-c",cmd))
                if(!proc.waitFor(1200,TimeUnit.MILLISECONDS)){proc.destroy();continue}
                val value=BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim()
                if(!value.isNullOrEmpty()) return value
            }
            null
        } catch(_:Exception){null}
    }
    private fun estimateHealthFromBatteryManager():String = "Not available on this device"
    private fun displayValue(v:String)=when{v=="1"->"Enabled";v=="0"->"Disabled";else->v}
    private fun formatChargingControlValue(label:String, v:String):String {
        if (v == "1" || v == "0") return displayValue(v)
        val raw=v.toLongOrNull() ?: return v
        return when (label) {
            "Charge Current Limit" -> {
                val amps=raw / 1_000_000.0
                String.format(java.util.Locale.US,"%.2f A",amps)
            }
            else -> v
        }
    }
    private fun parseChargingControlValue(label:String, input:String):String? {
        if (label != "Charge Current Limit") return input.takeIf { it.isNotEmpty() }
        val normalized=input.replace(",",".").trim().lowercase()
        return try {
            when {
                normalized.endsWith("ma") -> {
                    val ma=normalized.removeSuffix("ma").trim().toDouble()
                    if(ma>0) (ma*1000.0).toLong().toString() else null
                }
                normalized.endsWith("a") -> {
                    val amps=normalized.removeSuffix("a").trim().toDouble()
                    if(amps>0) (amps*1_000_000.0).toLong().toString() else null
                }
                else -> {
                    val n=normalized.toDouble()
                    if(n<=0) null
                    else if(n <= 20.0) (n*1_000_000.0).toLong().toString()
                    else if(n <= 20_000.0) (n*1000.0).toLong().toString()
                    else n.toLong().toString()
                }
            }
        } catch(_:NumberFormatException) { null }
    }

    private fun formatDiagnosticValue(name:String, v:String):String {
        val raw=v.toLongOrNull() ?: return v
        return when (name) {
            "Battery Cycle Count" -> "${raw} cycles"
            "Charge Full Design", "Charge Full" -> formatCapacity(raw)
            else -> v
        }
    }
    private fun formatCurrentUa(ua:Long):String {
        return if (kotlin.math.abs(ua) >= 1_000_000L) String.format(java.util.Locale.US,"%.2f A",ua/1_000_000.0) else "${ua/1000} mA"
    }
    private fun formatCurrent(v:String)=v.toLongOrNull()?.let{"${kotlin.math.abs(it)/1000} mA"}?:v
    private fun formatChargingPower(currentNowUa:String):String {
        val currentUa=currentNowUa.toLongOrNull() ?: return "Unknown"
        val(voltageMv,usedFallback)=readNegotiatedVoltageMv()
        val watts=kotlin.math.abs(currentUa).toDouble()*voltageMv.toDouble()/1_000_000_000.0
        val base="%.1f W".format(watts)
        return if(usedFallback) "$base (est., 5V assumed)" else base
    }
    private fun formatCapacity(v:Long):String="${v/1000} mAh"
    private fun formatEnergyAsCapacity(uwH:Long):String="${uwH/1000} mWh"
    private fun healthPercent(full:Long,design:Long):String {
        if(full <= 0L || design <= 0L) return "Unknown"
        val percent=(full*100.0/design).coerceIn(0.0,100.0)
        return String.format(java.util.Locale.US,"%.0f%%",percent)
    }
    private fun batteryStatus(s:Int)=when(s){BatteryManager.BATTERY_STATUS_CHARGING->"Charging";BatteryManager.BATTERY_STATUS_FULL->"Full";BatteryManager.BATTERY_STATUS_DISCHARGING->"Discharging";BatteryManager.BATTERY_STATUS_NOT_CHARGING->"Not charging";else->"Unknown"}
    private fun powerSource(p:Int)=when(p){BatteryManager.BATTERY_PLUGGED_USB->"USB";BatteryManager.BATTERY_PLUGGED_AC->"AC";BatteryManager.BATTERY_PLUGGED_WIRELESS->"Wireless";else->"Battery"}
    private fun health(h:Int)=when(h){BatteryManager.BATTERY_HEALTH_GOOD->"Good";BatteryManager.BATTERY_HEALTH_OVERHEAT->"Overheat";BatteryManager.BATTERY_HEALTH_DEAD->"Dead";BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE->"Over voltage";BatteryManager.BATTERY_HEALTH_COLD->"Cold";else->"Unknown"}
    private fun rounded(c:Int,r:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
    private fun toast(s:String)=showToast(s, Toast.LENGTH_SHORT)

}
