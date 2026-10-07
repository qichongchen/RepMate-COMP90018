package com.repmate.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/*
 * Shared axis support for the two charts (ScoreTrendChart, AccelerometerCurveChart).
 *
 * Two halves, kept in one small file on purpose:
 *  - pure functions that decide WHICH labels to show (thinTickIndices, timeTickMillis, ...), so
 *    they can be unit tested on the JVM, and
 *  - AxesCanvas, which measures and draws them and hands the caller a plot area to draw into.
 */

/** One labelled tick; [fraction] is how far along its axis it sits: 0 = origin (left / bottom), 1 = far end. */
internal data class AxisTick(
    val fraction: Float,
    val label: String,
)

/**
 * What [AxesCanvas] draws around a plot. Captions are short ("rep", "score"); [xTicks] may list
 * more labels than fit and are thinned to fit; [yGridFractions] are y positions (0..1) that get
 * a faint gridline across the plot.
 */
internal data class ChartAxes(
    val xCaption: String,
    val yCaption: String,
    val xTicks: List<AxisTick>,
    val yTicks: List<AxisTick>,
    val yGridFractions: List<Float> = emptyList(),
)

// --- Pure helpers (unit tested) --------------------------------------------------------------

/**
 * Which of [count] evenly spaced tick labels to draw, as indices, when at most [maxLabels] fit.
 *
 * The first and last are always included. If everything fits, all are returned; otherwise every
 * k-th is, with k the smallest step that stays within [maxLabels]. A tick sitting closer than k
 * to the last one is dropped, so no two labels are ever less than k ticks apart and, with the
 * labels' width folded into [maxLabels], never overlap. [maxLabels] below 2 is treated as 2.
 */
internal fun thinTickIndices(
    count: Int,
    maxLabels: Int,
): List<Int> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(0)
    val limit = maxLabels.coerceAtLeast(2)
    if (count <= limit) return (0 until count).toList()

    val last = count - 1
    val step = (last + limit - 2) / (limit - 1) // ceil(last / (limit - 1))
    val picked = (0 until last step step).toMutableList()
    if (picked.size > 1 && last - picked.last() < step) picked.removeAt(picked.lastIndex)
    picked += last
    return picked
}

/** Round step sizes, in ms, that [timeTickStepMs] picks from (scaled by powers of ten). */
private val NICE_STEPS_MS = longArrayOf(10L, 20L, 25L, 50L)

/**
 * The step between x ticks for a rep lasting [durationMs]: the smallest round step (...100, 200,
 * 250, 500, 1000...) that still gives at most [maxTicks] ticks from 0, which in practice means
 * 3 to 4 ticks. [maxTicks] below 2 is treated as 2.
 */
internal fun timeTickStepMs(
    durationMs: Long,
    maxTicks: Int = 4,
): Long {
    val limit = maxTicks.coerceAtLeast(2)
    var scale = 1L
    while (true) {
        for (base in NICE_STEPS_MS) {
            val step = base * scale
            if (durationMs / step + 1 <= limit) return step
        }
        scale *= 10L
    }
}

/**
 * Tick positions in ms for an x axis that runs from 0 to [durationMs]: 0 and every
 * [timeTickStepMs] after it, not past the duration. A zero or negative duration has just 0.
 */
internal fun timeTickMillis(
    durationMs: Long,
    maxTicks: Int = 4,
): List<Long> {
    if (durationMs <= 0L) return listOf(0L)
    val step = timeTickStepMs(durationMs, maxTicks)
    return (0..durationMs / step).map { it * step }
}

/**
 * A time tick in seconds, with just enough decimals to tell neighbouring ticks apart for this
 * [stepMs] (whole seconds for 1 s steps, one decimal for 0.1 s multiples, else two). No unit: the
 * axis caption carries it.
 */
internal fun formatTimeTick(
    tickMs: Long,
    stepMs: Long,
): String {
    val decimals =
        when {
            stepMs % 1000L == 0L -> 0
            stepMs % 100L == 0L -> 1
            else -> 2
        }
    return String.format(Locale.US, "%.${decimals}f", tickMs / 1000.0)
}

/** A y-axis value with one decimal, e.g. "9.8". */
internal fun formatAxisValue(value: Float): String = String.format(Locale.US, "%.1f", value)

// --- Drawing ---------------------------------------------------------------------------------

private val LABEL_GAP = 4.dp
private val MIN_X_LABEL_SPACING = 6.dp

