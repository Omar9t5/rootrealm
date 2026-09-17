package com.faroquetech.rootrealm.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

// =============================================================
// PAYLOAD IMAGE EXTRACTOR
//
// Reconstructs a single partition's raw ".img" from payload.bin,
// given the PartitionInfo produced by PayloadExtractor.inspect().
//
// This file deliberately does NOT modify PayloadExtractor.kt. One
// consequence: OperationInfo (as returned by PayloadExtractor) only
// carries type / dataOffset / dataLength - it does not carry the
// operation's destination block ranges (dst_extents), because
// PayloadExtractor only ever needed offset/length for its read-only
// inspection. Reconstruction, however, needs to know exactly which
// blocks of the output image each operation writes to.
//
// Rather than duplicating or changing PayloadExtractor, this file
// re-reads just the manifest bytes (using the header fields
// PayloadExtractor already validated via inspect()) and walks the
// target partition's operations a second time with a small local
// protobuf reader, extracting only the dst_extents field that
// PayloadExtractor's parser intentionally skips. Both parses walk
// the same bytes in the same order, so operations line up 1:1 by
// index with partition.operations from PayloadExtractor.
//
// No external protobuf dependency is introduced - this reader is
// the same hand-rolled varint/length-delimited approach used by
// PayloadExtractor's own ProtoReader, scoped to just the fields
// this file needs.
// =============================================================

/**
 * Thrown for any failure while reconstructing a partition image:
 * an unsupported operation type, out-of-range payload data, a
 * corrupt/mismatched manifest, or an underlying I/O failure.
 */
class PayloadExtractionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

object PayloadImageExtractor {

    private const val PROGRESS_COMPLETE = 100

    private const val ZERO_CHUNK_SIZE = 64 * 1024

    // Field numbers per update_metadata.proto (same source PayloadExtractor's
    // own field constants come from). Only the fields not already parsed by
    // PayloadExtractor are declared here.
    private const val FIELD_MANIFEST_PARTITIONS = 13
    private const val FIELD_PARTITION_NAME = 1
    private const val FIELD_PARTITION_OPERATIONS = 8
    private const val FIELD_OP_DST_EXTENTS = 6
    private const val FIELD_EXTENT_START_BLOCK = 1
    private const val FIELD_EXTENT_NUM_BLOCKS = 2

    /** Operation types this engine can actually write bytes for. */
    private val SUPPORTED_TYPES = setOf(
        OperationType.REPLACE,
        OperationType.REPLACE_BZ,
        OperationType.REPLACE_XZ,
        OperationType.REPLACE_ZSTD,
        OperationType.ZERO
    )

