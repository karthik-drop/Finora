package com.finora.app.platform.places

import com.finora.app.BuildConfig
import com.finora.app.data.db.PlaceCacheDao
import com.finora.app.data.db.PlaceLookupCacheEntity
import com.finora.app.domain.merchant.GeoPoint
import com.finora.app.domain.merchant.MerchantNormalizer
import com.finora.app.domain.merchant.PlaceCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** The one network-facing port. Swappable for a self-hosted or commercial provider. */
interface PlaceSearch {
    sealed interface Outcome {
        data class Found(val candidates: List<PlaceCandidate>, val fromCache: Boolean) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun search(name: String, near: GeoPoint?): Outcome
}

/**
 * OpenStreetMap Nominatim search, used under its usage policy
 * (https://operations.osmfoundation.org/policies/nominatim/):
 *  - only on an explicit user action (never periodic, never autocomplete, never in bulk);
 *  - at most one request per second, enforced here;
 *  - an identifying User-Agent, never a library default;
 *  - results cached locally by (normalised name, ~1 km cell), so a repeated lookup is free;
 *  - "© OpenStreetMap contributors" shown wherever results appear;
 *  - the base URL is a setting, so it can be pointed at a self-hosted instance or switched off
 *    without an app update.
 * Coordinates are sent only for that one lookup; no financial data is ever included.
 */
class NominatimPlaceSearch(
    private val baseUrl: () -> String,
    private val cache: PlaceCacheDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaceSearch {
    private val gate = Mutex()
    private var lastRequestAt = 0L

    override suspend fun search(name: String, near: GeoPoint?): PlaceSearch.Outcome = withContext(Dispatchers.IO) {
        val key = MerchantNormalizer.key(name)
        if (key.isBlank()) return@withContext PlaceSearch.Outcome.Failed("No merchant name to search for.")
        val cell = near?.let { String.format(Locale.US, "%.2f,%.2f", it.lat, it.lon) } ?: "any"
        val cacheKey = "$key|$cell"
        cache.get(cacheKey)?.takeIf { clock() - it.fetchedAt < CACHE_TTL_MILLIS }?.let {
            return@withContext PlaceSearch.Outcome.Found(parse(it.resultJson), fromCache = true)
        }
        try {
            val json = gate.withLock {
                val wait = 1_100 - (clock() - lastRequestAt)
                if (wait > 0) Thread.sleep(wait)
                lastRequestAt = clock()
                fetch(buildUrl(key, near))
            }
            cache.put(PlaceLookupCacheEntity(cacheKey, json, clock()))
            PlaceSearch.Outcome.Found(parse(json), fromCache = false)
        } catch (e: IOException) {
            PlaceSearch.Outcome.Failed("Could not reach the map service. Choose the merchant manually.")
        } catch (e: org.json.JSONException) {
            PlaceSearch.Outcome.Failed("The map service returned something unexpected.")
        }
    }

    private fun buildUrl(query: String, near: GeoPoint?): URL {
        val q = URLEncoder.encode(query, "UTF-8")
        val sb = StringBuilder(baseUrl().trimEnd('/')).append("/search?format=jsonv2&limit=10&addressdetails=0&q=").append(q)
        if (near != null) {
            // A ~5 km box around the user, bounded, so results are local.
            val d = 0.045
            sb.append(String.format(Locale.US, "&viewbox=%.5f,%.5f,%.5f,%.5f&bounded=1", near.lon - d, near.lat + d, near.lon + d, near.lat - d))
        } else sb.append("&countrycodes=in")
        return URL(sb.toString())
    }

    private fun fetch(url: URL): String {
        require(url.protocol == "https") { "Place search must use HTTPS" }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "en-IN,en")
            val code = conn.responseCode
            if (code == 429 || code == 403) throw IOException("rate limited or blocked ($code)")
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val CACHE_TTL_MILLIS = 30L * 24 * 3600 * 1000
        val USER_AGENT = "Finora/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}; personal finance app; on-device merchant lookup)"

        fun parse(json: String): List<PlaceCandidate> {
            val arr = JSONArray(json)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                val display = o.optString("display_name")
                val name = o.optString("name").ifBlank { display.substringBefore(',') }
                if (name.isBlank()) return@mapNotNull null
                PlaceCandidate(
                    name = name,
                    address = display.substringAfter(", ", "").takeIf { it.isNotBlank() },
                    point = GeoPoint(lat, lon),
                    kind = o.optString("type").ifBlank { null },
                    osmRef = o.optString("osm_type").takeIf { it.isNotBlank() }?.let { "$it/${o.optLong("osm_id")}" },
                )
            }
        }
    }
}
