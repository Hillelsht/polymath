package com.hillelsht.smart.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.ui.LocalImageResolver
import com.hillelsht.smart.ui.theme.SmartPalette

/** Parses a category's stored hex accent into a Compose colour. */
fun Category.accent(): Color = Color(android.graphics.Color.parseColor(accentHex))

fun Category.secondary(): Color = Color(android.graphics.Color.parseColor(secondaryHex))

fun Category.gradient(): Brush = Brush.linearGradient(listOf(accent(), secondary()))

/** The app's standard raised panel. */
@Composable
fun SmartCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 20.dp,
    border: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, border),
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/**
 * A circular progress ring drawn with the category's own gradient.
 *
 * The sweep animates from wherever it was, so returning to Home after a session shows the ring
 * moving rather than jumping — the single clearest signal that the work counted for something.
 */
@Composable
fun MasteryRing(
    progress: Float,
    modifier: Modifier = Modifier,
    ringSize: Dp = 76.dp,
    strokeWidth: Dp = 8.dp,
    colors: List<Color> = listOf(SmartPalette.IrisBright, SmartPalette.Mint),
    trackColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    label: (@Composable () -> Unit)? = null,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900),
        label = "mastery",
    )

    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            if (animated > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(colors + colors.first()),
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        label?.invoke()
    }
}

/**
 * A Wikipedia image for a fact, with a graceful fallback.
 *
 * When there is no network, no picture, or the resolution is still in flight, the slot is
 * filled with the category's gradient rather than a grey box or a layout jump — an empty state
 * that still looks deliberate is the difference between "loading" and "broken".
 */
@Composable
fun FactImage(
    wikiTitle: String?,
    category: Category,
    modifier: Modifier = Modifier,
    ratio: Float = 16f / 10f,
    cornerRadius: Dp = 20.dp,
) {
    val resolver = LocalImageResolver.current
    val url by produceState<String?>(initialValue = null, wikiTitle) {
        value = wikiTitle?.let { resolver.resolve(it) }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    listOf(
                        category.accent().copy(alpha = 0.35f),
                        category.secondary().copy(alpha = 0.55f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val resolved = url
        if (resolved != null) {
            SubcomposeAsyncImage(
                model = resolved,
                contentDescription = wikiTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { CategoryGlyph() },
                error = { CategoryGlyph() },
            )
        } else {
            CategoryGlyph()
        }
    }
}

@Composable
private fun CategoryGlyph() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(40.dp),
        )
    }
}

/** Small pill used for category names and metadata. */
@Composable
fun CategoryChip(
    category: Category,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(category.accent().copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(category.accent()),
        )
        Text(
            text = category.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = category.accent(),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A statistic with its caption, used across Home and Stats. */
@Composable
fun StatTile(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Shown whenever a queue is empty — always with a reason and a way forward. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** Horizontal progress bar used in Learn, Review and Quiz to show session position. */
@Composable
fun SessionProgress(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val fraction by animateFloatAsState(
        targetValue = if (total == 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "sessionProgress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(accent),
        )
    }
}
