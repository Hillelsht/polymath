package com.hillelsht.smart.ui.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.ui.components.CategoryChip
import com.hillelsht.smart.ui.components.EmptyState
import com.hillelsht.smart.ui.components.FactImage
import com.hillelsht.smart.ui.components.GoDeeper
import com.hillelsht.smart.ui.components.SessionProgress
import com.hillelsht.smart.ui.components.accent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LearnUiState(
    val queue: List<Fact> = emptyList(),
    val index: Int = 0,
    val ready: Boolean = false,
) {
    val current: Fact? get() = queue.getOrNull(index)
    val finished: Boolean get() = ready && index >= queue.size
}

/**
 * Teaches new facts one at a time.
 *
 * The queue is captured once when the screen opens rather than tracked live: as each fact is
 * marked learned it leaves the plan, and a reactive queue would delete the card out from under
 * the person reading it.
 */
class LearnViewModel(private val repository: SmartRepository) : ViewModel() {

    private val index = MutableStateFlow(0)
    private var captured: List<Fact>? = null

    val state = combine(
        repository.plan,
        repository.facts,
        index,
    ) { plan, facts, position ->
        val queue = captured ?: run {
            val byId = facts.associateBy { it.id }
            plan.newFactIds.mapNotNull(byId::get).also { if (facts.isNotEmpty()) captured = it }
        }
        LearnUiState(queue = queue, index = position, ready = facts.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LearnUiState())

    fun learned(fact: Fact) {
        viewModelScope.launch {
            repository.markLearned(fact.id)
            index.value += 1
        }
    }

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { LearnViewModel(repository) }
        }
    }
}

@Composable
fun LearnScreen(repository: SmartRepository, onDone: () -> Unit) {
    val viewModel: LearnViewModel = viewModel(factory = LearnViewModel.factory(repository))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            SessionProgress(
                current = state.index,
                total = state.queue.size,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${state.index.coerceAtMost(state.queue.size)}/${state.queue.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        val fact = state.current
        when {
            fact != null -> FactCard(
                fact = fact,
                modifier = Modifier.weight(1f),
                onNext = { viewModel.learned(fact) },
            )

            state.finished -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Nice work",
                    body = "You learned ${state.queue.size} new " +
                        if (state.queue.size == 1) "fact." else "facts.",
                    action = {
                        Button(onClick = onDone, shape = RoundedCornerShape(14.dp)) {
                            Text("Done")
                        }
                    },
                )
            }

            else -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Nothing new today",
                    body = "You have hit today's limit for new material. " +
                        "Come back tomorrow, or review what you already know.",
                    action = {
                        Button(onClick = onDone, shape = RoundedCornerShape(14.dp)) {
                            Text("Back")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FactCard(fact: Fact, modifier: Modifier = Modifier, onNext: () -> Unit) {
    Column(modifier) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            FactImage(imageUrl = fact.imageUrl, wikiTitle = fact.wikiTitle, category = fact.category)
            Spacer(Modifier.height(20.dp))
            CategoryChip(fact.category)
            Spacer(Modifier.height(14.dp))
            Text(
                text = fact.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = fact.statement,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            fact.hook?.let { hook ->
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(fact.category.accent().copy(alpha = 0.12f))
                        .padding(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = fact.category.accent(),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "MEMORY HOOK",
                            style = MaterialTheme.typography.labelSmall,
                            color = fact.category.accent(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = hook,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            GoDeeper(
                details = fact.details,
                pageUrl = fact.pageUrl,
                accent = fact.category.accent(),
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 24.dp),
        ) {
            Text("Got it", style = MaterialTheme.typography.labelLarge)
        }
    }
}
