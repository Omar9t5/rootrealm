package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager

import java.security.Signature

import com.faroquetech.theme.RootRealmGlobalTheme

import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.content.ContentValues
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher

class AdbFastbootActivity : BaseActivity() {

    private val bgColor get() = ThemeManager.current(this).background
    private val cardColor get() = ThemeManager.current(this).surface
    private val borderColor get() = ThemeManager.current(this).let { ThemeManager.borderColor(it) }
    private val primaryColor get() = ThemeManager.current(this).accent
    private val textColor get() = ThemeManager.current(this).text
    private val secondaryColor get() = ThemeManager.current(this).secondary
    private val dangerColor get() = ThemeManager.current(this).error

    private lateinit var usbManager: UsbManager
    private lateinit var statusCard: LinearLayout
    private lateinit var deviceStatus: TextView
    private lateinit var deviceInfo: TextView
    private lateinit var outputCard: LinearLayout
    private lateinit var output: TextView
    private lateinit var commandInput: EditText

    // Tracks how many background tasks are currently running so overlapping
    // tasks (e.g. a reboot fired while a flash is still finishing) don't
    // stop the pulse animation early.
    private var runningTaskCount: Int = 0
    private var taskPulseAnimator: ValueAnimator? = null

    private lateinit var selectedFileText: TextView
    private lateinit var fileTypeText: TextView

    private lateinit var logcatOutput: TextView
    private lateinit var logcatScrollView: ScrollView
    private lateinit var logcatFilterInput: EditText

    private var selectedDevice: UsbDevice? = null

    private enum class UsbMode {
        ADB,
        FASTBOOT
    }

    private var selectedUsbMode: UsbMode? = null

    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""
    private var selectedFileType: String = ""

    private var adbCrypto: AdbCrypto? = null
    private var activeAdbConnection: AdbConnection? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val logcatExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    // Logcat can emit hundreds of USB packets/sec. Buffering here and flushing
    // on a timer (instead of posting a TextView update per packet) keeps the
    // main thread from being flooded and freezing the UI.
    private val logcatBuffer = StringBuilder()
    private val logcatLock = Any()
    private var logcatFlushScheduled = false

    // ADB and Fastboot deliberately use separate permission actions.
    // ADB permission never enters the Fastboot path, and vice versa.
    private val ACTION_ADB_USB_PERMISSION =
        "com.faroquetech.rootrealm.ADB_USB_PERMISSION"

    private val ACTION_FASTBOOT_USB_PERMISSION =
        "com.faroquetech.rootrealm.FASTBOOT_USB_PERMISSION"

    private val PICK_PACKAGE_FILE = 2001

    private val usbReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            val action = intent.action

            val mode = when (action) {
                ACTION_ADB_USB_PERMISSION -> UsbMode.ADB
                ACTION_FASTBOOT_USB_PERMISSION -> UsbMode.FASTBOOT
                else -> return
            }

            val device =
                intent.getParcelableExtra<UsbDevice>(
                    UsbManager.EXTRA_DEVICE
                )

            if (device == null) {
                appendOutput(
                    "${mode.name} USB permission callback contained no device.\n"
                )
                return
            }

            // Never let an ADB callback become a Fastboot device or the
            // other way around. Re-check the USB interface state because
            // the phone can reboot/change modes while the permission
            // dialog is on screen.
            val stillCorrectMode = when (mode) {
                UsbMode.ADB -> isAdbDevice(device)
                UsbMode.FASTBOOT -> isFastbootDevice(device)
            }

