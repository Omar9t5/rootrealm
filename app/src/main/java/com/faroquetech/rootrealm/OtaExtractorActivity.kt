package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.faroquetech.rootrealm.core.OtaExtractor
import java.util.concurrent.Executors

class OtaExtractorActivity : BaseActivity() {

    private lateinit var root: LinearLayout

    private lateinit var fileNameText: TextView
    private lateinit var outputText: TextView

    private lateinit var inspectButton: Button
    private lateinit var extractZipButton: Button
    private lateinit var extractPayloadButton: Button
    private lateinit var extractImagesButton: Button
    private lateinit var selectOutputButton: Button

    private lateinit var terminalScroll: ScrollView
    private lateinit var terminalText: TextView

    private var selectedUri: Uri? = null
    private var selectedFileName: String = ""
    private var outputTreeUri: Uri? = null
    private var outputFolderText: TextView? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var spinnerRunnable: Runnable? = null
    private var bounceAnimator: android.animation.ObjectAnimator? = null

    /** Cycled while a spinner is running, so each frame ends with more dots. */
    private val spinnerFrames = listOf(
        "\u2615 %s, grab a coffee",
        "\u2615 %s, grab a coffee.",
        "\u2615 %s, grab a coffee..",
        "\u2615 %s, grab a coffee..."
    )

    companion object {
        private const val REQUEST_OTA = 5001
        private const val REQUEST_OUTPUT_FOLDER = 5002
        private const val PREFS_NAME = "ota_extractor"
        private const val PREF_OUTPUT_TREE_URI = "output_tree_uri"
    }

