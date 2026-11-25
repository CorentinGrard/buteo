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
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

private const val TAG = "VectorMap"

/**
 * A region's map on the phone (prototype, 2026-11-25): OpenStreetMap
 * vector tiles in one PMTiles file in XCSoarData/maps, built with
 * Planetiler from a Geofabrik extract.  With it, the vector map needs
 * no network: every tile of the region is in the file, at every zoom.
 *
 * @param date the day of the OpenStreetMap data, e.g. "2026-11-24"
 */
class RegionMap(
    val file: File,
    val name: String,
    val west: Double, val south: Double, val east: Double, val north: Double,
    val date: String?,
) {
    fun covers(latitude: Double, longitude: Double) =
        latitude in south..north && longitude in west..east

    companion object {
        /** The first region file in [dir], or null. */
        fun find(dir: File?): RegionMap? =
            dir?.listFiles { f -> f.isFile && f.extension == "pmtiles" }
                ?.sortedBy { it.name }
                ?.firstNotNullOfOrNull { read(it) }

        /** The PMTiles (version 3) header's bounds and the metadata's date. */
        fun read(file: File): RegionMap? = try {
            RandomAccessFile(file, "r").use { f ->
                val header = ByteArray(127)
                f.readFully(header)
                if (String(header, 0, 7, Charsets.US_ASCII) != "PMTiles" || header[7] != 3.toByte())
                    return null
                val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val bound = { offset: Int -> b.getInt(offset) / 1e7 }
                RegionMap(file, file.nameWithoutExtension,
                          west = bound(102), south = bound(106),
                          east = bound(110), north = bound(114),
                          date = metadataDate(f, b))
            }
        } catch (e: IOException) {
            Log.w(TAG, "region map $file", e)
            null
        }

        /** Planetiler writes the OpenStreetMap data's time in the metadata. */
        private fun metadataDate(f: RandomAccessFile, header: ByteBuffer): String? {
            val offset = header.getLong(24)
            val length = header.getLong(32)
            if (length <= 0 || length > 1 shl 20) return null
            val bytes = ByteArray(length.toInt())
            f.seek(offset)
            f.readFully(bytes)
            val json = if (header.get(97) == 2.toByte())
                GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            else bytes
            return JSONObject(String(json))
                .optString("planetiler:osm:osmosisreplicationtime")
                .takeIf { it.length >= 10 }?.substring(0, 10)
        }
    }
}

/**
 * The vector map's style: the region's file when the phone has one,
 * else OpenFreeMap's tiles over the network (fetched and cached as the
 * map needs them, so not for flying without a network).  The fonts are
 * in the app and the relief comes from the core's terrain
 * ([MapNetwork]).
 */
fun vectorMapStyle(json: String, region: RegionMap?): String {
    val style = JSONObject(json)
    if (region != null)
        style.getJSONObject("sources").put("osm", JSONObject()
            .put("type", "vector")
            .put("url", "pmtiles://file://${region.file.absolutePath}"))
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
                    inFlight -> throw IOException("no network for the map in flight")
                    else -> chain.proceed(request)
                }
            })
            .build())
    }

    /** /{z}/{x}/{y}.png as Terrarium heights: (R * 256 + G) - 32768 m. */
    private fun terrainTile(request: Request): Response {
        val path = request.url.pathSegments
        val zoom = path.getOrNull(0)?.toIntOrNull()
        val x = path.getOrNull(1)?.toIntOrNull()
        val y = path.getOrNull(2)?.removeSuffix(".png")?.toIntOrNull()
        val heights = if (zoom != null && x != null && y != null)
            runBlocking { terrain?.invoke(zoom, x, y, TERRAIN_SIZE) }
        else null
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
