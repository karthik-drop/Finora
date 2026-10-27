package com.finora.app.platform.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.platform.crypto.PasswordCipher
import com.finora.app.platform.crypto.VaultCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup is ONE password-encrypted file holding the database and the decrypted vault files
 * (they are re-encrypted with the new device's own key on restore). It is written wherever the
 * user picks with the system file picker; Finora never uploads it.
 *
 * Restore REPLACES all data, which is what makes duplicates impossible: nothing is merged.
 * (CSV import is the merging path, and it de-duplicates by fingerprint.)
 */
class BackupManager(private val context: Context, private val dbProvider: () -> FinoraDatabase) {

    data class Preview(
        val createdAt: Long,
        val schemaVersion: Int,
        val counts: Map<String, Int>,
        val documentFiles: Int,
        internal val stagingDir: File,
    )

    private val countedTables = listOf("transactions", "accounts", "merchants", "people", "loans", "loan_payments", "investments", "investment_valuations", "bill_splits", "documents")

    suspend fun createBackup(target: Uri, password: CharArray): Int = withContext(Dispatchers.IO) {
        require(password.size >= 8) { "Use a password of at least 8 characters." }
        val db = dbProvider()
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val dbFile = context.getDatabasePath(FinoraDatabase.NAME)
        val vaultDir = File(context.filesDir, "vault")
        var files = 0
        context.contentResolver.openOutputStream(target, "wt")?.use { raw ->
            PasswordCipher.encryptingStream(password, raw).use { enc ->
                ZipOutputStream(enc).use { zip ->
                    val manifest = JSONObject().put("app", "finora").put("createdAt", System.currentTimeMillis())
                        .put("schemaVersion", db.openHelper.readableDatabase.version)
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("finora.db")); dbFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    vaultDir.listFiles()?.forEach { f ->
                        zip.putNextEntry(ZipEntry("vault/${f.name}"))
                        f.inputStream().use { input -> VaultCipher.decryptingStream(input).use { it.copyTo(zip) } }
                        zip.closeEntry()
                        files++
                    }
                }
            }
        } ?: error("Could not write to the chosen location.")
        files
    }

    /** Decrypts into a private staging folder and reports what WOULD be restored. Changes nothing. */
    suspend fun preview(source: Uri, password: CharArray): Preview = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var manifest: JSONObject? = null
        var docs = 0
        try {
            context.contentResolver.openInputStream(source)?.use { raw ->
                ZipInputStream(PasswordCipher.decryptingStream(password, raw)).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val name = e.name
                        // Never let an entry name escape the staging folder.
                        val out = File(staging, name).canonicalFile
                        require(out.path.startsWith(staging.canonicalPath)) { "Invalid backup entry" }
                        when {
                            name == "manifest.json" -> manifest = JSONObject(zip.readBytes().decodeToString())
                            name == "finora.db" || name.startsWith("vault/") -> {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zip.copyTo(it) }
                                if (name.startsWith("vault/")) docs++
                            }
                        }
                        e = zip.nextEntry
                    }
                }
            } ?: error("Could not open the chosen file.")
        } catch (e: PasswordCipher.BadBackup) {
            throw e
        } catch (e: java.io.IOException) {
            throw PasswordCipher.BadBackup("Wrong password, or the backup file is damaged.")
        }
        val m = manifest ?: throw PasswordCipher.BadBackup("This file is not a Finora backup.")
        val dbFile = File(staging, "finora.db")
        if (!dbFile.exists()) throw PasswordCipher.BadBackup("The backup has no database.")
        val counts = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sdb ->
            countedTables.associateWith { t -> runCatching { sdb.rawQuery("SELECT COUNT(*) FROM $t", null).use { c -> c.moveToFirst(); c.getInt(0) } }.getOrDefault(0) }
        }
        val current = dbProvider().openHelper.readableDatabase.version
        val version = m.optInt("schemaVersion")
        if (version > current) throw PasswordCipher.BadBackup("This backup was made by a newer version of Finora. Update the app first.")
        Preview(m.optLong("createdAt"), version, counts, docs, staging)
    }

    /**
     * Replaces the live data with the staged backup. The caller must restart the process
     * afterwards (the database is closed here). Older backups are upgraded by Room's own
     * migrations the next time the database opens.
     */
    suspend fun applyRestore(preview: Preview) = withContext(Dispatchers.IO) {
        dbProvider().close()
        val dbFile = context.getDatabasePath(FinoraDatabase.NAME)
        listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { it.delete() }
        File(preview.stagingDir, "finora.db").copyTo(dbFile, overwrite = true)
        val vault = File(context.filesDir, "vault")
        vault.deleteRecursively(); vault.mkdirs()
        File(preview.stagingDir, "vault").listFiles()?.forEach { f ->
            File(vault, f.name).outputStream().use { out -> f.inputStream().use { VaultCipher.encrypt(it, out) } }
        }
        preview.stagingDir.deleteRecursively()
    }
}