/** Every label measured once, so the space to reserve is known before anything is drawn. */
private class MeasuredAxes(
    val xCaption: TextLayoutResult,
    val yCaption: TextLayoutResult,
    val xTicks: List<TextLayoutResult>,
    val yTicks: List<TextLayoutResult>,
) {
    val lineHeight: Float = xCaption.size.height.toFloat()
    val widestYLabel: Int = yTicks.maxOfOrNull { it.size.width } ?: 0
    val widestXLabel: Int = xTicks.maxOfOrNull { it.size.width } ?: 0
}

/**
 * A [Canvas] with readable axes: a left and a bottom axis line, tick labels, optional gridlines
 * and a caption on each axis. [drawPlot] draws into the plot area only: inside it `size` is the
 * plot's own size and (0, 0) its top-left, so the caller never deals with the label margins.
 *
 * ## Reserved space
 * The margins are computed from the measured text, then added to [plotHeight]: the plot itself
 * is always exactly [plotHeight] tall and does not change with the label text. The width the
 * plot gets is whatever the labels leave of the parent's width.
 *  - left: the widest y label plus a small gap;
 *  - top: one caption line plus half a line, so the top y label (centred on the plot's top edge)
 *    never touches the y caption;
 *  - right: half the widest x label, because the last x label is centred on the plot's right edge;
 *  - bottom: a row of x labels and a row for the x caption.
 *
 * x labels that would not fit are thinned with [thinTickIndices]. Colours come from
 * [MaterialTheme] (labels `onSurfaceVariant`, lines `outlineVariant`), so light and dark both work.
 */
@Composable
internal fun AxesCanvas(
    axes: ChartAxes,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 120.dp,
    drawPlot: DrawScope.() -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val gridColor = axisColor.copy(alpha = 0.5f)

    val measured =
        remember(axes, labelStyle) {
            MeasuredAxes(
                xCaption = measurer.measure(axes.xCaption, labelStyle),
                yCaption = measurer.measure(axes.yCaption, labelStyle),
                xTicks = axes.xTicks.map { measurer.measure(it.label, labelStyle) },
                yTicks = axes.yTicks.map { measurer.measure(it.label, labelStyle) },
            )
        }

    val gap = with(density) { LABEL_GAP.toPx() }
    val minLabelSpacing = with(density) { MIN_X_LABEL_SPACING.toPx() }
    val marginLeft = measured.widestYLabel + gap
    val marginTop = measured.lineHeight * 1.5f
    val marginRight = measured.widestXLabel / 2f
    val marginBottom = gap + measured.lineHeight + gap + measured.lineHeight
    val totalHeight = plotHeight + with(density) { (marginTop + marginBottom).toDp() }

    Canvas(modifier = modifier.height(totalHeight)) {
        val plotLeft = marginLeft
        val plotTop = marginTop
        val plotRight = size.width - marginRight
        val plotBottom = size.height - marginBottom
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(0f)
        val plotHeightPx = (plotBottom - plotTop).coerceAtLeast(0f)
        val lineWidth = 1.dp.toPx()

        fun yAt(fraction: Float): Float = plotBottom - fraction * plotHeightPx

        drawText(measured.yCaption, topLeft = Offset(0f, 0f))

        axes.yGridFractions.forEach { fraction ->
            drawLine(gridColor, Offset(plotLeft, yAt(fraction)), Offset(plotRight, yAt(fraction)), strokeWidth = lineWidth)
        }
        drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), strokeWidth = lineWidth)
        drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), strokeWidth = lineWidth)

        axes.yTicks.forEachIndexed { index, tick ->
            val label = measured.yTicks[index]
            drawText(label, topLeft = Offset(plotLeft - gap - label.size.width, yAt(tick.fraction) - label.size.height / 2f))
        }

        // How many x labels fit side by side with a small gap, judged by the widest one.
        val maxLabels = ((plotWidth + minLabelSpacing) / (measured.widestXLabel + minLabelSpacing)).toInt()
        thinTickIndices(axes.xTicks.size, maxLabels).forEach { index ->
            val label = measured.xTicks[index]
            val centreX = plotLeft + axes.xTicks[index].fraction * plotWidth
            val left = (centreX - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
            drawText(label, topLeft = Offset(left, plotBottom + gap))
        }

        val captionLeft = plotLeft + (plotWidth - measured.xCaption.size.width) / 2f
        drawText(measured.xCaption, topLeft = Offset(captionLeft, plotBottom + gap + measured.lineHeight + gap))

        inset(left = plotLeft, top = plotTop, right = size.width - plotRight, bottom = size.height - plotBottom) {
            drawPlot()
        }
    }
}
