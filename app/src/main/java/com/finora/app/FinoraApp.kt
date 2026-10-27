package com.finora.app

import android.app.Application
import androidx.work.Configuration
import com.finora.app.platform.reminders.ReminderScheduler
import org.osmdroid.config.Configuration as OsmConfiguration

class FinoraApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderScheduler.createChannels(this)
        // Tile requests must identify the app (OSM tile usage policy). Tiles cache in app storage.
        OsmConfiguration.getInstance().apply {
            userAgentValue = com.finora.app.platform.places.NominatimPlaceSearch.USER_AGENT
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }
        container.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
}
