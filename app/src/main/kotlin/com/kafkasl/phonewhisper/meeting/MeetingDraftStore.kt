package com.kafkasl.phonewhisper.meeting

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class MeetingDraftStore internal constructor(
    private val file: File,
    private val atomicFile: AtomicFile,
) {
    constructor(file: File) : this(file, AtomicFile(file))

    private val backupFile = File(file.path + ".bak")
    private val newFile = File(file.path + ".new")

    @Synchronized
    fun load(): MeetingDocumentRead {
        if (!file.exists() && !backupFile.exists()) {
            if (newFile.exists() && !newFile.delete()) {
                throw IOException("Unable to remove an incomplete first meeting draft write")
            }
            return MeetingDocumentRead.Absent
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val raw = InputStreamReader(atomicFile.openRead(), decoder).buffered().use { reader -> reader.readText() }
        return MeetingDocumentJson.decode(raw)
    }

    @Synchronized
    fun save(document: MeetingDocument) {
        val encoded = MeetingDocumentJson.encode(document).toByteArray(StandardCharsets.UTF_8)
        val current = load()
        val previousBytes = when (current) {
            MeetingDocumentRead.Absent -> null
            is MeetingDocumentRead.Ready -> file.readBytes()
            is MeetingDocumentRead.Unsupported ->
                throw IllegalStateException(
                    "Refusing to overwrite meeting draft version ${current.version}; clear it explicitly first",
                )
            is MeetingDocumentRead.Invalid ->
                throw IllegalStateException("Refusing to overwrite an invalid meeting draft; clear it explicitly first")
        }

        val output = atomicFile.startWrite()
        try {
            output.write(encoded)
            output.fd.sync()
            atomicFile.finishWrite(output)
            verifyPublished(encoded)
        } catch (failure: Throwable) {
            rollbackFailedWrite(output, previousBytes, failure)
            throw failure
        }
    }

    @Synchronized
    fun clear() {
        atomicFile.delete()
        if (file.exists() || backupFile.exists() || newFile.exists()) {
            throw IOException("Unable to clear meeting draft files")
        }
    }

    private fun verifyPublished(expected: ByteArray) {
        if (newFile.exists()) throw IOException("AtomicFile left an unpublished temporary draft")
        if (backupFile.exists()) throw IOException("AtomicFile left a backup after draft publication")
        if (!file.isFile || !file.readBytes().contentEquals(expected)) {
            throw IOException("AtomicFile did not publish the expected meeting draft")
        }
    }

    private fun rollbackFailedWrite(
        output: FileOutputStream,
        previousBytes: ByteArray?,
        failure: Throwable,
    ) {
        try {
            atomicFile.failWrite(output)
        } catch (restoreFailure: Throwable) {
            failure.addSuppressed(restoreFailure)
        }

        try {
            if (backupFile.exists()) {
                atomicFile.openRead().close()
            }
            if (newFile.exists() && !newFile.delete()) {
                throw IOException("Unable to remove unpublished meeting draft temporary")
            }

            if (previousBytes == null) {
                if (file.exists() && !file.delete()) {
                    throw IOException("Unable to remove failed first meeting draft")
                }
            } else if (!file.isFile || !file.readBytes().contentEquals(previousBytes)) {
                restorePreviousBytes(previousBytes)
            }

            if (newFile.exists() || backupFile.exists()) {
                throw IOException("AtomicFile rollback left temporary draft data")
            }
            if (previousBytes != null && (!file.isFile || !file.readBytes().contentEquals(previousBytes))) {
                throw IOException("AtomicFile rollback did not restore the previous meeting draft")
            }
            if (previousBytes == null && file.exists()) {
                throw IOException("AtomicFile rollback left an uncommitted first meeting draft")
            }
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
    }

    private fun restorePreviousBytes(previousBytes: ByteArray) {
        val restore = atomicFile.startWrite()
        try {
            restore.write(previousBytes)
            restore.fd.sync()
            atomicFile.finishWrite(restore)
            if (newFile.exists() || backupFile.exists() || !file.isFile || !file.readBytes().contentEquals(previousBytes)) {
                throw IOException("Unable to restore previous meeting draft")
            }
        } catch (failure: Throwable) {
            try {
                atomicFile.failWrite(restore)
            } catch (restoreFailure: Throwable) {
                failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
    }
}
