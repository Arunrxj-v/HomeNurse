package com.homenurse.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.ai.ModelFailure
import com.homenurse.ai.ModelManifestEntry
import com.homenurse.ai.ModelStatus
import com.homenurse.core.model.DeviceCapabilityChecker
import com.homenurse.domain.repository.AuthState
import com.homenurse.ui.auth.AuthForm
import com.homenurse.ui.auth.LoginViewModel
import com.homenurse.ui.components.HomeNurseCard
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.LargeButton
import com.homenurse.ui.components.LargeOutlinedButton
import com.homenurse.ui.components.NoticeCard
import com.homenurse.ui.components.StatusChip
import com.homenurse.ui.components.StatusTone
import com.homenurse.ui.components.formatBytes
import com.homenurse.ui.theme.ChipCornerRadiusPercent
import com.homenurse.ui.theme.HomeNurseColors

/**
 * Onboarding: welcome → privacy → local AI → sign in / create account →
 * download HomeNurse AI from our own server → SHA-256 verification → install
 * → initialise + inference test → ready.
 *
 * Each claim shown here is backed by real state: the model step renders
 * [ModelStatus] straight from [com.homenurse.ai.ModelManager]. The user is
 * never asked for any third-party token — the model comes from the HomeNurse
 * model server after authentication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
    loginViewModel: LoginViewModel = viewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val downloadError by viewModel.downloadError.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(R.string.app_name),
                onBack = if (step != OnboardingStep.WELCOME) {
                    { viewModel.back() }
                } else {
                    null
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(onNext = { viewModel.next() })

                OnboardingStep.PRIVACY -> InfoStep(
                    icon = { Icon(Icons.Filled.PrivacyTip, null, modifier = Modifier.size(48.dp)) },
                    title = stringResource(R.string.onboarding_privacy_title),
                    body = stringResource(R.string.onboarding_privacy_body),
                    bullets = listOf(
                        stringResource(R.string.onboarding_privacy_bullet_1),
                        stringResource(R.string.onboarding_privacy_bullet_2),
                        stringResource(R.string.onboarding_privacy_bullet_3),
                    ),
                    onNext = { viewModel.next() },
                )

                OnboardingStep.LOCAL_AI -> InfoStep(
                    icon = { Icon(Icons.Filled.CloudOff, null, modifier = Modifier.size(48.dp)) },
                    title = stringResource(R.string.onboarding_local_title),
                    body = stringResource(R.string.onboarding_local_body),
                    bullets = listOf(
                        stringResource(R.string.onboarding_local_bullet_1),
                        stringResource(R.string.onboarding_local_bullet_2),
                        stringResource(R.string.onboarding_local_bullet_3),
                    ),
                    onNext = { viewModel.next() },
                )

                OnboardingStep.SIGN_IN -> SignInStep(
                    authState = authState,
                    loginViewModel = loginViewModel,
                    onAuthenticated = { viewModel.next() },
                )

                OnboardingStep.MODEL -> ModelStep(
                    model = viewModel.downloadableModel,
                    status = modelStatus,
                    error = downloadError,
                    onDownload = viewModel::startModelDownload,
                    onPause = viewModel::pauseModelDownload,
                    onRetry = viewModel::retryModel,
                    onNext = { viewModel.next() },
                )

                OnboardingStep.READY -> ReadyStep(
                    onDone = {
                        viewModel.finish()
                        onFinished()
                    },
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LargeButton(text = stringResource(R.string.onboarding_continue), onClick = onNext)
    }
}

@Composable
private fun InfoStep(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    bullets: List<String>,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        icon()
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(text = body, style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            bullets.forEach { bullet ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = bullet,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LargeButton(text = stringResource(R.string.onboarding_continue), onClick = onNext)
    }
}

/**
 * Real authentication: username/email + password or Continue with Google.
 * When a session already exists the step simply confirms it.
 */
@Composable
private fun SignInStep(
    authState: AuthState,
    loginViewModel: LoginViewModel,
    onAuthenticated: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.onboarding_signin_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_signin_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        when (authState) {
            is AuthState.SignedIn -> {
                NoticeCard(
                    text = stringResource(
                        R.string.onboarding_signed_in_as,
                        authState.account.displayName,
                    ),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                LargeButton(
                    text = stringResource(R.string.onboarding_continue),
                    onClick = onAuthenticated,
                )
            }
            AuthState.SignedOut -> AuthForm(
                viewModel = loginViewModel,
                onAuthenticated = onAuthenticated,
            )
        }
    }
}

