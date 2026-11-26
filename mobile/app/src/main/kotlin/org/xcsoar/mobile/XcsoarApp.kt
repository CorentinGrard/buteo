// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.net.Uri
import android.provider.OpenableColumns
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xcsoar.AppPermissionManager
import org.xcsoar.CoreBattery
import org.xcsoar.CoreGraphics
import org.xcsoar.mobile.core.IgcFile
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.GeoPosition
import org.xcsoar.mobile.core.REPOSITORY_URI
import org.xcsoar.mobile.core.RepositoryFile
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.core.WeGlideFlight
import org.xcsoar.mobile.ui.MessageException
import org.xcsoar.mobile.ui.flights.FlightLog
import org.xcsoar.mobile.ui.flight.CalloutSettings
import org.xcsoar.mobile.ui.flights.UploadStore
import org.xcsoar.mobile.ui.flight.TILE_ROWS
import org.xcsoar.mobile.ui.flight.VECTOR_CIRCLING_ZOOM
import org.xcsoar.mobile.ui.flight.VECTOR_CRUISE_ZOOM
import org.xcsoar.mobile.ui.text
import org.xcsoar.mobile.ui.theme.ThemeChoice
import java.io.File
import java.util.Locale

/**
 * Owns what lives as long as the process: the core (one per process,
 * XCSoar has process-wide state) and the permission manager.
 */
class XcsoarApp : Application() {
    internal val permissionManager by lazy { AppPermissionManager(this) }

    /** The XCSoarData directory; visible over USB for copying data files. */
    val xcsoarDataDir: File by lazy {
        // exists before the core starts (its data layout migration writes
        // a marker there first)
        File(getExternalFilesDir(null) ?: filesDir, "XCSoarData").apply { mkdirs() }
    }

