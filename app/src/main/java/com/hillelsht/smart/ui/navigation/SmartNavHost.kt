package com.hillelsht.smart.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hillelsht.smart.R
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.MascotDirector.Surface
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.ui.home.HomeScreen
import com.hillelsht.smart.ui.learn.LearnScreen
import com.hillelsht.smart.ui.library.CategoryScreen
import com.hillelsht.smart.ui.library.LibraryScreen
import com.hillelsht.smart.ui.mascot.MascotHost
import com.hillelsht.smart.ui.play.PlayScreen
import com.hillelsht.smart.ui.play.chains.ChainsScreen
import com.hillelsht.smart.ui.play.climb.ClimbScreen
import com.hillelsht.smart.ui.play.gambit.ChessScreen
import com.hillelsht.smart.ui.play.palace.PalaceScreen
import com.hillelsht.smart.ui.quiz.QuizScreen
import com.hillelsht.smart.ui.review.ReviewScreen
import com.hillelsht.smart.ui.settings.SettingsScreen
import com.hillelsht.smart.ui.stats.StatsScreen
import com.hillelsht.smart.ui.watch.VideoPlayerScreen
import com.hillelsht.smart.ui.watch.WatchScreen

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val WATCH = "watch"
    const val PLAY = "play"
    const val STATS = "stats"
    const val LEARN = "learn"
    const val REVIEW = "review"
    const val QUIZ = "quiz?category={category}&facts={facts}"
    const val CATEGORY = "category/{categoryId}"
    const val PLAYER = "player/{videoId}"
    const val CLIMB = "play/climb"
    const val CHAINS = "play/chains"
    const val GAMBIT = "play/gambit"
    const val PALACE = "play/palace"
    const val SETTINGS = "settings"

    fun quiz(category: Category? = null, factIds: List<String> = emptyList()) =
        "quiz?category=${category?.id ?: ""}&facts=${factIds.joinToString(",")}"

    fun category(category: Category) = "category/${category.id}"
    fun player(videoId: String) = "player/$videoId"
}

// The label is a string resource id, not resolved text — this list is built once at file scope,
// outside any composition, and stringResource() only works from inside one.
private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.tab_today, Icons.Rounded.Home),
    Tab(Routes.LIBRARY, R.string.tab_read, Icons.Rounded.MenuBook),
    Tab(Routes.WATCH, R.string.tab_watch, Icons.Rounded.PlayCircle),
    Tab(Routes.PLAY, R.string.tab_play, Icons.Rounded.SportsEsports),
    Tab(Routes.STATS, R.string.tab_progress, Icons.Rounded.Insights),
    Tab(Routes.SETTINGS, R.string.tab_settings, Icons.Rounded.Settings),
)

