package com.kafkasl.phonewhisper

import kotlin.math.roundToInt

internal data class NoteExportImageSize(val widthPt: Float, val heightPt: Float)

/** Shared print bounds for PDF and HTML image blocks. Dimensions are expressed in PDF points. */
internal object NoteExportImageSizing {
    const val CAMERA_MAX_WIDTH_PT = 340f
    const val CAMERA_MAX_HEIGHT_PT = 230f
    const val DOCUMENT_MAX_WIDTH_PT = 430f
    const val DOCUMENT_MAX_HEIGHT_PT = 420f

    fun fit(kind: NoteImageKind, sourceWidth: Int, sourceHeight: Int): NoteExportImageSize {
        require(sourceWidth > 0 && sourceHeight > 0) { "Dimensions d’image invalides" }
        val maxWidth = maxWidthPt(kind)
        val maxHeight = maxHeightPt(kind)
        val scale = minOf(1f, maxWidth / sourceWidth, maxHeight / sourceHeight)
        return NoteExportImageSize(sourceWidth * scale, sourceHeight * scale)
    }

    fun maxWidthPt(kind: NoteImageKind): Float =
        if (kind == NoteImageKind.CAMERA) CAMERA_MAX_WIDTH_PT else DOCUMENT_MAX_WIDTH_PT

    fun maxHeightPt(kind: NoteImageKind): Float =
        if (kind == NoteImageKind.CAMERA) CAMERA_MAX_HEIGHT_PT else DOCUMENT_MAX_HEIGHT_PT

    fun maxWidthCssPx(kind: NoteImageKind): Int = (maxWidthPt(kind) * CSS_PIXELS_PER_POINT).roundToInt()

    fun maxHeightCssPx(kind: NoteImageKind): Int = (maxHeightPt(kind) * CSS_PIXELS_PER_POINT).roundToInt()

    fun htmlClass(kind: NoteImageKind): String =
        if (kind == NoteImageKind.CAMERA) "camera" else "document"

    private const val CSS_PIXELS_PER_POINT = 4f / 3f
}
