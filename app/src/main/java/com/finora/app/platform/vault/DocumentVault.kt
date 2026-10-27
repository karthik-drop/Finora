package com.finora.app.platform.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.finora.app.data.db.DocumentEntity
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.domain.model.DocumentKind
import com.finora.app.platform.crypto.VaultCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Stores documents encrypted in app-private storage. Nothing is uploaded anywhere. */
class DocumentVault(private val context: Context, private val db: FinoraDatabase) {
    val dir: File get() = File(context.filesDir, "vault").apply { mkdirs() }

    suspend fun import(uri: Uri, title: String, kind: DocumentKind, tags: String, notes: String?): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        val stored = UUID.randomUUID().toString() + ".bin"
        val target = File(dir, stored)
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { VaultCipher.encrypt(input, it) } }
            ?: error("Could not open the selected file")
        db.documents().insert(
            DocumentEntity(
                title = title.ifBlank { name ?: "Document" }, kind = kind, storedFileName = stored, originalFileName = name,
                mimeType = mime, sizeBytes = if (size >= 0) size else target.length(), tags = tags.lowercase(), notes = notes,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /** Decrypts to a short-lived cache file for preview/sharing; [clearPreviews] removes them. */
    suspend fun decryptToCache(doc: DocumentEntity): File = withContext(Dispatchers.IO) {
        val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, doc.originalFileName?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "document")
        File(dir, doc.storedFileName).inputStream().use { input -> VaultCipher.decryptingStream(input).use { d -> out.outputStream().use { d.copyTo(it) } } }
        out
    }

    suspend fun readBytes(doc: DocumentEntity): ByteArray = withContext(Dispatchers.IO) {
        File(dir, doc.storedFileName).inputStream().use { input -> VaultCipher.decryptingStream(input).use { it.readBytes() } }
    }

    suspend fun delete(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        File(dir, doc.storedFileName).delete()
        db.documents().delete(doc)
    }

    fun clearPreviews() {
        File(context.cacheDir, "exports").listFiles()?.forEach { it.delete() }
    }
}
