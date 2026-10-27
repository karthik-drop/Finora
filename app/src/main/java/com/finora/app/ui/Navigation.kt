package com.finora.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.finora.app.AppContainer
import com.finora.app.FinoraApp
import com.finora.app.ui.analytics.AnalyticsScreen
import com.finora.app.ui.documents.DocumentDetailScreen
import com.finora.app.ui.documents.DocumentsScreen
import com.finora.app.ui.home.HomeScreen
import com.finora.app.ui.invest.InvestmentDetailScreen
import com.finora.app.ui.invest.InvestmentEditScreen
import com.finora.app.ui.invest.InvestmentsScreen
import com.finora.app.ui.invest.PortfolioUpdateScreen
import com.finora.app.ui.loans.LoanDetailScreen
import com.finora.app.ui.loans.LoanEditScreen
import com.finora.app.ui.loans.LoansScreen
import com.finora.app.ui.more.MoreScreen
import com.finora.app.ui.review.ReviewScreen
import com.finora.app.ui.settings.AccountsScreen
import com.finora.app.ui.settings.BackupScreen
import com.finora.app.ui.settings.CategoriesScreen
import com.finora.app.ui.settings.MerchantsScreen
import com.finora.app.ui.settings.PeopleScreen
import com.finora.app.ui.settings.PrivacyScreen
import com.finora.app.ui.settings.RecurringBillsScreen
import com.finora.app.ui.settings.SettingsScreen
import com.finora.app.ui.split.SplitCreateScreen
import com.finora.app.ui.split.SplitDetailScreen
import com.finora.app.ui.split.SplitsScreen
import com.finora.app.ui.transactions.AddTransactionScreen
import com.finora.app.ui.transactions.TransactionDetailScreen
import com.finora.app.ui.transactions.TransactionsScreen

/** ViewModels get the container through their factory, never by reaching for a global. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as FinoraApp
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app.container) } })
}

object Routes {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val SPLIT = "split"
    const val LOANS = "loans"
    const val INVEST = "invest"
    const val ANALYTICS = "analytics"
    const val DOCUMENTS = "documents"
    const val SETTINGS = "settings"
    const val MORE = "more"
    const val REVIEW = "review"
    const val PORTFOLIO_UPDATE = "portfolio_update"
    const val ADD_TXN = "txn/new"
    fun txn(id: Long) = "txn/$id"
    const val SPLIT_NEW = "split/new"
    fun split(id: Long) = "split/$id"
    const val LOAN_NEW = "loan/new"
    fun loan(id: Long) = "loan/$id"
    fun loanEdit(id: Long) = "loan/$id/edit"
    const val INVEST_NEW = "invest/new"
    fun investment(id: Long) = "invest/$id"
    fun document(id: Long) = "document/$id"
    const val MERCHANTS = "settings/merchants"
    const val CATEGORIES = "settings/categories"
    const val PEOPLE = "settings/people"
    const val ACCOUNTS = "settings/accounts"
    const val PRIVACY = "settings/privacy"
    const val BACKUP = "settings/backup"
    const val RECURRING = "settings/recurring"
}

private data class TopDest(val route: String, val label: String, val icon: ImageVector)

private val phoneDestinations = listOf(
    TopDest(Routes.HOME, "Home", Icons.Filled.Home),
    TopDest(Routes.TRANSACTIONS, "Activity", Icons.AutoMirrored.Filled.ReceiptLong),
    TopDest(Routes.SPLIT, "Split", Icons.Filled.CallSplit),
    TopDest(Routes.INVEST, "Invest", Icons.Filled.ShowChart),
    TopDest(Routes.MORE, "More", Icons.Filled.MoreHoriz),
)

/** On a tablet or landscape there is room for all eight sections on a rail. */
private val railDestinations = listOf(
    TopDest(Routes.HOME, "Home", Icons.Filled.Home),
    TopDest(Routes.TRANSACTIONS, "Activity", Icons.AutoMirrored.Filled.ReceiptLong),
    TopDest(Routes.SPLIT, "Split", Icons.Filled.CallSplit),
    TopDest(Routes.LOANS, "Loans", Icons.Filled.AccountBalanceWallet),
    TopDest(Routes.INVEST, "Invest", Icons.Filled.ShowChart),
    TopDest(Routes.ANALYTICS, "Analytics", Icons.Filled.Insights),
    TopDest(Routes.DOCUMENTS, "Docs", Icons.Filled.Folder),
    TopDest(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

private val moreChildren = setOf(Routes.LOANS, Routes.ANALYTICS, Routes.DOCUMENTS, Routes.SETTINGS)

@Composable
fun FinoraNavHost(startRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as FinoraApp).container
    val reviewCount by container.db.transactions().observeNeedsReviewCount().collectAsStateWithLifecycle(0)

    LaunchedEffect(startRoute) {
        if (startRoute != null) {
            nav.navigate(startRoute) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

    val dests = if (wide) railDestinations else phoneDestinations
    val showBar = current in dests.map { it.route } || (!wide && current in moreChildren)

    fun go(route: String) = nav.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun selected(route: String) = current == route || (!wide && route == Routes.MORE && current in moreChildren)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!wide && showBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                dests.forEach { d ->
                    NavigationBarItem(
                        selected = selected(d.route), onClick = { go(d.route) },
                        icon = { BadgedIcon(d, if (d.route == Routes.TRANSACTIONS) reviewCount else 0) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                dests.forEach { d ->
                    NavigationRailItem(selected = selected(d.route), onClick = { go(d.route) }, icon = { BadgedIcon(d, if (d.route == Routes.TRANSACTIONS) reviewCount else 0) }, label = { Text(d.label) })
                }
            }
            NavHost(
                navController = nav, startDestination = Routes.HOME, modifier = Modifier.weight(1f),
                enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 } },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 } },
            ) { graph(nav) }
        }
    }
}

