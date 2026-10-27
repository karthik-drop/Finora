package com.finora.app.data.repo

import com.finora.app.data.db.SettingDao
import com.finora.app.data.db.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Typed view over the key/value settings table. Defaults live here, in one place. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val currencySymbol: String = "₹",
    val currencyCode: String = "INR",
    val smsCaptureEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val preciseLocation: Boolean = false,
    val placeLookupEnabled: Boolean = false,
    val mapPreviewEnabled: Boolean = false,
    val placeSearchBaseUrl: String = DEFAULT_PLACE_SEARCH_URL,
    val ownUpiIds: Set<String> = emptySet(),
    val ownNames: Set<String> = emptySet(),
    val staleValuationDays: Int = 14,
    val reviewUnknownCredits: Boolean = true,
    val onboardingDone: Boolean = false,
) {
    companion object {
        const val DEFAULT_PLACE_SEARCH_URL = "https://nominatim.openstreetmap.org"
    }
}

class SettingsRepository(private val dao: SettingDao) {
    val settings: Flow<AppSettings> = dao.observeAll().map { rows -> fromMap(rows.associate { it.key to it.value }) }

    suspend fun current(): AppSettings = fromMap(Keys.all.mapNotNull { k -> dao.get(k)?.let { k to it } }.toMap())

    suspend fun set(key: String, value: String) = dao.put(SettingEntity(key, value))
    suspend fun set(key: String, value: Boolean) = set(key, value.toString())
    suspend fun set(key: String, value: Int) = set(key, value.toString())
    suspend fun setSet(key: String, value: Set<String>) = set(key, value.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))

    object Keys {
        const val THEME = "theme"
        const val CURRENCY_SYMBOL = "currency_symbol"
        const val CURRENCY_CODE = "currency_code"
        const val SMS = "sms_capture"
        const val LOCATION = "location"
        const val PRECISE = "precise_location"
        const val LOOKUP = "place_lookup"
        const val MAP = "map_preview"
        const val PLACE_URL = "place_search_url"
        const val OWN_UPI = "own_upi_ids"
        const val OWN_NAMES = "own_names"
        const val STALE_DAYS = "stale_days"
        const val REVIEW_CREDITS = "review_unknown_credits"
        const val ONBOARDED = "onboarding_done"
        val all = listOf(THEME, CURRENCY_SYMBOL, CURRENCY_CODE, SMS, LOCATION, PRECISE, LOOKUP, MAP, PLACE_URL, OWN_UPI, OWN_NAMES, STALE_DAYS, REVIEW_CREDITS, ONBOARDED)
    }

    private fun fromMap(m: Map<String, String>): AppSettings {
        val d = AppSettings()
        fun bool(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
        fun set(k: String) = m[k]?.split("\n")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        return AppSettings(
            themeMode = m[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            currencySymbol = m[Keys.CURRENCY_SYMBOL] ?: d.currencySymbol,
            currencyCode = m[Keys.CURRENCY_CODE] ?: d.currencyCode,
            smsCaptureEnabled = bool(Keys.SMS, d.smsCaptureEnabled),
            locationEnabled = bool(Keys.LOCATION, d.locationEnabled),
            preciseLocation = bool(Keys.PRECISE, d.preciseLocation),
            placeLookupEnabled = bool(Keys.LOOKUP, d.placeLookupEnabled),
            mapPreviewEnabled = bool(Keys.MAP, d.mapPreviewEnabled),
            placeSearchBaseUrl = m[Keys.PLACE_URL]?.takeIf { it.startsWith("https://") } ?: d.placeSearchBaseUrl,
            ownUpiIds = set(Keys.OWN_UPI),
            ownNames = set(Keys.OWN_NAMES),
            staleValuationDays = m[Keys.STALE_DAYS]?.toIntOrNull()?.coerceIn(1, 365) ?: d.staleValuationDays,
            reviewUnknownCredits = bool(Keys.REVIEW_CREDITS, d.reviewUnknownCredits),
            onboardingDone = bool(Keys.ONBOARDED, d.onboardingDone),
        )
    }
}
