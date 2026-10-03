package com.homenurse.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.ai.ModelFailure
import com.homenurse.ai.ModelStatus
import com.homenurse.domain.repository.Account
import com.homenurse.domain.repository.AuthState
import com.homenurse.ui.auth.authErrorText
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.LargeButton
import com.homenurse.ui.components.LargeOutlinedButton
import com.homenurse.ui.components.NoticeCard
import com.homenurse.ui.components.SectionCard
import com.homenurse.ui.components.formatBytes
import java.io.File

/**
 * Settings — every control is real: account/session actions (sign out,
 * delete account), model status/version + deletion, storage usage computed
 * from actual files, reminder toggle, JSON export (share sheet), true
 * delete-all-medical-data (files + rows + key), license notices, privacy info.
 *
 * Privacy distinction made explicit here: signing out or deleting the account
 * never deletes medical data; deleting medical data never touches the account.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenLicenses: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val modelStatusRaw by viewModel.modelStatus.collectAsStateWithLifecycle()
    val modelStatus = modelStatusRaw
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val remindersEnabled by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    val dataDeleted by viewModel.dataDeleted.collectAsStateWithLifecycle()
    val accountMessage by viewModel.accountActionMessage.collectAsStateWithLifecycle()

    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var deleteAccountPassword by remember { mutableStateOf("") }
    var deleteAccountFailed by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<Int?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setRemindersEnabled(granted)
    }

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- account (authentication only) ------------------------------
            SectionCard {
                Text(
                    text = stringResource(R.string.settings_account_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                when (val state = authState) {
                    is AuthState.SignedIn -> {
                        val account: Account = state.account
                        Text(
                            text = stringResource(
                                R.string.settings_account_signed_in,
                                account.displayName,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (account.email.isNotBlank()) {
                            Text(
                                text = stringResource(
                                    R.string.settings_account_email,
                                    account.email,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = stringResource(
                                if (account.provider == "google") {
                                    R.string.settings_account_provider_google
                                } else {
                                    R.string.settings_account_provider_local
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LargeOutlinedButton(
                            text = stringResource(R.string.settings_sign_out),
                            onClick = { showSignOutDialog = true },
                        )
                        NoticeCard(
                            text = stringResource(R.string.settings_delete_account_explain),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LargeOutlinedButton(
                            text = stringResource(R.string.settings_delete_account),
                            onClick = {
                                deleteAccountPassword = ""
                                deleteAccountFailed = false
                                showDeleteAccountDialog = true
                            },
                        )
                    }
                    AuthState.SignedOut -> {
                        Text(
                            text = stringResource(R.string.settings_account_not_signed_in),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                accountMessage?.let { message ->
                    Text(
                        text = stringResource(message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    viewModel.consumeAccountMessage()
                }
            }

            // --- local AI model ------------------------------------------------
            SectionCard {
                Text(
                    text = stringResource(R.string.settings_model_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when (modelStatus) {
                        ModelStatus.Ready -> stringResource(R.string.model_state_ready)
                        is ModelStatus.UpdateAvailable ->
                            stringResource(R.string.model_update_available, modelStatus.availableVersion)
                        is ModelStatus.Downloading -> stringResource(R.string.model_state_downloading_short)
                        is ModelStatus.Paused -> stringResource(R.string.model_state_paused)
                        ModelStatus.Verifying -> stringResource(R.string.model_state_verifying)
                        ModelStatus.Installing -> stringResource(R.string.model_state_installing)
                        ModelStatus.Initializing -> stringResource(R.string.model_state_initializing)
                        ModelStatus.NotInstalled -> stringResource(R.string.home_ai_missing)
                        is ModelStatus.Failed -> stringResource(
                            when (modelStatus.failure) {
                                ModelFailure.CHECKSUM_MISMATCH -> R.string.model_error_checksum
                                ModelFailure.MANIFEST -> R.string.model_error_manifest
                                ModelFailure.UNAUTHORIZED -> R.string.model_error_unauthorized
                                ModelFailure.INITIALIZATION -> R.string.model_error_init
                                else -> R.string.model_error_unknown
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                viewModel.installedVersion?.let {
                    Text(
                        text = stringResource(R.string.settings_model_version, it),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (modelStatus is ModelStatus.Failed &&
                    modelStatus.failure == ModelFailure.INITIALIZATION
                ) {
                    LargeOutlinedButton(
                        text = stringResource(R.string.retry),
                        onClick = viewModel::retryModelInit,
                    )
                }

                if (modelStatus is ModelStatus.Paused) {
                    LargeOutlinedButton(
                        text = stringResource(R.string.model_resume_download),
                        onClick = viewModel::resumeModelDownload,
                    )
                }

                LargeOutlinedButton(
                    text = stringResource(R.string.settings_delete_model),
                    onClick = viewModel::deleteModel,
                )
            }

            // --- storage -------------------------------------------------------
            SectionCard {
                Text(
                    text = stringResource(R.string.settings_storage_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.settings_storage_documents,
                        formatBytes(storage.documentBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.settings_storage_model,
                        formatBytes(storage.modelBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.settings_storage_total,
                        formatBytes(storage.documentBytes + storage.modelBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // --- notifications -------------------------------------------------
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.settings_notifications),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = remindersEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher
                                    .launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setRemindersEnabled(enabled)
                            }
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.settings_notifications_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- privacy -------------------------------------------------------
            SectionCard {
                Text(
                    text = stringResource(R.string.settings_privacy_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_privacy_stays_device),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_privacy_no_upload),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_privacy_offline),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // --- data ----------------------------------------------------------
            SectionCard {
                Text(
                    text = stringResource(R.string.settings_data_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                LargeOutlinedButton(
                    text = stringResource(R.string.settings_export),
                    onClick = {
                        viewModel.export { json ->
                            if (json.isNotEmpty()) {
                                shareExport(context, json)
                                exportMessage = R.string.settings_export_done
                            } else {
                                exportMessage = R.string.error_unknown
                            }
                        }
                    },
                )
                exportMessage?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                LargeOutlinedButton(
                    text = stringResource(R.string.settings_delete_all),
                    onClick = { showDeleteAllDialog = true },
                )
                if (dataDeleted) {
                    Text(
                        text = stringResource(R.string.settings_delete_all_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    viewModel.consumeDeleted()
                }
            }

            // --- licenses ------------------------------------------------------
            SectionCard {
                Text(
                    text = stringResource(R.string.settings_licenses),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_licenses_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LargeOutlinedButton(
                    text = stringResource(R.string.settings_licenses),
                    onClick = onOpenLicenses,
                )
            }

            // --- about ---------------------------------------------------------
            SectionCard {
                Text(
                    text = stringResource(R.string.settings_about_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_about_body, viewModel.appVersion),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            icon = {
                Icon(Icons.Filled.DeleteForever, contentDescription = null)
            },
            title = { Text(stringResource(R.string.settings_delete_all_title)) },
            text = { Text(stringResource(R.string.settings_delete_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllMedicalData()
                        showDeleteAllDialog = false
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        viewModel.signOut(onSignedOut)
                    },
                ) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text(stringResource(R.string.settings_delete_account_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_delete_account_message))
                    OutlinedTextField(
                        value = deleteAccountPassword,
                        onValueChange = {
                            deleteAccountPassword = it
                            deleteAccountFailed = false
                        },
                        label = {
                            Text(stringResource(R.string.settings_delete_account_password))
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (deleteAccountFailed) {
                        Text(
                            text = stringResource(R.string.auth_error_invalid_credentials),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAccount(
                            password = deleteAccountPassword.ifBlank { null },
                        ) { success ->
                            if (success) {
                                showDeleteAccountDialog = false
                            } else {
                                deleteAccountFailed = true
                            }
                        }
                    },
                ) { Text(stringResource(R.string.settings_delete_account)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun shareExport(context: android.content.Context, json: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "homenurse-export.json")
    runCatching { file.writeText(json) }.onFailure { return }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
