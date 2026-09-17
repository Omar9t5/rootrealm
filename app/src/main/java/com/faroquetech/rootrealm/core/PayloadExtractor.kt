package com.faroquetech.rootrealm.core

import java.io.File
import java.io.RandomAccessFile

// =============================================================
// PAYLOAD EXTRACTOR
//
// Parses the header and protobuf manifest of an Android OTA
// "payload.bin" file (the chromeos_update_engine payload format
// used by AOSP's update_engine).
//
// This file is READ-ONLY inspection: it validates the payload
// header, decodes the DeltaArchiveManifest protobuf by hand
// (no external protobuf runtime dependency), and exposes the
// partitions/operations described inside it.
//
// payload.bin is NOT a ZIP file and is never treated as one here.
// No image data is extracted by this file - that is intentionally
// left for a later step once the parser is verified.
//
// On-disk layout (chromeos_update_engine payload format):
//
//   char   magic[4]                  = "CrAU"
//   uint64 file_format_version       (big-endian)
//   uint64 manifest_size             (big-endian)
//   uint32 metadata_signature_size   (big-endian, only if version >= 2)
//   bytes  manifest[manifest_size]   (serialized DeltaArchiveManifest)
//   bytes  metadata_signature[metadata_signature_size]
//   bytes  operation data blobs...
// =============================================================

/**
 * Thrown when payload.bin does not match the expected OTA payload
 * format, or the embedded manifest cannot be decoded.
 */
class PayloadFormatException(message: String) : Exception(message)

/**
 * The fixed-size header fields read from the start of payload.bin.
 */
data class PayloadHeader(
    val magic: String,
    val majorVersion: Long,
    val manifestSize: Long,
    val metadataSignatureSize: Long,
    val headerSize: Long,
    val dataBlobOffset: Long
)

/**
 * The install operation types defined by update_metadata.proto's
 * InstallOperation.Type enum. Unrecognized codes decode to UNKNOWN
 * rather than throwing, so inspection never fails on newer/unknown
 * operation kinds.
 */
