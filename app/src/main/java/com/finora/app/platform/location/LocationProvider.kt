package com.finora.app.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.finora.app.domain.merchant.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot, foreground location using the platform LocationManager (no Google Play services).
 * Called only when the user presses "Find nearby" for an unfamiliar merchant; there is no
 * tracking, no background request and no stored history — the point is used for that one
 * lookup and then discarded.
 */
class LocationProvider(private val context: Context) {

    sealed interface Result {
        data class Found(val point: GeoPoint, val accuracyMeters: Float?) : Result
        data object NoPermission : Result
        data object Disabled : Result
        data object Unavailable : Result
    }

    fun hasPermission(): Boolean = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMillis: Long = 10_000): Result {
        if (!hasPermission()) return Result.NoPermission
        val lm = context.getSystemService(LocationManager::class.java) ?: return Result.Unavailable
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return Result.Disabled

        val fresh = withTimeoutOrNull(timeoutMillis) { requestOnce(lm, providers.first()) }
        val loc = fresh ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            // A stale last-known fix is worse than none for "what is near me now".
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
            .maxByOrNull { it.time }
        return loc?.let { Result.Found(GeoPoint(it.latitude, it.longitude), if (it.hasAccuracy()) it.accuracy else null) } ?: Result.Unavailable
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc -> if (cont.isActive) cont.resume(loc) }
        } else {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) { if (cont.isActive) cont.resume(location) }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) { if (cont.isActive) cont.resume(null) }
            }
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }
    }
}
