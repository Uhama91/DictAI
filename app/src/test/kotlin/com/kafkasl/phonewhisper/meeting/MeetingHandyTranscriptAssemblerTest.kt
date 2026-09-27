package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingHandyTranscriptAssemblerTest {
    @Test
    fun preserves_utf8_apostrophes_and_punctuation_while_aligning_lexical_timing() {
        val nativeParts = listOf(
            byteArrayOf('C'.code.toByte()),
            byteArrayOf('a'.code.toByte(), 'f'.code.toByte()),
            byteArrayOf(0xC3.toByte()),
            byteArrayOf(0xA9.toByte()),
            " ".toByteArray(),
            "d'accord".toByteArray(),
            ",".toByteArray(),
            " ".toByteArray(),
            "ça".toByteArray(),
            " ".toByteArray(),
            "va".toByteArray(),
            ".".toByteArray(),
        )
        val tokenTimes = listOf(
            0L to 10L,
            10L to 20L,
            20L to 30L,
            30L to 40L,
            40L to 42L,
            50L to 70L,
            70L to 95L,
            95L to 100L,
            100L to 120L,
            120L to 125L,
            130L to 150L,
            160L to 500L,
        )
        val assembler = MeetingHandyTranscriptAssembler()

        val updates = assembler.update(
            window = window("Café d'accord, ça va.", nativeParts, tokenTimes),
            audioProcessedMs = 1_000L,
            isFinal = true,
        )

        assertEquals(1, updates.size)
        val update = updates.single()
        assertEquals("Café d'accord, ça va.", update.transcript)
        assertEquals(listOf("Café", "d'accord,", "ça", "va."), update.words.map { it.text })
        assertEquals(40L, update.words.first().endMs)
        assertEquals(70L, update.words[1].endMs)
        assertEquals(150L, update.words.last().endMs)
        assertTrue(update.words.all { it.channel == 0 })

        val reducer = MeetingTranscriptReducer("source", "run")
        reducer.apply(update.asHypothesis("run"))
        assertEquals("Café d'accord, ça va.", reducer.snapshot().turns.single().recognizedText)

        val diarized = assembler.reviseDiarization(
            diarization(firstFrame = 0L, frameCount = 200, stableFrameCount = 200L, totalFrameCount = 200L),
            audioProcessedMs = 1_000L,
        ).single()
        assertTrue(diarized.words.all { it.channel == 1 })
        val attributedReducer = MeetingTranscriptReducer("voice", "run")
        attributedReducer.apply(diarized.asHypothesis("run"))
        assertEquals("voice:participant:1", attributedReducer.snapshot().turns.single().automaticParticipantId)
    }

    @Test
    fun retains_an_unfinished_suffix_without_assigning_or_sealing_its_partial_word() {
        val first = window(
            fullText = "Bonjour salu",
            parts = listOf("Bonjour".toByteArray(), " ".toByteArray(), "salu".toByteArray()),
            times = listOf(0L to 100L, 100L to 110L, 110L to 180L),
        )
        val assembler = MeetingHandyTranscriptAssembler()

        val interim = assembler.update(first, audioProcessedMs = 1_000L, isFinal = false).single()

        assertEquals("Bonjour salu", interim.transcript)
        assertEquals(listOf("Bonjour"), interim.words.map { it.text })
        assertFalse(interim.isFinal)

        val completed = assembler.update(
            window(
                fullText = "Bonjour salut",
                parts = listOf("Bonjour".toByteArray(), " ".toByteArray(), "salut".toByteArray()),
                times = listOf(0L to 100L, 100L to 110L, 110L to 200L),
            ),
            audioProcessedMs = 1_000L,
            isFinal = true,
        ).single()

        assertEquals(interim.utteranceId, completed.utteranceId)
        assertTrue(completed.revision > interim.revision)
        assertEquals("Bonjour salut", completed.transcript)
        assertEquals(listOf("Bonjour", "salut"), completed.words.map { it.text })
        assertTrue(completed.isFinal)
    }

    @Test
    fun falls_back_to_authoritative_text_when_token_alignment_is_not_exact() {
        val assembler = MeetingHandyTranscriptAssembler()

        val update = assembler.update(
            window(
                fullText = "Bonjour tout le monde",
                parts = listOf("Bonjour le monde".toByteArray()),
                times = listOf(0L to 400L),
            ),
            audioProcessedMs = 1_000L,
            isFinal = true,
        ).single()

        assertEquals("Bonjour tout le monde", update.transcript)
        assertTrue(update.words.isEmpty())
        assertEquals(0L, update.stableSpeakerThroughMs)
    }

    @Test
    fun waits_for_a_trailing_utf8_code_point_without_poisoning_later_alignment() {
        val assembler = MeetingHandyTranscriptAssembler()
        val partial = window(
            fullText = "Café",
            fullTextUtf8 = "Caf".toByteArray() + byteArrayOf(0xC3.toByte()),
            parts = listOf("C".toByteArray(), "a".toByteArray(), "f".toByteArray(), byteArrayOf(0xC3.toByte())),
            times = listOf(0L to 10L, 10L to 20L, 20L to 30L, 30L to 40L),
        )

        val interim = assembler.update(partial, audioProcessedMs = 200L, isFinal = false).single()

        assertEquals("Caf", interim.transcript)
        assertTrue(interim.words.isEmpty())
        val completed = assembler.update(
            window(
                fullText = "Café",
                parts = listOf(
                    "C".toByteArray(), "a".toByteArray(), "f".toByteArray(),
                    byteArrayOf(0xC3.toByte()), byteArrayOf(0xA9.toByte()),
                ),
                times = listOf(0L to 10L, 10L to 20L, 20L to 30L, 30L to 40L, 40L to 50L),
            ),
            audioProcessedMs = 200L,
            isFinal = true,
        ).single()

        assertEquals(interim.utteranceId, completed.utteranceId)
        assertEquals("Café", completed.transcript)
        assertEquals(listOf("Café"), completed.words.map { it.text })
        assertEquals(50L, completed.words.single().endMs)
    }

    @Test
    fun retries_before_a_sliding_token_window_splits_an_accented_character() {
        val text = "a".repeat(128) + "é ok"
        val parts = text.toByteArray(Charsets.UTF_8).map { byteArrayOf(it) }
        val times = parts.indices.map { index -> index * 10L to (index + 1L) * 10L }
        val assembler = MeetingHandyTranscriptAssembler()

        val partial = assembler.update(
            window(
                fullText = text,
                parts = parts.drop(129),
                times = times.drop(129),
                firstTokenIndex = 129,
                totalTokenCount = parts.size,
            ),
            audioProcessedMs = 2_000L,
            isFinal = false,
        ).single()

        assertEquals(text, partial.transcript)
        assertTrue(partial.words.isEmpty())
        assertEquals(128, assembler.nextSnapshotFirstTokenIndex)

        val aligned = assembler.update(
            window(text, parts, times),
            audioProcessedMs = 2_000L,
            isFinal = true,
        ).single()
        assertEquals(listOf("a".repeat(128) + "é", "ok"), aligned.words.map { it.text })
    }

    @Test
    fun retries_one_token_at_a_time_for_a_split_utf8_codepoint_after_the_8192_token_window() {
        val fullText = "a ".repeat(9_000) + "🧠 " + "b".repeat(126)
        val parts = fullText.toByteArray(Charsets.UTF_8).map { byteArrayOf(it) }
        val times = parts.indices.map { index -> index.toLong() to index + 1L }
        val totalTokens = parts.size
        val assembler = MeetingHandyTranscriptAssembler(maxRetainedChunks = 256)
        val firstSnapshotIndex = (totalTokens - 8_192).coerceAtLeast(0)

        assembler.update(
            window(
                fullText = fullText,
                parts = parts.drop(firstSnapshotIndex),
                times = times.drop(firstSnapshotIndex),
                firstTokenIndex = firstSnapshotIndex,
                totalTokenCount = totalTokens,
            ),
            audioProcessedMs = totalTokens + 1_000L,
            isFinal = false,
        )

        // The rolling 128-token overlap starts on the final continuation byte of the emoji.
        var retryIndex = totalTokens - 128
        assertTrue(retryIndex > 8_192)
        repeat(3) {
            assertTrue(retryIndex > 8_192)
            assembler.update(
                window(
                    fullText = fullText,
                    parts = parts.drop(retryIndex),
                    times = times.drop(retryIndex),
                    firstTokenIndex = retryIndex,
                    totalTokenCount = totalTokens,
                ),
                audioProcessedMs = totalTokens + 1_000L,
                isFinal = false,
            )
            assertEquals(retryIndex - 1, assembler.nextSnapshotFirstTokenIndex)
            retryIndex--
        }

        assertEquals(18_000, retryIndex)
        val aligned = assembler.update(
            window(
                fullText = fullText,
                parts = parts.drop(retryIndex),
                times = times.drop(retryIndex),
                firstTokenIndex = retryIndex,
                totalTokenCount = totalTokens,
            ),
            audioProcessedMs = totalTokens + 1_000L,
            isFinal = true,
        )

        assertEquals(totalTokens - 128, assembler.nextSnapshotFirstTokenIndex)
        val finalChunk = aligned.last { it.transcript.endsWith("b".repeat(126)) }
        val trailingWord = finalChunk.words.single { it.text == "b".repeat(126) }
        assertEquals(18_005L, trailingWord.startMs)
        assertEquals(totalTokens.toLong(), trailingWord.endMs)
    }

    @Test
    fun an_unaligned_repeated_middle_word_does_not_reuse_the_following_words_timing() {
        val text = "echo echo echo"
        val parts = listOf("echo".toByteArray(), " ".toByteArray(), "echo".toByteArray(), " ".toByteArray(), "echo".toByteArray())
        val times = listOf(0L to 10L, 10L to 11L, 20L to 80L, 80L to 81L, 40L to 50L)
        val assembler = MeetingHandyTranscriptAssembler()

        val first = assembler.update(window(text, parts, times), audioProcessedMs = 60L, isFinal = true).single()
        val repeated = assembler.update(window(text, parts, times), audioProcessedMs = 60L, isFinal = true)

        assertEquals(listOf(0L, 40L), first.words.map { it.startMs })
        assertTrue("unchanged audio time alone must not publish", repeated.isEmpty())
    }

    @Test
    fun audio_time_only_changes_do_not_emit_revisions_and_old_chunks_are_not_resurrected() {
        val assembler = MeetingHandyTranscriptAssembler(
            maxWordsPerChunk = 1,
            maxRetainedAudioMs = 50L,
            maxRetainedChunks = 2,
        )
        val firstParts = listOf("one".toByteArray(), " ".toByteArray(), "two".toByteArray(), " ".toByteArray(), "three".toByteArray())
        val firstTimes = listOf(0L to 10L, 10L to 11L, 10L to 20L, 20L to 21L, 20L to 30L)
        val initial = assembler.update(window("one two three", firstParts, firstTimes), audioProcessedMs = 30L, isFinal = true)
        assertEquals(listOf("one", "two", "three"), initial.map { it.transcript.trim() })
        val reducer = MeetingTranscriptReducer("retention", "run")
        initial.forEach { reducer.apply(it.asHypothesis("run")) }
        assertEquals("one two three", reducer.snapshot().turns.joinToString(" ") { it.recognizedText })
        assertTrue(assembler.retainedChunkCount <= 2)

        assertTrue(assembler.update(window("one two three", firstParts, firstTimes), audioProcessedMs = 200L, isFinal = true).isEmpty())
        val extendedParts = firstParts + listOf(" ".toByteArray(), "four".toByteArray())
        val extendedTimes = firstTimes + listOf(30L to 31L, 30L to 40L)
        val current = assembler.update(window("one two three four", extendedParts, extendedTimes), audioProcessedMs = 210L, isFinal = true)

        assertEquals(listOf(4L), current.map { it.utteranceId })
        assertEquals(1, assembler.retainedChunkCount)
        assertTrue(assembler.update(window("one two three four", extendedParts, extendedTimes), audioProcessedMs = 220L, isFinal = true).isEmpty())
    }

    @Test
    fun all_new_long_transcript_chunks_are_emitted_once_before_revision_retention_is_applied() {
        val words = (0 until 300).map { "word%03d".format(it) }
        val text = words.joinToString(" ")
        val parts = words.flatMap { listOf(it.toByteArray(), " ".toByteArray()) }.dropLast(1)
        val times = parts.indices.map { index -> index * 5L to (index + 1L) * 5L }
        val assembler = MeetingHandyTranscriptAssembler(
            maxWordsPerChunk = 1,
            maxRetainedAudioMs = Long.MAX_VALUE,
            maxRetainedChunks = 256,
        )

        val updates = assembler.update(
            window(text, parts, times),
            audioProcessedMs = parts.size * 5L + 1_000L,
            isFinal = true,
        )

        assertEquals(words, updates.map { it.transcript.trim() })
        assertEquals(words.size, updates.map { it.utteranceId }.toSet().size)
        assertEquals(256, assembler.retainedChunkCount)
        val reducer = MeetingTranscriptReducer("long-retention", "run")
        updates.forEach { reducer.apply(it.asHypothesis("run")) }
        assertEquals(text, reducer.snapshot().turns.joinToString(" ") { it.recognizedText })
        assertTrue(
            assembler.update(
                window(text, parts, times),
                audioProcessedMs = parts.size * 5L + 1_100L,
                isFinal = true,
            ).isEmpty(),
        )
    }

    @Test
    fun audio_processed_time_never_moves_backward_between_asr_and_diarization_updates() {
        val assembler = MeetingHandyTranscriptAssembler()
        assembler.update(
            window("Bonjour", listOf("Bonjour".toByteArray()), listOf(0L to 20L)),
            audioProcessedMs = 1_000L,
            isFinal = true,
        )

        val voiceRevision = assembler.reviseDiarization(
            diarization(firstFrame = 0L, frameCount = 2, stableFrameCount = 2L, totalFrameCount = 2L),
            audioProcessedMs = 500L,
        ).single()
        assertEquals(1_000L, voiceRevision.audioProcessedMs)

        val laterAsrRevision = assembler.update(
            window(
                "Bonjour!",
                listOf("Bonjour".toByteArray(), "!".toByteArray()),
                listOf(0L to 20L, 20L to 21L),
            ),
            audioProcessedMs = 600L,
            isFinal = true,
        ).single()
        assertEquals(1_000L, laterAsrRevision.audioProcessedMs)
    }

    @Test
    fun identical_snapshots_skip_revisions_but_audio_timing_final_and_retry_changes_are_processed() {
        val assembler = MeetingHandyTranscriptAssembler()
        val text = "Bonjour "
        val parts = listOf("Bonjour".toByteArray(), " ".toByteArray())
        val early = window(text, parts, listOf(0L to 800L, 790L to 800L))

        val beforeAudioArrives = assembler.update(early, audioProcessedMs = 780L, isFinal = false)
        assertEquals(1, beforeAudioArrives.size)
        assertTrue(beforeAudioArrives.single().words.isEmpty())

        assertTrue(assembler.update(early, audioProcessedMs = 790L, isFinal = false).isEmpty())
        val audioCaughtUp = assembler.update(early, audioProcessedMs = 800L, isFinal = false).single()
        assertEquals(800L, audioCaughtUp.words.single().endMs)

        val changedTiming = window(text, parts, listOf(0L to 790L, 790L to 800L))
        val timingRevision = assembler.update(changedTiming, audioProcessedMs = 800L, isFinal = false).single()
        assertEquals(790L, timingRevision.words.single().endMs)

        val finalRevision = assembler.update(changedTiming, audioProcessedMs = 800L, isFinal = true).single()
        assertTrue(finalRevision.isFinal)
        assertTrue(assembler.update(changedTiming, audioProcessedMs = 900L, isFinal = true).isEmpty())
    }

    @Test
    fun an_earlier_chunk_is_not_sealed_until_its_boundary_word_is_committed() {
        val assembler = MeetingHandyTranscriptAssembler(maxWordsPerChunk = 1)
        val parts = listOf("known".toByteArray(), " ".toByteArray(), "pending".toByteArray())
        val times = listOf(0L to 10L, 10L to 11L, 11L to 30L)

        val provisional = assembler.update(
            window("known pending", parts, times, committedTokenCount = 0),
            audioProcessedMs = 100L,
            isFinal = false,
        )
        assertEquals(listOf(false, false), provisional.map { it.isFinal })

        val committed = assembler.update(
            window("known pending", parts, times, committedTokenCount = parts.size),
            audioProcessedMs = 100L,
            isFinal = false,
        )
        assertTrue(committed.first { it.utteranceId == 1L }.isFinal)
    }

    @Test
    fun a_compacted_diarization_window_does_not_erase_a_stable_known_speaker() {
        val assembler = MeetingHandyTranscriptAssembler()
        assembler.update(
            window("Bonjour", listOf("Bonjour".toByteArray()), listOf(20L to 50L)),
            audioProcessedMs = 100L,
            isFinal = true,
        )

        val attributed = assembler.reviseDiarization(
            diarization(firstFrame = 0L, frameCount = 10, stableFrameCount = 10L, totalFrameCount = 10L),
            audioProcessedMs = 100L,
        ).single()
        assertEquals(1, attributed.words.single().channel)

        val afterCompaction = assembler.reviseDiarization(
            diarization(firstFrame = 10L, frameCount = 10, stableFrameCount = 20L, totalFrameCount = 20L),
            audioProcessedMs = 100L,
        )
        assertTrue(afterCompaction.isEmpty())
    }

    @Test
    fun native_speaker_seven_maps_to_the_eighth_domain_participant() {
        val assembler = MeetingHandyTranscriptAssembler()
        assembler.update(
            window("Bonjour", listOf("Bonjour".toByteArray()), listOf(0L to 20L)),
            audioProcessedMs = 100L,
            isFinal = true,
        )

        val revision = assembler.reviseDiarization(
            diarization(
                firstFrame = 0L,
                frameCount = 2,
                stableFrameCount = 2L,
                totalFrameCount = 2L,
                speakerCount = 8,
                activeSpeakers = setOf(7),
            ),
            audioProcessedMs = 100L,
        ).single()

        assertEquals(8, revision.words.single().channel)
        val reducer = MeetingTranscriptReducer("eight", "run")
        reducer.apply(revision.asHypothesis("run"))
        assertEquals("eight:participant:8", reducer.snapshot().turns.single().automaticParticipantId)
    }

    @Test
    fun float32_ten_millisecond_frames_do_not_add_a_frame_at_exact_boundaries() {
        val assembler = MeetingHandyTranscriptAssembler()
        assembler.update(
            window("Bonjour", listOf("Bonjour".toByteArray()), listOf(0L to 20L)),
            audioProcessedMs = 100L,
            isFinal = true,
        )

        val revisions = assembler.reviseDiarization(
            diarization(
                firstFrame = 0L,
                frameCount = 10,
                stableFrameCount = 10L,
                totalFrameCount = 10L,
                speakerCount = 2,
                activeSpeakerByFrame = (2..9).associateWith { 1 },
                secondsPerFrame = 0.009999999776482582,
            ),
            audioProcessedMs = 100L,
        )

        // [0, 20) covers frames 0 and 1. Frame 2 belongs to the next speaker.
        assertEquals(1, revisions.size)
        val revision = revisions.single()
        assertEquals(1, revision.words.single().channel)
        assertEquals(100L, revision.stableSpeakerThroughMs)
    }

    @Test
    fun overlapping_native_speakers_leave_the_word_unattributed() {
        val assembler = MeetingHandyTranscriptAssembler()
        val initial = assembler.update(
            window("Bonjour", listOf("Bonjour".toByteArray()), listOf(0L to 20L)),
            audioProcessedMs = 100L,
            isFinal = true,
        ).single()
        assertEquals(0, initial.words.single().channel)

        val revisions = assembler.reviseDiarization(
            diarization(
                firstFrame = 0L,
                frameCount = 2,
                stableFrameCount = 2L,
                totalFrameCount = 2L,
                speakerCount = 8,
                activeSpeakers = setOf(0, 7),
            ),
            audioProcessedMs = 100L,
        )

        assertTrue(revisions.isEmpty())
    }

    private fun window(
        fullText: String,
        parts: List<ByteArray>,
        times: List<Pair<Long, Long>>,
        firstTokenIndex: Int = 0,
        totalTokenCount: Int = firstTokenIndex + parts.size,
        committedTokenCount: Int = totalTokenCount,
        fullTextUtf8: ByteArray = fullText.toByteArray(Charsets.UTF_8),
    ): HandyTokenWindow {
        require(parts.size == times.size)
        val combined = ByteArray(parts.sumOf { it.size })
        val ends = IntArray(parts.size)
        var offset = 0
        parts.forEachIndexed { index, part ->
            part.copyInto(combined, offset)
            offset += part.size
            ends[index] = offset
        }
        return HandyTokenWindow(
            fullTextUtf8 = fullTextUtf8,
            firstTokenIndex = firstTokenIndex,
            totalTokenCount = totalTokenCount,
            committedTokenCount = committedTokenCount,
            tokenBytes = combined,
            tokenByteEnds = ends,
            tokenStartsMs = times.map { it.first }.toLongArray(),
            tokenEndsMs = times.map { it.second }.toLongArray(),
        )
    }

    private fun diarization(
        firstFrame: Long,
        frameCount: Int,
        stableFrameCount: Long,
        totalFrameCount: Long,
        speakerCount: Int = 1,
        activeSpeakers: Set<Int> = setOf(0),
        activeSpeakerByFrame: Map<Int, Int>? = null,
        secondsPerFrame: Double = 0.01,
    ) = DiarizationFrameWindow(
        firstFrameIndex = firstFrame,
        secondsPerFrame = secondsPerFrame,
        probabilities = FloatArray(frameCount * speakerCount) { index ->
            val frame = index / speakerCount
            val speaker = activeSpeakerByFrame?.get(frame)
            if (if (speaker != null) index % speakerCount == speaker else index % speakerCount in activeSpeakers) {
                1.0f
            } else {
                0.0f
            }
        },
        speakerCount = speakerCount,
        stableFrameCount = stableFrameCount,
        totalFrameCount = totalFrameCount,
    )

    private fun MeetingNativeUpdate.asHypothesis(runId: String) = MeetingHypothesis(
        runId = runId,
        utteranceId = utteranceId,
        revision = revision,
        words = words,
        transcript = transcript,
        isFinal = isFinal,
        stableSpeakerThroughMs = stableSpeakerThroughMs,
        audioProcessedMs = audioProcessedMs,
    )
}
