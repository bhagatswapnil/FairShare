package com.splitexpense.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.*
import androidx.navigation.compose.*
import com.splitexpense.ui.screens.expense.AddExpenseScreen
import com.splitexpense.ui.screens.group.GroupScreen
import com.splitexpense.ui.screens.home.HomeScreen
import com.splitexpense.ui.screens.scan.ScanBillScreen
import com.splitexpense.ui.screens.splash.SplashScreen

object Routes {
    const val SPLASH       = "splash"
    const val HOME         = "home"
    const val GROUP        = "group/{groupId}"
    const val ADD_EXPENSE  = "add_expense/{groupId}"
    const val EDIT_EXPENSE = "edit_expense/{groupId}/{expenseId}"
    const val SCAN_BILL    = "scan_bill/{groupId}"

    fun group(groupId: String)                          = "group/$groupId"
    fun addExpense(groupId: String)                     = "add_expense/$groupId"
    fun editExpense(groupId: String, expenseId: String) = "edit_expense/$groupId/$expenseId"
    fun scanBill(groupId: String)                       = "scan_bill/$groupId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        enterTransition  = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left,  tween(300)) },
        exitTransition   = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
        popEnterTransition  = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) },
        popExitTransition   = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
    ) {

        // Splash — no slide transitions, just fade
        composable(
            route = Routes.SPLASH,
            enterTransition = { EnterTransition.None },
            exitTransition  = { fadeOut(tween(400)) }
        ) {
            SplashScreen(
                onComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onGroupClick = { groupId -> navController.navigate(Routes.group(groupId)) }
            )
        }

        composable(
            route = Routes.GROUP,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStack ->
            val groupId = backStack.arguments?.getString("groupId") ?: return@composable
            GroupScreen(
                groupId       = groupId,
                onBack        = { navController.popBackStack() },
                onAddExpense  = { navController.navigate(Routes.addExpense(groupId)) },
                onScanBill    = { navController.navigate(Routes.scanBill(groupId)) },
                onEditExpense = { expenseId -> navController.navigate(Routes.editExpense(groupId, expenseId)) }
            )
        }

        composable(
            route = Routes.ADD_EXPENSE,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStack ->
            val groupId = backStack.arguments?.getString("groupId") ?: return@composable
            AddExpenseScreen(
                groupId = groupId,
                onBack  = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.SCAN_BILL,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStack ->
            val groupId = backStack.arguments?.getString("groupId") ?: return@composable
            ScanBillScreen(
                groupId = groupId,
                onBack  = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.EDIT_EXPENSE,
            arguments = listOf(
                navArgument("groupId")   { type = NavType.StringType },
                navArgument("expenseId") { type = NavType.StringType }
            )
        ) { backStack ->
            val groupId   = backStack.arguments?.getString("groupId")   ?: return@composable
            val expenseId = backStack.arguments?.getString("expenseId") ?: return@composable
            AddExpenseScreen(
                groupId   = groupId,
                expenseId = expenseId,
                onBack    = { navController.popBackStack() }
            )
        }
    }
}
