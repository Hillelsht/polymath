package com.hillelsht.smart.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hillelsht.smart.R
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.CategoryMastery
import com.hillelsht.smart.domain.LibraryKeys
import com.hillelsht.smart.domain.MasteryCalculator
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.Phase
import com.hillelsht.smart.domain.model.ReviewState
import com.hillelsht.smart.ui.components.FactImage
import com.hillelsht.smart.ui.components.GoDeeper
import com.hillelsht.smart.ui.components.MasteryRing
import com.hillelsht.smart.ui.components.SmartCard
import com.hillelsht.smart.ui.components.accent
import com.hillelsht.smart.ui.components.localizedBlurb
import com.hillelsht.smart.ui.components.localizedName
import com.hillelsht.smart.ui.components.secondary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: SmartRepository) : ViewModel() {
    val mastery = repository.mastery
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // The generated library tops itself up silently, so a category's fact count keeps
            // climbing without anyone having to ask for it.
            runCatching { repository.topUpLibrary() }
        }
    }

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { LibraryViewModel(repository) }
        }
    }
}

@Composable
fun LibraryScreen(repository: SmartRepository, onCategory: (Category) -> Unit) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(repository))
    val mastery by viewModel.mastery.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        // Bottom padding clears Aryeh's 64dp strip (MascotHost.DRAWN_HEIGHT_DP) plus a margin,
        // so the last card never sits under him when the list is scrolled to the end.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text(
                    text = stringResource(R.string.tab_read),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.library_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        items(mastery, key = { LibraryKeys.category(it.category.id) }) { entry ->
            CategoryRow(entry) { onCategory(entry.category) }
        }
    }
}

@Composable
private fun CategoryRow(entry: CategoryMastery, onClick: () -> Unit) {
    val category = entry.category
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        category.accent().copy(alpha = 0.16f),
                        category.secondary().copy(alpha = 0.08f),
                    ),
                ),
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MasteryRing(
            progress = entry.mastery,
            ringSize = 56.dp,
            strokeWidth = 6.dp,
            colors = listOf(category.accent(), category.secondary()),
            label = {
                Text(
                    text = "${(entry.mastery * 100).toInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = category.localizedName(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = category.localizedBlurb(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.library_mastered_of_total,
                    entry.masteredFacts,
                    entry.totalFacts,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = category.accent(),
            )
        }
    }
}

// --- Single category ------------------------------------------------------------------

class CategoryViewModel(repository: SmartRepository, category: Category) : ViewModel() {
    val facts = combine(
        repository.factsIn(category),
        repository.reviewStates,
    ) { facts, states ->
        // Sorting by id was fine for ninety-five hand-written facts. Now that the library
        // tops itself up, a category holds thousands and their ids order them by which
        // template generated them — every capital, then every currency. Facts already in play
        // come first so a learner's own material stays at the top, and the rest follow
        // most-important-first, which is the order they will actually be taught in.
        val ordered = facts.sortedWith(
            compareBy(
                { states[it.id]?.phase.let { phase -> phase == null || phase == Phase.NEW } },
                { it.difficulty },
                { it.title },
            ),
        )
        ordered to states
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList<Fact>() to emptyMap<String, ReviewState>(),
        )

    companion object {
        fun factory(repository: SmartRepository, category: Category) = viewModelFactory {
            initializer { CategoryViewModel(repository, category) }
        }
    }
}

@Composable
fun CategoryScreen(
    repository: SmartRepository,
    category: Category?,
    onBack: () -> Unit,
    onQuiz: (Category) -> Unit,
) {
    if (category == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.category_unknown), color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    val viewModel: CategoryViewModel = viewModel(
        factory = CategoryViewModel.factory(repository, category),
        key = category.id,
    )
    val content by viewModel.facts.collectAsStateWithLifecycle()
    val (facts, states) = content
    var expandedId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = category.localizedName(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.library_fact_count, facts.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onQuiz(category) }) {
                    Icon(
                        Icons.Rounded.Quiz,
                        contentDescription = stringResource(R.string.library_quiz_category_cd),
                        tint = category.accent(),
                    )
                }
            }
        }

        items(facts, key = { it.id }) { fact ->
            FactRow(
                fact = fact,
                state = states[fact.id],
                expanded = expandedId == fact.id,
                onToggle = { expandedId = if (expandedId == fact.id) null else fact.id },
            )
        }
    }
}

@Composable
private fun FactRow(
    fact: Fact,
    state: ReviewState?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SmartCard(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        contentPadding = 16.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MasteryDot(MasteryCalculator.factMastery(state), fact.category.accent())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = fact.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = fact.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(14.dp))
                FactImage(
                    imageUrl = fact.imageUrl,
                    wikiTitle = fact.wikiTitle,
                    category = fact.category,
                    ratio = 16f / 9f,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = fact.statement,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                GoDeeper(
                    details = fact.details,
                    pageUrl = fact.pageUrl,
                    accent = fact.category.accent(),
                )
                state?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            if (it.intervalDays == 1) {
                                R.string.library_review_status_singular
                            } else {
                                R.string.library_review_status_plural
                            },
                            it.intervalDays,
                            it.totalReviews,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MasteryDot(mastery: Float, accent: Color) {
    Box(
        Modifier.size(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        MasteryRing(
            progress = mastery,
            ringSize = 34.dp,
            strokeWidth = 4.dp,
            colors = listOf(accent, accent),
        )
    }
}
