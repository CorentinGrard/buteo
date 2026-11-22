// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.analysis

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.xcsoar.mobile.core.LatLon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * North up on a flat projection fitting [all] into [width] x [height]
 * pixels less [inset] on each side, centred; [north] .. [east] is the
 * whole area's box, for the terrain behind it.
 */
internal class FlatMap(all: List<LatLon>, width: Float, height: Float, inset: Float = 0f) {
    private val minLat = all.minOf { it.latitude }
    private val maxLon = all.maxOf { it.longitude }
    private val minLon = all.minOf { it.longitude }
    private val maxLat = all.maxOf { it.latitude }
    private val cosLat = cos((minLat + maxLat) / 2 * PI / 180)
    private val spanX = max((maxLon - minLon) * cosLat, 1e-6)
    private val spanY = max(maxLat - minLat, 1e-6)
    /** pixels per degree of latitude */
    private val scale = min((width - 2 * inset) / spanX, (height - 2 * inset) / spanY)
    private val offsetX = (width - spanX * scale) / 2
    private val offsetY = (height - spanY * scale) / 2

    fun at(p: LatLon) =
        Offset((offsetX + (p.longitude - minLon) * cosLat * scale).toFloat(),
               (offsetY + (maxLat - p.latitude) * scale).toFloat())

    val north = maxLat + offsetY / scale
    val south = north - height / scale
    val west = minLon - offsetX / (cosLat * scale)
    val east = west + width / (cosLat * scale)
}

/**
 * Loads the map's terrain for a box (degrees) at a size (pixels); null
 * when there is none.  Provided where the native core draws the map
 * (MainActivity); none in previews and tests.
 */
typealias TerrainLoader = suspend (north: Double, west: Double, south: Double, east: Double,
                                   width: Int, height: Int) -> ImageBitmap?

val LocalTerrain = staticCompositionLocalOf<TerrainLoader?> { null }

/**
 * The map's terrain under a chart of [map], [width] x [height] pixels,
 * faded so the flight and the task stay in front, as upstream's
 * analysis maps (FlightStatisticsRenderer).
 */
@Composable
internal fun TerrainBehind(map: FlatMap?, width: Int, height: Int) {
    val terrain = LocalTerrain.current ?: return
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(map?.north, map?.west, map?.south, map?.east, width, height) {
        // half the pixels: terrain has no fine detail, and it is faded
        image = if (map == null || width < 2 || height < 2) null
                else terrain(map.north, map.west, map.south, map.east, width / 2, height / 2)
    }
    image?.let {
        Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.FillBounds, alpha = TERRAIN_ALPHA)
    }
}

/** Upstream washes the map in the chart's background at 3/4. */
private const val TERRAIN_ALPHA = 0.4f