    /**
     * Reconstructs [partition]'s raw image from [payloadFile] into
     * [outputFile].
     *
     * [outputFile] is created (or truncated) at exactly
     * [PartitionInfo.newPartitionSize] and is deleted again if
     * reconstruction fails partway, so a failed run never leaves a
     * partially-written image behind.
     *
     * @param onProgress invoked with a value from 0 to 100 as operations
     * complete. Granularity is per-operation, not per-byte.
     *
     * @throws PayloadExtractionException if any operation in [partition]
     * is of a type this engine cannot safely reconstruct yet, if any
     * payload data range or destination block range is invalid, or on
     * any underlying I/O failure.
     */
    fun extractPartition(
        payloadFile: File,
        partition: PartitionInfo,
        outputFile: File,
        onProgress: (progress: Int) -> Unit = {}
    ) {

        if (!payloadFile.exists() || !payloadFile.isFile) {
            throw PayloadExtractionException(
                "payload.bin not found: ${payloadFile.absolutePath}"
            )
        }

        if (partition.newPartitionSize <= 0L) {
            throw PayloadExtractionException(
                "Partition \"${partition.name}\" has an invalid newPartitionSize " +
                    "(${partition.newPartitionSize})"
            )
        }

        // Fail fast on anything we can't reconstruct, before creating or
        // writing a single byte of the output file.
        partition.operations.forEachIndexed { index, op ->
            if (op.type !in SUPPORTED_TYPES) {
                throw PayloadExtractionException(
                    "Cannot reconstruct partition \"${partition.name}\": operation " +
                        "#$index is of type ${op.type}, which is not yet supported " +
                        "for image reconstruction. Supported types: " +
                        SUPPORTED_TYPES.joinToString()
                )
            }
        }

        val payloadLength = payloadFile.length()

        // Reuses PayloadExtractor for header validation + block size, then
        // recovers this partition's destination extents (see file header
        // comment for why that second, narrow pass is needed).
        val inspection = PayloadExtractor.inspect(payloadFile)

        val freshPartition = inspection.manifest.partitions.firstOrNull {
            it.name == partition.name
        } ?: throw PayloadExtractionException(
            "Partition \"${partition.name}\" was not found while re-inspecting " +
                "payload.bin. Does this payload.bin match the PartitionInfo passed in?"
        )

        if (freshPartition.operationCount != partition.operationCount) {
            throw PayloadExtractionException(
                "Partition \"${partition.name}\" has ${partition.operationCount} " +
                    "operations in the supplied PartitionInfo but " +
                    "${freshPartition.operationCount} when payload.bin is re-inspected. " +
                    "Refusing to reconstruct against mismatched data."
            )
        }

        val blockSize = inspection.manifest.blockSize

        if (blockSize <= 0L) {
            throw PayloadExtractionException(
                "payload.bin manifest reports an invalid block size ($blockSize)"
            )
        }

        val extentsPerOperation = readDestinationExtents(
            payloadFile,
            partition,
            inspection
        )

        if (extentsPerOperation.size != partition.operations.size) {
            throw PayloadExtractionException(
                "Internal error reconstructing partition \"${partition.name}\": " +
                    "found ${extentsPerOperation.size} destination extent groups for " +
                    "${partition.operations.size} operations"
            )
        }

        outputFile.parentFile?.mkdirs()

        var payloadRaf: RandomAccessFile? = null
        var outRaf: RandomAccessFile? = null

        try {

            payloadRaf = RandomAccessFile(payloadFile, "r")
            outRaf = RandomAccessFile(outputFile, "rw")
            outRaf.setLength(partition.newPartitionSize)

            val total = partition.operations.size.coerceAtLeast(1)

            partition.operations.forEachIndexed { index, op ->

                applyOperation(
                    payloadRaf = payloadRaf,
                    outRaf = outRaf,
                    partition = partition,
                    op = op,
                    extents = extentsPerOperation[index],
                    blockSize = blockSize,
                    payloadLength = payloadLength,
                    opIndex = index
                )

                onProgress(((index + 1) * PROGRESS_COMPLETE) / total)
            }

            onProgress(PROGRESS_COMPLETE)

        } catch (e: PayloadExtractionException) {
            closeQuietly(outRaf, payloadRaf)
            outputFile.delete()
            throw e
        } catch (e: IOException) {
            closeQuietly(outRaf, payloadRaf)
            outputFile.delete()
            throw PayloadExtractionException(
                "I/O error reconstructing partition \"${partition.name}\": ${e.message}",
                e
            )
        } finally {
            closeQuietly(outRaf, payloadRaf)
        }
    }

    private fun closeQuietly(vararg closeables: RandomAccessFile?) {
        closeables.forEach {
            try {
                it?.close()
            } catch (_: IOException) {
                // Best-effort close; the original failure (if any) is what matters.
            }
        }
    }

    // =========================================================
    // OPERATION APPLICATION
    // =========================================================