/**
 * "Set up your private AI": the green/white HomeNurse AI card (private
 * on-device AI badge, real model facts — name, download size, required
 * storage, free space) with a stage checklist (download → verification →
 * initialization → ready), real download progress with pause/resume, and
 * the honest error handling from [ModelStatus].
 * No third-party token field exists here.
 */
@Composable
private fun ModelStep(
    model: ModelManifestEntry?,
    status: ModelStatus,
    error: String?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val freeStorage = remember(context) {
        DeviceCapabilityChecker.availableStorage(context)
    }
    val stages = stageSpec(status)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.onboarding_model_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_model_body),
            style = MaterialTheme.typography.bodyLarge,
        )

        // Green/white HomeNurse AI card.
        HomeNurseCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(HomeNurseColors.PrimaryLight, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = HomeNurseColors.Primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = stringResource(R.string.onboarding_model_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = HomeNurseColors.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    StatusChip(
                        text = stringResource(R.string.onboarding_model_badge),
                        tone = StatusTone.INFO,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.onboarding_model_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = HomeNurseColors.TextSecondary,
            )

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = HomeNurseColors.Border)

            ModelFactRow(
                label = stringResource(R.string.model_label_name),
                value = model?.displayName ?: stringResource(R.string.model_info_name),
            )
            if (model != null) {
                ModelFactRow(
                    label = stringResource(R.string.model_label_size),
                    value = formatBytes(model.sizeBytes),
                )
                // Model file + ~10% runtime-cache margin (DeviceCapabilityChecker).
                ModelFactRow(
                    label = stringResource(R.string.model_label_storage),
                    value = formatBytes(model.sizeBytes * 11 / 10),
                )
                ModelFactRow(
                    label = stringResource(R.string.model_label_free),
                    value = formatBytes(freeStorage),
                )
            }

            // Stage checklist: download → verification → initialization → ready.
            if (stages != null) {
                HorizontalDivider(color = HomeNurseColors.Border)
                stages.forEach { (labelRes, state) ->
                    ModelStageRow(label = stringResource(labelRes), state = state)
                }
            }

            // Download progress inside the card.
            val progress = status.downloadedProgress()
            if (progress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(ChipCornerRadiusPercent)),
                    color = HomeNurseColors.Primary,
                    trackColor = HomeNurseColors.Surface,
                    drawStopIndicator = {},
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (status is ModelStatus.Paused) {
                        stringResource(
                            R.string.onboarding_model_paused,
                            (progress * 100).toInt(),
                        )
                    } else {
                        stringResource(
                            R.string.model_state_downloading,
                            (progress * 100).toInt(),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = HomeNurseColors.TextSecondary,
                )
            }

            // Active verification / install / initialization caption.
            val activeCaption = when (status) {
                ModelStatus.Verifying -> stringResource(R.string.model_state_verifying)
                ModelStatus.Installing -> stringResource(R.string.model_state_installing)
                ModelStatus.Initializing -> stringResource(R.string.model_state_initializing)
                else -> null
            }
            if (activeCaption != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = HomeNurseColors.Primary,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = activeCaption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeNurseColors.TextSecondary,
                    )
                }
            }

            // Ready confirmation.
            if (status == ModelStatus.Ready || status is ModelStatus.UpdateAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = HomeNurseColors.Success,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.model_state_ready),
                        style = MaterialTheme.typography.titleSmall,
                        color = HomeNurseColors.OnInfoContainer,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.onboarding_model_test_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeNurseColors.TextSecondary,
                )
            }

            // Privacy footer.
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = HomeNurseColors.Border)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HomeNurseColors.InfoContainer)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.PrivacyTip,
                    contentDescription = null,
                    tint = HomeNurseColors.OnInfoContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_model_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeNurseColors.OnInfoContainer,
                )
            }
        }

        // Honest error handling.
        val errorMessage = when {
            status is ModelStatus.Failed -> modelErrorText(status.failure.name)
            status == ModelStatus.NotInstalled -> error?.let { modelErrorText(it) }
            else -> null
        }
        errorMessage?.let { NoticeCard(text = it) }

        when (status) {
            ModelStatus.NotInstalled -> LargeButton(
                text = stringResource(R.string.onboarding_model_download),
                onClick = onDownload,
            )

            is ModelStatus.Paused -> LargeButton(
                text = stringResource(R.string.onboarding_model_resume),
                onClick = onDownload,
            )

            is ModelStatus.Downloading -> LargeOutlinedButton(
                text = stringResource(R.string.onboarding_model_pause),
                onClick = onPause,
            )

            ModelStatus.Ready,
            is ModelStatus.UpdateAvailable,
            -> LargeButton(
                text = stringResource(R.string.onboarding_continue),
                onClick = onNext,
            )

            is ModelStatus.Failed -> LargeButton(
                text = stringResource(R.string.retry),
                onClick = onRetry,
            )

            else -> {}
        }
    }
}

