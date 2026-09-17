package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class RootManagerActivity : BaseActivity() {

    private val bgColor get() = ThemeManager.current(this).background
    private val cardColor get() = ThemeManager.current(this).surface
    private val borderColor get() = ThemeManager.current(this).let { ThemeManager.borderColor(it) }
    private val primaryColor get() = ThemeManager.current(this).accent
    private val textColor get() = ThemeManager.current(this).text
    private val secondaryColor get() = ThemeManager.current(this).secondary
    private val dangerColor get() = ThemeManager.current(this).error

    private lateinit var rootStatus: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var activeAccess: TextView

    private lateinit var magiskStatus: TextView
    private lateinit var kernelSuStatus: TextView
    private lateinit var apatchStatus: TextView
    private lateinit var rootProviderStatus: TextView

    private lateinit var slotStatus: TextView
    private lateinit var slotAStatus: TextView
    private lateinit var slotBStatus: TextView
    private lateinit var recoveryPartitionStatus: TextView
    private lateinit var recoveryEnvironmentStatus: TextView
    private lateinit var bootStatus: TextView
    private lateinit var vendorBootStatus: TextView
    private lateinit var avbStatus: TextView
    private lateinit var bootloaderStatus: TextView

    private lateinit var terminalOutput: TextView
    private lateinit var terminalScroll: ScrollView

    private var rootEnabled = false
    private var shizukuAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        buildUi()
        detectAll()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    /** Applies the app ThemeManager palette to every custom alert/dialog. */
    private fun styleAppDialog(dialog: Dialog, widthFraction: Float = 0.88f) {
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.68f)
            setLayout(
                (resources.displayMetrics.widthPixels * widthFraction).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            attributes = attributes.apply {
                gravity = Gravity.CENTER
            }
            // Keep the dialog chrome on the same surface as the app theme.
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun text(
        value: String,
        size: Float,
        color: Int = textColor
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }
    }

    private fun header(value: String): TextView {
        return TextView(this).apply {
            text = value.uppercase()
            textSize = 13f
            setTextColor(primaryColor)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
    }

    private fun card(): LinearLayout {
        val drawable = GradientDrawable().apply {
            setColor(cardColor)
            setStroke(dp(1), borderColor)
            cornerRadius = dp(14).toFloat()
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = drawable
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
    }

    private fun button(
        title: String,
        color: Int
    ): Button {
        val drawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }

        return Button(this).apply {
            text = title
            textSize = 13f
            setTextColor(textColor)
            isAllCaps = false
            background = drawable
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(8), 0, dp(8), 0)

            // Subtle press animation so the button feels clickable.
            setOnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.animate()
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setDuration(70)
                            .start()
                    }

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(70)
                            .start()
                    }
                }
                false
            }
        }
    }

    private fun addStatus(
        parent: LinearLayout,
        value: String
    ): TextView {
        val view = text(value, 14f, secondaryColor)

        parent.addView(
            view,
            LinearLayout.LayoutParams(-1, -2).apply {
                if (parent.childCount > 0) {
                    topMargin = dp(10)
                }
            }
        )

        return view
    }

    private fun buildUi() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
        }

        content.addView(text("Root & System Manager", 28f))

        content.addView(
            text(
                "Advanced root, boot, partition and system tools",
                14f,
                secondaryColor
            ),
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(18)
            }
        )

        content.addView(header("ACCESS"))

        val accessCard = card()

        rootStatus = addStatus(accessCard, "Root: Checking...")
        shizukuStatus = addStatus(accessCard, "Shizuku: Checking...")
        activeAccess = addStatus(accessCard, "Active access: Checking...")

        content.addView(
            accessCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(18)
            }
        )

        content.addView(header("ROOT FRAMEWORKS"))

        val frameworkCard = card()

        magiskStatus = addStatus(frameworkCard, "Magisk: Checking...")
        kernelSuStatus = addStatus(frameworkCard, "KernelSU: Checking...")
        apatchStatus = addStatus(frameworkCard, "APatch: Checking...")
        rootProviderStatus =
            addStatus(frameworkCard, "Root provider: Checking...")

        content.addView(
            frameworkCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(18)
            }
        )

        // Root Manager tools: kept as one card so the existing sections above/below
        // remain unchanged while the root-specific controls live together.
        content.addView(header("ROOT MANAGER"))

        val rootManagerCard = card()
        val bootImageButton = button("BOOT IMAGE TOOL", primaryColor)
        val initServiceButton = button("INIT.D / SERVICE CONTROL", cardColor)
        val permissionsButton = button("PERMISSIONS MANAGER", cardColor)
        val systemlessButton = button("SYSTEMLESS MODIFICATION", cardColor)
        val rootFileManagerButton = button("ROOT FILE MANAGER", cardColor)
        val kernelParametersButton = button("KERNEL PARAMETERS", cardColor)
        val performanceProfilesButton = button("PERFORMANCE PROFILES", cardColor)

        rootManagerCard.addView(bootImageButton, LinearLayout.LayoutParams(-1, dp(48)))
        rootManagerCard.addView(initServiceButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        rootManagerCard.addView(permissionsButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        rootManagerCard.addView(systemlessButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        rootManagerCard.addView(rootFileManagerButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        rootManagerCard.addView(kernelParametersButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        rootManagerCard.addView(performanceProfilesButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })

        content.addView(
            rootManagerCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(18)
            }
        )

        bootImageButton.setOnClickListener { showBootImageTool() }
        initServiceButton.setOnClickListener { showInitServiceControl() }
        permissionsButton.setOnClickListener { showPermissionsManager() }
        systemlessButton.setOnClickListener { showSystemlessManager() }
        rootFileManagerButton.setOnClickListener { showRootFileManager() }
        kernelParametersButton.setOnClickListener { showKernelParameters() }
        performanceProfilesButton.setOnClickListener { showPerformanceProfiles() }

        content.addView(header("BOOT & PARTITIONS"))

        val partitionCard = card()

        slotStatus = addStatus(partitionCard, "A/B Slots: Checking...")
        slotAStatus = addStatus(partitionCard, "Slot A: Checking...")
        slotBStatus = addStatus(partitionCard, "Slot B: Checking...")
        recoveryPartitionStatus =
            addStatus(partitionCard, "Recovery partition: Checking...")
        recoveryEnvironmentStatus =
            addStatus(partitionCard, "Recovery environment: Checking...")
        bootStatus = addStatus(partitionCard, "Boot partition: Checking...")
        vendorBootStatus =
            addStatus(partitionCard, "Vendor boot: Checking...")
        avbStatus = addStatus(partitionCard, "Verified Boot: Checking...")
        bootloaderStatus =
            addStatus(partitionCard, "Bootloader: Checking...")

        content.addView(
            partitionCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(18)
            }
        )

        content.addView(header("SLOT MANAGER"))

        val slotCard = card()

        val slotAButton = button(
            "SWITCH TO SLOT A",
            cardColor
        )

        slotCard.addView(
            slotAButton,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        val slotBButton = button(
            "SWITCH TO SLOT B",
            cardColor
        )

        slotCard.addView(
            slotBButton,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(10)
            }
        )

        content.addView(
            slotCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(18)
            }
        )

        slotAButton.setOnClickListener {
            confirmSlotChange("a")
        }

        slotBButton.setOnClickListener {
            confirmSlotChange("b")
        }

        content.addView(header("ADVANCED REBOOT"))

        val rebootCard = card()

        val reboot = button("REBOOT SYSTEM", primaryColor)
        rebootCard.addView(
            reboot,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        val recovery = button(
            "REBOOT TO RECOVERY",
            cardColor
        )

        rebootCard.addView(
            recovery,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(10)
            }
        )

        val bootloader = button(
            "REBOOT TO BOOTLOADER",
            cardColor
        )

        rebootCard.addView(
            bootloader,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(10)
            }
        )

        val fastbootd = button(
            "REBOOT TO FASTBOOTD",
            cardColor
        )

        rebootCard.addView(
            fastbootd,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(10)
            }
        )

        val soft = button(
            "SOFT REBOOT",
            cardColor
        )

        rebootCard.addView(
            soft,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(10)
            }
        )

        val power = button("POWER OFF", dangerColor)

        rebootCard.addView(
            power,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(10)
            }
        )

        content.addView(
            rebootCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(18)
            }
        )

        reboot.setOnClickListener {
            confirmReboot("reboot")
        }

        recovery.setOnClickListener {
            confirmReboot("recovery")
        }

        bootloader.setOnClickListener {
            confirmReboot("bootloader")
        }

        fastbootd.setOnClickListener {
            confirmReboot("fastboot")
        }

        soft.setOnClickListener {
            confirmReboot("soft")
        }

        power.setOnClickListener {
            confirmReboot("poweroff")
        }
        content.addView(header("TERMINAL"))

        val terminalCard = card()

        // One terminal: runs commands and shows full log output (scrollable)
        terminalOutput = text(
            "Root Realm terminal ready.\nType a command and press EXECUTE.\n\n",
            12f,
            Color.parseColor("#A8FF60")
        )
        terminalOutput.typeface = Typeface.MONOSPACE
        terminalOutput.setTextIsSelectable(true)
        terminalOutput.setPadding(dp(12), dp(12), dp(12), dp(12))

        terminalScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.parseColor("#0D1117"))
            addView(terminalOutput, ViewGroup.LayoutParams(-1, -2))
        }

        terminalCard.addView(
            terminalScroll,
            LinearLayout.LayoutParams(-1, dp(220))
        )

        // Scroll controls for the combined command + log terminal
        val scrollButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val topBtn = button("▲ TOP", cardColor)
        val upBtn = button("▲ UP", cardColor)
        val downBtn = button("▼ DOWN", cardColor)
        val bottomBtn = button("▼ END", cardColor)
        fun scrollBtnLp(extraRight: Boolean = true) =
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                if (extraRight) rightMargin = dp(6)
            }
        scrollButtons.addView(topBtn, scrollBtnLp())
        scrollButtons.addView(upBtn, scrollBtnLp())
        scrollButtons.addView(downBtn, scrollBtnLp())
        scrollButtons.addView(bottomBtn, scrollBtnLp(false))
        terminalCard.addView(scrollButtons, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        topBtn.setOnClickListener {
            terminalScroll.post { terminalScroll.fullScroll(android.view.View.FOCUS_UP) }
        }
        upBtn.setOnClickListener {
            terminalScroll.smoothScrollBy(0, -dp(140))
        }
        downBtn.setOnClickListener {
            terminalScroll.smoothScrollBy(0, dp(140))
        }
        bottomBtn.setOnClickListener {
            terminalScroll.post { terminalScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }

        val commandInput = EditText(this)
        commandInput.hint = "Enter command (runs as root if available)…"
        commandInput.setHintTextColor(secondaryColor)
        commandInput.setTextColor(textColor)
        commandInput.textSize = 13f
        commandInput.setSingleLine(true)
        commandInput.gravity = Gravity.CENTER_VERTICAL
        commandInput.setPadding(dp(16), 0, dp(16), 0)
        commandInput.background = GradientDrawable().apply {
            setColor(bgColor)
            setStroke(dp(1), borderColor)
            cornerRadius = dp(10).toFloat()
        }

        terminalCard.addView(
            commandInput,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                topMargin = dp(10)
            }
        )

        val terminalButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val execute = button("EXECUTE", primaryColor)
        terminalButtons.addView(
            execute,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val clear = button("CLEAR", cardColor)
        terminalButtons.addView(
            clear,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                leftMargin = dp(10)
            }
        )

        terminalCard.addView(
            terminalButtons,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            }
        )

        val bugReportButton = button("GENERATE BUG REPORT", cardColor)
        terminalCard.addView(
            bugReportButton,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(8)
            }
        )

        bugReportButton.setOnClickListener {
            confirmBugReport(bugReportButton)
        }

        content.addView(
            terminalCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(18)
            }
        )

        execute.setOnClickListener {
            val command = commandInput.text.toString().trim()
            if (command.isNotEmpty()) {
                executeTerminalCommand(command)
                commandInput.setText("")
            }
        }

        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
            ) {
                val command = commandInput.text.toString().trim()
                if (command.isNotEmpty()) {
                    executeTerminalCommand(command)
                    commandInput.setText("")
                }
                true
            } else {
                false
            }
        }

        clear.setOnClickListener {
            terminalOutput.text =
                "Root Realm terminal ready.\nType a command and press EXECUTE.\nLogs from root actions also appear here.\n\n"
            terminalScroll.post { terminalScroll.scrollTo(0, 0) }
        }

        content.addView(header("SYSTEM INFORMATION"))

        val systemCard = card()

        val systemInfo = text(
            "Loading system information...",
            14f,
            secondaryColor
        )

        systemCard.addView(
            systemInfo,
            LinearLayout.LayoutParams(-1, -2)
        )

        content.addView(
            systemCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            }
        )

        loadSystemInformation(systemInfo)

        scroll.addView(
            content,
            ViewGroup.LayoutParams(-1, -2)
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)
    }

    private fun requireRoot(onReady: () -> Unit) {
        if (!rootEnabled) {
            showToast("Root access is required", Toast.LENGTH_SHORT)
            return
        }
        onReady()
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun promptText(
        title: String,
        hint: String,
        defaultValue: String = "",
        actionLabel: String = "RUN",
        onSubmit: (String) -> Unit
    ) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(cardColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(18).toFloat()
            }
        }
        val titleView = text(title, 21f).apply { typeface = Typeface.DEFAULT_BOLD }
        val input = EditText(this).apply {
            this.hint = hint
            setHintTextColor(secondaryColor)
            setTextColor(textColor)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            setText(defaultValue)
            background = GradientDrawable().apply {
                setColor(bgColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(10).toFloat()
            }
        }
        panel.addView(titleView)
        panel.addView(input, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancel = button("CANCEL", cardColor)
        val run = button(actionLabel, primaryColor)
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(run, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(10) })
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        cancel.setOnClickListener { dialog.dismiss() }
        run.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) { dialog.dismiss(); onSubmit(value) }
        }
        dialog.setContentView(panel)
        dialog.show()
        styleAppDialog(dialog)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.68f)
            setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { gravity = Gravity.CENTER }
        }
    }

    private fun showBootImageTool() {
        requireRoot {
            val dialog = Dialog(this)
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(20), dp(14), dp(12))
                background = GradientDrawable().apply { setColor(cardColor); setStroke(dp(1), borderColor); cornerRadius = dp(18).toFloat() }
            }
            val title = text("Boot Image Tool", 21f).apply { typeface = Typeface.DEFAULT_BOLD }
            val info = text("Inspect boot images, create a root backup, or flash an image path. Use the image matching the current slot/device.", 14f, secondaryColor)
            panel.addView(title)
            panel.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            fun add(label: String, color: Int = cardColor, click: () -> Unit) {
                val b = button(label, color); panel.addView(b, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) }); b.setOnClickListener { click() }
            }
            add("INSPECT BOOT / INIT_BOOT") { inspectBootImages(); dialog.dismiss() }
            add("BACKUP CURRENT BOOT", cardColor) { backupBootImage(); dialog.dismiss() }
            add("FLASH BOOT IMAGE", primaryColor) { dialog.dismiss(); promptText("Flash Boot Image", "/sdcard/Download/boot.img") { path -> confirmFlashBoot(path) } }
            add("CANCEL") { dialog.dismiss() }
            dialog.setContentView(panel); dialog.show()
        styleAppDialog(dialog)
            dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.68f); setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT); attributes = attributes.apply { gravity = Gravity.CENTER } }
        }
    }

    private fun inspectBootImages() {
        Thread {
            val current = getCurrentSlot().lowercase().let { if (it == "a" || it == "b") "_$it" else "" }
            val dollar = '$'
            val cmd = "for n in boot boot_a boot_b init_boot init_boot_a init_boot_b vendor_boot vendor_boot_a vendor_boot_b; do p=\$(readlink -f /dev/block/by-name/\$n 2>/dev/null); [ -n \"\$p\" ] && echo \"\$n -> \${dollar}p\"; done; echo \"Current slot: $current\""
            termLog("› $cmd")
            termLog("[ROOT]\n${runRootCommand(cmd).ifBlank { "No boot image block links detected." }}\n")
        }.start()
    }

    private fun backupBootImage() {
        Thread {
            val slot = getCurrentSlot().lowercase()
            val name = if (slot == "a" || slot == "b") "boot_$slot" else "boot"
            val dest = "/sdcard/RootRealm_${name}_backup.img"
            val cmd = "src=\$(readlink -f /dev/block/by-name/$name 2>/dev/null); [ -z \"\$src\" ] && src=\$(readlink -f /dev/block/by-name/boot 2>/dev/null); if [ -n \"\$src\" ]; then dd if=\\\"\$src\\\" of=\\\"$dest\\\" bs=4M 2>/dev/null && sync && echo \"BACKUP: $dest\"; else echo \"ERROR: boot partition not found\"; fi"
            termLog("› $cmd")
            termLog("[ROOT]\n${runRootCommand(cmd).ifBlank { "No output." }}\n")
        }.start()
    }

    private fun confirmFlashBoot(path: String) {
        showConfirmDialog("Flash boot image?", "This writes the selected image to the active boot partition. A wrong or incompatible image can prevent Android from booting.\n\nImage: $path", "FLASH", dangerColor) {
            Thread {
                val slot = getCurrentSlot().lowercase()
                val name = if (slot == "a" || slot == "b") "boot_$slot" else "boot"
                val q = shellQuote(path)
                val cmd = "src=\$(readlink -f /dev/block/by-name/$name 2>/dev/null); [ -z \"\$src\" ] && src=\$(readlink -f /dev/block/by-name/boot 2>/dev/null); if [ -z \"\$src\" ]; then echo 'ERROR: boot partition not found'; elif [ ! -f $q ]; then echo 'ERROR: image file not found'; else dd if=$q of=\\\"\$src\\\" bs=4M && sync && echo 'FLASH COMPLETE'; fi"
                termLog("› $cmd")
                termLog("[ROOT]\n${runRootCommand(cmd).ifBlank { "No output." }}\n")
            }.start()
        }
    }

    private fun showInitServiceControl() {
        requireRoot {
            val options = arrayOf("LIST INIT.D", "LIST SERVICES", "START SERVICE", "STOP SERVICE", "RESTART SERVICE")
            showChoiceDialog("init.d / Service Control", options) { choice ->
                when (choice) {
                    0 -> runRootTool("ls -la /system/etc/init.d /system/bin/init.d /data/adb/service.d 2>/dev/null || true")
                    1 -> runRootTool("service list 2>/dev/null")
                    2 -> promptText("Start Service", "service name") { runRootTool("start ${shellQuote(it)}") }
                    3 -> promptText("Stop Service", "service name") { runRootTool("stop ${shellQuote(it)}") }
                    4 -> promptText("Restart Service", "service name") { runRootTool("stop ${shellQuote(it)}; sleep 1; start ${shellQuote(it)}") }
                }
            }
        }
    }

    private fun showChoiceDialog(title: String, choices: Array<String>, onChoice: (Int) -> Unit) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(14), dp(12)); background = GradientDrawable().apply { setColor(cardColor); setStroke(dp(1), borderColor); cornerRadius = dp(18).toFloat() } }
        panel.addView(text(title, 21f).apply { typeface = Typeface.DEFAULT_BOLD })
        choices.forEachIndexed { index, label ->
            val b = button(label, if (index == 0) primaryColor else cardColor)
            panel.addView(b, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
            b.setOnClickListener { dialog.dismiss(); onChoice(index) }
        }
        val cancel = button("CANCEL", cardColor); panel.addView(cancel, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) }); cancel.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(panel); dialog.show()
        styleAppDialog(dialog)
        dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.68f); setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT); attributes = attributes.apply { gravity = Gravity.CENTER } }
    }

    private fun showPermissionsManager() {
        requireRoot {
            promptText("Permissions Manager", "package name, e.g. com.example.app") { packageName ->
                val actions = arrayOf("VIEW PERMISSIONS", "GRANT PERMISSION", "REVOKE PERMISSION")
                showChoiceDialog("$packageName", actions) { action ->
                    when (action) {
                        0 -> runRootTool("dumpsys package ${shellQuote(packageName)} 2>/dev/null | grep -E 'requested permissions:|android.permission.|install permissions:'")
                        1 -> promptText("Grant Permission", "android.permission.CAMERA") { permission ->
                            runRootTool("pm grant ${shellQuote(packageName)} ${shellQuote(permission)}")
                        }
                        2 -> promptText("Revoke Permission", "android.permission.CAMERA") { permission ->
                            showConfirmDialog("Revoke permission?", "Revoke $permission from $packageName? Android may reject changes for non-revocable or privileged permissions.", "REVOKE", dangerColor) {
                                runRootTool("pm revoke ${shellQuote(packageName)} ${shellQuote(permission)}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showTextDialog(title: String, body: String) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(14), dp(12)); background = GradientDrawable().apply { setColor(cardColor); setStroke(dp(1), borderColor); cornerRadius = dp(18).toFloat() } }
        panel.addView(text(title, 21f).apply { typeface = Typeface.DEFAULT_BOLD })
        val output = text(body, 12f, secondaryColor); output.typeface = Typeface.MONOSPACE; output.setTextIsSelectable(true)
        val scroll = ScrollView(this); scroll.addView(output); panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(280)).apply { topMargin = dp(10) })
        val close = button("CLOSE", primaryColor); panel.addView(close, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) }); close.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(panel); dialog.show(); styleAppDialog(dialog, 0.9f)
    }

    private fun showSystemlessManager() {
        requireRoot {
            val options = arrayOf("LIST MAGISK MODULES", "LIST KERNELSU/APATCH MODULES", "CREATE SYSTEMLESS DIRECTORY", "MOUNT /DATA/ADB")
            showChoiceDialog("Systemless Modification", options) { choice ->
                when (choice) {
                    0 -> runRootTool("ls -la /data/adb/modules 2>/dev/null || echo 'Magisk module directory not found.'")
                    1 -> runRootTool("ls -la /data/adb/modules 2>/dev/null || echo 'No /data/adb/modules directory found.'")
                    2 -> promptText("Create Systemless Directory", "/data/adb/modules/my_module") { path ->
                        val safe = shellQuote(path.trimEnd('/')); runRootTool("mkdir -p $safe && chmod 755 $safe && echo 'CREATED: $path'")
                    }
                    3 -> runRootTool("mount | grep -E '/data/adb|magisk|overlay' || true")
                }
            }
        }
    }

    private fun runRootTool(command: String) {
        Thread {
            termLog("› $command")
            val result = runRootCommand(command)
            termLog("[ROOT]\n${result.ifBlank { "(no output)" }}\n")
        }.start()
    }

    private fun detectAll() {

        Thread {

            rootEnabled = checkRootAccess()
            shizukuAvailable = checkShizuku()

            val magisk = detectMagisk()
            val kernelSu = detectKernelSU()

            // APatch is intentionally strict.
            val apatch = detectAPatch()

            // Select one actual root framework. Some devices keep stale files from
            // a previously installed framework, so do not report multiple managers.
            val provider = detectRootProvider(
                magisk,
                kernelSu,
                apatch
            )

            val activeMagisk = provider == "Magisk"
            val activeKernelSu = provider == "KernelSU"
            val activeAPatch = provider == "APatch"

            val slot = getCurrentSlot()

            val slotA =
                partitionExists("boot_a") ||
                partitionExists("system_a") ||
                partitionExists("vendor_a")

            val slotB =
                partitionExists("boot_b") ||
                partitionExists("system_b") ||
                partitionExists("vendor_b")

            val recoveryPartition =
                partitionExists("recovery") ||
                partitionExists("recovery_a") ||
                partitionExists("recovery_b")

            val boot =
                partitionExists("boot") ||
                slotA ||
                slotB

            val vendorBoot =
                partitionExists("vendor_boot") ||
                partitionExists("vendor_boot_a") ||
                partitionExists("vendor_boot_b")

            val recoveryEnvironment =
                recoveryPartition || vendorBoot

            val avb =
                getProperty("ro.boot.verifiedbootstate")

            val locked =
                getProperty("ro.boot.flash.locked")

            runOnUiThread {

                updateStatus(
                    rootStatus,
                    "Root",
                    rootEnabled
                )

                updateStatus(
                    shizukuStatus,
                    "Shizuku",
                    shizukuAvailable
                )

                activeAccess.text =
                    when {
                        rootEnabled ->
                            "Active access: Root"

                        shizukuAvailable ->
                            "Active access: Shizuku"

                        else ->
                            "Active access: None"
                    }

                activeAccess.setTextColor(
                    if (rootEnabled || shizukuAvailable) {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                updateStatus(
                    magiskStatus,
                    "Magisk",
                    activeMagisk
                )

                updateStatus(
                    kernelSuStatus,
                    "KernelSU",
                    activeKernelSu
                )

                updateStatus(
                    apatchStatus,
                    "APatch",
                    activeAPatch
                )

                rootProviderStatus.text =
                    "Root provider: $provider"

                rootProviderStatus.setTextColor(
                    if (
                        provider != "None" &&
                        provider != "Unknown"
                    ) {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                slotStatus.text =
                    if (slotA || slotB) {
                        "A/B Slots: Supported"
                    } else {
                        "A/B Slots: Not detected"
                    }

                slotStatus.setTextColor(
                    if (slotA || slotB) {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                slotAStatus.text =
                    "Slot A: " +
                    if (slotA) {
                        "Available"
                    } else {
                        "Not detected"
                    }

                slotBStatus.text =
                    "Slot B: " +
                    if (slotB) {
                        "Available"
                    } else {
                        "Not detected"
                    }

                slotAStatus.setTextColor(
                    if (slotA) {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                slotBStatus.setTextColor(
                    if (slotB) {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                recoveryPartitionStatus.text =
                    "Recovery partition: " +
                    if (recoveryPartition) {
                        "Detected"
                    } else {
                        "Not detected"
                    }

                recoveryPartitionStatus.setTextColor(
                    if (recoveryPartition) {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                recoveryEnvironmentStatus.text =
                    "Recovery environment: " +
                    if (recoveryEnvironment) {
                        "Possible"
                    } else {
                        "Not detected"
                    }

                recoveryEnvironmentStatus.setTextColor(
                    if (recoveryEnvironment) {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                bootStatus.text =
                    "Boot partition: " +
                    if (boot) {
                        "Detected"
                    } else {
                        "Not detected"
                    }

                bootStatus.setTextColor(
                    if (boot) {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                vendorBootStatus.text =
                    "Vendor boot: " +
                    if (vendorBoot) {
                        "Detected"
                    } else {
                        "Not detected"
                    }

                vendorBootStatus.setTextColor(
                    if (vendorBoot) {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                avbStatus.text =
                    "Verified Boot: " +
                    when {
                        avb == "green" -> "Green"
                        avb == "yellow" -> "Yellow"
                        avb == "orange" -> "Orange"
                        avb == "red" -> "Red"
                        avb.isBlank() -> "Unknown"
                        else -> avb
                    }

                avbStatus.setTextColor(
                    if (avb == "green") {
                        primaryColor
                    } else {
                        secondaryColor
                    }
                )

                bootloaderStatus.text =
                    "Bootloader: " +
                    when (locked) {
                        "0" -> "Locked"
                        "1" -> "Unlocked"
                        else -> "Unknown"
                    }

                bootloaderStatus.setTextColor(
                    if (locked == "1") {
                        dangerColor
                    } else {
                        secondaryColor
                    }
                )
            }

        }.start()
    }

    private fun updateStatus(
        view: TextView,
        name: String,
        detected: Boolean
    ) {

        view.text =
            if (detected) {
                "$name: Detected"
            } else {
                "$name: Not detected"
            }

        view.setTextColor(
            if (detected) {
                primaryColor
            } else {
                secondaryColor
            }
        )
    }

    private fun detectMagisk(): Boolean {

        val paths = arrayOf(
            "/data/adb/magisk",
            "/data/adb/magisk.db",
            "/data/adb/magisk.img",
            "/sbin/.magisk",
            "/debug_ramdisk/.magisk"
        )

        for (path in paths) {
            if (pathExistsRootAware(path)) {
                return true
            }
        }

        if (!rootEnabled) {
            return false
        }

        val commands = arrayOf(
            "magisk -v",
            "magisk --version",
            "magisk -V"
        )

        for (command in commands) {

            val result =
                runRootCommand(command)

            if (
                result.isNotBlank() &&
                !result.contains("not found", true) &&
                !result.contains("unknown command", true)
            ) {
                return true
            }
        }

        return getRootProperties().contains(
            "magisk",
            true
        )
    }

    private fun detectKernelSU(): Boolean {

        val paths = arrayOf(
            "/data/adb/ksu",
            "/data/adb/ksud",
            "/data/adb/ksu/bin",
            "/data/adb/ksu/bin/ksud",
            "/data/adb/modules/ksu",
            "/proc/ksu",
            "/sys/module/kernelsu"
        )

        for (path in paths) {
            if (pathExistsRootAware(path)) {
                return true
            }
        }

        if (!rootEnabled) {
            return false
        }

        val commands = arrayOf(
            "ksud -V",
            "ksud --version",
            "ksud version",
            "su -V",
            "su -v",
            "su --version"
        )

        for (command in commands) {

            val result =
                runRootCommand(command)

            if (isKernelSUOutput(result)) {
                return true
            }
        }

        val properties =
            getRootProperties()

        if (
            properties.contains("kernelsu", true) ||
            properties.contains("kernel_su", true) ||
            properties.contains("ksu", true)
        ) {
            return true
        }

        val suLocation =
            runRootCommand(
                "readlink -f /system/bin/su 2>/dev/null"
            )

        return isKernelSUOutput(suLocation)
    }

    /*
     * APatch detector:
     *
     * NO filesystem detection.
     * NO generic getprop detection.
     * NO "apd" substring detection.
     *
     * Only a successful APatch-specific version response
     * can return true.
     */
    private fun detectAPatch(): Boolean {

        if (!rootEnabled) {
            return false
        }

        val commands = arrayOf(
            "apd -V 2>/dev/null",
            "apd --version 2>/dev/null",
            "apd version 2>/dev/null"
        )

        for (command in commands) {

            val result =
                runRootCommand(command)

            if (isConfirmedAPatchVersion(result)) {
                return true
            }
        }

        return false
    }

    private fun isConfirmedAPatchVersion(
        value: String
    ): Boolean {

        if (value.isBlank()) {
            return false
        }

        val text =
            value.trim().lowercase()

        if (
            text.contains("not found") ||
            text.contains("no such file") ||
            text.contains("unknown command") ||
            text.contains("permission denied") ||
            text.contains("invalid option") ||
            text.contains("usage:")
        ) {
            return false
        }

        /*
         * APatch itself must identify the output.
         * Merely containing "apd" is NOT enough.
         */
        return text.contains("apatch")
    }
    private fun detectRootProvider(
        magisk: Boolean,
        kernelSu: Boolean,
        apatch: Boolean
    ): String {

        if (!rootEnabled) {
            return "None"
        }

        if (kernelSu) {
            return "KernelSU"
        }

        if (magisk) {
            return "Magisk"
        }

        if (apatch) {
            return "APatch"
        }

        return "Unknown root framework"
    }

    private fun isKernelSUOutput(
        value: String
    ): Boolean {

        if (value.isBlank()) {
            return false
        }

        val text =
            value.lowercase()

        return text.contains("kernelsu") ||
                text.contains("kernel su") ||
                text.contains("ksu") ||
                text.contains("ksud")
    }

    private fun getRootProperties(): String {

        if (!rootEnabled) {
            return ""
        }

        return runRootCommand(
            "getprop 2>/dev/null"
        )
    }

    private fun pathExists(
        path: String
    ): Boolean {

        return try {
            java.io.File(path).exists()
        } catch (e: Exception) {
            false
        }
    }

    private fun pathExistsRootAware(
        path: String
    ): Boolean {

        if (pathExists(path)) {
            return true
        }

        if (!rootEnabled) {
            return false
        }

        val result =
            runRootCommand(
                "if [ -e '$path' ]; then echo YES; fi"
            )

        return result.trim() == "YES"
    }

    private fun checkRootAccess(): Boolean {

        return try {

            val process =
                Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "id"
                    )
                )

            val reader =
                BufferedReader(
                    InputStreamReader(
                        process.inputStream
                    )
                )

            val output =
                reader.readText()

            reader.close()

            process.waitFor()

            output.contains("uid=0")

        } catch (e: Exception) {
            false
        }
    }

    private fun checkShizuku(): Boolean {

        return try {
            // The Shizuku API is bundled with Root Realm, so loading
            // rikka.shizuku.Shizuku does not prove Shizuku is installed.
            val shizukuPackage = "moe.shizuku.privileged.api"

            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                shizukuPackage,
                0
            )

            // The Shizuku service must also be running.
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                return false
            }

            // pingBinder() can still report a stale "true" if the
            // Shizuku server process was killed by the OS (e.g. an
            // OEM battery/background kill) before its death callback
            // has fired locally. Force a real round-trip call so a
            // genuinely dead server is actually caught here instead
            // of showing as detected.
            rikka.shizuku.Shizuku.getUid()

            true

        } catch (_: Throwable) {
            false
        }
    }

    private fun partitionExists(
        name: String
    ): Boolean {

        val paths = arrayOf(
            "/dev/block/by-name/$name",
            "/dev/block/bootdevice/by-name/$name"
        )

        for (path in paths) {
            if (pathExistsRootAware(path)) {
                return true
            }
        }

        val command =
            "find /dev/block -path '*/by-name/$name' " +
            "-print 2>/dev/null"

        val result =
            if (rootEnabled) {
                runRootCommand(command)
            } else {
                runShellCommand(command)
            }

        return result.isNotBlank()
    }

    private fun getCurrentSlot(): String {

        val suffix =
            getProperty("ro.boot.slot_suffix")

        if (suffix.isNotBlank()) {
            return suffix
                .removePrefix("_")
                .uppercase()
        }

        val slotProp =
            getProperty("ro.boot.slot")

        if (slotProp.equals("a", true)) {
            return "A"
        }

        if (slotProp.equals("b", true)) {
            return "B"
        }

        if (rootEnabled) {

            val slot =
                runRootCommand(
                    "bootctl get-current-slot"
                )

            return when (slot.trim()) {
                "0" -> "A"
                "1" -> "B"
                else -> "Unknown"
            }
        }

        return "Unknown"
    }

    private fun getProperty(
        property: String
    ): String {

        return try {

            val process =
                Runtime.getRuntime().exec(
                    arrayOf(
                        "getprop",
                        property
                    )
                )

            val output =
                BufferedReader(
                    InputStreamReader(
                        process.inputStream
                    )
                ).readText()

            process.waitFor()

            output.trim()

        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Runs a command with a hard timeout, wrapped in the shell's own `timeout` so a
     * hung command (a full logcat dump can stall on some devices) is actually killed
     * by the shell itself - Process.destroy() on a `su -c`/`sh -c` child only kills
     * that wrapper process, not a grandchild it spawned, so relying on the Java-side
     * timeout alone can leave the real command running in the background.
     * Falls back to the bare command if the device has no `timeout` binary.
     */
    private fun runTimedCommand(
        command: String,
        useRoot: Boolean,
        timeoutMs: Long = 12000
    ): Pair<String, String> {
        fun spawn(cmd: String): Process {
            return if (useRoot) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            }
        }
        return try {
            val process = spawn("timeout 10 $command")
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return "" to "Timed out waiting for the command"
            }
            var out = BufferedReader(InputStreamReader(process.inputStream)).readText()
            var err = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            if (out.isBlank() && err.contains("not found", true)) {
                val fallback = spawn(command)
                if (!fallback.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    fallback.destroy()
                    return "" to "Timed out waiting for the command"
                }
                out = BufferedReader(InputStreamReader(fallback.inputStream)).readText()
                err = BufferedReader(InputStreamReader(fallback.errorStream)).readText().trim()
            }
            out to err
        } catch (e: Exception) {
            "" to (e.message ?: "unknown error")
        }
    }

    private fun confirmBugReport(button: Button) {
        if (!rootEnabled) {
            showToast("Root access is required for a full bug report", Toast.LENGTH_SHORT)
            return
        }
        showConfirmDialog(
            title = "Generate full bug report?",
            message = "This runs Android's built-in bug report tool (bugreportz). It bundles system logs, dumpsys output and app state into a zip file, and can take 30–90 seconds depending on the device.\n\n⚠ This file is NOT redacted. It commonly includes IMEI/serial numbers, MAC addresses, Wi-Fi network names, linked accounts, and location data. Review its contents before sending it anywhere.",
            positiveText = "GENERATE"
        ) {
            takeBugReport(button)
        }
    }

    /**
     * Runs Android's bugreportz - a much fuller diagnostic than logcat alone
     * (dumpsys, system properties, app states, kernel info, etc. bundled into one
     * zip). Streams its PROGRESS:/OK:/FAIL: lines into the terminal as they arrive,
     * then reports the final zip path once done. Falls back to the legacy
     * `bugreport` command on very old builds where bugreportz doesn't exist.
     *
     * A hard 3-minute ceiling guards against a hang, since this spawns a real
     * dumpstate process under the hood that could theoretically stall.
     */
    private fun takeBugReport(button: Button) {
        button.isEnabled = false
        val originalText = button.text
        button.text = "GENERATING…"
        termLog("› BUG REPORT (bugreportz, this can take a minute)…")

        Thread {
            var resultPath: String? = null
            var failReason: String? = null
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "bugreportz -p"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val startedAt = System.currentTimeMillis()
                val maxMs = 180000L

                while (true) {
                    if (System.currentTimeMillis() - startedAt > maxMs) {
                        process.destroy()
                        termLog("[BUG REPORT] Timed out after 3 minutes.")
                        break
                    }
                    val line = reader.readLine() ?: break
                    termLog(line)
                    when {
                        line.startsWith("OK:") -> resultPath = line.removePrefix("OK:").trim()
                        line.startsWith("FAIL:") -> failReason = line.removePrefix("FAIL:").trim()
                    }
                }
                process.waitFor()

                when {
                    resultPath != null -> {
                        val details = runRootCommand("ls -la \"$resultPath\"")
                        termLog("[BUG REPORT] Saved: $resultPath\n${details.ifBlank { "" }}")
                        termLog("[BUG REPORT] Not redacted - may contain IMEI/serial, MAC addresses, accounts, Wi-Fi and location data. Review before sharing.\n")
                        runOnUiThread {
                            showToast("Bug report saved: $resultPath", Toast.LENGTH_LONG)
                        }
                    }
                    failReason != null -> {
                        termLog("[BUG REPORT] Failed: $failReason\n")
                    }
                    else -> {
                        termLog("[BUG REPORT] bugreportz gave no result - it may not exist on this build. Trying the legacy 'bugreport' command…")
                        val legacy = runTimedCommand("bugreport", useRoot = true, timeoutMs = 90000)
                        if (legacy.first.isNotBlank()) {
                            termLog("[BUG REPORT legacy]\n${legacy.first.trimEnd()}\n")
                        } else {
                            termLog("[BUG REPORT] ${legacy.second.ifBlank { "No output from the legacy bugreport command either." }}\n")
                        }
                    }
                }
            } catch (e: Exception) {
                termLog("[BUG REPORT] error: ${e.message ?: "unknown error"}\n")
            } finally {
                runOnUiThread {
                    button.isEnabled = true
                    button.text = originalText
                }
            }
        }.start()
    }

    private fun runRootCommand(
        command: String
    ): String {

        return try {

            val process =
                Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        command
                    )
                )

            val output =
                BufferedReader(
                    InputStreamReader(
                        process.inputStream
                    )
                ).readText()

            val error =
                BufferedReader(
                    InputStreamReader(
                        process.errorStream
                    )
                ).readText()

            process.waitFor()

            if (output.isNotBlank()) {
                output.trim()
            } else {
                error.trim()
            }

        } catch (e: Exception) {
            ""
        }
    }

    private fun runShellCommand(
        command: String
    ): String {

        return try {

            val process =
                Runtime.getRuntime().exec(
                    arrayOf(
                        "sh",
                        "-c",
                        command
                    )
                )

            val output =
                BufferedReader(
                    InputStreamReader(
                        process.inputStream
                    )
                ).readText()

            val error =
                BufferedReader(
                    InputStreamReader(
                        process.errorStream
                    )
                ).readText()

            process.waitFor()

            if (output.isNotBlank()) {
                output.trim()
            } else {
                error.trim()
            }

        } catch (e: Exception) {
            e.message ?: ""
        }
    }

    /** Append text to the single terminal (commands + logs) and scroll to end. */
    private fun termLog(message: String, scrollToEnd: Boolean = true) {
        runOnUiThread {
            if (!::terminalOutput.isInitialized) return@runOnUiThread
            terminalOutput.append(message)
            if (!message.endsWith("\n")) terminalOutput.append("\n")
            val maxChars = 24000
            val current = terminalOutput.text?.toString() ?: ""
            if (current.length > maxChars) {
                terminalOutput.text = "…(log trimmed)…\n" + current.takeLast(maxChars - 40)
            }
            if (scrollToEnd && ::terminalScroll.isInitialized) {
                terminalScroll.post {
                    terminalScroll.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun executeTerminalCommand(
        command: String
    ) {
        termLog("› $command")

        Thread {
            val output =
                if (rootEnabled) {
                    runRootCommand(command)
                } else {
                    runShellCommand(command)
                }

            val mode = if (rootEnabled) "ROOT" else "SHELL"
            val body = if (output.isBlank()) "(no output)" else output.trimEnd()
            termLog("[$mode]\n$body\n")
        }.start()
    }

    private fun confirmSlotChange(
        slot: String
    ) {

        if (!rootEnabled) {

            showToast("Root access is required", Toast.LENGTH_SHORT)

            return
        }

        showConfirmDialog(
            title = "Switch active slot?",
            message =
                "Switch the active boot slot to Slot " +
                slot.uppercase() +
                "?\n\nOnly do this if you know that this slot contains a bootable system.",
            positiveText = "SWITCH"
        ) {

            Thread {
                val number = if (slot == "a") "0" else "1"
                val cmd = "bootctl set-active-boot-slot $number"
                termLog("› $cmd")
                val result = runRootCommand(cmd)
                termLog("[ROOT]\n${if (result.isBlank()) "(no output)" else result.trimEnd()}\n")

                runOnUiThread {
                    showToast(if (result.isBlank()) "Slot change command sent" else result, Toast.LENGTH_LONG)
                    detectAll()
                }
            }.start()
        }
    }

    private fun confirmReboot(
        mode: String
    ) {

        if (!rootEnabled) {

            showToast("Root access is required", Toast.LENGTH_SHORT)

            return
        }

        val message =
            when (mode) {
                "reboot" -> "Reboot the device normally?"
                "recovery" -> "Reboot into recovery?"
                "bootloader" -> "Reboot into bootloader?"
                "fastboot" -> "Reboot into FastbootD?"
                "soft" -> "Perform a soft reboot?"
                "poweroff" -> "Power off the device?"
                else -> "Continue?"
            }

        val positiveColor =
            if (mode == "poweroff") dangerColor else primaryColor

        showConfirmDialog(
            title = "Advanced Reboot",
            message = message,
            positiveText = "CONTINUE",
            positiveColor = positiveColor
        ) {

            Thread {
                val command =
                    when (mode) {
                        "reboot" -> "reboot"
                        "recovery" -> "reboot recovery"
                        "bootloader" -> "reboot bootloader"
                        "fastboot" -> "reboot fastboot"
                        "soft" -> "setprop ctl.restart zygote"
                        "poweroff" -> "reboot -p"
                        else -> "reboot"
                    }
                termLog("› $command")
                val result = runRootCommand(command)
                termLog("[ROOT]\n${if (result.isBlank()) "(no output)" else result.trimEnd()}\n")
            }.start()
        }
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        positiveText: String,
        positiveColor: Int = primaryColor,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(this)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(cardColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(18).toFloat()
            }
        }

        val titleView = text(title, 21f, textColor).apply {
            typeface = Typeface.DEFAULT_BOLD
        }

        val messageView = text(message, 15f, secondaryColor).apply {
            setLineSpacing(0f, 1.08f)
        }

        panel.addView(
            titleView,
            LinearLayout.LayoutParams(-1, -2)
        )

        panel.addView(
            messageView,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                rightMargin = dp(8)
            }
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        fun dialogAction(
            label: String,
            color: Int,
            onClick: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(10).toFloat()
                }
                minHeight = dp(44)
                setOnClickListener {
                    onClick()
                }
            }
        }

        val cancel = dialogAction(
            "CANCEL",
            secondaryColor
        ) {
            dialog.dismiss()
        }

        val confirm = dialogAction(
            positiveText,
            positiveColor
        ) {
            dialog.dismiss()
            onConfirm()
        }

        actions.addView(
            cancel,
            LinearLayout.LayoutParams(-2, dp(48))
        )

        actions.addView(
            confirm,
            LinearLayout.LayoutParams(-2, dp(48))
        )

        panel.addView(
            actions,
            LinearLayout.LayoutParams(-1, dp(52)).apply {
                topMargin = dp(8)
            }
        )

        dialog.setContentView(panel)

        dialog.show()
        styleAppDialog(dialog)

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.68f)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            attributes = attributes.apply {
                gravity = Gravity.CENTER
            }
        }
    }

    private fun showRootFileManager() {
        if (!rootEnabled) {
            showToast("Root access is required", Toast.LENGTH_SHORT)
            return
        }

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(cardColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(18).toFloat()
            }
        }

        val title = text("ROOT FILE MANAGER", 21f, textColor).apply {
            typeface = Typeface.DEFAULT_BOLD
        }

        val pathInput = EditText(this).apply {
            setText("/")
            setTextColor(textColor)
            setHintTextColor(secondaryColor)
            hint = "Path"
            isSingleLine = true
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(bgColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(10).toFloat()
            }
        }

        val fileList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val scroll = ScrollView(this).apply {
            addView(fileList)
        }

        lateinit var listPath: () -> Unit

        fun addEntry(name: String, isDirectory: Boolean) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dp(10).toFloat()
                }
                isClickable = true
                isFocusable = true
            }

            val icon = TextView(this).apply {
                text = if (isDirectory) "📁" else "📄"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(textColor)
            }

            val label = TextView(this).apply {
                text = name
                textSize = 14f
                setTextColor(textColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, dp(4), 0)
            }

            row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(48)))
            row.addView(label, LinearLayout.LayoutParams(0, dp(48), 1f))

            row.setOnClickListener {
                if (isDirectory) {
                    val current = pathInput.text.toString().trim().ifBlank { "/" }.trimEnd('/')
                    val next = if (current.isBlank()) "/$name" else "$current/$name"
                    pathInput.setText(next)
                    listPath()
                } else {
                    val current = pathInput.text.toString().trim().ifBlank { "/" }.trimEnd('/')
                    val fullPath = if (current.isBlank()) "/$name" else "$current/$name"
                    Thread {
                        val safe = fullPath.replace("'", "'\\''")
                        val info = runRootCommand("ls -ld '$safe' 2>/dev/null || true")
                        runOnUiThread {
                            showToast(if (info.isBlank()) "Unable to read file" else info.trim(), Toast.LENGTH_LONG)
                        }
                    }.start()
                }
            }

            fileList.addView(row, LinearLayout.LayoutParams(-1, dp(52)).apply {
                topMargin = dp(4)
            })
        }

        listPath = {
            val path = pathInput.text.toString().trim().ifBlank { "/" }
            Thread {
                val safePath = path.replace("'", "'\\''")
                val result = runRootCommand(
                    "cd '$safePath' 2>/dev/null || exit 1; " +
                        "for f in * .[!.]* ..?*; do " +
                        "[ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; " +
                        "if [ -d \"\$f\" ]; then printf 'D|%s\\n' \"\$f\"; else printf 'F|%s\\n' \"\$f\"; fi; " +
                        "done | sort -f"
                )
                runOnUiThread {
                    fileList.removeAllViews()
                    if (result.isBlank()) {
                        val empty = text("Folder is empty or inaccessible.", 14f, secondaryColor).apply {
                            setPadding(dp(12), dp(18), dp(12), dp(18))
                        }
                        fileList.addView(empty)
                    } else {
                        result.lineSequence().forEach { line ->
                            val type = line.substringBefore("|", "F")
                            val name = line.substringAfter("|", "").trim()
                            if (name.isNotBlank() && name != "." && name != "..") {
                                addEntry(name, type == "D")
                            }
                        }
                    }
                }
                termLog("› Root File Manager: '$path'")
            }.start()
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val upButton = dialogButton("UP", secondaryColor) {
            val current = pathInput.text.toString().trim().trimEnd('/').ifBlank { "/" }
            val parent = current.substringBeforeLast('/', "").ifBlank { "/" }
            pathInput.setText(parent)
            listPath()
        }

        val refreshButton = dialogButton("REFRESH", primaryColor) {
            listPath()
        }

        val closeButton = dialogButton("CLOSE", secondaryColor) {
            dialog.dismiss()
        }

        actions.addView(upButton, LinearLayout.LayoutParams(-2, dp(46)))
        actions.addView(refreshButton, LinearLayout.LayoutParams(-2, dp(46)))
        actions.addView(closeButton, LinearLayout.LayoutParams(-2, dp(46)))

        panel.addView(title, LinearLayout.LayoutParams(-1, -2))
        panel.addView(pathInput, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(12)
        })
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(10)
        })
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(8)
        })

        dialog.setContentView(panel)
        dialog.show()
        styleAppDialog(dialog)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.68f)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (resources.displayMetrics.heightPixels * 0.78f).toInt()
            )
            attributes = attributes.apply {
                gravity = Gravity.CENTER
            }
        }

        listPath()
    }

    private fun showKernelParameters() {
        if (!rootEnabled) {
            showToast("Root access is required", Toast.LENGTH_SHORT)
            return
        }

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(cardColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(18).toFloat()
            }
        }

        val title = text("KERNEL PARAMETERS", 21f, textColor).apply {
            typeface = Typeface.DEFAULT_BOLD
        }

        val pathInput = EditText(this).apply {
            setText("/proc/sys")
            setTextColor(textColor)
            setHintTextColor(secondaryColor)
            hint = "Kernel parameter path"
            isSingleLine = true
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(bgColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(10).toFloat()
            }
        }

        val valueInput = EditText(this).apply {
            setTextColor(textColor)
            setHintTextColor(secondaryColor)
            hint = "New value (for a file parameter)"
            isSingleLine = true
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(bgColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(10).toFloat()
            }
        }

        val output = TextView(this).apply {
            textSize = 12f
            setTextColor(secondaryColor)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val scroll = ScrollView(this).apply { addView(output) }

        fun inspect() {
            val rawPath = pathInput.text.toString().trim().ifBlank { "/proc/sys" }
            val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
            Thread {
                val safe = path.replace("'", "'\\''")
                val command = if (path.endsWith("/proc/sys") || path == "/proc/sys") {
                    "find '$safe' -type f 2>/dev/null | sort | head -n 250 | while read -r f; do printf '\\n[%s]\\n' \"\$f\"; cat \"\$f\" 2>/dev/null; done"
                } else {
                    "if [ -f '$safe' ]; then printf '[%s]\\n' '$safe'; cat '$safe' 2>/dev/null; elif [ -d '$safe' ]; then find '$safe' -maxdepth 2 -type f 2>/dev/null | sort | head -n 250; else echo 'Path not found'; fi"
                }
                val result = runRootCommand(command)
                runOnUiThread {
                    output.text = result.ifBlank { "No readable kernel parameters found." }
                }
                termLog("› Kernel Parameters: $path")
            }.start()
        }

        fun readSingle() {
            val rawPath = pathInput.text.toString().trim()
            val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
            if (!path.startsWith("/proc/sys/")) {
                showToast("For safety, parameter editing is limited to /proc/sys", Toast.LENGTH_SHORT)
                return
            }
            Thread {
                val safe = path.replace("'", "'\\''")
                val result = runRootCommand("cat '$safe' 2>/dev/null")
                runOnUiThread {
                    valueInput.setText(result)
                    output.text = "[$path]\n${if (result.isBlank()) "Unable to read parameter." else result}"
                }
            }.start()
        }

        fun writeSingle() {
            val rawPath = pathInput.text.toString().trim()
            val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
            val value = valueInput.text.toString()
            if (!path.startsWith("/proc/sys/")) {
                showToast("For safety, parameter editing is limited to /proc/sys", Toast.LENGTH_SHORT)
                return
            }
            if (value.isBlank()) {
                showToast("Enter a value first", Toast.LENGTH_SHORT)
                return
            }
            showConfirmDialog(
                "WRITE KERNEL PARAMETER",
                "Write the new value to:\n$path\n\nThis changes a live kernel setting and may be reset after reboot.",
                "WRITE",
                primaryColor
            ) {
                Thread {
                    val safePath = path.replace("'", "'\\''")
                    val safeValue = value.replace("'", "'\\''")
                    val result = runRootCommand("printf '%s' '$safeValue' > '$safePath' 2>&1 && cat '$safePath' 2>/dev/null")
                    runOnUiThread {
                        output.text = if (result.isBlank()) "Write failed or produced no output." else "[$path]\n$result"
                        showToast(if (result.isBlank()) "Kernel parameter write failed" else "Kernel parameter updated", Toast.LENGTH_SHORT)
                    }
                    termLog("› Kernel Parameters write: $path = $value")
                }.start()
            }
        }

        val readButton = dialogButton("READ", primaryColor) { readSingle() }
        val writeButton = dialogButton("WRITE", primaryColor) { writeSingle() }
        val browseButton = dialogButton("BROWSE", secondaryColor) { inspect() }
        val closeButton = dialogButton("CLOSE", secondaryColor) { dialog.dismiss() }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        actions.addView(browseButton, LinearLayout.LayoutParams(-2, dp(46)))
        actions.addView(readButton, LinearLayout.LayoutParams(-2, dp(46)))
        actions.addView(writeButton, LinearLayout.LayoutParams(-2, dp(46)))
        actions.addView(closeButton, LinearLayout.LayoutParams(-2, dp(46)))

        panel.addView(title)
        panel.addView(text("View /proc/sys values and edit supported live kernel parameters.", 13f, secondaryColor), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        panel.addView(pathInput, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        panel.addView(valueInput, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })

        dialog.setContentView(panel)
        dialog.show()
        styleAppDialog(dialog, 0.94f)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.heightPixels * 0.82f).toInt())
        inspect()
    }

    private fun showPerformanceProfiles() {
        if (!rootEnabled) {
            showToast("Root access is required", Toast.LENGTH_SHORT)
            return
        }

        data class PerformanceProfile(val name: String, val governor: String, val minFreq: String, val maxFreq: String)

        val prefs = getSharedPreferences("root_realm_performance_profiles", MODE_PRIVATE)
        val builtIns = listOf(
            PerformanceProfile("Balanced", "schedutil", "", ""),
            PerformanceProfile("Performance", "performance", "", ""),
            PerformanceProfile("Power Saver", "powersave", "", ""),
            PerformanceProfile("Emergency Battery Saver", "powersave", "", "__EMERGENCY_MIN__")
        )

        fun loadCustom(): MutableList<PerformanceProfile> {
            return prefs.getStringSet("profiles", emptySet())!!.mapNotNull { row ->
                val parts = row.split("|", limit = 4)
                if (parts.size == 4) PerformanceProfile(parts[0], parts[1], parts[2], parts[3]) else null
            }.toMutableList()
        }

        fun availableInfo(): String {
            return runRootCommand("for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"\$p\" ] || continue; echo \"[\$p]\"; printf 'governor: '; cat \"\$p/scaling_governor\" 2>/dev/null; printf 'available: '; cat \"\$p/scaling_available_governors\" 2>/dev/null; printf 'min: '; cat \"\$p/scaling_min_freq\" 2>/dev/null; printf 'max: '; cat \"\$p/scaling_max_freq\" 2>/dev/null; done")
        }

        fun applyProfile(profile: PerformanceProfile, output: TextView) {
            Thread {
                val governor = profile.governor.trim().replace("'", "'\\''")
                val min = profile.minFreq.trim().replace("'", "'\\''")
                val max = profile.maxFreq.trim().replace("'", "'\\''")
                val emergency = profile.maxFreq == "__EMERGENCY_MIN__"
                val command = if (emergency) {
                    "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"\$p\" ] || continue; " +
                        "if [ -w \"\$p/scaling_governor\" ]; then printf '%s' 'powersave' > \"\$p/scaling_governor\" 2>/dev/null; fi; " +
                        "if [ -r \"\$p/scaling_min_freq\" ] && [ -w \"\$p/scaling_max_freq\" ]; then min_freq=$(cat \"\$p/scaling_min_freq\" 2>/dev/null); [ -n \"\$min_freq\" ] && printf '%s' \"\$min_freq\" > \"\$p/scaling_max_freq\" 2>/dev/null; fi; done; " +
                        "echo Applied; " +
                        "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"\$p\" ] || continue; echo \"[\$p] $(cat \"\$p/scaling_governor\" 2>/dev/null) min=$(cat \"\$p/scaling_min_freq\" 2>/dev/null) max=$(cat \"\$p/scaling_max_freq\" 2>/dev/null)\"; done"
                } else {
"for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"\$p\" ] || continue; " +
                    "if [ -n '$governor' ] && [ -w \"\$p/scaling_governor\" ]; then printf '%s' '$governor' > \"\$p/scaling_governor\" 2>/dev/null; fi; " +
                    "if [ -n '$min' ] && [ -w \"\$p/scaling_min_freq\" ]; then printf '%s' '$min' > \"\$p/scaling_min_freq\" 2>/dev/null; fi; " +
                    "if [ -n '$max' ] && [ -w \"\$p/scaling_max_freq\" ]; then printf '%s' '$max' > \"\$p/scaling_max_freq\" 2>/dev/null; fi; done; " +
                    "echo Applied; " +
                    "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"\$p\" ] || continue; echo \"[\$p] $(cat \"\$p/scaling_governor\" 2>/dev/null) min=$(cat \"\$p/scaling_min_freq\" 2>/dev/null) max=$(cat \"\$p/scaling_max_freq\" 2>/dev/null)\"; done"
                }
                val result = runRootCommand(command)
                runOnUiThread {
                    output.text = if (result.isBlank()) "Profile could not be applied. The governor or frequency interface may be unavailable on this kernel." else result
                    showToast(if (result.isBlank()) "Profile apply failed" else "${profile.name} profile applied", Toast.LENGTH_SHORT)
                }
                termLog("› Performance Profile: ${profile.name}")
            }.start()
        }

        fun showEditor(onCreated: (PerformanceProfile) -> Unit) {
            val edit = Dialog(this)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(14))
                background = GradientDrawable().apply { setColor(cardColor); setStroke(dp(1), borderColor); cornerRadius = dp(18).toFloat() }
            }
            val name = EditText(this).apply { hint = "Profile name"; setTextColor(textColor); setHintTextColor(secondaryColor); isSingleLine = true }
            val governor = EditText(this).apply { hint = "Governor (e.g. schedutil, performance)"; setTextColor(textColor); setHintTextColor(secondaryColor); isSingleLine = true }
            val min = EditText(this).apply { hint = "Min frequency in kHz (optional)"; setTextColor(textColor); setHintTextColor(secondaryColor); isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            val max = EditText(this).apply { hint = "Max frequency in kHz (optional)"; setTextColor(textColor); setHintTextColor(secondaryColor); isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            box.addView(text("CREATE PERFORMANCE PROFILE", 20f, textColor).apply { typeface = Typeface.DEFAULT_BOLD })
            listOf(name, governor, min, max).forEachIndexed { i, v -> box.addView(v, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(if (i == 0) 12 else 8) }) }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            actions.addView(dialogButton("CANCEL", secondaryColor) { edit.dismiss() })
            actions.addView(dialogButton("CREATE", primaryColor) {
                val n = name.text.toString().trim()
                if (n.isBlank()) {
                    showToast("Enter a profile name", Toast.LENGTH_SHORT)
                } else {
                    onCreated(PerformanceProfile(n, governor.text.toString().trim(), min.text.toString().trim(), max.text.toString().trim()))
                    edit.dismiss()
                }
            })
            box.addView(actions, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
            edit.setContentView(box); edit.show(); styleAppDialog(edit)
        }

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = GradientDrawable().apply { setColor(cardColor); setStroke(dp(1), borderColor); cornerRadius = dp(18).toFloat() }
        }
        val title = text("PERFORMANCE PROFILES", 21f, textColor).apply { typeface = Typeface.DEFAULT_BOLD }
        val output = TextView(this).apply { textSize = 12f; setTextColor(secondaryColor); setTypeface(Typeface.MONOSPACE, Typeface.NORMAL); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val profileNames = TextView(this).apply { textSize = 14f; setTextColor(textColor); setPadding(dp(10), dp(8), dp(10), dp(8)) }
        var selectedProfile: PerformanceProfile? = null
        val selected = TextView(this).apply {
            text = "No profile selected"
            textSize = 14f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(bgColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(10).toFloat()
            }
        }
        val selectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val selectButton = dialogButton("SELECT", primaryColor) {
            val all = builtIns + loadCustom()
            if (all.isEmpty()) {
                showToast("No profiles available", Toast.LENGTH_SHORT)
            } else {
                val chooser = Dialog(this)
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(18), dp(18), dp(14))
                    background = GradientDrawable().apply { setColor(cardColor); setStroke(dp(1), borderColor); cornerRadius = dp(18).toFloat() }
                }
                box.addView(text("SELECT PERFORMANCE PROFILE", 20f, textColor).apply { typeface = Typeface.DEFAULT_BOLD })
                val listScroll = ScrollView(this)
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                all.forEach { profile ->
                    list.addView(dialogButton(profile.name, textColor) {
                        selectedProfile = profile
                        selected.text = profile.name
                        selected.setTextColor(textColor)
                        chooser.dismiss()
                    }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
                }
                listScroll.addView(list)
                box.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
                box.addView(dialogButton("CLOSE", secondaryColor) { chooser.dismiss() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
                chooser.setContentView(box)
                chooser.show()
                styleAppDialog(chooser)
                chooser.window?.setLayout((resources.displayMetrics.widthPixels * 0.82f).toInt(), (resources.displayMetrics.heightPixels * 0.72f).toInt())
            }
        }
        selectRow.addView(selected, LinearLayout.LayoutParams(0, dp(48), 1f))
        selectRow.addView(selectButton, LinearLayout.LayoutParams(dp(88), dp(48)).apply { leftMargin = dp(8) })

        fun refreshNames() {
            val all = builtIns + loadCustom()
            profileNames.text = all.joinToString("\n") { p -> "• ${p.name}" }
            output.text = availableInfo().ifBlank { "No cpufreq policy information found." }
        }

        val scroll = ScrollView(this).apply { addView(output) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(dialogButton("CREATE", primaryColor) { showEditor { profile ->
            val set = prefs.getStringSet("profiles", emptySet())!!.toMutableSet()
            set.removeAll { it.substringBefore("|") == profile.name }
            set.add(listOf(profile.name, profile.governor, profile.minFreq, profile.maxFreq).joinToString("|"))
            prefs.edit().putStringSet("profiles", set).apply()
            refreshNames()
        } })
        actions.addView(dialogButton("APPLY", primaryColor) {
            val profile = selectedProfile
            if (profile == null) {
                showToast("Select a profile first", Toast.LENGTH_SHORT)
            } else {
                applyProfile(profile, output)
            }
        })
        actions.addView(dialogButton("REFRESH", secondaryColor) { refreshNames() })
        actions.addView(dialogButton("CLOSE", secondaryColor) { dialog.dismiss() })

        panel.addView(title)
        panel.addView(text("Select a built-in or custom CPU frequency profile. Availability depends on the kernel's cpufreq driver.", 13f, secondaryColor), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        panel.addView(profileNames, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.addView(selectRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })

        dialog.setContentView(panel); dialog.show(); styleAppDialog(dialog, 0.94f)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.heightPixels * 0.82f).toInt())
        refreshNames()
    }

    private fun dialogButton(
        label: String,
        color: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            minHeight = dp(44)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun loadSystemInformation(
        view: TextView
    ) {

        Thread {

            val manufacturer =
                android.os.Build.MANUFACTURER

            val model =
                android.os.Build.MODEL

            val device =
                android.os.Build.DEVICE

            val androidVersion =
                android.os.Build.VERSION.RELEASE

            val sdk =
                android.os.Build.VERSION.SDK_INT

            val build =
                android.os.Build.DISPLAY

            val kernel =
                runShellCommand("uname -r")

            val selinux =
                runShellCommand("getenforce")

            val slot =
                getCurrentSlot()

            val information =
                "Device: $manufacturer $model\n" +
                "Codename: $device\n" +
                "Android: $androidVersion\n" +
                "SDK: $sdk\n" +
                "Build: $build\n" +
                "Kernel: ${kernel.ifBlank { "Unknown" }}\n" +
                "SELinux: ${selinux.ifBlank { "Unknown" }}\n" +
                "Current slot: $slot"

            runOnUiThread {

                view.text = information
                view.setTextColor(secondaryColor)
            }

        }.start()
    }
}
