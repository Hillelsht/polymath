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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.ui.home.HomeScreen
import com.hillelsht.smart.ui.learn.LearnScreen
import com.hillelsht.smart.ui.library.CategoryScreen
import com.hillelsht.smart.ui.library.LibraryScreen
import com.hillelsht.smart.ui.quiz.QuizScreen
import com.hillelsht.smart.ui.review.ReviewScreen
import com.hillelsht.smart.ui.stats.StatsScreen

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val STATS = "stats"
    const val LEARN = "learn"
    const val REVIEW = "review"
    const val QUIZ = "quiz?category={category}"
    const val CATEGORY = "category/{categoryId}"

    fun quiz(category: Category? = null) = "quiz?category=${category?.id ?: ""}"
    fun category(category: Category) = "category/${category.id}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Today", Icons.Rounded.Home),
    Tab(Routes.LIBRARY, "Library", Icons.Rounded.MenuBook),
    Tab(Routes.STATS, "Progress", Icons.Rounded.Insights),
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
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
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
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        repository = repository,
                        onCategory = { navController.navigate(Routes.category(it)) },
                    )
                }
                composable(Routes.STATS) {
                    StatsScreen(repository = repository)
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
                    QuizScreen(
                        repository = repository,
                        category = Category.fromId(categoryId),
                        onDone = { navController.popBackStack() },
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
