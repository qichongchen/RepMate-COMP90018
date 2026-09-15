package com.repmate.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.repmate.R
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.theme.RepMateTheme
import kotlinx.coroutines.launch

/** The square each page's icon sits in, dashed border and all. */
private val ICON_BOX_SIZE = 160.dp

/** The icon itself, inset within [ICON_BOX_SIZE]. */
private val ICON_SIZE = 84.dp

/**
 * One explainer page's copy plus its icon. [icon] takes the [Modifier] that sizes/centers it
 * inside the page's [DashedIconBox], so every page's icon renders at the same size regardless of
 * whether it's a drawable ([AutoRepCountIcon]) or hand-drawn ([ChartLineIcon], [PlayScrubberIcon]).
 */
private data class OnboardingPage(
    val title: String,
    val body: String,
    val icon: @Composable (Modifier) -> Unit,
)

private val ONBOARDING_PAGES =
    listOf(
        OnboardingPage(
            title = "Count reps automatically",
            body =
                "RepMate uses your phone's accelerometer and gyroscope to count reps as you move.",
            icon = { modifier -> AutoRepCountIcon(modifier) },
        ),
        OnboardingPage(
            title = "Score your form",
            body = "See how clean each rep is: range of motion, tempo, and consistency, not just a number.",
            icon = { modifier -> ChartLineIcon(modifier) },
        ),
        OnboardingPage(
            title = "Replay every rep",
            body = "Step through your session rep by rep and see exactly what changed.",
            icon = { modifier -> PlayScrubberIcon(modifier) },
        ),
    )

/**
 * The 3-page onboarding explainer, shown once per Firebase UID right after first sign-in; see
 * [OnboardingGateViewModel] for where that decision actually gets made, in `NavGraph.kt`.
 *
 * @param onFinish invoked once onboarding is marked complete for the current user, via Skip or
 *   "Get started" on the last page, not on every "Next" tap, only on actual finish.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) {
        viewModel.finished.collect { onFinish() }
    }

    OnboardingContent(
        onFinishClick = viewModel::onFinishClicked,
        modifier = modifier,
    )
}

@Composable
private fun OnboardingContent(
    onFinishClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { ONBOARDING_PAGES.size })
    val coroutineScope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGES.lastIndex

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            OnboardingPageContent(ONBOARDING_PAGES[page])
        }

        PageIndicatorDots(
            pageCount = ONBOARDING_PAGES.size,
            currentPage = pagerState.currentPage,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
        )

        RepMateButton(
            text = if (isLastPage) "Get started" else "Next",
            onClick = {
                if (isLastPage) {
                    onFinishClick()
                } else {
                    // NOTE: animateScrollToPage, not a jump: the pager should visibly slide,
                    // the same motion a manual swipe would produce, not snap to the next page.
                    coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
        )

        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onFinishClick, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Skip",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DashedIconBox(icon = page.icon)
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The dashed-border square every page's icon sits inside, shared so all three pages match. */
@Composable
private fun DashedIconBox(
    icon: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(ICON_BOX_SIZE)
                .dashedBorder(color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center,
    ) {
        icon(Modifier.size(ICON_SIZE))
    }
}

/**
 * A dashed-outline border in an arbitrary [shape]. Compose's built-in `Modifier.border` only
 * draws solid strokes, so this draws the outline itself with a dashed [PathEffect] instead.
 */
private fun Modifier.dashedBorder(
    color: Color,
    shape: Shape,
    strokeWidth: Dp = 1.5.dp,
    dashLength: Dp = 8.dp,
    gapLength: Dp = 6.dp,
): Modifier =
    drawWithContent {
        drawContent()
        drawOutline(
            outline = shape.createOutline(size, layoutDirection, this),
            color = color,
            style =
                Stroke(
                    width = strokeWidth.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength.toPx(), gapLength.toPx()), 0f),
                ),
        )
    }

/**
 * Page 1's dedicated icon (a stopwatch, for automatic rep counting), tinted via [Icon]'s
 * ColorFilter at render time, the same approach as `WelcomeScreen`'s brand icon and for the same
 * reason: the drawable's own fillColor is just a placeholder, this is what makes it theme-aware.
 */
@Composable
private fun AutoRepCountIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(id = R.drawable.auto_rep_count),
        contentDescription = null,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** Page 2's icon: a simple jagged upward line, drawn directly with Canvas/Path (no icon library). */
@Composable
private fun ChartLineIcon(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * ICON_STROKE_FRACTION
        val path =
            Path().apply {
                moveTo(size.width * 0.10f, size.height * 0.80f)
                lineTo(size.width * 0.36f, size.height * 0.55f)
                lineTo(size.width * 0.58f, size.height * 0.68f)
                lineTo(size.width * 0.90f, size.height * 0.20f)
            }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Page 3's icon: a stroked circle with a filled play triangle, same stroke weight as [ChartLineIcon]. */
@Composable
private fun PlayScrubberIcon(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * ICON_STROKE_FRACTION
        drawCircle(
            color = tint,
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(width = stroke),
        )

        val triangleWidth = size.minDimension * 0.32f
        val triangleHeight = size.minDimension * 0.38f
        // Nudged slightly right of true center: a symmetric triangle reads as visually
        // off-center-left once its point is centered, the same optical correction real play
        // icons make.
        val centerX = size.width / 2f + triangleWidth * 0.12f
        val centerY = size.height / 2f
        val trianglePath =
            Path().apply {
                moveTo(centerX - triangleWidth / 2f, centerY - triangleHeight / 2f)
                lineTo(centerX - triangleWidth / 2f, centerY + triangleHeight / 2f)
                lineTo(centerX + triangleWidth / 2f, centerY)
                close()
            }
        drawPath(path = trianglePath, color = tint)
    }
}

/** Stroke width for [ChartLineIcon]/[PlayScrubberIcon], as a fraction of the icon's own size so both scale identically. */
private const val ICON_STROKE_FRACTION = 0.09f

@Composable
private fun PageIndicatorDots(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (selected) 10.dp else 8.dp)
                        .background(
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        ),
            )
        }
    }
}

@Preview(name = "Light - Page 1", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun OnboardingScreenPage1LightPreview() {
    RepMateTheme(darkTheme = false) {
        OnboardingContent(onFinishClick = {}, initialPage = 0)
    }
}

@Preview(name = "Dark - Page 1", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun OnboardingScreenPage1DarkPreview() {
    RepMateTheme(darkTheme = true) {
        OnboardingContent(onFinishClick = {}, initialPage = 0)
    }
}

@Preview(name = "Light - Page 2", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun OnboardingScreenPage2LightPreview() {
    RepMateTheme(darkTheme = false) {
        OnboardingContent(onFinishClick = {}, initialPage = 1)
    }
}

@Preview(name = "Dark - Page 2", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun OnboardingScreenPage2DarkPreview() {
    RepMateTheme(darkTheme = true) {
        OnboardingContent(onFinishClick = {}, initialPage = 1)
    }
}

@Preview(name = "Light - Page 3", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun OnboardingScreenPage3LightPreview() {
    RepMateTheme(darkTheme = false) {
        OnboardingContent(onFinishClick = {}, initialPage = 2)
    }
}

@Preview(name = "Dark - Page 3", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun OnboardingScreenPage3DarkPreview() {
    RepMateTheme(darkTheme = true) {
        OnboardingContent(onFinishClick = {}, initialPage = 2)
    }
}