    private fun applyOperation(
        payloadRaf: RandomAccessFile,
        outRaf: RandomAccessFile,
        partition: PartitionInfo,
        op: OperationInfo,
        extents: List<Extent>,
        blockSize: Long,
        payloadLength: Long,
        opIndex: Int
    ) {

        if (op.type == OperationType.ZERO) {
            for (extent in extents) {
                val destOffset = extent.startBlock * blockSize
                val destLength = extent.numBlocks * blockSize
                validateDestinationRange(
                    destOffset, destLength, partition, opIndex, "ZERO"
                )
                writeZeros(outRaf, destOffset, destLength)
            }
            return
        }

        // REPLACE / REPLACE_BZ / REPLACE_XZ / REPLACE_ZSTD all start from a
        // raw data blob read from payload.bin; only decompression differs.
        validateDataRange(op, payloadLength, partition.name, opIndex)

        if (op.dataLength > Int.MAX_VALUE) {
            throw PayloadExtractionException(
                "Operation #$opIndex in partition \"${partition.name}\" has a data " +
                    "blob larger than ${Int.MAX_VALUE} bytes, which is not supported"
            )
        }

        val rawBytes = ByteArray(op.dataLength.toInt())
        payloadRaf.seek(op.absoluteDataOffset)
        payloadRaf.readFully(rawBytes)

        val expectedSize = extents.sumOf { it.numBlocks * blockSize }

        if (expectedSize <= 0L || expectedSize > Int.MAX_VALUE) {
            throw PayloadExtractionException(
                "Operation #$opIndex in partition \"${partition.name}\" has an " +
                    "invalid total destination size ($expectedSize bytes)"
            )
        }

        val decoded = when (op.type) {
            OperationType.REPLACE -> rawBytes
            OperationType.REPLACE_BZ -> decompressBzip2(rawBytes, expectedSize.toInt())
            OperationType.REPLACE_XZ -> decompressXz(rawBytes, expectedSize.toInt())
            OperationType.REPLACE_ZSTD -> decompressZstd(rawBytes, expectedSize.toInt())
            else -> throw PayloadExtractionException(
                "Cannot reconstruct partition \"${partition.name}\": operation " +
                    "#$opIndex is of type ${op.type}, which is not yet supported " +
                    "for image reconstruction."
            )
        }

        writeAcrossExtents(
            outRaf, decoded, extents, blockSize, partition, opIndex
        )
    }

    private fun validateDataRange(
        op: OperationInfo,
        payloadLength: Long,
        partitionName: String,
        opIndex: Int
    ) {
        if (op.absoluteDataOffset < 0 || op.dataLength < 0) {
            throw PayloadExtractionException(
                "Operation #$opIndex in partition \"$partitionName\" has a negative " +
                    "data offset or length (offset=${op.absoluteDataOffset}, " +
                    "length=${op.dataLength})"
            )
        }
        if (op.absoluteDataOffset + op.dataLength > payloadLength) {
            throw PayloadExtractionException(
                "Operation #$opIndex in partition \"$partitionName\" (type ${op.type}) " +
                    "reads past the end of payload.bin: offset=${op.absoluteDataOffset}, " +
                    "length=${op.dataLength}, file size=$payloadLength"
            )
        }
    }

    private fun validateDestinationRange(
        destOffset: Long,
        destLength: Long,
        partition: PartitionInfo,
        opIndex: Int,
        opTypeLabel: String
    ) {
        if (destOffset < 0 || destLength < 0 ||
            destOffset + destLength > partition.newPartitionSize
        ) {
            throw PayloadExtractionException(
                "Operation #$opIndex ($opTypeLabel) in partition \"${partition.name}\" " +
                    "targets an out-of-range destination block range " +
                    "(offset=$destOffset, length=$destLength, " +
                    "partition size=${partition.newPartitionSize})"
            )
        }
    }

    private fun writeAcrossExtents(
        outRaf: RandomAccessFile,
        data: ByteArray,
        extents: List<Extent>,
        blockSize: Long,
        partition: PartitionInfo,
        opIndex: Int
    ) {
        var srcPos = 0

        for (extent in extents) {

            val destOffset = extent.startBlock * blockSize
            val destLength = extent.numBlocks * blockSize

            validateDestinationRange(
                destOffset, destLength, partition, opIndex, partition.operations[opIndex].type.name
            )

            if (destLength > Int.MAX_VALUE) {
                throw PayloadExtractionException(
                    "Operation #$opIndex in partition \"${partition.name}\" has an " +
                        "extent larger than ${Int.MAX_VALUE} bytes, which is not supported"
                )
            }

            val len = destLength.toInt()

            if (srcPos + len > data.size) {
                throw PayloadExtractionException(
                    "Decompressed data for operation #$opIndex in partition " +
                        "\"${partition.name}\" is shorter than its destination extents " +
                        "require (need $len more bytes at offset $srcPos, only " +
                        "${data.size - srcPos} available)"
                )
            }

            outRaf.seek(destOffset)
            outRaf.write(data, srcPos, len)
            srcPos += len
        }

        if (srcPos != data.size) {
            throw PayloadExtractionException(
                "Decompressed data for operation #$opIndex in partition " +
                    "\"${partition.name}\" has ${data.size} bytes but its destination " +
                    "extents only cover $srcPos bytes"
            )
        }
    }

