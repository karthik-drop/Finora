package com.finora.app.data.repo

import androidx.room.withTransaction
import com.finora.app.core.Dates
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.data.db.TransactionEntity
import com.finora.app.domain.csv.TransactionCsv
import com.finora.app.domain.merchant.MerchantNormalizer
import com.finora.app.domain.model.PaymentMethod
import com.finora.app.domain.model.TxnSource
import com.finora.app.domain.sms.Deduplication
import kotlinx.coroutines.flow.first

/** CSV export, and an import that previews first and never inserts a row twice. */
class CsvService(private val db: FinoraDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun export(from: Long?, to: Long?): String {
        val rows = db.transactions().search(null, from, to, null, null, null, null, null, null, null, Int.MAX_VALUE)
        val list = rows.first()
        return TransactionCsv.write(list.sortedBy { it.txn.occurredAt }.map { r ->
            TransactionCsv.Row(
                Dates.toLocalDateTime(r.txn.occurredAt), r.txn.amountPaise, r.txn.direction, r.txn.type, r.merchantName ?: r.txn.rawCounterparty, r.personName,
                r.categoryName, r.txn.purpose, r.accountName, r.txn.paymentMethod?.label, r.txn.reference, r.txn.notes, r.txn.fingerprint,
            )
        })
    }

    data class ImportPreview(val rows: List<TransactionCsv.Row>, val newRows: List<TransactionCsv.Row>, val duplicates: Int, val errors: List<String>)

    /** Reads and classifies every row as new or duplicate. Writes nothing. */
    suspend fun preview(text: String): ImportPreview {
        val parsed = TransactionCsv.read(text)
        val seen = mutableSetOf<String>()
        val fresh = parsed.rows.filter { r ->
            val fp = fingerprintFor(r)
            if (!seen.add(fp)) return@filter false
            if (db.transactions().getByFingerprint(fp) != null) return@filter false
            val at = millis(r)
            db.transactions().nearby(r.amountPaise, r.direction, at - 60_000, at + 60_000).none { e ->
                (e.reference != null && e.reference == r.reference) || (e.rawCounterparty?.let(MerchantNormalizer::key) == r.merchant?.let(MerchantNormalizer::key))
            }
        }
        return ImportPreview(parsed.rows, fresh, parsed.rows.size - fresh.size, parsed.errors)
    }

    suspend fun commit(preview: ImportPreview): Int = db.withTransaction {
        val cats = db.categories().all().associateBy { it.name.lowercase() }
        val accounts = db.accounts().all().associateBy { it.name.lowercase() }
        val now = clock()
        preview.newRows.count { r ->
            db.transactions().insert(
                TransactionEntity(
                    amountPaise = r.amountPaise, direction = r.direction, type = r.type, occurredAt = millis(r),
                    accountId = r.account?.lowercase()?.let { accounts[it]?.id },
                    merchantId = r.merchant?.let { db.merchants().byKey(MerchantNormalizer.key(it))?.id },
                    categoryId = r.category?.lowercase()?.let { cats[it]?.id }, purpose = r.purpose, rawCounterparty = r.merchant,
                    reference = r.reference, paymentMethod = PaymentMethod.entries.firstOrNull { it.label.equals(r.method, true) || it.name.equals(r.method, true) },
                    notes = r.notes, source = TxnSource.CSV_IMPORT, fingerprint = fingerprintFor(r), createdAt = now, updatedAt = now,
                )
            ) != -1L
        }
    }

    private fun millis(r: TransactionCsv.Row) = r.dateTime.atZone(Dates.zone).toInstant().toEpochMilli()

    /** A Finora export carries its own fingerprints, so re-importing it is a no-op. */
    private fun fingerprintFor(r: TransactionCsv.Row): String = r.fingerprint?.takeIf { it.isNotBlank() }
        ?: ("csv:" + Deduplication.sha256(listOf(r.dateTime, r.amountPaise, r.direction, r.merchant?.let(MerchantNormalizer::key), r.reference).joinToString("|")).take(32))
}
