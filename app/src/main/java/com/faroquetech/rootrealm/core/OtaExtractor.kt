package com.faroquetech.rootrealm.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class OtaInfo(
    val fileName: String,
    val fileSize: Long,
    val hasPayload: Boolean,
    val hasMetadata: Boolean,
    val entries: List<String>
)

/**
 * Result of an "Extract IMG files" run.
 *
 * [extracted] lists the image files that were written to the output folder.
 * [skipped] lists partitions from payload.bin that could NOT be reconstructed
 * (e.g. because they use a delta/incremental operation type this extractor
 * doesn't support yet), each entry formatted as "name: reason".
 */
data class ImageExtractionSummary(
    val extracted: List<String>,
    val skipped: List<String>
)

object OtaExtractor {

    private const val OUTPUT_FOLDER = "Root Realm/OTA"

    /**
     * Reads basic information from an OTA ZIP.
     */
    fun inspect(
        context: Context,
        uri: Uri
    ): OtaInfo {
        val fileName = getFileName(context, uri)
        val tempFile = copyToCache(context, uri)

        return try {
            ZipFile(tempFile).use { zip ->
                val entries = zip.entries().asSequence()
                    .map { it.name }
                    .toList()

                val hasPayload = entries.any {
                    it.equals("payload.bin", ignoreCase = true)
                }

                val hasMetadata = entries.any {
                    it.equals("payload_properties.txt", ignoreCase = true) ||
                    it.equals("META-INF/com/android/metadata", ignoreCase = true)
                }

                OtaInfo(
                    fileName = fileName,
                    fileSize = tempFile.length(),
                    hasPayload = hasPayload,
                    hasMetadata = hasMetadata,
                    entries = entries
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Legacy/app-private extraction. Kept for callers that still use it.
     * New UI should use extractZipToTree() so the user-selected folder is honored.
     */
    fun extractZip(
        context: Context,
        uri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): File {
        val fileName = getFileName(context, uri)
        val baseName = safeBaseName(fileName)

        val outputRoot = File(
            context.getExternalFilesDir(null),
            "$OUTPUT_FOLDER/$baseName"
        )

        if (outputRoot.exists()) {
            outputRoot.deleteRecursively()
        }
        if (!outputRoot.mkdirs() && !outputRoot.isDirectory) {
            throw IllegalStateException("Unable to create OTA output directory")
        }

        val tempFile = copyToCache(context, uri)

        try {
            ZipFile(tempFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val total = entries.size.coerceAtLeast(1)

                entries.forEachIndexed { index, entry ->
                    if (entry.isDirectory) {
                        safeFileForEntry(outputRoot, entry.name).mkdirs()
                    } else {
                        val destination = safeFileForEntry(outputRoot, entry.name)
                        destination.parentFile?.mkdirs()

                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(destination).use { output ->
                                copyStream(input, output)
                            }
                        }
                    }

                    onProgress?.invoke(((index + 1) * 100) / total)
                }
            }
        } finally {
            tempFile.delete()
        }

        return outputRoot
    }

    /**
     * Extracts the complete OTA ZIP into the directory selected through
     * ACTION_OPEN_DOCUMENT_TREE.
     *
     * The returned string is a user-facing destination description built from
     * the selected folder's own display name plus the OTA's subfolder, since
     * Android's Storage Access Framework does not expose a normal filesystem
     * path for arbitrary user-selected folders.
     */
    fun extractZipToTree(
        context: Context,
        uri: Uri,
        outputTreeUri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): String {
        val fileName = getFileName(context, uri)
        val baseName = safeBaseName(fileName)
        val root = treeRootUri(outputTreeUri)
        val cache = SafDirCache()
        val outputFolder = getOrCreateDirectory(context, root, baseName, cache)

        val tempFile = copyToCache(context, uri)

        try {
            ZipFile(tempFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val total = entries.size.coerceAtLeast(1)

                entries.forEachIndexed { index, entry ->
                    val safeParts = safeEntryParts(entry.name)
                    if (safeParts.isNotEmpty()) {
                        if (entry.isDirectory) {
                            getOrCreateDirectoryPath(
                                context,
                                outputFolder,
                                safeParts,
                                cache
                            )
                        } else {
                            writeZipEntryToTree(
                                context,
                                zip,
                                entry,
                                outputFolder,
                                safeParts,
                                cache
                            )
                        }
                    }

                    onProgress?.invoke(((index + 1) * 100) / total)
                }
            }
        } finally {
            tempFile.delete()
        }

        return "${rootDisplayName(context, root)}/$baseName"
    }

    /**
     * Extracts partition images from the OTA.
     *
     * Most modern (A/B, "seamless update") OTA packages don't contain raw
     * .img files at all - the partition images are encoded as operations
     * inside payload.bin. So this:
     *
     *  1. First checks for .img files that are physically present as ZIP
     *     entries (older / non-A/B "block-based" OTA packages sometimes have
     *     these directly).
     *  2. If none exist, falls back to parsing payload.bin (via
     *     [PayloadExtractor]) and reconstructing each partition's raw image
     *     (via [PayloadImageExtractor]).
     *
     * Reconstruction only supports full-image operation types (REPLACE /
     * REPLACE_BZ / REPLACE_XZ / REPLACE_ZSTD / ZERO). Partitions that rely on
     * delta operations (common in incremental/OTA-diff packages) can't be
     * rebuilt from payload.bin alone and are reported in
     * [ImageExtractionSummary.skipped] rather than failing the whole run.
     */
    fun extractImagesToTree(
        context: Context,
        uri: Uri,
        outputTreeUri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): ImageExtractionSummary {

        val tempFile = copyToCache(context, uri)

        return try {
            val hasRawImages = ZipFile(tempFile).use { zip ->
                zip.entries().asSequence().any {
                    !it.isDirectory && it.name.lowercase().endsWith(".img")
                }
            }

            if (hasRawImages) {
                ImageExtractionSummary(
                    extracted = extractRawImageEntries(
                        context,
                        tempFile,
                        uri,
                        outputTreeUri,
                        onProgress
                    ),
                    skipped = emptyList()
                )
            } else {
                extractImagesFromPayload(
                    context,
                    tempFile,
                    uri,
                    outputTreeUri,
                    onProgress
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    /** Fast path: .img files that already exist as literal ZIP entries. */
    private fun extractRawImageEntries(
        context: Context,
        otaZipFile: File,
        uri: Uri,
        outputTreeUri: Uri,
        onProgress: ((Int) -> Unit)?
    ): List<String> {
        val fileName = getFileName(context, uri)
        val baseName = safeBaseName(fileName) + "_images"
        val root = treeRootUri(outputTreeUri)
        val cache = SafDirCache()
        val outputFolder = getOrCreateDirectory(context, root, baseName, cache)

        val extracted = mutableListOf<String>()

        ZipFile(otaZipFile).use { zip ->
            val imageEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.lowercase().endsWith(".img") }
                .toList()

            val total = imageEntries.size.coerceAtLeast(1)

            imageEntries.forEachIndexed { index, entry ->
                val safeParts = safeEntryParts(entry.name)
                if (safeParts.isNotEmpty()) {
                    writeZipEntryToTree(
                        context,
                        zip,
                        entry,
                        outputFolder,
                        safeParts,
                        cache
                    )
                    extracted += safeParts.joinToString("/")
                }

                onProgress?.invoke(((index + 1) * 100) / total)
            }
        }

        return extracted
    }

    /** Slow path: reconstruct partition images out of payload.bin. */
    private fun extractImagesFromPayload(
        context: Context,
        otaZipFile: File,
        uri: Uri,
        outputTreeUri: Uri,
        onProgress: ((Int) -> Unit)?
    ): ImageExtractionSummary {

        val fileName = getFileName(context, uri)
        val baseName = safeBaseName(fileName) + "_images"
        val root = treeRootUri(outputTreeUri)
        val cache = SafDirCache()
        val outputFolder = getOrCreateDirectory(context, root, baseName, cache)

        val payloadFile = extractPayloadEntryToLocalFile(otaZipFile)

        val extracted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        try {
            val inspection = PayloadExtractor.inspect(payloadFile)
            val partitions = inspection.manifest.partitions

            if (partitions.isEmpty()) {
                throw IllegalArgumentException(
                    "payload.bin does not describe any partitions"
                )
            }

            val localDir = File(
                context.cacheDir,
                "rootrealm_payload_images_${System.currentTimeMillis()}"
            )
            localDir.mkdirs()

            try {
                val total = partitions.size

                partitions.forEachIndexed { index, partition ->
                    val localImg = File(localDir, "${partition.name}.img")

                    try {
                        PayloadImageExtractor.extractPartition(
                            payloadFile,
                            partition,
                            localImg
                        ) { opProgress ->
                            val overall = ((index * 100) + opProgress) / total
                            onProgress?.invoke(overall)
                        }

                        writeLocalFileToTree(
                            context,
                            localImg,
                            outputFolder,
                            "${partition.name}.img",
                            cache
                        )

                        extracted += "${partition.name}.img"

                    } catch (e: PayloadExtractionException) {
                        skipped += "${partition.name}: ${e.message ?: "unsupported operation type"}"
                    } finally {
                        localImg.delete()
                    }

                    onProgress?.invoke(((index + 1) * 100) / total)
                }
            } finally {
                localDir.deleteRecursively()
            }

        } finally {
            payloadFile.delete()
        }

        if (extracted.isEmpty()) {
            throw IllegalArgumentException(
                "None of this payload's ${skipped.size} partition(s) could be " +
                    "reconstructed:\n\n" +
                    skipped.joinToString("\n") +
                    "\n\nIf the reason above mentions a missing class/dependency, " +
                    "add it to app/build.gradle. If it mentions delta/incremental " +
                    "operations, try a FULL OTA package instead of an incremental one."
            )
        }

        return ImageExtractionSummary(extracted, skipped)
    }

    /** Pulls payload.bin out of an already-local OTA ZIP copy. */
    private fun extractPayloadEntryToLocalFile(otaZipFile: File): File {
        val payloadFile = File(
            otaZipFile.parentFile,
            "rootrealm_payload_${System.currentTimeMillis()}.bin"
        )

        ZipFile(otaZipFile).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull {
                    !it.isDirectory &&
                        it.name.equals("payload.bin", ignoreCase = true)
                }
                ?: throw IllegalArgumentException(
                    "This OTA ZIP contains no .img files and no payload.bin - " +
                        "there is nothing to extract"
                )

            zip.getInputStream(entry).use { input ->
                FileOutputStream(payloadFile).use { output ->
                    copyStream(input, output)
                }
            }
        }

        return payloadFile
    }

    /**
     * Extracts only payload.bin from the OTA ZIP.
     */
    fun extractPayload(
        context: Context,
        uri: Uri
    ): File {
        val fileName = getFileName(context, uri)
        val baseName = safeBaseName(fileName)

        val outputDirectory = File(
            context.getExternalFilesDir(null),
            "$OUTPUT_FOLDER/$baseName"
        )

        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create payload output directory")
        }

        val payloadFile = File(outputDirectory, "payload.bin")
        val tempFile = copyToCache(context, uri)

        try {
            ZipFile(tempFile).use { zip ->
                val entry = zip.entries().asSequence()
                    .firstOrNull {
                        it.name.equals("payload.bin", ignoreCase = true)
                    }
                    ?: throw IllegalArgumentException(
                        "This OTA does not contain payload.bin"
                    )

                zip.getInputStream(entry).use { input ->
                    FileOutputStream(payloadFile).use { output ->
                        copyStream(input, output)
                    }
                }
            }
        } finally {
            tempFile.delete()
        }

        return payloadFile
    }

    /**
     * Extracts only payload.bin into the user-selected SAF folder.
     */
    fun extractPayloadToTree(
        context: Context,
        uri: Uri,
        outputTreeUri: Uri
    ): String {
        val fileName = getFileName(context, uri)
        val baseName = safeBaseName(fileName)
        val root = treeRootUri(outputTreeUri)
        val cache = SafDirCache()
        val outputFolder = getOrCreateDirectory(
            context,
            root,
            baseName,
            cache
        )

        val tempFile = copyToCache(context, uri)

        try {
            ZipFile(tempFile).use { zip ->
                val entry = zip.entries().asSequence()
                    .firstOrNull {
                        !it.isDirectory &&
                            it.name.equals("payload.bin", ignoreCase = true)
                    }
                    ?: throw IllegalArgumentException(
                        "This OTA does not contain payload.bin"
                    )

                writeZipEntryToTree(
                    context,
                    zip,
                    entry,
                    outputFolder,
                    listOf("payload.bin"),
                    cache
                )
            }
        } finally {
            tempFile.delete()
        }

        return "${rootDisplayName(context, root)}/$baseName/payload.bin"
    }

    /**
     * Reads the Android OTA metadata file when available.
     */
    fun readMetadata(
        context: Context,
        uri: Uri
    ): String? {
        val tempFile = copyToCache(context, uri)

        return try {
            ZipFile(tempFile).use { zip ->
                val entry = zip.entries().asSequence()
                    .firstOrNull {
                        it.name.equals(
                            "META-INF/com/android/metadata",
                            ignoreCase = true
                        )
                    }
                    ?: return null

                zip.getInputStream(entry)
                    .bufferedReader()
                    .use { it.readText() }
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Returns the old app-private OTA output directory for compatibility.
     */
    fun getOutputDirectory(context: Context): File {
        val directory = File(
            context.getExternalFilesDir(null),
            OUTPUT_FOLDER
        )

        if (!directory.exists()) {
            directory.mkdirs()
        }

        return directory
    }

    private fun treeRootUri(treeUri: Uri): Uri {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            ?: throw IllegalArgumentException("Invalid output folder URI")

        return DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            documentId
        )
    }

    /** Display name of the user-selected output folder itself, for status messages. */
    private fun rootDisplayName(context: Context, rootDocUri: Uri): String {
        var name: String? = null

        context.contentResolver.query(
            rootDocUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                if (index >= 0) {
                    name = cursor.getString(index)
                }
            }
        }

        return name ?: "Selected folder"
    }

    private fun getOrCreateDirectory(
        context: Context,
        parent: Uri,
        name: String,
        cache: SafDirCache
    ): Uri {
        cache.get(context, parent, name)?.let { return it }

        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        ) ?: throw IllegalStateException(
            "Unable to create output folder: $name"
        )

        cache.put(parent, name, created)
        return created
    }

    private fun getOrCreateDirectoryPath(
        context: Context,
        root: Uri,
        parts: List<String>,
        cache: SafDirCache
    ): Uri {
        var current = root

        for (part in parts) {
            current = getOrCreateDirectory(context, current, part, cache)
        }

        return current
    }

    private fun writeZipEntryToTree(
        context: Context,
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        root: Uri,
        parts: List<String>,
        cache: SafDirCache
    ) {
        if (parts.isEmpty()) return

        val parentParts = parts.dropLast(1)
        val fileName = parts.last()
        val parent = getOrCreateDirectoryPath(
            context,
            root,
            parentParts,
            cache
        )

        // Replace an existing file with the same name rather than silently
        // appending/creating a duplicate with a provider-generated suffix.
        cache.get(context, parent, fileName)?.let { existing ->
            runCatching {
                DocumentsContract.deleteDocument(
                    context.contentResolver,
                    existing
                )
            }
            cache.remove(parent, fileName)
        }

        val fileUri = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            "application/octet-stream",
            fileName
        ) ?: throw IllegalStateException(
            "Unable to create output file: $fileName"
        )

        cache.put(parent, fileName, fileUri)

        context.contentResolver.openOutputStream(fileUri, "w")?.use { output ->
            zip.getInputStream(entry).use { input ->
                copyStream(input, output)
            }
        } ?: throw IllegalStateException(
            "Unable to write output file: $fileName"
        )
    }

    /** Same as [writeZipEntryToTree] but the source is a local file rather than a ZIP entry. */
    private fun writeLocalFileToTree(
        context: Context,
        localFile: File,
        parent: Uri,
        displayName: String,
        cache: SafDirCache
    ) {
        cache.get(context, parent, displayName)?.let { existing ->
            runCatching {
                DocumentsContract.deleteDocument(
                    context.contentResolver,
                    existing
                )
            }
            cache.remove(parent, displayName)
        }

        val fileUri = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            "application/octet-stream",
            displayName
        ) ?: throw IllegalStateException(
            "Unable to create output file: $displayName"
        )

        cache.put(parent, displayName, fileUri)

        context.contentResolver.openOutputStream(fileUri, "w")?.use { output ->
            localFile.inputStream().use { input ->
                copyStream(input, output)
            }
        } ?: throw IllegalStateException(
            "Unable to write output file: $displayName"
        )
    }

    private fun safeEntryParts(name: String): List<String> {
        val normalized = name.replace('\\', '/')
        val parts = normalized.split('/')

        if (parts.any { it == ".." }) {
            throw SecurityException("Unsafe ZIP entry: $name")
        }

        return parts
            .filter { it.isNotBlank() && it != "." }
            .map { it.replace(Regex("[\\u0000-\\u001F]"), "_") }
    }

    private fun safeFileForEntry(
        root: File,
        entryName: String
    ): File {
        val parts = safeEntryParts(entryName)
        val destination = parts.fold(root) { current, part ->
            File(current, part)
        }

        val canonicalRoot = root.canonicalPath + File.separator
        val canonicalDestination = destination.canonicalPath

        if (!canonicalDestination.startsWith(canonicalRoot)) {
            throw SecurityException("Unsafe ZIP entry: $entryName")
        }

        return destination
    }

    private fun safeBaseName(fileName: String): String {
        val withoutExtension = fileName.substringBeforeLast(
            ".",
            fileName
        )

        return withoutExtension
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.')
            .ifBlank { "OTA" }
    }

    private fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream
    ) {
        val buffer = ByteArray(1024 * 64)

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
        }
    }

