package com.smartago.tvfocus

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** BlurMaskFilter is immutable and shareable; radii are whole px, so this stays tiny. */
private val blurFilterCache = HashMap<Int, BlurMaskFilter>()

/**
 * True CSS box-shadow parity (`0 0 <blur> <spread> color`): a rounded rect expanded by
 * [spread], gaussian-blurred by [blur] via BlurMaskFilter, drawn BEHIND the element —
 * a soft halo like a CSS focus glow (no banding, unlike stroke stacking).
 *
 * [clipOut]: CSS outer box-shadows never paint INSIDE the border box. Elements with a
 * solid background hide that area anyway, but TRANSPARENT elements (the traveling focus
 * ring) must clip the interior out or the glow tints whatever is underneath yellow.
 */
fun Modifier.cssGlow(color: Color, alpha: Float, blur: Dp, spread: Dp, corner: Dp, clipOut: Boolean = false): Modifier =
    drawBehind {
        // WHOLE pixels, deliberately: skia caches blurred masks by (sigma, shape), and the focus
        // ring's pulse animates blur 10→18 and spread 4→8 CONTINUOUSLY — every frame a fresh
        // sigma, so every frame pays a full gaussian re-blur (brutal on the emulator's software
        // GL, real work on a box too). Rounded, one 1.2s pulse cycles ~16 cached masks instead
        // of rendering ~72 unique ones, and the eye cannot tell the steps apart.
        val blurPx = kotlin.math.round(blur.toPx()).coerceAtLeast(1f)
        val spreadPx = kotlin.math.round(spread.toPx())
        // pill shapes pass an oversized corner (999.dp) — clamp like RoundedCornerShape does
        val maxCorner = minOf(size.width, size.height) / 2f
        val baseCorner = minOf(corner.toPx(), maxCorner)
        val cornerPx = baseCorner + spreadPx
        drawIntoCanvas { canvas ->
            val paint = Paint()
            val fp = paint.asFrameworkPaint()
            fp.isAntiAlias = true
            fp.color = color.copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
            // same rounded radius → same filter instance → skia's mask cache actually hits
            fp.maskFilter = blurFilterCache.getOrPut(blurPx.toInt()) {
                BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
            if (clipOut) {
                canvas.save()
                val inner = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(baseCorner)))
                }
                canvas.clipPath(inner, ClipOp.Difference)
            }
            canvas.drawRoundRect(
                left = -spreadPx,
                top = -spreadPx,
                right = size.width + spreadPx,
                bottom = size.height + spreadPx,
                radiusX = cornerPx,
                radiusY = cornerPx,
                paint = paint,
            )
            if (clipOut) canvas.restore()
        }
    }