    /**
     * The native core, or `null` if libxcsoar_core.so is not in the APK
     * for this device's ABI (the UI then falls back to a fake core).
     */
    val core: XcsoarCore? by lazy {
        try {
            CoreGraphics.initialise(this)
            NativeCore.nativeInit(this, permissionManager)
            CoreBattery.register(this)
            NativeXcsoarCore(xcsoarDataDir.path, profile?.takeIf { File(it).isFile })
        } catch (e: LinkageError) {
            /* UnsatisfiedLinkError the first time; NoClassDefFoundError
               for every later app in the same process (JVM tests), as
               NativeCore's initialiser failed */
            Log.w(TAG, "no native core, using the fake one", e)
            null
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The core every screen shares: the native one, else the fake one. */
    val anyCore: XcsoarCore by lazy { core ?: FakeXcsoarCore(appScope) }

    /** The data a flight needs: its check and its downloads (D31). */
    lateinit var flightData: FlightData
        private set

    override fun onCreate() {
        super.onCreate()
        WeatherUpdates(this, appScope)
        flightData = FlightData(this, appScope)
    }

    /* the language the core read its translations in (at start) */
    private var coreLanguage = Locale.getDefault().language

    /**
     * The pilot gave Buteo another language (Android's per-app language,
     * res/xml/locales_config.xml): the process goes on, so the core's
     * texts (tiles, status pages) must follow by themselves.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val language = newConfig.locales[0].language
        if (language == coreLanguage) return
        coreLanguage = language
        val core = core ?: return
        appScope.launch {
            try {
                core.reloadLanguage()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "core texts stay in the old language", e)
            }
        }
    }

    private val uiPreferences by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    /** The theme the pilot chose; kept on the phone, not in the profile. */
    var theme: ThemeChoice
        get() = ThemeChoice.entries.firstOrNull {
            it.name == uiPreferences.getString("theme", null)
        } ?: ThemeChoice.SYSTEM
        set(value) = uiPreferences.edit().putString("theme", value.name).apply()

    /** The vector map's zooms, cruising and circling; on the phone, like the theme. */
    var vectorMapZooms: Pair<Double, Double>
        get() = uiPreferences.getFloat("vectorCruiseZoom", VECTOR_CRUISE_ZOOM.toFloat()).toDouble() to
            uiPreferences.getFloat("vectorCirclingZoom", VECTOR_CIRCLING_ZOOM.toFloat()).toDouble()
        set(value) = uiPreferences.edit()
            .putFloat("vectorCruiseZoom", value.first.toFloat())
            .putFloat("vectorCirclingZoom", value.second.toFloat())
            .apply()

    /**
     * Where the glider was last (latitude, longitude): the vector map
     * starts there before a fix.  On the phone, like the theme.
     */
    var vectorMapPlace: Pair<Double, Double>?
        get() = if (uiPreferences.contains("vectorLatitude"))
            uiPreferences.getFloat("vectorLatitude", 0f).toDouble() to
                uiPreferences.getFloat("vectorLongitude", 0f).toDouble()
        else null
        set(value) {
            if (value == null) return
            uiPreferences.edit()
                .putFloat("vectorLatitude", value.first.toFloat())
                .putFloat("vectorLongitude", value.second.toFloat())
                .apply()
        }

    /** Rows of tiles on the flight screen ([TILE_ROWS]); on the phone, like the theme. */
    var tileRows: Int
        get() = uiPreferences.getInt("tileRows", TILE_ROWS.first).coerceIn(TILE_ROWS)
        set(value) = uiPreferences.edit().putInt("tileRows", value).apply()

    /**
     * The profile file the core starts with; null for XCSoar's
     * "default.prf".  Kept on the phone: the profile cannot name itself.
     */
    var profile: String?
        get() = uiPreferences.getString("profile", null)
        set(value) = uiPreferences.edit().putString("profile", value).commit().let {}

    /**
     * Copy a profile the user picked (e.g. from XCSoar on another
     * device) into XCSoarData/profiles, keeping its name.
     *
     * @return the path of the copy
     * @throws IllegalStateException if a profile has that name already
     */
    suspend fun importProfile(uri: Uri): String = withContext(Dispatchers.IO) {
        var name = displayName(uri)
        if (!name.endsWith(".prf", ignoreCase = true)) name += ".prf"
        val target = File(File(xcsoarDataDir, "profiles").apply { mkdirs() }, name)
        if (target.exists())
            throw MessageException(text(R.string.profiles_exists, name.removeSuffix(".prf")),
                                   "A profile named ${name.removeSuffix(".prf")} exists already")
        val input = checkNotNull(contentResolver.openInputStream(uri)) { "cannot open $uri" }
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        target.path
    }

    /** The flights uploaded to WeGlide, by IGC file name (WeGlide's answer). */
    val weGlideUploads = object : UploadStore {
        private val preferences by lazy { getSharedPreferences("weglide_uploads", MODE_PRIVATE) }

        override suspend fun load() = withContext(Dispatchers.IO) {
            preferences.all.mapNotNull { (name, value) ->
                (value as? String)?.let {
                    try {
                        name to WeGlideFlight.parse(it)
                    } catch (_: Exception) {
                        null
                    }
                }
            }.toMap()
        }

        override suspend fun save(name: String, flight: WeGlideFlight) {
            withContext(Dispatchers.IO) {
                preferences.edit().putString(name, flight.encode()).commit()
            }
        }

        override suspend fun remove(name: String) {
            withContext(Dispatchers.IO) { preferences.edit().remove(name).commit() }
        }
    }

    /** The pilot chose the plane and crew since the app started. */
    var crewChosen = false

    /** Spoken callouts, for the whole flight (the screen may be off). */
    val voice by lazy { Voice(this) }

    /** Which callouts the pilot hears; kept on the phone, not in the profile. */
    var calloutSettings: CalloutSettings
        get() = CalloutSettings(
            airspace = uiPreferences.getBoolean("callout_airspace", true),
            finalGlide = uiPreferences.getBoolean("callout_final_glide", true),
            taskPoints = uiPreferences.getBoolean("callout_task_points", true),
            climbs = uiPreferences.getBoolean("callout_climbs", true))
        set(value) = uiPreferences.edit()
            .putBoolean("callout_airspace", value.airspace)
            .putBoolean("callout_final_glide", value.finalGlide)
            .putBoolean("callout_task_points", value.taskPoints)
            .putBoolean("callout_climbs", value.climbs)
            .apply()

    /**
     * The first start's setup (units, pilot and glider, maps) is done
     * or was put off: it is not offered again.  An install that has a
     * profile, planes or data files already needs none.
     */
    var setupDone: Boolean
        get() = uiPreferences.getBoolean("setup_done", false) ||
            listOf("profiles", "planes", "maps", "airspace").any {
                File(xcsoarDataDir, it).list()?.isNotEmpty() == true
            }
        set(value) = uiPreferences.edit().putBoolean("setup_done", value).apply()

    /** The pilot has set up a plane (XCSoarData/planes, upstream's layout). */
    val hasPlanes: Boolean
        get() = File(xcsoarDataDir, "planes").list()
            ?.any { it.endsWith(".xcp", ignoreCase = true) } == true

    /**
     * Where the phone is, by country code, best first: its mobile
     * network, its SIM, its language's country.  No permission needed.
     */
    fun regionHints(): List<String> {
        val phone = getSystemService(TelephonyManager::class.java)
        return listOfNotNull(phone?.networkCountryIso, phone?.simCountryIso,
                             Locale.getDefault().country)
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * The phone's last known position from any provider (no new fix is
     * asked for); null without the location permission or a fix.
     */
    fun lastKnownPosition(): GeoPosition? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
                             Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return null
        val manager = getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.let { GeoPosition(it.latitude, it.longitude) }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Copy a file the user picked into XCSoarData, into the folder
     * XCSoar uses for its kind (GetFileTypeDefaultDir() in
     * src/Repository/FileType.cpp), keeping its name.
     *
     * @return the path of the copy
     */
    suspend fun importDataFile(uri: Uri, kind: DataFile): String = withContext(Dispatchers.IO) {
        val folder = when (kind) {
            DataFile.MAP -> "maps"
            DataFile.AIRSPACE -> "airspace"
            DataFile.WAYPOINTS -> "waypoints"
            DataFile.RASP -> "weather/rasp"
        }
        val target = File(File(xcsoarDataDir, folder).apply { mkdirs() }, displayName(uri))
        val input = checkNotNull(contentResolver.openInputStream(uri)) { "cannot open $uri" }
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        target.path
    }

    val downloader by lazy { Downloader(cacheDir) }

    /**
     * Download a repository file into the XCSoarData folder XCSoar uses
     * for its kind.
     *
     * @return the path of the file
     */
    suspend fun downloadDataFile(file: RepositoryFile, progress: (Float) -> Unit): String {
        val folder = file.folder ?: throw MessageException(
            text(R.string.download_unknown_kind, file.name),
            "${file.name}: unknown kind of file")
        val target = File(File(xcsoarDataDir, folder), File(file.name).name)
        downloader.download(file.uri, target, file.sha256, progress)
        return target.path
    }

    /**
     * Download [name] from the repository again and use it as [kind]:
     * RASP forecasts change every day under the same name.
     */
    suspend fun updateDataFile(kind: DataFile, name: String) {
        val files = anyCore.repositoryFiles(downloader.index(REPOSITORY_URI).path)
            ?: throw MessageException(text(R.string.download_needs_core),
                                      "downloads need the native core")
        val file = files.firstOrNull { it.name == name }
            ?: throw MessageException(text(R.string.download_gone, name),
                                      "$name is no longer in XCSoar's repository")
        anyCore.setDataFile(kind, downloadDataFile(file) {})
    }

    /** The file name of a picked document, safe to use as a file name. */
    private fun displayName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                                         null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "imported"
        val base = name.substringAfterLast('/').replace('\\', '_')
        return if (base.isBlank() || base == "." || base == "..") "imported" else base
    }

    /** The IGC files XCSoar's logger wrote, and when each flight began. */
    suspend fun flightLogs(): List<FlightLog> = withContext(Dispatchers.IO) {
        val logs = File(xcsoarDataDir, "logs")
        // the demo flight the app copied there until 2026-11-21
        File(logs, OLD_DEMO_FLIGHT).delete()
        logs.listFiles { f ->
            f.isFile && f.name.endsWith(".igc", ignoreCase = true)
        }.orEmpty().map {
            FlightLog(it.path, it.name, it.lastModified(), it.length(), IgcFile.start(it))
        }
    }

    private companion object {
        const val TAG = "XcsoarApp"
        const val OLD_DEMO_FLIGHT = "demo.igc"
    }
}