    /**
     * Copies the selected content URI to the application's cache.
     */
    private fun copyToCache(
        context: Context,
        uri: Uri
    ): File {
        val tempFile = File(
            context.cacheDir,
            "rootrealm_ota_${System.currentTimeMillis()}.zip"
        )

        context.contentResolver
            .openInputStream(uri)
            ?.use { input ->
                BufferedInputStream(input).use { bufferedInput ->
                    FileOutputStream(tempFile).use { output ->
                        copyStream(bufferedInput, output)
                    }
                }
            }
            ?: throw IllegalStateException(
                "Unable to open selected OTA file"
            )

        if (tempFile.length() <= 0L) {
            tempFile.delete()
            throw IllegalStateException("Selected OTA file is empty")
        }

        return tempFile
    }

    private fun getFileName(
        context: Context,
        uri: Uri
    ): String {
        var result: String? = null

        context.contentResolver
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
                        cursor.getColumnIndex("_display_name")

                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            }

        return result ?: "selected_ota.zip"
    }
}

/**
 * Caches each SAF directory's children (name -> document Uri) so extracting
 * a ZIP with many files/folders doesn't re-query the DocumentsProvider for
 * every path segment of every single entry. Without this, a full OTA ZIP
 * with thousands of files could take many minutes (or appear to hang
 * entirely) because every nested file write was doing a fresh linear scan
 * of its parent directory's contents over IPC.
 *
 * Scoped to a single extraction call - create a fresh instance per
 * extractZipToTree()/extractImagesToTree() run rather than sharing one
 * across calls, since a stale cache could hide files another process wrote
 * to the same folder in between calls.
 */
