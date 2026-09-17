package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ThermalManagerActivity : Activity() {

    private data class Option(
        val label: String,
        val value: String
    )

    private data class CpuPolicy(
        val name: String,
        val governorPath: String,
        val minPath: String,
        val maxPath: String,
        val governors: List<Option>,
        val frequencies: List<Option>,
        val originalGovernor: String,
        val originalMin: String,
        val originalMax: String,
        var governorSpinner: Spinner? = null,
        var minSpinner: Spinner? = null,
        var maxSpinner: Spinner? = null,
        var selected: Boolean = false
    )

    private data class GpuPolicy(
        val name: String,
        val dir: String,
        val governorPath: String,
        val minPath: String,
        val maxPath: String,
        val governors: List<Option>,
        val frequencies: List<Option>,
        val originalGovernor: String,
        val originalMin: String,
        val originalMax: String,
        var governorSpinner: Spinner? = null,
        var minSpinner: Spinner? = null,
        var maxSpinner: Spinner? = null,
        var selected: Boolean = false
    )

    private data class GenericControl(
        val title: String,
        val path: String,
        val options: List<Option>,
        val originalValue: String,
        var spinner: Spinner? = null,
        var selected: Boolean = false
    )

    private data class ThermalZone(
        val path: String,
        val type: String,
        val temperature: String
    )

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private val executor: ExecutorService =
        Executors.newFixedThreadPool(3)

    private val handler =
        Handler(Looper.getMainLooper())

    private var scanFuture: Future<*>? = null
    private var scanning = false
    private var rootEnabled = false

    private val cpuPolicies =
        mutableListOf<CpuPolicy>()

    private val cpuControls =
        mutableListOf<GenericControl>()

    private val gpuPolicies =
        mutableListOf<GpuPolicy>()

    private val gpuControls =
        mutableListOf<GenericControl>()

    private val thermalControls =
        mutableListOf<GenericControl>()

    private val thermalZones =
        mutableListOf<ThermalZone>()

    private lateinit var temperatureValue: TextView
    private lateinit var temperatureStatus: TextView
    private var temperatureMonitorRunning = false

    private val temperatureMonitor = object : Runnable {
        override fun run() {
            if (!temperatureMonitorRunning) return
            executor.submit {
                val snapshot = readLiveTemperature()
                handler.post {
                    if (!temperatureMonitorRunning) return@post
                    temperatureValue.text = snapshot.first
                    temperatureStatus.text = snapshot.second
                    scheduleTemperatureMonitor()
                }
            }
        }
    }

    private lateinit var terminalOutput: TextView
    private lateinit var terminalScroll: ScrollView
    private val logBuffer = StringBuilder()

    // All UI colors come from the global Theme Manager.
    private val BG get() = ThemeManager.current(this).background
    private val CARD get() = ThemeManager.current(this).surface
    private val CARD2 get() = ThemeManager.current(this).surface2
    private val BORDER get() = ThemeManager.borderColor(ThemeManager.current(this))
    private val TEXT get() = ThemeManager.current(this).text
    private val SECONDARY get() = ThemeManager.current(this).secondary
    private val PRIMARY get() = ThemeManager.current(this).accent
    private val WARNING get() = ThemeManager.current(this).accent
    private val DANGER get() = ThemeManager.current(this).error

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        buildBase()
        setupSystemBars()

        // detectRoot() shells out to su and can take up to its
        // own timeout to return — never do that on the UI thread.
        // Start the scan immediately in the background. The UI is already
        // visible, so a slow/blocked root manager cannot delay opening.
        executor.submit {
            val detected = detectRoot()
            handler.post {
                rootEnabled = detected
                scanHardwareAsync()
            }
        }
    }

    override fun onDestroy() {
        temperatureMonitorRunning = false
        handler.removeCallbacks(temperatureMonitor)
        handler.removeCallbacksAndMessages(null)
        scanFuture?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun setupSystemBars() {

        window.statusBarColor = BG
        window.navigationBarColor = BG

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true)
        }

        window.decorView.systemUiVisibility = 0

        if (Build.VERSION.SDK_INT >= 30) {

            scrollInsetsListener()

        } else {

            scroll.setPadding(
                0,
                dp(24),
                0,
                dp(24)
            )
        }
    }

    private fun scrollInsetsListener() {

        scroll.setOnApplyWindowInsetsListener {
                view,
                insets ->

            val top =
                insets.getInsets(
                    WindowInsets.Type.statusBars()
                ).top

            val bottom =
                insets.getInsets(
                    WindowInsets.Type.navigationBars()
                ).bottom

            view.setPadding(
                0,
                top + dp(12),
                0,
                bottom + dp(20)
            )

            insets
        }

        scroll.requestApplyInsets()
    }

    private fun buildBase() {

        scroll = ScrollView(this).apply {

            setBackgroundColor(BG)

            isFillViewport = true

            // Must clip to padding, or content scrolls up
            // underneath/behind the status bar inset area.
            clipToPadding = true

            overScrollMode =
                View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        content = LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setBackgroundColor(BG)

            setPadding(
                dp(16),
                dp(8),
                dp(16),
                dp(28)
            )

            clipChildren = false
            clipToPadding = false
        }

        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(scroll)

        addTitle()

        showLoading()
    }

    private fun addTitle() {

        val title = TextView(this).apply {

            text = "Android Toolbox"

            textSize = 27f

            setTextColor(TEXT)

            typeface =
                Typeface.DEFAULT_BOLD

            includeFontPadding = true

            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                0,
                dp(2),
                0,
                dp(2)
            )
        }

        content.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(2)
            }
        )

        val subtitle = TextView(this).apply {

            text =
                "Thermal Manager"

            textSize = 14f

            setTextColor(SECONDARY)

            includeFontPadding = true

            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                0,
                dp(2),
                0,
                dp(2)
            )
        }

        content.addView(
            subtitle,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )
    }

    private fun showLoading() {

        if (content.childCount > 2) {

            content.removeViews(
                2,
                content.childCount - 2
            )
        }

        val box = createCard()

        val text = TextView(this).apply {

            text =
                if (scanning) {
                    "Scanning hardware…"
                } else {
                    "Preparing hardware…"
                }

            textSize = 15f

            setTextColor(TEXT)

            includeFontPadding = true

            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                0,
                dp(8),
                0,
                dp(8)
            )
        }

        box.addView(
            text,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )
    }

    private fun toast(
        message: String
    ) {
        runOnUiThread {

            showToast(message, Toast.LENGTH_SHORT)
        }
    }

    /**
     * Themed toast with no icon. See BaseActivity.showToast() in the
     * other Activities for the full rationale — this Activity is the
     * one screen that extends Activity() directly rather than
     * BaseActivity(), so it needs its own copy rather than inheriting
     * one.
     */
    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val palette = ThemeManager.current(this)

        val toastView = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(palette.text)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBackground(
                palette.surface2,
                ThemeManager.borderColor(palette),
                12
            )
        }

        // Not built with .apply{}: inside a Toast.apply block, an
        // unqualified `duration` would resolve to the Toast receiver's
        // own `duration` property instead of this function's parameter
        // of the same name, silently no-op'ing the assignment.
        val toast = Toast(this)
        toast.duration = duration
        toast.view = toastView
        toast.show()
    }

    private fun dp(
        value: Int
    ): Int {
        return (
            value *
                resources.displayMetrics.density
        ).toInt().coerceAtLeast(1)
    }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int,
        radius: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(fillColor)

            cornerRadius =
                dp(radius).toFloat()

            setStroke(
                dp(1),
                strokeColor
            )
        }
    }

    private fun createCard():
        LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            background =
                roundedBackground(
                    CARD,
                    BORDER,
                    22
                )

            setPadding(
                dp(16),
                dp(16),
                dp(16),
                dp(16)
            )

            clipChildren = false
            clipToPadding = false
        }
    }

    private fun addSectionTitle(
        title: String,
        subtitle: String = ""
    ) {

        val wrapper =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(2),
                    dp(12),
                    dp(2),
                    dp(8)
                )
            }

        val titleView =
            TextView(this).apply {

                text =
                    title.uppercase(
                        Locale.US
                    )

                textSize = 12f

                setTextColor(PRIMARY)

                typeface =
                    Typeface.DEFAULT_BOLD

                letterSpacing = 0.12f

                includeFontPadding = true

                setPadding(
                    dp(2),
                    dp(4),
                    dp(2),
                    dp(4)
                )
            }

        wrapper.addView(
            titleView,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        if (subtitle.isNotBlank()) {

            val subtitleView =
                TextView(this).apply {

                    text = subtitle

                    textSize = 12f

                    setTextColor(
                        SECONDARY
                    )

                    includeFontPadding = true

                    setPadding(
                        0,
                        dp(2),
                        0,
                        dp(2)
                    )
                }

            wrapper.addView(
                subtitleView,
                LinearLayout.LayoutParams(
                    -1,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                }
            )
        }

        content.addView(
            wrapper,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addEmptyCard(
        message: String
    ) {

        val box = createCard()

        val text =
            TextView(this).apply {

                this.text = message

                textSize = 14f

                setTextColor(SECONDARY)

                includeFontPadding = true

                setLineSpacing(
                    dp(2).toFloat(),
                    1.0f
                )
            }

        box.addView(
            text,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }
        private fun detectRoot(): Boolean {

        val result =
            runRoot(
                "id",
                800
            )

        return result.first &&
            result.second.contains(
                "uid=0"
            )
    }

    private fun runRoot(
        command: String,
        timeoutMs: Long
    ): Pair<Boolean, String> {

        var process: Process? = null

        return try {

            process =
                ProcessBuilder(
                    "su",
                    "-c",
                    command
                )
                    .redirectErrorStream(true)
                    .start()

            // Read the stream on its own thread so a partial line
            // with no trailing newline can never block this call
            // past timeoutMs (readLine()/ready() do not guarantee
            // that — this was the cause of the indefinite hang).
            val outputBytes =
                java.io.ByteArrayOutputStream()

            val pumpThread = Thread {
                try {
                    process.inputStream.copyTo(outputBytes)
                } catch (_: Exception) {
                }
            }

            pumpThread.isDaemon = true
            pumpThread.start()

            val exited =
                process.waitFor(
                    timeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )

            if (!exited) {

                process.destroyForcibly()

                pumpThread.join(200)

                return Pair(
                    false,
                    outputBytes.toString(
                        Charsets.UTF_8.name()
                    )
                )
            }

            pumpThread.join(500)

            Pair(
                process.exitValue() == 0,
                outputBytes.toString(
                    Charsets.UTF_8.name()
                ).trim()
            )

        } catch (_: Exception) {

            try {
                process?.destroyForcibly()
            } catch (_: Exception) {
            }

            Pair(false, "")
        }
    }

    private fun safePath(
        path: String
    ): Boolean {

        return path.startsWith(
            "/sys/devices/system/cpu/"
        ) ||
        path.startsWith(
            "/sys/class/kgsl/"
        ) ||
        path.startsWith(
            "/sys/class/devfreq/"
        ) ||
        path.startsWith(
            "/sys/class/thermal/"
        ) ||
        path.startsWith(
            "/sys/devices/virtual/thermal/"
        ) ||
        path.startsWith(
            "/sys/module/cpu_boost/"
        )
    }

    private fun shellQuote(
        value: String
    ): String {

        return "'" +
            value.replace(
                "'",
                "'\\''"
            ) +
            "'"
    }

    private fun rootRead(
        path: String
    ): String {

        if (!safePath(path)) {
            return ""
        }

        return runRoot(
            "cat ${shellQuote(path)} 2>/dev/null",
            700
        ).second.trim()
    }

    private fun writable(
        path: String
    ): Boolean {

        if (!safePath(path)) {
            return false
        }

        return runRoot(
            "test -w ${shellQuote(path)}",
            500
        ).first
    }

    /**
     * Checks writability AND reads content for many paths using a
     * SINGLE su invocation, instead of one su spawn per path per
     * check. Scanning a thermal zone alone can involve ~100
     * candidate sysfs files; at 2 su spawns each (writable + read)
     * that's ~200 process spawns per zone, times however many
     * zones the device exposes — the actual cause of the scan
     * appearing to hang forever. This runs it all as one shell
     * script under one su session instead.
     *
     * Returns a map of path -> Pair(writable, content).
     * Paths that fail safePath() are skipped entirely.
     */
    private fun batchProbe(
        paths: List<String>
    ): Map<String, Pair<Boolean, String>> {

        val filtered =
            paths.distinct()
                .filter { safePath(it) }

        if (filtered.isEmpty()) {
            return emptyMap()
        }

        val script = StringBuilder()

        // Some devices have a sysfs/driver read that intermittently
        // blocks at the kernel level (observed on thermal trip
        // point files). A plain `cat` on that one file can hang
        // the whole batch until the outer su timeout below fires.
        // Bound each individual read with `timeout` (toybox, present
        // since Android 6) so a single bad file costs ~1s instead
        // of dragging out — or blowing — the entire batch. Falls
        // back to plain cat if `timeout` isn't available.
        script.append(
            "HAVE_TIMEOUT=0\n" +
                "if command -v timeout >/dev/null 2>&1; " +
                "then HAVE_TIMEOUT=1; fi\n"
        )

        for ((i, p) in filtered.withIndex()) {

            val q = shellQuote(p)

            script.append("echo '##").append(i).append("##'\n")
            script.append("if [ -w ").append(q).append(" ]; then echo 1; else echo 0; fi\n")
            script.append(
                "if [ \"\$HAVE_TIMEOUT\" = \"1\" ]; then " +
                    "timeout -s KILL 0.35 cat ${q} 2>/dev/null; " +
                    "else cat ${q} 2>/dev/null; fi\n"
            )
        }

        // Per-file work inside one shell is cheap (no su spawn per
        // file). With each read now individually bounded to ~1s
        // worst case, size the overall timeout to cover every file
        // hitting that worst case, capped well under the scan-wide
        // watchdog so this can never be the thing that blows past it.
        val timeout =
            (1200L + (filtered.size * 450L))
                .coerceAtMost(6000L)

        val result =
            runRoot(
                script.toString(),
                timeout
            )

        val marker =
            Regex("##(\\d+)##\\n")

        val indices =
            marker.findAll(result.second)
                .map { it.groupValues[1].toIntOrNull() }
                .toList()

        val blocks =
            marker.split(result.second)

        val map =
            HashMap<String, Pair<Boolean, String>>()

        for ((order, idx) in indices.withIndex()) {

            if (idx == null) {
                continue
            }

            val path =
                filtered.getOrNull(idx)
                    ?: continue

            val block =
                blocks.getOrNull(order + 1)
                    ?: ""

            val lines =
                block.split(
                    "\n",
                    limit = 2
                )

            val isWritable =
                lines.getOrNull(0)
                    ?.trim() == "1"

            val content =
                lines.getOrNull(1)
                    ?.trim()
                    ?: ""

            map[path] = Pair(isWritable, content)
        }

        return map
    }

    private fun writeValue(
        path: String,
        value: String
    ): Boolean {

        if (!rootEnabled) {
            return false
        }

        if (!safePath(path)) {
            return false
        }

        if (!writable(path)) {
            return false
        }

        val ok = runRoot(
            "printf '%s' ${shellQuote(value)} > ${shellQuote(path)}",
            1800
        ).first
        termLog("› write $path = $value → ${if (ok) "OK" else "FAIL"}")
        return ok
    }

    // Hard ceiling on total scan time. Even if a subsystem's su
    // calls somehow ignore our own per-call timeouts (some root
    // managers can leave orphaned work behind a killed client
    // process), the UI is guaranteed to leave the loading screen
    // once this fires — never stuck forever.
    private val SCAN_WATCHDOG_MS = 8000L

    private val scanWatchdog = Runnable {

        if (scanning) {

            scanning = false

            scanFuture?.cancel(true)

            toast(
                "Scan took too long — showing what was found"
            )

            rebuildContent()
        }
    }

    private fun scanHardwareAsync() {

        if (scanning) {
            return
        }

        scanning = true

        showLoading()

        scanFuture?.cancel(true)

        handler.removeCallbacks(scanWatchdog)

        handler.postDelayed(
            scanWatchdog,
            SCAN_WATCHDOG_MS
        )

        scanFuture =
            executor.submit {

                try {

                    termLog("Scanning hardware…")
                    scanHardware()
                    termLog("Scan finished. zones=${thermalZones.size}, controls=${thermalControls.size}")

                } finally {

                    // finally, not just the try body: guarantees
                    // scanning always clears and the UI always
                    // rebuilds even if something above throws or
                    // this thread gets interrupted mid-scan.
                    handler.post {

                        handler.removeCallbacks(
                            scanWatchdog
                        )

                        if (scanning) {

                            scanning = false

                            rebuildContent()
                        }
                    }
                }
            }
    }

    private fun scanHardware() {

        cpuPolicies.clear()
        cpuControls.clear()
        gpuPolicies.clear()
        gpuControls.clear()
        thermalControls.clear()
        thermalZones.clear()

        if (!rootEnabled) {
            // Nothing writable without root — skip straight to
            // showing the "Root access unavailable" state instead
            // of attempting (and waiting out timeouts on) su calls
            // that can only fail.
            return
        }

        // Each subsystem is isolated: a crash or hang-avoidance
        // exception in one must never prevent the others from
        // being scanned and shown.
        try {
            scanThermal()
        } catch (_: Exception) {
        }
    }


    private fun scanCpu() {

        val result =
            runRoot(
                "find /sys/devices/system/cpu/cpufreq " +
                    "-maxdepth 1 -type d " +
                    "-name 'policy*' 2>/dev/null",
                1500
            )

        val policies =
            result.second
                .lineSequence()
                .map { it.trim() }
                .filter {
                    it.startsWith(
                        "/sys/devices/system/cpu/"
                    )
                }
                .sortedBy {

                    it.substringAfterLast(
                        "policy"
                    ).toIntOrNull()
                        ?: 9999
                }
                .toList()

        for (dir in policies) {

            val governorPath =
                "$dir/scaling_governor"

            val minPath =
                "$dir/scaling_min_freq"

            val maxPath =
                "$dir/scaling_max_freq"

            val governor =
                rootRead(governorPath)

            val min =
                rootRead(minPath)

            val max =
                rootRead(maxPath)

            if (
                governor.isBlank() &&
                min.isBlank() &&
                max.isBlank()
            ) {
                continue
            }

            val governors =
                createOptions(
                    rootRead(
                        "$dir/scaling_available_governors"
                    )
                )

            val frequencies =
                parseFrequencyOptions(
                    rootRead(
                        "$dir/scaling_available_frequencies"
                    )
                )

            cpuPolicies.add(
                CpuPolicy(
                    name =
                        dir.substringAfterLast("/"),
                    governorPath =
                        governorPath,
                    minPath =
                        minPath,
                    maxPath =
                        maxPath,
                    governors =
                        governors,
                    frequencies =
                        frequencies,
                    originalGovernor =
                        governor,
                    originalMin =
                        min,
                    originalMax =
                        max
                )
            )
        }

        scanCpuAdvanced()
    }

    private fun scanCpuAdvanced() {

        val paths = listOf(

            Pair(
                "CPU Boost",
                "/sys/devices/system/cpu/cpufreq/boost"
            ),

            Pair(
                "Input Boost Enabled",
                "/sys/devices/system/cpu/cpufreq/input_boost_enabled"
            ),

            Pair(
                "Input Boost Enabled",
                "/sys/module/cpu_boost/parameters/input_boost_enabled"
            ),

            Pair(
                "Input Boost Duration",
                "/sys/module/cpu_boost/parameters/input_boost_ms"
            ),

            Pair(
                "Dynamic Stune Boost",
                "/sys/module/cpu_boost/parameters/dynamic_stune_boost"
            ),

            Pair(
                "Input Boost Duration",
                "/sys/module/cpu_boost/parameters/input_boost_ms"
            )
        )

        for (item in paths) {

            addAdvancedControl(
                item.first,
                item.second,
                cpuControls
            )
        }
    }

    private fun addAdvancedControl(
        title: String,
        path: String,
        target: MutableList<GenericControl>
    ) {

        if (!writable(path)) {
            return
        }

        val current =
            rootRead(path)

        if (current.isBlank()) {
            return
        }

        val options =
            createValueOptions(
                path,
                current
            )

        if (options.isEmpty()) {
            return
        }

        if (
            target.any {
                it.path == path
            }
        ) {
            return
        }

        target.add(
            GenericControl(
                title = title,
                path = path,
                options = options,
                originalValue = current
            )
        )
    }

    private fun scanGpu() {

        val dirs =
            linkedSetOf<String>()

        dirs.add(
            "/sys/class/kgsl/kgsl-3d0"
        )

        val kgsl =
            runRoot(
                "find -L /sys/class/kgsl/kgsl-3d0 " +
                    "-maxdepth 1 -type d 2>/dev/null",
                3000
            )

        kgsl.second
            .lineSequence()
            .map { it.trim() }
            .filter {
                it.isNotBlank()
            }
            .forEach {
                dirs.add(it)
            }

        val devfreq =
            runRoot(
                "find -L /sys/class/devfreq " +
                    "-maxdepth 1 -type d 2>/dev/null",
                3000
            )

        devfreq.second
            .lineSequence()
            .map { it.trim() }
            .filter {
                it.contains(
                    "gpu",
                    true
                ) ||
                it.contains(
                    "kgsl",
                    true
                ) ||
                it.contains(
                    "3d",
                    true
                )
            }
            .forEach {
                dirs.add(it)
            }

        for (dir in dirs) {

            if (!safePath(dir)) {
                continue
            }

            scanGpuDirectory(dir)
        }
    }

    private fun scanGpuPolicy(
        dir: String
    ): Set<String> {

        val claimed =
            mutableSetOf<String>()

        val governorPath =
            "$dir/governor"

        val governor =
            if (writable(governorPath)) {
                rootRead(governorPath)
            } else {
                ""
            }

        if (governor.isNotBlank()) {
            claimed.add("governor")
        }

        val minCandidates =
            listOf(
                "min_freq",
                "scaling_min_freq"
            )

        val maxCandidates =
            listOf(
                "max_freq",
                "scaling_max_freq"
            )

        var minName = ""
        var minValue = ""

        for (name in minCandidates) {

            val path = "$dir/$name"

            if (!writable(path)) {
                continue
            }

            val value = rootRead(path)

            if (value.isNotBlank()) {
                minName = name
                minValue = value
                break
            }
        }

        var maxName = ""
        var maxValue = ""

        for (name in maxCandidates) {

            val path = "$dir/$name"

            if (!writable(path)) {
                continue
            }

            val value = rootRead(path)

            if (value.isNotBlank()) {
                maxName = name
                maxValue = value
                break
            }
        }

        if (minName.isNotBlank()) {
            claimed.add(minName)
        }

        if (maxName.isNotBlank()) {
            claimed.add(maxName)
        }

        if (
            governor.isBlank() &&
            minName.isBlank() &&
            maxName.isBlank()
        ) {
            return emptySet()
        }

        val governors =
            createOptions(
                rootRead(
                    "$dir/available_governors"
                )
            )

        val availableFreqText =
            rootRead(
                "$dir/available_frequencies"
            ).ifBlank {
                rootRead(
                    "$dir/scaling_available_frequencies"
                )
            }

        val frequencies =
            parseFrequencyOptions(
                availableFreqText
            )

        gpuPolicies.add(
            GpuPolicy(
                name =
                    dir.substringAfterLast("/"),
                dir = dir,
                governorPath =
                    if (governor.isNotBlank()) {
                        governorPath
                    } else {
                        ""
                    },
                minPath =
                    if (minName.isNotBlank()) {
                        "$dir/$minName"
                    } else {
                        ""
                    },
                maxPath =
                    if (maxName.isNotBlank()) {
                        "$dir/$maxName"
                    } else {
                        ""
                    },
                governors = governors,
                frequencies = frequencies,
                originalGovernor = governor,
                originalMin = minValue,
                originalMax = maxValue
            )
        )

        return claimed
    }

    private fun scanGpuDirectory(
        dir: String
    ) {

        val claimed =
            scanGpuPolicy(dir)

        val names = listOf(

            "governor",
            "min_freq",
            "max_freq",

            "scaling_min_freq",
            "scaling_max_freq",
            "scaling_cur_freq",

            "available_frequencies",
            "scaling_available_frequencies",

            "available_pwrlevels",
            "min_pwrlevel",
            "max_pwrlevel",
            "pwrlevel",

            "force_bus_on",
            "force_clk_on",
            "force_rail_on",

            "idle_timer",
            "force_performance",

            "bus_split",
            "throttling",
            "adrenoboost",

            "gpu_busy_percentage",

            "default_pwrlevel",
            "num_pwrlevels"
        )

        // One su call for all 23 candidate names instead of up
        // to 46 separate su spawns (writable + read per name).
        val probed =
            batchProbe(
                names.map { "$dir/$it" }
            )

        for (name in names) {

            if (claimed.contains(name)) {
                continue
            }

            val path =
                "$dir/$name"

            val entry =
                probed[path]
                    ?: continue

            if (!entry.first) {
                continue
            }

            val current = entry.second

            if (current.isBlank()) {
                continue
            }

            val options =
                when {
                    name == "governor" -> {

                        createOptions(
                            rootRead(
                                "$dir/available_governors"
                            )
                        )
                    }

                    name.contains(
                        "freq",
                        true
                    ) -> {

                        val available =
                            rootRead(
                                "$dir/available_frequencies"
                            )

                        if (available.isBlank()) {
                            createValueOptions(
                                path,
                                current
                            )
                        } else {
                            parseFrequencyOptions(
                                available
                            )
                        }
                    }

                    name.contains(
                        "pwrlevel",
                        true
                    ) -> {

                        createOptions(
                            rootRead(
                                "$dir/available_pwrlevels"
                            )
                        ).ifEmpty {
                            createValueOptions(
                                path,
                                current
                            )
                        }
                    }

                    else -> {

                        createValueOptions(
                            path,
                            current
                        )
                    }
                }

            if (options.isEmpty()) {
                continue
            }

            val title =
                "GPU • " +
                    prettyName(name) +
                    " • " +
                    dir.substringAfterLast("/")

            if (
                gpuControls.none {
                    it.path == path
                }
            ) {

                gpuControls.add(
                    GenericControl(
                        title = title,
                        path = path,
                        options = options,
                        originalValue = current
                    )
                )
            }
        }
    }
        private fun scanThermal() {

        val result =
            runRoot(
                "find -L /sys/class/thermal " +
                    "-maxdepth 1 -type d " +
                    "-name 'thermal_zone*' " +
                    "2>/dev/null",
                3000
            )

        val zones =
            result.second
                .lineSequence()
                .map { it.trim() }
                .filter {
                    it.isNotBlank()
                }
                .sortedBy {

                    it.substringAfterLast("/")
                        .removePrefix(
                            "thermal_zone"
                        )
                        .toIntOrNull()
                        ?: 9999
                }
                .toList()

        for (zone in zones) {

            if (!safePath(zone)) {
                continue
            }

            val type =
                rootRead(
                    "$zone/type"
                ).ifBlank {
                    "Unknown"
                }

            val rawTemp =
                rootRead(
                    "$zone/temp"
                )

            thermalZones.add(
                ThermalZone(
                    path = zone,
                    type = type,
                    temperature =
                        formatTemperature(
                            rawTemp
                        )
                )
            )

            scanThermalControls(
                zone,
                type
            )
        }
    }

    private fun scanThermalControls(
        zone: String,
        type: String
    ) {

        val names =
            mutableListOf<String>()

        names.add("mode")
        names.add("polling_delay")
        names.add("polling_delay_passive")
        names.add("emul_temp")

        // Most Android devices expose a small number of trip points.
        // Limiting the probe set avoids dozens of slow root/sysfs calls
        // while still covering the normal thermal controls.
        for (i in 0..15) {

            names.add(
                "trip_point_${i}_temp"
            )

            names.add(
                "trip_point_${i}_hyst"
            )

            names.add(
                "trip_point_${i}_type"
            )
        }

        // One su call probes writability + content for every
        // candidate name in this zone, instead of up to 200
        // separate su spawns (writable + read per name).
        val probed =
            batchProbe(
                names.map { "$zone/$it" }
            )

        for (name in names) {

            val path =
                "$zone/$name"

            val entry =
                probed[path]
                    ?: continue

            if (!entry.first) {
                continue
            }

            val current = entry.second

            if (current.isBlank()) {
                continue
            }

            val options =
                if (
                    name.endsWith(
                        "_type"
                    )
                ) {
                    createOptions(
                        current
                    )
                } else {
                    createValueOptions(
                        path,
                        current
                    )
                }

            if (options.isEmpty()) {
                continue
            }

            thermalControls.add(
                GenericControl(
                    title =
                        "$type • " +
                            prettyName(name),
                    path = path,
                    options = options,
                    originalValue = current
                )
            )
        }
    }


    private fun createValueOptions(
        path: String,
        current: String
    ): List<Option> {

        val normalized =
            current.trim()
                .lowercase(Locale.US)

        if (
            normalized == "0" ||
            normalized == "1"
        ) {
            return listOf(
                Option(
                    "Off / 0",
                    "0"
                ),
                Option(
                    "On / 1",
                    "1"
                )
            )
        }

        if (
            normalized == "y" ||
            normalized == "n"
        ) {
            return listOf(
                Option(
                    "No / N",
                    "N"
                ),
                Option(
                    "Yes / Y",
                    "Y"
                )
            )
        }

        if (
            normalized == "on" ||
            normalized == "off"
        ) {
            return listOf(
                Option(
                    "Off",
                    "off"
                ),
                Option(
                    "On",
                    "on"
                )
            )
        }

        val available =
            when {

                path.contains(
                    "min_pwrlevel"
                ) ||
                path.contains(
                    "max_pwrlevel"
                ) -> {

                    rootRead(
                        path.substringBeforeLast("/") +
                            "/available_pwrlevels"
                    )
                }

                else -> ""
            }

        if (available.isNotBlank()) {

            val options =
                createOptions(
                    available
                )

            if (options.isNotEmpty()) {
                return options
            }
        }

        return listOf(
            Option(
                current.trim(),
                current.trim()
            )
        )
    }

    private fun createOptions(
        text: String
    ): List<Option> {

        return text
            .split(
                Regex("[\\s,]+")
            )
            .map {
                it.trim()
            }
            .filter {
                it.isNotEmpty()
            }
            .distinct()
            .map {
                Option(
                    label = it,
                    value = it
                )
            }
    }

    private fun parseFrequencyOptions(
        text: String
    ): List<Option> {

        return text
            .split(
                Regex("[\\s,]+")
            )
            .mapNotNull {

                it.trim()
                    .toLongOrNull()
            }
            .distinct()
            .sorted()
            .map {

                Option(
                    label =
                        formatFrequency(it),
                    value =
                        it.toString()
                )
            }
    }

    private fun formatFrequency(
        value: Long
    ): String {

        return when {

            value >= 1_000_000 -> {

                String.format(
                    Locale.US,
                    "%.2f GHz",
                    value / 1_000_000.0
                )
            }

            value >= 1_000 -> {

                String.format(
                    Locale.US,
                    "%.0f MHz",
                    value / 1_000.0
                )
            }

            else -> {

                "$value kHz"
            }
        }
    }

    private fun addTemperatureMonitorCard() {
        val card = createCard()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "THERMAL TEMPERATURE"
            textSize = 12f
            setTextColor(PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }

        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        temperatureStatus = TextView(this).apply {
            text = "Reading…"
            textSize = 12f
            setTextColor(SECONDARY)
        }
        header.addView(temperatureStatus)

        temperatureValue = TextView(this).apply {
            text = "—"
            textSize = 30f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, 0)
        }

        card.addView(header)
        card.addView(temperatureValue)

        val subtitleView = TextView(this).apply {
            text = "Thermal temperature monitor • live update"
            textSize = 11f
            setTextColor(SECONDARY)
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(subtitleView)

        content.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        })

        startTemperatureMonitor()
    }

    private fun startTemperatureMonitor() {
        temperatureMonitorRunning = true
        handler.removeCallbacks(temperatureMonitor)
        handler.post(temperatureMonitor)
    }

    private fun scheduleTemperatureMonitor() {
        if (temperatureMonitorRunning) handler.postDelayed(temperatureMonitor, 1500L)
    }

    private fun readLiveTemperature(): Pair<String, String> {
        val command = """
            for z in /sys/class/thermal/thermal_zone*; do
                [ -r "${'$'}z/type" ] || continue
                [ -r "${'$'}z/temp" ] || continue
                type=$(cat "${'$'}z/type" 2>/dev/null)
                temp=$(cat "${'$'}z/temp" 2>/dev/null)
                [ -n "${'$'}type" ] || continue
                [ -n "${'$'}temp" ] || continue
                echo "${'$'}type|${'$'}temp"
            done
        """.trimIndent()

        val output = runRoot(command, 2500).second
        val readings = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val raw = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
            val celsius = if (kotlin.math.abs(raw) >= 1000) raw / 1000.0 else raw.toDouble()
            parts[0].trim().lowercase(Locale.US) to celsius
        }.toList()

        if (readings.isEmpty()) return "N/A" to "No thermal sensor"

        val selected = when ("THERMAL") {
            "CPU" -> readings.filter { it.first.contains("cpu") || it.first.contains("cluster") }
            "GPU" -> readings.filter { it.first.contains("gpu") || it.first.contains("kgsl") || it.first.contains("gpufreq") }
            else -> readings
        }

        if (selected.isEmpty()) return "N/A" to "Dedicated sensor unavailable"

        val value = if ("THERMAL" == "THERMAL") selected.maxOf { it.second }
                   else selected.map { it.second }.average()
        val label = if ("THERMAL" == "THERMAL") "Peak thermal" else "Live"
        return String.format(Locale.US, "%.1f °C", value) to label
    }

    private fun formatTemperature(
        raw: String
    ): String {

        val value =
            raw.trim()
                .toLongOrNull()
                ?: return raw

        return when {

            value >= 1000 -> {

                String.format(
                    Locale.US,
                    "%.1f °C",
                    value / 1000.0
                )
            }

            else -> {

                "$value °C"
            }
        }
    }

    private fun rebuildContent() {

        if (content.childCount > 2) {

            content.removeViews(
                2,
                content.childCount - 2
            )
        }

        addRootStatus()
        addTemperatureMonitorCard()

        addThermalSection()

        addTerminalSection()

        addActions()
    }

    private fun addRootStatus() {

        val box = createCard()

        val label =
            TextView(this).apply {

                text = "ROOT ACCESS"

                textSize = 11f

                setTextColor(
                    SECONDARY
                )

                typeface =
                    Typeface.DEFAULT_BOLD

                includeFontPadding = true
            }

        box.addView(
            label,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val status =
            TextView(this).apply {

                text =
                    if (rootEnabled) {
                        "● Root access available"
                    } else {
                        "● Root access unavailable"
                    }

                textSize = 16f

                setTextColor(
                    if (rootEnabled) {
                        PRIMARY
                    } else {
                        DANGER
                    }
                )

                typeface =
                    Typeface.DEFAULT_BOLD

                includeFontPadding = true

                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(4)
                )
            }

        box.addView(
            status,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun addSummary() {

        val count =
            cpuControls.size +
                gpuPolicies.size +
                gpuControls.size +
                thermalControls.size

        val text =
            "CPU policies: ${cpuPolicies.size}\n" +
            "GPU policies: ${gpuPolicies.size}\n" +
            "GPU advanced controls: ${gpuControls.size}\n" +
            "Thermal zones: ${thermalZones.size}\n" +
            "Writable controls: $count"

        val box = createCard()

        val title =
            TextView(this).apply {

                this.text =
                    "HARDWARE SUMMARY"

                textSize = 11f

                setTextColor(
                    SECONDARY
                )

                typeface =
                    Typeface.DEFAULT_BOLD

                includeFontPadding = true
            }

        box.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val summary =
            TextView(this).apply {

                this.text = text

                textSize = 14f

                setTextColor(TEXT)

                includeFontPadding = true

                setLineSpacing(
                    dp(3).toFloat(),
                    1.0f
                )

                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(2)
                )
            }

        box.addView(
            summary,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun addCpuSection() {

        addSectionTitle(
            "CPU Manager",
            "Frequency, governors and advanced CPU controls"
        )

        if (cpuPolicies.isEmpty()) {

            addEmptyCard(
                "No CPU frequency policies were detected."
            )

        } else {

            for (policy in cpuPolicies) {

                addCpuPolicy(
                    policy
                )
            }
        }

        if (cpuControls.isNotEmpty()) {

            addAdvancedSection(
                "CPU Advanced Controls",
                cpuControls
            )
        }
    }

    private fun addGpuSection() {

        addSectionTitle(
            "GPU Manager",
            "Frequency, governor, power levels and advanced KGSL/devfreq controls"
        )

        if (gpuPolicies.isEmpty()) {

            addEmptyCard(
                "No GPU frequency or governor policies were detected."
            )

        } else {

            for (policy in gpuPolicies) {

                addGpuPolicy(
                    policy
                )
            }
        }

        if (gpuControls.isNotEmpty()) {

            addAdvancedSection(
                "GPU Advanced Controls",
                gpuControls
            )
        }
    }

    private fun addThermalSection() {

        addSectionTitle(
            "Thermal Manager",
            "Thermal zones, temperatures and writable thermal controls"
        )

        if (thermalZones.isEmpty()) {

            addEmptyCard(
                "No thermal zones were detected."
            )

        } else {

            for (zone in thermalZones) {

                addThermalZone(
                    zone
                )
            }
        }

        if (thermalControls.isNotEmpty()) {

            addAdvancedSection(
                "Thermal Controls",
                thermalControls
            )
        }
    }
        private fun addCpuPolicy(
        policy: CpuPolicy
    ) {

        val box = createCard()

        val title =
            TextView(this).apply {

                text = policy.name

                textSize = 17f

                setTextColor(TEXT)

                typeface =
                    Typeface.DEFAULT_BOLD

                includeFontPadding = true
            }

        box.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        )

        if (policy.governors.isNotEmpty()) {

            val spinner =
                addSpinnerRow(
                    box,
                    "Governor",
                    policy.governors,
                    policy.originalGovernor
                )

            policy.governorSpinner =
                spinner
        }

        if (policy.frequencies.isNotEmpty()) {

            val min =
                addSpinnerRow(
                    box,
                    "Minimum Frequency",
                    policy.frequencies,
                    policy.originalMin
                )

            policy.minSpinner =
                min

            val max =
                addSpinnerRow(
                    box,
                    "Maximum Frequency",
                    policy.frequencies,
                    policy.originalMax
                )

            policy.maxSpinner =
                max
        }

        content.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )
    }

    private fun addGpuPolicy(
        policy: GpuPolicy
    ) {

        val box = createCard()

        val title =
            TextView(this).apply {

                text = policy.name

                textSize = 17f

                setTextColor(TEXT)

                typeface =
                    Typeface.DEFAULT_BOLD

                includeFontPadding = true
            }

        box.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        )

        val pathHint =
            TextView(this).apply {

                text = policy.dir

                textSize = 11f

                setTextColor(SECONDARY)

                includeFontPadding = true
            }

        box.addView(
            pathHint,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        )

        if (
            policy.governorPath.isNotBlank() &&
            policy.governors.isNotEmpty()
        ) {

            val spinner =
                addSpinnerRow(
                    box,
                    "Governor",
                    policy.governors,
                    policy.originalGovernor
                )

            policy.governorSpinner =
                spinner
        }

        if (
            policy.minPath.isNotBlank() &&
            policy.frequencies.isNotEmpty()
        ) {

            val min =
                addSpinnerRow(
                    box,
                    "Minimum Frequency",
                    policy.frequencies,
                    policy.originalMin
                )

            policy.minSpinner =
                min
        }

        if (
            policy.maxPath.isNotBlank() &&
            policy.frequencies.isNotEmpty()
        ) {

            val max =
                addSpinnerRow(
                    box,
                    "Maximum Frequency",
                    policy.frequencies,
                    policy.originalMax
                )

            policy.maxSpinner =
                max
        }

        content.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )
    }

    private fun addSpinnerRow(
        parent: LinearLayout,
        label: String,
        options: List<Option>,
        current: String
    ): Spinner {

        val wrapper =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    0,
                    dp(3),
                    0,
                    dp(6)
                )
            }

        val labelView =
            TextView(this).apply {

                text = label

                textSize = 12f

                setTextColor(
                    SECONDARY
                )

                includeFontPadding = true
            }

        wrapper.addView(
            labelView,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(3)
            }
        )

        val spinner =
            Spinner(this).apply {

                background = null

                setPadding(
                    dp(8),
                    0,
                    dp(8),
                    0
                )

                adapter =
                    ArrayAdapter(
                        this@ThermalManagerActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        options.map {
                            it.label
                        }
                    )

                val index =
                    options.indexOfFirst {
                        it.value.trim() ==
                            current.trim()
                    }

                if (index >= 0) {
                    setSelection(index)
                }
            }

        val spinnerBox =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                background =
                    roundedBackground(
                        CARD2,
                        BORDER,
                        12
                    )

                setPadding(
                    dp(8),
                    0,
                    dp(8),
                    0
                )
            }

        spinnerBox.addView(
            spinner,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            )
        )

        wrapper.addView(
            spinnerBox,
            LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        )

        parent.addView(
            wrapper,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return spinner
    }

    private fun addAdvancedSection(
        title: String,
        controls: List<GenericControl>
    ) {

        val heading =
            TextView(this).apply {

                text = title

                textSize = 15f

                setTextColor(TEXT)

                typeface =
                    Typeface.DEFAULT_BOLD

                includeFontPadding = true

                setPadding(
                    dp(2),
                    dp(5),
                    dp(2),
                    dp(7)
                )
            }

        content.addView(
            heading,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        for (control in controls) {

            addGenericControl(
                control
            )
        }
    }

    private fun addGenericControl(
        control: GenericControl
    ) {

        val box = createCard()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title =
            TextView(this).apply {
                text = control.title
                textSize = 14f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = true
                setLineSpacing(
                    dp(2).toFloat(),
                    1.0f
                )
            }

        val select =
            CheckBox(this).apply {
                text = "SELECT"
                textSize = 10.5f
                setTextColor(SECONDARY)
                isChecked = control.selected
                setPadding(0, 0, 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    control.selected = checked
                }
            }

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        header.addView(
            select,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        box.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val path =
            TextView(this).apply {

                text = control.path

                textSize = 10f

                setTextColor(
                    SECONDARY
                )

                includeFontPadding = true

                maxLines = 3

                setLineSpacing(
                    dp(1).toFloat(),
                    1.0f
                )

                setPadding(
                    0,
                    dp(4),
                    0,
                    dp(6)
                )
            }

        box.addView(
            path,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val spinner =
            Spinner(this).apply {

                background = null

                setPadding(
                    dp(8),
                    0,
                    dp(8),
                    0
                )

                adapter =
                    ArrayAdapter(
                        this@ThermalManagerActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        control.options.map {
                            it.label
                        }
                    )

                val index =
                    control.options.indexOfFirst {
                        it.value.trim() ==
                            control.originalValue.trim()
                    }

                if (index >= 0) {
                    setSelection(index)
                }
            }

        control.spinner =
            spinner

        val spinnerBox =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                background =
                    roundedBackground(
                        CARD2,
                        BORDER,
                        12
                    )

                setPadding(
                    dp(8),
                    0,
                    dp(8),
                    0
                )
            }

        spinnerBox.addView(
            spinner,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            )
        )

        box.addView(
            spinnerBox,
            LinearLayout.LayoutParams(
                -1,
                dp(54)
            ).apply {
                topMargin = dp(3)
            }
        )

        content.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun addThermalZone(
        zone: ThermalZone
    ) {

        val box = createCard()

        val title =
            TextView(this).apply {

                text = zone.type

                textSize = 16f

                setTextColor(TEXT)

                typeface =
                    Typeface.DEFAULT_BOLD

                includeFontPadding = true
            }

        box.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val zoneName =
            TextView(this).apply {

                text =
                    zone.path.substringAfterLast(
                        "/"
                    )

                textSize = 11f

                setTextColor(
                    SECONDARY
                )

                includeFontPadding = true

                setPadding(
                    0,
                    dp(3),
                    0,
                    dp(4)
                )
            }

        box.addView(
            zoneName,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val temperature =
            TextView(this).apply {

                text =
                    if (
                        zone.temperature.isBlank()
                    ) {
                        "Temperature unavailable"
                    } else {
                        "Temperature: " +
                            zone.temperature
                    }

                textSize = 15f

                setTextColor(
                    if (
                        zone.temperature.isBlank()
                    ) {
                        SECONDARY
                    } else {
                        WARNING
                    }
                )

                typeface =
                    Typeface.DEFAULT_BOLD

                includeFontPadding = true

                setPadding(
                    0,
                    dp(4),
                    0,
                    dp(3)
                )
            }

        box.addView(
            temperature,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }


    private fun addTerminalSection() {

        addSectionTitle(
            "TERMINAL / LOG",
            "Commands and hardware scan output"
        )

        // Everything below this point belongs to the terminal card.
        // The log viewport clips its child so log text can never paint
        // over section titles or the controls below it.
        val box = createCard().apply {
            clipChildren = true
            clipToPadding = true
        }

        terminalOutput = TextView(this).apply {
            text = "THERMAL terminal ready.\n"
            setTextColor(Color.parseColor("#A8FF60"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setHorizontallyScrolling(false)
            includeFontPadding = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        terminalScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.parseColor("#0D1117"))
            clipChildren = true
            clipToPadding = true

            // This ScrollView is nested inside the page ScrollView.
            // Keep drag gestures in the terminal while the finger is
            // over the terminal viewport.
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }

            addView(
                terminalOutput,
                ViewGroup.LayoutParams(-1, -2)
            )
        }

        box.addView(
            terminalScroll,
            LinearLayout.LayoutParams(-1, dp(220))
        )

        val navigationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun terminalButton(label: String) =
            actionButton(label, TEXT).apply {
                textSize = 10.5f
                minHeight = dp(40)
                minimumHeight = dp(40)
            }

        val top = terminalButton("▲ TOP")
        val up = terminalButton("▲ UP")
        val down = terminalButton("▼ DOWN")
        val end = terminalButton("▼ END")

        listOf(top, up, down, end).forEachIndexed { i, button ->
            navigationRow.addView(
                button,
                LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (i < 3) rightMargin = dp(4)
                }
            )
        }

        box.addView(
            navigationRow,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
            }
        )

        val copyClearRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val copy = terminalButton("COPY LOG")
        val clear = terminalButton("CLEAR")

        copyClearRow.addView(
            copy,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                rightMargin = dp(4)
            }
        )

        copyClearRow.addView(
            clear,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(4)
            }
        )

        box.addView(
            copyClearRow,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
            }
        )

        top.setOnClickListener {
            terminalScroll.post {
                terminalScroll.fullScroll(View.FOCUS_UP)
            }
        }

        up.setOnClickListener {
            terminalScroll.smoothScrollBy(0, -dp(140))
        }

        down.setOnClickListener {
            terminalScroll.smoothScrollBy(0, dp(140))
        }

        end.setOnClickListener {
            terminalScroll.post {
                terminalScroll.fullScroll(View.FOCUS_DOWN)
            }
        }

        copy.setOnClickListener {
            val log = terminalOutput.text?.toString().orEmpty()

            if (log.isBlank()) {
                toast("Nothing to copy")
            } else {
                val clipboard =
                    getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "DroidBox Thermal Manager Log",
                        log
                    )
                )

                toast("Log copied")
            }
        }

        clear.setOnClickListener {
            logBuffer.clear()
            terminalOutput.text = "Log cleared.\n"
        }

        content.addView(
            box,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
        )
    }

    private fun termLog(message: String) {
        handler.post {
            if (!::terminalOutput.isInitialized) return@post
            if (logBuffer.isNotEmpty()) logBuffer.append('\n')
            logBuffer.append(message.trimEnd())
            if (logBuffer.length > 14000) logBuffer.delete(0, logBuffer.length - 12000)
            terminalOutput.text = logBuffer.toString()
            terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun addActions() {

        addSectionTitle(
            "Actions",
            "Changes are applied only to detected writable controls"
        )

        val apply =
            actionButton(
                "APPLY ALL",
                PRIMARY
            )

        apply.setOnClickListener {
            applyAll()
        }

        content.addView(
            apply,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val applySelectedButton =
            actionButton(
                "APPLY SELECTED ONLY",
                PRIMARY
            )

        applySelectedButton.setOnClickListener {
            applySelected()
        }

        content.addView(
            applySelectedButton,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val restore =
            actionButton(
                "RESTORE ORIGINAL",
                WARNING
            )

        restore.setOnClickListener {
            restoreAll()
        }

        content.addView(
            restore,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val rescan =
            actionButton(
                "RESCAN HARDWARE",
                TEXT
            )

        rescan.setOnClickListener {
            scanHardwareAsync()
        }

        content.addView(
            rescan,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            )
        )
    }

    private fun actionButton(
        text: String,
        color: Int
    ): Button {

        return Button(this).apply {

            this.text = text

            textSize = 13f

            isAllCaps = false

            typeface =
                Typeface.DEFAULT_BOLD

            gravity =
                Gravity.CENTER

            minHeight = dp(56)

            minimumHeight = dp(56)

            stateListAnimator = null

            isClickable = true
            isFocusable = true

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.animate()
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setDuration(70L)
                            .start()
                    }

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(70L)
                            .start()
                    }
                }
                false
            }

            if (color == TEXT) {

                // Tertiary action: outlined card style
                setTextColor(TEXT)

                background =
                    roundedBackground(
                        CARD,
                        BORDER,
                        16
                    )

            } else {

                // Primary/secondary actions: filled pill,
                // matching createButton() in BackupRestoreActivity
                setTextColor(TEXT)

                background =
                    roundedBackground(
                        color,
                        color,
                        16
                    )
            }

            setPadding(
                dp(8),
                0,
                dp(8),
                0
            )

            includeFontPadding = true
        }
    }


    private fun applySelectedGeneric(
        controls: List<GenericControl>
    ): Pair<Int, Int> {
        var success = 0
        var failed = 0

        for (control in controls) {
            if (!control.selected) continue

            val position = control.spinner?.selectedItemPosition ?: continue
            if (position !in control.options.indices) continue

            val value = control.options[position].value
            if (!writable(control.path)) continue

            if (writeValue(control.path, value)) success++ else failed++
        }

        return Pair(success, failed)
    }

    private fun applySelected() {
        if (!rootEnabled) {
            toast("Root access is required")
            return
        }

        if (scanning) {
            toast("Please wait for the scan to finish")
            return
        }

        val selectedCount =
            thermalControls.count { it.selected }

        if (selectedCount == 0) {
            toast("Select at least one control first")
            return
        }

        executor.submit {
            var success = 0
            var failed = 0

            val result =
                applySelectedGeneric(thermalControls)

            success += result.first
            failed += result.second

            handler.post {
                termLog(
                    "Selected apply finished: success=$success, failed=$failed"
                )
                toast("Selected: $success • Failed: $failed")
                scanHardwareAsync()
            }
        }
    }

    private fun applyAll() {

        if (!rootEnabled) {

            toast(
                "Root access is required"
            )

            return
        }

        if (scanning) {

            toast(
                "Please wait for the scan to finish"
            )

            return
        }

        executor.submit {

            var success = 0
            var failed = 0

            for (policy in cpuPolicies) {

                val governor =
                    policy.governorSpinner
                        ?.selectedItemPosition
                        ?.let {
                            if (
                                it in policy.governors.indices
                            ) {
                                policy.governors[
                                    it
                                ].value
                            } else {
                                null
                            }
                        }

                val min =
                    policy.minSpinner
                        ?.selectedItemPosition
                        ?.let {
                            if (
                                it in policy.frequencies.indices
                            ) {
                                policy.frequencies[
                                    it
                                ].value
                            } else {
                                null
                            }
                        }

                val max =
                    policy.maxSpinner
                        ?.selectedItemPosition
                        ?.let {
                            if (
                                it in policy.frequencies.indices
                            ) {
                                policy.frequencies[
                                    it
                                ].value
                            } else {
                                null
                            }
                        }

                if (
                    governor != null &&
                    writable(
                        policy.governorPath
                    )
                ) {

                    if (
                        writeValue(
                            policy.governorPath,
                            governor
                        )
                    ) {
                        success++
                    } else {
                        failed++
                    }
                }

                if (
                    min != null &&
                    writable(
                        policy.minPath
                    )
                ) {

                    if (
                        writeValue(
                            policy.minPath,
                            min
                        )
                    ) {
                        success++
                    } else {
                        failed++
                    }
                }

                if (
                    max != null &&
                    writable(
                        policy.maxPath
                    )
                ) {

                    if (
                        writeValue(
                            policy.maxPath,
                            max
                        )
                    ) {
                        success++
                    } else {
                        failed++
                    }
                }
            }

            for (policy in gpuPolicies) {

                val governor =
                    policy.governorSpinner
                        ?.selectedItemPosition
                        ?.let {
                            if (
                                it in policy.governors.indices
                            ) {
                                policy.governors[
                                    it
                                ].value
                            } else {
                                null
                            }
                        }

                val min =
                    policy.minSpinner
                        ?.selectedItemPosition
                        ?.let {
                            if (
                                it in policy.frequencies.indices
                            ) {
                                policy.frequencies[
                                    it
                                ].value
                            } else {
                                null
                            }
                        }

                val max =
                    policy.maxSpinner
                        ?.selectedItemPosition
                        ?.let {
                            if (
                                it in policy.frequencies.indices
                            ) {
                                policy.frequencies[
                                    it
                                ].value
                            } else {
                                null
                            }
                        }

                if (
                    governor != null &&
                    policy.governorPath.isNotBlank() &&
                    writable(
                        policy.governorPath
                    )
                ) {

                    if (
                        writeValue(
                            policy.governorPath,
                            governor
                        )
                    ) {
                        success++
                    } else {
                        failed++
                    }
                }

                if (
                    min != null &&
                    policy.minPath.isNotBlank() &&
                    writable(
                        policy.minPath
                    )
                ) {

                    if (
                        writeValue(
                            policy.minPath,
                            min
                        )
                    ) {
                        success++
                    } else {
                        failed++
                    }
                }

                if (
                    max != null &&
                    policy.maxPath.isNotBlank() &&
                    writable(
                        policy.maxPath
                    )
                ) {

                    if (
                        writeValue(
                            policy.maxPath,
                            max
                        )
                    ) {
                        success++
                    } else {
                        failed++
                    }
                }
            }

            success +=
                applyGeneric(
                    cpuControls
                ).first

            failed +=
                applyGeneric(
                    cpuControls
                ).second

            val gpuResult =
                applyGeneric(
                    gpuControls
                )

            success +=
                gpuResult.first

            failed +=
                gpuResult.second

            val thermalResult =
                applyGeneric(
                    thermalControls
                )

            success +=
                thermalResult.first

            failed +=
                thermalResult.second

            handler.post {

                toast(
                    "Applied: $success • Failed: $failed"
                )

                scanHardwareAsync()
            }
        }
    }

    private fun applyGeneric(
        controls: List<GenericControl>
    ): Pair<Int, Int> {

        var success = 0
        var failed = 0

        for (control in controls) {

            val spinner =
                control.spinner
                    ?: continue

            val position =
                spinner.selectedItemPosition

            if (
                position !in
                    control.options.indices
            ) {
                continue
            }

            val value =
                control.options[
                    position
                ].value

            if (
                writeValue(
                    control.path,
                    value
                )
            ) {
                success++
            } else {
                failed++
            }
        }

        return Pair(
            success,
            failed
        )
    }