enum class OperationType(val code: Int) {
    REPLACE(0),
    REPLACE_BZ(1),
    MOVE(2),
    BSDIFF(3),
    SOURCE_COPY(4),
    SOURCE_BSDIFF(5),
    ZERO(6),
    DISCARD(7),
    REPLACE_XZ(8),
    PUFFDIFF(9),
    BROTLI_BSDIFF(10),
    ZUCCHINI(11),
    LZ4DIFF_BSDIFF(12),
    LZ4DIFF_PUFFDIFF(13),
    REPLACE_ZSTD(14),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): OperationType =
            values().firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * A single InstallOperation belonging to a partition.
 *
 * [dataOffset] is the raw offset as stored in the manifest (relative
 * to the start of the payload's data-blob region). [absoluteDataOffset]
 * is that same offset translated into a byte offset within payload.bin
 * itself, ready to seek to.
 */
data class OperationInfo(
    val type: OperationType,
    val dataOffset: Long,
    val absoluteDataOffset: Long,
    val dataLength: Long
)

/**
 * A single partition described by the manifest (e.g. "system", "boot",
 * "vendor"), along with every operation needed to write it.
 */
data class PartitionInfo(
    val name: String,
    val newPartitionSize: Long,
    val oldPartitionSize: Long,
    val operationCount: Int,
    val operations: List<OperationInfo>
) {
    /** Count of operations grouped by type, e.g. REPLACE_XZ -> 12. */
    val operationTypeCounts: Map<OperationType, Int>
        get() = operations.groupingBy { it.type }.eachCount()
}

/**
 * The decoded DeltaArchiveManifest protobuf.
 */
data class PayloadManifestInfo(
    val blockSize: Long,
    val minorVersion: Long,
    val maxTimestamp: Long,
    val partitions: List<PartitionInfo>
)

/**
 * Full result of inspecting a payload.bin: header plus manifest.
 */
data class PayloadInspectionResult(
    val header: PayloadHeader,
    val manifest: PayloadManifestInfo
)

/**
 * Entry point for parsing payload.bin. Stateless - safe to call
 * from any thread (callers should still do so off the UI thread,
 * since manifests can be several megabytes).
 */
object PayloadExtractor {

    private const val PAYLOAD_MAGIC = "CrAU"

    // Field numbers below correspond to the message definitions in
    // AOSP's system/update_engine/update_metadata.proto.

    // DeltaArchiveManifest
    private const val FIELD_MANIFEST_BLOCK_SIZE = 3
    private const val FIELD_MANIFEST_MINOR_VERSION = 12
    private const val FIELD_MANIFEST_PARTITIONS = 13
    private const val FIELD_MANIFEST_MAX_TIMESTAMP = 14

    // PartitionUpdate
    private const val FIELD_PARTITION_NAME = 1
    private const val FIELD_PARTITION_OLD_INFO = 6
    private const val FIELD_PARTITION_NEW_INFO = 7
    private const val FIELD_PARTITION_OPERATIONS = 8

    // PartitionInfo
    private const val FIELD_PARTITION_INFO_SIZE = 1

    // InstallOperation
    private const val FIELD_OP_TYPE = 1
    private const val FIELD_OP_DATA_OFFSET = 2
    private const val FIELD_OP_DATA_LENGTH = 3

    /**
     * Reads and validates the payload header, then parses the
     * DeltaArchiveManifest protobuf, returning full partition and
     * operation metadata.
     *
     * @throws PayloadFormatException if the file is missing, too
     * short, has the wrong magic, or the manifest cannot be decoded.
     */
    fun inspect(payloadFile: File): PayloadInspectionResult {

        if (!payloadFile.exists() || !payloadFile.isFile) {
            throw PayloadFormatException(
                "payload.bin not found: ${payloadFile.absolutePath}"
            )
        }

        RandomAccessFile(payloadFile, "r").use { raf ->

            if (raf.length() < 20) {
                throw PayloadFormatException(
                    "File is too small to be a valid payload.bin"
                )
            }

            val magicBytes = ByteArray(4)
            raf.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)

            if (magic != PAYLOAD_MAGIC) {
                throw PayloadFormatException(
                    "Invalid payload.bin: expected magic \"$PAYLOAD_MAGIC\" " +
                        "but found \"$magic\". This file is not an Android " +
                        "OTA payload (it will not be treated as a ZIP)."
                )
            }

            val majorVersion = readUInt64BE(raf)
            val manifestSize = readUInt64BE(raf)

            val metadataSignatureSize =
                if (majorVersion >= 2L) readUInt32BE(raf) else 0L

            var headerSize = 4L + 8L + 8L
            if (majorVersion >= 2L) {
                headerSize += 4L
            }

            if (manifestSize <= 0L || manifestSize > Int.MAX_VALUE.toLong()) {
                throw PayloadFormatException(
                    "Invalid or unsupported manifest size: $manifestSize"
                )
            }

            if (headerSize + manifestSize + metadataSignatureSize > raf.length()) {
                throw PayloadFormatException(
                    "payload.bin is truncated: declared manifest/signature " +
                        "size extends past the end of the file"
                )
            }

            val manifestBytes = ByteArray(manifestSize.toInt())
            raf.readFully(manifestBytes)

            val dataBlobOffset = headerSize + manifestSize + metadataSignatureSize

            val header = PayloadHeader(
                magic = magic,
                majorVersion = majorVersion,
                manifestSize = manifestSize,
                metadataSignatureSize = metadataSignatureSize,
                headerSize = headerSize,
                dataBlobOffset = dataBlobOffset
            )

            val manifest = parseManifest(manifestBytes, dataBlobOffset)

            validateOperationRanges(manifest, raf.length())

            return PayloadInspectionResult(header, manifest)
        }
    }

    /**
     * Ensures every operation's data range actually falls inside
     * payload.bin. Manifests are untrusted input, so an operation
     * whose declared offset/length would read past the end of the
     * file (or before its start) is treated as a malformed payload
     * rather than allowed through to a later reader.
     */
    private fun validateOperationRanges(
        manifest: PayloadManifestInfo,
        fileLength: Long
    ) {

        manifest.partitions.forEach { partition ->

            partition.operations.forEachIndexed { index, operation ->

                if (operation.absoluteDataOffset < 0) {
                    throw PayloadFormatException(
                        "Invalid operation data range in partition " +
                            "\"${partition.name}\" (operation #$index, " +
                            "type ${operation.type}): absoluteDataOffset " +
                            "(${operation.absoluteDataOffset}) is negative"
                    )
                }

                val end = operation.absoluteDataOffset + operation.dataLength

                if (end > fileLength) {
                    throw PayloadFormatException(
                        "Operation data out of bounds in partition " +
                            "\"${partition.name}\" (operation #$index, " +
                            "type ${operation.type}): range " +
                            "[${operation.absoluteDataOffset}, $end) exceeds " +
                            "payload.bin length ($fileLength)"
                    )
                }
            }
        }
    }

    // =========================================================
    // PROTOBUF MESSAGE PARSING
    //
    // Hand-rolled decoding of just the fields Root Realm needs from
    // DeltaArchiveManifest / PartitionUpdate / PartitionInfo /
    // InstallOperation. Unknown fields are skipped using their wire
    // type so the parser stays forward-compatible with newer
    // payload versions.
    // =========================================================

    private fun parseManifest(
        bytes: ByteArray,
        dataBlobOffset: Long
    ): PayloadManifestInfo {

        val reader = ProtoReader(bytes)

        var blockSize = 4096L
        var minorVersion = 0L
        var maxTimestamp = 0L
        val partitions = mutableListOf<PartitionInfo>()

        while (reader.hasRemaining) {

            val tag = reader.readTag() ?: break
            val (field, wireType) = tag

            when (field) {

                FIELD_MANIFEST_BLOCK_SIZE ->
                    blockSize = reader.readVarint()

                FIELD_MANIFEST_MINOR_VERSION ->
                    minorVersion = reader.readVarint()

                FIELD_MANIFEST_MAX_TIMESTAMP ->
                    maxTimestamp = reader.readVarint()

                FIELD_MANIFEST_PARTITIONS ->
                    partitions.add(
                        parsePartitionUpdate(
                            reader.readLengthDelimited(),
                            dataBlobOffset
                        )
                    )

                else -> reader.skip(wireType)
            }
        }

        return PayloadManifestInfo(
            blockSize = blockSize,
            minorVersion = minorVersion,
            maxTimestamp = maxTimestamp,
            partitions = partitions
        )
    }

    private fun parsePartitionUpdate(
        bytes: ByteArray,
        dataBlobOffset: Long
    ): PartitionInfo {

        val reader = ProtoReader(bytes)

        var name = ""
        var oldSize = 0L
        var newSize = 0L
        val operations = mutableListOf<OperationInfo>()

        while (reader.hasRemaining) {

            val tag = reader.readTag() ?: break
            val (field, wireType) = tag

            when (field) {

                FIELD_PARTITION_NAME ->
                    name = String(
                        reader.readLengthDelimited(),
                        Charsets.UTF_8
                    )

                FIELD_PARTITION_OLD_INFO ->
                    oldSize = parsePartitionInfoSize(
                        reader.readLengthDelimited()
                    )

                FIELD_PARTITION_NEW_INFO ->
                    newSize = parsePartitionInfoSize(
                        reader.readLengthDelimited()
                    )

                FIELD_PARTITION_OPERATIONS ->
                    operations.add(
                        parseInstallOperation(
                            reader.readLengthDelimited(),
                            dataBlobOffset
                        )
                    )

                else -> reader.skip(wireType)
            }
        }

        return PartitionInfo(
            name = name,
            newPartitionSize = newSize,
            oldPartitionSize = oldSize,
            operationCount = operations.size,
            operations = operations
        )
    }

    private fun parsePartitionInfoSize(bytes: ByteArray): Long {

        val reader = ProtoReader(bytes)
        var size = 0L

        while (reader.hasRemaining) {

            val tag = reader.readTag() ?: break
            val (field, wireType) = tag

            when (field) {
                FIELD_PARTITION_INFO_SIZE -> size = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }

        return size
    }

    private fun parseInstallOperation(
        bytes: ByteArray,
        dataBlobOffset: Long
    ): OperationInfo {

        val reader = ProtoReader(bytes)

        var typeCode = 0
        var dataOffset = 0L
        var dataLength = 0L

        while (reader.hasRemaining) {

            val tag = reader.readTag() ?: break
            val (field, wireType) = tag

            when (field) {
                FIELD_OP_TYPE -> typeCode = reader.readVarint().toInt()
                FIELD_OP_DATA_OFFSET -> dataOffset = reader.readVarint()
                FIELD_OP_DATA_LENGTH -> dataLength = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }

        return OperationInfo(
            type = OperationType.fromCode(typeCode),
            dataOffset = dataOffset,
            absoluteDataOffset = dataBlobOffset + dataOffset,
            dataLength = dataLength
        )
    }

    // =========================================================
    // BIG-ENDIAN HEADER FIELD READERS
    // =========================================================

    private fun readUInt64BE(raf: RandomAccessFile): Long {

        val b = ByteArray(8)
        raf.readFully(b)

        var value = 0L

        for (i in 0 until 8) {
            value = (value shl 8) or (b[i].toLong() and 0xFF)
        }

        return value
    }

    private fun readUInt32BE(raf: RandomAccessFile): Long {

        val b = ByteArray(4)
        raf.readFully(b)

        var value = 0L

        for (i in 0 until 4) {
            value = (value shl 8) or (b[i].toLong() and 0xFF)
        }

        return value
    }
}

// =============================================================
// MINIMAL PROTOBUF (proto2) WIRE-FORMAT READER
//
// Supports only what DeltaArchiveManifest needs: varint, 32/64-bit
// fixed, and length-delimited fields. This is intentionally NOT a
// general-purpose protobuf library - just enough to decode the OTA
// manifest without pulling in an external dependency.
// =============================================================

private class ProtoReader(private val data: ByteArray) {

    var pos: Int = 0
        private set

    val hasRemaining: Boolean
        get() = pos < data.size

    /** Reads a (fieldNumber, wireType) tag, or null at end of input. */
    fun readTag(): Pair<Int, Int>? {

        if (!hasRemaining) return null

        val tag = readVarint()
        val fieldNumber = (tag shr 3).toInt()
        val wireType = (tag and 0x7L).toInt()

        return fieldNumber to wireType
    }

    fun readVarint(): Long {

        var result = 0L
        var shift = 0

        while (true) {

            if (pos >= data.size) {
                throw PayloadFormatException(
                    "Unexpected end of manifest while reading varint"
                )
            }

            val byte = data[pos].toInt() and 0xFF
            pos++

            result = result or ((byte.toLong() and 0x7F) shl shift)

            if (byte and 0x80 == 0) break

            shift += 7

            if (shift > 63) {
                throw PayloadFormatException("Malformed varint in manifest")
            }
        }

        return result
    }

    fun readLengthDelimited(): ByteArray {

        val length = readVarint()

        if (length < 0 || length > Int.MAX_VALUE.toLong() ||
            pos + length.toInt() > data.size
        ) {
            throw PayloadFormatException(
                "Invalid length-delimited field in manifest"
            )
        }

        val end = pos + length.toInt()
        val slice = data.copyOfRange(pos, end)
        pos = end

        return slice
    }

    fun readFixed32(): Long {

        if (pos + 4 > data.size) {
            throw PayloadFormatException(
                "Unexpected end of manifest while reading fixed32"
            )
        }

        var value = 0L

        for (i in 0 until 4) {
            value = value or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        }

        pos += 4

        return value
    }

    fun readFixed64(): Long {

        if (pos + 8 > data.size) {
            throw PayloadFormatException(
                "Unexpected end of manifest while reading fixed64"
            )
        }

        var value = 0L

        for (i in 0 until 8) {
            value = value or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        }

        pos += 8

        return value
    }

    /** Skips a field's value given its wire type, for unknown fields. */
    fun skip(wireType: Int) {

        when (wireType) {
            0 -> readVarint()
            1 -> readFixed64()
            2 -> readLengthDelimited()
            5 -> readFixed32()
            else -> throw PayloadFormatException(
                "Unsupported protobuf wire type: $wireType"
            )
        }
    }
}
