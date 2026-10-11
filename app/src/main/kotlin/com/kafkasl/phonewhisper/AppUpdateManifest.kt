package com.kafkasl.phonewhisper

import org.json.JSONObject

data class AppUpdate(val version: String, val downloadUrl: String)

object AppUpdateManifest {
    const val FEED_URL = "https://github.com/Uhama91/DictAI/releases/latest/download/updates.json"
    const val MAX_BYTES = 65_536
    private val download = Regex("https://github\\.com/Uhama91/DictAI/releases/download/[A-Za-z0-9][A-Za-z0-9._-]{0,127}/[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.apk")

    fun android(raw: String, currentCode: Int, sdk: Int): AppUpdate? = try {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val feed = JSONObject(raw)
        require(integer(feed, "schemaVersion") == 1)
        require(!feed.optBoolean("testOnly", false))
        if (!feed.has("android")) null else {
            val item = feed.getJSONObject("android")
            val code = integer(item, "versionCode")
            val minimum = integer(item, "minSdk")
            val version = item.get("versionName") as? String ?: throw IllegalArgumentException("Version invalide")
            val url = item.getString("downloadUrl")
            require(code > 0 && minimum in 1..100)
            require(version.isNotBlank() && version.length <= 40 && version.none { it.isISOControl() })
            require(download.matches(url))
            if (item.getString("applicationId") == "com.uhama.whisperpin" && code > currentCode && minimum <= sdk) AppUpdate(version, url) else null
        }
    } catch (error: Exception) {
        throw IllegalArgumentException("Manifeste de mise à jour invalide", error)
    }

    private fun integer(objectValue: JSONObject, key: String): Int {
        val value = objectValue.get(key)
        require(value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in 0..Int.MAX_VALUE.toLong())
        return number.toInt()
    }
}

object AppUpdateSchedule {
    fun due(now: Long, lastAttempt: Long, succeeded: Boolean): Boolean =
        lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= if (succeeded) 86_400_000L else 3_600_000L
}
