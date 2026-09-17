package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Telephony
import android.Manifest
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupRestoreActivity : BaseActivity() {

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
        private const val REQUEST_CALL_LOG = 1002
        private const val REQUEST_SMS = 1003
        private const val REQUEST_STORAGE = 1004
        private const val REQUEST_RESTORE_BACKUP = 1005
    }

    private val bgColor get() = ThemeManager.current(this).background
    private val cardColor get() = ThemeManager.current(this).surface
    private val borderColor get() = ThemeManager.current(this).let { ThemeManager.borderColor(it) }
    private val primaryColor get() = ThemeManager.current(this).accent
    private val textColor get() = ThemeManager.current(this).text
    private val secondaryColor get() = ThemeManager.current(this).secondary
    private val dangerColor get() = ThemeManager.current(this).error

    private lateinit var rootLayout: LinearLayout
    private lateinit var backupContainer: LinearLayout
    private lateinit var rootStatus: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var backupButton: Button
    private lateinit var restoreButton: Button
    private lateinit var appDataButton: Button

    // Live terminal / log view so restore failures show full output
    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var logUpButton: Button
    private lateinit var logDownButton: Button
    private lateinit var logClearButton: Button
    private lateinit var terminalProgressBar: ProgressBar
    private lateinit var terminalProgressText: TextView
    private val logBuffer = StringBuilder()

    private val automaticBackupRoot: File
        get() = File(
            Environment.getExternalStorageDirectory(),
            "Root Realm Backups"
        )

    private val automaticPartitionDirectory: File
        get() = File(automaticBackupRoot, "Partitions")

    private val automaticAppDataDirectory: File
        get() = File(automaticBackupRoot, "APK + Data")

    private val automaticMediaDirectory: File
        get() = File(automaticBackupRoot, "Media")

    private val automaticCallLogDirectory: File
        get() = File(automaticBackupRoot, "Call Log")

    private val automaticSmsDirectory: File
        get() = File(automaticBackupRoot, "SMS")

    private val automaticWifiDirectory: File
        get() = File(automaticBackupRoot, "Wi-Fi")

    private val appDataBackupDirectory: File
        get() = File(filesDir, "app_data_backups")

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private data class Partition(
        val name: String,
        val path: String,
        val size: Long
    )

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    )

    private val partitions = mutableListOf<Partition>()
    private val selectedPartitions = mutableSetOf<String>()

    private lateinit var backupDirectory: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        // Partition backups live directly in shared internal storage.
        // Do not use Root Realm's private filesDir for partition images.
        backupDirectory = automaticPartitionDirectory

        if (!backupDirectory.exists()) {
            backupDirectory.mkdirs()
        }

        if (!appDataBackupDirectory.exists()) {
            appDataBackupDirectory.mkdirs()
        }

        buildUi()
        requestNotificationPermissionIfNeeded()
        checkRootAndLoad()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
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
            "Backup & Restore",
            28f,
            textColor
        )

        content.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val subtitle = createText(
            "Partition image backup and restore",
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
                bottomMargin = dp(14)
            }
        )

        content.addView(
            createSectionHeader("PARTITIONS"),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val partitionCard = createCard()

        val partitionInfo = createText(
            "Select the partitions you want to back up or restore.",
            13f,
            secondaryColor
        )

        partitionCard.addView(
            partitionInfo,
            LinearLayout.LayoutParams(-1, -2)
        )

        val partitionButton = createButton("SELECT PARTITIONS")

        partitionCard.addView(
            partitionButton,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                topMargin = dp(12)
            }
        )

        content.addView(
            partitionCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(14)
            }
        )

        partitionButton.setOnClickListener {
            showPartitionPicker()
        }

        val appDataCard = createCard()

        val appDataInfo = createText(
            "Backup user apps with search. System apps are excluded. All split APKs included. Root preferred for data; APK-only works without it.",
            13f,
            secondaryColor
        )

        appDataCard.addView(
            appDataInfo,
            LinearLayout.LayoutParams(-1, -2)
        )

        appDataButton = createButton("APK BACKUP")
        appDataCard.addView(
            appDataButton,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                topMargin = dp(12)
            }
        )

        content.addView(
            appDataCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(14)
            }
        )

        appDataButton.setOnClickListener {
            showAppBackupPicker()
        }

        // MEDIA
        val mediaCard = createCard()
        mediaCard.addView(
            createText(
                "Photos, videos, music and documents from internal storage. Select what you want to include.",
                13f,
                secondaryColor
            ),
            LinearLayout.LayoutParams(-1, -2)
        )
        val mediaButton = createButton("SELECT MEDIA")
        mediaCard.addView(mediaButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        content.addView(mediaCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        mediaButton.setOnClickListener { requestMediaPermission() }

        // CALL LOG
        val callLogCard = createCard()
        callLogCard.addView(
            createText(
                "Back up your call history, including incoming, outgoing and missed calls.",
                13f,
                secondaryColor
            ),
            LinearLayout.LayoutParams(-1, -2)
        )
        val callLogButton = createButton("BACK UP CALL LOG")
        callLogCard.addView(callLogButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        content.addView(callLogCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        callLogButton.setOnClickListener { requestCallLogPermission() }

        // SMS
        val smsCard = createCard()
        smsCard.addView(
            createText(
                "Back up SMS messages stored on this device. Android may require SMS permission or the default SMS role.",
                13f,
                secondaryColor
            ),
            LinearLayout.LayoutParams(-1, -2)
        )
        val smsButton = createButton("BACK UP SMS")
        smsCard.addView(smsButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        content.addView(smsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        smsButton.setOnClickListener { requestSmsPermission() }

        // WI-FI
        val wifiCard = createCard()
        wifiCard.addView(
            createText(
                "Back up saved Wi-Fi configuration data. Root access is required. " +
                        "On newer Android versions, Wi-Fi credentials may be encrypted by the system.",
                13f,
                secondaryColor
            ),
            LinearLayout.LayoutParams(-1, -2)
        )
        val wifiButton = createButton("BACK UP WI-FI DATA")
        wifiCard.addView(
            wifiButton,
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) }
        )
        content.addView(
            wifiCard,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        )
        wifiButton.setOnClickListener { showWifiBackupDialog() }

        val permissionNoteCard = createCard()
        permissionNoteCard.addView(
            createText(
                "PERMISSIONS",
                12f,
                primaryColor
            ).apply {
                letterSpacing = 0.12f
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        permissionNoteCard.addView(
            createText(
                "Notifications show backup progress and completion. " +
                        "Call Log and SMS permissions are used only for their backups. " +
                        "Storage / Media permissions allow access to the files you select. " +
                        "Internet access is used to download modules and required files.",
                13f,
                secondaryColor
            ),
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
            }
        )
        content.addView(
            permissionNoteCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(14)
            }
        )

        content.addView(
            createSectionHeader("BACKUP"),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val backupCard = createCard()

        val backupInfo = createText(
            "Create raw .img files from selected partitions. " +
                    "Root access is required.",
            13f,
            secondaryColor
        )

        backupCard.addView(
            backupInfo,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        backupButton = createButton(
            "BACK UP SELECTED"
        )

        backupCard.addView(
            backupButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                topMargin = dp(14)
            }
        )

        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        )

        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE

        backupCard.addView(
            progressBar,
            LinearLayout.LayoutParams(
                -1,
                dp(8)
            ).apply {
                topMargin = dp(12)
            }
        )

        progressText = createText(
            "",
            12f,
            secondaryColor
        )

        progressText.visibility = View.GONE

        backupCard.addView(
            progressText,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(6)
            }
        )

        content.addView(
            backupCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        backupButton.setOnClickListener {
            startBackup()
        }

        content.addView(
            createSectionHeader("BACKUPS"),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        backupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(
            backupContainer,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        restoreButton = createButton(
            "SELECT RESTORE BACKUP"
        )

        restoreButton.isEnabled = true

        content.addView(
            restoreButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        restoreButton.setOnClickListener {
            // Automatically detect backups in Internal storage/Root Realm Backups first.
            // Manual file selection is only the fallback for backups stored elsewhere.
            detectRestoreBackups()
        }

        // ========== LIVE TERMINAL / LOG ==========
        content.addView(
            createSectionHeader("TERMINAL / LOG"),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        )

        val logCard = createCard()

        logText = TextView(this).apply {
            text = "Ready. Restore output will appear here."
            setTextColor(Color.parseColor("#A8FF60")) // terminal green
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
        }

        logScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(logText, ViewGroup.LayoutParams(-1, -2))
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        logCard.addView(
            logScroll,
            LinearLayout.LayoutParams(-1, dp(180))
        )

        // Progress bar inside the terminal card (backup / restore)
        terminalProgressText = createText("", 12f, secondaryColor).apply {
            visibility = View.GONE
        }
        logCard.addView(
            terminalProgressText,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        )

        terminalProgressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        logCard.addView(
            terminalProgressBar,
            LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(4) }
        )

        val logButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        logUpButton = createButton("▲ UP")
        logDownButton = createButton("▼ DOWN")
        logClearButton = createButton("CLEAR")

        logButtons.addView(logUpButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            rightMargin = dp(6)
        })
        logButtons.addView(logDownButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            rightMargin = dp(6)
        })
        logButtons.addView(logClearButton, LinearLayout.LayoutParams(0, dp(44), 1f))

        logCard.addView(logButtons, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })

        content.addView(logCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(14)
        })

        logUpButton.setOnClickListener {
            logScroll.smoothScrollBy(0, -dp(120))
        }
        logDownButton.setOnClickListener {
            logScroll.smoothScrollBy(0, dp(120))
        }
        logClearButton.setOnClickListener {
            logBuffer.clear()
            logText.text = "Log cleared."
        }
        // ========== END TERMINAL ==========

        val warningCard = createCard()

        val warningTitle = createText(
            "⚠  IMPORTANT",
            15f,
            dangerColor
        )

        warningCard.addView(
            warningTitle,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val warningText = createText(
            "Raw partition restore is dangerous. " +
                    "Restoring the wrong image can cause boot failure, " +
                    "data loss, or a bricked device. " +
                    "Only restore images made for this exact device.",
            13f,
            secondaryColor
        )

        warningCard.addView(
            warningText,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(8)
            }
        )

        content.addView(
            warningCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        scrollView.addView(content)

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

    private fun checkRootAndLoad() {

        rootStatus.text = "Checking root access…"

        executor.execute {

            val result = runRootWithExitCode(
                "id"
            )

            val hasRoot =
                result.exitCode == 0 &&
                        result.output.contains("uid=0")

            handler.post {

                if (hasRoot) {

                    rootStatus.text =
                        "● ROOT ACCESS AVAILABLE"

                    rootStatus.setTextColor(
                        primaryColor
                    )

                    discoverAndRender()

                } else {

                    rootStatus.text =
                        "● ROOT ACCESS NOT AVAILABLE"

                    rootStatus.setTextColor(
                        dangerColor
                    )

                    toast("Root access is required to read partitions.")
                    loadBackups()
                }
            }
        }
    }

    private fun discoverAndRender() {

        executor.execute {

            val found = discoverPartitions()

            handler.post {

                partitions.clear()
                partitions.addAll(found)

                updateBackupButton()
                loadBackups()
            }
        }
    }

    private fun discoverPartitions(): List<Partition> {

        val dollar = '$'

        val command =
            "for f in /dev/block/by-name/*; do " +
                    "[ -e \"${dollar}f\" ] || continue; " +
                    "name=${'$'}(basename \"${dollar}f\"); " +
                    "target=${dollar}(readlink -f \"${dollar}f\"); " +
                    "size=${dollar}(blockdev --getsize64 \"${dollar}f\" 2>/dev/null); " +
                    "echo \"${dollar}name|${dollar}target|${dollar}size\"; " +
                    "done"

        val result =
            runRootWithExitCode(command)

        if (result.exitCode != 0) {
            return emptyList()
        }

        val found =
            mutableListOf<Partition>()

        result.output
            .lineSequence()
            .map {
                it.trim()
            }
            .filter {
                it.isNotEmpty()
            }
            .forEach { line ->

                val pieces =
                    line.split("|")

                if (pieces.size < 3) {
                    return@forEach
                }

                val name =
                    pieces[0].trim()

                val path =
                    pieces[1].trim()

                val size =
                    pieces[2]
                        .trim()
                        .toLongOrNull()
                        ?: 0L

                if (
                    name.isNotEmpty() &&
                    path.isNotEmpty()
                ) {
                    found.add(
                        Partition(
                            name = name,
                            path = path,
                            size = size
                        )
                    )
                }
            }

        return found.sortedBy {
            it.name.lowercase(Locale.US)
        }
    }

    private fun showPartitionPicker() {

        if (partitions.isEmpty()) {
            toast("No partitions found.")
            return
        }

        val names = partitions.map {
            "${it.name}  •  ${formatBytes(it.size)}"
        }.toTypedArray()

        val checked = BooleanArray(partitions.size) { index ->
            selectedPartitions.contains(partitions[index].name)
        }

        AlertDialog.Builder(this)
            .setTitle("Select Partitions")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val partition = partitions[which]

                if (isChecked) {
                    selectedPartitions.add(partition.name)
                } else {
                    selectedPartitions.remove(partition.name)
                }

                updateBackupButton()
            }
            .setPositiveButton("DONE", null)
            .setNeutralButton("DETAILS") { _, _ ->
                showSelectedPartitionDetails()
            }
            .show()
    }

    private fun showSelectedPartitionDetails() {

        if (selectedPartitions.isEmpty()) {
            toast("Select a partition first.")
            return
        }

        val details = partitions
            .filter { selectedPartitions.contains(it.name) }
            .joinToString("\n\n") { partition ->
                "${partition.name}\n" +
                        "Path: ${partition.path}\n" +
                        "Size: ${formatBytes(partition.size)}"
            }

        AlertDialog.Builder(this)
            .setTitle("Partition Details")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateBackupButton() {

        val enabled =
            selectedPartitions.isNotEmpty()

        backupButton.isEnabled = enabled

        if (enabled) {
            backupButton.alpha = 1f
        } else {
            backupButton.alpha = 0.45f
        }
    }

    private fun startBackup() {

        if (selectedPartitions.isEmpty()) {
            toast("Select at least one partition.")
            return
        }

        val selected =
            partitions.filter {
                selectedPartitions.contains(
                    it.name
                )
            }

        val hasLargeOrSensitive =
            selected.any {
                isSensitivePartition(it.name)
            }

        if (hasLargeOrSensitive) {

            AlertDialog.Builder(this)
                .setTitle("Large / sensitive backup")
                .setMessage(
                    "Some selected partitions may be very large " +
                            "or contain personal data. " +
                            "The backup will be saved to the selected backup location.\n\n" +
                            "Continue?"
                )
                .setNegativeButton(
                    "CANCEL",
                    null
                )
                .setPositiveButton(
                    "CONTINUE"
                ) { _, _ ->
                    performBackup(selected)
                }
                .show()

        } else {

            performBackup(selected)
        }
    }

    private fun performBackup(
        selected: List<Partition>
    ) {

        backupButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0

        executor.execute {

            var completed = 0

            selected.forEach { partition ->

                handler.post {

                    progressText.text =
                        "Backing up ${partition.name}…"
                }

                val safeName =
                    partition.name
                        .replace(
                            Regex("[^A-Za-z0-9._-]"),
                            "_"
                        )

                // Always create the partition image in shared internal storage.
                // This prevents it from being written to /data/user/0/.../files/.
                val outputFile = File(
                    automaticPartitionDirectory,
                    "${safeName}.img"
                )

                if (outputFile.exists()) {
                    outputFile.delete()
                }

                val folderResult = runRootWithExitCode(
                    "mkdir -p '${escapeShell(automaticPartitionDirectory.absolutePath)}'"
                )
                if (folderResult.exitCode != 0) {
                    handler.post {
                        toast("Cannot create Internal storage/Root Realm Backups/Partitions.")
                    }
                    return@forEach
                }

                val command =
                    "mkdir -p '${escapeShell(automaticPartitionDirectory.absolutePath)}' && " +
                            "rm -f '${escapeShell(outputFile.absolutePath)}' && " +
                            "dd if='${escapeShell(partition.path)}' " +
                            "of='${escapeShell(outputFile.absolutePath)}' " +
                            "bs=4M status=none && " +
                            "test -s '${escapeShell(outputFile.absolutePath)}' && " +
                            "stat -c %s '${escapeShell(outputFile.absolutePath)}'"

                var result = runRootWithExitCode(command)
                if (result.exitCode != 0) {
                    result = runRootMountMaster(command)
                }

                val rootSize = result.output.trim().lines().lastOrNull()
                    ?.trim()?.toLongOrNull() ?: 0L

                if (result.exitCode != 0 || rootSize <= 0L) {
                    handler.post {
                        val detail = result.output.trim().takeLast(300)
                        toast("Partition backup failed: ${partition.name}" +
                                if (detail.isNotEmpty()) "\n$detail" else "")
                    }
                    return@forEach
                }

                if (false) {
                    // Keep the guaranteed internal-storage copy even if a third-party
                    // SAF provider rejects the selected folder.
                    saveFileToBackupLocation(outputFile, outputFile.name)
                }

                completed++

                val progress =
                    (
                        completed.toFloat() /
                                selected.size.toFloat()
                        ) * 100f

                handler.post {

                    progressBar.progress =
                        progress.toInt()

                    progressText.text =
                        "Completed $completed / ${selected.size}"
                }
            }

            handler.post {

                backupButton.isEnabled = true
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE

                loadBackups()

                if (completed == selected.size) {

                    toast(
                        "Backup completed successfully."
                    )

                } else {

                    toast(
                        "Backup finished with errors."
                    )
                }

                updateBackupButton()
            }
        }
    }

    private fun loadBackups() {

        backupContainer.removeAllViews()

        if (!backupDirectory.exists()) {
            showEmptyState(
                backupContainer,
                "No backups created yet."
            )
            return
        }

        val files =
            backupDirectory
                .listFiles()
                ?.filter {
                    it.isFile &&
                            it.extension.equals("img", ignoreCase = true)
                }
                ?.sortedBy { it.name.lowercase(Locale.US) }
                ?: emptyList()

        if (files.isNotEmpty()) {
            files.forEach {
                addBackupRow(it)
            }
        }

        val appFiles = linkedMapOf<String, File>()

        appDataBackupDirectory.listFiles()
            ?.filter {
                it.isFile && (
                    it.name.endsWith(".tar.gz", true) ||
                    it.name.endsWith(".apk", true)
                )
            }
            ?.forEach { appFiles[it.name] = it }

        automaticAppDataDirectory.listFiles()
            ?.filter {
                it.isFile && (
                    it.name.endsWith(".tar.gz", true) ||
                    it.name.endsWith(".apk", true)
                )
            }
            ?.forEach { appFiles[it.name] = it }

        appFiles.values
            .sortedBy { it.name.lowercase(Locale.US) }
            .forEach { addAppBackupRow(it) }

        if (files.isEmpty() && appFiles.isEmpty()) {
            showEmptyState(
                backupContainer,
                "No backups created yet."
            )
        }
    }

    private fun addBackupRow(
        file: File
    ) {

        val card = createCard()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val name =
            file.nameWithoutExtension

        val title = createText(
            name,
            16f,
            textColor
        )

        row.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val details = createText(
            "${formatBytes(file.length())}  •  ${file.absolutePath}",
            11f,
            secondaryColor
        )

        row.addView(
            details,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(5)
            }
        )

        val restore =
            createButton("RESTORE")

        row.addView(
            restore,
            LinearLayout.LayoutParams(
                -1,
                dp(46)
            ).apply {
                topMargin = dp(10)
            }
        )

        restore.setOnClickListener {
            confirmRestore(file)
        }

        card.addView(
            row,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        backupContainer.addView(
            card,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(10)
            }
        )
    }

    private fun confirmRestore(
        file: File
    ) {

        val partitionName =
            file.nameWithoutExtension

        if (
            isDangerousRestorePartition(
                partitionName
            )
        ) {

            AlertDialog.Builder(this)
                .setTitle("Restore blocked")
                .setMessage(
                    "Restoring '$partitionName' directly is disabled " +
                            "because it can easily cause an unrecoverable boot " +
                            "or data problem."
                )
                .setPositiveButton(
                    "OK",
                    null
                )
                .show()

            return
        }

        val partition =
            partitions.firstOrNull {
                it.name.equals(
                    partitionName,
                    ignoreCase = true
                )
            }

        if (partition == null) {

            AlertDialog.Builder(this)
                .setTitle("Partition unavailable")
                .setMessage(
                    "The target partition '$partitionName' " +
                            "is not currently available on this device."
                )
                .setPositiveButton(
                    "OK",
                    null
                )
                .show()

            return
        }

        if (file.length() <= 0) {

            toast("Backup image is empty.")
            return
        }

        if (
            partition.size > 0 &&
            file.length() > partition.size
        ) {

            toast(
                "Backup image is larger than the target partition."
            )

            return
        }

        AlertDialog.Builder(this)
            .setTitle("⚠ Restore partition?")
            .setMessage(
                "This will overwrite:\n\n" +
                        "${partition.name}\n" +
                        "${partition.path}\n\n" +
                        "Image size: ${formatBytes(file.length())}\n\n" +
                        "This operation can cause data loss or boot failure.\n\n" +
                        "Only continue if this image belongs to this exact device."
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "RESTORE"
            ) { _, _ ->

                performRestore(
                    file,
                    partition
                )
            }
            .show()
    }

    private fun performRestore(
        file: File,
        partition: Partition
    ) {

        restoreButton.isEnabled = false

        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.isIndeterminate = true

        executor.execute {

            handler.post {

                progressText.text =
                    "Restoring ${partition.name}…"
            }

            val command =
                "dd if='${escapeShell(file.absolutePath)}' " +
                        "of='${escapeShell(partition.path)}' " +
                        "bs=4M status=none"

            val result =
                runRootWithExitCode(command)

            handler.post {

                progressBar.isIndeterminate = false
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                restoreButton.isEnabled = true

                if (result.exitCode == 0) {

                    toast(
                        "Restore completed: ${partition.name}"
                    )

                } else {

                    toast(
                        "Restore failed: ${partition.name}"
                    )
                }
            }
        }
    }

    private data class BackupApp(
        val label: String,
        val packageName: String,
        val sourceApks: List<String>,
        val dataDir: String
    )

    private fun showAppBackupPicker() {
        // User apps only — system apps are excluded (rarely useful to back up).
        val allApps = try {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName.isNotBlank() }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.US) }
        } catch (_: Exception) {
            emptyList<ApplicationInfo>()
        }

        if (allApps.isEmpty()) {
            toast("No user apps found.")
            return
        }

        val dialog = android.app.Dialog(this)
        val selected = mutableSetOf<Int>() // indexes into allApps

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(10))
            background = roundedBackground(cardColor, borderColor, 24)
        }

        container.addView(
            createText("APK BACKUP", 20f, textColor).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        container.addView(
            createText("Search and select user apps. System apps are hidden. Split APKs included automatically.", 13f, secondaryColor),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        )

        val searchBox = EditText(this).apply {
            hint = "Search app name or package…"
            setHintTextColor(secondaryColor)
            setTextColor(textColor)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(
                Color.parseColor("#1A1A1A").let { if (cardColor == it) Color.parseColor("#252525") else cardColor },
                borderColor,
                14
            )
        }
        container.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })

        val countText = createText("${allApps.size} apps", 12f, secondaryColor)
        container.addView(countText, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(list)
        container.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(8)
        })

        fun rebuildList(query: String) {
            list.removeAllViews()
            val q = query.trim().lowercase(Locale.US)
            val filtered = allApps.mapIndexed { index, app -> index to app }
                .filter { (_, app) ->
                    if (q.isEmpty()) true
                    else {
                        val label = app.loadLabel(packageManager).toString().lowercase(Locale.US)
                        label.contains(q) || app.packageName.lowercase(Locale.US).contains(q)
                    }
                }

            countText.text = if (q.isEmpty()) {
                "${allApps.size} apps"
            } else {
                "${filtered.size} of ${allApps.size} apps"
            }

            if (filtered.isEmpty()) {
                list.addView(
                    createText("No apps match \"$query\"", 13f, secondaryColor).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(24), dp(8), dp(24))
                    },
                    LinearLayout.LayoutParams(-1, -2)
                )
                return
            }

            filtered.forEach { (index, app) ->
                val label = app.loadLabel(packageManager).toString()
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    background = roundedBackground(cardColor, cardColor, 14)
                }
                val check = CheckBox(this).apply {
                    text = if (app.packageName == packageName) "$label  [this app]" else label
                    setTextColor(textColor)
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isChecked = selected.contains(index)
                    buttonTintList = android.content.res.ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf()
                        ),
                        intArrayOf(primaryColor, secondaryColor)
                    )
                    setPadding(0, 0, 0, 0)
                }
                row.addView(check, LinearLayout.LayoutParams(-1, dp(48)))
                row.addView(
                    createText(app.packageName, 11f, secondaryColor).apply {
                        setPadding(dp(48), 0, dp(8), dp(8))
                        maxLines = 2
                    },
                    LinearLayout.LayoutParams(-1, -2)
                )

                val toggle = View.OnClickListener {
                    check.isChecked = !check.isChecked
                    if (check.isChecked) selected.add(index) else selected.remove(index)
                }
                row.setOnClickListener(toggle)
                check.setOnClickListener {
                    if (check.isChecked) selected.add(index) else selected.remove(index)
                }

                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                })
            }
        }

        rebuildList("")

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                rebuildList(s?.toString() ?: "")
            }
        })

        val actions = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancel = createDialogAction("CANCEL", secondaryColor)
        val done = createDialogAction("CONTINUE", primaryColor)
        actions.addView(cancel, LinearLayout.LayoutParams(-2, dp(48)))
        actions.addView(done, LinearLayout.LayoutParams(-2, dp(48)).apply {
            leftMargin = dp(4)
        })
        container.addView(actions, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(4)
        })

        cancel.setOnClickListener { dialog.dismiss() }
        done.setOnClickListener {
            dialog.dismiss()
            val chosen = selected.sorted().map { index ->
                val app = allApps[index]
                BackupApp(
                    app.loadLabel(packageManager).toString(),
                    app.packageName,
                    listOfNotNull(app.sourceDir).plus(app.splitSourceDirs?.toList() ?: emptyList()),
                    app.dataDir ?: ""
                )
            }
            confirmAppBackup(chosen)
        }

        dialog.setContentView(container)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.72f }
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (resources.displayMetrics.heightPixels * 0.78f).toInt()
        )
    }

    private fun confirmAppBackup(selected: List<BackupApp>) {
        if (selected.isEmpty()) {
            toast("Select at least one app.")
            return
        }

        val list = selected.take(15).joinToString("\n\n") {
            "${it.label}\n${it.packageName}"
        }
        val extra = if (selected.size > 15) "\n\n+ ${selected.size - 15} more app(s)" else ""

        showStyledConfirmDialog(
            title = "Confirm APK backup",
            message = "The following app(s) will be backed up:\n\n$list$extra\n\n" +
                    "• All split APKs are included (via pm path)\n" +
                    "• App data is included when root is available\n" +
                    "• If data fails, APK-only backup is still saved",
            positiveText = "BACK UP",
            negativeText = "CANCEL",
            onPositive = { performAppBackup(selected) }
        )
    }

    private fun performAppBackup(selected: List<BackupApp>) {
        appDataButton.isEnabled = false
        logClear()
        setTerminalProgress(0, "Starting backup…")
        log("=== APK BACKUP START (${selected.size} app(s)) ===")

        executor.execute {
            var ok = 0
            var failed = 0
            val total = selected.size.coerceAtLeast(1)

            selected.forEachIndexed { index, app ->
                val safe = app.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val archive = File(appDataBackupDirectory, "$safe.tar.gz")
                if (archive.exists()) archive.delete()

                val pct = ((index.toFloat() / total) * 100f).toInt()
                setTerminalProgress(pct, "Backing up ${index + 1}/$total: ${app.label}")

                log("")
                log("→ ${app.label} (${app.packageName})")

                val hasRoot = runRootWithExitCode("id").let {
                    it.exitCode == 0 && it.output.contains("uid=0")
                }

                // Collect every APK path with no restrictions
                val pmPathResult = if (hasRoot) {
                    runRootWithExitCode("pm path '${escapeShell(app.packageName)}' 2>/dev/null")
                } else {
                    // Non-root fallback still works for many packages
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf("pm", "path", app.packageName))
                        val out = p.inputStream.bufferedReader().readText()
                        p.waitFor()
                        CommandResult(p.exitValue(), out)
                    } catch (_: Exception) {
                        CommandResult(-1, "")
                    }
                }

                val pmPaths = pmPathResult.output.lineSequence()
                    .map { it.trim().removePrefix("package:").trim() }
                    .filter { it.endsWith(".apk", ignoreCase = true) && it.isNotBlank() }
                    .distinct()
                    .toList()

                val apkPaths = (pmPaths + app.sourceApks)
                    .distinct()
                    .filter { it.isNotBlank() }

                if (apkPaths.isEmpty()) {
                    failed++
                    log("  ERROR: no APK paths found")
                    handler.post { toast("No APK files found for ${app.label}.") }
                    return@forEachIndexed
                }

                log("  APKs (${apkPaths.size}):")
                apkPaths.forEach { log("    • $it") }

                val apkArgs = apkPaths.joinToString(" ") {
                    "-C / '${escapeShell(it.removePrefix("/"))}'"
                }

                // Try full APK + data first when root is available
                var saved = false
                if (hasRoot && app.dataDir.isNotBlank()) {
                    val dataDir = app.dataDir.trimEnd('/')
                    val dataArg = "-C / '${escapeShell(dataDir.removePrefix("/"))}'"
                    val fullCommand =
                        "rm -f '${escapeShell(archive.absolutePath)}' && " +
                        "tar -czpf '${escapeShell(archive.absolutePath)}' $apkArgs $dataArg 2>&1 && " +
                        "test -s '${escapeShell(archive.absolutePath)}'"

                    var result = runRootWithExitCode(fullCommand)
                    if (result.exitCode != 0) {
                        val master = runRootMountMaster(fullCommand)
                        if (master.exitCode == 0) result = master
                    }

                    if (result.exitCode == 0 && archive.exists() && archive.length() > 0) {
                        log("  APK + data archive OK (${formatBytes(archive.length())})")
                        saved = saveFileToBackupLocation(archive, archive.name)
                        if (saved) {
                            archive.delete()
                            ok++
                            log("  Saved to Root Realm Backups")
                        } else {
                            log("  ERROR: could not copy archive to shared storage")
                        }
                    } else {
                        log("  Data backup failed, falling back to APK-only…")
                        if (archive.exists()) archive.delete()
                    }
                }

                // APK-only fallback (works with or without root / data)
                if (!saved) {
                    val apkOnlyCommand =
                        "rm -f '${escapeShell(archive.absolutePath)}' && " +
                        "tar -czpf '${escapeShell(archive.absolutePath)}' $apkArgs 2>&1 && " +
                        "test -s '${escapeShell(archive.absolutePath)}'"

                    var result = if (hasRoot) {
                        runRootWithExitCode(apkOnlyCommand)
                    } else {
                        // Best-effort without root (may fail for protected paths)
                        try {
                            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", apkOnlyCommand))
                            val out = p.inputStream.bufferedReader().readText() +
                                    p.errorStream.bufferedReader().readText()
                            CommandResult(p.waitFor(), out)
                        } catch (e: Exception) {
                            CommandResult(-1, e.message ?: "")
                        }
                    }
                    if (hasRoot && result.exitCode != 0) {
                        val master = runRootMountMaster(apkOnlyCommand)
                        if (master.exitCode == 0) result = master
                    }

                    if (result.exitCode == 0 && archive.exists() && archive.length() > 0) {
                        log("  APK-only archive OK (${formatBytes(archive.length())})")
                        saved = saveFileToBackupLocation(archive, archive.name)
                        if (saved) {
                            archive.delete()
                            ok++
                            log("  Saved to Root Realm Backups (APK only)")
                        } else {
                            log("  ERROR: could not copy archive to shared storage")
                            failed++
                        }
                    } else {
                        failed++
                        if (archive.exists()) archive.delete()
                        log("  ERROR: backup failed")
                        if (result.output.isNotBlank()) log(result.output.trim().takeLast(400))
                        handler.post {
                            toast("Backup failed: ${app.label}")
                        }
                    }
                }
            }

            log("")
            log("=== BACKUP FINISHED: $ok ok, $failed failed ===")
            setTerminalProgress(100, "Backup finished: $ok ok, $failed failed")

            handler.post {
                appDataButton.isEnabled = true
                loadBackups()
                when {
                    ok == selected.size -> toast("Backup completed ($ok app(s)).")
                    ok > 0 -> toast("Backup completed for $ok / ${selected.size} app(s).")
                    else -> toast("Backup failed.")
                }
                // Hide progress after a short delay
                handler.postDelayed({ hideTerminalProgress() }, 2500)
            }
        }
    }

    private fun addAppBackupRow(file: File) {
        val card = createCard()
        val title = createText(file.nameWithoutExtension, 16f, textColor)
        card.addView(title, LinearLayout.LayoutParams(-1, -2))
        card.addView(
            createText("${formatBytes(file.length())}  •  ${file.absolutePath}", 11f, secondaryColor),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) }
        )
        val restore = createButton("RESTORE")
        card.addView(restore, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        restore.setOnClickListener {
            showStyledConfirmDialog(
                title = "Restore APK + Data?",
                message = "This may replace application data. Only restore a backup made for this device/app.",
                positiveText = "RESTORE",
                negativeText = "CANCEL",
                onPositive = { restoreAppBackup(file) }
            )
        }
        backupContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun restoreAppBackup(file: File) {
        if (!file.exists() || file.length() <= 0L) {
            toast("Backup file not found.")
            log("ERROR: Backup file not found or empty: ${file.absolutePath}")
            return
        }

        val packageName = file.name.removeSuffix(".tar.gz").removeSuffix(".tar").trim()
        restoreButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "Installing APK and restoring data…"

        logClear()
        setTerminalProgress(0, "Starting restore…")
        log("=== APK + DATA RESTORE START ===")
        log("Package : $packageName")
        log("Archive : ${file.absolutePath}")
        log("Size    : ${formatBytes(file.length())}")
        log("")

        executor.execute {
            val safePackage = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val extractDirPath = "/data/local/tmp/rootrealm_restore_${safePackage}_${System.currentTimeMillis()}"
            val dir = escapeShell(extractDirPath)
            try {
                val archive = escapeShell(file.absolutePath)

                setTerminalProgress(10, "Extracting APKs…")
                log("1) Extracting APKs from archive…")
                val extractApksCommand =
                    "rm -rf '$dir' && mkdir -p '$dir' && " +
                    "apk_list=\$(tar -tzf '$archive' 2>/dev/null | grep -E '\\.apk\$' || true); " +
                    "if [ -z \"\$apk_list\" ]; then echo 'No APK members found in archive'; exit 1; fi; " +
                    "printf '%s\\n' \"\$apk_list\" | while IFS= read -r member; do " +
                    "tar -xzf '$archive' -C '$dir' \"\$member\" 2>/dev/null || true; " +
                    "done; " +
                    "find '$dir' -type f -name '*.apk' | head -1 >/dev/null || { echo 'APK extraction produced no files'; exit 1; }"

                var extractResult = runRootWithExitCode(extractApksCommand)
                log("   extract exit=${extractResult.exitCode}")
                if (extractResult.output.isNotBlank()) log(extractResult.output.trim().takeLast(800))

                if (extractResult.exitCode != 0) {
                    log("   trying mount-master…")
                    extractResult = runRootMountMaster(extractApksCommand)
                    log("   mount-master exit=${extractResult.exitCode}")
                    if (extractResult.output.isNotBlank()) log(extractResult.output.trim().takeLast(800))
                }
                if (extractResult.exitCode != 0) {
                    log("   full-archive fallback…")
                    val fullExtract =
                        "rm -rf '$dir' && mkdir -p '$dir' && " +
                        "tar -xzf '$archive' -C '$dir' 2>&1 && " +
                        "find '$dir' -type f -name '*.apk' | head -1 >/dev/null"
                    extractResult = runRootWithExitCode(fullExtract)
                    if (extractResult.exitCode != 0) {
                        extractResult = runRootMountMaster(fullExtract)
                    }
                    log("   full extract exit=${extractResult.exitCode}")
                    if (extractResult.output.isNotBlank()) log(extractResult.output.trim().takeLast(800))
                }
                if (extractResult.exitCode != 0) {
                    throw IllegalStateException("Could not extract APKs")
                }
                log("   APK extraction OK")
                log("")

                setTerminalProgress(40, "Installing APK(s)…")
                log("2) Installing APK(s) with Package Manager…")
                val dollar = "$"
                val installCommand = buildString {
                    append("set -e; ")
                    append("cd '$dir' || exit 1; ")
                    append("rm -f __rootrealm_apk_*.apk; ")
                    append("i=0; ")
                    append("apk_args=''; ")
                    append("for apk in ${dollar}(find . -type f -name '*.apk' | sort); do ")
                    append("i=${dollar}((i+1)); ")
                    append("cp -f \"${dollar}apk\" __rootrealm_apk_${dollar}i.apk; ")
                    append("chmod 644 __rootrealm_apk_${dollar}i.apk; ")
                    append("apk_args=\"${dollar}apk_args __rootrealm_apk_${dollar}i.apk\"; ")
                    append("done; ")
                    append("if [ \"${dollar}i\" -lt 1 ]; then echo 'No APK files found after extraction'; ls -la; exit 1; fi; ")
                    append("echo \"Found ${dollar}i APK file(s):\"; ")
                    append("ls -l __rootrealm_apk_*.apk; ")
                    append("echo '--- installing ---'; ")
                    append("if pm install-multiple -r -d -t --user 0 ${dollar}apk_args 2>&1; then exit 0; fi; ")
                    append("echo 'install-multiple failed, trying session API...'; ")
                    append("SESSION=${dollar}(pm install-create -r -d -t --user 0 2>/dev/null | grep -oE '[0-9]+' | head -1); ")
                    append("if [ -z \"${dollar}SESSION\" ]; then echo 'Could not create install session'; exit 1; fi; ")
                    append("idx=0; ")
                    append("for f in ${dollar}apk_args; do ")
                    append("size=${dollar}(stat -c %s \"${dollar}f\" 2>/dev/null || echo 0); ")
                    append("pm install-write -S ${dollar}size ${dollar}SESSION ${dollar}idx \"${dollar}f\" 2>&1 || true; ")
                    append("idx=${dollar}((idx+1)); ")
                    append("done; ")
                    append("pm install-commit ${dollar}SESSION 2>&1")
                }

                var installResult = runRootWithExitCode(installCommand)
                log("   install exit=${installResult.exitCode}")
                log("--- pm output start ---")
                log(installResult.output.trim().ifEmpty { "(empty)" })
                log("--- pm output end ---")

                if (installResult.exitCode != 0) {
                    log("   retrying with mount-master…")
                    installResult = runRootMountMaster(installCommand)
                    log("   mount-master exit=${installResult.exitCode}")
                    log(installResult.output.trim().ifEmpty { "(empty)" })
                }
                if (installResult.exitCode != 0 || !installResult.output.contains("Success", ignoreCase = true)) {
                    val out = installResult.output
                    if (out.contains("INSTALL_FAILED_MISSING_SPLIT", ignoreCase = true) ||
                        out.contains("Missing split", ignoreCase = true)
                    ) {
                        throw IllegalStateException(
                            "MISSING SPLIT APKs\n" +
                            "This backup only contains the base APK.\n" +
                            "The app requires additional split APKs that were not saved.\n" +
                            "→ Delete this backup and create a NEW one with the updated app.\n" +
                            "(The new backup uses 'pm path' to capture all splits.)"
                        )
                    }
                    throw IllegalStateException("APK installation failed (see log above)")
                }
                log("   APK install OK")
                log("")

                setTerminalProgress(70, "Restoring app data…")
                log("3) Restoring app data…")
                val dataDir = "/data/user/0/$packageName"
                val dataRelative = dataDir.removePrefix("/")
                val restoreDataCommand =
                    "mkdir -p '${escapeShell(dataDir)}' && " +
                    "tar -xzpf '$archive' -C / '${escapeShell(dataRelative)}' 2>&1"

                var dataResult = runRootWithExitCode(restoreDataCommand)
                if (dataResult.exitCode != 0) dataResult = runRootMountMaster(restoreDataCommand)
                log("   data restore exit=${dataResult.exitCode}")
                if (dataResult.output.isNotBlank()) log(dataResult.output.trim().takeLast(600))
                if (dataResult.exitCode != 0) {
                    throw IllegalStateException("Data restore failed (see log above)")
                }
                log("   Data restore OK")
                log("")

                setTerminalProgress(90, "Fixing ownership / SELinux…")
                log("4) Fixing ownership / SELinux…")
                val fixCommand =
                    "uid=\$(dumpsys package '$packageName' 2>/dev/null | grep -m1 -oE 'userId=[0-9]+' | cut -d= -f2); " +
                    "if [ -n \"\$uid\" ]; then chown -R \"\$uid:\$uid\" '$dataDir'; echo \"chown uid=\$uid\"; fi; " +
                    "restorecon -RF '$dataDir' >/dev/null 2>&1 || true"
                val fixResult = runRootWithExitCode(fixCommand)
                log(fixResult.output.trim().ifEmpty { "ownership fixed" })

                runRootWithExitCode("rm -rf '$dir'")
                log("")
                log("=== RESTORE COMPLETED SUCCESSFULLY ===")
                setTerminalProgress(100, "Restore completed")

                handler.post {
                    file.delete()
                    restoreButton.isEnabled = true
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    loadBackups()
                    toast("APK installed and data restored: $packageName")
                    handler.postDelayed({ hideTerminalProgress() }, 2500)
                }
            } catch (e: Exception) {
                runRootWithExitCode("rm -rf '$dir'")
                log("")
                log("=== RESTORE FAILED ===")
                log("Error: ${e.message ?: "unknown"}")
                setTerminalProgress(100, "Restore failed")
                handler.post {
                    restoreButton.isEnabled = true
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    toast("Restore failed — see TERMINAL / LOG below")
                    handler.postDelayed({ hideTerminalProgress() }, 4000)
                }
            }
        }
    }

    private fun showWifiBackupDialog() {
        showStyledConfirmDialog(
            title = "Back up Wi-Fi data?",
            message = "This will copy the device's Wi-Fi configuration files, including saved network data where Android makes it available, to:\n\n" +
                    "${automaticWifiDirectory.absolutePath}\n\n" +
                    "Root access is required. On newer Android versions, saved passwords may be encrypted or protected by Android and may not be recoverable as plaintext.",
            positiveText = "BACK UP",
            negativeText = "CANCEL",
            onPositive = { performWifiBackup() }
        )
    }

    private fun performWifiBackup() {
        val directory = automaticWifiDirectory
        if (!directory.exists()) directory.mkdirs()

        logClear()
        setTerminalProgress(0, "Backing up Wi-Fi data…")
        log("=== WI-FI BACKUP START ===")

        executor.execute {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(java.util.Date())
            val archive = File(directory, "wifi_backup_$stamp.tar.gz")

            val safeArchive = escapeShell(archive.absolutePath)
            val command = "mkdir -p '${escapeShell(directory.absolutePath)}' && " +
                    "rm -f '$safeArchive' && " +
                    "tar -czf '$safeArchive' " +
                    "/data/misc/wifi " +
                    "/data/misc/apexdata/com.android.wifi 2>&1"

            val result = runRootWithExitCode(command)
            val success = result.exitCode == 0 && archive.exists() && archive.length() > 0L

            if (result.output.isNotBlank()) log(result.output.trim())

            handler.post {
                if (success) {
                    setTerminalProgress(100, "Wi-Fi backup complete")
                    log("Wi-Fi backup saved: ${archive.absolutePath}")
                    Toast.makeText(
                        this,
                        "Wi-Fi backup saved",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    setTerminalProgress(0, "Wi-Fi backup failed")
                    log("Wi-Fi backup failed. Root access and readable Wi-Fi configuration files are required.")
                    if (archive.exists() && archive.length() == 0L) archive.delete()
                    Toast.makeText(
                        this,
                        "Wi-Fi backup failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showStyledConfirmDialog(
        title: String,
        message: String,
        positiveText: String,
        negativeText: String,
        onPositive: () -> Unit
    ) {
        val dialog = android.app.Dialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(14))
            background = roundedBackground(cardColor, borderColor, 24)
        }

        val titleView = createText(title, 20f, textColor).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        val messageView = createText(message, 14f, secondaryColor).apply {
            setLineSpacing(0f, 1.12f)
        }
        container.addView(messageView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })

        val actions = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val cancel = createDialogAction(negativeText, secondaryColor)
        val positive = createDialogAction(positiveText, primaryColor)

        actions.addView(cancel, LinearLayout.LayoutParams(-2, dp(48)))
        actions.addView(positive, LinearLayout.LayoutParams(-2, dp(48)).apply {
            leftMargin = dp(4)
        })
        container.addView(actions, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(8)
        })

        cancel.setOnClickListener { dialog.dismiss() }
        positive.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }

        dialog.setContentView(container)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.72f }
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showStyledMultiChoiceDialog(
        title: String,
        subtitle: String,
        items: List<Pair<String, String>>,
        positiveText: String,
        negativeText: String,
        onSelectionChanged: ((List<Int>) -> List<Int>)? = null,
        onDone: (List<Int>) -> Unit
    ) {
        val dialog = android.app.Dialog(this)
        val selected = mutableSetOf<Int>()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(10))
            background = roundedBackground(cardColor, borderColor, 24)
        }

        val titleView = createText(title, 20f, textColor).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        container.addView(
            createText(subtitle, 13f, secondaryColor).apply {
                setLineSpacing(0f, 1.1f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(4), dp(2), dp(4))
                background = roundedBackground(cardColor, cardColor, 14)
            }

            val check = CheckBox(this).apply {
                text = item.first
                setTextColor(textColor)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(primaryColor, secondaryColor)
                )
                setPadding(0, 0, 0, 0)
            }
            row.addView(check, LinearLayout.LayoutParams(-1, dp(48)))

            val detail = createText(item.second, 11f, secondaryColor).apply {
                setPadding(dp(48), 0, dp(8), dp(8))
                maxLines = 2
            }
            row.addView(detail, LinearLayout.LayoutParams(-1, -2))

            val toggle = View.OnClickListener {
                check.isChecked = !check.isChecked
                if (check.isChecked) selected.add(index) else selected.remove(index)
            }
            row.setOnClickListener(toggle)
            check.setOnClickListener {
                if (check.isChecked) selected.add(index) else selected.remove(index)
            }

            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
            })
        }

        scroll.addView(list)
        container.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(10)
        })

        val actions = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancel = createDialogAction(negativeText, secondaryColor)
        val done = createDialogAction(positiveText, primaryColor)
        actions.addView(cancel, LinearLayout.LayoutParams(-2, dp(48)))
        actions.addView(done, LinearLayout.LayoutParams(-2, dp(48)).apply {
            leftMargin = dp(4)
        })
        container.addView(actions, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(4)
        })

        cancel.setOnClickListener { dialog.dismiss() }
        done.setOnClickListener {
            var result = selected.toList().sorted()
            result = onSelectionChanged?.invoke(result) ?: result
            dialog.dismiss()
            onDone(result)
        }

        dialog.setContentView(container)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.72f }
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (resources.displayMetrics.heightPixels * 0.78f).toInt()
        )
    }

    private fun createDialogAction(text: String, color: Int): TextView {
        return createText(text, 13f, color).apply {
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            isClickable = true
            isFocusable = true
            setPadding(dp(12), 0, dp(12), 0)
            setOnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(70).start()
                }
                false
            }
        }
    }

    private fun runShizukuCommand(command: String): CommandResult {
        return try {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            val newProcess = shizuku.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            val process = newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val output = process.inputStream.bufferedReader().readText() + process.errorStream.bufferedReader().readText()
            CommandResult(process.waitFor(), output)
        } catch (e: Exception) {
            CommandResult(127, "Shizuku unavailable: ${e.message}")
        }
    }

    private fun showMediaBackupPicker() {
        val options = listOf(
            Pair("Photos", "DCIM"),
            Pair("Videos", "Movies"),
            Pair("Music", "Music"),
            Pair("Documents", "Documents"),
            Pair("Entire Internal Storage", "Everything on shared internal storage")
        )

        showStyledMultiChoiceDialog(
            title = "MEDIA BACKUP",
            subtitle = "Choose what you want to package for transfer.",
            items = options,
            positiveText = "CONTINUE",
            negativeText = "CANCEL",
            onSelectionChanged = { selected ->
                // Selecting the full-storage option means all categories are included.
                if (selected.contains(4)) {
                    IntArray(options.size) { it }.toList()
                } else {
                    selected
                }
            }
        ) { selectedIndexes ->
            val selected = if (selectedIndexes.contains(4)) {
                listOf("Entire Internal Storage")
            } else {
                selectedIndexes.map { options[it].first }
            }

            if (selected.isEmpty()) {
                toast("Select at least one media category.")
            } else {
                confirmMediaBackup(selected)
            }
        }
    }

    private fun confirmMediaBackup(selected: List<String>) {
        showStyledConfirmDialog(
            title = "Confirm media backup",
            message = "Root Realm will package:\n\n" +
                    selected.joinToString("\n") { "• $it" } +
                    "\n\nPhotos and videos are already compressed, so the archive mainly packages them for easy transfer.",
            positiveText = "BACK UP",
            negativeText = "CANCEL",
            onPositive = { performMediaBackup(selected) }
        )
    }

    private fun performMediaBackup(selected: List<String>) {
        val fileName = "media_${System.currentTimeMillis()}.tar.gz"
        val archive = File(cacheDir, fileName)

        executor.execute {
            try {
                if (archive.exists()) archive.delete()

                val internal = Environment.getExternalStorageDirectory()
                val selectedPaths = mutableListOf<String>()

                if (selected.contains("Entire Internal Storage")) {
                    selectedPaths.add(internal.absolutePath)
                } else {
                    if (selected.contains("Photos")) selectedPaths.add(File(internal, "DCIM").absolutePath)
                    if (selected.contains("Videos")) selectedPaths.add(File(internal, "Movies").absolutePath)
                    if (selected.contains("Music")) selectedPaths.add(File(internal, "Music").absolutePath)
                    if (selected.contains("Documents")) selectedPaths.add(File(internal, "Documents").absolutePath)
                }

                val existing = selectedPaths.filter { File(it).exists() }
                if (existing.isEmpty()) {
                    throw IllegalStateException("The selected media folders are empty or unavailable.")
                }

                // Use root tar instead of the app's File API. This avoids scoped-storage
                // restrictions and also handles large media collections much more reliably.
                val paths = existing.joinToString(" ") { "'${escapeShell(it)}'" }
                val exclude = if (selected.contains("Entire Internal Storage")) {
                    " --exclude='${escapeShell(automaticBackupRoot.absolutePath)}'"
                } else ""

                val command =
                    "tar -czpf '${escapeShell(archive.absolutePath)}'" +
                            exclude + " " + paths + " 2>&1"

                var result = runRootWithExitCode(command)
                if (result.exitCode != 0) {
                    val master = runRootMountMaster(command)
                    if (master.exitCode == 0) result = master
                    else if (master.output.isNotBlank()) result = master
                }

                if (result.exitCode != 0 || !archive.exists() || archive.length() <= 0L) {
                    val detail = result.output.trim().takeLast(500)
                    throw IllegalStateException(
                        "Root media archive failed" + if (detail.isNotEmpty()) ": $detail" else "."
                    )
                }

                if (!saveFileToBackupLocation(archive, archive.name)) {
                    throw IllegalStateException("Could not save the media backup to internal storage.")
                }

                archive.delete()
                handler.post {
                    loadBackups()
                    toast("Media backup completed.")
                }
            } catch (e: Exception) {
                archive.delete()
                handler.post { toast("Media backup failed: ${e.message ?: "unknown error"}") }
            }
        }
    }

    private fun addDirectoryToZip(root: File, base: File, zip: ZipOutputStream) {
        if (root.isFile) {
            addFileToZip(root, base, zip)
            return
        }
        root.walkTopDown().filter { it.isFile && it.canRead() }.forEach { addFileToZip(it, base, zip) }
    }

    private fun addFileToZip(file: File, base: File, zip: ZipOutputStream) {
        val relative = try {
            base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        } catch (_: Exception) {
            file.name
        }
        zip.putNextEntry(ZipEntry(relative))
        file.inputStream().buffered().use { input -> input.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED) return

        showPermissionReasonDialog(
            title = "Notification permission",
            message = "Root Realm uses notifications to show backup progress and let you know when a backup or restore task has finished.",
            onContinue = {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS
                )
            }
        )
    }

    private fun requestCallLogPermission() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PERMISSION_GRANTED) {
            confirmCallLogBackup()
            return
        }

        showPermissionReasonDialog(
            title = "Call Log permission",
            message = "Root Realm needs Call Log permission to read and save your incoming, outgoing and missed call history when you choose Call Log backup.",
            onContinue = {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_CALL_LOG),
                    REQUEST_CALL_LOG
                )
            }
        )
    }

    private fun requestSmsPermission() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PERMISSION_GRANTED) {
            confirmSmsBackup()
            return
        }

        showPermissionReasonDialog(
            title = "SMS permission",
            message = "Root Realm needs SMS permission to read and save the SMS messages on this device when you choose SMS backup. Android may also require the appropriate default SMS role.",
            onContinue = {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_SMS),
                    REQUEST_SMS
                )
            }
        )
    }

    private fun requestMediaPermission() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            checkSelfPermission(it) != PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            showMediaBackupPicker()
            return
        }

        showPermissionReasonDialog(
            title = "Storage / Media permission",
            message = "Root Realm needs access to shared storage or media so it can read the photos, videos, music and documents you choose to package for transfer.",
            onContinue = {
                requestPermissions(
                    missing.toTypedArray(),
                    REQUEST_STORAGE
                )
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PERMISSION_GRANTED }

        when (requestCode) {
            REQUEST_NOTIFICATIONS -> {
                if (!granted) {
                    toast("Notification permission was not granted. Backup can still be used.")
                }
            }

            REQUEST_CALL_LOG -> {
                if (granted) {
                    confirmCallLogBackup()
                } else {
                    toast("Call Log permission was not granted.")
                }
            }

            REQUEST_SMS -> {
                if (granted) {
                    confirmSmsBackup()
                } else {
                    toast("SMS permission was not granted.")
                }
            }

            REQUEST_STORAGE -> {
                if (granted) {
                    showMediaBackupPicker()
                } else {
                    toast("Storage / Media permission was not granted.")
                }
            }
        }
    }

    private fun showPermissionReasonDialog(
        title: String,
        message: String,
        onContinue: () -> Unit
    ) {
        showStyledConfirmDialog(
            title = title,
            message = message,
            positiveText = "ALLOW",
            negativeText = "CANCEL",
            onPositive = onContinue
        )
    }

    private fun confirmCallLogBackup() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PERMISSION_GRANTED) {
            toast("Call log permission is required.")
            return
        }
        showStyledConfirmDialog(
            title = "Confirm call log backup",
            message = "Back up the call history currently available to Root Realm?",
            positiveText = "BACK UP",
            negativeText = "CANCEL",
            onPositive = { performCallLogBackup() }
        )
    }

    private fun performCallLogBackup() {
        val file = File(cacheDir, "call_log_${System.currentTimeMillis()}.csv")
        executor.execute {
            try {
                var rowCount = 0
                file.bufferedWriter().use { out ->
                    out.appendLine("date,number,type,duration,name")
                    contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(
                            CallLog.Calls.DATE,
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DURATION,
                            CallLog.Calls.CACHED_NAME
                        ),
                        null, null,
                        CallLog.Calls.DATE + " ASC"
                    )?.use { c ->
                        while (c.moveToNext()) {
                            rowCount++
                            out.appendLine(listOf(
                                c.getLong(0), csv(c.getString(1)), c.getInt(2), c.getLong(3), csv(c.getString(4))
                            ).joinToString(","))
                        }
                    }
                }
                if (!saveFileToBackupLocation(file, file.name)) {
                    file.delete()
                    throw IllegalStateException("Could not save call log backup to Internal storage/Root Realm Backups")
                }
                file.delete()
                handler.post { loadBackups(); toast("Call log backup completed.") }
            } catch (e: Exception) {
                file.delete()
                handler.post { toast("Call log backup failed: ${e.message ?: "permission denied"}") }
            }
        }
    }

    private fun confirmSmsBackup() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PERMISSION_GRANTED) {
            toast("SMS permission is required.")
            return
        }
        showStyledConfirmDialog(
            title = "Confirm SMS backup",
            message = "Back up the SMS messages currently available to Root Realm?",
            positiveText = "BACK UP",
            negativeText = "CANCEL",
            onPositive = { performSmsBackup() }
        )
    }

    private fun performSmsBackup() {
        val file = File(cacheDir, "sms_${System.currentTimeMillis()}.csv")
        executor.execute {
            try {
                var rowCount = 0
                file.bufferedWriter().use { out ->
                    out.appendLine("date,address,type,body,read,status,thread_id")
                    contentResolver.query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(
                            Telephony.Sms.DATE,
                            Telephony.Sms.ADDRESS,
                            Telephony.Sms.TYPE,
                            Telephony.Sms.BODY,
                            Telephony.Sms.READ,
                            Telephony.Sms.STATUS,
                            Telephony.Sms.THREAD_ID
                        ),
                        null, null,
                        Telephony.Sms.DATE + " ASC"
                    )?.use { c ->
                        while (c.moveToNext()) {
                            rowCount++
                            out.appendLine(listOf(
                                c.getLong(0), csv(c.getString(1)), c.getInt(2), csv(c.getString(3)),
                                c.getInt(4), c.getInt(5), c.getLong(6)
                            ).joinToString(","))
                        }
                    }
                }
                if (!saveFileToBackupLocation(file, file.name)) {
                    file.delete()
                    throw IllegalStateException("Could not save SMS backup to Internal storage/Root Realm Backups")
                }
                file.delete()
                handler.post { loadBackups(); toast("SMS backup completed.") }
            } catch (e: Exception) {
                file.delete()
                handler.post { toast("SMS backup failed: ${e.message ?: "permission denied"}") }
            }
        }
    }

    private fun csv(value: String?): String {
        val v = value ?: ""
        return "\"${v.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")}\""
    }

    private data class DetectedRestoreBackup(
        val name: String,
        val path: String,
        val size: Long
    )

    private fun detectRestoreBackups() {
        restoreButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "Detecting Root Realm backups…"

        executor.execute {
            val detected = detectAutomaticRestoreBackups()

            handler.post {
                restoreButton.isEnabled = true
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE

                if (detected.isNotEmpty()) {
                    showDetectedRestoreBackups(detected)
                } else {
                    // Nothing in the normal Root Realm folder: let the user manually
                    // choose a backup from another location/device/USB/SD card.
                    selectRestoreBackup()
                }
            }
        }
    }

    private fun detectAutomaticRestoreBackups(): List<DetectedRestoreBackup> {
        val root = escapeShell(automaticBackupRoot.absolutePath)
        val command =
            "if [ -d '$root' ]; then " +
                    "find '$root' -type f \\( " +
                    "-name '*.img' -o -name '*.tar.gz' -o -name '*.apk' -o " +
                    "-name '*.zip' -o -name 'call_log_*.csv' -o -name 'sms_*.csv' " +
                    "\\) -printf '%p|%s\n' 2>/dev/null; " +
                    "fi"

        val result = runRootWithExitCode(command)
        if (result.exitCode != 0) return emptyList()

        return result.output
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.lastIndexOf('|')
                if (separator <= 0 || separator >= line.length - 1) return@mapNotNull null

                val path = line.substring(0, separator).trim()
                val size = line.substring(separator + 1).trim().toLongOrNull() ?: 0L
                if (path.isBlank() || size <= 0L) return@mapNotNull null

                DetectedRestoreBackup(
                    File(path).name,
                    path,
                    size
                )
            }
            .sortedBy { it.path.lowercase(Locale.US) }
            .toList()
    }

    private fun showDetectedRestoreBackups(
        backups: List<DetectedRestoreBackup>
    ) {
        val labels = backups.map {
            val relative = it.path.substringAfter(automaticBackupRoot.absolutePath + "/", it.path)
            "${it.name}\n${formatBytes(it.size)}  •  $relative"
        }.toTypedArray()

        showStyledMultiChoiceDialog(
            title = "Detected Backups",
            subtitle = "Select the backup you want to restore.",
            items = backups.map { backup ->
                val relative = backup.path.substringAfter(automaticBackupRoot.absolutePath + "/", backup.path)
                val label = backup.name
                val detail = "${formatBytes(backup.size)}  •  $relative"
                label to detail
            },
            positiveText = "RESTORE",
            negativeText = "CANCEL",
            onDone = { selected ->
                val index = selected.firstOrNull()
                if (index != null) prepareDetectedRestore(backups[index])
                else toast("Select a backup first.")
            }
        )
    }

    private fun prepareDetectedRestore(
        backup: DetectedRestoreBackup
    ) {
        restoreButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "Preparing ${backup.name}…"

        executor.execute {
            val safeName = backup.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val local = File(cacheDir, "restore_$safeName")
            if (local.exists()) local.delete()

            val command =
                "cp -f '${escapeShell(backup.path)}' '${escapeShell(local.absolutePath)}' && " +
                        "chmod 0644 '${escapeShell(local.absolutePath)}' && " +
                        "test -s '${escapeShell(local.absolutePath)}'"

            var result = runRootWithExitCode(command)
            if (result.exitCode != 0) {
                result = runRootMountMaster(command)
            }

            val copied = result.exitCode == 0 && local.exists() && local.length() > 0L

            handler.post {
                restoreButton.isEnabled = true
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE

                if (!copied) {
                    local.delete()
                    toast("Could not prepare the detected backup.")
                    return@post
                }

                when {
                    backup.name.endsWith(".img", true) -> confirmRestore(local)
                    backup.name.endsWith(".tar.gz", true) -> {
                        showStyledConfirmDialog(
                            title = "Restore APK + Data?",
                            message = "Restore ${backup.name}? Only use an APK + Data backup made for the target app/device.",
                            positiveText = "RESTORE",
                            negativeText = "CANCEL",
                            onPositive = { restoreAppBackup(local) }
                        )
                    }
                    else -> {
                        toast("This backup type is detected, but its restore handler is not available yet: ${backup.name}")
                        local.delete()
                    }
                }
            }
        }
    }

    private fun selectRestoreBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_RESTORE_BACKUP)
    }

    @Deprecated("Use Activity Result APIs when this screen is modernized")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_RESTORE_BACKUP || resultCode != Activity.RESULT_OK) return

        val uri = data?.data ?: return
        try {
            val takeFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Exception) {
        }

        restoreButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "Preparing restore…"

        executor.execute {
            val name = queryDisplayName(uri) ?: "selected_backup"
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val local = File(cacheDir, "restore_$safeName")

            val copied = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(local).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                local.exists() && local.length() > 0L
            } catch (_: Exception) {
                false
            }

            handler.post {
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                restoreButton.isEnabled = true

                if (!copied) {
                    local.delete()
                    toast("Could not read the selected backup file.")
                    return@post
                }

                when {
                    name.endsWith(".img", true) -> {
                        confirmRestore(local)
                    }
                    name.endsWith(".tar.gz", true) -> {
                        showStyledConfirmDialog(
                            title = "Restore APK + Data?",
                            message = "Restore $name to this device? This can replace application data. Only use a backup made for this device/app.",
                            positiveText = "RESTORE",
                            negativeText = "CANCEL",
                            onPositive = { restoreAppBackup(local) }
                        )
                    }
                    else -> {
                        toast("Selected backup format is not supported yet: $name")
                        local.delete()
                    }
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf("_display_name"),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveFileToBackupLocation(source: File, fileName: String): Boolean {
        // Manual backup-location selection has been removed. All backups are
        // automatically stored under Internal storage/Root Realm Backups.
        return saveToAutomaticStorage(source, fileName)
    }

    private fun saveToAutomaticStorage(source: File, fileName: String): Boolean {
        if (!source.exists() || source.length() <= 0L) return false

        return try {
            val targetDirectory = when {
                fileName.endsWith(".img", true) ->
                    automaticPartitionDirectory

                fileName.endsWith(".tar.gz", true) ||
                        fileName.endsWith(".apk", true) ->
                    automaticAppDataDirectory

                fileName.startsWith("media_", true) ||
                        fileName.endsWith(".zip", true) ->
                    automaticMediaDirectory

                fileName.startsWith("call_log_", true) ->
                    automaticCallLogDirectory

                fileName.startsWith("sms_", true) ->
                    automaticSmsDirectory

                else ->
                    automaticBackupRoot
            }

            val target = File(targetDirectory, fileName)

            // Use root to write to shared internal storage. This avoids scoped-storage
            // restrictions in the normal app process and keeps the backup outside
            // Root Realm's private /data/user/0/.../files directory.
            val command =
                "mkdir -p '${escapeShell(targetDirectory.absolutePath)}' && " +
                        "cp -f '${escapeShell(source.absolutePath)}' " +
                        "'${escapeShell(target.absolutePath)}' && " +
                        "chmod 0644 '${escapeShell(target.absolutePath)}'"

            val verifyCommand = command +
                    " && test -s '${escapeShell(target.absolutePath)}' && " +
                    "stat -c %s '${escapeShell(target.absolutePath)}'"

            var result = runRootWithExitCode(verifyCommand)
            if (result.exitCode != 0) {
                result = runRootMountMaster(verifyCommand)
            }

            val targetSize = result.output.trim().lines().lastOrNull()
                ?.trim()?.toLongOrNull() ?: 0L

            result.exitCode == 0 &&
                    targetSize == source.length() &&
                    targetSize > 0L
        } catch (_: Exception) {
            false
        }
    }

    private fun isSensitivePartition(
        name: String
    ): Boolean {

        return when (
            name.lowercase(Locale.US)
        ) {
            "userdata",
            "data",
            "metadata",
            "cache",
            "sdcard" -> true

            else -> false
        }
    }

    private fun isDangerousRestorePartition(
        name: String
    ): Boolean {

        return when (
            name.lowercase(Locale.US)
        ) {
            "userdata",
            "super",
            "metadata",
            "cache",
            "system",
            "vendor",
            "product",
            "odm" -> true

            else -> false
        }
    }

    private fun runRootMountMaster(
        command: String
    ): CommandResult {
        return try {
            val process =
                ProcessBuilder(
                    "su",
                    "-mm",
                    "-c",
                    command
                )
                    .redirectErrorStream(true)
                    .start()

            val output =
                process.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            val exitCode = process.waitFor()

            CommandResult(
                exitCode = exitCode,
                output = output
            )
        } catch (e: Exception) {
            CommandResult(
                exitCode = -1,
                output = e.message ?: ""
            )
        }
    }

    private fun runRootWithExitCode(
        command: String
    ): CommandResult {

        return try {

            val process =
                ProcessBuilder(
                    "su",
                    "-c",
                    command
                )
                    .redirectErrorStream(true)
                    .start()

            val output =
                process.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            val exitCode =
                process.waitFor()

            CommandResult(
                exitCode = exitCode,
                output = output
            )

        } catch (e: Exception) {

            CommandResult(
                exitCode = -1,
                output = e.message ?: ""
            )
        }
    }

    private fun escapeShell(
        value: String
    ): String {

        return value.replace(
            "'",
            "'\\''"
        )
    }

    private fun showEmptyState(
        container: LinearLayout,
        message: String
    ) {

        val text =
            createText(
                message,
                13f,
                secondaryColor
            )

        text.gravity =
            Gravity.CENTER

        text.setPadding(
            dp(10),
            dp(24),
            dp(10),
            dp(24)
        )

        container.addView(
            text,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )
    }

    private fun createSectionHeader(
        text: String
    ): TextView {

        return createText(
            text,
            12f,
            primaryColor
        ).apply {

            letterSpacing = 0.12f

            setPadding(
                dp(2),
                dp(4),
                dp(2),
                dp(4)
            )
        }
    }

    private fun createCard(): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(16),
                dp(16),
                dp(16),
                dp(16)
            )

            background =
                roundedBackground(
                    cardColor,
                    borderColor,
                    22
                )
        }
    }

    private fun createButton(
        text: String
    ): Button {

        return Button(this).apply {

            this.text = text

            setTextColor(
                textColor
            )

            textSize = 12f

            isAllCaps = false

            typeface =
                android.graphics.Typeface.DEFAULT_BOLD

            gravity =
                Gravity.CENTER

            background =
                roundedBackground(
                    primaryColor,
                    primaryColor,
                    16
                )

            stateListAnimator = null
            isClickable = true
            isFocusable = true
            setOnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(70).start()
                    }
                }
                false
            }

            setPadding(
                dp(8),
                0,
                dp(8),
                0
            )
        }
    }

    private fun createText(
        text: String,
        size: Float,
        color: Int
    ): TextView {

        return TextView(this).apply {

            this.text = text
            textSize = size
            setTextColor(color)

            includeFontPadding = true

            setPadding(
                0,
                0,
                0,
                0
            )
        }
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

    private fun space(
        height: Int
    ): View {

        return View(this).apply {

            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    dp(height)
                )
        }
    }

    private fun formatBytes(
        bytes: Long
    ): String {

        if (bytes <= 0) {
            return "Unknown size"
        }

        val units =
            arrayOf(
                "B",
                "KB",
                "MB",
                "GB",
                "TB"
            )

        var value =
            bytes.toDouble()

        var index = 0

        while (
            value >= 1024 &&
            index < units.lastIndex
        ) {

            value /= 1024
            index++
        }

        return if (index == 0) {

            "${bytes} ${units[index]}"

        } else {

            String.format(
                Locale.US,
                "%.2f %s",
                value,
                units[index]
            )
        }
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                    resources.displayMetrics.density
            ).toInt()
    }

    private fun toast(
        message: String
    ) {

        showToast(message, Toast.LENGTH_LONG)
    }

    /** Append a line to the on-screen terminal and auto-scroll to bottom. */
    private fun log(message: String) {
        val line = message.trimEnd()
        if (line.isEmpty()) return
        handler.post {
            if (logBuffer.isNotEmpty()) logBuffer.append('\n')
            logBuffer.append(line)
            // Keep the buffer from growing forever
            if (logBuffer.length > 12000) {
                logBuffer.delete(0, logBuffer.length - 10000)
            }
            logText.text = logBuffer.toString()
            logScroll.post {
                logScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun logClear() {
        handler.post {
            logBuffer.clear()
            logText.text = "Log cleared."
        }
    }

    private fun setTerminalProgress(percent: Int, label: String) {
        handler.post {
            terminalProgressBar.visibility = View.VISIBLE
            terminalProgressText.visibility = View.VISIBLE
            terminalProgressBar.isIndeterminate = false
            terminalProgressBar.progress = percent.coerceIn(0, 100)
            terminalProgressText.text = "$label  ($percent%)"
        }
    }

    private fun hideTerminalProgress() {
        handler.post {
            terminalProgressBar.visibility = View.GONE
            terminalProgressText.visibility = View.GONE
            terminalProgressBar.progress = 0
            terminalProgressText.text = ""
        }
    }
}
