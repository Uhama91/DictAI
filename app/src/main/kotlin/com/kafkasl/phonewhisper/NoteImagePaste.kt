package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** One explicit capture, one image clipboard item. The user controls pasting in their keyboard. */
internal object NoteImagePaste {
    private fun prefs(context: Context) = context.getSharedPreferences("image_clipboard", Context.MODE_PRIVATE)

    /** Keep exported URIs stable briefly for keyboard history; never overwrite a previous image file. */
    fun prepare(context: Context, image: NoteImage): Uri {
        val root = File(context.cacheDir, "note_exports").apply { mkdirs() }
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7L * 24 * 3600 * 1000 }
            ?.forEach { it.deleteRecursively() }
        val directory = File(root, "paste-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val destination = File(directory, "Image.jpg")
        try { NoteImageStore(context).file(image.id).copyTo(destination) }
        catch (error: Exception) { directory.deleteRecursively(); throw error }
        return FileProvider.getUriForFile(context, "${context.packageName}.note_files", destination)
    }

    fun copy(context: Context, uri: Uri): Boolean = runCatching {
        require(uri.scheme == "content" && (uri.authority == "${context.packageName}.note_files" ||
            (uri.authority == MediaStore.AUTHORITY && context.contentResolver.getType(uri) == "image/jpeg")))
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(imageClip(uri))
        true
    }.getOrDefault(false)

    // URI-only image item, equivalent to ClipData.newUri: a synthetic empty text item can make
    // keyboards classify the clipboard as text instead of presenting the JPEG thumbnail.
    fun imageClip(uri: Uri) = ClipData("Image DictAI", arrayOf("image/jpeg"), ClipData.Item(uri))

    fun recordCopy(context: Context, kind: NoteImageKind, copied: Boolean, savedInGallery: Boolean = false) {
        prefs(context).edit().clear().putString("kind", kind.label).putBoolean("copied", copied).putBoolean("gallery", savedInGallery)
            .putLong("time", System.currentTimeMillis()).apply()
    }

    fun report(context: Context): String {
        val p = prefs(context)
        if (!p.contains("time")) return "Aucune copie d’image effectuée dans cette version."
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT)
            .format(java.util.Date(p.getLong("time", 0)))
        return "DictAI — dernière copie d’image\nApplication : ${BuildConfig.VERSION_NAME}\nDate : $time\nType : ${p.getString("kind", "")}\nRésultat : ${if (p.getBoolean("copied", false)) "image remise au presse-papier Android" else "copie impossible"}\nStockage : ${if (p.getBoolean("gallery", false)) "Photos / DictAI" else "enregistrement galerie non confirmé"}\nCollage : manuel depuis Gboard ou votre clavier\nChaque capture remplace le contenu courant du presse-papier. L’historique et la réception de l’image dépendent du clavier et de l’application.\nCe diagnostic ne contient ni image, ni note, ni chemin de fichier."
    }
}

class NoteFileProvider : FileProvider()