private class SafDirCache {

    private val childrenByParent =
        mutableMapOf<Uri, MutableMap<String, Uri>>()

    fun get(context: Context, parent: Uri, name: String): Uri? {
        return childrenOf(context, parent)[name]
    }

    fun put(parent: Uri, name: String, uri: Uri) {
        childrenByParent.getOrPut(parent) { mutableMapOf() }[name] = uri
    }

    fun remove(parent: Uri, name: String) {
        childrenByParent[parent]?.remove(name)
    }

    private fun childrenOf(context: Context, parent: Uri): MutableMap<String, Uri> {
        return childrenByParent.getOrPut(parent) { queryChildren(context, parent) }
    }

    private fun queryChildren(context: Context, parent: Uri): MutableMap<String, Uri> {
        val result = mutableMapOf<String, Uri>()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent)
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        runCatching {
            context.contentResolver.query(
                childrenUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                val nameIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )

                while (cursor.moveToNext()) {
                    if (idIndex >= 0 && nameIndex >= 0) {
                        val name = cursor.getString(nameIndex)
                        val id = cursor.getString(idIndex)
                        if (name != null && id != null) {
                            result[name] = DocumentsContract.buildDocumentUriUsingTree(
                                parent,
                                id
                            )
                        }
                    }
                }
            }
        }

        return result
    }
}
