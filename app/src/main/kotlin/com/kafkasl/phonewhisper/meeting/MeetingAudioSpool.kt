package com.kafkasl.phonewhisper.meeting

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** Owns one private, per-session spool directory beneath the app's private cache directory. */
internal class MeetingAudioSpool(root: File) : AutoCloseable {
    private val directory: File
    private val ownerFile: RandomAccessFile
    private val ownerLock: FileLock
    private var nextSegment = 0
    private var closed = false

    init {
        val setup = synchronized(PROCESS_SETUP_LOCK) {
            val rootDirectory = ensurePrivateDirectory(root)
            val sweepLockFile = File(rootDirectory, SWEEP_LOCK_NAME)
            val sweepLockOwner = RandomAccessFile(sweepLockFile, "rw")
            val sweepLock = try {
                sweepLockOwner.channel.tryLock()
                    ?: throw IOException("Meeting audio cache is already being prepared")
            } catch (failure: Throwable) {
                sweepLockOwner.close()
                throw IOException("Unable to lock private meeting audio cache", failure)
            }
            try {
                sweepOrphans(rootDirectory)
                val sessionDirectory = File(rootDirectory, "session-${UUID.randomUUID()}")
                if (!sessionDirectory.mkdir()) throw IOException("Unable to create private meeting audio spool")
                setPrivatePermissions(sessionDirectory, directory = true)
                val owner = File(sessionDirectory, OWNER_LOCK_NAME)
                if (!owner.createNewFile()) throw IOException("Unable to create meeting spool owner lock")
                setPrivatePermissions(owner, directory = false)
                val ownerHandle = RandomAccessFile(owner, "rw")
                val acquired = try {
                    ownerHandle.channel.tryLock()
                } catch (failure: Throwable) {
                    ownerHandle.close()
                    sessionDirectory.deleteRecursively()
                    throw IOException("Unable to lock meeting audio spool", failure)
                } ?: run {
                    ownerHandle.close()
                    sessionDirectory.deleteRecursively()
                    throw IOException("Meeting audio spool is already active")
                }
                Triple(sessionDirectory, ownerHandle, acquired)
            } finally {
                try {
                    sweepLock.release()
                } finally {
                    sweepLockOwner.close()
                }
            }
        }
        directory = setup.first
        ownerFile = setup.second
        ownerLock = setup.third
    }

    fun createSegment(): File {
        checkOpen()
        val segment = File(directory, "segment-${nextSegment++.toString().padStart(8, '0')}.pcmq")
        if (!segment.createNewFile()) throw IOException("Unable to create meeting audio segment")
        setPrivatePermissions(segment, directory = false)
        return segment
    }

    fun deleteSegment(segment: File): Unit {
        if (segment.parentFile?.canonicalFile != directory.canonicalFile) return
        if (segment.exists() && !segment.delete()) throw IOException("Unable to reclaim meeting audio segment")
    }

    override fun close(): Unit {
        if (closed) return
        closed = true
        try {
            if (directory.exists()) directory.deleteRecursively()
        } finally {
            try {
                ownerLock.release()
            } finally {
                ownerFile.close()
            }
        }
    }

    private fun checkOpen() {
        check(!closed) { "Meeting audio spool is closed" }
    }

    private companion object {
        const val OWNER_LOCK_NAME = ".owner.lock"
        const val SWEEP_LOCK_NAME = ".sweep.lock"
        val PROCESS_SETUP_LOCK = Any()

        fun ensurePrivateDirectory(path: File): File {
            if (!path.exists() && !path.mkdirs()) throw IOException("Unable to create private meeting audio cache")
            if (!path.isDirectory) throw IOException("Meeting audio cache path is not a directory")
            setPrivatePermissions(path, directory = true)
            return path.canonicalFile
        }

        fun setPrivatePermissions(path: File, directory: Boolean) {
            path.setReadable(false, false)
            path.setWritable(false, false)
            path.setExecutable(false, false)
            val readable = path.setReadable(true, true)
            val writable = path.setWritable(true, true)
            val executable = !directory || path.setExecutable(true, true)
            if (!readable || !writable || !executable) throw IOException("Unable to protect meeting audio spool")
        }

        fun sweepOrphans(root: File) {
            val rootCanonical = root.canonicalFile
            root.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("session-") }
                .forEach { candidate ->
                    if (candidate.canonicalFile.parentFile != rootCanonical) return@forEach
                    sweepIfUnowned(candidate)
                }
        }

        fun sweepIfUnowned(candidate: File) {
            val owner = File(candidate, OWNER_LOCK_NAME)
            if (!owner.exists()) {
                candidate.deleteRecursively()
                return
            }
            val handle = try {
                RandomAccessFile(owner, "rw")
            } catch (_: Throwable) {
                return
            }
            val lock = try {
                handle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: Throwable) {
                null
            }
            if (lock == null) {
                handle.close()
                return
            }
            try {
                candidate.deleteRecursively()
            } finally {
                try {
                    lock.release()
                } finally {
                    handle.close()
                }
            }
        }
    }
}

internal class MeetingAudioSpoolException(cause: Throwable) : IOException("Meeting audio spool I/O failed", cause)
