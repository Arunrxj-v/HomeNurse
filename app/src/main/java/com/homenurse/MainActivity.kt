package com.homenurse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homenurse.ui.navigation.HomeNurseRoot
import com.homenurse.ui.navigation.Routes
import com.homenurse.ui.onboarding.OnboardingViewModel
import com.homenurse.ui.theme.HomeNurseTheme

/**
 * Single activity. [WindowManager.LayoutParams.FLAG_SECURE] is always on:
 * medical content must not appear in screenshots, recents previews or screen
 * recordings (documented privacy decision). Also requests the notification
 * permission needed for local care reminders (Android 13+).
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Result is read by the settings/reminders toggle itself.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        // Light-first identity: dark system icons on the light app background,
        // regardless of the system dark-mode setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )

        val container = (application as HomeNurseApp).container
        val onboardingDone =
            container.secureStorage.getString(OnboardingViewModel.KEY_COMPLETED) == "true"
        // Start destination: onboarding first, otherwise the existing session
        // decides between auto-login (HOME) and the login screen.
        val startDestination = when {
            !onboardingDone -> Routes.ONBOARDING
            container.sessionManager.hasSession() -> Routes.HOME
            else -> Routes.LOGIN
        }

        // POST_NOTIFICATIONS exists on Android 13+ only; below that the
        // request would be a pointless no-op.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val authState by container.authRepository.state.collectAsStateWithLifecycle()
            HomeNurseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeNurseRoot(
                        startDestination = startDestination,
                        authState = authState,
                        onboardingCompleted = onboardingDone,
                    )
                }
            }
        }
    }
}
