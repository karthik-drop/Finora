package com.finora.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {
    /** The device zone. Tests pin it explicitly where it matters. */
    var zone: ZoneId = ZoneId.systemDefault()

    private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val dayShortFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val monthFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    fun nowMillis(): Long = System.currentTimeMillis()
    fun today(): LocalDate = LocalDate.now(zone)

    fun toLocalDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun toLocalDateTime(millis: Long): LocalDateTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
    fun startOfDayMillis(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
    fun monthRange(month: YearMonth): LongRange =
        startOfDayMillis(month.atDay(1)) until startOfDayMillis(month.plusMonths(1).atDay(1))

    fun formatDay(millis: Long): String = toLocalDate(millis).format(dayFmt)
    fun formatDay(date: LocalDate): String = date.format(dayFmt)
    fun formatDayShort(date: LocalDate): String =
        if (date.year == today().year) date.format(dayShortFmt) else date.format(dayFmt)
    fun formatTime(millis: Long): String = toLocalDateTime(millis).format(timeFmt)
    fun formatMonth(month: YearMonth): String = month.format(monthFmt)

    /** "Today", "Yesterday", or a date — the transaction list's group header. */
    fun relativeDay(date: LocalDate): String = when (date) {
        today() -> "Today"
        today().minusDays(1) -> "Yesterday"
        else -> formatDayShort(date)
    }
}
