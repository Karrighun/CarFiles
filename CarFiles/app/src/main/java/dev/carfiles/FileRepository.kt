package dev.carfiles

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class FileEntry(val uri: Uri, val name: String, val directory: Boolean, val kind: String)

class FileRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    fun root(): Uri? = prefs.getString("tree", null)?.let(Uri::parse)
    fun saveRoot(uri: Uri) { prefs.edit().putString("tree", uri.toString()).apply() }
    fun children(uri: Uri): List<FileEntry> {
        // fromTreeUri() resets a child URI to the tree root. Query the actual document ID.
        val documentId = if (DocumentsContract.isDocumentUri(context, uri))
            DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val entries = mutableListOf<FileEntry>()
        val cursor = context.contentResolver.query(childrenUri, columns, null, null, null)
            ?: error("Папка недоступна. Выберите её заново.")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val name = it.getString(1) ?: "Без имени"
                val mime = it.getString(2)
                entries.add(FileEntry(DocumentsContract.buildDocumentUriUsingTree(uri, id), name,
                    mime == DocumentsContract.Document.MIME_TYPE_DIR, FileKind.classify(mime, name)))
            }
        }
        return entries.sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name.lowercase() })
    }
    fun audio(): List<FileEntry> {
        val start = root() ?: return emptyList()
        val pending = ArrayDeque<Pair<Uri, Int>>()
        val seen = hashSetOf<String>()
        val tracks = mutableListOf<FileEntry>()
        pending.add(start to 0)
        var visited = 0
        while (pending.isNotEmpty()) {
            val (uri, depth) = pending.removeFirst()
            if (!seen.add(uri.toString())) continue
            for (entry in children(uri)) {
                check(++visited <= 10000) { "В папке более 10000 объектов. Выберите папку поменьше." }
                if (entry.directory) {
                    check(depth < 32) { "Слишком много вложенных папок." }
                    pending.add(entry.uri to depth + 1)
                } else if (entry.kind == "audio") tracks.add(entry)
            }
        }
        return tracks
    }
}
