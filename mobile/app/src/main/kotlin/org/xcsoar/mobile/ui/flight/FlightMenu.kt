// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.MapCoverage
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.ui.Chevron
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.theme.ThemeChoice
import org.xcsoar.mobile.ui.theme.XcsTheme

/** How long the menu takes to slide in or out. */
private const val MENU_MILLIS = 250

/**
 * An entry of the flight menu; disabled entries stay visible, dimmed.
 * Most open a page over the menu, so Back comes back to it; those with
 * [closesMenu] act on the flight screen itself.
 *
 * @param icon what it is at a glance (`res/drawable/ic_menu_*`); 0 for none
 * @param caution [detail] is something to see to before the flight
 */
data class MenuAction(
    val label: String,
    val detail: String,
    val enabled: Boolean = true,
    val closesMenu: Boolean = false,
    @DrawableRes val icon: Int = 0,
    val caution: Boolean = false,
    val onClick: () -> Unit,
)

/** A titled list of the flight menu. */
class MenuSection(val title: String, val actions: List<MenuAction>)

/**
 * What the flight menu offers.  It is the app's only menu: the settings
 * are a section of it, not a page of their own.
 *
 * @param inFlight large buttons for what the pilot does in the air
 * @param sections lists for the ground and the settings
 * @param tileLayout the pilot's choice of tiles: null follows the
 * flight mode, else circling (true) or cruise (false) until the next
 * mode change
 * @param tileRows how many rows of tiles the flight screen shows
 * @param orientation which way the map is up; null hides the choice
 * (no map, or the core has not answered yet)
 * @param varioSound the vario sound is on; null hides the switch (no
 * vario sound on this device)
 * @param theme white, dark or the phone's
 */
class FlightMenu(
    val inFlight: List<MenuAction> = emptyList(),
    val sections: List<MenuSection> = emptyList(),
    val tileLayout: Boolean? = null,
    val onTileLayout: (circling: Boolean?) -> Unit = {},
    val tileRows: Int = TILE_ROWS.first,
    val onTileRows: (Int) -> Unit = {},
    val orientation: MapOrientation? = null,
    val onOrientation: (MapOrientation) -> Unit = {},
    val varioSound: Boolean? = null,
    val onVarioSound: (Boolean) -> Unit = {},
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val onTheme: (ThemeChoice) -> Unit = {},
    /** what the phone has of the map for this flight; null hides it */
    val mapCoverage: MapCoverage? = null,
)

/** The orientations the menu offers, in its order. */
private val ORIENTATIONS = listOf(MapOrientation.NORTH_UP to R.string.north,
                                  MapOrientation.TRACK_UP to R.string.track,
                                  MapOrientation.TARGET_UP to R.string.target)

/**
 * The menu as a sheet over the flight screen (D34).  The in-flight
 * buttons stay at its bottom, under the thumb that opened it, whatever
 * the lists above them: the screen's own settings (map up, tiles,
 * theme, vario sound), then what is done before and after the flight
 * and the settings, which scroll.  Every entry has an icon.
 * It slides up over the dimmed map when [visible]; a tap on the map
 * closes it.
 */
@Composable
fun FlightMenuSheet(visible: Boolean, menu: FlightMenu, onClose: () -> Unit,
                    modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(MENU_MILLIS)),
                           exit = fadeOut(tween(MENU_MILLIS))) {
            Box(Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() },
                           indication = null, onClickLabel = stringResource(R.string.menu_close),
                           onClick = onClose))
        }

        val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        val sheetMax = maxHeight - 32.dp
        // landscape or a tablet: the in-flight buttons in one row
        val wide = maxWidth >= 520.dp
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(MENU_MILLIS, easing = LinearOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(MENU_MILLIS, easing = FastOutLinearInEasing)) { it },
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = sheetMax)
                    .background(colors.sheet, shape)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            ) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.menu), color = colors.text, fontSize = 22.sp,
                         fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    CloseButton(onClose)
                }

                // what is not needed in the air scrolls above the in-flight
                // buttons; a fade says when there is more below
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f, fill = false)) {
                    Column(
                        Modifier
                            .verticalScroll(scroll)
                            .padding(horizontal = 12.dp),
                    ) {
                        QuickSettings(menu)
                        menu.sections.forEach { section ->
                            Caption(section.title,
                                    Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
                            SettingsGroup {
                                section.actions.forEachIndexed { index, action ->
                                    if (index > 0)
                                        HorizontalDivider(Modifier.padding(start = 54.dp),
                                                          color = colors.panelBorder)
                                    ListAction(action, onClose)
                                }
                            }
                        }
                        // the licence's credit, here rather than an "i" on the map
                        if (menu.mapCoverage != null)
                            Text(stringResource(R.string.menu_map_credit),
                                 color = colors.textSecondary, fontSize = 12.sp,
                                 modifier = Modifier.padding(start = 4.dp, top = 16.dp,
                                                             bottom = 12.dp))
                        else Spacer(Modifier.height(12.dp))
                    }
                    if (scroll.canScrollForward)
                        Box(Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(Brush.verticalGradient(
                                    listOf(colors.sheet.copy(alpha = 0f), colors.sheet))))
                }

                InFlightActions(menu.inFlight, onClose, wide)
            }
        }
    }
}

/**
 * The in-flight buttons, two by two (four in a row on a wide sheet),
 * on a band of their own at the bottom of the menu.
 */
