// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.waypoints

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xcsoar.mobile.R
import org.xcsoar.mobile.VacChart
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.settings.Explanation
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.io.File

/**
 * A field's approach chart, its pages one under the other, drawn by
 * Android's PDF renderer: no other app, and no network (the chart is
 * on the phone).  Two fingers zoom up to 4x and move the zoomed page.
 */
@Composable
fun VacScreen(icao: String, chart: VacChart?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val colors = XcsTheme.colors
    val widthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    var pages by remember(chart) { mutableStateOf<List<ImageBitmap>?>(null) }
    var failed by remember(chart) { mutableStateOf(false) }
    LaunchedEffect(chart) {
        val file = chart?.file ?: return@LaunchedEffect
        try {
            // sharp when zoomed in twice
            pages = withContext(Dispatchers.IO) { render(file, widthPx * 2) }
        } catch (e: Exception) {
            Log.w("XCSoar", "chart of $icao", e)
            failed = true
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier
               .fillMaxSize()
               .background(colors.sheet)
               .windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.padding(12.dp)) {
            ScreenHeader(stringResource(R.string.field_chart_open, icao), onBack)
        }
        val shown = pages
        when {
            failed || chart == null ->
                Box(Modifier.padding(12.dp)) {
                    Explanation(stringResource(R.string.field_chart_failed, icao))
                }
            shown == null ->
                Box(Modifier.padding(12.dp)) { Explanation(stringResource(R.string.status_reading)) }
            else -> Box(Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                    offset = if (scale == 1f) Offset.Zero else offset + pan
                                }
                            }) {
                Column(Modifier
                           .fillMaxWidth()
                           .verticalScroll(rememberScrollState())
                           .graphicsLayer {
                               scaleX = scale
                               scaleY = scale
                               translationX = offset.x
                               translationY = offset.y
                           },
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEachIndexed { i, page ->
                        Image(page, stringResource(R.string.field_chart_page, icao, i + 1),
                              contentScale = ContentScale.FillWidth,
                              modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private const val MAX_ZOOM = 4f

/** Every page of [file], [width] pixels wide, on white. */
private fun render(file: File, width: Int): List<ImageBitmap> =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { page ->
                    val height = width * page.height / page.width
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }
        }
    }
