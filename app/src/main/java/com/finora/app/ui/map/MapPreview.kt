package com.finora.app.ui.map

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker

/**
 * A small, static-feeling map of one candidate place. Only tiles for this view are fetched
 * (interactive viewing only, no prefetch or offline download, per the OSM tile policy), with
 * the required "© OpenStreetMap contributors" drawn on the map itself.
 */
@Composable
fun MapPreview(lat: Double, lon: Double, title: String) {
    val context = LocalContext.current
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            overlays.add(CopyrightOverlay(context))
            controller.setZoom(17.0)
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(
        factory = { map },
        modifier = Modifier.fillMaxWidth().height(170.dp).clip(MaterialTheme.shapes.medium),
        update = { m ->
            val p = GeoPoint(lat, lon)
            m.controller.setCenter(p)
            m.overlays.removeAll { it is Marker }
            m.overlays.add(Marker(m).apply { position = p; this.title = title; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
            m.invalidate()
        },
    )
}
