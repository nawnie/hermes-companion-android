package dev.hermeskotlin.android.local

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** These tools operate only on images visible through Android's media permissions. */
class LocalImageTools(private val context: Context, private val cancelled: AtomicBoolean, private val progress: (String) -> Unit) : ToolSet {
    private val resolver get() = context.contentResolver
    private val path = "${Environment.DIRECTORY_PICTURES}/Hermes Collection/"

    @Tool(description = "Count images available to Hermes in the phone media library, excluding the Hermes Collection folder. Android permission may limit what is visible.")
    fun count_phone_images(): String = countPhoneImages()

    fun countPhoneImages(): String = guarded {
        require(Build.VERSION.SDK_INT >= 29) { "Image tools require Android 10 or later" }
        var count = 0
        eachImage { _, _, _, _, _, _ -> count++ }
        progress("Photo tool found $count accessible images.")
        JSONObject().put("accessible_images", count).put("scope", "Android media library visible to this app; hidden and unindexed files may be absent").toString()
    }

    @Tool(description = "Copy accessible phone images into Pictures/Hermes Collection, keeping originals. Previously copied source versions are skipped. May be called again if incomplete.")
    fun collect_phone_images(): String = collectPhoneImages()

    fun collectPhoneImages(): String = guarded {
        require(Build.VERSION.SDK_INT >= 29) { "Image collection requires Android 10 or later" }
        val destination = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val existing = mutableSetOf<String>()
        resolver.query(destination, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf(path), null)?.use { cursor ->
            while (cursor.moveToNext()) existing.add(cursor.getString(0) ?: "")
        } ?: error("Could not inspect collection folder")
        var copied = 0; var skipped = 0; var failed = 0; var seen = 0; var incomplete = false
        eachImage { uri, name, mime, size, modified, generation ->
            // Android 10 lacks GENERATION_MODIFIED. Hash the actual source bytes so a
            // same-size edit within DATE_MODIFIED's one-second precision is not skipped.
            val sourceVersion = if (Build.VERSION.SDK_INT >= 30) "$size|$modified|$generation" else try {
                val digest = MessageDigest.getInstance("SHA-256")
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open source image" }
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled.get()) throw InterruptedException("Collection cancelled")
                        val n = input.read(buffer); if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                failed++
                return@eachImage
            }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest("$uri|$sourceVersion".toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val safeName = name.replace(Regex("[\\\\/\\u0000-\\u001f]"), "_").takeLast(120).ifBlank { "image" }
            val targetName = "hermes_${fingerprint}_$safeName"
            if (targetName in existing) { skipped++; return@eachImage }
            if (seen >= 10000) { incomplete = true; return@eachImage }
            seen++
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, targetName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            var target: android.net.Uri? = null
            try {
                target = resolver.insert(destination, values) ?: error("Could not create destination image")
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open source image" }
                    resolver.openOutputStream(target).use { output ->
                        requireNotNull(output) { "Could not open destination image" }
                        val buffer = ByteArray(128 * 1024)
                        var bytesCopied = 0L
                        val copiedDigest = if (Build.VERSION.SDK_INT < 30) MessageDigest.getInstance("SHA-256") else null
                        while (true) {
                            if (cancelled.get()) throw InterruptedException("Collection cancelled")
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n)
                            bytesCopied += n
                            copiedDigest?.update(buffer, 0, n)
                        }
                        if (copiedDigest != null) {
                            val copiedHash = copiedDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                            check(copiedHash == sourceVersion) { "Source changed during copy" }
                        }
                        check(size <= 0 || bytesCopied == size) { "Source size changed or copy was incomplete" }
                    }
                }
                check(resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { "Could not finish destination image" }
                existing.add(targetName); copied++
                if (copied % 10 == 0) progress("Copied $copied images; skipped $skipped already collected.")
            } catch (e: Exception) {
                target?.let { runCatching { resolver.delete(it, null, null) } }
                if (e is InterruptedException) throw e
                failed++
            }
        }
        progress("Collection finished: $copied copied, $skipped already collected, $failed failed" + if (incomplete) "; more remain." else ".")
        JSONObject().put("copied", copied).put("already_collected", skipped).put("failed", failed)
            .put("incomplete", incomplete).put("destination", path)
            .put("scope", "Only indexed images accessible under Android media permission; originals kept").toString()
    }

    private inline fun eachImage(action: (android.net.Uri, String, String, Long, Long, Long) -> Unit) {
        val volumes = MediaStore.getExternalVolumeNames(context)
        for (volume in volumes) {
            val collection = MediaStore.Images.Media.getContentUri(volume)
            val columns = mutableListOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= 30) columns.add(MediaStore.Images.Media.GENERATION_MODIFIED)
            resolver.query(collection, columns.toTypedArray(), null, null, "${MediaStore.Images.Media._ID} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cancelled.get()) throw InterruptedException("Operation cancelled")
                    val relative = cursor.getString(5) ?: ""
                    if (relative.startsWith(path, ignoreCase = true)) continue
                    action(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1) ?: "image", cursor.getString(2) ?: "image/jpeg", cursor.getLong(3), cursor.getLong(4), if (Build.VERSION.SDK_INT >= 30) cursor.getLong(6) else 0L)
                }
            } ?: error("Could not read media library volume $volume")
        }
    }

    private inline fun guarded(block: () -> String): String = try { block() } catch (e: Exception) {
        progress("Image operation stopped: ${e.message ?: "unknown error"}")
        JSONObject().put("error", e.message ?: "Image operation failed").toString()
    }
}
