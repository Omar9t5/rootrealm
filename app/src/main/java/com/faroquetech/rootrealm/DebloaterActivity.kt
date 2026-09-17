package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager

import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

class DebloaterActivity : BaseActivity() {

    // Exact Backup & Restore theme
    private val bgColor get() = ThemeManager.current(this).background
    private val cardColor get() = ThemeManager.current(this).surface
    private val borderColor get() = ThemeManager.current(this).let { ThemeManager.borderColor(it) }
    private val primaryColor get() = ThemeManager.current(this).accent
    private val textColor get() = ThemeManager.current(this).text
    private val secondaryColor get() = ThemeManager.current(this).secondary
    private val dangerColor get() = ThemeManager.current(this).error

    private lateinit var rootLayout: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var resultCount: TextView
    private lateinit var selectionCount: TextView
    private lateinit var rootStatus: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var activeAccess: TextView

    private val allApps = mutableListOf<ApplicationInfo>()
    private val selectedPackages = LinkedHashSet<String>()

    private enum class AppCategory {
        SYSTEM,
        CORE,
        USER
    }

    private var selectedCategory = AppCategory.USER

    private var rootEnabled = false
    private var shizukuAvailable = false

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var terminalOutput: TextView
    private lateinit var terminalScroll: ScrollView
    private val logBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        buildUi()
        detectAccess()
        loadApps()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun createText(
        value: String,
        size: Float,
        color: Int = textColor
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
        }
    }

    private fun createSectionHeader(value: String): TextView {
        return createText(
            value,
            13f,
            primaryColor
        ).apply {
            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
        }
    }

    private fun createCard(): LinearLayout {
        val background = GradientDrawable().apply {
            setColor(cardColor)
            setStroke(dp(1), borderColor)
            cornerRadius = dp(14).toFloat()
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(16),
                dp(16),
                dp(16),
                dp(16)
            )
            this.background = background
        }
    }

    private fun createButton(
        title: String,
        color: Int = primaryColor
    ): Button {
        val background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }

        return Button(this).apply {
            text = title
            textSize = 13f
            setTextColor(textColor)
            isAllCaps = false
            this.background = background
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(
                dp(8),
                0,
                dp(8),
                0
            )
        }
    }

    private fun buildUi() {

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(32)
            )
        }

        val title = createText(
            "Debloater & App Manager",
            28f,
            textColor
        )

        title.setTypeface(
            Typeface.DEFAULT,
            Typeface.BOLD
        )

        content.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val subtitle = createText(
            "Debloat, manage, inspect and maintain installed apps",
            14f,
            secondaryColor
        )

        content.addView(
            subtitle,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(18)
            }
        )

        rootStatus = createText(
            "Checking root access…",
            14f,
            secondaryColor
        )

        val rootCard = createCard()

        rootCard.addView(
            rootStatus,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        content.addView(
            rootCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        shizukuStatus = createText(
            "Shizuku: Checking…",
            14f,
            secondaryColor
        )

        val shizukuCard = createCard()

        shizukuCard.addView(
            shizukuStatus,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        content.addView(
            shizukuCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        activeAccess = createText(
            "Active access: Checking…",
            14f,
            secondaryColor
        )

        val accessCard = createCard()

        accessCard.addView(
            activeAccess,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        content.addView(
            accessCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        content.addView(
            createSectionHeader("PACKAGE SEARCH"),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val searchCard = createCard()

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        search = EditText(this).apply {
            hint = "App name or package name"
            setHintTextColor(
                secondaryColor
            )
            setTextColor(textColor)
            textSize = 15f
            setSingleLine(true)
            setPadding(
                dp(12),
                0,
                dp(12),
                0
            )

            background = GradientDrawable().apply {
                setColor(bgColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(10).toFloat()
            }
        }

        searchRow.addView(
            search,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                marginEnd = dp(8)
            }
        )

        val searchButton = createButton("SEARCH")

        searchRow.addView(
            searchButton,
            LinearLayout.LayoutParams(
                dp(100),
                dp(52)
            )
        )

        searchCard.addView(
            searchRow,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val clearButton = createButton(
            "CLEAR",
            cardColor
        )

        searchCard.addView(
            clearButton,
            LinearLayout.LayoutParams(
                -1,
                dp(46)
            ).apply {
                topMargin = dp(10)
            }
        )

        resultCount = createText(
            "Loading packages…",
            12f,
            secondaryColor
        )

        searchCard.addView(
            resultCount,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(8)
            }
        )

        content.addView(
            searchCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        searchButton.setOnClickListener {
            performSearch()
        }

        clearButton.setOnClickListener {
            search.setText("")
            renderApps(getFilteredApps())
        }
        content.addView(
            createSectionHeader("APP CATEGORY"),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val categoryCard = createCard()

        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val systemCategoryButton = createButton("SYSTEM")
        val coreCategoryButton = createButton("CORE", dangerColor)
        val userCategoryButton = createButton("USER", primaryColor)

        categoryRow.addView(
            systemCategoryButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(4)
            }
        )

        categoryRow.addView(
            coreCategoryButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        )

        categoryRow.addView(
            userCategoryButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginStart = dp(4)
            }
        )

        categoryCard.addView(
            categoryRow,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val categoryInfo = createText(
            "User apps are selected by default. Core apps require extra confirmation.",
            12f,
            secondaryColor
        )

        categoryCard.addView(
            categoryInfo,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(10)
            }
        )

        content.addView(
            categoryCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        fun selectCategory(category: AppCategory) {
            selectedCategory = category
            selectedPackages.clear()
            updateSelectionCount()
            updateCategoryButtons(
                systemCategoryButton,
                coreCategoryButton,
                userCategoryButton
            )
            renderApps(getFilteredApps())
        }

        systemCategoryButton.setOnClickListener {
            selectCategory(AppCategory.SYSTEM)
        }

        coreCategoryButton.setOnClickListener {
            selectCategory(AppCategory.CORE)
        }

        userCategoryButton.setOnClickListener {
            selectCategory(AppCategory.USER)
        }

        updateCategoryButtons(
            systemCategoryButton,
            coreCategoryButton,
            userCategoryButton
        )

        content.addView(
            createSectionHeader("ACTIONS"),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val actionCard = createCard()

        selectionCount = createText(
            "Selected: 0",
            14f,
            secondaryColor
        )

        actionCard.addView(
            selectionCount,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(12)
            }
        )

        val selectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val selectAllButton = createButton("SELECT ALL")

        val clearSelectionButton = createButton(
            "CLEAR",
            cardColor
        )

        selectionRow.addView(
            selectAllButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        selectionRow.addView(
            clearSelectionButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginStart = dp(6)
            }
        )

        actionCard.addView(
            selectionRow,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val disableButton = createButton(
            "DISABLE",
            dangerColor
        )

        val enableButton = createButton(
            "ENABLE",
            primaryColor
        )

        actionRow.addView(
            disableButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(6)
                topMargin = dp(10)
            }
        )

        actionRow.addView(
            enableButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginStart = dp(6)
                topMargin = dp(10)
            }
        )

        actionCard.addView(
            actionRow,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val uninstallButton = createButton(
            "UNINSTALL SELECTED",
            dangerColor
        )

        actionCard.addView(
            uninstallButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                topMargin = dp(10)
            }
        )

        content.addView(
            actionCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        selectAllButton.setOnClickListener {
            selectAllVisible()
        }

        clearSelectionButton.setOnClickListener {
            selectedPackages.clear()
            updateSelectionCount()
            renderApps(getFilteredApps())
        }

        disableButton.setOnClickListener {
            if (!requireSelectionAndAccess()) return@setOnClickListener
            confirmDestructiveAction(
                "Disable selected apps?",
                buildSelectionMessage("The selected packages will be disabled for user 0."),
                "DISABLE",
                dangerColor
            ) {
                disableSelected()
            }
        }

        enableButton.setOnClickListener {
            if (!requireSelectionAndAccess()) return@setOnClickListener
            confirmDestructiveAction(
                "Enable selected apps?",
                buildSelectionMessage("The selected packages will be enabled again."),
                "ENABLE",
                primaryColor
            ) {
                enableSelected()
            }
        }

        uninstallButton.setOnClickListener {
            if (!requireSelectionAndAccess()) return@setOnClickListener
            confirmDestructiveAction(
                "Uninstall selected apps?",
                buildSelectionMessage("The selected apps will be uninstalled for user 0."),
                "UNINSTALL",
                dangerColor
            ) {
                uninstallSelected()
            }
        }

        content.addView(
            createSectionHeader("TERMINAL / LOG"),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val terminalCard = createCard()
        terminalOutput = TextView(this).apply {
            text = "Debloater & App Manager terminal ready.\n"
            setTextColor(Color.parseColor("#A8FF60"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        terminalScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0D1117"))
            addView(terminalOutput, ViewGroup.LayoutParams(-1, -2))
        }
        terminalCard.addView(terminalScroll, LinearLayout.LayoutParams(-1, dp(150)))
        val termBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val tTop = createButton("▲ TOP", cardColor)
        val tUp = createButton("▲ UP", cardColor)
        val tDown = createButton("▼ DOWN", cardColor)
        val tEnd = createButton("▼ END", cardColor)
        val tClear = createButton("CLEAR", cardColor)
        listOf(tTop, tUp, tDown, tEnd, tClear).forEachIndexed { i, b ->
            termBtns.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (i < 4) marginEnd = dp(4)
            })
        }
        terminalCard.addView(termBtns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        tTop.setOnClickListener { terminalScroll.post { terminalScroll.fullScroll(android.view.View.FOCUS_UP) } }
        // UP/END/TOP all defer their scroll inside terminalScroll.post { }, which runs the
        // scroll after any pending layout pass has settled. DOWN previously called
        // smoothScrollBy directly on click, so if it fired while the ScrollView's child
        // was mid-relayout (e.g. right after termLog updated the text a moment earlier),
        // it computed its max-scroll against a stale height and the tap did nothing.
        // Wrapping it in .post { } like the others fixes that race.
        tUp.setOnClickListener { terminalScroll.post { terminalScroll.smoothScrollBy(0, -dp(120)) } }
        tDown.setOnClickListener { terminalScroll.post { terminalScroll.smoothScrollBy(0, dp(120)) } }
        tEnd.setOnClickListener { terminalScroll.post { terminalScroll.fullScroll(android.view.View.FOCUS_DOWN) } }
        tClear.setOnClickListener {
            logBuffer.clear()
            terminalOutput.text = "Log cleared.\n"
        }

        // Log-capture row: pulls the two most useful logs for diagnosing what the
        // debloater is doing (userspace logcat + kernel dmesg), filters them down to
        // package-manager/app-related lines, and explains what's found in plain words.
        val logBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val tTakeLog = createButton("TAKE LOG", cardColor)
        val tKernelLog = createButton("KERNEL LOG", cardColor)
        logBtns.addView(tTakeLog, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        logBtns.addView(tKernelLog, LinearLayout.LayoutParams(0, dp(42), 1f))
        terminalCard.addView(logBtns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        tTakeLog.setOnClickListener { takeLogcat() }
        tKernelLog.setOnClickListener { takeDmesg() }

        content.addView(terminalCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        content.addView(
            createSectionHeader("INSTALLED PACKAGES"),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val listCard = createCard()

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        listCard.addView(
            list,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        content.addView(
            listCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        // IMPORTANT:
        // ScrollView uses ViewGroup.LayoutParams here.
        scrollView.addView(
            content,
            ViewGroup.LayoutParams(
                -1,
                -2
            )
        )

        rootLayout.addView(
            scrollView,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(rootLayout)
    }

    private fun loadApps() {

        Thread {

            try {

                val apps =
                    packageManager
                        .getInstalledApplications(
                            PackageManager.GET_META_DATA
                        )
                        .sortedWith(
                            compareBy {
                                packageManager
                                    .getApplicationLabel(it)
                                    .toString()
                                    .lowercase(
                                        Locale.getDefault()
                                    )
                            }
                        )

                allApps.clear()
                allApps.addAll(apps)

                runOnUiThread {
                    renderApps(getFilteredApps())
                }

            } catch (e: Exception) {

                runOnUiThread {

                    resultCount.text =
                        "Unable to load packages"

                    showToast(
                        e.message
                            ?: "Unknown error"
                    )
                }
            }

        }.start()
    }

    private fun performSearch() {
        renderApps(getFilteredApps())
    }

    private fun getFilteredApps():
        List<ApplicationInfo> {

        val query =
            search.text
                .toString()
                .trim()
                .lowercase(
                    Locale.getDefault()
                )

        val categoryApps =
            allApps.filter { app ->
                when (selectedCategory) {
                    AppCategory.CORE ->
                        isCoreApp(app)

                    AppCategory.SYSTEM ->
                        isSystemApp(app) && !isCoreApp(app)

                    AppCategory.USER ->
                        !isSystemApp(app)
                }
            }

        if (query.isEmpty()) {
            return categoryApps
        }

        return categoryApps.filter { app ->

            val name =
                packageManager
                    .getApplicationLabel(app)
                    .toString()
                    .lowercase(
                        Locale.getDefault()
                    )

            val packageName =
                app.packageName
                    .lowercase(
                        Locale.getDefault()
                    )

            name.contains(query) ||
                packageName.contains(query)
        }
    }

    private fun isSystemApp(
        app: ApplicationInfo
    ): Boolean {
        return (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private fun isCoreApp(
        app: ApplicationInfo
    ): Boolean {
        if (!isSystemApp(app)) {
            return false
        }

        val pkg = app.packageName.lowercase(
            Locale.getDefault()
        )

        return pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg == "com.android.settings" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "com.google.android.permissioncontroller" ||
            pkg == "com.android.providers.settings" ||
            pkg == "com.android.providers.media" ||
            pkg == "com.android.providers.telephony" ||
            pkg == "com.android.phone" ||
            pkg == "com.android.shell" ||
            pkg == "com.android.server.telecom"
    }

    private fun updateCategoryButtons(
        systemButton: Button,
        coreButton: Button,
        userButton: Button
    ) {
        val selectedBackground = primaryColor
        val normalBackground = cardColor

        systemButton.background =
            GradientDrawable().apply {
                setColor(
                    if (selectedCategory == AppCategory.SYSTEM)
                        selectedBackground
                    else
                        normalBackground
                )
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), borderColor)
            }

        coreButton.background =
            GradientDrawable().apply {
                setColor(
                    if (selectedCategory == AppCategory.CORE)
                        dangerColor
                    else
                        normalBackground
                )
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), borderColor)
            }

        userButton.background =
            GradientDrawable().apply {
                setColor(
                    if (selectedCategory == AppCategory.USER)
                        selectedBackground
                    else
                        normalBackground
                )
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), borderColor)
            }

        systemButton.setTextColor(textColor)
        coreButton.setTextColor(textColor)
        userButton.setTextColor(textColor)
    }

    private fun renderApps(
        apps: List<ApplicationInfo>
    ) {

        list.removeAllViews()

        resultCount.text =
            "${apps.size} packages found"

        if (apps.isEmpty()) {

            val empty =
                createText(
                    "No packages found",
                    14f,
                    secondaryColor
                ).apply {

                    gravity = Gravity.CENTER

                    setPadding(
                        0,
                        dp(20),
                        0,
                        dp(20)
                    )
                }

            list.addView(
                empty,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )

            return
        }

        apps.forEach { app ->
            addPackageRow(app)
        }
    }

    private fun addPackageRow(
        app: ApplicationInfo
    ) {

        val packageName = app.packageName

        val appName =
            packageManager
                .getApplicationLabel(app)
                .toString()

        val rowBackground =
            GradientDrawable().apply {

                setColor(
                    Color.rgb(
                        10,
                        10,
                        10
                    )
                )

                setStroke(
                    dp(1),
                    borderColor
                )

                cornerRadius =
                    dp(10).toFloat()
            }

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(10),
                    dp(10),
                    dp(10),
                    dp(10)
                )

                background =
                    rowBackground
            }

        val checkBox =
            CheckBox(this).apply {

                isChecked =
                    selectedPackages
                        .contains(packageName)

                buttonTintList =
                    ColorStateList(
                        arrayOf(
                            intArrayOf(
                                android.R.attr.state_checked
                            ),
                            intArrayOf()
                        ),
                        intArrayOf(
                            primaryColor,
                            secondaryColor
                        )
                    )
            }

        row.addView(
            checkBox,
            LinearLayout.LayoutParams(
                dp(42),
                dp(42)
            )
        )

        val textContainer =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val nameText =
            createText(
                appName,
                14f,
                textColor
            )

        nameText.setTypeface(
            Typeface.DEFAULT,
            Typeface.BOLD
        )

        val packageText =
            createText(
                packageName,
                12f,
                secondaryColor
            )

        textContainer.addView(
            nameText,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        textContainer.addView(
            packageText,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(3)
            }
        )

        row.addView(
            textContainer,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        val manageButton = createButton("MANAGE", cardColor).apply {
            textSize = 11f
        }
        row.addView(
            manageButton,
            LinearLayout.LayoutParams(
                dp(88),
                dp(44)
            ).apply {
                marginStart = dp(6)
            }
        )
        manageButton.setOnClickListener {
            showAppManager(app)
        }

        checkBox.setOnCheckedChangeListener {
            _,
            checked ->

            if (checked) {
                selectedPackages.add(
                    packageName
                )
            } else {
                selectedPackages.remove(
                    packageName
                )
            }

            updateSelectionCount()
        }

        row.setOnClickListener {
            checkBox.isChecked =
                !checkBox.isChecked
        }

        list.addView(
            row,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun showAppManager(app: ApplicationInfo) {
        val appName = packageManager.getApplicationLabel(app).toString()
        val pkg = app.packageName
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = createCard().apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
        val title = createText("$appName", 18f).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        card.addView(createText(pkg, 12f, secondaryColor), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val actions = listOf(
            "APP INFO" to { showAppInfoDialog(app) },
            "PACKAGE MANAGER" to { showPackageManagerDialog(pkg) },
            "APP PERMISSIONS" to { showPermissionsDialog(app) },
            "EXTRACT APK" to { extractApk(app) },
            "CLEAR CACHE" to { confirmAction("Clear cache?", "Cache files for $appName will be removed. App data will remain.", "CLEAR CACHE", dangerColor) { clearAppCache(pkg) } },
            "CLEAR DATA" to { confirmAction("Clear app data?", "This removes the app's stored user data and cache for $appName.", "CLEAR DATA", dangerColor) { clearAppData(pkg) } }
        )
        actions.forEachIndexed { index, pair ->
            val button = createButton(pair.first, if (pair.first == "CLEAR DATA" || pair.first == "CLEAR CACHE") dangerColor else primaryColor)
            card.addView(button, LinearLayout.LayoutParams(-1, dp(46)).apply {
                if (index > 0) topMargin = dp(8)
            })
            button.setOnClickListener {
                dialog.dismiss()
                pair.second()
            }
        }
        val close = createButton("CLOSE", cardColor)
        card.addView(close, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        close.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.7f)
        }
        dialog.show()
    }

    private fun showAppInfoDialog(app: ApplicationInfo) {
        val pm = packageManager
        val pkg = app.packageName
        val info = try { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) } catch (e: Exception) { null }
        val versionName = info?.versionName ?: "Unknown"
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info?.longVersionCode?.toString() ?: "Unknown" else @Suppress("DEPRECATION") { info?.versionCode?.toString() ?: "Unknown" }
        val installer = try { pm.getInstallerPackageName(pkg) ?: "Unknown" } catch (_: Exception) { "Unknown" }
        val details = buildString {
            append("App: ${pm.getApplicationLabel(app)}\n\n")
            append("Package: $pkg\n")
            append("Version: $versionName\n")
            append("Version code: $versionCode\n")
            append("UID: ${app.uid}\n")
            append("Target SDK: ${app.targetSdkVersion}\n")
            append("Min SDK: ${app.minSdkVersion}\n")
            append("APK: ${app.sourceDir}\n")
            if (app.splitSourceDirs?.isNotEmpty() == true) {
                append("Splits: ${app.splitSourceDirs!!.joinToString("; ")}\n")
            }
            append("Data directory: ${app.dataDir}\n")
            append("Installer: $installer\n")
            append("System app: ${isSystemApp(app)}")
        }
        showTextDialog("APP INFO", details)
    }

    private fun showPackageManagerDialog(pkg: String) {
        if (!hasAccess()) {
            showToast("Root or Shizuku access is required")
            return
        }
        Thread {
            val (out, err) = runPrivilegedCapture("dumpsys package ${shellQuote(pkg)}", 10000)
            runOnUiThread {
                showTextDialog("PACKAGE MANAGER — $pkg", if (out.isNotBlank()) out else err.ifBlank { "No package-manager information returned." })
            }
        }.start()
    }

    private fun showPermissionsDialog(app: ApplicationInfo) {
        val pkg = app.packageName
        val info = try { packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) } catch (e: Exception) { null }
        val permissions = info?.requestedPermissions?.toList().orEmpty()
        if (permissions.isEmpty()) {
            showTextDialog("APP PERMISSIONS", "No declared permissions found for $pkg.")
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = createCard()
        card.addView(createText("APP PERMISSIONS", 18f).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        card.addView(createText(pkg, 12f, secondaryColor), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permissions.forEach { permission ->
            val granted = try { packageManager.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val label = createText(if (granted) "✓ GRANTED" else "✕ DENIED", 12f, if (granted) primaryColor else dangerColor)
            val name = createText(permission, 11f, textColor)
            row.addView(label)
            row.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val grant = createButton("GRANT", primaryColor)
            val revoke = createButton("REVOKE", dangerColor)
            buttons.addView(grant, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4); topMargin = dp(4) })
            buttons.addView(revoke, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4); topMargin = dp(4) })
            row.addView(buttons)
            grant.setOnClickListener { changePermission(pkg, permission, true, label) }
            revoke.setOnClickListener { changePermission(pkg, permission, false, label) }
            body.addView(row)
        }
        scroll.addView(body)
        card.addView(scroll, LinearLayout.LayoutParams(-1, dp(420)))
        val close = createButton("CLOSE", cardColor)
        card.addView(close, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        close.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.7f)
        }
        dialog.show()
    }

    private fun changePermission(pkg: String, permission: String, grant: Boolean, status: TextView) {
        if (!hasAccess()) { showToast("Root or Shizuku access is required"); return }
        val command = if (grant) "pm grant ${shellQuote(pkg)} ${shellQuote(permission)}" else "pm revoke ${shellQuote(pkg)} ${shellQuote(permission)}"
        Thread {
            val ok = executeCommand(command)
            runOnUiThread {
                if (ok) {
                    status.text = if (grant) "✓ GRANTED" else "✕ DENIED"
                    status.setTextColor(if (grant) primaryColor else dangerColor)
                    showToast(if (grant) "Permission granted" else "Permission revoked")
                } else showToast("Permission change failed")
            }
        }.start()
    }

    private fun extractApk(app: ApplicationInfo) {
        if (!hasAccess()) { showToast("Root or Shizuku access is required"); return }
        val paths = mutableListOf(app.sourceDir)
        app.splitSourceDirs?.let { paths.addAll(it) }
        val dir = "/sdcard/RootRealm/APK/${shellSafeName(app.packageName)}"
        Thread {
            var success = 0
            executeCommand("mkdir -p ${shellQuote(dir)}")
            paths.forEachIndexed { index, source ->
                val name = if (index == 0) "base.apk" else "split_${index}.apk"
                if (executeCommand("cp -f ${shellQuote(source)} ${shellQuote("$dir/$name")}")) success++
            }
            runOnUiThread { showToast("Extracted $success/${paths.size} APK file(s) to $dir") }
        }.start()
    }

    private fun clearAppCache(pkg: String) {
        if (!hasAccess()) { showToast("Root or Shizuku access is required"); return }
        Thread {
            val command = "rm -rf /data/user/0/${shellSafeName(pkg)}/cache /data/user_de/0/${shellSafeName(pkg)}/cache /data/data/${shellSafeName(pkg)}/cache"
            val ok = executeCommand(command)
            runOnUiThread { showToast(if (ok) "Cache cleared" else "Failed to clear cache") }
        }.start()
    }

    private fun clearAppData(pkg: String) {
        if (!hasAccess()) { showToast("Root or Shizuku access is required"); return }
        Thread {
            val ok = executeCommand("pm clear --user 0 ${shellQuote(pkg)}")
            runOnUiThread { showToast(if (ok) "App data cleared" else "Failed to clear app data") }
        }.start()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun shellSafeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun showTextDialog(title: String, text: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = createCard()
        card.addView(createText(title, 17f).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val scroll = ScrollView(this)
        val body = createText(text, 11f, textColor).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        scroll.addView(body)
        card.addView(scroll, LinearLayout.LayoutParams(-1, dp(420)))
        val close = createButton("CLOSE", cardColor)
        card.addView(close, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        close.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.7f)
        }
        dialog.show()
    }

    private fun selectAllVisible() {

        getFilteredApps().forEach { app ->
            selectedPackages.add(
                app.packageName
            )
        }

        updateSelectionCount()

        renderApps(
            getFilteredApps()
        )
    }

    private fun updateSelectionCount() {

        selectionCount.text =
            "Selected: ${selectedPackages.size}"
    }
  private fun detectAccess() {

    Thread {

        rootEnabled = checkRootAccess()
        shizukuAvailable = checkShizuku()

        runOnUiThread {

            rootStatus.text =
                if (rootEnabled) {
                    "Root access: Active"
                } else {
                    "Root access: Not available"
                }

            rootStatus.setTextColor(
                if (rootEnabled) {
                    primaryColor
                } else {
                    secondaryColor
                }
            )

            shizukuStatus.text =
                if (shizukuAvailable) {
                    "Shizuku: Available"
                } else {
                    "Shizuku: Not available"
                }

            shizukuStatus.setTextColor(
                if (shizukuAvailable) {
                    primaryColor
                } else {
                    secondaryColor
                }
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
        }

    }.start()
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

        val output = reader.readText()

        reader.close()

        process.waitFor()

        output.contains("uid=0")

    } catch (e: Exception) {
        false
    }
}

    /** True only when we actually have a way to run privileged commands. */
    private fun hasAccess(): Boolean {
        return rootEnabled || shizukuAvailable
    }

    /**
     * Gate for every destructive action button. Blocks the action (and the
     * confirmation dialog) entirely if nothing is selected or if neither
     * root nor Shizuku access is currently available.
     */
    private fun confirmDestructiveAction(
        title: String,
        message: String,
        positiveText: String,
        positiveColor: Int,
        action: () -> Unit
    ) {
        val containsCore =
            selectedPackages.any { pkg ->
                allApps.firstOrNull {
                    it.packageName == pkg
                }?.let { isCoreApp(it) } == true
            }

        val finalMessage =
            if (containsCore) {
                "⚠ WARNING: One or more selected packages are classified as CORE apps. " +
                    "Removing or disabling them may affect Android or essential system features.\\n\\n" +
                    message
            } else {
                message
            }

        confirmAction(
            title,
            finalMessage,
            positiveText,
            positiveColor,
            action
        )
    }

    private fun requireSelectionAndAccess(): Boolean {

        if (selectedPackages.isEmpty()) {
            showToast("No packages selected")
            return false
        }

        if (!hasAccess()) {
            showToast("Root or Shizuku access is required")
            return false
        }

        return true
    }

    private fun checkShizuku(): Boolean {

        return try {

            val clazz =
                Class.forName(
                    "rikka.shizuku.Shizuku"
                )

            val pingBinder =
                clazz.getMethod(
                    "pingBinder"
                )

            val binderAlive =
                pingBinder.invoke(null) as Boolean

            if (!binderAlive) {
                return false
            }

            // Binder being alive only means the Shizuku service is
            // running — it does NOT mean this app has been granted
            // permission to use it. Check that explicitly too.
            val checkSelfPermission =
                clazz.getMethod(
                    "checkSelfPermission"
                )

            val permissionResult =
                checkSelfPermission.invoke(null) as Int

            // PackageManager.PERMISSION_GRANTED == 0
            permissionResult == 0

        } catch (
            e: Exception
        ) {
            false
        }
    }

    private fun executeRootCommand(
        command: String
    ): Boolean {

        return try {

            val process =
                Runtime.getRuntime()
                    .exec(
                        arrayOf(
                            "su",
                            "-c",
                            command
                        )
                    )

            process.waitFor()

            process.exitValue() == 0

        } catch (
            e: Exception
        ) {
            false
        }
    }

    /**
     * Runs a shell command via Shizuku's privileged process API using
     * reflection, so this file doesn't require a hard compile-time
     * dependency on the Shizuku AAR.
     *
     * NOTE: `Shizuku.newProcess` is the legacy API and is deprecated on
     * newer Shizuku versions in favor of a proper Shizuku UserService
     * (AIDL-based). If you add the real `dev.rikka.shizuku:api` dependency,
     * prefer wiring up a UserService instead of this reflection path —
     * this is kept dependency-free as a minimal working fallback.
     */
    private fun executeShizukuCommand(command: String): Boolean {

        return try {

            val clazz = Class.forName("rikka.shizuku.Shizuku")

            val method = clazz.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            process.waitFor()
            process.exitValue() == 0

        } catch (e: Exception) {
            false
        }
    }

    /**
     * Single entry point for every privileged command. Always re-checks
     * access at call time and routes to whichever method is active,
     * preferring root when both are available.
     */
    private fun executeCommand(command: String): Boolean {
        termLog("› $command")
        val ok = when {
            rootEnabled -> executeRootCommand(command)
            shizukuAvailable -> executeShizukuCommand(command)
            else -> false
        }
        termLog("  → ${if (ok) "OK" else "FAIL"}")
        return ok
    }

    private fun disableSelected() {

        if (!requireSelectionAndAccess()) {
            return
        }

        var success = 0

        selectedPackages
            .toList()
            .forEach { pkg ->

                if (
                    executeCommand(
                        "pm disable-user --user 0 $pkg"
                    )
                ) {
                    success++
                }
            }

        showToast(
            "$success package(s) disabled"
        )

        handler.postDelayed(
            {
                loadApps()
            },
            300
        )
    }

    private fun enableSelected() {

        if (!requireSelectionAndAccess()) {
            return
        }

        var success = 0

        selectedPackages
            .toList()
            .forEach { pkg ->

                if (
                    executeCommand(
                        "pm enable $pkg"
                    )
                ) {
                    success++
                }
            }

        showToast(
            "$success package(s) enabled"
        )

        handler.postDelayed(
            {
                loadApps()
            },
            300
        )
    }

    private fun uninstallSelected() {

        if (!requireSelectionAndAccess()) {
            return
        }

        var success = 0

        selectedPackages
            .toList()
            .forEach { pkg ->

                if (
                    executeCommand(
                        "pm uninstall --user 0 $pkg"
                    )
                ) {
                    success++

                    selectedPackages.remove(
                        pkg
                    )
                }
            }

        showToast(
            "$success package(s) uninstalled"
        )

        updateSelectionCount()

        handler.postDelayed(
            {
                loadApps()
            },
            300
        )
    }

    private fun buildSelectionMessage(
        actionDescription: String
    ): String {
        val selected = selectedPackages.toList()

        val shown = selected
            .take(20)
            .joinToString("\\n") { pkg ->
                val app = allApps.firstOrNull {
                    it.packageName == pkg
                }

                val name =
                    if (app != null) {
                        packageManager
                            .getApplicationLabel(app)
                            .toString()
                    } else {
                        pkg
                    }

                "• $name\\n  $pkg"
            }

        val extra =
            if (selected.size > 20) {
                "\\n…and ${selected.size - 20} more"
            } else {
                ""
            }

        return "$actionDescription\\n\\n$shown$extra"
    }

    private fun confirmAction(
        title: String,
        message: String,
        positiveText: String,
        positiveColor: Int = dangerColor,
        action: () -> Unit
    ) {

        val dialog = Dialog(this)

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        val card = createCard().apply {
            setPadding(
                dp(20),
                dp(20),
                dp(20),
                dp(20)
            )
        }

        val titleText = createText(
            title,
            17f,
            textColor
        ).apply {
            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
        }

        card.addView(
            titleText,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val messageText = createText(
            message,
            14f,
            secondaryColor
        )

        card.addView(
            messageText,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(22)
            }
        )

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val cancelButton = createButton(
            "CANCEL",
            cardColor
        )

        val positiveButton = createButton(
            positiveText,
            positiveColor
        )

        buttonRow.addView(
            cancelButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        buttonRow.addView(
            positiveButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginStart = dp(6)
            }
        )

        card.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        positiveButton.setOnClickListener {
            dialog.dismiss()
            action()
        }

        dialog.setContentView(card)

        dialog.window?.apply {
            setBackgroundDrawable(
                ColorDrawable(Color.TRANSPARENT)
            )
            setLayout(
                (resources.displayMetrics.widthPixels * 0.86).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            setDimAmount(0.7f)
        }

        dialog.show()
    }

    // Keywords used to filter raw logs down to lines actually relevant to package
    // management / app installs, so the terminal isn't flooded with unrelated noise.
    private val LOG_KEYWORDS = listOf(
        "packagemanager", "pm ", "pm.", "installer", "uninstall", "disable-user",
        "apk", "app_process", "activitymanager", "permission", "denied", "avc:"
    )

    /**
     * Runs a shell command with output captured, using whichever privileged access
     * is currently active (root preferred, Shizuku as fallback) - same routing as
     * executeCommand(), but this variant returns stdout/stderr instead of just a
     * success boolean, since log capture needs the actual output.
     *
     * Wrapped in the shell's own `timeout` so a stuck command (dmesg can hang on
     * some ROMs) is killed by the shell itself - Process.destroy() on a `su -c`
     * child only kills the su wrapper, not a grandchild process it spawned, so
     * relying on the Java-side timeout alone can leave the real command running.
     */
    private fun runPrivilegedCapture(command: String, timeoutMs: Long = 8000): Pair<String, String> {
        if (!rootEnabled && !shizukuAvailable) {
            return "" to "No root or Shizuku access available"
        }
        val wrapped = "timeout 5 $command"
        return try {
            val process: Process = if (rootEnabled) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", wrapped))
            } else {
                val clazz = Class.forName("rikka.shizuku.Shizuku")
                val method = clazz.getMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.invoke(null, arrayOf("sh", "-c", wrapped), null, null) as Process
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return "" to "Timed out waiting for the command"
            }
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText()
            var err = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            if (out.isBlank() && err.contains("not found", true)) {
                // Device has no `timeout` binary - fall back to the bare command.
                val fallbackProc = if (rootEnabled) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                } else {
                    val clazz = Class.forName("rikka.shizuku.Shizuku")
                    val method = clazz.getMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    )
                    method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
                }
                if (!fallbackProc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    fallbackProc.destroy()
                    return "" to "Timed out waiting for the command"
                }
                val fOut = BufferedReader(InputStreamReader(fallbackProc.inputStream)).readText()
                err = BufferedReader(InputStreamReader(fallbackProc.errorStream)).readText().trim()
                return fOut to err
            }
            out to err
        } catch (e: Exception) {
            "" to (e.message ?: "unknown error")
        }
    }

    private fun takeLogcat() {
        termLog("── LOGCAT (package manager lines) ──")
        termLog("Capturing recent system log…")
        Thread {
            val (out, err) = runPrivilegedCapture("logcat -d -t 800")
            runOnUiThread {
                if (out.isBlank()) {
                    termLog(
                        "No logcat output" + (err.takeIf { it.isNotBlank() }?.let { ": $it" }
                            ?: " (logcat may be restricted or empty on this device).")
                    )
                    return@runOnUiThread
                }
                val relevant = out.lineSequence()
                    .filter { line -> LOG_KEYWORDS.any { line.contains(it, ignoreCase = true) } }
                    .toList()
                    .takeLast(60)
                if (relevant.isEmpty()) {
                    termLog("No package-manager related lines found in recent logcat.")
                } else {
                    relevant.forEach { termLog(it) }
                }
                termLog("── In simple words ──")
                explainLog(relevant).forEach { termLog("• $it") }
            }
        }.start()
    }

    private fun takeDmesg() {
        termLog("── KERNEL LOG (dmesg, related lines) ──")
        termLog("Reading kernel messages…")
        Thread {
            val (out, err) = runPrivilegedCapture("dmesg")
            runOnUiThread {
                if (out.isBlank()) {
                    termLog(
                        "No kernel log output" + (err.takeIf { it.isNotBlank() }?.let { ": $it" }
                            ?: " (dmesg is blocked or the read hung on this device - common on newer Android builds).")
                    )
                    return@runOnUiThread
                }
                val relevant = out.lineSequence()
                    .filter { line -> LOG_KEYWORDS.any { line.contains(it, ignoreCase = true) } }
                    .toList()
                    .takeLast(60)
                if (relevant.isEmpty()) {
                    termLog("No related lines found in the kernel log.")
                } else {
                    relevant.forEach { termLog(it) }
                }
                termLog("── In simple words ──")
                explainLog(relevant).forEach { termLog("• $it") }
            }
        }.start()
    }

    /** Turns raw log lines into a short plain-English explanation by matching known
     *  patterns relevant to disabling/uninstalling packages. Not exhaustive - just
     *  the common causes for why an action might fail or a package might reappear. */
    private fun explainLog(lines: List<String>): List<String> {
        if (lines.isEmpty()) {
            return listOf("Nothing relevant showed up. If an action still failed, it's most likely being reported directly by the OK/FAIL line above rather than logged elsewhere.")
        }
        val text = lines.joinToString("\n").lowercase()
        val notes = mutableListOf<String>()
        if (text.contains("avc:") && text.contains("denied"))
            notes += "Android's built-in security system (SELinux) blocked one of the actions. This is why some system apps resist being disabled or uninstalled even with root."
        if (text.contains("permission") && text.contains("denied"))
            notes += "A permission was denied. Some packages are protected by the OS and refuse changes even from a privileged shell."
        if (text.contains("securityexception"))
            notes += "The system threw a security exception - this app or package is likely protected by device policy or a system-level restriction."
        if (text.contains("unknown package") || text.contains("not found"))
            notes += "The package name wasn't recognized by the system - it may already be removed, disabled for a different user, or the name was mistyped."
        if (text.contains("deletefailed") || text.contains("delete_failed"))
            notes += "The system reported the uninstall failed - this usually means the app is a protected system component."
        if (text.contains("reinstall") || text.contains("restore"))
            notes += "Something reinstalled or restored a package - some OEM system apps have a watchdog that reinstalls them after removal."
        if (notes.isEmpty())
            notes += "No known problem pattern matched these lines. If something still isn't working as expected, the OK/FAIL result above is the most direct signal."
        return notes
    }


    private fun termLog(message: String) {
        runOnUiThread {
            if (!::terminalOutput.isInitialized) return@runOnUiThread
            if (logBuffer.isNotEmpty()) logBuffer.append('\n')
            logBuffer.append(message.trimEnd())
            if (logBuffer.length > 12000) logBuffer.delete(0, logBuffer.length - 10000)
            terminalOutput.text = logBuffer.toString()
            terminalScroll.post { terminalScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }
}