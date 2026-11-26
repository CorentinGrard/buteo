// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.xcsoar.mobile.core.OfflineMap
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val TAG = "VectorMap"

/**
 * The vector map's style: its tiles come only from the region files on
 * the phone ([OfflineMap], answered by [MapNetwork]): Protomaps'
 * basemap (schema 4) cut by region (D31).  Without any, the map has the
 * relief and the core's layers on a blank ground.  The fonts are in the
 * app and the relief comes from the core's terrain.
 */
fun vectorMapStyle(json: String, offline: OfflineMap?): String {
    val style = JSONObject(json)
    if (offline != null) {
        val b = offline.bounds
        style.getJSONObject("sources").getJSONObject("osm")
            .put("minzoom", offline.minZoom)
            .put("maxzoom", offline.maxZoom)
            .put("bounds", JSONArray().put(b[0]).put(b[1]).put(b[2]).put(b[3]))
    }
    return style.toString()
}

/**
 * What the vector map takes from the network.  The relief's tiles are
 * made from the core's terrain (the glide computer's, from the map
 * file) and never leave the phone; in flight nothing else does either,
 * so the map shows in the air what it showed on the ground with the
 * network off.
 */
object MapNetwork {
    /** The relief's tiles: answered here (".invalid" never resolves). */
    const val TERRAIN_HOST = "terrain.buteo.invalid"
    private const val TERRAIN_SIZE = 256
    /** The region files' tiles, answered here too. */
    const val TILES_HOST = "tiles.buteo.invalid"

    /** The region files on the phone, null without. */
    @Volatile
    var offline: OfflineMap? = null

    /** In flight: only what is on the phone. */
    @Volatile
    var inFlight = false

    /** The core's [org.xcsoar.mobile.core.XcsoarCore.terrainTile]. */
    @Volatile
    var terrain: (suspend (zoom: Int, x: Int, y: Int, size: Int) -> ShortArray?)? = null

    private var installed = false

    /** After MapLibre.getInstance(). */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        // MapLibre stops asking without a network, the relief's tiles too
        MapLibre.setConnected(true)
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = 20 }  // MapLibre's own
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                when {
                    request.url.host == TERRAIN_HOST -> terrainTile(request)
                    request.url.host == TILES_HOST -> regionTile(request)
                    inFlight -> throw IOException("no network for the map in flight")
                    else -> chain.proceed(request)
                }
            })
            .build())
    }

    /** /{z}/{x}/{y}.pbf from the first region file that has it; none: no content. */
    private fun regionTile(request: Request): Response {
        val path = request.url.pathSegments
        val zoom = path.getOrNull(0)?.toIntOrNull()
        val x = path.getOrNull(1)?.toIntOrNull()
        val y = path.getOrNull(2)?.removeSuffix(".pbf")?.toIntOrNull()
        val tile = try {
            if (zoom != null && x != null && y != null) offline?.tile(zoom, x, y) else null
        } catch (e: Exception) {
            // a file replaced or closed under the map: drawn without the tile
            Log.w(TAG, "tile $zoom/$x/$y", e)
            null
        }
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (tile == null)
            return response.code(204).message("No tile")
                .body(ByteArray(0).toResponseBody(null)).build()
        return response.code(200).message("OK")
            .body(tile.toResponseBody("application/x-protobuf".toMediaType()))
            .build()
    }

    /** /{z}/{x}/{y}.png as Terrarium heights: (R * 256 + G) - 32768 m. */
    private fun terrainTile(request: Request): Response {
        val path = request.url.pathSegments
        val zoom = path.getOrNull(0)?.toIntOrNull()
        val x = path.getOrNull(1)?.toIntOrNull()
        val y = path.getOrNull(2)?.removeSuffix(".png")?.toIntOrNull()
        val heights = try {
            if (zoom != null && x != null && y != null)
                runBlocking { terrain?.invoke(zoom, x, y, TERRAIN_SIZE) }
            else null
        } catch (e: Exception) {
            // thrown on OkHttp's thread, anything but an IOException
            // would end the app: the map goes without this tile
            Log.w(TAG, "terrain $zoom/$x/$y", e)
            null
        }
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (heights == null || heights.size != TERRAIN_SIZE * TERRAIN_SIZE)
            return response.code(404).message("No terrain")
                .body(ByteArray(0).toResponseBody(null)).build()

        val pixels = IntArray(heights.size) { i ->
            val v = heights[i] + 32768
            (0xFF shl 24) or ((v shr 8) shl 16) or ((v and 0xFF) shl 8)
        }
        val bitmap = Bitmap.createBitmap(pixels, TERRAIN_SIZE, TERRAIN_SIZE,
                                         Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
        bitmap.recycle()
        // made again after a while: the core may have loaded finer terrain
        return response.code(200).message("OK")
            .header("Cache-Control", "max-age=300")
            .body(png.toByteArray().toResponseBody("image/png".toMediaType()))
            .build()
    }
}
