package com.kafkasl.phonewhisper

import java.io.InputStream
import java.io.OutputStream
import java.io.FilterOutputStream
import java.util.Base64
import java.time.Instant

/** A standalone HTML document: no network, JavaScript, tracking, or filesystem references. */
internal object NoteHtmlExport {
    fun write(note: TranscriptNote, output: OutputStream, openImage: (NoteImage) -> InputStream) {
        fun text(value: String) { output.write(value.toByteArray(Charsets.UTF_8)) }
        text("""<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<title>${escape(note.title)}</title>
<style>body{max-width:48rem;margin:2rem auto;padding:0 1rem;color:#202329;background:#fff;font:18px/1.6 system-ui,sans-serif}h1{font-size:1.7rem;line-height:1.2}.text{white-space:pre-wrap;overflow-wrap:anywhere}figure{margin:1.5rem 0;break-inside:avoid}img{display:block;max-width:100%;height:auto;max-height:85vh;object-fit:contain;border:1px solid #ddd}figcaption,.meta{color:#58606a;font-size:.85rem}a{color:#226349}@media print{body{margin:0;font-size:11pt}img{max-height:22cm}}</style>
</head><body><main><h1>${escape(note.title)}</h1>
<p class="meta">DictAI · ${escape(Instant.ofEpochMilli(note.updatedAt).toString())}</p>
""")
        NoteImageMarkers.parts(note).forEach { part ->
            when (part) {
                is NoteImageMarkers.Part.Text -> text("<div class=\"text\">${escape(part.text)}</div>\n")
                is NoteImageMarkers.Part.Image -> {
                    val image = part.image
                    val caption = "Image ${image.number} · ${image.kind.label} · ${Instant.ofEpochMilli(image.capturedAt)}" +
                        if (part.missingMarker) " · repère retiré du texte" else ""
                    text("<figure id=\"image-${image.number}\"><img alt=\"${escape(caption)}\" width=\"${image.width}\" height=\"${image.height}\" src=\"data:image/jpeg;base64,")
                    // Encode in a stream: ten photos never become ten in-memory base64 strings.
                    val nonClosing = object : FilterOutputStream(output) {
                        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
                        override fun close() { flush() }
                    }
                    Base64.getEncoder().wrap(nonClosing).use { encoded -> openImage(image).use { it.copyTo(encoded) } }
                    text("\"><figcaption>${escape(caption)}</figcaption></figure>\n")
                }
            }
        }
        text("</main></body></html>\n")
    }
    internal fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
