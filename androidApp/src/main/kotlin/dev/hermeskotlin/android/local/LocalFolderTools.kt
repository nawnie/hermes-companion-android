package dev.hermeskotlin.android.local

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Access is limited to immediate children of the selected Storage Access Framework folder. */
class LocalFolderTools(private val context: Context) : ToolSet {
    private val outputBudgetBytes = 1800
    private fun tree(): Uri = Uri.parse(context.getSharedPreferences("local_folder", Context.MODE_PRIVATE).getString("tree", null) ?: error("No folder selected. Use Choose file folder first."))
    private fun queryChildren(uri: Uri): Cursor = context.contentResolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)),
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
        null, null, null
    ) ?: error("Could not access the chosen folder")

    @Tool(description = "List immediate children of the selected folder in pages. Use next_offset to continue; entries include IDs for reading text files. Folder changes between pages can change ordering.")
    fun list_selected_folder(@ToolParam(description = "Zero-based entry offset; use 0 initially and next_offset for later pages") offset: Int = 0): String = listSelectedFolder(offset)

    fun listSelectedFolder(offset: Int = 0): String = guarded {
        require(offset in 0..1_000_000) { "Folder offset must be between 0 and 1000000" }
        val items = JSONArray()
        val selected = tree()
        queryChildren(selected).use { cursor ->
            var index = offset
            while (items.length() < 20 && cursor.moveToPosition(index)) {
                val id = cursor.getString(0) ?: error("Folder entry has no document ID")
                val name = (cursor.getString(1) ?: "").take(160)
                val type = (cursor.getString(2) ?: "").take(120)
                val item = JSONObject().put("id", id).put("name", name).put("mime_type", type)
                val candidate = JSONArray(items.toString()).put(item)
                val encoded = JSONObject().put("items", candidate).put("next_offset", index + 1).put("has_more", true).toString()
                if (encoded.toByteArray(Charsets.UTF_8).size > outputBudgetBytes) {
                    if (items.length() == 0) return@guarded JSONObject().put("error", "Entry is too large to return within the tool response budget").put("next_offset", index + 1).toString()
                    break
                }
                items.put(item)
                index++
            }
            val more = cursor.moveToPosition(index)
            JSONObject().put("items", items).put("next_offset", if (more) index else JSONObject.NULL).put("has_more", more).toString()
        }
    }

    @Tool(description = "Read a bounded UTF-8 text chunk from an immediate child of the selected folder. Pass next_offset as byte_offset to continue; offsets are bytes, not characters.")
    fun read_selected_text_file(
        @ToolParam(description = "Document ID returned by list_selected_folder") document_id: String,
        @ToolParam(description = "Byte offset returned by the preceding chunk; use 0 initially") byte_offset: Int = 0
    ): String = readSelectedTextFile(document_id, byte_offset)

    fun readSelectedTextFile(documentId: String, byteOffset: Int = 0): String = guarded {
        require(byteOffset >= 0) { "Byte offset must not be negative" }
        val selected = tree()
        val mime = queryChildren(selected).use { cursor ->
            var found: String? = null
            while (cursor.moveToNext()) if (cursor.getString(0) == documentId) { found = cursor.getString(2); break }
            found ?: error("File is not an immediate child of the selected folder")
        }
        require(mime.startsWith("text/") || mime in setOf("application/json", "application/xml")) { "Only text, JSON and XML files can be read" }
        val file = DocumentsContract.buildDocumentUriUsingTree(selected, documentId)
        context.contentResolver.openInputStream(file)?.use { input ->
            var remaining = byteOffset.toLong()
            val skipBuffer = ByteArray(8192)
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped
                else {
                    val n = input.read(skipBuffer, 0, minOf(remaining, skipBuffer.size.toLong()).toInt())
                    require(n > 0) { "Byte offset is beyond end of file" }
                    remaining -= n
                }
            }
            val buffer = ByteArray(256); var used = 0
            while (used < buffer.size) {
                val n = input.read(buffer, used, buffer.size - used)
                if (n < 0) break
                if (n == 0) break
                used += n
            }
            val extra = input.read() >= 0
            var length = used
            var decoded: String? = null
            // A valid UTF-8 code point may cross the boundary. Leave up to three trailing
            // bytes for the next chunk; reject invalid UTF-8 in the middle of a chunk.
            while (length >= maxOf(0, used - 3)) {
                decoded = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer, 0, length)).toString()
                } catch (_: java.nio.charset.CharacterCodingException) { null }
                if (decoded != null) break
                length--
            }
            require(decoded != null && (length > 0 || used == 0)) { "File is not valid UTF-8 at this offset" }
            val more = extra || length < used
            val result = JSONObject().put("text", decoded).put("has_more", more)
                .put("next_offset", if (more) byteOffset.toLong() + length else JSONObject.NULL).toString()
            check(result.toByteArray(Charsets.UTF_8).size <= outputBudgetBytes) { "Text chunk exceeded the response budget" }
            result
        } ?: error("Cannot open file")
    }
    private inline fun guarded(block: () -> String): String = try { block() } catch (e: Exception) {
        JSONObject().put("error", (e.message ?: "File operation failed").take(200)).toString()
    }
}