    private val bgColor get() = ThemeManager.current(this).background
    private val cardColor get() = ThemeManager.current(this).surface
    private val cardColor2 get() = ThemeManager.current(this).surface2
    private val whiteColor get() = ThemeManager.current(this).text
    private val secondaryColor get() = ThemeManager.current(this).secondary
    private val greenColor get() = ThemeManager.current(this).success
    private val blueColor get() = ThemeManager.current(this).accent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restoreOutputFolder()
    }

    private fun buildUi() {

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgColor)

        root.setOnApplyWindowInsetsListener { view, insets ->

            val topInset =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }

            view.setPadding(
                view.paddingLeft,
                topInset,
                view.paddingRight,
                view.paddingBottom
            )

            insets
        }

        val scrollView = ScrollView(this)
        scrollView.setBackgroundColor(bgColor)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(
            dp(18),
            dp(18),
            dp(18),
            dp(30)
        )

        scrollView.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        addHeader(content)
        addFileCard(content)
        addTerminalCard(content)
        addOutputFolderCard(content)
        addActionsCard(content)
        addOutputCard(content)
    }

    private fun addHeader(parent: LinearLayout) {

        val title = TextView(this)

        title.text = "OTA Extractor"
        title.setTextColor(whiteColor)
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)

        parent.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(this)

        subtitle.text =
            "Inspect Android OTA packages and extract their contents."

        subtitle.setTextColor(secondaryColor)
        subtitle.textSize = 14f

        val subtitleParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

        subtitleParams.topMargin = dp(5)

        parent.addView(
            subtitle,
            subtitleParams
        )

        val space = Space(this)

        parent.addView(
            space,
            LinearLayout.LayoutParams(
                1,
                dp(18)
            )
        )
    }

    private fun addFileCard(parent: LinearLayout) {

        val card = createCard()

        val title =
            createCardTitle("OTA Package")

        card.addView(title)

        fileNameText = TextView(this)

        fileNameText.text =
            "No OTA package selected"

        fileNameText.setTextColor(
            secondaryColor
        )

        fileNameText.textSize = 14f

        val fileParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

        fileParams.topMargin = dp(8)

        card.addView(
            fileNameText,
            fileParams
        )

        val selectButton =
            createButton(
                "Select OTA ZIP",
                blueColor
            )

        val buttonParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )

        buttonParams.topMargin = dp(16)

        card.addView(
            selectButton,
            buttonParams
        )

        selectButton.setOnClickListener {
            showActionToast("Opening OTA file picker…")
            animateClick(selectButton)
            selectOta()
        }

        parent.addView(card)

        addVerticalSpace(parent, 12)
    }

    private fun addOutputFolderCard(parent: LinearLayout) {

        val card = createCard()

        card.addView(
            createCardTitle("Output Folder")
        )

        outputFolderText = createInfoText(
            "No output folder selected"
        )

        card.addView(outputFolderText)

        selectOutputButton = createButton(
            "Select Output Folder",
            blueColor
        )

        val params =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )

        params.topMargin = dp(14)

        card.addView(selectOutputButton, params)

        selectOutputButton.setOnClickListener {
            showActionToast("Opening output folder picker…")
            animateClick(selectOutputButton)
            selectOutputFolder()
        }

        parent.addView(card)
        addVerticalSpace(parent, 12)
    }

    private fun selectOutputFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

        startActivityForResult(
            intent,
            REQUEST_OUTPUT_FOLDER
        )
    }

    private fun restoreOutputFolder() {
        val saved = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        ).getString(PREF_OUTPUT_TREE_URI, null)

        if (saved.isNullOrBlank()) return

        val uri = runCatching { Uri.parse(saved) }.getOrNull()
            ?: return

        outputTreeUri = uri

        outputFolderText?.text =
            "Output folder selected\n${getTreeDisplayName(uri)}"

        outputFolderText?.setTextColor(whiteColor)
    }

    private fun addActionsCard(parent: LinearLayout) {

        val card = createCard()

        val title =
            createCardTitle("Actions")

        card.addView(title)

        inspectButton =
            createButton(
                "Inspect OTA",
                blueColor
            )

        extractZipButton =
            createButton(
                "Extract Complete OTA",
                greenColor
            )

        extractPayloadButton =
            createButton(
                "Extract payload.bin",
                greenColor
            )

        extractImagesButton =
            createButton(
                "Extract IMG files",
                greenColor
            )

        card.addView(
            inspectButton,
            actionParams()
        )

        card.addView(
            extractZipButton,
            actionParams()
        )

        card.addView(
            extractPayloadButton,
            actionParams()
        )

        card.addView(
            extractImagesButton,
            actionParams()
        )

        inspectButton.isEnabled = false
        extractZipButton.isEnabled = false
        extractPayloadButton.isEnabled = false
        extractImagesButton.isEnabled = false

        inspectButton.setOnClickListener {
            runAction(inspectButton, "Inspect OTA clicked — inspecting OTA…") { inspectOta() }
        }

        extractZipButton.setOnClickListener {
            runAction(extractZipButton, "Extract Complete OTA clicked — starting extraction…") { extractOta() }
        }

        extractPayloadButton.setOnClickListener {
            runAction(extractPayloadButton, "Extract payload.bin clicked — extracting…") { extractPayload() }
        }

        extractImagesButton.setOnClickListener {
            runAction(extractImagesButton, "Extract IMG files clicked — extracting…") { extractImages() }
        }

        parent.addView(card)

        addVerticalSpace(parent, 12)
    }

    private fun addTerminalCard(parent: LinearLayout) {

        val card = createCard()

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = android.view.Gravity.CENTER_VERTICAL

        val titleText = createCardTitle("Root Realm Terminal")

        titleRow.addView(
            titleText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val copyButton = TextView(this)
        copyButton.text = "Copy"
        copyButton.setTextColor(blueColor)
        copyButton.textSize = 13f
        copyButton.setTypeface(null, Typeface.BOLD)
        copyButton.setPadding(dp(10), dp(4), dp(10), dp(4))
        copyButton.isClickable = true
        copyButton.isFocusable = true

        copyButton.setOnClickListener {
            animateClick(copyButton)
            copyTerminalToClipboard()
        }

        titleRow.addView(
            copyButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(titleRow)

        terminalScroll = ScrollView(this)
        terminalScroll.setBackgroundColor(Color.BLACK)
        terminalScroll.isVerticalScrollBarEnabled = true

        // The card sits inside the page's own ScrollView, so without this the
        // outer scroll steals every drag before the terminal ever gets to
        // scroll itself. Claiming the gesture on touch-down lets the user
        // scroll the terminal's own history independently of the page.
        terminalScroll.setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(true)
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        terminalText = TextView(this)
        terminalText.setTextColor(Color.parseColor("#33FF66"))
        terminalText.textSize = 12f
        terminalText.typeface = Typeface.MONOSPACE
        terminalText.setPadding(dp(10), dp(10), dp(10), dp(10))
        terminalText.text = "rootrealm@ota:~$ waiting for a command…"
        terminalText.setTextIsSelectable(true)

        terminalScroll.addView(
            terminalText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val scrollParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(180)
        )
        scrollParams.topMargin = dp(10)

        card.addView(terminalScroll, scrollParams)

        parent.addView(card)
        addVerticalSpace(parent, 12)
    }

    /** Appends a timestamped line to the terminal view and scrolls to the bottom. */
    private fun terminalLog(line: String) {
        mainHandler.post {
            val stamp = java.text.SimpleDateFormat(
                "HH:mm:ss",
                java.util.Locale.US
            ).format(java.util.Date())

            terminalText.append("\n[$stamp] $line")

            terminalScroll.post {
                terminalScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    /**
     * Starts an animated "please wait" line at the bottom of the terminal
     * (dots cycling, coffee emoji) that keeps replacing itself in place
     * rather than spamming new lines, until [stopSpinner] is called.
     */
    private fun startSpinner(message: String) {
        stopSpinner()

        terminalLog(spinnerFrames[0].format(message))

        var frame = 1

        val runnable = object : Runnable {
            override fun run() {
                val current = terminalText.text.toString()
                val lastBreak = current.lastIndexOf('\n')
                val head = if (lastBreak >= 0) current.substring(0, lastBreak + 1) else ""
                val stamp = java.text.SimpleDateFormat(
                    "HH:mm:ss",
                    java.util.Locale.US
                ).format(java.util.Date())

                terminalText.text = "$head[$stamp] ${spinnerFrames[frame % spinnerFrames.size].format(message)}"

                terminalScroll.post {
                    terminalScroll.fullScroll(View.FOCUS_DOWN)
                }

                frame++
                mainHandler.postDelayed(this, 450L)
            }
        }

        spinnerRunnable = runnable
        mainHandler.postDelayed(runnable, 450L)
    }

    /** Copies the entire terminal log to the clipboard, for the Copy button. */
    private fun copyTerminalToClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager

        if (clipboard == null) {
            showError("Clipboard is unavailable")
            return
        }

        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "Root Realm Terminal log",
                terminalText.text.toString()
            )
        )

        showActionToast("Terminal log copied to clipboard")
    }

    /** Stops the running spinner, if any, and logs a final line in its place. */
    private fun stopSpinner(finalLine: String? = null) {
        spinnerRunnable?.let { mainHandler.removeCallbacks(it) }
        spinnerRunnable = null

        if (finalLine != null) {
            terminalLog(finalLine)
        }
    }

    private fun addOutputCard(parent: LinearLayout) {

        val card = createCard()

        val title =
            createCardTitle("Output")

        card.addView(title)

        outputText =
            createInfoText(
                "No extraction performed yet."
            )

        card.addView(outputText)

        parent.addView(card)
    }

    private fun selectOta() {

        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)

        intent.addCategory(
            Intent.CATEGORY_OPENABLE
        )

        // Some file providers label OTA packages as application/zip,
        // application/octet-stream, or a generic MIME type.
        intent.type = "*/*"
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/zip",
                "application/octet-stream",
                "application/x-zip-compressed"
            )
        )

        startActivityForResult(
            intent,
            REQUEST_OTA
        )
    }

    @Deprecated("Deprecated in Android API")
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
            requestCode == REQUEST_OUTPUT_FOLDER &&
            resultCode == RESULT_OK
        ) {
            val uri = data?.data ?: return

            val flags =
                data.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    flags
                )
            }

            outputTreeUri = uri

            getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            ).edit()
                .putString(PREF_OUTPUT_TREE_URI, uri.toString())
                .apply()

            outputFolderText?.text =
                "Output folder selected\n${getTreeDisplayName(uri)}"

            outputFolderText?.setTextColor(whiteColor)

            if (selectedUri != null) {
                extractZipButton.isEnabled = true
                extractPayloadButton.isEnabled = true
                extractImagesButton.isEnabled = true
            }

            showToast("Output folder selected", Toast.LENGTH_SHORT)

            return
        }

        if (
            requestCode == REQUEST_OTA &&
            resultCode == RESULT_OK
        ) {

            val uri =
                data?.data ?: return

            selectedUri = uri

            selectedFileName =
                getDisplayName(uri)

            fileNameText.text =
                selectedFileName

            fileNameText.setTextColor(
                whiteColor
            )

            outputText.text =
                "Ready to inspect or extract."

            terminalLog("rootrealm@ota:~$ ota selected: $selectedFileName")

            inspectButton.isEnabled = true
            extractZipButton.isEnabled = outputTreeUri != null
            extractPayloadButton.isEnabled = outputTreeUri != null
            extractImagesButton.isEnabled = outputTreeUri != null

            showToast("OTA selected", Toast.LENGTH_SHORT)
        }
    }

    private fun inspectOta() {

        val uri =
            selectedUri ?: return

        setBusy(true)

        terminalLog("rootrealm@ota:~$ inspect \"$selectedFileName\"")
        startSpinner("Inspecting OTA")

        executor.execute {

            try {

                val info =
                    OtaExtractor.inspect(
                        this,
                        uri
                    )

                runOnUiThread {

                    setBusy(false)

                    stopSpinner()
                    terminalLog("File name : ${info.fileName}")
                    terminalLog("File size : ${info.fileSize} bytes")
                    terminalLog("payload.bin : ${if (info.hasPayload) "FOUND" else "NOT FOUND"}")
                    terminalLog("Metadata : ${if (info.hasMetadata) "FOUND" else "NOT FOUND"}")
                    terminalLog("Entries : ${info.entries.size} file(s) in archive")
                    terminalLog("rootrealm@ota:~$ inspection complete \u2713")

                    readMetadata(uri)
                }

            } catch (e: Exception) {

                runOnUiThread {

                    setBusy(false)

                    stopSpinner("rootrealm@ota:~$ inspection failed: ${e.message ?: "unknown error"}")

                    showError(
                        e.message
                            ?: "Unable to inspect OTA"
                    )
                }
            }
        }
    }

    private fun readMetadata(uri: Uri) {

        executor.execute {

            try {

                val metadata =
                    OtaExtractor.readMetadata(
                        this,
                        uri
                    )

                runOnUiThread {

                    if (!metadata.isNullOrBlank()) {
                        terminalLog("Metadata contents:")
                        metadata.trim().lines().forEach { terminalLog("  $it") }
                    }
                }

            } catch (_: Exception) {
                // Metadata is optional.
            }
        }
    }
    private fun extractOta() {

        val uri = selectedUri ?: return
        val outputUri = outputTreeUri

        if (outputUri == null) {
            showToast("Select an output folder first", Toast.LENGTH_SHORT)
            return
        }

        setBusy(true)

        terminalLog("rootrealm@ota:~$ extract-zip \"$selectedFileName\"")
        startSpinner("Extracting complete OTA")

        executor.execute {
            try {
                val destination =
                    OtaExtractor.extractZipToTree(
                        this,
                        uri,
                        outputUri
                    )

                runOnUiThread {
                    outputText.text =
                        "Extracted to:\n$destination"

                    setBusy(false)

                    stopSpinner("rootrealm@ota:~$ extracted to $destination \u2713")

                    showToast("OTA extracted successfully", Toast.LENGTH_SHORT)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    outputText.text =
                        "Extraction failed."

                    setBusy(false)

                    stopSpinner("rootrealm@ota:~$ extraction failed: ${e.message ?: "unknown error"}")

                    showError(
                        e.message
                            ?: "Unable to extract OTA"
                    )
                }
            }
        }
    }

    private fun extractPayload() {

        val uri = selectedUri ?: return
        val outputUri = outputTreeUri

        if (outputUri == null) {
            showToast("Select an output folder first", Toast.LENGTH_SHORT)
            return
        }

        setBusy(true)

        terminalLog("rootrealm@ota:~$ extract-payload \"$selectedFileName\"")
        startSpinner("Extracting payload.bin")

        executor.execute {
            try {
                val destination =
                    OtaExtractor.extractPayloadToTree(
                        this,
                        uri,
                        outputUri
                    )

                runOnUiThread {
                    outputText.text =
                        "payload.bin saved to:\n$destination"

                    setBusy(false)

                    stopSpinner("rootrealm@ota:~$ payload.bin saved to $destination \u2713")

                    showToast("payload.bin extracted", Toast.LENGTH_SHORT)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)

                    stopSpinner("rootrealm@ota:~$ payload extraction failed: ${e.message ?: "unknown error"}")

                    showError(
                        e.message
                            ?: "This OTA does not contain payload.bin"
                    )
                }
            }
        }
    }

    private fun extractImages() {

        val uri = selectedUri ?: return
        val outputUri = outputTreeUri

        if (outputUri == null) {
            showToast("Select an output folder first", Toast.LENGTH_SHORT)
            return
        }

        setBusy(true)
        startBounce(extractImagesButton)

        terminalLog("rootrealm@ota:~$ extract-images \"$selectedFileName\"")
        startSpinner("Extracting IMG files")

        executor.execute {
            try {
                val result =
                    OtaExtractor.extractImagesToTree(
                        this,
                        uri,
                        outputUri
                    )

                runOnUiThread {
                    stopSpinner()
                    terminalLog("Extracted : ${result.extracted.size} file(s)")
                    if (result.skipped.isNotEmpty()) {
                        terminalLog("Skipped : ${result.skipped.size} partition(s)")
                        result.skipped.forEach { terminalLog("  - $it") }
                    }
                    terminalLog("rootrealm@ota:~$ image extraction complete \u2713")

                    outputText.text =
                        buildString {
                            append(
                                "Extracted ${result.extracted.size} IMG file(s) " +
                                    "to the selected folder."
                            )

                            if (result.skipped.isNotEmpty()) {
                                append(
                                    "\n\n${result.skipped.size} partition(s) could " +
                                        "not be reconstructed (likely a delta/" +
                                        "incremental OTA):\n"
                                )
                                append(result.skipped.joinToString("\n"))
                            }
                        }

                    setBusy(false)
                    stopBounce(extractImagesButton)

                    showToast("${result.extracted.size} IMG file(s) extracted", Toast.LENGTH_SHORT)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    outputText.text =
                        "IMG extraction failed."

                    setBusy(false)
                    stopBounce(extractImagesButton)

                    stopSpinner("rootrealm@ota:~$ image extraction failed: ${e.message ?: "unknown error"}")

                    showError(
                        e.message
                            ?: "No .img files or payload.bin were found in the OTA ZIP"
                    )
                }
            }
        }
    }

    private fun setBusy(
        busy: Boolean
    ) {

        inspectButton.isEnabled =
            !busy && selectedUri != null

        extractZipButton.isEnabled =
            !busy && selectedUri != null && outputTreeUri != null

        extractPayloadButton.isEnabled =
            !busy && selectedUri != null && outputTreeUri != null

        extractImagesButton.isEnabled =
            !busy && selectedUri != null && outputTreeUri != null

        selectOutputButton.isEnabled = !busy
    }

    private fun showError(
        message: String
    ) {

        showToast(message, Toast.LENGTH_LONG)
    }

    private fun showActionToast(message: String) {
        showToast(message, Toast.LENGTH_SHORT)
    }

    /** Gives every button in the Actions card immediate visual + toast feedback. */
    private fun runAction(button: Button, message: String, action: () -> Unit) {
        showActionToast(message)
        animateClick(button)
        button.postDelayed({ action() }, 90L)
    }

    private fun animateClick(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .alpha(0.78f)
            .setDuration(65)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(130)
                    .start()
            }
            .start()
    }

    private fun animateEntrance(view: View, delay: Long = 0L) {
        view.alpha = 0f
        view.translationY = dp(14).toFloat()
        view.postDelayed({
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
                .start()
        }, delay)
    }

    /** Starts a gentle, continuous up-and-down bob on [view] (e.g. a button while its work runs). */
    private fun startBounce(view: View) {
        stopBounce(view)

        val distance = dp(6).toFloat()

        val animator = android.animation.ObjectAnimator.ofFloat(
            view,
            View.TRANSLATION_Y,
            0f,
            -distance,
            0f
        )

        animator.duration = 600L
        animator.repeatCount = android.animation.ObjectAnimator.INFINITE
        animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()

        bounceAnimator = animator
        animator.start()
    }

    /** Stops the bounce started by [startBounce] and resets [view] back in place. */
    private fun stopBounce(view: View) {
        bounceAnimator?.cancel()
        bounceAnimator = null
        view.translationY = 0f
    }

    private fun createCard(): LinearLayout {

        val card =
            LinearLayout(this)

        card.orientation =
            LinearLayout.VERTICAL

        card.setPadding(
            dp(16),
            dp(16),
            dp(16),
            dp(16)
        )

        card.setBackgroundColor(
            cardColor
        )

        animateEntrance(card)
        return card
    }

    private fun createCardTitle(
        text: String
    ): TextView {

        val title =
            TextView(this)

        title.text = text
        title.setTextColor(
            whiteColor
        )

        title.textSize = 18f

        title.setTypeface(
            null,
            Typeface.BOLD
        )

        return title
    }

    private fun createInfoText(
        text: String
    ): TextView {

        val view =
            TextView(this)

        view.text = text

        view.setTextColor(
            secondaryColor
        )

        view.textSize = 14f

        view.setPadding(
            0,
            dp(10),
            0,
            dp(2)
        )

        return view
    }

    private fun createButton(
        text: String,
        accent: Int
    ): Button {

        val button =
            Button(this)

        button.text = text

        button.setTextColor(
            whiteColor
        )

        button.textSize = 14f

        button.isAllCaps = false

        button.setTypeface(null, Typeface.BOLD)

        button.stateListAnimator = null

        button.background =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor2)
                setStroke(dp(1), accent)
                cornerRadius = dp(14).toFloat()
            }

        animatePress(button)

        return button
    }

    /** Quick scale-down/scale-up press feedback for buttons. Runs alongside whatever
     *  onClickListener is already set, so it never interferes with click handling. */
    private fun animatePress(v: View) {

        v.setOnTouchListener { view, event ->

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }

            false
        }
    }

    private fun actionParams():
        LinearLayout.LayoutParams {

        val params =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )

        params.topMargin =
            dp(12)

        return params
    }

    private fun addVerticalSpace(
        parent: LinearLayout,
        size: Int
    ) {

        val space =
            Space(this)

        parent.addView(
            space,
            LinearLayout.LayoutParams(
                1,
                dp(size)
            )
        )
    }

    private fun getTreeDisplayName(uri: Uri): String {
        var name: String? = null

        runCatching {
            contentResolver.query(
                uri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    )
                    if (index >= 0) {
                        name = cursor.getString(index)
                    }
                }
            }
        }

        return name ?: uri.lastPathSegment ?: "Selected folder"
    }

    private fun getDisplayName(
        uri: Uri
    ): String {

        var name: String? = null

        contentResolver
            .query(
                uri,
                arrayOf("_display_name"),
                null,
                null,
                null
            )
            ?.use { cursor ->

                if (cursor.moveToFirst()) {

                    val index =
                        cursor.getColumnIndex(
                            "_display_name"
                        )

                    if (index >= 0) {

                        name =
                            cursor.getString(index)
                    }
                }
            }

        return name ?: "selected_ota.zip"
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    override fun onDestroy() {

        stopSpinner()
        bounceAnimator?.cancel()
        executor.shutdownNow()

        super.onDestroy()
    }
}