    private fun writeZeros(outRaf: RandomAccessFile, offset: Long, length: Long) {
        outRaf.seek(offset)
        val zeroChunk = ByteArray(minOf(length, ZERO_CHUNK_SIZE.toLong()).toInt())
        var remaining = length
        while (remaining > 0) {
            val chunkSize = minOf(remaining, zeroChunk.size.toLong()).toInt()
            outRaf.write(zeroChunk, 0, chunkSize)
            remaining -= chunkSize
        }
    }

    // =========================================================
    // DECOMPRESSION (REPLACE_BZ / REPLACE_XZ / REPLACE_ZSTD)
    //
    // These formats have no JDK/Android standard-library decoder, so
    // there is no way to support them without a third-party library.
    // Rather than hard-importing those libraries (which would break
    // :app:assembleDebug for anyone who hasn't added the matching
    // gradle dependency), they're loaded via reflection: if the class
    // is present, it's used exactly like a normal InputStream; if not,
    // a PayloadExtractionException names the exact dependency to add.
    // =========================================================

    private fun decompressXz(data: ByteArray, expectedSize: Int): ByteArray =
        decompressViaReflection(
            className = "org.tukaani.xz.XZInputStream",
            gradleDependencyHint = "implementation \"org.tukaani:xz:1.9\"",
            compressed = data,
            expectedSize = expectedSize
        )

    private fun decompressBzip2(data: ByteArray, expectedSize: Int): ByteArray =
        decompressViaReflection(
            className = "org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream",
            gradleDependencyHint = "implementation \"org.apache.commons:commons-compress:1.26.0\"",
            compressed = data,
            expectedSize = expectedSize
        )

    private fun decompressZstd(data: ByteArray, expectedSize: Int): ByteArray =
        decompressViaReflection(
            className = "com.github.luben.zstd.ZstdInputStream",
            gradleDependencyHint = "implementation \"com.github.luben:zstd-jni:1.5.6-3\"",
            compressed = data,
            expectedSize = expectedSize
        )

    private fun decompressViaReflection(
        className: String,
        gradleDependencyHint: String,
        compressed: ByteArray,
        expectedSize: Int
    ): ByteArray {

        val streamClass = try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            throw PayloadExtractionException(
                "This operation requires \"$className\", which is not on the " +
                    "classpath. Add `$gradleDependencyHint` to app/build.gradle.",
                e
            )
        }

