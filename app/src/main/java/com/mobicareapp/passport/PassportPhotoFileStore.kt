package com.mobicareapp.passport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.Segmentation
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a passport-size photo (35x45mm, the size used by India, the UK, the EU/Schengen area,
 * and most of the world) and a print-ready sheet with 4 copies of it in a single horizontal row.
 *
 * [PdfDocument.PageInfo] width/height are in points (1/72 inch), not pixels, so the sheet's page
 * and every photo on it are sized in points computed from millimeters — that's what makes each
 * photo come out at its true physical size when printed at "Actual size"/100% rather than
 * whatever "Fit to page" would stretch it to. The page itself is fixed at a real, buyable paper
 * size (6x4in photo paper) rather than sized to just fit the content, so "Fit to page" and
 * "Actual size" printing produce the same result either way — there's no print-dialog setting
 * left that can silently distort it.
 */
/** Look presets for the finished photo — applied as a color-matrix pass over the whole image (person and filled-in background alike), last, right before it's saved. */
enum class PhotoFilter(val label: String) {
    NONE("Original"),
    ENHANCE("Enhance"),
    GLOW("Glow"),
    STUDIO("Studio"),
    VIVID("Vivid"),
    BRIGHT("Bright"),
    WARM("Warm"),
    COOL("Cool"),
    SOFT("Soft"),
    FADE("Fade"),
    DRAMATIC("Dramatic"),
    SEPIA("Sepia"),
    GRAYSCALE("Grayscale"),
    BLACK_AND_WHITE("B&W")
}

object PassportPhotoFileStore {

    private const val PHOTO_WIDTH_MM = 35f
    private const val PHOTO_HEIGHT_MM = 45f
    private const val MM_PER_INCH = 25.4f
    private const val POINTS_PER_INCH = 72f
    private const val PRINT_DPI = 300f

    val PHOTO_ASPECT_RATIO = PHOTO_WIDTH_MM / PHOTO_HEIGHT_MM

    /** Colors passport photo rules actually allow — a plain, light, even background — rather than an unrestricted picker. */
    val BACKGROUND_PRESETS: List<Pair<String, Int>> = listOf(
        "White" to Color.rgb(255, 255, 255),
        "Light gray" to Color.rgb(214, 214, 214),
        "Sky blue" to Color.rgb(179, 214, 242),
        "Off-white" to Color.rgb(245, 240, 230)
    )

    private fun mmToPoints(mm: Float): Float = mm / MM_PER_INCH * POINTS_PER_INCH
    private fun mmToPixels(mm: Float): Int = (mm / MM_PER_INCH * PRINT_DPI).toInt()

    /** Pixel resolution a 35x45mm photo needs to print at a sharp 300dpi. */
    val PHOTO_WIDTH_PX = mmToPixels(PHOTO_WIDTH_MM)
    val PHOTO_HEIGHT_PX = mmToPixels(PHOTO_HEIGHT_MM)