@Composable
private fun InFlightActions(actions: List<MenuAction>, onClose: () -> Unit, wide: Boolean) {
    val colors = XcsTheme.colors
    HorizontalDivider(color = colors.panelBorder)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Caption(stringResource(R.string.menu_in_flight), Modifier.padding(start = 4.dp))
        actions.chunked(if (wide) 4 else 2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    BigAction(action, onClose, Modifier.weight(1f).fillMaxHeight())
                }
                repeat((if (wide) 4 else 2) - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** What the flight screen shows and sounds: map up, vario sound, tiles, rows, theme. */
@Composable
private fun QuickSettings(menu: FlightMenu) {
    Caption(stringResource(R.string.menu_quick_settings),
            Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        menu.orientation?.let { orientation ->
            // heading and wind up (set elsewhere) select none
            LabelledSegmented(stringResource(R.string.menu_map_up),
                              ORIENTATIONS.map { stringResource(it.second) },
                              selected = ORIENTATIONS.indexOfFirst { it.first == orientation },
                              onSelect = { menu.onOrientation(ORIENTATIONS[it].first) })
        }
        // the two used in the air first, then the screen's layout
        menu.varioSound?.let { on ->
            LabelledSegmented(stringResource(R.string.sound_tone_vario),
                              listOf(stringResource(R.string.on),
                                     stringResource(R.string.menu_muted)),
                              selected = if (on) 0 else 1,
                              onSelect = { menu.onVarioSound(it == 0) },
                              description = stringResource(R.string.menu_vario_sound))
        }
        LabelledSegmented(stringResource(R.string.menu_tiles),
                          listOf(stringResource(R.string.auto),
                                 stringResource(R.string.cruise),
                                 stringResource(R.string.circling)),
                          selected = when (menu.tileLayout) {
                              null -> 0
                              false -> 1
                              true -> 2
                          },
                          onSelect = { menu.onTileLayout(if (it == 0) null else it == 2) })
        LabelledSegmented(stringResource(R.string.menu_rows),
                          TILE_ROWS.map { it.toString() },
                          selected = menu.tileRows - TILE_ROWS.first,
                          onSelect = { menu.onTileRows(TILE_ROWS.first + it) })
        LabelledSegmented(stringResource(R.string.menu_theme),
                          ThemeChoice.entries.map { stringResource(it.label) },
                          selected = menu.theme.ordinal,
                          onSelect = { menu.onTheme(ThemeChoice.entries[it]) })
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    val colors = XcsTheme.colors
    val label = stringResource(R.string.menu_close)
    Box(
        Modifier
            .size(48.dp)
            .background(colors.panel, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClose)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = 2.5.dp.toPx()
            drawLine(colors.text, Offset(0f, 0f), Offset(size.width, size.height), stroke,
                     StrokeCap.Round)
            drawLine(colors.text, Offset(size.width, 0f), Offset(0f, size.height), stroke,
                     StrokeCap.Round)
        }
    }
}

/**
 * A choice of the flight screen: its name, then the segments.
 * [description] names it for TalkBack when [label] is short.
 */
@Composable
private fun LabelledSegmented(label: String, options: List<String>, selected: Int,
                              onSelect: (Int) -> Unit, description: String? = null) {
    val colors = XcsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 1,
             modifier = Modifier.width(72.dp).padding(start = 4.dp).then(
                 if (description != null) Modifier.semantics { contentDescription = description }
                 else Modifier))
        Segmented(options, selected, onSelect, Modifier.weight(1f))
    }
}

/**
 * What the pre-flight check says of the vector map, when it has
 * something to see to: no map for the flight, the glider beyond it, or
 * a hole on the route; null when the map covers the flight.
 */
@Composable
fun mapCoverageCaution(coverage: MapCoverage?): String? {
    coverage ?: return null
    val gapNear = coverage.gapNear
    return when {
        coverage.regions.isEmpty() -> stringResource(R.string.menu_map_none)
        coverage.gliderOutside -> stringResource(R.string.menu_map_glider_outside)
        gapNear != null -> stringResource(R.string.menu_map_task_gap, gapNear)
        else -> null
    }
}

/** A menu icon, in the text's colour unless [tint] says otherwise. */
@Composable
private fun MenuIcon(@DrawableRes icon: Int, tint: Color = XcsTheme.colors.text,
                     modifier: Modifier = Modifier) {
    if (icon == 0) return
    Image(painterResource(icon), contentDescription = null,
          colorFilter = ColorFilter.tint(tint), modifier = modifier.size(24.dp))
}

/**
 * A large in-flight button: its icon, the name, and what it holds or
 * shows now.  The icon above the name leaves it the button's width: a
 * long word ("Configuration") is not broken.
 */
@Composable
private fun BigAction(action: MenuAction, onClose: () -> Unit, modifier: Modifier) {
    val colors = XcsTheme.colors
    Column(
        modifier
            .heightIn(min = 80.dp)
            .alpha(if (action.enabled) 1f else 0.4f)
            .background(colors.panel, RoundedCornerShape(16.dp))
            .clickable(enabled = action.enabled, role = Role.Button) {
                if (action.closesMenu) onClose()
                action.onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MenuIcon(action.icon, modifier = Modifier.padding(bottom = 2.dp))
        Text(action.label, color = colors.text, fontSize = 17.sp, lineHeight = 20.sp,
             fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(action.detail, color = if (action.caution) colors.caution else colors.textSecondary,
             fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2,
             overflow = TextOverflow.Ellipsis)
    }
}

/** A row of a list: its icon, the name, its detail, and a chevron. */
@Composable
private fun ListAction(action: MenuAction, onClose: () -> Unit) {
    val colors = XcsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .alpha(if (action.enabled) 1f else 0.4f)
            .clickable(enabled = action.enabled, role = Role.Button) {
                if (action.closesMenu) onClose()
                action.onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MenuIcon(action.icon, colors.textSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(action.label, color = colors.text, fontSize = 17.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            Text(action.detail, color = if (action.caution) colors.caution
                                        else colors.textSecondary,
                 fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Chevron()
    }
}