@Composable
fun SmartApp(repository: SmartRepository) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The study flows take over the whole screen: a tab bar during a flashcard review is an
    // invitation to abandon the session halfway through.
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val destination = backStackEntry?.destination
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                        val label = stringResource(tab.labelRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = label) },
                            label = {
                                // Russian labels ("Настройки", "Прогресс") run noticeably
                                // wider than their English equivalents at this style's default
                                // size, and a wrapped second line changes this item's height
                                // relative to its neighbours — the bar visibly jumps as the
                                // wrapped and unwrapped items swap places while switching tabs.
                                // A smaller fixed size plus a hard single-line cap keeps every
                                // item's label the same height regardless of language.
                                Text(
                                    label,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp),
        ) {
            // Aryeh lives on the browsing tabs only. The study flows below hide the tab bar for
            // the same reason he is absent from them: a lion strolling past mid-flashcard would
            // undo exactly what that was for.
            val mascotSurface = when (currentRoute) {
                Routes.HOME -> Surface.TODAY
                Routes.LIBRARY -> Surface.LIBRARY
                Routes.WATCH -> Surface.WATCH
                // The picker only. Inside a run he would be a lion wandering across a fight.
                Routes.PLAY -> Surface.PLAY
                Routes.STATS -> Surface.PROGRESS
                Routes.SETTINGS -> Surface.SETTINGS
                else -> null
            }
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(160)) },
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        repository = repository,
                        onLearn = { navController.navigate(Routes.LEARN) },
                        onReview = { navController.navigate(Routes.REVIEW) },
                        onQuiz = { navController.navigate(Routes.quiz()) },
                        onBrowse = { navController.navigateToTab(Routes.LIBRARY) },
                        onWatch = { navController.navigateToTab(Routes.WATCH) },
                        onPlay = { navController.navigateToTab(Routes.PLAY) },
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        repository = repository,
                        onCategory = { navController.navigate(Routes.category(it)) },
                    )
                }
                composable(Routes.WATCH) {
                    WatchScreen(
                        repository = repository,
                        onPlay = { navController.navigate(Routes.player(it.id)) },
                    )
                }
                composable(Routes.PLAY) {
                    PlayScreen(
                        repository = repository,
                        onClimb = { navController.navigate(Routes.CLIMB) },
                        onChains = { navController.navigate(Routes.CHAINS) },
                        onGambit = { navController.navigate(Routes.GAMBIT) },
                        onPalace = { navController.navigate(Routes.PALACE) },
                        onQuiz = { navController.navigate(Routes.quiz()) },
                    )
                }
                composable(Routes.STATS) {
                    StatsScreen(repository = repository)
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen()
                }

                // Games take over the screen exactly as the study flows do — the tab bar is
                // hidden for them, so a run is somewhere you are rather than somewhere you are
                // passing through.
                composable(
                    Routes.CLIMB,
                    enterTransition = { slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(280)) },
                    exitTransition = { slideOutVertically(tween(220)) { it / 6 } + fadeOut(tween(220)) },
                ) {
                    ClimbScreen(repository = repository, onBack = { navController.popBackStack() })
                }
                composable(
                    Routes.CHAINS,
                    enterTransition = { slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(280)) },
                    exitTransition = { slideOutVertically(tween(220)) { it / 6 } + fadeOut(tween(220)) },
                ) {
                    ChainsScreen(repository = repository, onBack = { navController.popBackStack() })
                }
                composable(
                    Routes.GAMBIT,
                    enterTransition = { slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(280)) },
                    exitTransition = { slideOutVertically(tween(220)) { it / 6 } + fadeOut(tween(220)) },
                ) {
                    ChessScreen(repository = repository, onBack = { navController.popBackStack() })
                }
                composable(
                    Routes.PALACE,
                    enterTransition = { slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(280)) },
                    exitTransition = { slideOutVertically(tween(220)) { it / 6 } + fadeOut(tween(220)) },
                ) {
                    PalaceScreen(repository = repository, onBack = { navController.popBackStack() })
                }

                // Study flows slide up over the tabs, signalling "you are inside something".
                composable(
                    Routes.LEARN,
                    enterTransition = { slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(280)) },
                    exitTransition = { slideOutVertically(tween(220)) { it / 6 } + fadeOut(tween(220)) },
                ) {
                    LearnScreen(repository = repository, onDone = { navController.popBackStack() })
                }
                composable(
                    Routes.REVIEW,
                    enterTransition = { slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(280)) },
                    exitTransition = { slideOutVertically(tween(220)) { it / 6 } + fadeOut(tween(220)) },
                ) {
                    ReviewScreen(repository = repository, onDone = { navController.popBackStack() })
                }
                composable(Routes.QUIZ) { entry ->
                    val categoryId = entry.arguments?.getString("category").orEmpty()
                    val factIds = entry.arguments?.getString("facts").orEmpty()
                        .split(",").filter { it.isNotBlank() }
                    QuizScreen(
                        repository = repository,
                        category = Category.fromId(categoryId),
                        factIds = factIds,
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(Routes.PLAYER) { entry ->
                    VideoPlayerScreen(
                        repository = repository,
                        videoId = entry.arguments?.getString("videoId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onQuiz = { factIds, category ->
                            navController.navigate(Routes.quiz(category = category, factIds = factIds))
                        },
                    )
                }
                composable(Routes.CATEGORY) { entry ->
                    val categoryId = entry.arguments?.getString("categoryId").orEmpty()
                    CategoryScreen(
                        repository = repository,
                        category = Category.fromId(categoryId),
                        onBack = { navController.popBackStack() },
                        onQuiz = { navController.navigate(Routes.quiz(it)) },
                    )
                }
            }

            // Drawn over the tab content rather than inside it, so he is not clipped by a
            // screen's scroll and does not shift when a list grows. He sits above the tab bar.
            if (mascotSurface != null) {
                MascotHost(
                    surface = mascotSurface,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
