package com.hillelsht.smart.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.CategoryMastery
import com.hillelsht.smart.domain.DailyPlan
import com.hillelsht.smart.domain.MasteryCalculator
import com.hillelsht.smart.domain.Streak
import com.hillelsht.smart.ui.components.MasteryRing
import com.hillelsht.smart.ui.components.SmartCard
import com.hillelsht.smart.ui.components.accent
import com.hillelsht.smart.ui.components.secondary
import com.hillelsht.smart.ui.theme.SmartPalette
import com.hillelsht.smart.util.CrashLog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime

data class HomeUiState(
    val ready: Boolean = false,
    val plan: DailyPlan = DailyPlan(emptyList(), emptyList()),
    val streak: Streak = Streak(0, 0, false),
    val mastery: List<CategoryMastery> = emptyList(),
) {
    val overall: Float get() = MasteryCalculator.overall(mastery)
    val totalFacts: Int get() = mastery.sumOf { it.totalFacts }
    val masteredFacts: Int get() = mastery.sumOf { it.masteredFacts }
    val seenFacts: Int get() = mastery.sumOf { it.seenFacts }
}

class HomeViewModel(repository: SmartRepository) : ViewModel() {

    val state = combine(
        repository.plan,
        repository.streak,
        repository.mastery,
    ) { plan, streak, mastery ->
        HomeUiState(ready = true, plan = plan, streak = streak, mastery = mastery)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}

@Composable
fun HomeScreen(
    repository: SmartRepository,
    onLearn: () -> Unit,
    onReview: () -> Unit,
    onQuiz: () -> Unit,
    onBrowse: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(repository))
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { CrashCard() }
        item { Greeting(state.streak) }
        item { TodayCard(state, onLearn = onLearn, onReview = onReview, onQuiz = onQuiz) }
        item { OverallProgress(state) }
        item { MasteryRow(state.mastery) }
        item {
            QuickActions(onQuiz = onQuiz, onBrowse = onBrowse)
        }
    }
}

/**
 * Surfaces the last uncaught exception, if there was one.
 *
 * The developer cannot attach a debugger to this phone, so without this a crash report is
 * "it closed again" and diagnosis is guesswork. Copy puts the full trace on the clipboard.
 */
@Composable
private fun CrashCard() {
    val context = LocalContext.current
    var trace by remember { mutableStateOf(CrashLog.read(context)) }
    val report = trace ?: return
    val clipboard = LocalClipboardManager.current

    SmartCard(
        Modifier.fillMaxWidth(),
        border = SmartPalette.Danger.copy(alpha = 0.5f),
    ) {
        Column {
            Text(
                text = "The app crashed last time",
                style = MaterialTheme.typography.titleMedium,
                color = SmartPalette.Danger,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = report.lineSequence().take(6).joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(report)) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Copy report")
                }
                Button(
                    onClick = {
                        CrashLog.clear(context)
                        trace = null
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun Greeting(streak: Streak) {
    val hour = remember { LocalTime.now().hour }
    val salutation = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = salutation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Let's get smarter",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        StreakBadge(streak)
    }
}

@Composable
private fun StreakBadge(streak: Streak) {
    val alive = streak.current > 0
    Row(
        Modifier
            .clip(CircleShape)
            .background(
                if (alive) SmartPalette.Warning.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.LocalFireDepartment,
            contentDescription = "Current streak",
            tint = if (alive) SmartPalette.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = streak.current.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (alive) SmartPalette.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The single most important card in the app: what to do right now.
 *
 * Reviews are offered before new material, because clearing memory debt matters more than
 * adding to it — the button order is the teaching strategy made visible.
 */
@Composable
private fun TodayCard(
    state: HomeUiState,
    onLearn: () -> Unit,
    onReview: () -> Unit,
    onQuiz: () -> Unit,
) {
    val due = state.plan.dueFactIds.size
    val fresh = state.plan.newFactIds.size

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(SmartPalette.Iris, SmartPalette.IrisBright, SmartPalette.Mint),
                ),
            )
            .padding(24.dp),
    ) {
        Column {
            Text(
                text = "TODAY'S SESSION",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    !state.ready -> "Preparing your curriculum…"
                    state.plan.isEmpty -> "All caught up. Nothing is due."
                    else -> "$due to review · $fresh new"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (state.plan.isEmpty) {
                    "Take a quiz to keep the knowledge sharp."
                } else {
                    "About ${estimateMinutes(due, fresh)} minutes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (due > 0) {
                    HeroButton("Review $due", Modifier.weight(1f), onReview)
                }
                if (fresh > 0) {
                    HeroButton(
                        text = if (due > 0) "Learn" else "Learn $fresh new",
                        modifier = Modifier.weight(1f),
                        onClick = onLearn,
                        filled = due == 0,
                    )
                }
                if (state.plan.isEmpty && state.ready) {
                    HeroButton("Start a quiz", Modifier.weight(1f), onQuiz)
                }
            }
        }
    }
}

private fun estimateMinutes(due: Int, fresh: Int): Int =
    ((due * 6 + fresh * 20) / 60).coerceAtLeast(1)

@Composable
private fun HeroButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    filled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) Color.White else Color.White.copy(alpha = 0.22f),
            contentColor = if (filled) SmartPalette.Iris else Color.White,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun OverallProgress(state: HomeUiState) {
    SmartCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MasteryRing(
                progress = state.overall,
                ringSize = 88.dp,
                label = {
                    Text(
                        text = "${(state.overall * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
            )
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    text = "Knowledge mastered",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${state.masteredFacts} of ${state.totalFacts} facts locked in",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${state.seenFacts} seen so far",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MasteryRow(mastery: List<CategoryMastery>) {
    Column {
        Text(
            text = "By subject",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(mastery, key = { it.category.id }) { entry ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp)
                        .width(104.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MasteryRing(
                        progress = entry.mastery,
                        ringSize = 60.dp,
                        strokeWidth = 6.dp,
                        colors = listOf(entry.category.accent(), entry.category.secondary()),
                        label = {
                            Text(
                                text = "${(entry.mastery * 100).toInt()}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = entry.category.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActions(onQuiz: () -> Unit, onBrowse: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionTile(
            icon = Icons.Rounded.Quiz,
            title = "Quiz me",
            subtitle = "10 questions",
            modifier = Modifier.weight(1f),
            onClick = onQuiz,
        )
        ActionTile(
            icon = Icons.Rounded.MenuBook,
            title = "Library",
            subtitle = "Browse everything",
            modifier = Modifier.weight(1f),
            onClick = onBrowse,
        )
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