            val granted =
                intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
                )

            if (!stillCorrectMode) {
                appendOutput(
                    "${mode.name} permission result ignored: device is no longer in ${mode.name} mode.\n"
                )
                refreshUsbDevices()
                return
            }

            if (granted && usbManager.hasPermission(device)) {
                selectedDevice = device
                selectedUsbMode = mode

                appendOutput(
                    "${mode.name} USB permission granted.\n"
                )

                inspectDevice(device)

            } else {
                selectedDevice = device
                selectedUsbMode = mode

                deviceStatus.text =
                    "● ${mode.name} USB PERMISSION DENIED"

                deviceStatus.setTextColor(
                    dangerColor
                )

                // This runs from a broadcast callback, outside onCreate/
                // onResume, so the global engine never gets a chance to
                // retint deviceStatus afterward unless asked to explicitly.
                RootRealmGlobalTheme.applyTheme(this@AdbFastbootActivity)

                appendOutput(
                    "${mode.name} USB permission denied.\n"
                )
            }
        }
    }

    // Fires when any USB device is physically plugged in or unplugged, so the
    // device list refreshes on its own instead of requiring a manual tap on
    // REFRESH. Separate from usbReceiver, which only handles the ADB/Fastboot
    // permission-result callbacks.
    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshUsbDevices()
                }
            }
        }
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        usbManager =
            getSystemService(
                Context.USB_SERVICE
            ) as UsbManager

        val usbPermissionFilter = IntentFilter().apply {
            addAction(ACTION_ADB_USB_PERMISSION)
            addAction(ACTION_FASTBOOT_USB_PERMISSION)
        }

        registerReceiver(
            usbReceiver,
            usbPermissionFilter,
            Context.RECEIVER_NOT_EXPORTED
        )

        val usbAttachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        registerReceiver(
            usbAttachReceiver,
            usbAttachFilter,
            Context.RECEIVER_NOT_EXPORTED
        )

        buildUi()
        refreshUsbDevices()
    }

    override fun onDestroy() {

        try {
            unregisterReceiver(
                usbReceiver
            )
        } catch (_: Exception) {
        }

        try {
            unregisterReceiver(
                usbAttachReceiver
            )
        } catch (_: Exception) {
        }

        activeAdbConnection?.stop()

        executor.shutdownNow()
        logcatExecutor.shutdownNow()

        handler.removeCallbacksAndMessages(null)
        synchronized(logcatLock) { logcatBuffer.setLength(0) }

        super.onDestroy()
    }

    private fun buildUi() {

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    bgColor
                )
            }

        val scroll =
            ScrollView(this).apply {
                setBackgroundColor(
                    bgColor
                )

                isFillViewport = true
            }

        val content =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(18),
                    dp(18),
                    dp(18),
                    dp(32)
                )
            }

        content.addView(
            createText(
                "ADB & Fastboot",
                28f,
                textColor
            ).apply {
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        content.addView(
            createText(
                "USB Android device tools",
                14f,
                secondaryColor
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(18)
            }
        )

        statusCard =
            createCard()

        deviceStatus =
            createText(
                "● NO USB DEVICE",
                15f,
                secondaryColor
            )

        statusCard.addView(
            deviceStatus
        )

        deviceInfo =
            createText(
                "Connect an Android device using USB OTG.",
                13f,
                secondaryColor
            )

        statusCard.addView(
            deviceInfo,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(8)
            }
        )

        content.addView(
            statusCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        content.addView(
            createSectionHeader(
                "OUTPUT"
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        outputCard =
            createCard()

        val outputScrollView =
            ScrollView(this).apply {
                setBackgroundColor(
                    bgColor
                )
                isFillViewport = false
                isVerticalScrollBarEnabled = true
                isSmoothScrollingEnabled = true
            }

        output =
            createText(
                "Waiting for device…",
                12f,
                primaryColor
            )

        output.typeface =
            Typeface.MONOSPACE

        // Keep the OUTPUT terminal at a fixed height but allow the user
        // to swipe up/down inside it to inspect the complete history.
        output.isVerticalScrollBarEnabled = true
        output.isHorizontalScrollBarEnabled = true
        output.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

        output.setPadding(
            dp(4),
            dp(4),
            dp(4),
            dp(4)
        )

        outputScrollView.addView(
            output,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        outputCard.addView(
            outputScrollView,
            LinearLayout.LayoutParams(
                -1,
                dp(240)
            )
        )

        // Manual terminal scrolling controls. These let the user move
        // through the OUTPUT history even when touch scrolling is awkward.
        val outputScrollButtons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }

        val outputUpButton =
            createButton("▲ SCROLL UP")

        outputUpButton.setOnClickListener {
            outputScrollView.smoothScrollBy(
                0,
                -dp(180)
            )
        }

        val outputDownButton =
            createButton("▼ SCROLL DOWN")

        outputDownButton.setOnClickListener {
            outputScrollView.smoothScrollBy(
                0,
                dp(180)
            )
        }

        outputScrollButtons.addView(
            outputUpButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(5)
            }
        )

        outputScrollButtons.addView(
            outputDownButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginStart = dp(5)
            }
        )

        outputCard.addView(
            outputScrollButtons,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        content.addView(
            outputCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        content.addView(
            createSectionHeader(
                "USB DEVICE"
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val usbCard =
            createCard()

        val refreshButton =
            createButton(
                "REFRESH USB DEVICES"
            )

        val permissionButton =
            createButton(
                "REQUEST USB PERMISSION"
            )

        usbCard.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        usbCard.addView(
            permissionButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        )

        content.addView(
            usbCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        refreshButton.setOnClickListener {
            refreshUsbDevices()
        }

        permissionButton.setOnClickListener {
            requestPermission()
        }
                content.addView(
            createSectionHeader(
                "ZIP / IMAGE"
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val packageCard =
            createCard()

        selectedFileText =
            createText(
                "No ZIP package or IMG image selected.",
                14f,
                secondaryColor
            )

        packageCard.addView(
            selectedFileText
        )

        fileTypeText =
            createText(
                "Select a ZIP package or IMG image.",
                12f,
                secondaryColor
            )

        packageCard.addView(
            fileTypeText,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(12)
            }
        )

        val selectFileButton =
            createButton(
                "SELECT ZIP / IMAGE"
            )

        packageCard.addView(
            selectFileButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val clearFileButton =
            createButton(
                "CLEAR SELECTION"
            )

        packageCard.addView(
            clearFileButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        )

        content.addView(
            packageCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        selectFileButton.setOnClickListener {
            selectPackageOrImage()
        }

        clearFileButton.setOnClickListener {
            clearSelectedFile()
        }

        content.addView(
            createSectionHeader(
                "ADB"
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val adbCard =
            createCard()

        val sideloadButton =
            createButton(
                "ADB SIDELOAD SELECTED FILE"
            )

        adbCard.addView(
            sideloadButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val adbRecoveryButton =
            createButton(
                "ADB REBOOT RECOVERY"
            )

        adbCard.addView(
            adbRecoveryButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val adbBootloaderButton =
            createButton(
                "ADB REBOOT BOOTLOADER"
            )

        adbCard.addView(
            adbBootloaderButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val rebootAdbButton =
            createButton(
                "REBOOT DEVICE"
            )

        adbCard.addView(
            rebootAdbButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        )

        content.addView(
            adbCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        sideloadButton.setOnClickListener {
            val uri = selectedFileUri
            if (uri == null) {
                toast("Select a ZIP package first.")
                return@setOnClickListener
            }

            if (!selectedFileName.lowercase().endsWith(".zip")) {
                toast("ADB sideload normally requires a ZIP package.")
                return@setOnClickListener
            }

            sideloadSelectedFile(uri, selectedFileName)
        }

        adbRecoveryButton.setOnClickListener {
            rebootAdbToMode("recovery")
        }

        adbBootloaderButton.setOnClickListener {
            rebootAdbToMode("bootloader")
        }

        rebootAdbButton.setOnClickListener {
            rebootAdbDevice()
        }

        content.addView(
            createSectionHeader(
                "LOGCAT"
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val logcatCard =
            createCard()

        logcatFilterInput =
            EditText(this).apply {

                hint =
                    "Optional filter, e.g. ActivityManager:I *:S"

                setHintTextColor(
                    secondaryColor
                )

                setTextColor(
                    textColor
                )

                textSize = 14f

                setSingleLine(true)

                setPadding(
                    dp(14),
                    0,
                    dp(14),
                    0
                )

                background =
                    rounded(
                        bgColor,
                        12
                    )
            }

        logcatCard.addView(
            logcatFilterInput,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val logcatButtonRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val startLogcatButton =
            createButton(
                "START LOGCAT"
            )

        val stopLogcatButton =
            createButton(
                "STOP"
            )

        logcatButtonRow.addView(
            startLogcatButton,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            ).apply {
                marginEnd = dp(8)
            }
        )

        logcatButtonRow.addView(
            stopLogcatButton,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            )
        )

        logcatCard.addView(
            logcatButtonRow,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val logcatButtonRow2 =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val clearLogcatButton =
            createButton(
                "CLEAR"
            )

        val saveLogcatButton =
            createButton(
                "SAVE LOG"
            )

        logcatButtonRow2.addView(
            clearLogcatButton,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            ).apply {
                marginEnd = dp(8)
            }
        )

        logcatButtonRow2.addView(
            saveLogcatButton,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            )
        )

        logcatCard.addView(
            logcatButtonRow2,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(12)
            }
        )

        logcatScrollView =
            ScrollView(this).apply {
                setBackgroundColor(
                    bgColor
                )

                // This ScrollView is nested inside the page's outer ScrollView.
                // Without this, the outer ScrollView intercepts vertical drags
                // before this one sees them, so touching the terminal scrolls
                // the whole page instead of the log content. Telling the parent
                // to disallow intercepting while the user is actively dragging
                // here (and releasing that claim once the gesture ends) makes
                // this view scroll independently, the way a terminal should.
                setOnTouchListener { v, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE -> {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    false
                }
            }

        logcatOutput =
            createText(
                "Logcat idle. Press START LOGCAT to stream logs " +
                        "from the connected device over USB.",
                11f,
                primaryColor
            ).apply {

                typeface =
                    Typeface.MONOSPACE

                setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(8)
                )
            }

        logcatScrollView.addView(
            logcatOutput,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        logcatCard.addView(
            logcatScrollView,
            LinearLayout.LayoutParams(
                -1,
                dp(320)
            )
        )

        content.addView(
            logcatCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        startLogcatButton.setOnClickListener {
            startLogcat()
        }

        stopLogcatButton.setOnClickListener {
            stopLogcat()
        }

        clearLogcatButton.setOnClickListener {
            clearLogcat()
        }

        saveLogcatButton.setOnClickListener {
            saveLogcatToFile()
        }

        content.addView(
            createSectionHeader(
                "FASTBOOT"
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val fastbootCard =
            createCard()

        val getVarsButton =
            createButton(
                "GET DEVICE VARIABLES"
            )

        fastbootCard.addView(
            getVarsButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val rebootButton =
            createButton(
                "REBOOT SYSTEM"
            )

        fastbootCard.addView(
            rebootButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val bootloaderButton =
            createButton(
                "REBOOT BOOTLOADER"
            )

        fastbootCard.addView(
            bootloaderButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val recoveryButton =
            createButton(
                "REBOOT RECOVERY"
            )

        fastbootCard.addView(
            recoveryButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val continueButton =
            createButton(
                "CONTINUE"
            )

        fastbootCard.addView(
            continueButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        )

        content.addView(
            fastbootCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        getVarsButton.setOnClickListener {
            runFastbootGetVars()
        }

        rebootButton.setOnClickListener {
            runFastbootCommand("reboot")
        }

        bootloaderButton.setOnClickListener {
            runFastbootCommand(
                "reboot-bootloader"
            )
        }

        recoveryButton.setOnClickListener {
            runFastbootCommand(
                "reboot-recovery"
            )
        }

        continueButton.setOnClickListener {
            runFastbootCommand("continue")
        }
                content.addView(
            createSectionHeader(
                "FASTBOOT IMAGE"
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val imageCard =
            createCard()

        val imageInfo =
            createText(
                "Select an IMG file above, then choose the target partition.",
                13f,
                secondaryColor
            )

        imageCard.addView(
            imageInfo,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val partitionInput =
            EditText(this).apply {

                hint =
                    "Target partition e.g. boot"

                setHintTextColor(
                    secondaryColor
                )

                setTextColor(
                    textColor
                )

                textSize = 14f

                setSingleLine(true)

                setPadding(
                    dp(14),
                    0,
                    dp(14),
                    0
                )

                background =
                    rounded(
                        bgColor,
                        12
                    )
            }

        imageCard.addView(
            partitionInput,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val flashButton =
            createButton(
                "FLASH SELECTED IMAGE"
            )

        imageCard.addView(
            flashButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        )

        content.addView(
            imageCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        flashButton.setOnClickListener {

            val uri =
                selectedFileUri

            if (uri == null) {

                toast(
                    "Select an IMG file first."
                )

                return@setOnClickListener
            }

            if (
                !selectedFileName
                    .lowercase()
                    .endsWith(".img")
            ) {

                toast(
                    "Fastboot flashing requires an IMG file."
                )

                return@setOnClickListener
            }

            val partition =
                partitionInput
                    .text
                    .toString()
                    .trim()

            if (partition.isEmpty()) {

                toast(
                    "Enter the target partition."
                )

                return@setOnClickListener
            }

            flashSelectedImage(uri, partition)
        }

        content.addView(
            createSectionHeader(
                "FASTBOOT COMMAND"
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val commandCard =
            createCard()

        commandInput =
            EditText(this).apply {

                hint =
                    "Example: getvar:product"

                setHintTextColor(
                    secondaryColor
                )

                setTextColor(
                    textColor
                )

                textSize = 14f

                setSingleLine(true)

                setPadding(
                    dp(14),
                    0,
                    dp(14),
                    0
                )

                background =
                    rounded(
                        bgColor,
                        12
                    )
            }

        commandCard.addView(
            commandInput,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val sendButton =
            createButton(
                "SEND FASTBOOT COMMAND"
            )

        commandCard.addView(
            sendButton,
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            )
        )

        content.addView(
            commandCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(14)
            }
        )

        sendButton.setOnClickListener {

            val command =
                commandInput
                    .text
                    .toString()
                    .trim()

            if (command.isEmpty()) {

                toast(
                    "Enter a Fastboot command."
                )

                return@setOnClickListener
            }

            runFastbootCommand(command)
        }

        val warningCard =
            createCard()

        warningCard.addView(
            createText(
                "⚠  IMPORTANT",
                15f,
                dangerColor
            )
        )

        warningCard.addView(
            createText(
                "Fastboot commands can modify or erase " +
                        "device partitions. Only flash images " +
                        "that belong to the target device. " +
                        "Never erase or flash unknown partitions.",
                13f,
                secondaryColor
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                topMargin = dp(8)
            }
        )

        content.addView(
            warningCard
        )

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun selectPackageOrImage() {

        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {

                addCategory(
                    Intent.CATEGORY_OPENABLE
                )

                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/zip",
                        "application/octet-stream",
                        "image/*"
                    )
                )
            }

        startActivityForResult(
            intent,
            PICK_PACKAGE_FILE
        )
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode != PICK_PACKAGE_FILE ||
            resultCode != RESULT_OK ||
            data == null
        ) {
            return
        }

        val uri =
            data.data

        if (uri == null) {
            return
        }

        selectedFileUri = uri

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }

        selectedFileName =
            getFileName(uri)

        val lowerName = selectedFileName.lowercase()
        if (!lowerName.endsWith(".zip") && !lowerName.endsWith(".img")) {
            selectedFileUri = null
            selectedFileName = ""
            selectedFileType = ""
            toast("Only ZIP and IMG files are supported.")
            appendOutput("\nFile selection rejected: only ZIP and IMG files are supported.\n")
            return
        }

        selectedFileType =
            detectFileType(
                selectedFileName
            )

        selectedFileText.text =
            selectedFileName

        selectedFileText.setTextColor(
            textColor
        )

        fileTypeText.text =
            selectedFileType

        fileTypeText.setTextColor(
            primaryColor
        )

        appendOutput(
            "\nSelected file:\n" +
                    "$selectedFileName\n" +
                    "Type: $selectedFileType\n"
        )
    }

    private fun clearSelectedFile() {

        selectedFileUri = null
        selectedFileName = ""
        selectedFileType = ""

        selectedFileText.text =
            "No ZIP package or IMG image selected."

        selectedFileText.setTextColor(
            secondaryColor
        )

        fileTypeText.text =
            "Select a ZIP package or IMG image."

        fileTypeText.setTextColor(
            secondaryColor
        )

        appendOutput(
            "\nFile selection cleared.\n"
        )
    }

    private fun getFileName(
        uri: Uri
    ): String {

        var name: String? = null

        try {

            val cursor =
                contentResolver.query(
                    uri,
                    arrayOf(
                        "_display_name"
                    ),
                    null,
                    null,
                    null
                )

            cursor?.use {

                if (it.moveToFirst()) {

                    val index =
                        it.getColumnIndex(
                            "_display_name"
                        )

                    if (index >= 0) {
                        name =
                            it.getString(index)
                    }
                }
            }

        } catch (_: Exception) {
        }

        return name
            ?: uri.lastPathSegment
            ?: "selected_file"
    }

    private fun detectFileType(
        name: String
    ): String {

        return when {

            name.lowercase()
                .endsWith(".img") ->
                "Android partition image"

            name.lowercase()
                .endsWith(".zip") ->
                "ZIP package"

            else ->
                "Unknown file type"
        }
    }
    private fun sideloadSelectedFile(
        uri: Uri,
        fileName: String
    ) {
        val device = selectedDevice

        if (device == null || !isAdbDevice(device)) {
            toast("Device must be in ADB sideload/recovery mode.")
            appendOutput("\nADB SIDELOAD rejected: device is not in ADB mode.\n")
            return
        }

        val size = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                ?: -1L
        } catch (_: Exception) {
            -1L
        }

        if (size <= 0L) {
            toast("Invalid sideload file size.")
            appendOutput("\nADB SIDELOAD ERROR: Invalid file size ($size).\n")
            return
        }

        appendOutput(
            "\nADB SIDELOAD REQUEST\n" +
                    "Package: $fileName ($size bytes)\n" +
                    "Target: Android Recovery / Apply update from ADB\n" +
                    "Put the target device in ADB sideload mode before continuing.\n"
        )

        executor.execute {
            setTaskRunning(true)
            try {
                if (adbCrypto == null) {
                    adbCrypto = AdbCrypto(applicationContext)
                }

                val connection = AdbConnection(
                    context = applicationContext,
                    usbManager = usbManager,
                    device = device,
                    crypto = adbCrypto!!,
                    onData = { text -> appendOutput(text) },
                    onStatus = { text -> appendOutput("$text\n") },
                    onProgress = { sent, total ->
                        val percent =
                            if (total > 0L) (sent * 100L / total).toInt() else 0
                        if (sent == total || percent % 5 == 0) {
                            appendOutput(
                                "\nADB SIDELOAD: $percent% ($sent/$total bytes)"
                            )
                        }
                    }
                )

                val result = connection.connectAndSideload(
                    uri = uri,
                    totalBytes = size
                ) { input ->
                    contentResolver.openInputStream(input)
                        ?: throw Exception("Unable to open selected sideload package.")
                }

                appendOutput("\nADB SIDELOAD RESULT: $result\n")
                handler.post { toast("ADB sideload completed.") }
            } catch (e: Exception) {
                appendOutput(
                    "\nADB SIDELOAD ERROR: ${e.message ?: "Unknown error"}\n"
                )
                handler.post { toast("ADB sideload failed.") }
            } finally {
                setTaskRunning(false)
            }
        }
    }

    private fun rebootAdbDevice() {

        val device = selectedDevice

        if (device == null || !isAdbDevice(device)) {
            toast("Device is not in ADB mode.")
            appendOutput("\nADB reboot rejected: device is not in ADB mode.\n")
            return
        }

        appendOutput("\nADB reboot requested.\n")

        executor.execute {
            setTaskRunning(true)
            try {
                if (adbCrypto == null) {
                    adbCrypto = AdbCrypto(applicationContext)
                }

                AdbConnection(
                    context = applicationContext,
                    usbManager = usbManager,
                    device = device,
                    crypto = adbCrypto!!,
                    onData = { text -> appendOutput(text) },
                    onStatus = { text -> appendOutput("$text\n") }
                ).connectAndReboot()

                appendOutput("ADB reboot command sent.\n")
                handler.post { toast("Reboot command sent.") }
            } catch (e: Exception) {
                appendOutput("ADB REBOOT ERROR: ${e.message ?: "Unknown error"}\n")
                handler.post { toast("ADB reboot failed.") }
            } finally {
                setTaskRunning(false)
            }
        }
    }

    private fun rebootAdbToMode(mode: String) {

        val device = selectedDevice

        if (device == null || !isAdbDevice(device)) {
            toast("Device is not in ADB mode.")
            appendOutput("\nADB reboot $mode rejected: device is not in ADB mode.\n")
            return
        }

        appendOutput("\nADB reboot $mode requested.\n")

        executor.execute {
            setTaskRunning(true)
            try {
                if (adbCrypto == null) {
                    adbCrypto = AdbCrypto(applicationContext)
                }

                AdbConnection(
                    context = applicationContext,
                    usbManager = usbManager,
                    device = device,
                    crypto = adbCrypto!!,
                    onData = { text -> appendOutput(text) },
                    onStatus = { text -> appendOutput("$text\n") }
                ).connectAndReboot("reboot $mode")

                appendOutput("ADB reboot $mode command sent.\n")
                handler.post { toast("Reboot $mode command sent.") }
            } catch (e: Exception) {
                appendOutput("ADB REBOOT $mode ERROR: ${e.message ?: "Unknown error"}\n")
                handler.post { toast("ADB reboot $mode failed.") }
            } finally {
                setTaskRunning(false)
            }
        }
    }

    private fun flashSelectedImage(uri: Uri, partition: String) {

        val device = selectedDevice

        if (device == null || !isFastbootDevice(device)) {
            toast("Device is not in Fastboot mode.")
            appendOutput("\nFastboot flash rejected: device is not in Fastboot mode.\n")
            return
        }

        val size = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                ?: -1L
        } catch (_: Exception) {
            -1L
        }

        if (size <= 0L) {
            toast("Invalid image size.")
            appendOutput("\nFASTBOOT FLASH ERROR: Invalid image size ($size).\n")
            return
        }

        appendOutput(
            "\nFASTBOOT FLASH REQUEST\n" +
                    "Image: $selectedFileName ($size bytes)\n" +
                    "Partition: $partition\n" +
                    "No unlock/erase operation will be performed.\n"
        )

        executor.execute {
            setTaskRunning(true)
            var connection: UsbDeviceConnection? = null
            var intf: UsbInterface? = null
            try {
                intf = findFastbootInterface(device)
                    ?: throw Exception("Fastboot USB interface not found.")
                val endpoints = getBulkEndpoints(intf!!)
                    ?: throw Exception("Fastboot bulk endpoints not found.")
                connection = usbManager.openDevice(device)
                    ?: throw Exception("Unable to open USB device.")

                if (!connection!!.claimInterface(intf!!, true)) {
                    throw Exception("Unable to claim Fastboot interface.")
                }

                fastbootDownloadAndFlash(
                    connection!!,
                    endpoints.first,
                    endpoints.second,
                    uri,
                    size,
                    partition
                )

                appendOutput("\nFASTBOOT FLASH complete: $partition\n")
                handler.post { toast("Fastboot flash complete.") }
            } catch (e: Exception) {
                appendOutput("\nFASTBOOT FLASH ERROR: ${e.message ?: "Unknown error"}\n")
                handler.post { toast("Fastboot flash failed.") }
            } finally {
                try { intf?.let { connection?.releaseInterface(it) } } catch (_: Exception) {}
                try { connection?.close() } catch (_: Exception) {}
                setTaskRunning(false)
            }
        }
    }

    private fun fastbootDownloadAndFlash(
        connection: UsbDeviceConnection,
        input: UsbEndpoint,
        outputEndpoint: UsbEndpoint,
        uri: Uri,
        size: Long,
        partition: String
    ) {
        val targetMaxDownload =
            queryFastbootMaxDownloadSize(
                connection,
                input,
                outputEndpoint
            )

        val maxDownload =
            if (targetMaxDownload > 0L) {
                minOf(targetMaxDownload, 0xFFFF_FFFFL)
            } else {
                0xFFFF_FFFFL
            }

        appendOutput(
            "\nFASTBOOT MAX DOWNLOAD: " +
                    if (targetMaxDownload > 0L) {
                        "$targetMaxDownload bytes"
                    } else {
                        "not reported; using protocol maximum"
                    }
        )

        if (maxDownload < 4096L) {
            throw Exception(
                "Device max-download-size is too small for safe sparse flashing: $maxDownload bytes."
            )
        }

        if (isSparseImage(uri)) {
            appendOutput("\nFASTBOOT: Android sparse image detected.")

            val segments =
                buildSparseSegments(
                    uri,
                    maxDownload
                )

            try {
                if (segments.isEmpty()) {
                    throw Exception("Sparse image contains no flashable chunks.")
                }

                appendOutput(
                    "\nFASTBOOT SPARSE: ${segments.size} download part(s) prepared."
                )

                segments.forEachIndexed { index, segment ->
                    appendOutput(
                        "\nFASTBOOT SPARSE PART ${index + 1}/${segments.size}: " +
                                "${segment.file.length()} bytes"
                    )

                    fastbootDownloadFileAndFlash(
                        connection,
                        input,
                        outputEndpoint,
                        segment.file,
                        segment.file.length(),
                        partition,
                        index + 1,
                        segments.size
                    )
                }
            } finally {
                segments.forEach {
                    try {
                        it.file.delete()
                    } catch (_: Exception) {
                    }
                }
            }

            return
        }

        if (size > maxDownload) {
            throw Exception(
                "Raw image is $size bytes, but the device accepts at most $maxDownload bytes per Fastboot download."
            )
        }

        fastbootDownloadUriAndFlash(
            connection,
            input,
            outputEndpoint,
            uri,
            size,
            partition
        )
    }

    private fun fastbootDownloadUriAndFlash(
        connection: UsbDeviceConnection,
        input: UsbEndpoint,
        outputEndpoint: UsbEndpoint,
        uri: Uri,
        size: Long,
        partition: String
    ) {
        fastbootWriteCommand(
            connection,
            outputEndpoint,
            "download:${size.toString(16).uppercase().padStart(8, '0')}"
        )

        expectFastbootDataSize(
            connection,
            input,
            size
        )

        contentResolver.openInputStream(uri)?.use { stream ->
            transferFastbootStream(
                connection,
                outputEndpoint,
                stream,
                size,
                "FASTBOOT DOWNLOAD"
            )
        } ?: throw Exception("Unable to open selected image.")

        finishFastbootDownloadAndFlash(
            connection,
            input,
            outputEndpoint,
            partition
        )
    }

    private fun fastbootDownloadFileAndFlash(
        connection: UsbDeviceConnection,
        input: UsbEndpoint,
        outputEndpoint: UsbEndpoint,
        file: File,
        size: Long,
        partition: String,
        partNumber: Int,
        partCount: Int
    ) {
        if (size <= 0L || size > 0xFFFF_FFFFL) {
            throw Exception("Invalid sparse part size: $size bytes.")
        }

        fastbootWriteCommand(
            connection,
            outputEndpoint,
            "download:${size.toString(16).uppercase().padStart(8, '0')}"
        )

        expectFastbootDataSize(
            connection,
            input,
            size
        )

        FileInputStream(file).use { stream ->
            transferFastbootStream(
                connection,
                outputEndpoint,
                stream,
                size,
                "FASTBOOT SPARSE $partNumber/$partCount"
            )
        }

        finishFastbootDownloadAndFlash(
            connection,
            input,
            outputEndpoint,
            partition
        )
    }

    private fun expectFastbootDataSize(
        connection: UsbDeviceConnection,
        input: UsbEndpoint,
        expectedSize: Long
    ) {
        val response = readFastbootResponseStrict(
            connection,
            input
        )

        if (!response.startsWith("DATA")) {
            throw Exception("Device rejected download: $response")
        }

        val accepted =
            response
                .removePrefix("DATA")
                .trim { it <= ' ' || it == '\u0000' }
                .take(8)
                .uppercase()

        val expectedHex =
            expectedSize
                .toString(16)
                .uppercase()
                .padStart(8, '0')

        appendOutput(
            "\nFASTBOOT DOWNLOAD SIZE: device=$accepted requested=$expectedHex"
        )

        if (accepted.length != 8 || accepted != expectedHex) {
            throw Exception(
                "Device accepted download size $accepted, expected $expectedHex."
            )
        }
    }

    private fun transferFastbootStream(
        connection: UsbDeviceConnection,
        outputEndpoint: UsbEndpoint,
        stream: InputStream,
        size: Long,
        label: String
    ) {
        val buffer = ByteArray(16 * 1024)
        var sentTotal = 0L
        var lastPercent = -1

        while (sentTotal < size) {
            val wanted =
                minOf(
                    buffer.size.toLong(),
                    size - sentTotal
                ).toInt()

            val count =
                stream.read(
                    buffer,
                    0,
                    wanted
                )

            if (count <= 0) {
                throw Exception("Unexpected end of image data.")
            }

            usbBulkWriteFully(
                connection,
                outputEndpoint,
                buffer,
                0,
                count,
                10000
            )

            sentTotal += count

            val percent =
                if (size > 0L) {
                    (sentTotal * 100L / size).toInt()
                } else {
                    100
                }

            if (
                percent != lastPercent &&
                (percent % 5 == 0 || sentTotal == size)
            ) {
                lastPercent = percent
                appendOutput(
                    "\n$label: $percent% ($sentTotal/$size bytes)"
                )
            }
        }
    }

    private fun finishFastbootDownloadAndFlash(
        connection: UsbDeviceConnection,
        input: UsbEndpoint,
        outputEndpoint: UsbEndpoint,
        partition: String
    ) {
        val transferResponse =
            readFastbootResponseStrict(
                connection,
                input,
                60000
            )

        if (transferResponse.startsWith("FAIL")) {
            throw Exception(
                "Device rejected image transfer: ${transferResponse.removePrefix("FAIL")}"
            )
        }

        if (!transferResponse.startsWith("OKAY")) {
            throw Exception(
                "Unexpected download response: $transferResponse"
            )
        }

        fastbootWriteCommand(
            connection,
            outputEndpoint,
            "flash:$partition"
        )

        val flashResponse =
            readFastbootResponseStrict(
                connection,
                input,
                60000
            )

        if (flashResponse.startsWith("FAIL")) {
            throw Exception(
                "Device rejected flash: ${flashResponse.removePrefix("FAIL")}"
            )
        }

        if (!flashResponse.startsWith("OKAY")) {
            throw Exception(
                "Unexpected flash response: $flashResponse"
            )
        }
    }

    private fun queryFastbootMaxDownloadSize(
        connection: UsbDeviceConnection,
        input: UsbEndpoint,
        outputEndpoint: UsbEndpoint
    ): Long {
        fastbootWriteCommand(
            connection,
            outputEndpoint,
            "getvar:max-download-size"
        )

        var value = ""
        val buffer = ByteArray(4096)

        while (true) {
            val count =
                connection.bulkTransfer(
                    input,
                    buffer,
                    buffer.size,
                    10000
                )

            if (count <= 0) {
                throw Exception(
                    "Timed out waiting for Fastboot max-download-size."
                )
            }

            val response =
                String(
                    buffer,
                    0,
                    count,
                    Charsets.US_ASCII
                )

            when {
                response.startsWith("INFO") -> {
                    val info =
                        response
                            .removePrefix("INFO")
                            .trim { it <= ' ' || it == '\u0000' }

                    if (info.isNotEmpty()) {
                        value = info
                        appendOutput("\nFASTBOOT INFO: $info")
                    }
                }

                response.startsWith("OKAY") -> {
                    return parseFastbootSize(value)
                }

                response.startsWith("FAIL") -> {
                    appendOutput(
                        "\nFASTBOOT: device did not report max-download-size; using protocol maximum. " +
                                response.removePrefix("FAIL")
                    )
                    return 0L
                }

                else -> {
                    throw Exception(
                        "Unexpected Fastboot getvar response: $response"
                    )
                }
            }
        }
    }

    private fun parseFastbootSize(value: String): Long {
        val text = value.trim()
        if (text.isEmpty()) return 0L

        return try {
            when {
                text.startsWith("0x", true) ->
                    text.substring(2).toLong(16)

                text.all { it.isDigit() } ->
                    text.toLong()

                else ->
                    text.toLong(16)
            }
        } catch (_: Exception) {
            0L
        }
    }

    private data class SparseSegment(
        val file: File
    )

    private companion object {
        const val SPARSE_MAGIC = 0xED26FF3A.toInt()
        const val SPARSE_CHUNK_RAW = 0xCAC1
        const val SPARSE_CHUNK_FILL = 0xCAC2
        const val SPARSE_CHUNK_DONT_CARE = 0xCAC3
        const val SPARSE_CHUNK_CRC32 = 0xCAC4
        const val SPARSE_HEADER_SIZE = 28
        const val SPARSE_CHUNK_HEADER_SIZE = 12
    }

    private fun isSparseImage(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(4)
                var offset = 0
                while (offset < 4) {
                    val count = input.read(header, offset, 4 - offset)
                    if (count <= 0) return@use false
                    offset += count
                }

                ByteBuffer.wrap(header)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int == SPARSE_MAGIC
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun buildSparseSegments(
        uri: Uri,
        maxDownloadSize: Long
    ): List<SparseSegment> {
        if (maxDownloadSize > 0xFFFF_FFFFL) {
            throw Exception("Invalid Fastboot sparse download limit: $maxDownloadSize")
        }

        contentResolver.openInputStream(uri)?.use { input ->
            val headerBytes = ByteArray(SPARSE_HEADER_SIZE)
            readFully(input, headerBytes)

            val header =
                ByteBuffer.wrap(headerBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)

            val magic = header.int
            val majorVersion = header.short.toInt() and 0xFFFF
            header.short // minor version
            val fileHeaderSize = header.short.toInt() and 0xFFFF
            val chunkHeaderSize = header.short.toInt() and 0xFFFF
            val blockSize = header.int.toLong() and 0xFFFF_FFFFL
            val totalBlocks = header.int.toLong() and 0xFFFF_FFFFL
            val totalChunks = header.int.toLong() and 0xFFFF_FFFFL
            header.int // image checksum

            if (magic != SPARSE_MAGIC) {
                throw Exception("Not an Android sparse image.")
            }
            if (majorVersion != 1) {
                throw Exception("Unsupported sparse image major version: $majorVersion")
            }
            if (fileHeaderSize < SPARSE_HEADER_SIZE ||
                chunkHeaderSize < SPARSE_CHUNK_HEADER_SIZE
            ) {
                throw Exception("Invalid Android sparse header size.")
            }
            if (blockSize <= 0L || blockSize > Int.MAX_VALUE.toLong()) {
                throw Exception("Invalid sparse block size: $blockSize")
            }

            if (fileHeaderSize > SPARSE_HEADER_SIZE) {
                skipFully(
                    input,
                    fileHeaderSize.toLong() - SPARSE_HEADER_SIZE.toLong()
                )
            }

            if (totalChunks > Int.MAX_VALUE.toLong()) {
                throw Exception("Sparse image has too many chunks: $totalChunks")
            }

            val tempFiles = mutableListOf<SparseSegment>()
            var currentFile: RandomAccessFile? = null
            var currentTemp: File? = null
            var currentPhysicalSize = 0L
            var currentChunkCount = 0L
            var currentLogicalEndBlock = 0L
            var logicalBlock = 0L

            fun closeCurrentSegment() {
                val raf = currentFile ?: return
                val file = currentTemp ?: return

                try {
                    raf.seek(0L)
                    writeLeInt(raf, SPARSE_MAGIC)
                    writeLeShort(raf, 1)
                    writeLeShort(raf, 0)
                    writeLeShort(raf, SPARSE_HEADER_SIZE)
                    writeLeShort(raf, SPARSE_CHUNK_HEADER_SIZE)
                    writeLeInt(raf, blockSize.toInt())
                    writeLeInt(raf, currentLogicalEndBlock.toInt())
                    writeLeInt(raf, currentChunkCount.toInt())
                    writeLeInt(raf, 0)
                    raf.close()
                    tempFiles.add(SparseSegment(file))
                } finally {
                    currentFile = null
                    currentTemp = null
                    currentPhysicalSize = 0L
                    currentChunkCount = 0L
                }
            }

            fun startSegment(startBlock: Long) {
                val file =
                    File.createTempFile(
                        "rootrealm-sparse-",
                        ".img",
                        cacheDir
                    )

                val raf = RandomAccessFile(file, "rw")
                raf.setLength(SPARSE_HEADER_SIZE.toLong())

                currentFile = raf
                currentTemp = file
                currentPhysicalSize = SPARSE_HEADER_SIZE.toLong()
                currentChunkCount = 0L
                currentLogicalEndBlock = startBlock

                if (startBlock > 0L) {
                    writeSparseChunkHeader(
                        raf,
                        SPARSE_CHUNK_DONT_CARE,
                        startBlock,
                        0L
                    )
                    currentPhysicalSize += SPARSE_CHUNK_HEADER_SIZE
                    currentChunkCount++
                }
            }

            fun ensureSegmentStarted() {
                if (currentFile == null) {
                    startSegment(logicalBlock)
                }
            }

            fun ensureCapacity(extraBytes: Long) {
                if (currentFile == null) {
                    startSegment(logicalBlock)
                }

                if (
                    currentPhysicalSize + extraBytes > maxDownloadSize &&
                    currentChunkCount > 0L
                ) {
                    closeCurrentSegment()
                    startSegment(logicalBlock)
                }

                if (
                    currentPhysicalSize + extraBytes > maxDownloadSize
                ) {
                    throw Exception(
                        "Sparse chunk cannot fit within Fastboot max-download-size ($maxDownloadSize bytes)."
                    )
                }
            }

            repeat(totalChunks.toInt()) {
                val chunkHeader = ByteArray(SPARSE_CHUNK_HEADER_SIZE)
                readFully(input, chunkHeader)

                val ch =
                    ByteBuffer.wrap(chunkHeader)
                        .order(ByteOrder.LITTLE_ENDIAN)

                val chunkType = ch.short.toInt() and 0xFFFF
                ch.short // reserved
                val chunkBlocks = ch.int.toLong() and 0xFFFF_FFFFL
                val totalChunkBytes = ch.int.toLong() and 0xFFFF_FFFFL

                if (chunkHeaderSize > SPARSE_CHUNK_HEADER_SIZE) {
                    skipFully(
                        input,
                        chunkHeaderSize.toLong() - SPARSE_CHUNK_HEADER_SIZE.toLong()
                    )
                }

                when (chunkType) {
                    SPARSE_CHUNK_RAW -> {
                        val expectedBytes =
                            chunkBlocks * blockSize +
                                    SPARSE_CHUNK_HEADER_SIZE

                        if (totalChunkBytes != expectedBytes) {
                            throw Exception("Invalid sparse RAW chunk size.")
                        }

                        var remainingBlocks = chunkBlocks

                        while (remainingBlocks > 0L) {
                            ensureSegmentStarted()

                            val available =
                                maxDownloadSize -
                                        currentPhysicalSize -
                                        SPARSE_CHUNK_HEADER_SIZE

                            val blocksThatFit =
                                available / blockSize

                            if (blocksThatFit <= 0L) {
                                closeCurrentSegment()
                                startSegment(logicalBlock)
                                continue
                            }

                            val takeBlocks =
                                minOf(
                                    remainingBlocks,
                                    blocksThatFit
                                )

                            val payloadBytes =
                                takeBlocks * blockSize

                            writeSparseChunkHeader(
                                currentFile!!,
                                SPARSE_CHUNK_RAW,
                                takeBlocks,
                                SPARSE_CHUNK_HEADER_SIZE + payloadBytes
                            )

                            copyInputToFile(
                                input,
                                currentFile!!,
                                payloadBytes
                            )

                            currentPhysicalSize +=
                                SPARSE_CHUNK_HEADER_SIZE + payloadBytes
                            currentChunkCount++
                            logicalBlock += takeBlocks
                            currentLogicalEndBlock = logicalBlock
                            remainingBlocks -= takeBlocks
                        }
                    }

                    SPARSE_CHUNK_FILL -> {
                        if (chunkBlocks == 0L || totalChunkBytes != 16L) {
                            throw Exception("Invalid sparse FILL chunk.")
                        }

                        val fillValue = ByteArray(4)
                        readFully(input, fillValue)

                        var remainingBlocks = chunkBlocks

                        while (remainingBlocks > 0L) {
                            ensureSegmentStarted()

                            val available =
                                maxDownloadSize -
                                        currentPhysicalSize -
                                        SPARSE_CHUNK_HEADER_SIZE

                            val maxBlocks =
                                if (available >= 4L) {
                                    remainingBlocks
                                } else {
                                    0L
                                }

                            if (maxBlocks <= 0L) {
                                closeCurrentSegment()
                                startSegment(logicalBlock)
                                continue
                            }

                            val takeBlocks =
                                minOf(
                                    remainingBlocks,
                                    maxBlocks
                                )

                            writeSparseChunkHeader(
                                currentFile!!,
                                SPARSE_CHUNK_FILL,
                                takeBlocks,
                                16L
                            )
                            currentFile!!.write(fillValue)

                            currentPhysicalSize +=
                                SPARSE_CHUNK_HEADER_SIZE + 4L
                            currentChunkCount++
                            logicalBlock += takeBlocks
                            currentLogicalEndBlock = logicalBlock
                            remainingBlocks -= takeBlocks
                        }
                    }

                    SPARSE_CHUNK_DONT_CARE -> {
                        if (totalChunkBytes != SPARSE_CHUNK_HEADER_SIZE.toLong()) {
                            throw Exception("Invalid sparse DONT_CARE chunk.")
                        }

                        ensureSegmentStarted()

                        writeSparseChunkHeader(
                            currentFile!!,
                            SPARSE_CHUNK_DONT_CARE,
                            chunkBlocks,
                            SPARSE_CHUNK_HEADER_SIZE.toLong()
                        )
                        currentPhysicalSize += SPARSE_CHUNK_HEADER_SIZE
                        currentChunkCount++
                        logicalBlock += chunkBlocks
                        currentLogicalEndBlock = logicalBlock
                    }

                    SPARSE_CHUNK_CRC32 -> {
                        // The CRC is for the original complete sparse stream.
                        // Once the image is resparsed into multiple downloads,
                        // the original CRC is no longer valid, so omit it.
                        if (totalChunkBytes < 16L) {
                            throw Exception("Invalid sparse CRC32 chunk.")
                        }
                        skipFully(
                            input,
                            totalChunkBytes - SPARSE_CHUNK_HEADER_SIZE.toLong()
                        )
                    }

                    else -> {
                        throw Exception(
                            "Unsupported sparse chunk type: 0x" +
                                    chunkType.toString(16)
                        )
                    }
                }
            }

            if (logicalBlock != totalBlocks) {
                throw Exception(
                    "Sparse image block count mismatch: parsed=$logicalBlock header=$totalBlocks"
                )
            }

            if (currentFile != null) {
                closeCurrentSegment()
            }

            return tempFiles
        } ?: throw Exception("Unable to open selected sparse image.")
    }

    private fun writeSparseChunkHeader(
        file: RandomAccessFile,
        type: Int,
        blocks: Long,
        totalChunkBytes: Long
    ) {
        writeLeShort(file, type)
        writeLeShort(file, 0)
        writeLeInt(file, blocks.toInt())
        writeLeInt(file, totalChunkBytes.toInt())
    }

    private fun writeLeShort(
        file: RandomAccessFile,
        value: Int
    ) {
        file.write(value and 0xFF)
        file.write((value ushr 8) and 0xFF)
    }

    private fun writeLeInt(
        file: RandomAccessFile,
        value: Int
    ) {
        file.write(value and 0xFF)
        file.write((value ushr 8) and 0xFF)
        file.write((value ushr 16) and 0xFF)
        file.write((value ushr 24) and 0xFF)
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray
    ) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(
                buffer,
                offset,
                buffer.size - offset
            )
            if (count <= 0) {
                throw Exception("Unexpected end of sparse image.")
            }
            offset += count
        }
    }

    private fun skipFully(
        input: InputStream,
        bytes: Long
    ) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }

            if (input.read() < 0) {
                throw Exception("Unexpected end of sparse image.")
            }
            remaining--
        }
    }

    private fun copyInputToFile(
        input: InputStream,
        output: RandomAccessFile,
        bytes: Long
    ) {
        val buffer = ByteArray(64 * 1024)
        var remaining = bytes

        while (remaining > 0L) {
            val wanted =
                minOf(
                    buffer.size.toLong(),
                    remaining
                ).toInt()

            val count =
                input.read(
                    buffer,
                    0,
                    wanted
                )

            if (count <= 0) {
                throw Exception("Unexpected end of sparse RAW chunk.")
            }

            output.write(
                buffer,
                0,
                count
            )
            remaining -= count
        }
    }

    private fun fastbootWriteCommand(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        command: String
    ) {
        val bytes = command.toByteArray(Charsets.US_ASCII)
        usbBulkWriteFully(connection, endpoint, bytes, 0, bytes.size, 5000)
    }

    private fun readFastbootResponseStrict(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        timeoutMs: Int = 10000
    ): String {
        val buffer = ByteArray(4096)
        while (true) {
            val count = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
            if (count <= 0) throw Exception("Timed out waiting for Fastboot response.")
            val part = String(buffer, 0, count, Charsets.US_ASCII)
            when {
                part.startsWith("INFO") -> {
                    appendOutput("\nFASTBOOT INFO: ${part.removePrefix("INFO")}")
                }
                part.startsWith("OKAY") || part.startsWith("FAIL") || part.startsWith("DATA") -> {
                    return part
                }
                else -> throw Exception("Unexpected Fastboot response: $part")
            }
        }
    }

    private fun usbBulkWriteFully(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        data: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ) {
        var written = 0
        while (written < length) {
            val count = connection.bulkTransfer(
                endpoint,
                data,
                offset + written,
                length - written,
                timeoutMs
            )
            if (count <= 0) throw Exception("USB transfer failed.")
            written += count
        }
    }

    private fun copyStream(
        input: InputStream,
        output: java.io.OutputStream
    ) {

        val buffer =
            ByteArray(64 * 1024)

        while (true) {

            val count =
                input.read(buffer)

            if (count == -1) {
                break
            }

            output.write(
                buffer,
                0,
                count
            )
        }

        output.flush()
    }

    private fun refreshUsbDevices() {

        val devices = usbManager.deviceList

        if (devices.isEmpty()) {

            selectedDevice = null
            selectedUsbMode = null

            deviceStatus.text =
                "● NO USB DEVICE"

            deviceStatus.setTextColor(
                secondaryColor
            )

            deviceInfo.text =
                "Connect an Android device using USB OTG."

            // Click-triggered, outside onCreate/onResume: the global
            // engine won't revisit this TextView on its own, so ask it to.
            RootRealmGlobalTheme.applyTheme(this)

            appendOutput(
                "\nNo USB devices detected.\n"
            )

            return
        }

        appendOutput(
            "\nUSB devices detected: ${devices.size}\n"
        )

        var adbDevice: UsbDevice? = null
        var fastbootDevice: UsbDevice? = null

        for (device in devices.values) {
            appendOutput(
                "Device: ${device.deviceName}\n" +
                        "VID: ${hex(device.vendorId)}\n" +
                        "PID: ${hex(device.productId)}\n"
            )

            // Detect each state independently. Do not combine these
            // checks into one generic Android-device condition.
            if (adbDevice == null && isAdbDevice(device)) {
                adbDevice = device
            }

            if (fastbootDevice == null && isFastbootDevice(device)) {
                fastbootDevice = device
            }
        }

        // If both somehow exist at the same time, prefer the ADB device
        // for the current selection. Each request is still mode-specific.
        val device: UsbDevice?
        val mode: UsbMode?

        if (adbDevice != null) {
            device = adbDevice
            mode = UsbMode.ADB
        } else if (fastbootDevice != null) {
            device = fastbootDevice
            mode = UsbMode.FASTBOOT
        } else {
            device = null
            mode = null
        }

        if (device == null || mode == null) {
            selectedDevice = null
            selectedUsbMode = null

            deviceStatus.text =
                "● USB DEVICE DETECTED"

            deviceStatus.setTextColor(
                secondaryColor
            )

            deviceInfo.text =
                "USB device found, but it does not appear to be an ADB or Fastboot device."

            RootRealmGlobalTheme.applyTheme(this)

            return
        }

        selectedDevice = device
        selectedUsbMode = mode

        inspectDevice(device)

        if (usbManager.hasPermission(device)) {
            appendOutput(
                "${mode.name} USB permission already granted.\n"
            )
        } else {
            requestPermissionFor(device, mode)
        }
    }

    private fun requestPermission() {

        val device = selectedDevice
        val mode = selectedUsbMode

        if (device == null || mode == null) {
            refreshUsbDevices()
            if (selectedDevice == null || selectedUsbMode == null) {
                toast("No ADB or Fastboot device detected.")
            }
            return
        }

        requestPermissionFor(device, mode)
    }

    private fun requestPermissionFor(
        device: UsbDevice,
        mode: UsbMode
    ) {

        // Refuse to send a request if the device has changed state.
        val correctMode = when (mode) {
            UsbMode.ADB -> isAdbDevice(device)
            UsbMode.FASTBOOT -> isFastbootDevice(device)
        }

        if (!correctMode) {
            appendOutput(
                "${mode.name} permission request cancelled: device is no longer in ${mode.name} mode.\n"
            )
            refreshUsbDevices()
            return
        }

        selectedDevice = device
        selectedUsbMode = mode

        if (usbManager.hasPermission(device)) {
            appendOutput(
                "${mode.name} USB permission already granted.\n"
            )
            inspectDevice(device)
            return
        }

        val action = when (mode) {
            UsbMode.ADB -> ACTION_ADB_USB_PERMISSION
            UsbMode.FASTBOOT -> ACTION_FASTBOOT_USB_PERMISSION
        }

        val requestCode = when (mode) {
            UsbMode.ADB -> 1001
            UsbMode.FASTBOOT -> 1002
        }

        val pendingIntent =
            PendingIntent.getBroadcast(
                this,
                requestCode,
                Intent(action).apply {
                    setPackage(packageName)
                },
                // UsbManager adds EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED to the
                // result PendingIntent. On Android 12+ this PendingIntent must
                // be mutable so the system can attach those result extras.
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_MUTABLE
            )

        appendOutput(
            "Requesting ${mode.name} USB permission for ${device.deviceName}...\n"
        )

        usbManager.requestPermission(
            device,
            pendingIntent
        )
    }

    private fun inspectDevice(
        device: UsbDevice
    ) {

        val mode =
            when {

                isFastbootDevice(device) ->
                    "FASTBOOT"

                isAdbDevice(device) ->
                    "ADB"

                else ->
                    "UNKNOWN"
            }

        deviceStatus.text =
            "● $mode DEVICE CONNECTED"

        deviceStatus.setTextColor(
            if (mode == "UNKNOWN")
                secondaryColor
            else
                primaryColor
        )

        deviceInfo.text =
            buildString {

                append(
                    "Name: ${device.deviceName}\n"
                )

                append(
                    "Vendor ID: ${hex(device.vendorId)}\n"
                )

                append(
                    "Product ID: ${hex(device.productId)}\n"
                )

                append(
                    "Interfaces: " +
                            "${device.interfaceCount}\n"
                )

                append(
                    "Mode: $mode"
                )
            }

        RootRealmGlobalTheme.applyTheme(this)

        animateDeviceConnected()

        appendOutput(
            "Detected $mode device.\n"
        )
    }

    private fun isAdbDevice(
        device: UsbDevice
    ): Boolean {

        for (
            i in 0 until device.interfaceCount
        ) {

            val intf =
                device.getInterface(i)

            if (
                intf.interfaceClass ==
                        UsbConstants.USB_CLASS_VENDOR_SPEC &&
                intf.interfaceSubclass == 0x42 &&
                intf.interfaceProtocol == 0x01
            ) {
                return true
            }
        }

        return false
    }

    private fun isFastbootDevice(
        device: UsbDevice
    ): Boolean {

        for (
            i in 0 until device.interfaceCount
        ) {

            val intf =
                device.getInterface(i)

            if (
                intf.interfaceClass ==
                        UsbConstants.USB_CLASS_VENDOR_SPEC &&
                intf.interfaceSubclass == 0x42 &&
                intf.interfaceProtocol == 0x03
            ) {
                return true
            }
        }

        return false
    }

    private fun findFastbootInterface(
        device: UsbDevice
    ): UsbInterface? {

        for (
            i in 0 until device.interfaceCount
        ) {

            val intf =
                device.getInterface(i)

            if (
                intf.interfaceClass ==
                        UsbConstants.USB_CLASS_VENDOR_SPEC &&
                intf.interfaceSubclass == 0x42 &&
                intf.interfaceProtocol == 0x03
            ) {
                return intf
            }
        }

        return null
    }

    private fun getBulkEndpoints(
        intf: UsbInterface
    ): Pair<UsbEndpoint, UsbEndpoint>? {

        var input: UsbEndpoint? = null
        var outputEndpoint: UsbEndpoint? = null

        for (
            i in 0 until intf.endpointCount
        ) {

            val endpoint =
                intf.getEndpoint(i)

            if (
                endpoint.type ==
                UsbConstants.USB_ENDPOINT_XFER_BULK
            ) {

                if (
                    endpoint.direction ==
                    UsbConstants.USB_DIR_IN
                ) {
                    input = endpoint
                } else {
                    outputEndpoint = endpoint
                }
            }
        }

        if (
            input == null ||
            outputEndpoint == null
        ) {
            return null
        }

        return Pair(
            input,
            outputEndpoint
        )
    }

    private fun runFastbootGetVars() {

        runFastbootCommand(
            "getvar:all"
        )
    }

    private fun runFastbootCommand(
        command: String
    ) {

        val device =
            selectedDevice

        if (device == null) {

            toast(
                "No USB device selected."
            )

            return
        }

        if (!isFastbootDevice(device)) {

            toast(
                "Device is not in Fastboot mode."
            )

            appendOutput(
                "\nFastboot command rejected: " +
                        "device is not in Fastboot mode.\n"
            )

            return
        }

        appendOutput(
            "\n> $command\n"
        )

        executor.execute {

            setTaskRunning(true)

            var connection:
                    UsbDeviceConnection? = null

            try {

                val intf =
                    findFastbootInterface(device)
                        ?: throw Exception(
                            "Fastboot USB interface not found."
                        )

                val endpoints =
                    getBulkEndpoints(intf)
                        ?: throw Exception(
                            "Fastboot bulk endpoints not found."
                        )

                connection =
                    usbManager.openDevice(device)
                        ?: throw Exception(
                            "Unable to open USB device."
                        )

                if (
                    !connection.claimInterface(
                        intf,
                        true
                    )
                ) {
                    throw Exception(
                        "Unable to claim Fastboot interface."
                    )
                }

                val bytes =
                    command.toByteArray(
                        Charsets.US_ASCII
                    )

                val sent =
                    connection.bulkTransfer(
                        endpoints.second,
                        bytes,
                        bytes.size,
                        3000
                    )

                if (sent < 0) {

                    throw Exception(
                        "Failed to send Fastboot command."
                    )
                }

                readFastbootResponse(
                    connection,
                    endpoints.first
                )

                connection.releaseInterface(
                    intf
                )

            } catch (e: Exception) {

                appendOutput(
                    "ERROR: ${e.message}\n"
                )

            } finally {

                try {
                    connection?.close()
                } catch (_: Exception) {
                }

                setTaskRunning(false)
            }
        }
    }

    private fun readFastbootResponse(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint
    ) {

        val buffer =
            ByteArray(4096)

        while (true) {

            val count =
                connection.bulkTransfer(
                    endpoint,
                    buffer,
                    buffer.size,
                    5000
                )

            if (count <= 0) {
                break
            }

            val response =
                String(
                    buffer,
                    0,
                    count,
                    Charsets.US_ASCII
                )

            appendOutput(response)

            if (
                response.startsWith("OKAY") ||
                response.startsWith("FAIL")
            ) {
                break
            }
        }
    }

    private fun startLogcat() {

        val device =
            selectedDevice

        if (device == null) {

            toast(
                "No USB device selected."
            )

            return
        }

        if (!isAdbDevice(device)) {

            toast(
                "Device is not in ADB mode."
            )

            appendOutput(
                "\nLogcat rejected: device is not in ADB mode.\n"
            )

            return
        }

        if (activeAdbConnection?.running == true) {

            toast(
                "Logcat is already running."
            )

            return
        }

        val filter =
            logcatFilterInput
                .text
                .toString()
                .trim()

        logcatOutput.text = ""

        appendLogcatStatus(
            "Connecting to device for logcat…\n"
        )

        logcatExecutor.execute {

            try {

                if (adbCrypto == null) {
                    adbCrypto =
                        AdbCrypto(
                            applicationContext
                        )
                }

                val connection =
                    AdbConnection(
                        context = applicationContext,
                        usbManager = usbManager,
                        device = device,
                        crypto = adbCrypto!!,
                        onData = { text ->
                            appendLogcat(text)
                        },
                        onStatus = { text ->
                            appendLogcatStatus(
                                "\n$text\n"
                            )
                        }
                    )

                activeAdbConnection =
                    connection

                connection.connectAndStreamLogcat(
                    filter
                )

            } catch (e: Exception) {

                appendLogcatStatus(
                    "\nLOGCAT ERROR: ${e.message}\n"
                )

            } finally {

                handler.post {
                    toast(
                        "Logcat stopped."
                    )
                }
            }
        }
    }

    private fun stopLogcat() {

        val connection =
            activeAdbConnection

        if (connection == null ||
            !connection.running
        ) {

            toast(
                "Logcat is not running."
            )

            return
        }

        connection.stop()

        appendLogcatStatus(
            "\nStopping logcat…\n"
        )
    }

    private fun clearLogcat() {

        if (::logcatOutput.isInitialized) {
            logcatOutput.text = ""
        }
    }

    private fun saveLogcatToFile() {

        if (!::logcatOutput.isInitialized) {
            return
        }

        val text =
            logcatOutput.text.toString()

        if (text.isBlank()) {

            toast(
                "No logcat output to save yet."
            )

            return
        }

        val bytes =
            text.toByteArray(Charsets.UTF_8)

        executor.execute {

            setTaskRunning(true)

            try {

                val fileName =
                    "logcat_${System.currentTimeMillis()}.txt"

                val savedBytes =
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.Q
                    ) {
                        saveViaMediaStore(
                            fileName,
                            bytes
                        )
                    } else {
                        saveViaLegacyFile(
                            fileName,
                            bytes
                        )
                    }

                if (savedBytes != bytes.size) {

                    throw Exception(
                        "Wrote $savedBytes of " +
                                "${bytes.size} bytes; " +
                                "file may be incomplete."
                    )
                }

                appendOutput(
                    "\nLogcat saved (" +
                            "$savedBytes bytes) as " +
                            "$fileName\n"
                )

                handler.post {
                    toast(
                        "Logcat saved: $fileName"
                    )
                }

            } catch (e: Exception) {

                appendOutput(
                    "\nSAVE LOGCAT ERROR: ${e.message}\n"
                )

                handler.post {
                    toast(
                        "Failed to save logcat."
                    )
                }
            } finally {
                setTaskRunning(false)
            }
        }
    }

    /**
     * Saves into the public Downloads collection via MediaStore
     * (API 29+). The row is created with IS_PENDING=1 so it is not
     * treated as a finished/visible file until the write is flushed
     * and verified, and it is deleted again if anything goes wrong —
     * this is what prevents a half-written or blank file from being
     * left behind in Downloads.
     */
    private fun saveViaMediaStore(
        fileName: String,
        bytes: ByteArray
    ): Int {

        val values =
            ContentValues().apply {

                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    fileName
                )

                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    "text/plain"
                )

                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Download/"
                )

                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    1
                )
            }

        val uri =
            contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw Exception(
                "Unable to create output file."
            )

        try {

            val written =
                contentResolver
                    .openOutputStream(uri, "w")
                    ?.use { out ->
                        out.write(bytes)
                        out.flush()
                        bytes.size
                    }
                    ?: throw Exception(
                        "Unable to open output stream."
                    )

            val clearPending =
                ContentValues().apply {

                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        0
                    )
                }

            contentResolver.update(
                uri,
                clearPending,
                null,
                null
            )

            return written

        } catch (e: Exception) {

            // Don't leave a broken/blank row behind in Downloads.
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }

            throw e
        }
    }

    private fun saveViaLegacyFile(
        fileName: String,
        bytes: ByteArray
    ): Int {

        val dir =
            getExternalFilesDir(
                Environment.DIRECTORY_DOWNLOADS
            ) ?: filesDir

        val file =
            File(dir, fileName)

        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }

        return file.length().toInt()
    }

    private fun appendLogcat(
        text: String
    ) {
        // Only touch the buffer (cheap, no UI work) from whatever thread
        // this was called on. The actual TextView update is batched below.
        synchronized(logcatLock) {
            logcatBuffer.append(text)
        }
        scheduleLogcatFlush()
    }

    // Coalesces many appendLogcat() calls into one TextView update every
    // ~150ms instead of one per USB packet, which is what was freezing the
    // UI under real logcat volume (each append + trim was a full relayout).
    private fun scheduleLogcatFlush() {
        if (logcatFlushScheduled) return
        logcatFlushScheduled = true
        handler.postDelayed({
            flushLogcatBuffer()
            logcatFlushScheduled = false
        }, 150)
    }

    private fun flushLogcatBuffer() {

        if (!::logcatOutput.isInitialized) {
            return
        }

        val chunk = synchronized(logcatLock) {
            if (logcatBuffer.isEmpty()) return
            val s = logcatBuffer.toString()
            logcatBuffer.setLength(0)
            s
        }

        logcatOutput.append(chunk)

        val maxChars = 200_000
        val current = logcatOutput.text

        // Hysteresis: only trim once meaningfully over the cap, and trim
        // back down to maxChars in one shot, so trimming (a full relayout)
        // doesn't happen on nearly every flush once the buffer is near-full.
        if (current.length > (maxChars * 1.2).toInt()) {

            logcatOutput.text =
                current.subSequence(
                    current.length - maxChars,
                    current.length
                )
        }

        if (::logcatScrollView.isInitialized) {

            logcatScrollView.post {
                logcatScrollView.fullScroll(
                    View.FOCUS_DOWN
                )
            }
        }
    }

    private fun appendLogcatStatus(
        text: String
    ) {
        appendLogcat(text)
    }

    private fun appendOutput(
        text: String
    ) {

        handler.post {

            if (::output.isInitialized) {
                output.append(text)
            }
        }
    }

    // Pulses the OUTPUT card while at least one background task (sideload,
    // reboot, flash, fastboot command, logcat save...) is running, so the
    // user can see something is in progress even if OUTPUT text is static
    // for a moment. Safe to call from any thread; also safe to call from
    // overlapping tasks since it just counts how many are active.
    private fun setTaskRunning(running: Boolean) {

        handler.post {

            if (!::outputCard.isInitialized) {
                return@post
            }

            runningTaskCount =
                (runningTaskCount + if (running) 1 else -1)
                    .coerceAtLeast(0)

            if (runningTaskCount > 0) {

                if (taskPulseAnimator?.isRunning == true) {
                    return@post
                }

                taskPulseAnimator =
                    ValueAnimator.ofFloat(1f, 0.45f).apply {
                        duration = 550
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener { anim ->
                            outputCard.alpha =
                                anim.animatedValue as Float
                        }
                        start()
                    }

            } else {

                taskPulseAnimator?.cancel()
                taskPulseAnimator = null

                outputCard.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
        }
    }

    // A quick pop/bounce on the status card whenever a device is detected,
    // so a fresh connection is obviously noticeable rather than just a
    // silent text change.
    private fun animateDeviceConnected() {

        handler.post {

            if (!::statusCard.isInitialized) {
                return@post
            }

            statusCard.animate().cancel()

            statusCard.apply {
                scaleX = 0.9f
                scaleY = 0.9f
                alpha = 0.5f

                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
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
        }
    }

    private fun createSectionHeader(
        text: String
    ): TextView {

        return createText(
            text,
            13f,
            secondaryColor
        ).apply {

            typeface =
                Typeface.DEFAULT_BOLD

            letterSpacing =
                0.04f

            setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(4)
            )
        }
    }

    private fun createCard():
            LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(14)
            )

            background =
                rounded(
                    cardColor,
                    20
                )

            elevation =
                dp(2).toFloat()
        }
    }

    private fun createButton(
        text: String
    ): Button {

        return Button(this).apply {

            this.text = text
            textSize = 13f

            setTextColor(
                textColor
            )

            typeface =
                Typeface.DEFAULT_BOLD

            background =
                ripple(
                    rounded(
                        cardColor,
                        16
                    ),
                    primaryColor
                )

            stateListAnimator = null

            minHeight = 0
            minimumHeight = 0

            setSingleLine(true)

            ellipsize =
                android.text.TextUtils.TruncateAt.END

            setPadding(
                dp(12),
                0,
                dp(12),
                0
            )
        }
    }

    private fun rounded(
        color: Int,
        radius: Int
    ): GradientDrawable {

        val hsv =
            FloatArray(3)

        Color.colorToHSV(
            color,
            hsv
        )

        val shaded =
            hsv.copyOf()

        shaded[2] =
            (shaded[2] * 0.8f)
                .coerceIn(0f, 1f)

        val bottomColor =
            Color.HSVToColor(
                shaded
            )

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                color,
                bottomColor
            )
        ).apply {

            cornerRadius =
                dp(radius).toFloat()

            setStroke(
                dp(1),
                borderColor
            )
        }
    }

    private fun ripple(
        base: Drawable,
        tint: Int
    ): Drawable {

        return RippleDrawable(
            ColorStateList.valueOf(
                colorWithAlpha(
                    tint,
                    70
                )
            ),
            base,
            null
        )
    }

    private fun colorWithAlpha(
        color: Int,
        alpha: Int
    ): Int {

        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun hex(
        value: Int
    ): String {

        return "0x" +
                value
                    .toString(16)
                    .uppercase()
                    .padStart(
                        4,
                        '0'
                    )
    }

    private fun toast(
        message: String
    ) {

        showToast(message, Toast.LENGTH_SHORT)
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                    resources.displayMetrics.density +
                    0.5f
        ).toInt()
    }
}
// =====================================================================
// Minimal ADB-over-USB protocol implementation.
//
// This talks directly to the ADB daemon on another Android device over
// USB (OTG), without needing a PC. It implements just enough of the
// protocol to authenticate and stream "adb logcat" output:
//   CNXN handshake -> AUTH (RSA challenge/response, falling back to
//   sending our public key so the user can approve "Allow USB
//   debugging" on the target device) -> OPEN a "shell:" stream running
//   logcat -> WRTE/OKAY flow control while streaming -> CLSE to stop.
// =====================================================================

private object AdbProtocolConstants {

    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541

    // Old-style ADB protocol version. Broadly compatible; avoids
    // requiring TLS (A_STLS) support.
    const val A_VERSION = 0x01000000

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val MAX_PAYLOAD = 4096

    fun syncId(value: String): Int {
        val b = value.toByteArray(Charsets.US_ASCII)
        require(b.size == 4) { "ADB SYNC id must be 4 bytes" }
        return (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
    }
}

/**
 * Generates/persists an RSA keypair for ADB auth and implements the two
 * crypto operations adbd requires from the host:
 *  - signing the random auth token it sends (raw PKCS#1 v1.5, SHA-1
 *    DigestInfo prefix, no actual hashing since the token already
 *    stands in for the digest)
 *  - encoding our public key in the little-endian "mincrypt"
 *    RSAPublicKey struct format that adbd expects, base64-encoded.
 */
private class AdbCrypto(context: Context) {

    private val privateKeyFile =
        File(context.filesDir, "rootrealm_adb_key.priv")

    private val publicKeyFile =
        File(context.filesDir, "rootrealm_adb_key.pub")

    lateinit var privateKey: PrivateKey
        private set

    lateinit var publicKey: RSAPublicKey
        private set

    init {

        if (privateKeyFile.exists() && publicKeyFile.exists()) {
            load()
        } else {
            generate()
        }
    }

    private fun generate() {

        val generator =
            KeyPairGenerator.getInstance("RSA")

        generator.initialize(
            RSAKeyGenParameterSpec(
                2048,
                RSAKeyGenParameterSpec.F4
            )
        )

        val pair = generator.generateKeyPair()

        privateKey = pair.private
        publicKey = pair.public as RSAPublicKey

        privateKeyFile.writeBytes(privateKey.encoded)
        publicKeyFile.writeBytes(publicKey.encoded)
    }

    private fun load() {

        val factory = KeyFactory.getInstance("RSA")

        privateKey =
            factory.generatePrivate(
                PKCS8EncodedKeySpec(
                    privateKeyFile.readBytes()
                )
            )

        publicKey =
            factory.generatePublic(
                X509EncodedKeySpec(
                    publicKeyFile.readBytes()
                )
            ) as RSAPublicKey
    }

    /**
     * Signs a 20-byte ADB auth token using this key. adbd sends a random
     * 20-byte token in place of a SHA-1 digest, so we wrap it with the
     * fixed SHA-1 DigestInfo ASN.1 prefix, PKCS#1 v1.5-pad it out to the
     * key size, and perform a raw RSA private-key transform.
     */
    fun sign(token: ByteArray): ByteArray {

        if (token.size != 20) {
            throw Exception("ADB auth token has an unexpected size.")
        }

        // adbd expects an RSA PKCS#1 v1.5 signature over SHA-1(token).
        // Using SHA1withRSA lets the Android crypto provider build the
        // correct DigestInfo + PKCS#1 padding. The previous implementation
        // incorrectly put the raw 20-byte token inside the DigestInfo.
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(privateKey)
        signature.update(token)
        return signature.sign()
    }

    /**
     * Builds the payload for an AUTH(RSAPUBLICKEY) packet: adbd's
     * mincrypt RSAPublicKey struct (len, n0inv, n[], rr[], exponent),
     * base64-encoded, with a trailing " name\0" comment as adbd expects.
     */
    fun getAdbPublicKeyPayload(): ByteArray {

        val numWords = 2048 / 32

        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = publicKey.modulus

        val rr =
            BigInteger.ONE
                .shiftLeft(numWords * 32 * 2)
                .mod(n)

        val rem = n.mod(r32)

        val n0inv =
            r32.subtract(rem.modInverse(r32)).mod(r32)

        val nWords = IntArray(numWords)
        val rrWords = IntArray(numWords)

        var nTmp = n
        var rrTmp = rr

        for (i in 0 until numWords) {

            nWords[i] = nTmp.mod(r32).toInt()
            nTmp = nTmp.divide(r32)

            rrWords[i] = rrTmp.mod(r32).toInt()
            rrTmp = rrTmp.divide(r32)
        }

        val buffer =
            ByteBuffer.allocate(4 + 4 + numWords * 4 + numWords * 4 + 4)
                .order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(numWords)
        buffer.putInt(n0inv.toInt())

        for (word in nWords) {
            buffer.putInt(word)
        }

        for (word in rrWords) {
            buffer.putInt(word)
        }

        buffer.putInt(publicKey.publicExponent.toInt())

        val encoded =
            Base64.getEncoder().encodeToString(buffer.array())

        return "$encoded rootrealm@device\u0000"
            .toByteArray(Charsets.US_ASCII)
    }
}

/**
 * A single ADB-over-USB session used to open a "shell:logcat" stream
 * and forward its output. Call [connectAndStreamLogcat] on a background
 * thread; call [stop] from any thread to end the session.
 */
private class AdbConnection(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val crypto: AdbCrypto,
    private val onData: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onProgress: (Long, Long) -> Unit = { _, _ -> }
) {

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    private data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    @Volatile
    var running = false
        private set

    private var streamOpen = false
    private val localId = 1
    private var remoteId = 0

    fun stop() {
        running = false
    }

    fun connectAndSideload(
        uri: Uri,
        totalBytes: Long,
        inputFactory: (Uri) -> InputStream
    ): String {
        running = true
        try {
            if (totalBytes <= 0L) {
                throw Exception("Unsupported sideload size: $totalBytes bytes.")
            }

            // Modern recovery/minadbd uses sideload-host:<size>:<block-size>.
            // The recovery side requests blocks from the host, rather than
            // accepting one-way raw WRTE packets. This is the protocol used by
            // current desktop adb.
            // AOSP uses a 64 KiB sideload-host block size. The ADB transport
            // below automatically splits that block into negotiated WRTE frames.
            val blockSize = 64 * 1024

            openTransport()

            try {
                openStream(
                    "sideload-host:$totalBytes:$blockSize"
                )
                onStatus(
                    "ADB: modern sideload-host stream opened; waiting for recovery requests..."
                )

                return runSideloadHost(
                    uri,
                    totalBytes,
                    blockSize,
                    inputFactory
                )
            } catch (modernError: Exception) {
                // The legacy sideload protocol uses a signed 32-bit package
                // size. Never fall back to it for packages larger than 2 GiB.
                if (totalBytes > Int.MAX_VALUE.toLong()) {
                    throw Exception(
                        "Modern sideload-host failed for a file larger than 2 GiB; legacy sideload cannot handle this size. ${modernError.message ?: "unknown error"}"
                    )
                }

                // Older recoveries do not expose sideload-host. Fall back to
                // the classic sideload:<size> streaming protocol only when
                // the package still fits the legacy signed 32-bit limit.
                onStatus(
                    "ADB: sideload-host unavailable (${modernError.message ?: "unknown error"}); trying classic sideload..."
                )
                cleanup()
                running = true
                openTransport()
                openStream("sideload:$totalBytes")
                onStatus(
                    "ADB: classic sideload stream opened; sending package..."
                )

                return runClassicSideload(
                    uri,
                    totalBytes,
                    inputFactory
                )
            }
        } finally {
            running = false
            cleanup()
        }
    }

    private fun runSideloadHost(
        uri: Uri,
        totalBytes: Long,
        blockSize: Int,
        inputFactory: (Uri) -> InputStream
    ): String {
        // Recovery may request blocks in any order. SAF InputStreams are not
        // guaranteed to support seeking, so spool the selected package once
        // into the app cache and serve requested blocks with RandomAccessFile.
        val tempFile =
            File.createTempFile(
                "rootrealm-sideload-",
                ".zip",
                context.cacheDir
            )

        try {
            inputFactory(uri).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(
                        output,
                        64 * 1024
                    )
                }
            }

            if (tempFile.length() != totalBytes) {
                throw Exception(
                    "Sideload package size changed while preparing transfer (${tempFile.length()}/$totalBytes bytes)."
                )
            }

            RandomAccessFile(
                tempFile,
                "r"
            ).use { file ->
                var transferred = 0L

                while (running) {
                    val packet =
                        readPacket(120_000)
                            ?: throw Exception(
                                "Timed out waiting for recovery sideload block request."
                            )

                    when (packet.command) {
                        AdbProtocolConstants.A_WRTE -> {
                            // Recovery sends an 8-byte ASCII decimal block
                            // number, or DONEDONE / FAILFAIL.
                            val request =
                                String(
                                    packet.payload,
                                    Charsets.US_ASCII
                                )

                            // ACK the recovery request before sending its data.
                            writePacket(
                                AdbProtocolConstants.A_OKAY,
                                localId,
                                remoteId,
                                ByteArray(0)
                            )

                            when {
                                request == "DONEDONE" -> {
                                    onProgress(
                                        totalBytes,
                                        totalBytes
                                    )
                                    return "OKAY"
                                }

                                request == "FAILFAIL" -> {
                                    throw Exception(
                                        "Recovery reported a sideload failure."
                                    )
                                }

                                request.length == 8 &&
                                        request.all { it.isDigit() } -> {
                                    val block =
                                        request.toLongOrNull()
                                            ?: throw Exception(
                                                "Invalid sideload block request: $request"
                                            )

                                    val offset =
                                        block * blockSize.toLong()

                                    if (offset < 0L || offset >= totalBytes) {
                                        throw Exception(
                                            "Recovery requested invalid sideload block $request."
                                        )
                                    }

                                    val toSend =
                                        minOf(
                                            blockSize.toLong(),
                                            totalBytes - offset
                                        ).toInt()

                                    file.seek(offset)

                                    val buffer =
                                        ByteArray(AdbProtocolConstants.MAX_PAYLOAD)

                                    var remaining = toSend
                                    var blockSent = 0

                                    while (remaining > 0 && running) {
                                        val count =
                                            minOf(
                                                remaining,
                                                buffer.size
                                            )

                                        file.readFully(
                                            buffer,
                                            0,
                                            count
                                        )

                                        // ADB v1 maxdata is 4096 in this app, so
                                        // send the 64 KiB recovery block as normal
                                        // ADB WRTE frames.
                                        sendAdbWrite(
                                            buffer.copyOf(count)
                                        )

                                        remaining -= count
                                        blockSent += count
                                    }

                                    transferred =
                                        maxOf(
                                            transferred,
                                            offset + blockSent
                                        )

                                    onProgress(
                                        transferred,
                                        totalBytes
                                    )
                                }

                                else -> {
                                    throw Exception(
                                        "Unexpected sideload request: $request"
                                    )
                                }
                            }
                        }

                        AdbProtocolConstants.A_CLSE -> {
                            throw Exception(
                                "Recovery closed the sideload stream."
                            )
                        }
                    }
                }
            }

            throw Exception("ADB sideload cancelled.")
        } finally {
            try {
                tempFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun runClassicSideload(
        uri: Uri,
        totalBytes: Long,
        inputFactory: (Uri) -> InputStream
    ): String {
        inputFactory(uri).use { input ->
            val buffer =
                ByteArray(AdbProtocolConstants.MAX_PAYLOAD)

            var sent = 0L

            while (running && sent < totalBytes) {
                val wanted =
                    minOf(
                        buffer.size.toLong(),
                        totalBytes - sent
                    ).toInt()

                val count =
                    input.read(
                        buffer,
                        0,
                        wanted
                    )

                if (count < 0) break
                if (count == 0) continue

                sendAdbWrite(
                    buffer.copyOf(count)
                )

                sent += count
                onProgress(sent, totalBytes)
            }

            if (!running) {
                throw Exception("ADB sideload cancelled.")
            }

            if (sent != totalBytes) {
                throw Exception(
                    "Sideload file could not be fully read ($sent/$totalBytes bytes)."
                )
            }
        }

        return readSideloadStatus()
    }

    private fun readSideloadStatus(): String {
        val deadline =
            System.currentTimeMillis() + 120_000L

        while (
            running &&
            System.currentTimeMillis() < deadline
        ) {
            val packet =
                readPacket(5000)
                    ?: continue

            when (packet.command) {
                AdbProtocolConstants.A_WRTE -> {
                    writePacket(
                        AdbProtocolConstants.A_OKAY,
                        localId,
                        remoteId,
                        ByteArray(0)
                    )

                    val text =
                        String(
                            packet.payload,
                            Charsets.US_ASCII
                        ).trim()

                    if (text.isNotEmpty()) {
                        return text
                    }
                }

                AdbProtocolConstants.A_CLSE -> {
                    throw Exception(
                        "Recovery closed the sideload stream without a status."
                    )
                }
            }
        }

        throw Exception(
            "Timed out waiting for recovery sideload result."
        )
    }

    fun connectAndReboot(command: String = "reboot") {
        running = true
        try {
            openTransport()
            openStream("shell:$command")
            waitForStreamClose()
        } finally {
            running = false
            cleanup()
        }
    }

    private fun openTransport() {
        val intf = findAdbInterface(device)
            ?: throw Exception("ADB USB interface not found.")
        val endpoints = getBulkEndpoints(intf)
            ?: throw Exception("ADB bulk endpoints not found.")
        epIn = endpoints.first
        epOut = endpoints.second
        if (!usbManager.hasPermission(device)) {
            throw Exception(
                "USB permission is not granted for the current ADB device. Reconnect or tap REQUEST USB PERMISSION."
            )
        }

        connection = usbManager.openDevice(device)
            ?: throw Exception(
                "Unable to open USB device. The target may have re-enumerated; refresh USB devices and grant permission again."
            )
        if (!connection!!.claimInterface(intf, true)) {
            throw Exception("Unable to claim ADB interface.")
        }
        claimedInterface = intf
        performHandshake()
    }

    private fun openStream(service: String) {
        val payload = "$service\u0000".toByteArray(Charsets.US_ASCII)
        writePacket(AdbProtocolConstants.A_OPEN, localId, 0, payload)
        val packet = readPacket(10000)
            ?: throw Exception("No response opening ADB service.")
        when (packet.command) {
            AdbProtocolConstants.A_OKAY -> {
                remoteId = packet.arg0
                streamOpen = true
            }
            AdbProtocolConstants.A_CLSE -> throw Exception("Device closed the ADB service.")
            else -> throw Exception("Unexpected response opening ADB service.")
        }
    }

    private fun sendSyncPacket(id: String, payload: ByteArray) {
        // ADB SYNC v1 packets are: 4-byte ID + 4-byte LE payload length + payload.
        val packet = ByteBuffer.allocate(8 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(AdbProtocolConstants.syncId(id))
            .putInt(payload.size)
            .put(payload)
            .array()

        sendAdbWrite(packet)
    }

    private fun sendAdbWrite(payload: ByteArray) {
        writePacket(AdbProtocolConstants.A_WRTE, localId, remoteId, payload)
        while (true) {
            val packet = readPacket(10000) ?: throw Exception("ADB write acknowledgement timed out.")
            when (packet.command) {
                AdbProtocolConstants.A_OKAY -> return
                AdbProtocolConstants.A_WRTE -> {
                    // A sync service can return a FAIL response inside an ADB WRTE
                    // while we are waiting for the stream READY/OKAY. Do not
                    // discard it, otherwise the host waits until timeout and
                    // hides the actual device-side error.
                    val inbound = packet.payload
                    writePacket(AdbProtocolConstants.A_OKAY, localId, remoteId, ByteArray(0))

                    if (inbound.size >= 8) {
                        val bb = ByteBuffer.wrap(inbound).order(ByteOrder.LITTLE_ENDIAN)
                        val syncId = bb.int
                        val syncLength = bb.int
                        if (syncId == AdbProtocolConstants.syncId("FAIL")) {
                            val safeLength = syncLength.coerceIn(0, inbound.size - 8)
                            val reason = String(inbound, 8, safeLength, Charsets.UTF_8)
                            throw Exception("ADB SYNC device error: ${reason.ifBlank { "unknown error" }}")
                        }
                    }
                }
                AdbProtocolConstants.A_CLSE -> throw Exception("Device closed the ADB stream.")
            }
        }
    }

    private fun readSyncResponse(): Pair<Int, ByteArray> {
        while (true) {
            val packet = readPacket(10000) ?: throw Exception("ADB SYNC response timed out.")
            when (packet.command) {
                AdbProtocolConstants.A_WRTE -> {
                    writePacket(AdbProtocolConstants.A_OKAY, localId, remoteId, ByteArray(0))
                    if (packet.payload.size < 8) throw Exception("Malformed ADB SYNC response.")
                    val bb = ByteBuffer.wrap(packet.payload).order(ByteOrder.LITTLE_ENDIAN)
                    val id = bb.int
                    val length = bb.int
                    if (length < 0 || length > packet.payload.size - 8) throw Exception("Malformed ADB SYNC response length.")
                    return Pair(id, packet.payload.copyOfRange(8, 8 + length))
                }
                AdbProtocolConstants.A_OKAY -> Unit
                AdbProtocolConstants.A_CLSE -> throw Exception("Device closed the ADB SYNC stream.")
            }
        }
    }

    private fun closeCurrentStream() {
        if (!streamOpen) return

        try {
            writePacket(
                AdbProtocolConstants.A_CLSE,
                localId,
                remoteId,
                ByteArray(0)
            )
            // Some adbd versions answer with CLSE, others may simply tear
            // the stream down when the next stream is opened. Do not block.
        } catch (_: Exception) {
        }

        streamOpen = false
        remoteId = 0
    }

    private fun readCommandOutput(timeoutMs: Int): String {
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (running && streamOpen && System.currentTimeMillis() < deadline) {
            val remaining =
                (deadline - System.currentTimeMillis()).coerceAtMost(1000L).toInt()
            if (remaining <= 0) break

            val packet = readPacket(remaining) ?: continue

            when (packet.command) {
                AdbProtocolConstants.A_WRTE -> {
                    if (packet.payload.isNotEmpty()) {
                        val text =
                            String(packet.payload, Charsets.UTF_8)
                        output.append(text)
                        onData(text)
                    }

                    writePacket(
                        AdbProtocolConstants.A_OKAY,
                        localId,
                        remoteId,
                        ByteArray(0)
                    )
                }

                AdbProtocolConstants.A_CLSE -> {
                    streamOpen = false
                }
            }
        }

        if (streamOpen) {
            throw Exception("Target command timed out.")
        }

        return output.toString()
    }

    private fun waitForStreamClose() {
        val deadline = System.currentTimeMillis() + 10_000L
        while (running && streamOpen && System.currentTimeMillis() < deadline) {
            val packet = readPacket(1000) ?: continue
            when (packet.command) {
                AdbProtocolConstants.A_WRTE -> {
                    writePacket(AdbProtocolConstants.A_OKAY, localId, remoteId, ByteArray(0))
                }
                AdbProtocolConstants.A_CLSE -> streamOpen = false
            }
        }
    }

    fun connectAndStreamLogcat(filter: String) {

        running = true

        try {

            val intf =
                findAdbInterface(device)
                    ?: throw Exception(
                        "ADB USB interface not found."
                    )

            val endpoints =
                getBulkEndpoints(intf)
                    ?: throw Exception(
                        "ADB bulk endpoints not found."
                    )

            epIn = endpoints.first
            epOut = endpoints.second

            val conn =
                usbManager.openDevice(device)
                    ?: throw Exception(
                        "Unable to open USB device."
                    )

            connection = conn

            if (!conn.claimInterface(intf, true)) {
                throw Exception(
                    "Unable to claim ADB interface."
                )
            }

            claimedInterface = intf

            performHandshake()

            val command =
                if (filter.isBlank())
                    "shell:logcat -v threadtime"
                else
                    "shell:logcat -v threadtime $filter"

            openLogcatStream(command)
            readLoop()

        } catch (e: Exception) {

            onStatus("LOGCAT ERROR: ${e.message}")

        } finally {

            running = false
            cleanup()
        }
    }

    private fun performHandshake() {

        val identity =
            "host::rootrealm\u0000".toByteArray(Charsets.US_ASCII)

        writePacket(
            AdbProtocolConstants.A_CNXN,
            AdbProtocolConstants.A_VERSION,
            AdbProtocolConstants.MAX_PAYLOAD,
            identity
        )

        var signatureSent = false
        var pubKeySent = false
        var waitTimeout = 15000

        while (running) {

            val packet =
                readPacket(waitTimeout)
                    ?: throw Exception(
                        "No response from device during handshake."
                    )

            when (packet.command) {

                AdbProtocolConstants.A_CNXN -> {
                    return
                }

                AdbProtocolConstants.A_AUTH -> {

                    if (packet.arg0 != AdbProtocolConstants.AUTH_TOKEN) {
                        continue
                    }

                    when {

                        !signatureSent -> {

                            signatureSent = true

                            val signature =
                                crypto.sign(packet.payload)

                            writePacket(
                                AdbProtocolConstants.A_AUTH,
                                AdbProtocolConstants.AUTH_SIGNATURE,
                                0,
                                signature
                            )
                        }

                        !pubKeySent -> {

                            pubKeySent = true
                            waitTimeout = 60000

                            onStatus(
                                "Check the other device's screen and " +
                                        "tap \"Allow USB debugging\"."
                            )

                            writePacket(
                                AdbProtocolConstants.A_AUTH,
                                AdbProtocolConstants.AUTH_RSAPUBLICKEY,
                                0,
                                crypto.getAdbPublicKeyPayload()
                            )
                        }

                        else -> throw Exception(
                            "ADB authorization was not granted " +
                                    "on the target device."
                        )
                    }
                }

                else -> {
                    // Ignore unrelated traffic during handshake.
                }
            }
        }

        throw Exception("ADB handshake cancelled.")
    }

    private fun openLogcatStream(shellCommand: String) {

        val payload =
            "$shellCommand\u0000".toByteArray(Charsets.US_ASCII)

        writePacket(
            AdbProtocolConstants.A_OPEN,
            localId,
            0,
            payload
        )

        val packet =
            readPacket(10000)
                ?: throw Exception(
                    "No response opening logcat stream."
                )

        when (packet.command) {

            AdbProtocolConstants.A_OKAY -> {
                remoteId = packet.arg0
                streamOpen = true
            }

            AdbProtocolConstants.A_CLSE -> throw Exception(
                "Device closed the logcat stream immediately."
            )

            else -> throw Exception(
                "Unexpected response opening logcat stream."
            )
        }
    }

    private fun readLoop() {

        onStatus("Logcat streaming started.")

        while (running && streamOpen) {

            val packet = readPacket(1000) ?: continue

            when (packet.command) {

                AdbProtocolConstants.A_WRTE -> {

                    if (packet.payload.isNotEmpty()) {

                        onData(
                            String(
                                packet.payload,
                                Charsets.UTF_8
                            )
                        )
                    }

                    writePacket(
                        AdbProtocolConstants.A_OKAY,
                        localId,
                        remoteId,
                        ByteArray(0)
                    )
                }

                AdbProtocolConstants.A_CLSE -> {
                    streamOpen = false
                    onStatus("Logcat stream closed by device.")
                }

                else -> {
                    // OKAY acks and unrelated traffic; nothing to do.
                }
            }
        }
    }

    private fun cleanup() {

        try {
            if (streamOpen) {
                writePacket(
                    AdbProtocolConstants.A_CLSE,
                    localId,
                    remoteId,
                    ByteArray(0)
                )
            }
        } catch (_: Exception) {
        }

        streamOpen = false

        try {
            claimedInterface?.let {
                connection?.releaseInterface(it)
            }
        } catch (_: Exception) {
        }

        try {
            connection?.close()
        } catch (_: Exception) {
        }

        connection = null
        claimedInterface = null
    }

    private fun writePacket(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {

        val conn = connection ?: return
        val out = epOut ?: return

        val checksum =
            payload.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }

        val header =
            ByteBuffer.allocate(24)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command)
                .putInt(arg0)
                .putInt(arg1)
                .putInt(payload.size)
                .putInt(checksum)
                .putInt(command.inv())
                .array()

        usbWriteFully(conn, out, header, 5000)

        if (payload.isNotEmpty()) {
            usbWriteFully(conn, out, payload, 5000)
        }
    }

    private fun usbReadFully(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        data: ByteArray,
        timeoutMs: Int
    ): Boolean {
        var offset = 0
        while (offset < data.size && running) {
            val count = conn.bulkTransfer(endpoint, data, offset, data.size - offset, timeoutMs)
            if (count <= 0) return false
            offset += count
        }
        return offset == data.size
    }

    private fun usbWriteFully(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        data: ByteArray,
        timeoutMs: Int
    ) {
        var offset = 0

        while (offset < data.size) {
            // Keep individual USB IRPs reasonably small. Android's ADB USB
            // implementation uses 16 KiB transfers on the host side.
            val chunk =
                minOf(
                    16 * 1024,
                    data.size - offset
                )

            val count =
                conn.bulkTransfer(
                    endpoint,
                    data,
                    offset,
                    chunk,
                    timeoutMs
                )

            if (count <= 0) {
                throw Exception("ADB USB write failed.")
            }

            offset += count
        }

        // ADB USB requires a zero-length packet when a host-to-device
        // transfer ends exactly on a USB max-packet boundary. Without the
        // ZLP, the target can wait forever for the end of the transfer.
        val maxPacket =
            endpoint.maxPacketSize

        if (data.isNotEmpty() &&
            maxPacket > 0 &&
            data.size % maxPacket == 0
        ) {
            val zlp =
                conn.bulkTransfer(
                    endpoint,
                    ByteArray(0),
                    0,
                    0,
                    timeoutMs
                )

            if (zlp < 0) {
                throw Exception("ADB USB zero-length packet failed.")
            }
        }
    }

    private fun readPacket(timeoutMs: Int): Packet? {

        val conn = connection ?: return null
        val input = epIn ?: return null

        val headerBuf = ByteArray(24)

        if (!usbReadFully(conn, input, headerBuf, timeoutMs)) {
            return null
        }

        val bb =
            ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)

        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val dataLen = bb.int
        bb.int // checksum, not verified on receive
        bb.int // magic, not verified on receive

        if (dataLen <= 0) {
            return Packet(command, arg0, arg1, ByteArray(0))
        }

        val payload = ByteArray(dataLen)
        var offset = 0

        while (offset < dataLen && running) {

            val chunkSize =
                minOf(
                    dataLen - offset,
                    AdbProtocolConstants.MAX_PAYLOAD
                )

            val chunk = ByteArray(chunkSize)

            if (!usbReadFully(conn, input, chunk, timeoutMs)) {
                break
            }

            System.arraycopy(chunk, 0, payload, offset, chunkSize)
            offset += chunkSize
        }

        return Packet(command, arg0, arg1, payload)
    }

    private fun findAdbInterface(dev: UsbDevice): UsbInterface? {

        for (i in 0 until dev.interfaceCount) {

            val intf = dev.getInterface(i)

            if (
                intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                intf.interfaceSubclass == 0x42 &&
                intf.interfaceProtocol == 0x01
            ) {
                return intf
            }
        }

        return null
    }

    private fun getBulkEndpoints(
        intf: UsbInterface
    ): Pair<UsbEndpoint, UsbEndpoint>? {

        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null

        for (i in 0 until intf.endpointCount) {

            val ep = intf.getEndpoint(i)

            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {

                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    inEp = ep
                } else {
                    outEp = ep
                }
            }
        }

        return if (inEp != null && outEp != null)
            Pair(inEp, outEp)
        else
            null
    }
}
