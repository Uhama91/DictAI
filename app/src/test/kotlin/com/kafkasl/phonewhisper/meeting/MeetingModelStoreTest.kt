package com.kafkasl.phonewhisper.meeting

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeetingModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val stores = mutableListOf<MeetingModelStore>()

    init {
        server.start()
    }

    @After
    fun tearDown() {
        stores.forEach(MeetingModelStore::shutdownForTests)
        server.shutdown()
    }

    @Test
    fun downloadPublishesReadyOnlyAfterBothFilesAreVerified() {
        val asr = "asr test bytes".toByteArray()
        val diarization = "diarization test bytes".toByteArray()
        enqueueBody(asr)
        enqueueBody(diarization)
        val root = temporaryFolder.newFolder("store")
        val store = newStore(root, catalog(asr, diarization))

        store.download()
        val ready = awaitState(store) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        assertEquals(asr.toList(), ready.paths.asrFile.readBytes().toList())
        assertEquals(diarization.toList(), ready.paths.diarizationFile.readBytes().toList())
        assertTrue(ready.paths.packageDirectory.name.startsWith("${catalog(asr, diarization).packageDirectoryPrefix}"))
        assertEquals(2, server.requestCount)

        val reopenedStore = newStore(root, catalog(asr, diarization))
        reopenedStore.refresh()
        awaitState(reopenedStore) { it is MeetingModelStoreState.Ready }
        assertEquals("refresh should reuse the verified package", 2, server.requestCount)
    }

    @Test
    fun explicitDownloadCopiesAndVerifiesPrivateCandidatesWhileRefreshNeverConsultsThem() {
        val asr = "Handy test bytes".toByteArray()
        val diarization = "legacy diarization test bytes".toByteArray()
        val privateFilesDirectory = temporaryFolder.newFolder("reuse-explicit")
        val root = File(privateFilesDirectory, "meeting-models")
        val candidates = candidateFiles(privateFilesDirectory, root, asr, diarization)
        var providerCalls = 0
        val provider = MeetingModelReuseCandidateProvider { _, _ ->
            providerCalls += 1
            listOf(candidates)
        }
        val store = newStore(root, catalog(asr, diarization, version = "v2-test"), candidateProvider = provider)

        val refreshEvents = assertRefreshEndsMissing(store)
        assertFalse("a refresh must not publish an uninstalled source pair", refreshEvents.any { it is MeetingModelStoreState.Ready })
        assertEquals("refresh is inspection only", 0, providerCalls)
        assertEquals(0, server.requestCount)

        store.download()
        val ready = awaitState(store) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        assertEquals(asr.toList(), ready.paths.asrFile.readBytes().toList())
        assertEquals(diarization.toList(), ready.paths.diarizationFile.readBytes().toList())
        assertEquals("explicit download reuses verified local files without HTTP", 0, server.requestCount)
        assertEquals(1, providerCalls)
        assertEquals(asr.toList(), requireNotNull(candidates.asrFile).readBytes().toList())
        assertEquals(diarization.toList(), requireNotNull(candidates.diarizationFile).readBytes().toList())
    }

    @Test
    fun sameSizeCorruptCandidateFallsBackToTheNormalVerifiedDownload() {
        val asr = "Handy bytes".toByteArray()
        val diarization = "diar bytes".toByteArray()
        val corruptAsr = asr.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val privateFilesDirectory = temporaryFolder.newFolder("reuse-corrupt")
        val root = File(privateFilesDirectory, "meeting-models")
        val candidates = candidateFiles(privateFilesDirectory, root, corruptAsr, diarization)
        enqueueBody(asr)
        val store = newStore(
            root,
            catalog(asr, diarization, version = "v2-corrupt-fallback"),
            candidateProvider = MeetingModelReuseCandidateProvider { _, _ -> listOf(candidates) },
        )

        store.download()
        val ready = awaitState(store) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        assertEquals(asr.toList(), ready.paths.asrFile.readBytes().toList())
        assertEquals(diarization.toList(), ready.paths.diarizationFile.readBytes().toList())
        assertEquals("only the corrupt ASR artifact is fetched", 1, server.requestCount)
        assertEquals("invalid sources are never modified", corruptAsr.toList(), requireNotNull(candidates.asrFile).readBytes().toList())
        assertEquals(diarization.toList(), requireNotNull(candidates.diarizationFile).readBytes().toList())
    }

    @Test
    fun externalSymlinkCandidateIsRejectedAndFallsBackWithoutPublishingIt() {
        val asr = "Handy symlink target".toByteArray()
        val diarization = "diar symlink target".toByteArray()
        val privateFilesDirectory = temporaryFolder.newFolder("reuse-private")
        val root = File(privateFilesDirectory, "meeting-models")
        val inside = candidateFiles(privateFilesDirectory, root, asr, diarization)
        val outsideDirectory = temporaryFolder.newFolder("reuse-outside")
        val outsideAsr = File(outsideDirectory, "asr-outside.gguf").apply { writeBytes(asr) }
        val link = File(privateFilesDirectory, "models/asr-link.gguf").apply { parentFile?.mkdirs() }
        Files.createSymbolicLink(link.toPath(), outsideAsr.toPath())
        enqueueBody(asr)
        val store = newStore(
            root,
            catalog(asr, diarization, version = "v2-symlink"),
            candidateProvider = MeetingModelReuseCandidateProvider { _, _ ->
                listOf(MeetingModelReuseCandidates(link, inside.diarizationFile))
            },
        )

        store.download()
        val ready = awaitState(store) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        assertEquals(asr.toList(), ready.paths.asrFile.readBytes().toList())
        assertEquals("only the rejected symlink artifact is downloaded", 1, server.requestCount)
        assertEquals("the symlink target remains untouched", asr.toList(), outsideAsr.readBytes().toList())
    }

    @Test
    fun handyCandidateIsReusedWhenLegacyDiarizationIsMissing() {
        val asr = "Handy available locally".toByteArray()
        val diarization = "diar fetched from server".toByteArray()
        val privateFilesDirectory = temporaryFolder.newFolder("reuse-asr-only")
        val root = File(privateFilesDirectory, "meeting-models")
        val handyAsr = File(privateFilesDirectory, "models/handy/asr.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(asr)
        }
        enqueueBody(diarization)
        val store = newStore(
            root,
            catalog(asr, diarization, version = "v2-asr-only"),
            candidateProvider = MeetingModelReuseCandidateProvider { _, _ ->
                listOf(MeetingModelReuseCandidates(asrFile = handyAsr))
            },
        )

        store.download()
        val ready = awaitState(store) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        assertEquals(asr.toList(), ready.paths.asrFile.readBytes().toList())
        assertEquals(diarization.toList(), ready.paths.diarizationFile.readBytes().toList())
        assertEquals("only the missing diarization artifact is fetched", 1, server.requestCount)
        assertEquals(asr.toList(), handyAsr.readBytes().toList())
    }

    @Test
    fun legacyDiarizationCandidateIsReusedWhenHandyIsMissing() {
        val asr = "Handy fetched from server".toByteArray()
        val diarization = "legacy diar available locally".toByteArray()
        val privateFilesDirectory = temporaryFolder.newFolder("reuse-diar-only")
        val root = File(privateFilesDirectory, "meeting-models")
        val oldDiarization = File(root, "legacy-package/diarization.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(diarization)
        }
        enqueueBody(asr)
        val store = newStore(
            root,
            catalog(asr, diarization, version = "v2-diar-only"),
            candidateProvider = MeetingModelReuseCandidateProvider { _, _ ->
                listOf(MeetingModelReuseCandidates(diarizationFile = oldDiarization))
            },
        )

        store.download()
        val ready = awaitState(store) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        assertEquals(asr.toList(), ready.paths.asrFile.readBytes().toList())
        assertEquals(diarization.toList(), ready.paths.diarizationFile.readBytes().toList())
        assertEquals("only the missing Handy ASR artifact is fetched", 1, server.requestCount)
        assertEquals(diarization.toList(), oldDiarization.readBytes().toList())
    }

    @Test
    fun privateReuseCreatesParentsForNestedArtifactPartFiles() {
        val asr = "nested Handy model".toByteArray()
        val diarization = "nested diarization model".toByteArray()
        val privateFilesDirectory = temporaryFolder.newFolder("reuse-nested")
        val root = File(privateFilesDirectory, "meeting-models")
        val candidates = candidateFiles(privateFilesDirectory, root, asr, diarization)
        val nestedCatalog = MeetingModelCatalog(
            packageName = "meeting-nested",
            version = "v2-test",
            asr = MeetingModelArtifact(
                id = "asr",
                relativePath = "handy/asr.gguf",
                url = server.url("/nested/asr.gguf").toString(),
                sizeBytes = asr.size.toLong(),
                sha256 = sha256(asr),
            ),
            diarization = MeetingModelArtifact(
                id = "diarization",
                relativePath = "diarization/segments/model.gguf",
                url = server.url("/nested/diarization.gguf").toString(),
                sizeBytes = diarization.size.toLong(),
                sha256 = sha256(diarization),
            ),
        )
        val store = newStore(
            root,
            nestedCatalog,
            candidateProvider = MeetingModelReuseCandidateProvider { _, _ -> listOf(candidates) },
        )

        store.download()
        val ready = awaitState(store) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        assertEquals(asr.toList(), ready.paths.asrFile.readBytes().toList())
        assertEquals(diarization.toList(), ready.paths.diarizationFile.readBytes().toList())
        assertEquals("nested private files are reused without network requests", 0, server.requestCount)
    }

    @Test
    fun cancellationDuringCandidateCopyNeverPublishesReadyOrDeletesSourceFiles() {
        val asr = ByteArray(16 * 1024) { (it % 127).toByte() }
        val diarization = "private diar source".toByteArray()
        val privateFilesDirectory = temporaryFolder.newFolder("reuse-cancel")
        val root = File(privateFilesDirectory, "meeting-models")
        val candidates = candidateFiles(privateFilesDirectory, root, asr, diarization)
        val copyEntered = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val stateAtCopy = AtomicReference<MeetingModelStoreState?>()
        val storeReference = AtomicReference<MeetingModelStore?>()
        val store = newStore(
            root,
            catalog(asr, diarization, version = "v2-cancel"),
            candidateProvider = MeetingModelReuseCandidateProvider { _, _ -> listOf(candidates) },
            reuseCopyCheckpoint = {
                stateAtCopy.set(storeReference.get()?.currentState)
                if (copyEntered.count > 0) {
                    copyEntered.countDown()
                    releaseCopy.await(5, TimeUnit.SECONDS)
                }
            },
        )
        storeReference.set(store)

        try {
            store.download()
            assertTrue("candidate bytes reached the cancellable copy", copyEntered.await(5, TimeUnit.SECONDS))
            store.cancelDownload()
        } finally {
            releaseCopy.countDown()
        }

        awaitState(store) { it is MeetingModelStoreState.Missing }
        assertFalse("cancelled copying must not report a complete pair", store.currentState is MeetingModelStoreState.Ready)
        assertEquals(
            "copy must already be exposed as an explicitly cancellable download",
            MeetingModelStoreState.Downloading(0L, catalog(asr, diarization, version = "v2-cancel").totalBytes),
            stateAtCopy.get(),
        )
        assertEquals(0, server.requestCount)
        assertEquals(asr.toList(), requireNotNull(candidates.asrFile).readBytes().toList())
        assertEquals(diarization.toList(), requireNotNull(candidates.diarizationFile).readBytes().toList())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".meeting-models-op-") })
        assertTrue(root.listFiles().orEmpty().none { store.catalog.isPackageDirectoryName(it.name) })
    }

    @Test
    fun previousNemotronPackageIsNotReadyUnderV2AndExplicitUpgradeKeepsEverySource() {
        val previousAsr = "old Nemotron ASR".toByteArray()
        val diarization = "same old diarization".toByteArray()
        val handyAsr = "new Handy Q8 model".toByteArray()
        val privateFilesDirectory = temporaryFolder.newFolder("v1-to-v2")
        val root = File(privateFilesDirectory, "meeting-models")
        val oldCatalog = catalog(previousAsr, diarization, version = "v1-asr-nemotron-diar-v1")
        val oldDirectory = File(root, oldCatalog.packageDirectoryName("a1b2c3d4")).apply { mkdirs() }
        val oldAsrFile = File(oldDirectory, oldCatalog.asr.relativePath).apply { writeBytes(previousAsr) }
        val oldDiarFile = File(oldDirectory, oldCatalog.diarization.relativePath).apply { writeBytes(diarization) }
        val legacyReader = newStore(root, oldCatalog)
        legacyReader.refresh()
        val oldReady = awaitState(legacyReader) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready
        assertEquals(previousAsr.toList(), oldReady.paths.asrFile.readBytes().toList())

        val handySource = File(privateFilesDirectory, "models/handy/asr.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(handyAsr)
        }
        val targetCatalog = catalog(handyAsr, diarization, version = "v2-asr-handy-diar-v1")
        val newStore = newStore(
            root,
            targetCatalog,
            candidateProvider = MeetingModelReuseCandidateProvider { _, _ ->
                listOf(MeetingModelReuseCandidates(handySource, oldDiarFile))
            },
        )

        val events = assertRefreshEndsMissing(newStore)
        assertFalse("a matching v1 Nemotron pair is not a v2 Handy package", events.any { it is MeetingModelStoreState.Ready })
        newStore.download()
        val ready = awaitState(newStore) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        assertEquals(handyAsr.toList(), ready.paths.asrFile.readBytes().toList())
        assertEquals(diarization.toList(), ready.paths.diarizationFile.readBytes().toList())
        assertEquals(previousAsr.toList(), oldAsrFile.readBytes().toList())
        assertEquals(diarization.toList(), oldDiarFile.readBytes().toList())
        assertEquals(handyAsr.toList(), handySource.readBytes().toList())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refreshRejectsSameSizeHashCorruptionAndMissingModelFile() {
        val asr = byteArrayOf(21, 22, 23, 24)
        val diarization = byteArrayOf(31, 32, 33)
        enqueueBody(asr)
        enqueueBody(diarization)
        val root = temporaryFolder.newFolder("refresh-integrity")
        val modelCatalog = catalog(asr, diarization)
        val installer = newStore(root, modelCatalog)
        installer.download()
        val installed = awaitState(installer) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        val reopened = newStore(root, modelCatalog)
        val changed = asr.copyOf().also { it[0] = (it[0] + 1).toByte() }
        installed.paths.asrFile.writeBytes(changed)
        val corruptedEvents = assertRefreshEndsMissing(reopened)
        assertFalse("same-size corruption must never be reported Ready", corruptedEvents.any { it is MeetingModelStoreState.Ready })
        assertFalse(reopened.currentState is MeetingModelStoreState.Ready)

        installed.paths.asrFile.writeBytes(asr)
        assertTrue(installed.paths.diarizationFile.delete())
        val missingEvents = assertRefreshEndsMissing(reopened)
        assertFalse("a missing paired file must never be reported Ready", missingEvents.any { it is MeetingModelStoreState.Ready })
        assertFalse(reopened.currentState is MeetingModelStoreState.Ready)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun missingSecondFileFailsAndCleansOnlyItsOwnStagingDirectory() {
        val asr = byteArrayOf(1, 2, 3)
        val diarization = byteArrayOf(4, 5)
        enqueueBody(asr)
        server.enqueue(MockResponse().setResponseCode(404))
        val root = temporaryFolder.newFolder("missing-second")
        val unrelated = File(root, "unrelated-cache").apply { mkdirs() }
        File(unrelated, "keep.txt").writeText("keep")
        val store = newStore(root, catalog(asr, diarization))

        store.download()
        val error = awaitState(store) { it is MeetingModelStoreState.Error } as MeetingModelStoreState.Error

        assertEquals(MeetingModelStoreState.DOWNLOAD_ERROR_MESSAGE, error.message)
        assertTrue(File(unrelated, "keep.txt").exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".meeting-models-op-") })
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(store.catalog.packageDirectoryPrefix) })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun truncationWrongShaAndOversizedResponsesNeverBecomeReady() {
        val bytes = byteArrayOf(10, 20, 30, 40)

        enqueueBody(byteArrayOf(10, 20))
        val truncatedStore = newStore(
            temporaryFolder.newFolder("truncated"),
            catalog(bytes, byteArrayOf(50), asrSize = bytes.size.toLong()),
        )
        truncatedStore.download()
        assertError(truncatedStore)
        assertFalse(truncatedStore.currentState is MeetingModelStoreState.Ready)

        enqueueBody(bytes)
        val wrongHashStore = newStore(
            temporaryFolder.newFolder("wrong-sha"),
            catalog(bytes, byteArrayOf(50), asrSha = "0".repeat(64)),
        )
        wrongHashStore.download()
        assertError(wrongHashStore)
        assertFalse(wrongHashStore.currentState is MeetingModelStoreState.Ready)

        enqueueBody(bytes + byteArrayOf(60))
        val oversizedStore = newStore(
            temporaryFolder.newFolder("oversized"),
            catalog(bytes, byteArrayOf(50), asrSize = bytes.size.toLong()),
        )
        oversizedStore.download()
        assertError(oversizedStore)
        assertFalse(oversizedStore.currentState is MeetingModelStoreState.Ready)
    }

    @Test
    fun insufficientSpaceStopsBeforeAnyRequest() {
        val store = newStore(
            temporaryFolder.newFolder("low-space"),
            catalog(byteArrayOf(1, 2), byteArrayOf(3, 4)),
            availableBytes = { 4L },
            spaceMarginBytes = 1L,
        )

        store.download()
        val error = awaitState(store) { it is MeetingModelStoreState.Error } as MeetingModelStoreState.Error

        assertEquals(MeetingModelStoreState.INSUFFICIENT_SPACE_MESSAGE, error.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellationClosesBlockedRequestAndALaterDownloadCanSucceed() {
        val asr = byteArrayOf(1, 2, 3)
        val diarization = byteArrayOf(4, 5, 6)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val root = temporaryFolder.newFolder("cancel")
        val store = newStore(root, catalog(asr, diarization))
        val observed = mutableListOf<MeetingModelStoreState>()
        val observer: (MeetingModelStoreState) -> Unit = { synchronized(observed) { observed += it } }
        store.addListener(observer)

        store.download()
        awaitState(store) { it is MeetingModelStoreState.Downloading }
        assertNotNull("blocked request should reach the local server", server.takeRequest(5, TimeUnit.SECONDS))
        store.cancelDownload()
        awaitState(store) { it is MeetingModelStoreState.Missing }
        assertTrue(synchronized(observed) { observed.none { it is MeetingModelStoreState.Ready } })

        enqueueBody(asr)
        enqueueBody(diarization)
        store.download()
        awaitState(store) { it is MeetingModelStoreState.Ready }
        assertEquals(3, server.requestCount)
        store.removeListener(observer)
    }

    @Test
    fun failedNewVersionKeepsPreviouslyPublishedPackageIntact() {
        val oldAsr = "old asr".toByteArray()
        val oldDiarization = "old diarization".toByteArray()
        enqueueBody(oldAsr)
        enqueueBody(oldDiarization)
        val root = temporaryFolder.newFolder("versioned")
        val oldStore = newStore(root, catalog(oldAsr, oldDiarization, version = "old-v1"))
        oldStore.download()
        val oldReady = awaitState(oldStore) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        val newAsr = "new asr".toByteArray()
        val newDiarization = "new diarization".toByteArray()
        enqueueBody(newAsr)
        server.enqueue(MockResponse().setResponseCode(503))
        val newStore = newStore(root, catalog(newAsr, newDiarization, version = "new-v2"))
        newStore.download()
        assertError(newStore)

        assertEquals(oldAsr.toList(), oldReady.paths.asrFile.readBytes().toList())
        assertEquals(oldDiarization.toList(), oldReady.paths.diarizationFile.readBytes().toList())
        assertTrue(oldReady.paths.packageDirectory.exists())
    }

    @Test
    fun publicationFailurePreservesTheOldPackageAndDoesNotExposeFilesystemDetails() {
        val oldAsr = byteArrayOf(1, 2)
        val oldDiarization = byteArrayOf(3, 4)
        enqueueBody(oldAsr)
        enqueueBody(oldDiarization)
        val root = temporaryFolder.newFolder("publish-old")
        val oldStore = newStore(root, catalog(oldAsr, oldDiarization, version = "previous"))
        oldStore.download()
        val previous = awaitState(oldStore) { it is MeetingModelStoreState.Ready } as MeetingModelStoreState.Ready

        val replacementAsr = byteArrayOf(5, 6)
        val replacementDiarization = byteArrayOf(7, 8)
        enqueueBody(replacementAsr)
        enqueueBody(replacementDiarization)
        val failedStore = newStore(
            root,
            catalog(replacementAsr, replacementDiarization, version = "replacement"),
            publishDirectory = { _, _ -> throw IOException("/private/path should not be shown") },
        )
        failedStore.download()
        val error = awaitState(failedStore) { it is MeetingModelStoreState.Error } as MeetingModelStoreState.Error

        assertEquals(MeetingModelStoreState.DOWNLOAD_ERROR_MESSAGE, error.message)
        assertFalse(error.message.contains("private"))
        assertEquals(oldAsr.toList(), previous.paths.asrFile.readBytes().toList())
        assertEquals(oldDiarization.toList(), previous.paths.diarizationFile.readBytes().toList())
        assertTrue(previous.paths.packageDirectory.exists())
    }

    @Test
    fun cancellationAfterAtomicRenameSuppressesReadyAndRefreshCanAdoptThePackage() {
        val asr = "publish-race-asr".toByteArray()
        val diarization = "publish-race-diarization".toByteArray()
        enqueueBody(asr)
        enqueueBody(diarization)
        val root = temporaryFolder.newFolder("publish-cancel-race")
        val modelCatalog = catalog(asr, diarization)
        val publisherEntered = CountDownLatch(1)
        val releasePublisher = CountDownLatch(1)
        val store = newStore(
            root,
            modelCatalog,
            publishDirectory = { source, destination ->
                java.nio.file.Files.move(
                    source.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
                publisherEntered.countDown()
                if (!releasePublisher.await(5, TimeUnit.SECONDS)) throw IOException("publisher test timed out")
            },
        )
        val events = CopyOnWriteArrayList<MeetingModelStoreState>()
        val listener: (MeetingModelStoreState) -> Unit = { events.add(it) }
        store.addListener(listener)
        store.download()
        assertTrue("atomic rename barrier should be reached", publisherEntered.await(5, TimeUnit.SECONDS))

        val cancellationExecutor = Executors.newSingleThreadExecutor()
        val eventCountAtCancel: Int
        try {
            cancellationExecutor.submit { store.cancelDownload() }.get(500, TimeUnit.MILLISECONDS)
            eventCountAtCancel = events.size
        } finally {
            releasePublisher.countDown()
            cancellationExecutor.shutdownNow()
        }

        awaitState(store) { it is MeetingModelStoreState.Missing }
        assertFalse("cancelled operation must not publish Ready", events.any { it is MeetingModelStoreState.Ready })
        assertFalse(
            "cancelled operation must not publish progress after cancellation",
            events.drop(eventCountAtCancel).any { it is MeetingModelStoreState.Downloading },
        )
        val committedDirectory = root.listFiles().orEmpty().singleOrNull {
            it.isDirectory && modelCatalog.isPackageDirectoryName(it.name)
        }
        assertNotNull("the complete atomic package may remain recoverable", committedDirectory)
        store.removeListener(listener)

        val reopened = newStore(root, modelCatalog)
        reopened.refresh()
        awaitState(reopened) { it is MeetingModelStoreState.Ready }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun concurrentDownloadCommandsCreateOnlyOnePairOfRequests() {
        val asr = "concurrent asr".toByteArray()
        val diarization = "concurrent diarization".toByteArray()
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(asr))
                .setBodyDelay(200, TimeUnit.MILLISECONDS),
        )
        enqueueBody(diarization)
        val store = newStore(temporaryFolder.newFolder("concurrent"), catalog(asr, diarization))
        val callers = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)

        try {
            repeat(8) {
                callers.submit {
                    gate.await()
                    store.download()
                }
            }
            gate.countDown()
            awaitState(store) { it is MeetingModelStoreState.Ready }
        } finally {
            callers.shutdownNow()
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun listenerCallbacksRunOutsideTheStateLockAndExceptionsCannotStopWork() {
        val asr = byteArrayOf(11, 12)
        val diarization = byteArrayOf(13, 14)
        enqueueBody(asr)
        enqueueBody(diarization)
        val store = newStore(temporaryFolder.newFolder("listeners"), catalog(asr, diarization))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker: (MeetingModelStoreState) -> Unit = {
            if (it is MeetingModelStoreState.Downloading) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        val throwing: (MeetingModelStoreState) -> Unit = { throw IllegalStateException("listener failure") }
        store.addListener(blocker)
        store.addListener(throwing)

        store.download()
        assertTrue("download progress callback should run", entered.await(2, TimeUnit.SECONDS))
        val cancelCall = Executors.newSingleThreadExecutor()
        try {
            val cancellation = cancelCall.submit { store.cancelDownload() }
            cancellation.get(500, TimeUnit.MILLISECONDS)
        } finally {
            release.countDown()
            cancelCall.shutdownNow()
        }
        awaitState(store) { it is MeetingModelStoreState.Missing }
        assertEquals("cancel during blocked callback must prevent the HTTP request", 0, server.requestCount)

        store.removeListener(blocker)
        store.removeListener(throwing)
        enqueueBody(asr)
        enqueueBody(diarization)
        store.download()
        awaitState(store) { it is MeetingModelStoreState.Ready }
        assertEquals(2, server.requestCount)
    }

    private fun assertRefreshEndsMissing(store: MeetingModelStore): List<MeetingModelStoreState> {
        val events = CopyOnWriteArrayList<MeetingModelStoreState>()
        val sawChecking = java.util.concurrent.atomic.AtomicBoolean(false)
        val complete = CountDownLatch(1)
        val listener: (MeetingModelStoreState) -> Unit = { state ->
            events += state
            if (state === MeetingModelStoreState.Checking) sawChecking.set(true)
            if (sawChecking.get() && state === MeetingModelStoreState.Missing) complete.countDown()
        }
        store.addListener(listener)
        try {
            store.refresh()
            assertTrue("refresh should finish with Missing after checking the files", complete.await(5, TimeUnit.SECONDS))
            return events.toList()
        } finally {
            store.removeListener(listener)
        }
    }

    private fun assertError(store: MeetingModelStore) {
        val error = awaitState(store) { it is MeetingModelStoreState.Error } as MeetingModelStoreState.Error
        assertEquals(MeetingModelStoreState.DOWNLOAD_ERROR_MESSAGE, error.message)
    }

    private fun enqueueBody(bytes: ByteArray) {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
    }

    private fun newStore(
        root: File,
        catalog: MeetingModelCatalog,
        availableBytes: (File) -> Long = { Long.MAX_VALUE },
        spaceMarginBytes: Long = 0L,
        candidateProvider: MeetingModelReuseCandidateProvider = MeetingModelReuseCandidateProvider { _, _ -> emptyList() },
        reuseCopyCheckpoint: (Long) -> Unit = {},
        publishDirectory: (File, File) -> Unit = { source, destination ->
            java.nio.file.Files.move(
                source.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        },
    ): MeetingModelStore = MeetingModelStore(
        filesDirectory = root,
        catalog = catalog,
        availableBytes = availableBytes,
        spaceMarginBytes = spaceMarginBytes,
        reuseCandidateProvider = candidateProvider,
        reuseCopyCheckpoint = reuseCopyCheckpoint,
        publishDirectory = publishDirectory,
    ).also(stores::add)

    private fun candidateFiles(
        privateFilesDirectory: File,
        storeDirectory: File,
        asrBytes: ByteArray,
        diarizationBytes: ByteArray,
    ): MeetingModelReuseCandidates {
        val asr = File(privateFilesDirectory, "models/test-source/asr.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(asrBytes)
        }
        val diarization = File(storeDirectory, "legacy-source/diarization.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(diarizationBytes)
        }
        return MeetingModelReuseCandidates(asr, diarization)
    }

    private fun catalog(
        asrBytes: ByteArray,
        diarizationBytes: ByteArray,
        version: String = "test-v1",
        asrSize: Long = asrBytes.size.toLong(),
        asrSha: String = sha256(asrBytes),
    ): MeetingModelCatalog = MeetingModelCatalog(
        packageName = "meeting-test",
        version = version,
        asr = MeetingModelArtifact(
            id = "asr",
            relativePath = "asr.gguf",
            url = server.url("/$version/asr.gguf").toString(),
            sizeBytes = asrSize,
            sha256 = asrSha,
        ),
        diarization = MeetingModelArtifact(
            id = "diarization",
            relativePath = "diarization.gguf",
            url = server.url("/$version/diarization.gguf").toString(),
            sizeBytes = diarizationBytes.size.toLong(),
            sha256 = sha256(diarizationBytes),
        ),
    )

    private fun awaitState(
        store: MeetingModelStore,
        timeoutSeconds: Long = 5,
        predicate: (MeetingModelStoreState) -> Boolean,
    ): MeetingModelStoreState {
        val result = AtomicReference<MeetingModelStoreState>()
        val latch = CountDownLatch(1)
        val listener: (MeetingModelStoreState) -> Unit = { state ->
            if (predicate(state)) {
                result.set(state)
                latch.countDown()
            }
        }
        store.addListener(listener)
        try {
            store.currentState.let { state ->
                if (predicate(state)) {
                    result.set(state)
                    latch.countDown()
                }
            }
            assertTrue("Timed out waiting for store state; current=${store.currentState}", latch.await(timeoutSeconds, TimeUnit.SECONDS))
            return result.get()
        } finally {
            store.removeListener(listener)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }
}
