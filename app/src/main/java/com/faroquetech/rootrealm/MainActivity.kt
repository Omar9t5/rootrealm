package com.faroquetech.rootrealm

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.faroquetech.theme.ThemeManager

class MainActivity : BaseActivity() {

    // =========================================================
    // COLORS
    // =========================================================

    private val theme get() = ThemeManager.current(this)

    private val backgroundColor get() = theme.background
    private val cardColor get() = theme.surface
    private val cardPressedColor get() = theme.surface2

    private val borderColor get() = theme.surface2
    private val iconBackgroundColor get() = theme.surface2

    private val primaryTextColor get() = theme.text
    private val secondaryTextColor get() = theme.secondary

    private val statusBackgroundColor get() = theme.surface2
    private val statusBorderColor get() = theme.accent
    private val statusColor get() = theme.success

    // =========================================================
    // BOOT TERMINAL
    // =========================================================

    private val bootHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val bootSequences = listOf(
        listOf("hey, welcome back", "let me get things ready for you", "almost there...", "DEVICE READY"),
        listOf("good to see you again", "just checking everything's in order", "one sec...", "DEVICE READY"),
        listOf("alright, waking your device up", "looking good so far", "wrapping up...", "DEVICE READY"),
        listOf("hey there", "warming things up for you", "loading your tools...", "DEVICE READY"),
        listOf("welcome back, let's dive in", "settling in your theme", "almost set...", "DEVICE READY"),
        listOf("hi, glad you're here", "getting everything lined up", "just a moment...", "DEVICE READY")
    )

