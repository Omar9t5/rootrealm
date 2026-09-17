package com.faroquetech.rootrealm

import com.faroquetech.theme.ThemeManager

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.io.BufferedOutputStream
import java.util.zip.ZipFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class FlashModuleActivity : BaseActivity() {

    private val bgColor get() = ThemeManager.current(this).background
    private val cardColor get() = ThemeManager.current(this).surface
    private val borderColor get() = ThemeManager.current(this).let { ThemeManager.borderColor(it) }
    private val primaryColor get() = ThemeManager.current(this).accent
    private val textColor get() = ThemeManager.current(this).text
    private val secondaryColor get() = ThemeManager.current(this).secondary
    private val dangerColor get() = ThemeManager.current(this).error

    private lateinit var list: LinearLayout
    private lateinit var searchBox: EditText
    private var themeRoot: View? = null

    private data class ThemeMark(
        val text: Int? = null,
        val hint: Int? = null,
        val background: Int? = null,
        val border: Boolean = false,
        val radius: Int = 14
    )

    private val repositories =
        ArrayList<Repository>()

    private data class Repository(
        var name: String,
        var url: String,
        var enabled: Boolean = true
    )

    private data class Module(
        val name: String,
        val id: String,
        val version: String,
        val author: String,
        val description: String,
        val downloadUrl: String,
        val type: String
    )

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        loadRepositories()
        build()
    }

    override fun onPostResume() {
        super.onPostResume()

        // RootRealmGlobalTheme applies its generic widget colors from the
        // activity lifecycle. Run this activity's palette after that pass.
        window.decorView.postDelayed({
            applyFlashTheme()
        }, 80)
    }

    private fun build() {

        val root =
            LinearLayout(this)

        root.orientation =
            LinearLayout.VERTICAL

        root.setBackgroundColor(bgColor)
        root.tag = ThemeMark(background = bgColor)

        val header =
            LinearLayout(this)

        header.gravity =
            Gravity.CENTER_VERTICAL

        header.setPadding(
            dp(18),
            dp(10),
            dp(18),
            dp(10)
        )

        val title =
            TextView(this)

        title.text =
            "Modules Manager"

        title.textSize =
            24f

        title.setTextColor(
            textColor
        )
        title.tag = ThemeMark(text = textColor)

        title.typeface =
            Typeface.DEFAULT_BOLD

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                dp(60),
                1f
            )
        )

        val refresh =
            Button(this)

        refresh.text =
            "↻"

        refresh.textSize =
            22f

        refresh.setTextColor(
            textColor
        )

        refresh.background =
            rounded(
                borderColor,
                12
            )

        refresh.backgroundTintList = null
        refresh.tag = ThemeMark(
            text = textColor,
            background = borderColor,
            radius = 12
        )

        refresh.stateListAnimator =
            null

        refresh.setOnClickListener {

            // Rebuild the complete screen so every newly created view reads
            // the current ThemeManager palette. This prevents stale colors
            // from surviving a refresh after the user changes themes.
            val query = searchBox.text.toString()

            build()

            searchBox.setText(query)
            searchBox.setSelection(searchBox.text.length)

            window.decorView.postDelayed({
                applyFlashTheme()
            }, 80)

            refreshRepositories()
        }

        header.addView(
            refresh,
            LinearLayout.LayoutParams(
                dp(55),
                dp(55)
            )
        )

        root.addView(header)

        searchBox =
            EditText(this)

        searchBox.setTextColor(
            textColor
        )

        searchBox.setHintTextColor(
            secondaryColor
        )

        searchBox.hint =
            "Search modules..."

        searchBox.setSingleLine(true)
        searchBox.gravity = Gravity.CENTER_VERTICAL

        searchBox.textSize =
            14f

        searchBox.setPadding(
            dp(15),
            0,
            dp(15),
            0
        )

        searchBox.background =
            rounded(
                cardColor,
                14
            )

        searchBox.backgroundTintList = null
        searchBox.tag = ThemeMark(
            text = textColor,
            hint = secondaryColor,
            background = cardColor,
            radius = 14
        )

        val searchParams =
            LinearLayout.LayoutParams(
                -1,
                dp(50)
            )

        searchParams.setMargins(
            dp(12),
            dp(4),
            dp(12),
            dp(10)
        )

        root.addView(
            searchBox,
            searchParams
        )

        searchBox.setOnEditorActionListener {
                _, _, _ ->

            refreshRepositories()

            false
        }

        val scroll =
            ScrollView(this)

        scroll.setBackgroundColor(
            bgColor
        )

        scroll.isFillViewport =
            true

        list =
            LinearLayout(this)

        list.orientation =
            LinearLayout.VERTICAL

        list.setPadding(
            dp(12),
            0,
            dp(12),
            dp(30)
        )

        scroll.addView(
            list,
            ViewGroup.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        themeRoot = root
        setContentView(root)

        // RootRealmGlobalTheme can run after this activity creates its views.
        // Re-apply this activity's own palette after that pass.
        root.postDelayed({
            applyFlashTheme()
        }, 80)

        scan()
    }

    private fun scan() {

        list.removeAllViews()

        section(
            "INSTALLED MODULES"
        )

        val modules =
            findInstalledModules()

        if (modules.isEmpty()) {

            item(
                "No modules detected",
                "No Magisk or KernelSU modules were found.",
                false
            )

        } else {

            for (module in modules) {
                moduleCard(module)
            }
        }

        section("FLASH ZIP")

        item(
            "Flash ZIP",
            "Flash kernels, modules and other compatible flashable ZIP files.",
            true
        ) {
            chooseZip()
        }

        section(
            "MODULE REPOSITORIES"
        )

        button(
            "＋  ADD REPOSITORY",
            primaryColor
        ) {
            addRepositoryDialog()
        }

        if (repositories.isEmpty()) {

            item(
                "No repositories added",
                "Add a repository URL to browse downloadable modules.",
                false
            )

        } else {

            for (repo in repositories) {
                repositoryCard(repo)
            }
        }

        section("SYSTEM")

        item(
            "Root",
            if (rootAvailable()) {
                "Available"
            } else {
                "Unavailable"
            },
            false
        )

        item(
            "Kernel",
            getKernel(),
            false
        )
    }

    /*
     * ROOT-AWARE MODULE SCANNER
     *
     * Normal File() access cannot reliably read
     * /data/adb/modules on Android.
     *
     * KernelSU/Magisk expose these directories to
     * the root shell, so we use su to inspect them.
     */
    private fun findInstalledModules(): List<Module> {

        val result =
            ArrayList<Module>()

        if (!rootAvailable()) {
            return result
        }

        val bases = arrayOf(
            "/data/adb/modules",
            "/data/adb/modules_update"
        )

        for (base in bases) {

            val listing =
                runRoot(
                    "if [ -d '$base' ]; then " +
                    "find '$base' -mindepth 1 -maxdepth 1 " +
                    "-type d -print 2>/dev/null; " +
                    "fi"
                )

            if (listing.isBlank()) {
                continue
            }

            val directories =
                listing.lines()

            for (modulePathRaw in directories) {

                val modulePath =
                    modulePathRaw.trim()

                if (modulePath.isBlank()) {
                    continue
                }

                val moduleIdFromFolder =
                    modulePath.substringAfterLast("/")

                if (moduleIdFromFolder.isBlank()) {
                    continue
                }

                if (result.any {
                        it.id == moduleIdFromFolder
                    }) {
                    continue
                }

                val propPath =
                    "$modulePath/module.prop"

                val propText =
                    runRoot(
                        "if [ -f '$propPath' ]; then " +
                        "cat '$propPath' 2>/dev/null; " +
                        "fi"
                    )

                var name =
                    moduleIdFromFolder

                var id =
                    moduleIdFromFolder

                var version =
                    "Unknown"

                var author =
                    "Unknown"

                var description =
                    ""

                if (propText.isNotBlank()) {

                    for (line in propText.lines()) {

                        val index =
                            line.indexOf("=")

                        if (index <= 0) {
                            continue
                        }

                        val key =
                            line.substring(
                                0,
                                index
                            ).trim()

                        val value =
                            line.substring(
                                index + 1
                            ).trim()

                        when (key) {

                            "name" ->
                                name = value

                            "id" ->
                                id = value

                            "version" ->
                                version = value

                            "author" ->
                                author = value

                            "description" ->
                                description = value
                        }
                    }
                }

                /*
                 * Some KernelSU modules can have an ID
                 * different from their folder name.
                 *
                 * Use the real module ID when available.
                 */
                if (
                    id.isBlank()
                ) {
                    id =
                        moduleIdFromFolder
                }

                result.add(
                    Module(
                        name,
                        id,
                        version,
                        author,
                        description,
                        "",
                        "Installed"
                    )
                )
            }
        }

        return result
    }

    private fun moduleCard(
        module: Module
    ) {

        val box =
            card()

        val name =
            TextView(this)

        name.text =
            module.name

        name.textSize =
            17f

        name.setTextColor(
            textColor
        )
        name.tag = ThemeMark(text = textColor)

        name.typeface =
            Typeface.DEFAULT_BOLD

        box.addView(name)

        val info =
            TextView(this)

        info.text =
            "ID: ${module.id}\n" +
            "Version: ${module.version}\n" +
            "Author: ${module.author}"

        info.textSize =
            12f

        info.setTextColor(
            secondaryColor
        )
        info.tag = ThemeMark(text = secondaryColor)

        box.addView(info)

        if (
            module.description.isNotBlank()
        ) {

            val desc =
                TextView(this)

            desc.text =
                module.description

            desc.textSize =
                12f

            desc.setTextColor(
                secondaryColor
            )
            desc.tag = ThemeMark(text = secondaryColor)

            desc.setPadding(
                0,
                dp(6),
                0,
                0
            )

            box.addView(desc)
        }

        val buttons =
            LinearLayout(this)

        buttons.orientation =
            LinearLayout.HORIZONTAL

        buttons.setPadding(
            0,
            dp(12),
            0,
            0
        )

        val disable =
            smallButton(
                "DISABLE",
                primaryColor
            ) {
                disableModule(module.id)
            }

        val remove =
            smallButton(
                "REMOVE",
                dangerColor
            ) {
                removeModule(module.id)
            }

        buttons.addView(
            disable,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                rightMargin =
                    dp(6)
            }
        )

        buttons.addView(
            remove,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                leftMargin =
                    dp(6)
            }
        )

        box.addView(buttons)

        list.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin =
                    dp(8)
            }
        )
    }

    private fun disableModule(
        id: String
    ) {

        if (!rootAvailable()) {
            toast("Root access required")
            return
        }

        Thread {

            runRoot(
                "if [ -d '/data/adb/modules/$id' ]; then " +
                "touch '/data/adb/modules/$id/disable'; " +
                "elif [ -d '/data/adb/modules_update/$id' ]; then " +
                "touch '/data/adb/modules_update/$id/disable'; " +
                "fi"
            )

            runOnUiThread {

                toast(
                    "Module disabled"
                )

                scan()
            }

        }.start()
    }

    private fun removeModule(
        id: String
    ) {

        if (!rootAvailable()) {
            toast("Root access required")
            return
        }

        AlertDialog.Builder(this)
            .setTitle(
                "Remove module?"
            )
            .setMessage(
                "Remove \"$id\" from the installed modules?"
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "REMOVE"
            ) { _, _ ->

                Thread {

                    runRoot(
                        "rm -rf " +
                        "'/data/adb/modules/$id' " +
                        "'/data/adb/modules_update/$id'"
                    )

                    runOnUiThread {

                        toast(
                            "Module removed"
                        )

                        scan()
                    }

                }.start()
            }
            .showThemed()
    }

    private fun chooseZip() {

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        startActivityForResult(intent, 500)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != 500 || resultCode != Activity.RESULT_OK) {
            return
        }

        val uri = data?.data ?: return
        copyAndFlashZip(uri)
    }

    private fun copyAndFlashZip(uri: Uri) {
        if (!rootAvailable()) {
            toast("Root access required")
            return
        }

        Thread {
            var copied: File? = null

            try {
                val dir = File(cacheDir, "rootrealm_flash")
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IllegalStateException("Could not create temporary flash directory.")
                }

                val displayName = queryDisplayName(uri)
                if (displayName != null && !displayName.lowercase().endsWith(".zip")) {
                    throw IllegalArgumentException("Only ZIP files are supported.")
                }

                val temp = File(
                    dir,
                    ".flash_${System.currentTimeMillis()}_${Thread.currentThread().id}.tmp"
                )
                val zip = File(
                    dir,
                    "flash_${System.currentTimeMillis()}_${Thread.currentThread().id}.zip"
                )

                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                } ?: throw IllegalStateException("Could not open selected file.")

                if (!temp.isFile || temp.length() <= 0L) {
                    throw IllegalArgumentException("Selected ZIP is empty.")
                }

                if (!temp.renameTo(zip)) {
                    temp.copyTo(zip, overwrite = true)
                    temp.delete()
                }

                copied = zip

                // Validate the archive before presenting the flash confirmation.
                ZipFile(zip).use { archive ->
                    if (archive.size() == 0) {
                        throw IllegalArgumentException("Selected ZIP contains no files.")
                    }
                }

                val finalZip = zip
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Flash ZIP")
                        .setMessage(
                            "Ready to flash:\n\n" +
                                "This can be a kernel, module, or other compatible flashable ZIP.\n\n" +
                                "Selected: ${displayName ?: finalZip.name}\n" +
                                "Size: ${formatBytes(finalZip.length())}"
                        )
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("FLASH") { _, _ ->
                            flashZip(finalZip)
                        }
                        .showThemed()
                }
            } catch (e: Exception) {
                copied?.delete()
                runOnUiThread {
                    toast(e.message ?: "Could not read ZIP")
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        if (bytes < 1024L * 1024L) return "%.1f KiB".format(bytes / 1024.0)
        if (bytes < 1024L * 1024L * 1024L) return "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        return "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /**
     * Complete ZIP flashing path.
     *
     * - AnyKernel/AnyKernel3 package: run its own recovery update-binary.
     * - KernelSU/Magisk module ZIP: use ksud module install.
     * - Never manufacture tools inside a kernel package.
     * - Never manually flash boot-new.img; the package owns the device-specific
     *   flashing operation.
     * - When the installer exposes a generated image and a concrete by-name
     *   target, perform an independent read-back hash verification.
     */
    private fun flashZip(zip: File) {
        if (!rootAvailable()) {
            toast("Root access required")
            return
        }

        if (!zip.isFile || zip.length() <= 0L) {
            toast("Selected ZIP is unavailable or empty")
            return
        }

        val terminal = FlashTerminal(this)
        val dialog = terminal.show()
        val started = SystemClock.elapsedRealtime()

        terminal.log("[INFO] Selected: ${zip.name}")
        terminal.log("[INFO] Size: ${formatBytes(zip.length())}")
        terminal.log("[INFO] Detecting ZIP type...")

        Thread {
            var packageDir: File? = null
            var verifyImage: File? = null
            var exitCode = -1
            var verified = false

            try {
                ZipFile(zip).use { archive ->
                    if (archive.size() == 0) {
                        throw IllegalArgumentException("ZIP archive is empty.")
                    }
                }

                val flashDir = File(cacheDir, "rootrealm_flash")
                if (!flashDir.exists() && !flashDir.mkdirs()) {
                    throw IllegalStateException("Could not create temporary flash directory.")
                }

                packageDir = File(
                    flashDir,
                    "package_${System.currentTimeMillis()}_${Thread.currentThread().id}"
                )
                if (!packageDir!!.mkdirs()) {
                    throw IllegalStateException("Could not create temporary flash directory.")
                }

                val anyKernelEntry = findAnyKernelEntry(zip)

                if (anyKernelEntry != null) {
                    terminal.log("[OK] AnyKernel3 package detected")
                    terminal.log("[INFO] Installer: $anyKernelEntry")
                    terminal.log("[INFO] Extracting package...")
                    extractZipSafely(zip, packageDir!!)
                    terminal.log("[OK] Extraction complete")

                    val extractionRoot = packageDir!!.canonicalFile
                    val installer = File(extractionRoot, anyKernelEntry).canonicalFile
                    val extractionRootPath = extractionRoot.path + File.separator

                    if (!installer.path.startsWith(extractionRootPath) || !installer.isFile) {
                        throw SecurityException("Invalid AnyKernel package: installer path is outside the package.")
                    }

                    // Support normal packages and ZIPs wrapped in one top-level
                    // directory. The directory containing anykernel.sh is the
                    // effective AKHOME/package root.
                    val packageRoot = installer.parentFile?.canonicalFile
                        ?: throw IllegalArgumentException("Invalid AnyKernel package root.")

                    val updateBinary = File(
                        packageRoot,
                        "META-INF/com/google/android/update-binary"
                    ).canonicalFile

                    val updateRoot = File(packageRoot, "META-INF/com/google/android").canonicalFile
                    val updateRootPath = updateRoot.path + File.separator
                    if (!updateBinary.path.startsWith(updateRootPath) || !updateBinary.isFile) {
                        throw IllegalArgumentException(
                            "AnyKernel package is missing META-INF/com/google/android/update-binary."
                        )
                    }

                    terminal.log("[INFO] Preparing installer permissions...")
                    val chmodCommand = buildString {
                        append("chmod 755 ")
                        append(shellQuote(updateBinary.absolutePath))
                        append(" ")
                        append(shellQuote(installer.absolutePath))
                        append(" 2>/dev/null")
                        val tools = File(packageRoot, "tools")
                        if (tools.isDirectory) {
                            append("; find ")
                            append(shellQuote(tools.absolutePath))
                            append(" -type f -exec chmod 755 {} + 2>/dev/null || true")
                        }
                    }

                    val chmodResult = runRootLive(chmodCommand, terminal)
                    if (chmodResult.exitCode != 0) {
                        terminal.log("[WARN] Permission preparation returned ${chmodResult.exitCode}; continuing.")
                    }

                    terminal.log("[OK] AnyKernel update-binary backend found")

                    // Capture boot-new.img at the moment AnyKernel's magiskboot finishes
                    // repacking it. This avoids depending on AnyKernel's cleanup syntax and
                    // avoids a polling race where the large image can be created and deleted
                    // before Java gets a chance to observe it. Only the extracted temporary
                    // package is modified; the user's ZIP is never changed.
                    verifyImage = File(
                        flashDir,
                        "verify_${System.currentTimeMillis()}_${Thread.currentThread().id}.img"
                    )

                    val verificationPrepared = prepareAnyKernelFinalImage(
                        packageRoot,
                        verifyImage,
                        terminal
                    )
                    if (verificationPrepared) {
                        terminal.log("[OK] Final-image capture hook enabled")
                    } else {
                        terminal.log("[INFO] Final-image capture hook unavailable; flash will continue normally")
                    }

                    terminal.log("[INFO] Running AnyKernel3 update-binary...")
                    terminal.log("[INFO] Read-back verification will use the generated image when available")

                    // Recovery update-binary contract: argv[1]=OUTFD, argv[2]=ZIP path.
                    // Run from the package root so relative AnyKernel paths resolve exactly
                    // as they do in recovery. Do not inject PATH helpers or fake tools.
                    val command =
                        "cd ${shellQuote(packageRoot.absolutePath)} && " +
                            "export AKHOME=${shellQuote(packageRoot.absolutePath)} && " +
                            "sh ${shellQuote(updateBinary.absolutePath)} 3 1 ${shellQuote(zip.absolutePath)}"

                    val result = runRootLive(command, terminal)
                    exitCode = result.exitCode

                    if (exitCode == 0 && verificationPrepared &&
                        (!verifyImage!!.isFile || verifyImage!!.length() <= 0L)
                    ) {
                        terminal.log("[INFO] AnyKernel completed without producing a capturable final image; verification unavailable for this package.")
                    }

                    if (exitCode == 0) {
                        verified = verifyAnyKernelWrite(
                            result.output,
                            verifyImage!!,
                            terminal
                        )
                    }

                    verifyImage?.delete()
                } else {
                    terminal.log("[INFO] No anykernel.sh found")
                    terminal.log("[INFO] Treating ZIP as a KernelSU module...")

                    val result = runRootLive(
                        "ksud module install ${shellQuote(zip.absolutePath)}",
                        terminal
                    )
                    exitCode = result.exitCode
                    verified = exitCode == 0
                }

                val elapsed = SystemClock.elapsedRealtime() - started
                when {
                    exitCode != 0 -> {
                        terminal.failure("Flash failed (exit code $exitCode)")
                    }
                    anyKernelEntry != null && !verified -> {
                        terminal.log("[INFO] Flash completed; independent read-back verification was unavailable for this package.")
                        terminal.success("Flash completed successfully (${elapsed / 1000}s)")
                    }
                    else -> {
                        terminal.success("Flash completed successfully (${elapsed / 1000}s)")
                    }
                }
            } catch (e: Exception) {
                terminal.failure("Flash failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                verifyImage?.delete()
                verifyImage?.let { File(it.absolutePath + ".tmp").delete() }
                packageDir?.let { deleteTree(it) }
                runOnUiThread {
                    terminal.finish()
                    if (dialog.isShowing) {
                        dialog.setCancelable(true)
                        dialog.setCanceledOnTouchOutside(true)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                    }
                }
            }
        }.start()
    }

    /**
     * Verify a completed AnyKernel write when its output contains both:
     *   - a concrete /dev/block/by-name/<partition> target, and
     *   - a generated boot-new.img path.
     *
     * The verification reads exactly the generated image length from the
     * target block device and compares SHA-256 hashes. If either value cannot
     * be identified safely, verification is reported as unavailable rather
     * than guessing a partition.
     */
    private fun verifyAnyKernelWrite(
        output: String,
        verifyImage: File,
        terminal: FlashTerminal
    ): Boolean {
        val targetRegex = Regex("/dev/block/(?:[^\\s/]+/)*by-name/[A-Za-z0-9._-]+")
        val imageRegex = Regex("(?:/tmp/[^\\s'\\\"]+|/data/[^\\s'\\\"]+|/cache/[^\\s'\\\"]+|/system/[^\\s'\\\"]+|/vendor/[^\\s'\\\"]+)?boot-new\\.img")

        val target = targetRegex.findAll(output).map { it.value }.lastOrNull()
        // Never fall back to a path mentioned in installer output: AnyKernel3
        // normally deletes boot-new.img during cleanup, so that path may no longer
        // exist and must not be treated as a verification input.
        val image = if (verifyImage.isFile && verifyImage.length() > 0L) {
            verifyImage.absolutePath
        } else {
            null
        }

        if (target == null || image == null) {
            terminal.log("[INFO] Independent read-back verification unavailable; no preserved final image was captured.")
            return false
        }

        val result = runRootLive(
            """
            target=${shellQuote(target)}
            image=${shellQuote(image)}
            if [ ! -f "${'$'}image" ]; then
                echo '[VERIFY] Generated image no longer exists: '"${'$'}image"
                exit 20
            fi
            if [ ! -b "${'$'}target" ]; then
                echo '[VERIFY] Target is not a block device: '"${'$'}target"
                exit 21
            fi
            size=${'$'}(wc -c < "${'$'}image") || exit 22
            if [ "${'$'}size" -le 0 ]; then
                echo '[VERIFY] Generated image is empty.'
                exit 23
            fi
            image_hash=${'$'}(sha256sum "${'$'}image" | awk '{print ${'$'}1}') || exit 24
            blocks=${'$'}(( (size + 4095) / 4096 ))
            target_hash=${'$'}(dd if="${'$'}target" bs=4096 count="${'$'}blocks" 2>/dev/null | head -c "${'$'}size" | sha256sum | awk '{print ${'$'}1}') || exit 25
            echo "[VERIFY] Target: ${'$'}target"
            echo "[VERIFY] Image:  ${'$'}image"
            echo "[VERIFY] Bytes:  ${'$'}size"
            echo "[VERIFY] Image SHA256:  ${'$'}image_hash"
            echo "[VERIFY] Target SHA256: ${'$'}target_hash"
            if [ "${'$'}image_hash" = "${'$'}target_hash" ]; then
                echo '[VERIFY] MATCH'
                exit 0
            fi
            echo '[VERIFY] MISMATCH'
            exit 26
            """.trimIndent(),
            terminal
        )

        if (result.exitCode == 0 && result.output.contains("[VERIFY] MATCH")) {
            terminal.log("[OK] Partition read-back matches generated image")
            terminal.log("[OK] Actual partition write verified")
            return true
        }

        // A missing final image/target is not a failed flash. The AnyKernel installer
        // already returned success; only report a verification failure when we actually
        // had both inputs and the hashes disagree.
        if (result.output.contains("[VERIFY] MISMATCH")) {
            terminal.log("[ERROR] Partition read-back does not match generated image")
            return false
        }

        terminal.log("[INFO] Independent read-back verification unavailable; installer result is authoritative.")
        return false
    }

    /**
     * Install a temporary wrapper around AnyKernel's magiskboot. The wrapper delegates
     * every command to the original binary and, immediately after a successful `repack`,
     * copies boot-new.img to an app-private verification file. This captures the image
     * before ak3-core.sh can remove it during cleanup, without relying on cleanup syntax.
     */
    private fun prepareAnyKernelFinalImage(
        packageRoot: File,
        verifyImage: File,
        terminal: FlashTerminal
    ): Boolean {
        val tools = File(packageRoot, "tools")
        val magiskboot = File(tools, "magiskboot")
        if (!magiskboot.isFile || magiskboot.length() <= 0L) {
            terminal.log("[INFO] tools/magiskboot not found; cannot install final-image capture hook.")
            return false
        }

        return try {
            val real = File(tools, "magiskboot.rootrealm-real")
            if (!real.exists()) {
                if (!magiskboot.renameTo(real)) {
                    // Fall back to a root copy if a direct rename is blocked by the filesystem.
                    val copy = runRootLive(
                        "cp -f ${shellQuote(magiskboot.absolutePath)} ${shellQuote(real.absolutePath)}",
                        terminal
                    )
                    if (copy.exitCode != 0 || !real.isFile || real.length() <= 0L) {
                        terminal.log("[WARN] Could not stage the original magiskboot binary: ${copy.output.trim().take(240)}")
                        return false
                    }
                    magiskboot.delete()
                }
            }

            if (!real.isFile || real.length() <= 0L) {
                terminal.log("[WARN] Staged magiskboot binary is missing or empty.")
                return false
            }

            // shellQuote() is intended for command arguments, not embedded shell assignments.
            // Rebuild the two paths with single-quoted literals for the wrapper itself.
            val safeReal = real.absolutePath.replace("'", "'\"'\"'")
            val safeVerify = verifyImage.absolutePath.replace("'", "'\"'\"'")
            val finalWrapper = """#!/system/bin/sh
REAL='$safeReal'
VERIFY='$safeVerify'
"${'$'}REAL" "${'$'}@"
RC=${'$'}?
if [ "${'$'}RC" -eq 0 ] && [ "${'$'}1" = "repack" ]; then
    AKROOT="${'$'}AKHOME"
    if [ -z "${'$'}AKROOT" ]; then
        AKROOT='${packageRoot.absolutePath.replace("'", "'\"'\"'")}'
    fi
    FINAL="${'$'}AKROOT/boot-new.img"
    if [ -s "${'$'}FINAL" ]; then
        cp -f "${'$'}FINAL" "${'$'}VERIFY.part" 2>/dev/null &&
            mv -f "${'$'}VERIFY.part" "${'$'}VERIFY" 2>/dev/null
    fi
fi
exit "${'$'}RC"
"""

            magiskboot.writeText(finalWrapper, Charsets.UTF_8)
            magiskboot.setExecutable(true, false)

            val chmod = runRootLive(
                "chmod 755 ${shellQuote(magiskboot.absolutePath)} ${shellQuote(real.absolutePath)}",
                terminal
            )
            if (chmod.exitCode != 0) {
                terminal.log("[WARN] Could not make temporary magiskboot wrapper executable; capture hook disabled.")
                return false
            }

            terminal.log("[OK] magiskboot repack capture hook installed")
            true
        } catch (e: Exception) {
            terminal.log("[WARN] Could not prepare final-image capture hook: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private data class RootLiveResult(
        val exitCode: Int,
        val output: String
    )

    /** Stream root stdout/stderr to the terminal while retaining a bounded copy
     * of the output for post-flash verification. */
    private fun runRootLive(
        command: String,
        terminal: FlashTerminal
    ): RootLiveResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val captured = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    terminal.log(line)
                    if (captured.length < 512 * 1024) {
                        val remaining = 512 * 1024 - captured.length
                        captured.append(line.take(remaining)).append('\n')
                    }
                }
            }

            RootLiveResult(process.waitFor(), captured.toString())
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            terminal.log("[ERROR] $message")
            RootLiveResult(-1, message)
        }
    }

    /** Detect a standard AnyKernel/AnyKernel3 installer, including packages
     * wrapped in one or more top-level directories. */
    private fun findAnyKernelEntry(zip: File): String? {
        ZipFile(zip).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue

                val name = entry.name.replace('\\', '/')
                val base = name.substringAfterLast('/')
                if (base.equals("anykernel.sh", ignoreCase = true)) {
                    return name
                }
            }
        }
        return null
    }

    /** ZIP extraction with Zip Slip protection and basic entry validation. */
    private fun extractZipSafely(
        zip: File,
        destination: File
    ) {
        val destinationRoot = destination.canonicalFile
        val rootPath = destinationRoot.path + File.separator

        ZipFile(zip).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val normalized = entry.name.replace('\\', '/')

                if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) {
                    throw SecurityException("ZIP contains an invalid path: ${entry.name}")
                }

                val output = File(destinationRoot, normalized).canonicalFile
                if (output.path != destinationRoot.path && !output.path.startsWith(rootPath)) {
                    throw SecurityException("ZIP contains an invalid path: ${entry.name}")
                }

                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) {
                        throw IllegalStateException("Could not create directory: ${entry.name}")
                    }
                    continue
                }

                output.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IllegalStateException("Could not create directory for: ${entry.name}")
                    }
                }

                archive.getInputStream(entry).use { input ->
                    BufferedOutputStream(output.outputStream()).use { out ->
                        input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    }
                }
            }
        }
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteTree(it) }
        }
        file.delete()
    }

    /** Quote one shell argument for the su -c command line. */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun addRepositoryDialog() {

        val layout =
            LinearLayout(this)

        layout.orientation =
            LinearLayout.VERTICAL

        layout.setPadding(
            dp(24),
            dp(8),
            dp(24),
            0
        )

        val name =
            EditText(this)

        name.setTextColor(
            textColor
        )
        name.tag = ThemeMark(
            text = textColor,
            hint = secondaryColor
        )

        name.setHintTextColor(
            secondaryColor
        )

        name.hint =
            "Repository name"

        name.setSingleLine(
            true
        )

        layout.addView(
            name,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            )
        )

        val url =
            EditText(this)

        url.setTextColor(
            textColor
        )
        url.tag = ThemeMark(
            text = textColor,
            hint = secondaryColor
        )

        url.setHintTextColor(
            secondaryColor
        )

        url.hint =
            "Repository JSON URL"

        url.setSingleLine(
            true
        )

        layout.addView(
            url,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            )
        )

        AlertDialog.Builder(this)
            .setTitle(
                "Add Repository"
            )
            .setView(layout)
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "ADD"
            ) { _, _ ->

                val repoName =
                    name.text
                        .toString()
                        .trim()

                val repoUrl =
                    url.text
                        .toString()
                        .trim()

                if (
                    repoName.isBlank() ||
                    repoUrl.isBlank()
                ) {

                    toast(
                        "Enter repository name and URL"
                    )

                    return@setPositiveButton
                }

                repositories.add(
                    Repository(
                        repoName,
                        repoUrl,
                        true
                    )
                )

                saveRepositories()
                scan()
            }
            .showThemed()
    }

    private fun repositoryCard(
        repo: Repository
    ) {

        val box =
            card()

        val name =
            TextView(this)

        name.text =
            repo.name

        name.textSize =
            17f

        name.setTextColor(
            textColor
        )
        name.tag = ThemeMark(text = textColor)

        name.typeface =
            Typeface.DEFAULT_BOLD

        box.addView(name)

        val url =
            TextView(this)

        url.text =
            repo.url

        url.textSize =
            11f

        url.setTextColor(
            secondaryColor
        )
        url.tag = ThemeMark(text = secondaryColor)

        url.setPadding(
            0,
            dp(5),
            0,
            0
        )

        box.addView(url)

        val status =
            TextView(this)

        status.text =
            if (repo.enabled) {
                "ENABLED"
            } else {
                "DISABLED"
            }

        status.textSize =
            11f

        status.setTextColor(
            if (repo.enabled) {
                primaryColor
            } else {
                dangerColor
            }
        )
        status.tag = ThemeMark(
            text = if (repo.enabled) primaryColor else dangerColor
        )

        status.setPadding(
            0,
            dp(6),
            0,
            0
        )

        box.addView(status)

        val buttons =
            LinearLayout(this)

        buttons.orientation =
            LinearLayout.HORIZONTAL

        buttons.setPadding(
            0,
            dp(10),
            0,
            0
        )

        val browse =
            smallButton(
                "BROWSE",
                primaryColor
            ) {
                loadRepository(repo)
            }

        val toggle =
            smallButton(
                if (repo.enabled) {
                    "DISABLE"
                } else {
                    "ENABLE"
                },
                primaryColor
            ) {

                repo.enabled =
                    !repo.enabled

                saveRepositories()
                scan()
            }

        val remove =
            smallButton(
                "REMOVE",
                dangerColor
            ) {

                repositories.remove(
                    repo
                )

                saveRepositories()
                scan()
            }

        buttons.addView(
            browse,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                rightMargin =
                    dp(4)
            }
        )

        buttons.addView(
            toggle,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                leftMargin =
                    dp(4)

                rightMargin =
                    dp(4)
            }
        )

        buttons.addView(
            remove,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                leftMargin =
                    dp(4)
            }
        )

        box.addView(buttons)

        list.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin =
                    dp(8)
            }
        )
    }

    private fun refreshRepositories() {

        val enabled =
            repositories.filter {
                it.enabled
            }

        if (enabled.isEmpty()) {
            return
        }

        Thread {

            val all =
                ArrayList<Module>()

            for (repo in enabled) {

                all.addAll(
                    fetchRepository(repo)
                )
            }

            runOnUiThread {

                showRepositoryModules(
                    all
                )
            }

        }.start()
    }

    private fun loadRepository(
        repo: Repository
    ) {

        toast(
            "Loading ${repo.name}..."
        )

        Thread {

            val modules =
                fetchRepository(repo)

            runOnUiThread {

                showRepositoryModules(
                    modules
                )
            }

        }.start()
    }

    private fun fetchRepository(
        repo: Repository
    ): List<Module> {

        try {

            val connection =
                URL(repo.url)
                    .openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                15000

            connection.setRequestProperty(
                "User-Agent",
                "Root Realm"
            )

            val text =
                connection
                    .inputStream
                    .bufferedReader()
                    .readText()

            connection.disconnect()

            val root =
                JSONObject(text)

            val array =
                root.optJSONArray(
                    "modules"
                ) ?: JSONArray()

            /*
             * Two module.json shapes are in the wild:
             *
             *  - "flat": each entry already carries name/version/author/
             *    description/download directly (some custom repos use this).
             *
             *  - Magisk-Modules-Alt-Repo's actual shape: each entry only has
             *    id/prop_url/zip_url/notes_url/stars/last_update - the real
             *    name/version/author live in the module.prop file that
             *    prop_url points to, and have to be fetched separately.
             *
             * Fetch prop_url in parallel (bounded) so a repo with hundreds
             * of modules doesn't take minutes to refresh; each fetch has
             * its own timeout and failure doesn't affect the others.
             */
            val pool =
                Executors.newFixedThreadPool(8)

            val tasks =
                (0 until array.length()).map { i ->

                    Callable<Module?> {

                        val obj =
                            array.optJSONObject(i)
                                ?: return@Callable null

                        val id =
                            obj.optString("id", "")

                        val flatName =
                            obj.optString("name", "")

                        if (flatName.isNotBlank()) {

                            // Flat schema: everything is already on the object.
                            Module(
                                flatName,
                                id,
                                obj.optString("version", "Unknown"),
                                obj.optString("author", "Unknown"),
                                obj.optString("description", ""),
                                obj.optString("download", ""),
                                obj.optString("type", "Magisk")
                            )

                        } else {

                            // Alt-Repo schema: pull the real details out of
                            // module.prop via prop_url.
                            val propUrl =
                                obj.optString("prop_url", "")

                            val props =
                                if (propUrl.isNotBlank()) {
                                    fetchModuleProp(propUrl)
                                } else {
                                    emptyMap()
                                }

                            val zipUrl =
                                obj.optString(
                                    "zip_url",
                                    obj.optString("download", "")
                                )

                            Module(
                                props["name"]?.takeIf { it.isNotBlank() }
                                    ?: id.ifBlank { "Unknown" },
                                id,
                                props["version"] ?: "Unknown",
                                props["author"] ?: "Unknown",
                                props["description"] ?: "",
                                zipUrl,
                                "Magisk"
                            )
                        }
                    }
                }

            val result =
                pool.invokeAll(tasks)
                    .mapNotNull {
                        runCatching { it.get() }.getOrNull()
                    }

            pool.shutdown()

            return result

        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Downloads a module.prop file (id=..., name=..., version=..., etc.)
     * and parses it the same key=value way as an installed module's
     * module.prop, since this is the exact same file format.
     */
    private fun fetchModuleProp(
        url: String
    ): Map<String, String> {

        return try {

            val connection =
                URL(url)
                    .openConnection()
                    as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "Root Realm")

            val text =
                connection
                    .inputStream
                    .bufferedReader()
                    .readText()

            connection.disconnect()

            val props = HashMap<String, String>()

            for (line in text.lines()) {

                val index = line.indexOf("=")
                if (index <= 0) continue

                val key = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim()

                props[key] = value
            }

            props

        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun showRepositoryModules(
        modules: List<Module>
    ) {

        if (modules.isEmpty()) {
            return
        }

        val query =
            searchBox.text
                .toString()
                .trim()
                .lowercase()

        val filtered =
            if (query.isBlank()) {

                modules

            } else {

                modules.filter {

                    it.name
                        .lowercase()
                        .contains(query) ||

                    it.id
                        .lowercase()
                        .contains(query) ||

                    it.author
                        .lowercase()
                        .contains(query) ||

                    it.type
                        .lowercase()
                        .contains(query)
                }
            }

        section(
            "AVAILABLE MODULES"
        )

        for (module in filtered) {
            downloadModuleCard(
                module
            )
        }
    }

    private fun downloadModuleCard(
        module: Module
    ) {

        val box =
            card()

        val name =
            TextView(this)

        name.text =
            module.name

        name.textSize =
            17f

        name.setTextColor(
            textColor
        )
        name.tag = ThemeMark(text = textColor)

        name.typeface =
            Typeface.DEFAULT_BOLD

        box.addView(name)

        val info =
            TextView(this)

        info.text =
            "${module.type} • " +
            "${module.version} • " +
            module.author

        info.textSize =
            12f

        info.setTextColor(
            secondaryColor
        )
        info.tag = ThemeMark(text = secondaryColor)

        info.setPadding(
            0,
            dp(5),
            0,
            0
        )

        box.addView(info)

        if (
            module.description.isNotBlank()
        ) {

            val description =
                TextView(this)

            description.text =
                module.description

            description.textSize =
                12f

            description.setTextColor(
                secondaryColor
            )
            description.tag = ThemeMark(text = secondaryColor)

            description.setPadding(
                0,
                dp(6),
                0,
                0
            )

            box.addView(
                description
            )
        }

        val download =
            smallButton(
                "DOWNLOAD & INSTALL",
                primaryColor
            ) {
                downloadModule(module)
            }

        box.addView(
            download,
            LinearLayout.LayoutParams(
                -1,
                dp(44)
            ).apply {
                topMargin =
                    dp(12)
            }
        )

        list.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin =
                    dp(8)
            }
        )
    }

    private fun downloadModule(
        module: Module
    ) {

        if (
            module.downloadUrl.isBlank()
        ) {

            toast(
                "No download URL"
            )

            return
        }

        toast(
            "Downloading ${module.name}..."
        )

        Thread {

            try {

                val file =
                    File(
                        cacheDir,
                        "${module.id.ifBlank { "module" }}.zip"
                    )

                val connection =
                    URL(
                        module.downloadUrl
                    )
                        .openConnection()
                        as HttpURLConnection

                connection.connectTimeout =
                    15000

                connection.readTimeout =
                    30000

                connection.setRequestProperty(
                    "User-Agent",
                    "Root Realm"
                )

                connection.inputStream
                    .use { input ->

                        file.outputStream()
                            .use { output ->

                                input.copyTo(
                                    output
                                )
                            }
                    }

                connection.disconnect()

                runOnUiThread {

                    AlertDialog.Builder(this)
                        .setTitle(
                            module.name
                        )
                        .setMessage(
                            "Download complete.\n\n" +
                            "Install this module now?"
                        )
                        .setNegativeButton(
                            "CANCEL",
                            null
                        )
                        .setPositiveButton(
                            "INSTALL"
                        ) { _, _ ->

                            installDownloadedModule(
                                file
                            )
                        }
                        .showThemed()
                }

            } catch (_: Exception) {

                runOnUiThread {

                    toast(
                        "Download failed"
                    )
                }
            }

        }.start()
    }

    private fun installDownloadedModule(
        file: File
    ) {

        if (!rootAvailable()) {
            toast("Root access required")
            return
        }

        toast(
            "Installing module..."
        )

        Thread {

            val result =
                runRoot(
                    "ksud module install " +
                    "'${file.absolutePath}' 2>&1"
                )

            runOnUiThread {

                showOutput(
                    "MODULE INSTALL",
                    result.ifBlank {
                        "Installation command completed."
                    }
                )

                scan()
            }

        }.start()
    }
    private fun loadRepositories() {

        val prefs =
            getSharedPreferences(
                "module_repositories",
                MODE_PRIVATE
            )

        val json =
            prefs.getString(
                "repos",
                "[]"
            ) ?: "[]"

        try {

            val array =
                JSONArray(json)

            for (
                i in 0 until array.length()
            ) {

                val obj =
                    array.getJSONObject(i)

                repositories.add(
                    Repository(
                        obj.optString(
                            "name"
                        ),
                        obj.optString(
                            "url"
                        ),
                        obj.optBoolean(
                            "enabled",
                            true
                        )
                    )
                )
            }

        } catch (_: Exception) {
        }
    }

    private fun saveRepositories() {

        val array =
            JSONArray()

        for (repo in repositories) {

            val obj =
                JSONObject()

            obj.put(
                "name",
                repo.name
            )

            obj.put(
                "url",
                repo.url
            )

            obj.put(
                "enabled",
                repo.enabled
            )

            array.put(obj)
        }

        getSharedPreferences(
            "module_repositories",
            MODE_PRIVATE
        )
            .edit()
            .putString(
                "repos",
                array.toString()
            )
            .apply()
    }

    private fun rootAvailable(): Boolean {

        return try {

            val p =
                Runtime.getRuntime()
                    .exec(
                        arrayOf(
                            "su",
                            "-c",
                            "id"
                        )
                    )

            p.waitFor()

            p.exitValue() == 0

        } catch (_: Exception) {
            false
        }
    }

    private fun runRoot(
        command: String
    ): String {

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

            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()

            val error =
                process.errorStream
                    .bufferedReader()
                    .readText()

            process.waitFor()

            if (
                output.isNotBlank()
            ) {
                output.trim()
            } else {
                error.trim()
            }

        } catch (e: Exception) {

            e.message ?: ""
        }
    }

    private fun getKernel(): String {

        return try {

            Runtime.getRuntime()
                .exec("uname -r")
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
                .ifBlank {
                    "Unknown"
                }

        } catch (_: Exception) {

            "Unknown"
        }
    }

    private fun section(
        title: String
    ) {

        val v =
            TextView(this)

        v.text =
            title

        v.textSize =
            13f

        v.setTextColor(
            primaryColor
        )
        v.tag = ThemeMark(text = primaryColor)

        v.typeface =
            Typeface.DEFAULT_BOLD

        v.setPadding(
            dp(5),
            dp(18),
            dp(5),
            dp(8)
        )

        list.addView(v)
    }

    private fun item(
        name: String,
        value: String,
        clickable: Boolean = false,
        action: (() -> Unit)? = null
    ) {

        val box =
            card()

        box.isClickable =
            clickable

        if (action != null) {

            box.setOnClickListener {
                action()
            }
        }

        val title =
            TextView(this)

        title.text =
            name

        title.textSize =
            16f

        title.setTextColor(
            textColor
        )
        title.tag = ThemeMark(text = textColor)

        box.addView(title)

        val valueView =
            TextView(this)

        valueView.text =
            value

        valueView.textSize =
            12f

        valueView.setTextColor(
            secondaryColor
        )
        valueView.tag = ThemeMark(text = secondaryColor)

        valueView.setPadding(
            0,
            dp(5),
            0,
            0
        )

        box.addView(
            valueView
        )

        list.addView(
            box,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin =
                    dp(8)
            }
        )
    }

    private fun button(
        text: String,
        color: Int,
        action: () -> Unit
    ): Button {

        val b =
            Button(this)

        b.text =
            text

        b.textSize =
            13f

        b.setTextColor(
            textColor
        )

        b.typeface =
            Typeface.DEFAULT_BOLD

        b.background =
            rounded(
                color,
                12
            )

        b.backgroundTintList = null
        b.tag = ThemeMark(
            text = textColor,
            background = color,
            radius = 12
        )

        b.stateListAnimator =
            null

        b.minimumHeight =
            0

        b.minHeight =
            0

        b.setOnClickListener {
            action()
        }

        list.addView(
            b,
            LinearLayout.LayoutParams(
                -1,
                dp(48)
            ).apply {
                bottomMargin =
                    dp(8)
            }
        )

        return b
    }

    private fun smallButton(
        text: String,
        color: Int,
        action: () -> Unit
    ): Button {

        val b =
            Button(this)

        b.text =
            text

        b.textSize =
            12f

        b.setTextColor(
            textColor
        )

        b.typeface =
            Typeface.DEFAULT_BOLD

        b.background =
            rounded(
                color,
                10
            )

        b.backgroundTintList = null
        b.tag = ThemeMark(
            text = textColor,
            background = color,
            radius = 10
        )

        b.stateListAnimator =
            null

        b.minimumHeight =
            0

        b.minHeight =
            0

        b.setOnClickListener {
            action()
        }

        return b
    }

    private fun card():
            LinearLayout {

        val box =
            LinearLayout(this)

        box.orientation =
            LinearLayout.VERTICAL

        box.setPadding(
            dp(15),
            dp(13),
            dp(15),
            dp(13)
        )

        box.background =
            android.graphics.drawable
                .GradientDrawable()
                .apply {

                    setColor(
                        cardColor
                    )

                    setStroke(
                        dp(1),
                        borderColor
                    )

                    cornerRadius =
                        dp(14).toFloat()
                }

        box.tag = ThemeMark(
            background = cardColor,
            border = true,
            radius = 14
        )

        return box
    }

    private fun rounded(
        color: Int,
        radius: Int
    ) =
        android.graphics.drawable
            .GradientDrawable()
            .apply {

                setColor(color)

                cornerRadius =
                    dp(radius).toFloat()
            }

    private fun applyFlashTheme() {
        val root = themeRoot ?: return
        applyFlashThemeToView(root)
    }

    private fun applyFlashThemeToView(view: View) {
        val mark = view.tag as? ThemeMark

        when (view) {
            is Button -> {
                val background = mark?.background
                if (background != null) {
                    view.background = rounded(
                        background,
                        mark.radius
                    )
                    view.backgroundTintList = null
                }
                view.setTextColor(mark?.text ?: textColor)
            }

            is EditText -> {
                val background = mark?.background
                if (background != null) {
                    view.background = if (mark.border) {
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(background)
                            setStroke(dp(1), borderColor)
                            cornerRadius = dp(mark.radius).toFloat()
                        }
                    } else {
                        rounded(background, mark.radius)
                    }
                    view.backgroundTintList = null
                }
                mark?.text?.let { view.setTextColor(it) }
                mark?.hint?.let { view.setHintTextColor(it) }
            }

            is TextView -> {
                mark?.text?.let { view.setTextColor(it) }
            }

            is LinearLayout -> {
                if (mark?.background != null) {
                    if (mark.border) {
                        view.background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(mark.background)
                            setStroke(dp(1), borderColor)
                            cornerRadius = dp(mark.radius).toFloat()
                        }
                    } else {
                        view.setBackgroundColor(mark.background)
                    }
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyFlashThemeToView(view.getChildAt(i))
            }
        }
    }

    /**
     * Animated, independently scrollable live output terminal used by Flash ZIP.
     * The terminal owns its ScrollView, so long AnyKernel/module logs cannot
     * overlap the rest of the dialog.
     */
    private class FlashTerminal(
        private val activity: FlashModuleActivity
    ) {
        private val terminalText = TextView(activity).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(activity.secondaryColor)
            tag = ThemeMark(text = activity.secondaryColor)
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            isFocusable = true
            setTextIsSelectable(true)
        }

        private val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            addView(
                terminalText,
                ViewGroup.LayoutParams(-1, -2)
            )
        }

        private val status = TextView(activity).apply {
            text = "● FLASHING"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activity.primaryColor)
            tag = ThemeMark(text = activity.primaryColor)
            setPadding(0, 0, 0, activity.dp(8))
        }

        private val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(8), activity.dp(14), 0)
            alpha = 0f
            translationY = activity.dp(12).toFloat()
        }

        private val copy = activity.smallButton("COPY LOG", activity.primaryColor) {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                ClipData.newPlainText("DroidBox Flash Log", terminalText.text.toString())
            )
            activity.toast("Flash log copied")
        }

        private val clear = activity.smallButton("CLEAR LOG", activity.cardColor) {
            terminalText.text = ""
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        private var dialog: AlertDialog? = null
        private var pulse = false

        private companion object {
            const val MAX_LOG_CHARS = 750_000
        }

        fun show(): AlertDialog {
            val buttons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(copy, LinearLayout.LayoutParams(0, activity.dp(44), 1f).apply {
                    rightMargin = activity.dp(6)
                })
                addView(clear, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
            }

            root.addView(status, LinearLayout.LayoutParams(-1, -2))
            root.addView(
                scroll,
                LinearLayout.LayoutParams(-1, 0, 1f).apply {
                    bottomMargin = activity.dp(8)
                }
            )
            root.addView(buttons, LinearLayout.LayoutParams(-1, activity.dp(48)))

            val frame = FrameLayout(activity).apply {
                setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(8))
                addView(
                    root,
                    FrameLayout.LayoutParams(
                        -1,
                        (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
                            .coerceAtMost(activity.dp(520))
                            .coerceAtLeast(activity.dp(300))
                    )
                )
                setBackgroundColor(Color.TRANSPARENT)
            }

            dialog = AlertDialog.Builder(activity)
                .setTitle("OUTPUT LOG")
                .setView(frame)
                .setPositiveButton("CLOSE", null)
                .create()

            dialog!!.setOnShowListener {
                activity.styleDialog(dialog!!)
                root.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                startPulse()
            }
            dialog!!.setOnDismissListener { pulse = false }
            dialog!!.show()
            activity.styleDialog(dialog!!)

            // The flashing process must not be accidentally dismissed while a
            // privileged installer is still writing a partition. The CLOSE
            // button is re-enabled by finish() after the process exits.
            dialog!!.setCancelable(false)
            dialog!!.setCanceledOnTouchOutside(false)
            dialog!!.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false

            return dialog!!
        }

        fun log(line: String) {
            activity.runOnUiThread {
                val clean = line.replace("\r", "")
                if (clean.isBlank()) return@runOnUiThread
                val atBottom = scroll.getChildAt(0)?.let {
                    scroll.scrollY + scroll.height >= it.height - activity.dp(48)
                } ?: true

                val incoming = clean + "\n"
                val current = terminalText.text.toString()
                if (current.length + incoming.length > MAX_LOG_CHARS) {
                    val keep = MAX_LOG_CHARS - incoming.length
                    val trimmed = if (keep > 0) {
                        current.takeLast(keep)
                    } else {
                        ""
                    }
                    terminalText.text = "[INFO] Earlier log output trimmed.\n" + trimmed + incoming
                } else {
                    terminalText.append(incoming)
                }

                if (atBottom) {
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }

        fun success(message: String) {
            activity.runOnUiThread {
                status.text = "✓ $message"
                status.setTextColor(activity.primaryColor)
                status.tag = ThemeMark(text = activity.primaryColor)
                log("[OK] $message")
            }
        }

        fun failure(message: String) {
            activity.runOnUiThread {
                status.text = "✕ $message"
                status.setTextColor(activity.dangerColor)
                status.tag = ThemeMark(text = activity.dangerColor)
                log("[ERROR] $message")
            }
        }

        fun finish() {
            activity.runOnUiThread {
                pulse = false
                copy.isEnabled = true
                clear.isEnabled = true
            }
        }

        private fun startPulse() {
            pulse = true
            val runnable = object : Runnable {
                override fun run() {
                    if (!pulse) return
                    status.animate()
                        .alpha(if (status.alpha > .7f) .45f else 1f)
                        .setDuration(450)
                        .withEndAction(this)
                        .start()
                }
            }
            status.post(runnable)
        }
    }

    private fun showOutput(
        title: String,
        output: String
    ) {

        val text =
            TextView(this)

        text.text =
            output

        text.textSize =
            12f

        text.setTextColor(
            secondaryColor
        )
        text.tag = ThemeMark(text = secondaryColor)

        text.setPadding(
            dp(18),
            dp(12),
            dp(18),
            dp(12)
        )

        val scroll =
            ScrollView(this)

        scroll.addView(text)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(
                "OK",
                null
            )
            .showThemed()
    }

    private fun AlertDialog.Builder.showThemed(): AlertDialog {
        val dialog = create()

        dialog.setOnShowListener {
            styleDialog(dialog)
        }

        dialog.show()
        styleDialog(dialog)
        return dialog
    }

    private fun styleDialog(dialog: AlertDialog) {
        val window = dialog.window
        window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor)
                setStroke(dp(1), borderColor)
                cornerRadius = dp(18).toFloat()
            }
        )

        dialog.findViewById<TextView>(
            android.R.id.message
        )?.setTextColor(secondaryColor)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(
                if (text.toString().equals("REMOVE", true)) {
                    dangerColor
                } else {
                    primaryColor
                }
            )
            backgroundTintList = null
        }

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(secondaryColor)
            backgroundTintList = null
        }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            setTextColor(secondaryColor)
            backgroundTintList = null
        }

        styleDialogViews(dialog.window?.decorView)
    }

    private fun styleDialogViews(view: View?) {
        if (view == null) return

        when (view) {
            is EditText -> {
                view.setTextColor(textColor)
                view.setHintTextColor(secondaryColor)
                view.backgroundTintList = ColorStateList.valueOf(borderColor)
            }
            is TextView -> {
                if (view !is Button &&
                    view.id != android.R.id.message
                ) {
                    // Dialog title and other labels use the active theme text color.
                    if (view.text.toString().isNotBlank()) {
                        view.setTextColor(textColor)
                    }
                }

                if (view.id == android.R.id.message) {
                    view.setTextColor(secondaryColor)
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                styleDialogViews(view.getChildAt(i))
            }
        }
    }

    private fun toast(
        text: String
    ) {

        runOnUiThread {

            showToast(text, Toast.LENGTH_SHORT)
        }
    }

    private fun dp(
        value: Int
    ): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density +
                .5f
        ).toInt()
}