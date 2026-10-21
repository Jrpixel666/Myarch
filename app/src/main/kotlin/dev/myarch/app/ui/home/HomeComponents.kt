package dev.myarch.app.ui.home

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.myarch.app.data.HomeItem
import dev.myarch.wayland.core.TouchPhase
import dev.myarch.wayland.protocol.ZwlrLayerShellV1

/** An app shortcut cell: icon, label, long-press to remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShortcutCell(
    label: String,
    icon: Drawable?,
    fallbackColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        if (icon != null) {
            Image(
                painter = remember(icon) { DrawablePainter(icon) },
                contentDescription = label,
                modifier = Modifier.size(52.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            // Linux entries have icon names, not bitmaps we can resolve without
            // walking the container's icon theme; a monogram is honest and fast.
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(fallbackColor.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = fallbackColor,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Draws an Android `Drawable` inside Compose. */
private class DrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize: androidx.compose.ui.geometry.Size
        get() = androidx.compose.ui.geometry.Size(
            drawable.intrinsicWidth.coerceAtLeast(1).toFloat(),
            drawable.intrinsicHeight.coerceAtLeast(1).toFloat(),
        )

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

/** A hosted Android app widget on a page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AndroidWidgetCell(
    state: HomeState,
    item: HomeItem.Widget,
    onRemove: () -> Unit,
) {
    val view = remember(item.appWidgetId) { state.widgetView(item) }
    if (view == null) {
        // The provider was uninstalled, or the host could not inflate it.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .height(item.heightDp.dp)
                .combinedClickable(onClick = {}, onLongClick = onRemove),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "Widget unavailable\nLong-press to remove",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(item.heightDp.dp)
            .combinedClickable(onClick = {}, onLongClick = onRemove),
    ) {
        AndroidView(
            factory = { view },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Draws the compositor's layer-shell surfaces over (or under) the pages.
 *
 * This is where Omarchy's bar and widgets actually land on the home screen.
 * Positions come from the layer surface's own anchors, resolved by the
 * compositor, so Quickshell's configuration decides the layout rather than
 * anything hard-coded here.
 *
 * @param layers which `zwlr_layer_shell_v1` layers to draw; the launcher calls
 *   this twice so background panels end up behind the icon grid and bars above.
 */
@Composable
internal fun PanelLayer(
    state: HomeState,
    modifier: Modifier = Modifier,
    layers: IntRange = ZwlrLayerShellV1.Layer.TOP..ZwlrLayerShellV1.Layer.OVERLAY,
) {
    val scale = state.service?.compositor?.config?.uiScale ?: 1
    val density = LocalDensity.current
    // Reading the revision is what makes new frames appear.
    @Suppress("UNUSED_EXPRESSION")
    state.frameRevision

    Box(modifier = modifier) {
        for (panel in state.panels) {
            if (panel.info.layer !in layers) continue
            val widthDp = with(density) { (panel.info.width * scale).toDp() }
            val heightDp = with(density) { (panel.info.height * scale).toDp() }
            val xDp = with(density) { (panel.info.x * scale).toDp() }
            val yDp = with(density) { (panel.info.y * scale).toDp() }
            Box(
                modifier = Modifier
                    .offset(x = xDp, y = yDp)
                    .size(width = widthDp, height = heightDp)
                    .pointerInput(panel.info.surfaceId) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                val phase = when (event.type) {
                                    PointerEventType.Press -> TouchPhase.DOWN
                                    PointerEventType.Move -> TouchPhase.MOVE
                                    PointerEventType.Release -> TouchPhase.UP
                                    else -> null
                                } ?: continue
                                state.service?.compositor?.dispatchPanelTouch(
                                    surfaceId = panel.info.surfaceId,
                                    touchId = 0,
                                    phase = phase,
                                    xPx = change.position.x,
                                    yPx = change.position.y,
                                )
                                // Consume so the pager underneath does not also
                                // scroll when the user drags inside a widget.
                                change.consume()
                            }
                        }
                    },
            ) {
                Image(
                    bitmap = panel.bitmap.asImageBitmap(),
                    contentDescription = panel.info.namespace,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }
}
