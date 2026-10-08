package com.mobicareapp.passport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One saved passport photo: [id] doubles as the shared file base name for both the raw capture
 * (`captures/<id>.jpg`) and the processed photo (`photos/<id>.jpg`) — see [PassportPhotoFileStore]
 * — which is what lets Edit redo background/filter from the original capture instead of
 * re-processing an already-composited image, and Delete clean up both files from one id.
 */
data class PassportPhotoEntry(
    val id: String,
    val timestampMillis: Long,
    val rawCapturePath: String,
    val photoPath: String,
    val backgroundColorArgb: Int,
    val filterName: String,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    /** Hand-drawn eraser/brush edits — replayed on every reprocess, so they survive changing the background or filter. */
    val strokes: List<PhotoStroke> = emptyList()
) {
    val filter: PhotoFilter get() = runCatching { PhotoFilter.valueOf(filterName) }.getOrDefault(PhotoFilter.NONE)
}

/**
 * Tracks saved passport photos in a small JSON file rather than a new Room table — this is one
 * flat, app-private list with no querying needs beyond "load them all", so a Room entity plus a
 * schema migration would be pure overhead (and migration risk) for no real benefit here.
 */
object PassportPhotoHistoryStore {
    private const val FILE_NAME = "history.json"

    /** Newest first, and only entries whose photo file still actually exists (in case a file was ever removed outside the app). */
    fun loadAll(context: Context): List<PassportPhotoEntry> =
        readEntries(context).filter { File(it.photoPath).exists() }.sortedByDescending { it.timestampMillis }

    /** Upsert by [PassportPhotoEntry.id] rather than a blind append — two entries sharing an id would otherwise crash the list screen's LazyColumn ("Key ... already used"), and callers only need to reason about "save this entry" without separately tracking whether they've saved it before. */
    fun add(context: Context, entry: PassportPhotoEntry) {
        val existing = readEntries(context)
        val updated = if (existing.any { it.id == entry.id }) {
            existing.map { if (it.id == entry.id) entry else it }
        } else {
            existing + entry
        }
        writeEntries(context, updated)
    }

    /** Updates the background/filter/adjustment recorded for [id] — the photo file itself is overwritten in place by [PassportPhotoFileStore.cropAndSave], so its path doesn't change. */
    fun update(
        context: Context,
        id: String,
        backgroundColorArgb: Int,
        filterName: String,
        brightness: Float,
        contrast: Float,
        strokes: List<PhotoStroke>
    ) {
        val updated = readEntries(context).map {
            if (it.id == id) {
                it.copy(backgroundColorArgb = backgroundColorArgb, filterName = filterName, brightness = brightness, contrast = contrast, strokes = strokes)
            } else {
                it
            }
        }
        writeEntries(context, updated)
    }

    fun delete(context: Context, entry: PassportPhotoEntry) {
        File(entry.photoPath).delete()
        File(entry.rawCapturePath).delete()
        writeEntries(context, readEntries(context).filterNot { it.id == entry.id })
    }

    private fun readEntries(context: Context): List<PassportPhotoEntry> {
        val file = historyFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                PassportPhotoEntry(
                    id = obj.getString("id"),
                    timestampMillis = obj.getLong("timestampMillis"),
                    rawCapturePath = obj.getString("rawCapturePath"),
                    photoPath = obj.getString("photoPath"),
                    backgroundColorArgb = obj.getInt("backgroundColorArgb"),
                    filterName = obj.getString("filterName"),
                    brightness = obj.optDouble("brightness", 0.0).toFloat(),
                    contrast = obj.optDouble("contrast", 1.0).toFloat(),
                    strokes = PhotoStrokeCodec.decode(obj.optString("strokes", ""))
                )
            }
        }.getOrDefault(emptyList())
            // Self-heals a history file written before add() became an upsert: keeps the last
            // (most recently written) copy of any id that shows up more than once, so a file that
            // already has a duplicate baked in stops crashing the list screen's LazyColumn instead
            // of requiring the user to clear app data.
            .asReversed().distinctBy { it.id }.asReversed()
    }

    private fun writeEntries(context: Context, entries: List<PassportPhotoEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("timestampMillis", entry.timestampMillis)
                    put("rawCapturePath", entry.rawCapturePath)
                    put("photoPath", entry.photoPath)
                    put("backgroundColorArgb", entry.backgroundColorArgb)
                    put("filterName", entry.filterName)
                    put("brightness", entry.brightness.toDouble())
                    put("contrast", entry.contrast.toDouble())
                    put("strokes", PhotoStrokeCodec.encode(entry.strokes))
                }
            )
        }
        historyFile(context).writeText(array.toString())
    }

    private fun historyFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "PassportPhotos").apply { mkdirs() }
        return File(dir, FILE_NAME)
    }
}
