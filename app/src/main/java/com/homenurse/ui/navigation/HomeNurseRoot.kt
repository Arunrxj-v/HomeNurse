package com.homenurse.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Send as SendOutlined
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.outlined.CalendarMonth as CalendarMonthOutlined
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileUpload as FileUploadOutlined
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.homenurse.R
import com.homenurse.domain.repository.AuthState
import com.homenurse.ui.auth.LoginScreen
import com.homenurse.ui.careplan.CarePlanScreen
import com.homenurse.ui.careplan.PlanDetailScreen
import com.homenurse.ui.chat.ChatScreen
import com.homenurse.ui.components.BottomTab
import com.homenurse.ui.components.HomeNurseBottomNavigation
import com.homenurse.ui.documents.CaptureScreen
import com.homenurse.ui.documents.DocumentDetailScreen
import com.homenurse.ui.documents.DocumentsScreen
import com.homenurse.ui.documents.FactReviewScreen
import com.homenurse.ui.home.HomeScreen
import com.homenurse.ui.medicines.MedicinesScreen
import com.homenurse.ui.onboarding.OnboardingScreen
import com.homenurse.ui.settings.LicensesScreen
import com.homenurse.ui.settings.SettingsScreen

object Routes {
    const val LOGIN = "login"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DOCUMENTS = "documents"
    const val CAPTURE = "capture"
    const val DOCUMENT = "document/{documentId}"
    const val REVIEW = "review/{documentId}"
    const val MEDICINES = "medicines"
    const val CARE_PLAN = "careplan"
    const val PLAN_DETAIL = "plan/{taskId}"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val LICENSES = "licenses"

    fun document(id: String) = "document/$id"
    fun review(id: String) = "review/$id"
    fun planStep(id: String) = "plan/$id"
}

/**
 * App shell: authentication gate + onboarding gate + the four main
 * destinations (Home / Plan / Ask / Upload) with a bottom bar and stack
 * screens for capture / detail / review / medicines / settings / licenses.
 *
 * Session gating: whenever the auth state becomes [AuthState.SignedOut]
 * (sign out, deleted account, or a refresh token the server rejected) the
 * user returns to the login route. Medical screens themselves are only
 * gated by navigation — medical data never leaves the device either way.
 */
@Composable
fun HomeNurseRoot(
    startDestination: String,
    authState: AuthState,
    onboardingCompleted: Boolean,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val bottomTabs = listOf(
        BottomTab(
            route = Routes.HOME,
            label = stringResource(R.string.nav_home),
            icon = Icons.Outlined.FavoriteBorder,
            selectedIcon = Icons.Filled.Favorite,
        ),
        BottomTab(
            route = Routes.CARE_PLAN,
            label = stringResource(R.string.nav_plan),
            icon = Icons.Outlined.CalendarMonthOutlined,
            selectedIcon = Icons.Filled.CalendarMonth,
        ),
        BottomTab(
            route = Routes.CHAT,
            label = stringResource(R.string.nav_chat),
            icon = Icons.AutoMirrored.Outlined.SendOutlined,
            selectedIcon = Icons.AutoMirrored.Filled.Send,
        ),
        BottomTab(
            route = Routes.DOCUMENTS,
            label = stringResource(R.string.nav_upload),
            icon = Icons.Outlined.FileUploadOutlined,
            selectedIcon = Icons.Filled.FileUpload,
        ),
    )

    val showBottomBar = bottomTabs.any { it.route == currentRoute }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Return to login when there is no valid session any more.
    LaunchedEffect(authState) {
        if (authState is AuthState.SignedOut &&
            currentRoute != null &&
            currentRoute != Routes.LOGIN &&
            currentRoute != Routes.ONBOARDING
        ) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                HomeNurseBottomNavigation(
                    tabs = bottomTabs,
                    currentRoute = currentRoute,
                    onSelect = { navigateTo(it) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onAuthenticated = {
                        val next = if (onboardingCompleted) Routes.HOME else Routes.ONBOARDING
                        navController.navigate(next) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    onOpenDocuments = { navigateTo(Routes.DOCUMENTS) },
                    onAddDocument = { navController.navigate(Routes.CAPTURE) },
                    onOpenChat = { navigateTo(Routes.CHAT) },
                    onOpenMedicines = { navigateTo(Routes.MEDICINES) },
                    onOpenCarePlan = { navigateTo(Routes.CARE_PLAN) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenDocument = { id -> navController.navigate(Routes.document(id)) },
                    onOpenStep = { id -> navController.navigate(Routes.planStep(id)) },
                )
            }

            composable(Routes.DOCUMENTS) {
                DocumentsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCapture = { navController.navigate(Routes.CAPTURE) },
                    onOpenDocument = { id -> navController.navigate(Routes.document(id)) },
                )
            }

            composable(Routes.CAPTURE) {
                CaptureScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.DOCUMENT,
                arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
            ) { entry ->
                val documentId = entry.arguments?.getString("documentId").orEmpty()
                DocumentDetailScreen(
                    documentId = documentId,
                    onBack = { navController.popBackStack() },
                    onReviewFacts = { navController.navigate(Routes.review(documentId)) },
                )
            }

            composable(
                route = Routes.REVIEW,
                arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
            ) { entry ->
                FactReviewScreen(
                    documentId = entry.arguments?.getString("documentId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.MEDICINES) {
                MedicinesScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.CARE_PLAN) {
                CarePlanScreen(
                    onBack = { navController.popBackStack() },
                    onOpenStep = { id -> navController.navigate(Routes.planStep(id)) },
                )
            }

            composable(
                route = Routes.PLAN_DETAIL,
                arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
            ) { entry ->
                PlanDetailScreen(
                    taskId = entry.arguments?.getString("taskId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.CHAT) {
                ChatScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onSignedOut = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenLicenses = { navController.navigate(Routes.LICENSES) },
                )
            }

            composable(Routes.LICENSES) {
                LicensesScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