@Composable
private fun BadgedIcon(d: TopDest, count: Int) {
    if (count > 0) BadgedBox(badge = { Badge { Text(if (count > 99) "99+" else count.toString()) } }) { Icon(d.icon, d.label) }
    else Icon(d.icon, d.label)
}

private fun NavGraphBuilder.graph(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    val idArg = listOf(navArgument("id") { type = NavType.LongType })
    fun open(route: String) { nav.navigate(route) }

    composable(Routes.HOME) { HomeScreen(::open) }
    composable(Routes.TRANSACTIONS) { TransactionsScreen(::open) }
    composable(Routes.REVIEW) { ReviewScreen(back, ::open) }
    composable(Routes.ADD_TXN) { AddTransactionScreen(back) }
    composable("txn/{id}", idArg) { TransactionDetailScreen(it.arguments!!.getLong("id"), back, ::open) }

    composable(Routes.SPLIT) { SplitsScreen(::open) }
    composable(Routes.SPLIT_NEW) { SplitCreateScreen(back) { id -> nav.popBackStack(); open(Routes.split(id)) } }
    composable("split/{id}", idArg) { SplitDetailScreen(it.arguments!!.getLong("id"), back) }

    composable(Routes.LOANS) { LoansScreen(::open, if (nav.previousBackStackEntry != null) back else null) }
    composable(Routes.LOAN_NEW) { LoanEditScreen(null, back) { id -> nav.popBackStack(); open(Routes.loan(id)) } }
    composable("loan/{id}", idArg) { LoanDetailScreen(it.arguments!!.getLong("id"), back, ::open) }
    composable("loan/{id}/edit", idArg) { LoanEditScreen(it.arguments!!.getLong("id"), back) { nav.popBackStack() } }

    composable(Routes.INVEST) { InvestmentsScreen(::open) }
    composable(Routes.INVEST_NEW) { InvestmentEditScreen(back) { id -> nav.popBackStack(); open(Routes.investment(id)) } }
    composable("invest/{id}", idArg) { InvestmentDetailScreen(it.arguments!!.getLong("id"), back) }
    composable(Routes.PORTFOLIO_UPDATE) { PortfolioUpdateScreen(back) }

    composable(Routes.ANALYTICS) { AnalyticsScreen(if (nav.previousBackStackEntry != null) back else null) }
    composable(Routes.DOCUMENTS) { DocumentsScreen(::open, if (nav.previousBackStackEntry != null) back else null) }
    composable("document/{id}", idArg) { DocumentDetailScreen(it.arguments!!.getLong("id"), back) }

    composable(Routes.MORE) { MoreScreen(::open) }
    composable(Routes.SETTINGS) { SettingsScreen(::open, if (nav.previousBackStackEntry != null) back else null) }
    composable(Routes.MERCHANTS) { MerchantsScreen(back) }
    composable(Routes.CATEGORIES) { CategoriesScreen(back) }
    composable(Routes.PEOPLE) { PeopleScreen(back) }
    composable(Routes.ACCOUNTS) { AccountsScreen(back) }
    composable(Routes.PRIVACY) { PrivacyScreen(back) }
    composable(Routes.BACKUP) { BackupScreen(back) }
    composable(Routes.RECURRING) { RecurringBillsScreen(back) }
}