        return try {
            val constructor = streamClass.getConstructor(InputStream::class.java)
            val stream = constructor.newInstance(ByteArrayInputStream(compressed)) as InputStream
            stream.use { readExactly(it, expectedSize) }
        } catch (e: ReflectiveOperationException) {
            throw PayloadExtractionException(
                "Failed to invoke decompressor \"$className\": ${e.message}",
                e
            )
        }
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) {
                throw PayloadExtractionException(
                    "Decompressed stream ended early: expected $size bytes, got " +
                        "only $offset"
                )
            }
            offset += read
        }
        return buffer
    }

    // =========================================================
    // DESTINATION EXTENT RECOVERY
    //
    // PayloadExtractor.OperationInfo does not carry dst_extents. This
    // section re-reads the already-validated manifest bytes (using
    // PayloadExtractor's own header fields) and walks just the target
    // partition's operations again to recover them, without touching
    // PayloadExtractor.kt itself.
    // =========================================================

    private data class Extent(val startBlock: Long, val numBlocks: Long)

    private fun readDestinationExtents(
        payloadFile: File,
        partition: PartitionInfo,
        inspection: PayloadInspectionResult
    ): List<List<Extent>> {

        val header = inspection.header

        if (header.manifestSize <= 0L || header.manifestSize > Int.MAX_VALUE) {
            throw PayloadExtractionException(
                "payload.bin manifest size is invalid (${header.manifestSize})"
            )
        }

        val manifestBytes = ByteArray(header.manifestSize.toInt())

        RandomAccessFile(payloadFile, "r").use { raf ->
            raf.seek(header.headerSize)
            raf.readFully(manifestBytes)
        }

        val reader = ManifestExtentReader(manifestBytes)
        var matchedPartitionBytes: ByteArray? = null

        while (reader.hasRemaining) {
            val tag = reader.readTag() ?: break
            val (field, wireType) = tag

            if (field == FIELD_MANIFEST_PARTITIONS) {
                val partitionBytes = reader.readLengthDelimited()
                if (partitionNameOf(partitionBytes) == partition.name) {
                    matchedPartitionBytes = partitionBytes
                    break
                }
            } else {
                reader.skip(wireType)
            }
        }

        val partitionBytes = matchedPartitionBytes
            ?: throw PayloadExtractionException(
                "Could not locate partition \"${partition.name}\" while re-reading " +
                    "the manifest for destination extents"
            )

        return operationExtentsOf(partitionBytes)
    }

    private fun partitionNameOf(partitionBytes: ByteArray): String {
        val reader = ManifestExtentReader(partitionBytes)
        var name = ""

        while (reader.hasRemaining) {
            val tag = reader.readTag() ?: break
            val (field, wireType) = tag

            if (field == FIELD_PARTITION_NAME) {
                name = String(reader.readLengthDelimited(), Charsets.UTF_8)
            } else {
                reader.skip(wireType)
            }
        }

        return name
    }

    private fun operationExtentsOf(partitionBytes: ByteArray): List<List<Extent>> {
        val reader = ManifestExtentReader(partitionBytes)
        val result = mutableListOf<List<Extent>>()

        while (reader.hasRemaining) {
            val tag = reader.readTag() ?: break
            val (field, wireType) = tag

            if (field == FIELD_PARTITION_OPERATIONS) {
                result.add(dstExtentsOf(reader.readLengthDelimited()))
            } else {
                reader.skip(wireType)
            }
        }

        return result
    }

    private fun dstExtentsOf(operationBytes: ByteArray): List<Extent> {
        val reader = ManifestExtentReader(operationBytes)
        val extents = mutableListOf<Extent>()

        while (reader.hasRemaining) {
            val tag = reader.readTag() ?: break
            val (field, wireType) = tag

            if (field == FIELD_OP_DST_EXTENTS) {
                extents.add(extentOf(reader.readLengthDelimited()))
            } else {
                reader.skip(wireType)
            }
        }

        return extents
    }

    private fun extentOf(extentBytes: ByteArray): Extent {
        val reader = ManifestExtentReader(extentBytes)
        var startBlock = 0L
        var numBlocks = 0L

        while (reader.hasRemaining) {
            val tag = reader.readTag() ?: break
            val (field, wireType) = tag

            when (field) {
                FIELD_EXTENT_START_BLOCK -> startBlock = reader.readVarint()
                FIELD_EXTENT_NUM_BLOCKS -> numBlocks = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }

        return Extent(startBlock, numBlocks)
    }
}

/**
 * Minimal protobuf (proto2) wire-format reader, scoped to exactly what
 * [PayloadImageExtractor] needs to recover destination extents: varint
 * and length-delimited fields, plus skip support for fixed32/fixed64 so
 * unrelated fields never break the walk. This intentionally mirrors
 * PayloadExtractor's own file-private ProtoReader rather than reusing it,
 * since that class is private to PayloadExtractor.kt and PayloadExtractor.kt
 * is not to be modified.
 */
private class ManifestExtentReader(private val data: ByteArray) {

    var pos: Int = 0
        private set

    val hasRemaining: Boolean
        get() = pos < data.size

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
                throw PayloadExtractionException(
                    "Unexpected end of manifest while reading varint"
                )
            }

            val byte = data[pos].toInt() and 0xFF
            pos++

            result = result or ((byte.toLong() and 0x7F) shl shift)

            if (byte and 0x80 == 0) break

            shift += 7

            if (shift > 63) {
                throw PayloadExtractionException("Malformed varint in manifest")
            }
        }

        return result
    }

    fun readLengthDelimited(): ByteArray {
        val length = readVarint()

        if (length < 0 || length > Int.MAX_VALUE.toLong() ||
            pos + length.toInt() > data.size
        ) {
            throw PayloadExtractionException(
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
            throw PayloadExtractionException(
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
            throw PayloadExtractionException(
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

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> readFixed64()
            2 -> readLengthDelimited()
            5 -> readFixed32()
            else -> throw PayloadExtractionException(
                "Unsupported protobuf wire type: $wireType"
            )
        }
    }
}
