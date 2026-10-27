package com.finora.app.domain.merchant

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

data class PlaceCandidate(
    val name: String,
    val address: String?,
    val point: GeoPoint,
    val kind: String?,
    val osmRef: String?,
)

data class RankedPlace(
    val place: PlaceCandidate,
    val nameScore: Double,
    val distanceMeters: Double?,
    val score: Double,
    val suggestedCategory: String?,
)

/**
 * Orders nearby-business candidates by how well the NAME matches and how CLOSE they are. The
 * result is a suggestion list for the user to choose from — proximity is context, not proof
 * that the payment happened there.
 */
object PlaceRanking {
    /** Distance at which the proximity score halves, roughly. */
    private const val DISTANCE_SCALE_M = 800.0

    fun rank(merchantName: String, here: GeoPoint?, candidates: List<PlaceCandidate>): List<RankedPlace> {
        val key = MerchantNormalizer.key(merchantName)
        return candidates.map { c ->
            val cKey = MerchantNormalizer.key(c.name)
            val nameScore = if (cKey.contains(key) || key.contains(cKey)) maxOf(0.9, Similarity.jaroWinkler(key, cKey)) else Similarity.jaroWinkler(key, cKey)
            val d = here?.let { haversineMeters(it, c.point) }
            val proximity = d?.let { exp(-it / DISTANCE_SCALE_M) }
            val score = if (proximity == null) nameScore else 0.65 * nameScore + 0.35 * proximity
            RankedPlace(c, nameScore, d, score, CategoryHints.forOsmKind(c.kind))
        }.filter { it.nameScore >= 0.6 }.sortedByDescending { it.score }
    }

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}

/** OpenStreetMap amenity/shop values -> a default category NAME (matched against local categories). */
object CategoryHints {
    fun forOsmKind(kind: String?): String? = when (kind?.lowercase()) {
        null -> null
        "restaurant", "fast_food", "cafe", "food_court", "bar", "pub", "ice_cream", "bakery" -> "Food & dining"
        "supermarket", "convenience", "greengrocer", "grocery", "butcher", "general" -> "Groceries"
        "fuel" -> "Fuel"
        "pharmacy", "chemist", "hospital", "clinic", "doctors", "dentist" -> "Health"
        "clothes", "shoes", "department_store", "mall", "electronics", "mobile_phone", "jewelry", "furniture", "hardware" -> "Shopping"
        "cinema", "theatre", "nightclub" -> "Entertainment"
        "hotel", "guest_house", "hostel" -> "Travel"
        "taxi", "bus_station", "parking" -> "Transport"
        "hairdresser", "beauty" -> "Personal care"
        else -> null
    }
}