    // =========================================================
    // ACTIVITY
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        buildDashboard()
    }

    override fun onDestroy() {
        bootHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // =========================================================
    // DASHBOARD
    // =========================================================

    private fun buildDashboard() {

        val root = LinearLayout(this)

        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(backgroundColor)

        val scrollView = ScrollView(this)

        scrollView.setBackgroundColor(backgroundColor)
        scrollView.isFillViewport = true
        scrollView.isVerticalScrollBarEnabled = false

        val content = LinearLayout(this)

        content.orientation = LinearLayout.VERTICAL

        content.setPadding(
            dp(20),
            dp(24),
            dp(20),
            dp(32)
        )

        // =====================================================
        // BRAND
        // =====================================================

        val brand = TextView(this)

        brand.text = "ROOTREALM"
        brand.textSize = 14f
        brand.setTextColor(secondaryTextColor)

        brand.typeface = Typeface.create(
            "sans-serif",
            Typeface.BOLD
        )

        brand.letterSpacing = 0.16f

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        brandRow.addView(
            brand,
            LinearLayout.LayoutParams(
                0,
                dp(40),
                1f
            )
        )

        val themeButton = TextView(this).apply {
            text = "◐"
            textSize = 21f
            setTextColor(primaryTextColor)
            gravity = Gravity.CENTER
            contentDescription = "Theme menu"
            background = rounded(
                iconBackgroundColor,
                borderColor,
                14
            )
            isClickable = true
            isFocusable = true
        }

        themeButton.setOnClickListener {
            showThemeMenu(themeButton)
        }

        brandRow.addView(
            themeButton,
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            ).apply {
                leftMargin = dp(8)
            }
        )

        content.addView(
            brandRow,
            LinearLayout.LayoutParams(
                -1,
                dp(46)
            ).apply {
                bottomMargin = dp(6)
            }
        )

        // =====================================================
        // TITLE / BRAND MESSAGE
        // =====================================================

        val title = TextView(this)

        title.text = "A toolbox"
        title.textSize = 40f

        title.setTextColor(primaryTextColor)

        title.typeface = Typeface.create(
            "sans-serif",
            Typeface.BOLD
        )

        title.setLineSpacing(
            0f,
            0.88f
        )

        content.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                dp(58)
            )
        )

        // =====================================================
        // CREATOR
        // =====================================================

        val creator = TextView(this)

        creator.text = "created by Faroque Tech"
        creator.textSize = 15f

        creator.setTextColor(secondaryTextColor)

        creator.typeface = Typeface.create(
            "sans-serif",
            Typeface.BOLD
        )

        creator.letterSpacing = 0.02f

        content.addView(
            creator,
            LinearLayout.LayoutParams(
                -1,
                dp(30)
            ).apply {
                bottomMargin = dp(7)
            }
        )

        // =====================================================
        // SUBTITLE
        // =====================================================

        val subtitle = TextView(this)

        subtitle.text =
            "Everything you need to inspect,\n" +
            "manage and control your device."

        subtitle.textSize = 16f

        subtitle.setTextColor(
            secondaryTextColor
        )

        subtitle.setLineSpacing(
            0f,
            1.05f
        )

        content.addView(
            subtitle,
            LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        )

        // =====================================================
        // DEVICE STATUS — BOOT TERMINAL
        // =====================================================

        val terminal = LinearLayout(this)

        terminal.orientation =
            LinearLayout.HORIZONTAL

        terminal.gravity =
            Gravity.CENTER_VERTICAL

        terminal.setPadding(
            dp(12),
            dp(8),
            dp(12),
            dp(8)
        )

        terminal.background = rounded(
            statusBackgroundColor,
            statusBorderColor,
            10
        )

        val terminalDot = TextView(this)

        terminalDot.text = "●"
        terminalDot.textSize = 10f

        terminalDot.setTextColor(
            statusColor
        )

        terminalDot.gravity =
            Gravity.CENTER

        terminal.addView(
            terminalDot,
            LinearLayout.LayoutParams(
                dp(16),
                dp(16)
            ).apply {
                rightMargin = dp(8)
            }
        )

        val terminalText = TextView(this)

        terminalText.text = ""
        terminalText.textSize = 10.5f

        terminalText.setTextColor(
            statusColor
        )

        terminalText.typeface =
            Typeface.MONOSPACE

        terminalText.maxLines = 1
        terminalText.isSingleLine = true

        terminal.addView(
            terminalText,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        content.addView(
            terminal,
            LinearLayout.LayoutParams(
                -1,
                dp(34)
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(24)
            }
        )

        runBootTerminal(terminalText)

        // =====================================================
        // TOOLS LIST
        // =====================================================

        val tools = listOf(

            Tool(
                title = "Device &\nSystem Info",
                description = "Hardware • Android • Kernel",
                icon = "▣",
                activity = DeviceInfoActivity::class.java
            ),

            Tool(
                title = "ADB &\nFastboot",
                description = "USB • Debugging • Devices",
                icon = "⌁",
                activity = AdbFastbootActivity::class.java
            ),

            Tool(
                title = "Backup &\nRestore",
                description = "Partitions • Images • Recovery",
                icon = "◈",
                activity = BackupRestoreActivity::class.java
            ),

            Tool(
                title = "DEBLOATER & APP MANAGER",
                description = "Packages • Apps • System",
                icon = "⊘",
                activity = DebloaterActivity::class.java
            ),

            Tool(
                title = "Root & System\nManager",
                description = "Magisk • KernelSU • APatch",
                icon = "◆",
                activity = RootManagerActivity::class.java
            ),

            Tool(
                title = "Display\nManager",
                description = "Refresh Rate • HDR • Display Info",
                icon = "▤",
                activity = DisplayManagerActivity::class.java
            ),

            Tool(
                title = "CPU & Kernel\nManager",
                description = "Frequency • Governor • CPU Controls",
                icon = "⚙",
                activity = CpuManagerActivity::class.java
            ),

            Tool(
                title = "GPU\nManager",
                description = "GPU Frequency • Governor • Controls",
                icon = "◈",
                activity = GpuManagerActivity::class.java
            ),

            Tool(
                title = "Thermal\nManager",
                description = "Temperatures • Thermal Zones • Limits",
                icon = "♨",
                activity = ThermalManagerActivity::class.java
            ),

            Tool(
                title = "Battery\nManager",
                description = "Health • Current • Thermal",
                icon = "◉",
                activity = BatteryManagerActivity::class.java
            ),

            Tool(
                title = "Flash & Module\nManager",
                description = "Modules • ZIP • Root",
                icon = "⬡",
                activity = FlashModuleActivity::class.java
            ),

            Tool(
                title = "OTA\nExtractor",
                description = "OTA • Payload • Images",
                icon = "⇩",
                activity = OtaExtractorActivity::class.java
            ),

        )

        // =====================================================
        // TOOLS HEADER
        // =====================================================

        val sectionHeader = LinearLayout(this)

        sectionHeader.orientation =
            LinearLayout.HORIZONTAL

        sectionHeader.gravity =
            Gravity.CENTER_VERTICAL

        val toolsLabel = TextView(this)

        toolsLabel.text = "TOOLS"
        toolsLabel.textSize = 12f

        toolsLabel.setTextColor(
            secondaryTextColor
        )

        toolsLabel.typeface =
            Typeface.DEFAULT_BOLD

        toolsLabel.letterSpacing = 0.15f

        sectionHeader.addView(
            toolsLabel,
            LinearLayout.LayoutParams(
                0,
                -1,
                1f
            )
        )

        val toolCount = TextView(this)

        toolCount.text = "${tools.size} TOOLS"
        toolCount.textSize = 10f

        toolCount.setTextColor(
            secondaryTextColor
        )

        toolCount.typeface =
            Typeface.DEFAULT_BOLD

        toolCount.letterSpacing = 0.08f

        sectionHeader.addView(
            toolCount,
            LinearLayout.LayoutParams(
                -2,
                -1
            )
        )

        content.addView(
            sectionHeader,
            LinearLayout.LayoutParams(
                -1,
                dp(28)
            )
        )

        // =====================================================
        // TOOL GRID
        // =====================================================

        val grid = GridLayout(this)

        grid.columnCount = 2
        grid.useDefaultMargins = false

        tools.forEachIndexed { index, tool ->

            val toolCard = createToolCard(tool)

            val params =
                GridLayout.LayoutParams()

            params.width = 0
            params.height = dp(178)

            params.columnSpec =
                GridLayout.spec(
                    index % 2,
                    1,
                    1f
                )

            params.rowSpec =
                GridLayout.spec(
                    index / 2
                )

            params.setMargins(
                if (index % 2 == 0) 0 else dp(5),
                dp(5),
                if (index % 2 == 0) dp(5) else 0,
                dp(5)
            )

            grid.addView(
                toolCard,
                params
            )
        }

        content.addView(
            grid,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        // =====================================================
        // FOOTER
        // =====================================================

        val footer = TextView(this)

        footer.text =
            "Faroque Tech • Root Realm\n" +
            "Root features appear when supported by your device."

        footer.textSize = 11f

        footer.setTextColor(
            secondaryTextColor
        )

        footer.gravity =
            Gravity.CENTER

        footer.setLineSpacing(
            dp(2).toFloat(),
            1f
        )

        content.addView(
            footer,
            LinearLayout.LayoutParams(
                -1,
                dp(62)
            ).apply {
                topMargin = dp(20)
            }
        )

        // =====================================================
        // IMPORTANT FIX
        //
        // Do not use ScrollView.LayoutParams here.
        // ScrollView automatically creates the correct
        // parameters for its child.
        // =====================================================

        scrollView.addView(content)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(root)
    }

    // =========================================================
    // BOOT TERMINAL ANIMATION
    // =========================================================
    //
    // Picks a random boot sequence every time the app is opened
    // and types it out line by line into `target`, finishing on
    // a blinking "DEVICE READY".
    // =========================================================

    private fun runBootTerminal(target: TextView) {

        val lines = bootSequences.random()
        var lineIndex = 0

        fun blinkReady() {
            var on = true
            val blink = object : Runnable {
                override fun run() {
                    target.text = if (on) "DEVICE READY" else "DEVICE READY_"
                    on = !on
                    bootHandler.postDelayed(this, 500)
                }
            }
            bootHandler.post(blink)
        }

        fun typeLine(line: String, isLast: Boolean, onDone: () -> Unit) {
            var charIndex = 0
            val typer = object : Runnable {
                override fun run() {
                    charIndex++
                    target.text = line.take(charIndex) + "_"
                    if (charIndex < line.length) {
                        bootHandler.postDelayed(this, 32)
                    } else if (isLast) {
                        bootHandler.postDelayed({ blinkReady() }, 350)
                    } else {
                        bootHandler.postDelayed({ onDone() }, 420)
                    }
                }
            }
            bootHandler.post(typer)
        }

        fun next() {
            if (lineIndex >= lines.size) return
            val line = lines[lineIndex]
            val isLast = lineIndex == lines.lastIndex
            lineIndex++
            typeLine(line, isLast) { next() }
        }

        next()
    }

        // =========================================================
    // THEME DROP-DOWN
    // =========================================================

    private fun showThemeMenu(anchor: View) {

        val palette = ThemeManager.current(this)
        val currentName = ThemeManager.currentName(this)

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(palette.surface, palette.surface2, 18)
        }

        ThemeManager.builtInThemes.forEach { definition ->
            val item = TextView(this).apply {
                text = if (definition.name == currentName) "✓  ${definition.name}" else "    ${definition.name}"
                textSize = 14f
                setTextColor(palette.text)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(palette.surface2, palette.surface2, 12)
                isClickable = true
                isFocusable = true

            }

            menu.addView(
                item,
                LinearLayout.LayoutParams(-1, dp(46)).apply {
                    bottomMargin = dp(4)
                }
            )
        }

        val popup = PopupWindow(
            menu,
            dp(190),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(rounded(palette.surface, palette.surface2, 18))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
        }

        // Rebind clicks after popup creation so the dismiss reference is valid.
        for (i in 0 until menu.childCount) {
            val item = menu.getChildAt(i)
            val definition = ThemeManager.builtInThemes[i]
            item.setOnClickListener {
                if (definition.name != ThemeManager.currentName(this)) {
                    ThemeManager.apply(this, definition.name)
                    showToast("Theme applied: ${definition.name}", Toast.LENGTH_SHORT)
                }
                popup.dismiss()
                recreate()
            }
        }

        popup.showAsDropDown(
            anchor,
            -(dp(190) - anchor.width),
            dp(6)
        )
    }

    // =========================================================
    // CREATE TOOL CARD
    // =========================================================

    private fun createToolCard(
        tool: Tool
    ): View {

        val container = LinearLayout(this)

        container.orientation =
            LinearLayout.VERTICAL

        container.setPadding(
            dp(15),
            dp(15),
            dp(15),
            dp(12)
        )

        container.background =
            rounded(
                cardColor,
                borderColor,
                22
            )

        container.isClickable = true
        container.isFocusable = true

        // =====================================================
        // ICON
        // =====================================================

        val icon = TextView(this)

        icon.text = tool.icon
        icon.textSize = 22f

        icon.setTextColor(
            primaryTextColor
        )

        icon.gravity =
            Gravity.CENTER

        icon.background =
            rounded(
                iconBackgroundColor,
                borderColor,
                14
            )

        container.addView(
            icon,
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            )
        )

        // =====================================================
        // TITLE
        // =====================================================

        val title = TextView(this)

        title.text = tool.title
        title.textSize = 17f

        title.setTextColor(
            primaryTextColor
        )

        title.typeface =
            Typeface.create(
                "sans-serif",
                Typeface.BOLD
            )

        title.setLineSpacing(
            0f,
            0.92f
        )

        title.maxLines = 2

        // Flexible space prevents the title from overlapping
        // the description.

        container.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            ).apply {
                topMargin = dp(10)
            }
        )

        // =====================================================
        // DESCRIPTION
        // =====================================================

        val description = TextView(this)

        description.text =
            tool.description

        description.textSize = 10.5f

        description.setTextColor(
            secondaryTextColor
        )

        description.maxLines = 2

        description.setLineSpacing(
            0f,
            1.05f
        )

        description.gravity =
            Gravity.CENTER_VERTICAL

        container.addView(
            description,
            LinearLayout.LayoutParams(
                -1,
                dp(30)
            )
        )

        // =====================================================
        // OPEN ACTION
        // =====================================================

        val open = TextView(this)

        open.text = "OPEN  →"
        open.textSize = 9.5f

        open.setTextColor(
            secondaryTextColor
        )

        open.typeface =
            Typeface.DEFAULT_BOLD

        open.letterSpacing = 0.08f

        open.gravity =
            Gravity.CENTER_VERTICAL

        container.addView(
            open,
            LinearLayout.LayoutParams(
                -1,
                dp(20)
            ).apply {
                topMargin = dp(2)
            }
        )

        // =====================================================
        // CLICK
        // =====================================================

        container.setOnClickListener {

            try {

                val intent =
                    Intent(
                        this,
                        tool.activity
                    )

                startActivity(intent)

            } catch (e: Exception) {

                showToast("Unable to open this tool.", Toast.LENGTH_SHORT)
            }
        }

        // =====================================================
        // PRESS ANIMATION
        // =====================================================

        container.setOnTouchListener { view, event ->

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    view.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(70)
                        .start()

                    view.background =
                        rounded(
                            cardPressedColor,
                            theme.accent,
                            22
                        )
                }

                MotionEvent.ACTION_UP -> {

                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(90)
                        .start()

                    view.background =
                        rounded(
                            cardColor,
                            borderColor,
                            22
                        )

                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {

                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(90)
                        .start()

                    view.background =
                        rounded(
                            cardColor,
                            borderColor,
                            22
                        )
                }
            }

            true
        }

        return container
    }

    // =========================================================
    // ROUNDED BACKGROUND
    // =========================================================

    private fun rounded(
        fill: Int,
        stroke: Int,
        radiusDp: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(fill)

            setStroke(
                dp(1),
                stroke
            )

            cornerRadius =
                dp(radiusDp).toFloat()
        }
    }

    // =========================================================
    // DP CONVERTER
    // =========================================================

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    // =========================================================
    // TOOL MODEL
    // =========================================================

    private data class Tool(

        val title: String,

        val description: String,

        val icon: String,

        val activity: Class<*>
    )
}