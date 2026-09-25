package com.kafkasl.phonewhisper.meeting

import java.net.URI

/** Immutable metadata for one file inside a versioned meeting-model package. */
data class MeetingModelArtifact(
    val id: String,
    val relativePath: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid model id" }
        require(isSafeRelativePath(relativePath)) { "Invalid relative model path" }
        require(sizeBytes > 0L) { "Model size must be positive" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid model SHA-256" }
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
            "Model URL must be an absolute HTTP(S) URL"
        }
        require(uri.rawUserInfo == null) { "Credentials are not allowed in model URLs" }
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || '\\' in path || ':' in path) return false
        return path.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." &&
                segment.matches(Regex("[A-Za-z0-9._-]+"))
        }
    }
}

/** Pinned, versioned pair of ASR and diarization model artifacts. */
data class MeetingModelCatalog(
    val packageName: String,
    val version: String,
    val asr: MeetingModelArtifact,
    val diarization: MeetingModelArtifact,
) {
    val artifacts: List<MeetingModelArtifact> = listOf(asr, diarization)
    val totalBytes: Long = Math.addExact(asr.sizeBytes, diarization.sizeBytes)
    val packageDirectoryPrefix: String = "$packageName-$version-"

    init {
        require(packageName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid package name" }
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid package version" }
        require(asr.id != diarization.id) { "Model ids must be distinct" }
        require(asr.relativePath != diarization.relativePath) { "Model paths must be distinct" }
    }

    fun packageDirectoryName(generationId: String): String {
        require(generationId.matches(Regex("[A-Fa-f0-9-]{1,80}"))) { "Invalid generation id" }
        return packageDirectoryPrefix + generationId
    }

    fun isPackageDirectoryName(name: String): Boolean =
        name.startsWith(packageDirectoryPrefix) &&
            name.removePrefix(packageDirectoryPrefix).matches(Regex("[A-Fa-f0-9-]{1,80}"))

    companion object {
        val production = MeetingModelCatalog(
            packageName = "dictai-meeting-models",
            version = "v1-asr-1c8deae-diar-f667ed73",
            asr = MeetingModelArtifact(
                id = "asr",
                relativePath = "nemotron-3.5-asr-streaming-0.6b.q8_0.gguf",
                url = "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/" +
                    "1c8deaecc64b91f034d73e08dd8b64625eb3395d/" +
                    "nemotron-3.5-asr-streaming-0.6b.q8_0.gguf",
                sizeBytes = 741_548_352L,
                sha256 = "a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae",
            ),
            diarization = MeetingModelArtifact(
                id = "diarization",
                relativePath = "Nemotron-3-Diarization.q8_0.gguf",
                url = "https://huggingface.co/nvidia/Nemotron-3-Diarization/resolve/" +
                    "f667ed73aee57d40cc39428eb768b4fd87a0a29e/" +
                    "Nemotron-3-Diarization.q8_0.gguf",
                sizeBytes = 107_012_128L,
                sha256 = "08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1",
            ),
        )
    }
}