/** Stage checklist state. */
private enum class StageState { PENDING, ACTIVE, DONE }

/** Stage label + state for the current [ModelStatus]; null while failed. */
private fun stageSpec(status: ModelStatus): List<Pair<Int, StageState>>? {
    val download = R.string.model_stage_download
    val verify = R.string.model_stage_verify
    val init = R.string.model_stage_init
    val ready = R.string.model_stage_ready
    val allPending = listOf(
        download to StageState.PENDING,
        verify to StageState.PENDING,
        init to StageState.PENDING,
        ready to StageState.PENDING,
    )
    return when (status) {
        ModelStatus.NotInstalled -> allPending

        is ModelStatus.Downloading, is ModelStatus.Paused -> listOf(
            download to StageState.ACTIVE,
            verify to StageState.PENDING,
            init to StageState.PENDING,
            ready to StageState.PENDING,
        )

        ModelStatus.Verifying -> listOf(
            download to StageState.DONE,
            verify to StageState.ACTIVE,
            init to StageState.PENDING,
            ready to StageState.PENDING,
        )

        ModelStatus.Installing -> listOf(
            download to StageState.DONE,
            verify to StageState.DONE,
            init to StageState.PENDING,
            ready to StageState.PENDING,
        )

        ModelStatus.Initializing -> listOf(
            download to StageState.DONE,
            verify to StageState.DONE,
            init to StageState.ACTIVE,
            ready to StageState.PENDING,
        )

        ModelStatus.Ready,
        is ModelStatus.UpdateAvailable,
        -> listOf(
            download to StageState.DONE,
            verify to StageState.DONE,
            init to StageState.DONE,
            ready to StageState.DONE,
        )

        is ModelStatus.Failed -> null
    }
}

/** Download progress for in-flight states, else null. */
private fun ModelStatus.downloadedProgress(): Float? = when (this) {
    is ModelStatus.Downloading ->
        if (totalBytes > 0) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    is ModelStatus.Paused ->
        if (totalBytes > 0) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    else -> null
}

@Composable
private fun ModelFactRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = HomeNurseColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = HomeNurseColors.TextPrimary,
        )
    }
}

@Composable
private fun ModelStageRow(label: String, state: StageState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        when (state) {
            StageState.DONE -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = HomeNurseColors.Success,
                modifier = Modifier.size(22.dp),
            )

            StageState.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = HomeNurseColors.Primary,
            )

            StageState.PENDING -> Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(2.dp, HomeNurseColors.Border, CircleShape),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (state == StageState.PENDING) {
                HomeNurseColors.TextSecondary
            } else {
                HomeNurseColors.TextPrimary
            },
            modifier = Modifier.weight(1f),
        )
        if (state == StageState.PENDING) {
            Text(
                text = stringResource(R.string.model_stage_pending),
                style = MaterialTheme.typography.labelSmall,
                color = HomeNurseColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun StepStatus(textRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ReadyStep(onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.onboarding_ready_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_ready_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LargeButton(text = stringResource(R.string.onboarding_ready_button), onClick = onDone)
    }
}

@Composable
private fun modelErrorText(failureName: String): String = when (
    runCatching { ModelFailure.valueOf(failureName) }.getOrNull()
) {
    ModelFailure.NETWORK -> stringResource(R.string.model_error_network)
    ModelFailure.UNAUTHORIZED -> stringResource(R.string.model_error_unauthorized)
    ModelFailure.HTTP -> stringResource(R.string.model_error_http)
    ModelFailure.CHECKSUM_MISMATCH -> stringResource(R.string.model_error_checksum)
    ModelFailure.INSUFFICIENT_STORAGE -> stringResource(R.string.model_error_storage)
    ModelFailure.UNSUPPORTED_DEVICE -> stringResource(R.string.model_error_device)
    ModelFailure.MANIFEST -> stringResource(R.string.model_error_manifest)
    ModelFailure.INITIALIZATION -> stringResource(R.string.model_error_init)
    null, ModelFailure.UNKNOWN -> stringResource(R.string.model_error_unknown)
}
