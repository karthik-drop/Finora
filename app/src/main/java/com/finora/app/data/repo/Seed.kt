package com.finora.app.data.repo

import com.finora.app.data.db.CategoryEntity
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.data.db.ReminderEntity
import com.finora.app.domain.model.CategoryKind

/**
 * Defaults created on first launch. Idempotent (IGNORE on unique keys), so it runs on every
 * start and repairs anything missing without touching what the user changed.
 */
object Seed {
    const val REVIEW_AFTERNOON = "review_afternoon"
    const val REVIEW_EVENING = "review_evening"
    const val PORTFOLIO = "portfolio"

    val defaultCategories: List<CategoryEntity> = listOf(
        cat("Food & dining", "restaurant", "#E07A5F"),
        cat("Groceries", "grocery", "#81B29A"),
        cat("Shopping", "shopping", "#C38D9E"),
        cat("Transport", "transport", "#5B8DEF"),
        cat("Fuel", "fuel", "#F2A541"),
        cat("Bills & utilities", "bolt", "#3D5A80"),
        cat("Rent", "home", "#6D597A"),
        cat("Health", "health", "#E63946"),
        cat("Entertainment", "movie", "#9B5DE5"),
        cat("Travel", "flight", "#00A6A6"),
        cat("Education", "school", "#457B9D"),
        cat("Personal care", "spa", "#F4A6A6"),
        cat("Subscriptions", "subscriptions", "#7B6CF6"),
        cat("Gifts & donations", "gift", "#D4A373"),
        cat("Fees & charges", "receipt", "#8D99AE"),
        cat("Taxes", "gavel", "#6C757D"),
        cat("Other", "category", "#94A3B8"),
        cat("Salary", "work", "#2A9D8F", CategoryKind.INCOME),
        cat("Business income", "storefront", "#52B788", CategoryKind.INCOME),
        cat("Interest", "savings", "#40916C", CategoryKind.INCOME),
        cat("Gifts received", "gift", "#B5838D", CategoryKind.INCOME),
        cat("Other income", "payments", "#74C69D", CategoryKind.INCOME),
    )

    private fun cat(name: String, icon: String, color: String, kind: CategoryKind = CategoryKind.EXPENSE) =
        CategoryEntity(name = name, kind = kind, iconKey = icon, colorHex = color, isDefault = true)

    suspend fun run(db: FinoraDatabase) {
        db.categories().insertAll(defaultCategories)
        db.reminders().insertIfAbsent(ReminderEntity(key = REVIEW_AFTERNOON, title = "Afternoon review", timeOfDayMinutes = 14 * 60 + 30, intervalDays = 1, enabled = true))
        db.reminders().insertIfAbsent(ReminderEntity(key = REVIEW_EVENING, title = "Evening review", timeOfDayMinutes = 21 * 60 + 30, intervalDays = 1, enabled = true))
        db.reminders().insertIfAbsent(
            ReminderEntity(key = PORTFOLIO, title = "Portfolio update", timeOfDayMinutes = 10 * 60, intervalDays = 14, enabled = true, anchorDate = java.time.LocalDate.now().toEpochDay())
        )
    }
}
