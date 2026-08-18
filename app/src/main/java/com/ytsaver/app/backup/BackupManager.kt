package com.ytsaver.app.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ytsaver.app.data.MediaAccess
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.data.PublicMediaStore
import com.ytsaver.app.data.SavedMedia
import com.ytsaver.app.data.SavedMediaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Copies downloaded files (plus a JSON manifest of their captions/metadata)
 * to a folder the user picks via the system folder picker, so a phone change
 * or app reinstall doesn't lose them. Restore reverses the process.
 */
object BackupManager {

    private const val MANIFEST_NAME = "ytsaver_backup.json"

    suspend fun backup(context: Context, treeUri: Uri, items: List<SavedMedia>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                    ?: error("Couldn't open the chosen folder")

                root.findFile(MANIFEST_NAME)?.delete()
                val manifest = JSONArray()
                var copied = 0

                for (item in items) {
                    val input = MediaAccess.openInputStream(context, item.filePath)
                    if (input == null) continue

                    val existing = root.findFile(item.fileName)
                    existing?.delete()
                    val destDoc = root.createFile("application/octet-stream", item.fileName)
                    if (destDoc == null) {
                        input.close()
                        continue
                    }

                    val output = context.contentResolver.openOutputStream(destDoc.uri)
                    if (output == null) {
                        input.close()
                        continue
                    }
                    output.use { out -> input.use { it.copyTo(out) } }

                    manifest.put(
                        JSONObject().apply {
                            put("caption", item.caption)
                            put("sourceUrl", item.sourceUrl)
                            put("type", item.type.name)
                            put("fileName", item.fileName)
                            put("thumbnailUrl", item.thumbnailUrl ?: JSONObject.NULL)
                            put("sizeBytes", item.sizeBytes)
                            put("durationSeconds", item.durationSeconds)
                            put("createdAt", item.createdAt)
                        }
                    )
                    copied++
                }

                val manifestDoc = root.createFile("application/json", MANIFEST_NAME)
                    ?: error("Couldn't write the backup manifest")
                context.contentResolver.openOutputStream(manifestDoc.uri)?.use { out ->
                    out.write(manifest.toString().toByteArray())
                }

                copied
            }
        }

    suspend fun restore(context: Context, treeUri: Uri, dao: SavedMediaDao): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                    ?: error("Couldn't open the chosen folder")
                val manifestDoc = root.findFile(MANIFEST_NAME)
                    ?: error("No backup found in that folder")

                val manifestText = context.contentResolver.openInputStream(manifestDoc.uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("Couldn't read the backup manifest")

                val array = JSONArray(manifestText)
                var restored = 0

                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    val caption = entry.getString("caption")
                    val sourceUrl = entry.getString("sourceUrl")
                    val type = MediaType.valueOf(entry.getString("type"))
                    val fileName = entry.getString("fileName")

                    val alreadyPresent = dao.findBySourceUrlAndType(sourceUrl, type) != null
                    if (alreadyPresent) continue

                    val sourceDoc = root.findFile(fileName) ?: continue
                    val sourceInput = context.contentResolver.openInputStream(sourceDoc.uri) ?: continue

                    val storedPath = if (PublicMediaStore.isSupported()) {
                        val mimeType = if (type == MediaType.VIDEO) "video/mp4" else "audio/mp4"
                        val uri = PublicMediaStore.createPendingTarget(context, type, fileName, mimeType)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            sourceInput.use { it.copyTo(out) }
                        }
                        PublicMediaStore.finalize(context, uri)
                        uri.toString()
                    } else {
                        val targetDir = legacyMediaDir(context, type)
                        val targetFile = uniqueFile(targetDir, fileName)
                        targetFile.outputStream().use { out ->
                            sourceInput.use { it.copyTo(out) }
                        }
                        targetFile.absolutePath
                    }

                    dao.insert(
                        SavedMedia(
                            caption = caption,
                            sourceUrl = sourceUrl,
                            type = type,
                            filePath = storedPath,
                            fileName = fileName,
                            thumbnailUrl = entry.optString("thumbnailUrl").ifBlank { null },
                            sizeBytes = MediaAccess.length(context, storedPath),
                            durationSeconds = entry.optLong("durationSeconds", 0),
                            createdAt = entry.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                    restored++
                }
                restored
            }
        }

    private fun legacyMediaDir(context: Context, type: MediaType): File {
        val publicSubDir = if (type == MediaType.VIDEO) android.os.Environment.DIRECTORY_MOVIES
        else android.os.Environment.DIRECTORY_MUSIC
        val dir = context.getExternalFilesDir(publicSubDir) ?: context.filesDir
        dir.mkdirs()
        return dir
    }

    private fun uniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = File(dir, name)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter)$ext")
            counter++
        }
        return candidate
    }
}