    /**
     * Builds the finished photo for [sourceUri]: the person (with [filter]/[brightness]/[contrast]
     * and any hand-drawn [strokes] applied to *them only*) composited over a flat [backgroundColor].
     * The filter used to be applied last, over the whole image — which tinted/brightened the
     * background fill too, so a "white" background no longer came out as the chosen color. Now the
     * background is composited in after everything else, so it's always exactly the chosen color.
     *
     * The slow part (person segmentation + face-based crop) is done once per source photo and
     * cached in memory (see [prepare]); re-rendering after a background/filter/stroke change only
     * replays cheap per-pixel work on the small 35x45mm-sized result, which is what keeps drawing
     * with the eraser/brush feeling immediate.
     *
     * [baseName] names the output file directly (`<baseName>.jpg`, overwriting whatever was there)
     * rather than always generating a fresh unique name — passing the same [baseName] back in
     * (paired with the same raw capture, tracked by [PassportPhotoHistoryStore]) is what lets
     * "Edit" change an already-saved photo in place instead of piling up duplicate files.
     */
    suspend fun cropAndSave(
        context: Context,
        sourceUri: Uri,
        backgroundColor: Int,
        filter: PhotoFilter,
        baseName: String,
        brightness: Float = 0f,
        contrast: Float = 1f,
        strokes: List<PhotoStroke> = emptyList()
    ): File {
        val prepared = prepare(context, sourceUri)
        val rendered = render(prepared, backgroundColor, filter, brightness, contrast, strokes)
        val file = File(photosDir(context), "$baseName.jpg")
        try {
            FileOutputStream(file).use { rendered.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            rendered.recycle()
        }
        return file
    }

    /** The source photo cropped to passport framing at print resolution, plus the matching per-pixel "this is the person" confidence (0..1) — everything that doesn't change when only the background, filter or hand edits do. */
    private class PreparedPhoto(val key: String, val base: Bitmap, val mask: FloatArray)

    @Volatile
    private var cachedPhoto: PreparedPhoto? = null

    private suspend fun prepare(context: Context, sourceUri: Uri): PreparedPhoto {
        // Retake/gallery overwrite the same path in place, so the path alone isn't a safe cache
        // key — last-modified time and size together tell a new capture from the old one.
        val sourceFile = sourceUri.path?.let { File(it) }
        val key = "${sourceUri}|${sourceFile?.lastModified()}|${sourceFile?.length()}"
        cachedPhoto?.takeIf { it.key == key }?.let { return it }

        val original = loadUprightBitmap(context, sourceUri)
        try {
            val personMask = segmentPerson(original)
            val crop = cropRectForFace(original, PHOTO_ASPECT_RATIO)

            val cropped = Bitmap.createBitmap(original, crop.left, crop.top, crop.width(), crop.height())
            val base = Bitmap.createScaledBitmap(cropped, PHOTO_WIDTH_PX, PHOTO_HEIGHT_PX, true)
            if (base !== cropped) cropped.recycle()

            // The mask goes through the exact same crop + scale as the photo, by way of a bitmap
            // whose alpha channel carries the confidence — keeps the two perfectly aligned.
            val maskBitmap = Bitmap.createBitmap(personMask.width, personMask.height, Bitmap.Config.ARGB_8888)
            maskBitmap.setPixels(
                IntArray(personMask.values.size) { i -> ((personMask.values[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()) shl 24 },
                0, personMask.width, 0, 0, personMask.width, personMask.height
            )
            val sx = personMask.width.toFloat() / original.width
            val sy = personMask.height.toFloat() / original.height
            val maskLeft = (crop.left * sx).toInt().coerceIn(0, personMask.width - 1)
            val maskTop = (crop.top * sy).toInt().coerceIn(0, personMask.height - 1)
            val maskCropped = Bitmap.createBitmap(
                maskBitmap, maskLeft, maskTop,
                (crop.width() * sx).toInt().coerceIn(1, personMask.width - maskLeft),
                (crop.height() * sy).toInt().coerceIn(1, personMask.height - maskTop)
            )
            val maskScaled = Bitmap.createScaledBitmap(maskCropped, PHOTO_WIDTH_PX, PHOTO_HEIGHT_PX, true)
            val maskPixels = IntArray(PHOTO_WIDTH_PX * PHOTO_HEIGHT_PX)
            maskScaled.getPixels(maskPixels, 0, PHOTO_WIDTH_PX, 0, 0, PHOTO_WIDTH_PX, PHOTO_HEIGHT_PX)
            val mask = FloatArray(maskPixels.size) { i -> (maskPixels[i] ushr 24) / 255f }
            if (maskScaled !== maskCropped) maskScaled.recycle()
            if (maskCropped !== maskBitmap) maskCropped.recycle()
            maskBitmap.recycle()

            return PreparedPhoto(key, base, mask).also { cachedPhoto = it }
        } finally {
            original.recycle()
        }
    }

    private class PersonMask(val width: Int, val height: Int, val values: FloatArray)

    /** On-device person segmentation: confidence (0..1) per pixel that it belongs to the person, rather than a hard in/out cutoff, so hair and edges can be blended smoothly. */
    private suspend fun segmentPerson(original: Bitmap): PersonMask {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
        val mask = try {
            segmenter.process(InputImage.fromBitmap(original, 0)).await()
        } finally {
            segmenter.close()
        }
        val buffer = mask.buffer
        buffer.rewind()
        val values = FloatArray(mask.width * mask.height)
        for (i in values.indices) values[i] = buffer.float
        return PersonMask(mask.width, mask.height, values)
    }

    private fun render(
        prepared: PreparedPhoto,
        backgroundColor: Int,
        filter: PhotoFilter,
        brightness: Float,
        contrast: Float,
        strokes: List<PhotoStroke>
    ): Bitmap {
        val width = prepared.base.width
        val height = prepared.base.height
        val mask = prepared.mask.copyOf()
        applyMaskStrokes(mask, width, height, strokes)

        // Filter/brightness/contrast go onto the person only — the background fill is composited
        // in afterwards, untouched.
        val personCopy = prepared.base.copy(Bitmap.Config.ARGB_8888, true)
        val person = applyFilter(personCopy, filter, brightness, contrast)
        if (person !== personCopy) personCopy.recycle()

        val pixels = IntArray(width * height)
        person.getPixels(pixels, 0, width, 0, 0, width, height)
        person.recycle()
        applyBrightenStrokes(pixels, width, height, strokes)

        for (i in pixels.indices) {
            val confidence = mask[i]
            if (confidence < 1f) pixels[i] = blendPixel(pixels[i], backgroundColor, confidence)
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /** Replays erase/restore strokes in order onto [mask] — order matters (erase then restore over the same spot ends up restored), so only consecutive strokes of the same tool are rasterized together. */
    private fun applyMaskStrokes(mask: FloatArray, width: Int, height: Int, strokes: List<PhotoStroke>) {
        var index = 0
        while (index < strokes.size) {
            val tool = strokes[index].tool
            var end = index
            while (end < strokes.size && strokes[end].tool == tool) end++
            if (tool != BrushTool.BRIGHTEN) {
                val coverage = rasterizeStrokes(strokes.subList(index, end), width, height, softEdge = false)
                for (i in mask.indices) {
                    val a = coverage[i]
                    if (a == 0f) continue
                    mask[i] = if (tool == BrushTool.ERASE) mask[i] * (1f - a) else mask[i] + (1f - mask[i]) * a
                }
            }
            index = end
        }
    }

    /** Lightens the touched part of the person toward white (rather than adding a flat amount), which keeps skin texture/detail instead of flattening it into a bright patch. */
    private fun applyBrightenStrokes(pixels: IntArray, width: Int, height: Int, strokes: List<PhotoStroke>) {
        val brighten = strokes.filter { it.tool == BrushTool.BRIGHTEN }
        if (brighten.isEmpty()) return
        val coverage = rasterizeStrokes(brighten, width, height, softEdge = true)
        for (i in pixels.indices) {
            val k = coverage[i] * MAX_BRIGHTEN
            if (k <= 0f) continue
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            pixels[i] = Color.rgb(
                (r + (255 - r) * k).toInt().coerceIn(0, 255),
                (g + (255 - g) * k).toInt().coerceIn(0, 255),
                (b + (255 - b) * k).toInt().coerceIn(0, 255)
            )
        }
    }

    /** At full brush strength, how far toward white a pixel can be pushed by brighten strokes. */
    private const val MAX_BRIGHTEN = 0.55f

    /**
     * Draws [strokes] as round-capped lines onto a single-channel bitmap and returns per-pixel
     * coverage (0..1). [softEdge] feathers the brush edge (for brightening, so there's no visible
     * patch outline); erase/restore only get a hairline of feathering so the new background
     * border stays crisp but not jagged.
     */
    private fun rasterizeStrokes(strokes: List<PhotoStroke>, width: Int, height: Int, softEdge: Boolean): FloatArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val diameterPx = (stroke.diameter * width).coerceAtLeast(2f)
            paint.alpha = if (stroke.tool == BrushTool.BRIGHTEN) (stroke.strength.coerceIn(0f, 1f) * 255f).toInt() else 255
            val feather = if (softEdge) diameterPx * 0.3f else 1.2f
            paint.maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
            val first = stroke.points.first()
            if (stroke.points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(first.first * width, first.second * height, diameterPx / 2f, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = diameterPx
                val path = android.graphics.Path().apply {
                    moveTo(first.first * width, first.second * height)
                    stroke.points.drop(1).forEach { (x, y) -> lineTo(x * width, y * height) }
                }
                canvas.drawPath(path, paint)
            }
        }
        val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        val bytes = buffer.array()
        val rowBytes = bitmap.rowBytes
        bitmap.recycle()
        return FloatArray(width * height) { i -> (bytes[(i / width) * rowBytes + (i % width)].toInt() and 0xFF) / 255f }
    }

    /**
     * Applies [filter] as a per-pixel color-matrix pass — the same technique (and honestly the
     * same rough category of effect) behind most classic Instagram-style filters, which are
     * really just tone/color curve presets rather than anything that understands image content —
     * then layers the user's own manual [brightness]/[contrast] adjustment on top of whichever
     * filter was chosen. Returns [source] unchanged when there's truly nothing to apply, so the
     * common case (no filter, no manual adjustment) allocates no new bitmap.
     */
    private fun applyFilter(source: Bitmap, filter: PhotoFilter, brightness: Float = 0f, contrast: Float = 1f): Bitmap {
        val hasManualAdjustment = brightness != 0f || contrast != 1f
        val filterMatrix = when (filter) {
            PhotoFilter.NONE -> null
            // A mild contrast/saturation/brightness lift — a safe, verifiable "look nicer" rather
            // than face-aware skin smoothing, which risks visible artifacts with no device to
            // check the result on.
            PhotoFilter.ENHANCE -> concatMatrices(contrastBrightnessMatrix(contrast = 1.12f, brightness = 6f), saturationMatrix(1.15f))
            // A brighter, slightly warm lift with gently reduced contrast — the "soft skin,
            // flattering light" look, without any actual blur/smoothing.
            PhotoFilter.GLOW -> concatMatrices(
                concatMatrices(contrastBrightnessMatrix(contrast = 0.95f, brightness = 14f), channelScaleMatrix(rScale = 1.06f, gScale = 1.02f, bScale = 0.98f)),
                saturationMatrix(1.08f)
            )
            // Clean, even, bright and slightly cool-neutral — the flat "studio lighting" look
            // passport photos are usually shot under, for a source photo that wasn't.
            PhotoFilter.STUDIO -> concatMatrices(contrastBrightnessMatrix(contrast = 1.08f, brightness = 18f), saturationMatrix(0.92f))
            PhotoFilter.VIVID -> concatMatrices(contrastBrightnessMatrix(contrast = 1.1f, brightness = 0f), saturationMatrix(1.4f))
            PhotoFilter.BRIGHT -> concatMatrices(contrastBrightnessMatrix(contrast = 1.05f, brightness = 25f), saturationMatrix(1.05f))
            PhotoFilter.WARM -> channelScaleMatrix(rScale = 1.12f, gScale = 1.03f, bScale = 0.86f)
            PhotoFilter.COOL -> channelScaleMatrix(rScale = 0.90f, gScale = 1.0f, bScale = 1.15f)
            PhotoFilter.SOFT -> concatMatrices(contrastBrightnessMatrix(contrast = 0.92f, brightness = 10f), saturationMatrix(0.9f))
            // A washed-out "faded film" look: low contrast (lifts shadows toward gray) plus extra brightness.
            PhotoFilter.FADE -> concatMatrices(contrastBrightnessMatrix(contrast = 0.8f, brightness = 25f), saturationMatrix(0.75f))
            PhotoFilter.DRAMATIC -> concatMatrices(contrastBrightnessMatrix(contrast = 1.35f, brightness = -8f), saturationMatrix(0.7f))
            PhotoFilter.SEPIA -> SEPIA_MATRIX
            PhotoFilter.GRAYSCALE -> saturationMatrix(0f)
            PhotoFilter.BLACK_AND_WHITE -> concatMatrices(contrastBrightnessMatrix(contrast = 1.25f, brightness = 4f), saturationMatrix(0f))
        }
        if (filterMatrix == null && !hasManualAdjustment) return source

        val manualMatrix = if (hasManualAdjustment) contrastBrightnessMatrix(contrast, brightness) else null
        val combined = when {
            filterMatrix != null && manualMatrix != null -> concatMatrices(filterMatrix, manualMatrix)
            filterMatrix != null -> filterMatrix
            else -> manualMatrix!!
        }

        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(combined) })
        return result
    }

    private fun saturationMatrix(saturation: Float): ColorMatrix = ColorMatrix().apply { setSaturation(saturation) }

    private fun contrastBrightnessMatrix(contrast: Float, brightness: Float): ColorMatrix {
        val translate = (-0.5f * contrast + 0.5f) * 255f + brightness
        return ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun channelScaleMatrix(rScale: Float, gScale: Float, bScale: Float): ColorMatrix = ColorMatrix(
        floatArrayOf(
            rScale, 0f, 0f, 0f, 0f,
            0f, gScale, 0f, 0f, 0f,
            0f, 0f, bScale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    private fun concatMatrices(first: ColorMatrix, second: ColorMatrix): ColorMatrix =
        ColorMatrix().apply { setConcat(first, second) }

    /** The standard sepia-tone transform. */
    private val SEPIA_MATRIX = ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )


    private fun blendPixel(foreground: Int, background: Int, foregroundWeight: Float): Int {
        val w = foregroundWeight.coerceIn(0f, 1f)
        val bw = 1f - w
        val r = (Color.red(foreground) * w + Color.red(background) * bw).toInt().coerceIn(0, 255)
        val g = (Color.green(foreground) * w + Color.green(background) * bw).toInt().coerceIn(0, 255)
        val b = (Color.blue(foreground) * w + Color.blue(background) * bw).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    /** A 6x4in sheet with 4 copies of [photoFile] side by side, each at exact 35x45mm. */
    fun buildPrintSheet(context: Context, photoFile: File): File {
        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath) ?: error("Couldn't read the passport photo")
        val document = PdfDocument()
        val outFile = uniqueFile(sheetsDir(context), "${photoFile.nameWithoutExtension}-print", "pdf")
        try {
            val pageWidthPt = mmToPoints(152.4f) // 6in
            val pageHeightPt = mmToPoints(101.6f) // 4in
            val photoWidthPt = mmToPoints(PHOTO_WIDTH_MM)
            val photoHeightPt = mmToPoints(PHOTO_HEIGHT_MM)
            val gapPt = mmToPoints(2f)
            val copies = 4

            val totalContentWidth = copies * photoWidthPt + (copies - 1) * gapPt
            val startX = (pageWidthPt - totalContentWidth) / 2f
            val startY = (pageHeightPt - photoHeightPt) / 2f

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPt.toInt(), pageHeightPt.toInt(), 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            val cutGuidePaint = Paint().apply {
                color = android.graphics.Color.LTGRAY
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            for (i in 0 until copies) {
                val left = startX + i * (photoWidthPt + gapPt)
                val destRect = RectF(left, startY, left + photoWidthPt, startY + photoHeightPt)
                canvas.drawBitmap(bitmap, null, destRect, null)
                canvas.drawRect(destRect, cutGuidePaint)
            }
            document.finishPage(page)
            FileOutputStream(outFile).use { document.writeTo(it) }
        } finally {
            document.close()
            bitmap.recycle()
        }
        return outFile
    }

    // Passport-photo head-size rules (US/India/UK/Schengen are all close to this): the head, crown
    // to chin, should be roughly 50-69% of the final photo's height, with a small margin above the
    // crown rather than it touching the very top edge. These are the middle-of-the-range values
    // this app targets, not pulled from any one country's exact spec — adjust if a print gets
    // rejected as outside a particular office's tolerance.
    private const val TARGET_HEAD_HEIGHT_FRACTION = 0.62f
    private const val TARGET_TOP_MARGIN_FRACTION = 0.10f

    /**
     * Detects the face in [source] and works out the crop that matches standard passport-photo
     * head-size proportions — previously this just center-cropped to the right aspect ratio
     * regardless of how large the person was in the original photo, which meant a normal phone
     * photo (taken from a few feet away, as people naturally do) produced a passport photo with a
     * tiny face and a lot of empty space around it. Falls back to a plain center-crop if no face is
     * found. Returns the rectangle (in [source] pixels) rather than a cropped bitmap, so the same
     * rectangle can also be applied to the person mask.
     */
    private suspend fun cropRectForFace(source: Bitmap, ratio: Float): android.graphics.Rect {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build()
        )
        val face: Face? = try {
            detector.process(InputImage.fromBitmap(source, 0)).await().maxByOrNull { it.boundingBox.height() }
        } catch (e: Exception) {
            null
        } finally {
            detector.close()
        }
        if (face == null) return centerCropRect(source, ratio)

        val box = face.boundingBox
        // ML Kit's face box runs roughly eyebrows-to-chin — it doesn't include the forehead, hair,
        // or the very tip of the chin, so both ends are extended a bit past the detected box to
        // approximate the actual crown-to-chin span a passport photo measures against.
        val faceHeight = box.height().toFloat()
        val crownY = box.top - faceHeight * 0.45f
        val chinY = box.bottom + faceHeight * 0.06f
        val headHeight = (chinY - crownY).coerceAtLeast(1f)
        val faceCenterX = box.centerX().toFloat()

        var cropHeight = (headHeight / TARGET_HEAD_HEIGHT_FRACTION)
        var cropWidth = cropHeight * ratio
        // The ideal crop is bigger than the source itself (e.g. the photo was already a tight
        // close-up) — shrink it to whichever dimension is the tighter fit, keeping the exact
        // passport ratio, rather than clamping width/height independently and distorting it.
        if (cropWidth > source.width || cropHeight > source.height) {
            val shrink = minOf(source.width / cropWidth, source.height / cropHeight)
            cropWidth *= shrink
            cropHeight *= shrink
        }
        val cropTop = crownY - cropHeight * TARGET_TOP_MARGIN_FRACTION
        val cropLeft = faceCenterX - cropWidth / 2f

        // Clamping can shift the crop (e.g. a face near the source's edge), which is the best
        // achievable result short of failing outright — better than refusing to produce a photo.
        val left = cropLeft.coerceIn(0f, (source.width - cropWidth).coerceAtLeast(0f))
        val top = cropTop.coerceIn(0f, (source.height - cropHeight).coerceAtLeast(0f))
        if (cropWidth < 1f || cropHeight < 1f) return centerCropRect(source, ratio)

        val w = cropWidth.toInt().coerceIn(1, source.width)
        val h = cropHeight.toInt().coerceIn(1, source.height)
        val l = left.toInt().coerceIn(0, source.width - w)
        val t = top.toInt().coerceIn(0, source.height - h)
        return android.graphics.Rect(l, t, l + w, t + h)
    }

    /** The largest rectangle matching [ratio] (width/height) that fits in [source], centered. */
    private fun centerCropRect(source: Bitmap, ratio: Float): android.graphics.Rect {
        val sourceRatio = source.width.toFloat() / source.height
        return if (sourceRatio > ratio) {
            // Source is relatively wider than the target — crop its sides.
            val cropWidth = (source.height * ratio).toInt().coerceIn(1, source.width)
            val x = (source.width - cropWidth) / 2
            android.graphics.Rect(x, 0, x + cropWidth, source.height)
        } else {
            // Source is relatively taller than the target — crop top/bottom, biased slightly
            // upward so a centered face (with typical headroom below the chin) isn't cut off.
            val cropHeight = (source.width / ratio).toInt().coerceIn(1, source.height)
            val y = ((source.height - cropHeight) * 0.4f).toInt().coerceIn(0, source.height - cropHeight)
            android.graphics.Rect(0, y, source.width, y + cropHeight)
        }
    }

    /**
     * A modern phone camera photo can be 4000x3000 or larger — decoding that at full resolution
     * just to run it through per-pixel segmentation and an IntArray copy (three-plus full-size
     * ARGB_8888 bitmaps alive at once) is exactly the kind of thing that exhausts memory and
     * crashes the app on real hardware, for zero benefit: the final output is a fixed 413x531px
     * regardless of how large the source was. inSampleSize lets BitmapFactory downsample *during*
     * decode, so the oversized bitmap is never actually allocated in the first place.
     */
    private const val MAX_SOURCE_DIMENSION = 1600

    private fun loadUprightBitmap(context: Context, uri: Uri): Bitmap {
        val exifDegrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_SOURCE_DIMENSION)

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: error("Couldn't read the captured photo")
        if (exifDegrees == 0) return bitmap

        val matrix = Matrix().apply { postRotate(exifDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        while (maxOf(width, height) / (sampleSize * 2) >= maxDimension) sampleSize *= 2
        return sampleSize
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * A private, app-owned target for the camera intent to write the raw capture into, named
     * after [baseName] so it and the eventual processed photo from [cropAndSave] share one id —
     * "Retake" on an existing entry passes that entry's own id back in, overwriting its old raw
     * capture (and, once reprocessed, its old photo) rather than leaving orphaned files behind.
     */
    fun captureTarget(context: Context, baseName: String): Pair<File, Uri> {
        val file = File(capturesDir(context), "$baseName.jpg")
        return file to shareUri(context, file)
    }

    fun newBaseName(): String =
        "Passport-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    /**
     * Copies a gallery-picked image into the same [baseName]-keyed spot a camera capture would
     * use, so a photo chosen from the gallery goes through the exact same background-removal/
     * crop/filter pipeline as one just taken — the picker's own Uri isn't guaranteed to keep
     * working later (permission can be scoped to just this pick), so this makes an app-owned copy
     * up front rather than holding onto it.
     */
    fun copyGalleryImage(context: Context, sourceUri: Uri, baseName: String): File {
        val file = File(capturesDir(context), "$baseName.jpg")
        val input = context.contentResolver.openInputStream(sourceUri) ?: error("Couldn't read the selected photo")
        input.use { source -> FileOutputStream(file).use { output -> source.copyTo(output) } }
        return file
    }

    private fun rootDir(context: Context): File =
        File(context.getExternalFilesDir(null), "PassportPhotos").apply { mkdirs() }

    private fun photosDir(context: Context): File = File(rootDir(context), "photos").apply { mkdirs() }
    private fun sheetsDir(context: Context): File = File(rootDir(context), "sheets").apply { mkdirs() }
    private fun capturesDir(context: Context): File = File(rootDir(context), "captures").apply { mkdirs() }

    private fun uniqueFile(dir: File, baseName: String, extension: String): File {
        var candidate = File(dir, "$baseName.$extension")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($counter).$extension")
            counter++
        }
        return candidate
    }
}
