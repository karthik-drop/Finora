package com.finora.app.domain.csv

import com.finora.app.core.Money
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.TxnType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** RFC 4180 CSV: quotes, embedded commas, doubled quotes and newlines inside fields. */
object Csv {
    fun escape(field: String?): String {
        val s = field ?: ""
        // A leading =,+,-,@ would be run as a formula by spreadsheet apps.
        val safe = if (s.isNotEmpty() && s[0] in "=+-@" && s.toDoubleOrNull() == null) "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun row(fields: List<String?>): String = fields.joinToString(",") { escape(it) }

    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row += field.toString(); field = StringBuilder() }
                '\r' -> Unit
                '\n' -> { row += field.toString(); rows += row; row = mutableListOf(); field = StringBuilder() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
}

/** The transaction CSV format Finora exports and can import back. */
object TransactionCsv {
    val HEADER = listOf("date", "amount", "direction", "type", "merchant", "person", "category", "purpose", "account", "method", "reference", "notes", "fingerprint")
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    data class Row(
        val dateTime: LocalDateTime,
        val amountPaise: Long,
        val direction: Direction,
        val type: TxnType,
        val merchant: String?,
        val person: String?,
        val category: String?,
        val purpose: String?,
        val account: String?,
        val method: String?,
        val reference: String?,
        val notes: String?,
        val fingerprint: String?,
    )

    fun write(rows: List<Row>): String = buildString {
        append(Csv.row(HEADER)).append("\r\n")
        rows.forEach { r ->
            append(
                Csv.row(
                    listOf(
                        r.dateTime.format(FMT), Money.toInput(r.amountPaise), r.direction.name, r.type.name, r.merchant, r.person,
                        r.category, r.purpose, r.account, r.method, r.reference, r.notes, r.fingerprint,
                    )
                )
            ).append("\r\n")
        }
    }

    data class ParseOutcome(val rows: List<Row>, val errors: List<String>)

    fun read(text: String): ParseOutcome {
        val all = Csv.parse(text)
        if (all.isEmpty()) return ParseOutcome(emptyList(), listOf("The file is empty."))
        val header = all.first().map { it.trim().lowercase() }
        val idx = HEADER.associateWith { header.indexOf(it) }
        if (idx["date"]!! < 0 || idx["amount"]!! < 0) return ParseOutcome(emptyList(), listOf("Missing required columns: date and amount."))
        val rows = mutableListOf<Row>()
        val errors = mutableListOf<String>()
        all.drop(1).forEachIndexed { n, cols ->
            fun col(name: String): String? = idx[name]?.takeIf { it >= 0 }?.let { cols.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }?.removePrefix("'")
            val line = n + 2
            val date = col("date")?.let { runCatching { LocalDateTime.parse(it, FMT) }.getOrNull() ?: runCatching { java.time.LocalDate.parse(it).atStartOfDay() }.getOrNull() }
            val amount = Money.parseToPaise(col("amount"))
            if (date == null) { errors += "Line $line: unreadable date"; return@forEachIndexed }
            if (amount == null || amount <= 0) { errors += "Line $line: unreadable amount"; return@forEachIndexed }
            val direction = col("direction")?.let { runCatching { Direction.valueOf(it.uppercase()) }.getOrNull() } ?: Direction.DEBIT
            val type = col("type")?.let { runCatching { TxnType.valueOf(it.uppercase()) }.getOrNull() }
                ?: if (direction == Direction.DEBIT) TxnType.EXPENSE else TxnType.INCOME
            rows += Row(date, amount, direction, type, col("merchant"), col("person"), col("category"), col("purpose"), col("account"), col("method"), col("reference"), col("notes"), col("fingerprint"))
        }
        return ParseOutcome(rows, errors)
    }
}