private fun restoreAll() {

    if (!rootEnabled) {
        toast("Root access is required")
        return
    }

    if (scanning) {
        toast("Please wait for the scan to finish")
        return
    }

    executor.submit {

        var success = 0
        var failed = 0

        // Restore CPU policies
        for (policy in cpuPolicies) {

            if (policy.originalGovernor.isNotBlank()) {
                if (
                    writeValue(
                        policy.governorPath,
                        policy.originalGovernor
                    )
                ) {
                    success++
                } else {
                    failed++
                }
            }

            if (policy.originalMin.isNotBlank()) {
                if (
                    writeValue(
                        policy.minPath,
                        policy.originalMin
                    )
                ) {
                    success++
                } else {
                    failed++
                }
            }

            if (policy.originalMax.isNotBlank()) {
                if (
                    writeValue(
                        policy.maxPath,
                        policy.originalMax
                    )
                ) {
                    success++
                } else {
                    failed++
                }
            }
        }

        // Restore GPU policies
        for (policy in gpuPolicies) {

            if (policy.originalGovernor.isNotBlank()) {
                if (
                    writeValue(
                        policy.governorPath,
                        policy.originalGovernor
                    )
                ) {
                    success++
                } else {
                    failed++
                }
            }

            if (policy.originalMin.isNotBlank()) {
                if (
                    writeValue(
                        policy.minPath,
                        policy.originalMin
                    )
                ) {
                    success++
                } else {
                    failed++
                }
            }

            if (policy.originalMax.isNotBlank()) {
                if (
                    writeValue(
                        policy.maxPath,
                        policy.originalMax
                    )
                ) {
                    success++
                } else {
                    failed++
                }
            }
        }

        // Restore CPU advanced controls
        val cpuResult =
            restoreGeneric(cpuControls)

        success += cpuResult.first
        failed += cpuResult.second

        // Restore GPU controls
        val gpuResult =
            restoreGeneric(gpuControls)

        success += gpuResult.first
        failed += gpuResult.second

        // Restore thermal controls
        val thermalResult =
            restoreGeneric(thermalControls)

        success += thermalResult.first
        failed += thermalResult.second

        handler.post {

            toast(
                "Restored: $success • Failed: $failed"
            )

            scanHardwareAsync()
        }
    }
}

private fun restoreGeneric(
    controls: List<GenericControl>
): Pair<Int, Int> {

    var success = 0
    var failed = 0

    for (control in controls) {

        if (control.originalValue.isBlank()) {
            continue
        }

        val restored =
            writeValue(
                control.path,
                control.originalValue
            )

        if (restored) {
            success++
        } else {
            failed++
        }
    }

    return Pair(
        success,
        failed
    )
}

private fun prettyName(
    value: String
): String {

    return value
        .replace("_", " ")
        .replace("-", " ")
        .split(" ")
        .filter {
            it.isNotBlank()
        }
        .joinToString(" ") {
            it.replaceFirstChar { c ->
                c.uppercase()
            }
        }
}
}