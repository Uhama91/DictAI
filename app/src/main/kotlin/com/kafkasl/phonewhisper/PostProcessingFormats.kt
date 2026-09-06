package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class PostProcessingFormat(val id: String, val name: String, val instructions: String)

internal class PostProcessingFormats(context: Context) {
    private val prefs = context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE)
    fun custom(): List<PostProcessingFormat> = runCatching {
        val array = JSONArray(prefs.getString("custom", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PostProcessingFormat(item.getString("id"), item.getString("name"), item.getString("instructions"))
        }
    }.getOrDefault(emptyList())
    fun all() = builtins + custom()
    fun selected() = all().firstOrNull { it.id == prefs.getString("selected", "cleanup") } ?: builtins.first()
    fun select(format: PostProcessingFormat) { prefs.edit().putString("selected", format.id).apply() }
    fun save(id: String?, name: String, instructions: String) {
        require(name.isNotBlank() && name.length <= 60 && instructions.isNotBlank() && instructions.length <= 4000)
        val format = PostProcessingFormat(id ?: UUID.randomUUID().toString(), name.trim(), instructions.trim())
        write(custom().filterNot { it.id == format.id } + format)
    }
    fun delete(id: String) {
        write(custom().filterNot { it.id == id })
        if (prefs.getString("selected", null) == id) select(builtins.first())
    }
    private fun write(formats: List<PostProcessingFormat>) {
        val array = JSONArray()
        formats.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("instructions", it.instructions)) }
        prefs.edit().putString("custom", array.toString()).apply()
    }
    companion object {
        val builtins = listOf(
            PostProcessingFormat("cleanup", "Texte corrigé", ""),
            PostProcessingFormat("list", "Liste à puces", "Organize the dictated items as a plain-text bulleted list using •. Keep all information and do not invent items."),
            PostProcessingFormat("email", "Mail", "Format as an email with paragraphs, a greeting and a closing only when supported by the dictation. Do not invent a recipient, sender, subject facts or signature."),
        )
    }
}
