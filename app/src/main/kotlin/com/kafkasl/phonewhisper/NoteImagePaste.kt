package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File

internal enum class ImagePasteResult { REQUESTED, COPIED, NO_TARGET, TARGET_CHANGED, SELECTION_ACTIVE, FAILED }

/** No claim of attachment success: ACTION_PASTE acknowledges the action, not the destination's UI. */
internal object NoteImagePaste {
    private fun prefs(context: Context) = context.getSharedPreferences("image_paste", Context.MODE_PRIVATE)
    fun prepare(context: Context, image: NoteImage): Uri {
        val root = File(context.cacheDir, "note_exports").apply { mkdirs() }
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7L*24*3600*1000 }?.forEach { it.deleteRecursively() }
        val directory = File(root, "paste-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val destination = File(directory, "Image-${image.number}.jpg")
        try { NoteImageCopies.write(context, image, destination) }
        catch (error: Exception) { directory.deleteRecursively(); throw error }
        return FileProvider.getUriForFile(context, "${context.packageName}.note_files", destination)
    }
    fun begin(context: Context, uri: Uri, number: Int, target: String?) {
        val uid = target?.let { runCatching { context.packageManager.getPackageUid(it, 0) }.getOrNull() } ?: -1
        prefs(context).edit().clear().putString("uri", uri.toString()).putInt("number", number).putInt("targetUid", uid)
            .putString("target", target ?: "aucun champ retrouvé").putLong("time", System.currentTimeMillis())
            .putString("outcome", ImagePasteResult.NO_TARGET.name).apply()
    }
    fun record(context: Context, uri: Uri, result: ImagePasteResult) {
        if (prefs(context).getString("uri", null) == uri.toString()) prefs(context).edit().putString("outcome", result.name).apply()
    }
    fun copy(context: Context, uri: Uri): Boolean = runCatching {
        require(uri.scheme == "content" && uri.authority == "${context.packageName}.note_files")
        // Empty text fallback prevents a plain editor from receiving a private content:// path.
        // Images remain URI items, as required by Android's receive-content API.
        val clip = imageClip(uri)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    }.getOrDefault(false)
    fun imageClip(uri: Uri) = ClipData("Image DictAI", arrayOf("image/jpeg"), ClipData.Item("", null, null, uri))
    fun read(context: Context, uri: Uri) = prefs(context).getString("uri", null) == uri.toString() && prefs(context).getBoolean("read", false)
    fun observedRead(context: Context, uri: Uri, caller: Int) {
        if (caller != Process.myUid() && caller == prefs(context).getInt("targetUid", -1) && prefs(context).getString("uri", null) == uri.toString())
            prefs(context).edit().putBoolean("read", true).apply()
    }
    fun report(context: Context): String {
        val p = prefs(context)
        if (!p.contains("time")) return "Aucun collage d’image tenté."
        val result = runCatching { ImagePasteResult.valueOf(p.getString("outcome", "").orEmpty()) }.getOrDefault(ImagePasteResult.FAILED)
        val label = when (result) {
            ImagePasteResult.REQUESTED -> "action de collage acceptée par le champ"
            ImagePasteResult.COPIED -> "image copiée ; collage non accepté par le champ"
            ImagePasteResult.NO_TARGET -> "image conservée ; aucun champ de conversation retrouvé"
            ImagePasteResult.TARGET_CHANGED -> "image conservée ; le champ ou la fenêtre a changé"
            ImagePasteResult.SELECTION_ACTIVE -> "image conservée ; sélection de texte active ou position inconnue"
            ImagePasteResult.FAILED -> "échec technique du collage ; image conservée"
        }
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT).format(java.util.Date(p.getLong("time", 0)))
        return "DictAI — dernier collage d’image\nApplication : ${BuildConfig.VERSION_NAME}\nDate : $time\nImage : ${p.getInt("number", 0)}\nDestination : ${p.getString("target", "")}\nRésultat : $label\nFichier lu par le destinataire : ${if (p.getBoolean("read", false)) "oui" else if (p.getInt("targetUid", -1) < 0) "identification indisponible" else "non observé"}\nUne action acceptée ou une lecture du fichier ne confirme pas que la pièce jointe apparaît dans la conversation.\nCe diagnostic ne contient ni image, ni note, ni chemin de fichier."
    }
}

/** Observation is limited to our own exported file, never other clipboard data or app content. */
class NoteFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val result = super.openFile(uri, mode)
        if (result != null && mode == "r") context?.let { NoteImagePaste.observedRead(it, uri, Binder.getCallingUid()) }
        return result
    }
}

internal object ImagePasteSafety {
    fun allow(currentWindow: Boolean, editable: Boolean, sensitive: Boolean, start: Int, end: Int): ImagePasteResult? = when {
        !currentWindow || !editable || sensitive -> ImagePasteResult.TARGET_CHANGED
        start < 0 || end < 0 || start != end -> ImagePasteResult.SELECTION_ACTIVE
        else -> null
    }
}
