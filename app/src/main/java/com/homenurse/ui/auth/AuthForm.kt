package com.homenurse.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homenurse.R
import com.homenurse.domain.repository.AuthError
import com.homenurse.ui.components.LargeButton
import com.homenurse.ui.components.LargeOutlinedButton
import com.homenurse.ui.components.NoticeCard

/**
 * The complete authentication form: username/email + password, sign in,
 * create account, forgot/reset password and "Continue with Google"
 * (Credential Manager → Google ID token → HomeNurse backend).
 *
 * Password fields are masked; credentials only ever travel to the HomeNurse
 * auth API. This composable is reused by the standalone login route and by
 * the onboarding sign-in step.
 */
@Composable
fun AuthForm(
    viewModel: LoginViewModel,
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val identifier by viewModel.identifier.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val email by viewModel.email.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val resetEmail by viewModel.resetEmail.collectAsStateWithLifecycle()
    val resetCode by viewModel.resetCode.collectAsStateWithLifecycle()
    val newPassword by viewModel.newPassword.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (mode) {
            AuthMode.SIGN_IN -> {
                Text(
                    text = stringResource(R.string.auth_signin_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = identifier,
                    onValueChange = viewModel::onIdentifierChange,
                    label = { Text(stringResource(R.string.auth_username_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = password,
                    onValueChange = viewModel::onPasswordChange,
                    label = stringResource(R.string.auth_password_label),
                    enabled = !busy,
                )
                AuthMessages(error = error, notice = notice, busy = busy)
                LargeButton(
                    text = stringResource(R.string.auth_sign_in_button),
                    enabled = !busy && identifier.isNotBlank() && password.isNotEmpty(),
                    onClick = { viewModel.signIn(onAuthenticated) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = { viewModel.setMode(AuthMode.FORGOT) }) {
                        Text(stringResource(R.string.auth_forgot_password))
                    }
                }

                OrDivider()

                LargeOutlinedButton(
                    text = stringResource(R.string.auth_google_button),
                    enabled = !busy,
                    onClick = { viewModel.signInWithGoogle(onAuthenticated) },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.auth_no_account),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { viewModel.setMode(AuthMode.REGISTER) }) {
                        Text(stringResource(R.string.auth_create_account_link))
                    }
                }
            }

            AuthMode.REGISTER -> {
                Text(
                    text = stringResource(R.string.auth_register_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.auth_register_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text(stringResource(R.string.auth_register_username_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text(stringResource(R.string.auth_email_label)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = password,
                    onValueChange = viewModel::onPasswordChange,
                    label = stringResource(R.string.auth_password_label),
                    helper = stringResource(R.string.auth_password_hint),
                    enabled = !busy,
                )
                AuthMessages(error = error, notice = notice, busy = busy)
                LargeButton(
                    text = stringResource(R.string.auth_register_button),
                    enabled = !busy &&
                        username.isNotBlank() && email.isNotBlank() && password.isNotEmpty(),
                    onClick = { viewModel.register(onAuthenticated) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.auth_have_account),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { viewModel.setMode(AuthMode.SIGN_IN) }) {
                        Text(stringResource(R.string.auth_signin_link))
                    }
                }
            }

            AuthMode.FORGOT -> {
                Text(
                    text = stringResource(R.string.auth_forgot_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (notice == AuthNotice.RESET_CODE_SENT) {
                    Text(
                        text = stringResource(R.string.auth_forgot_sent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = resetCode,
                        onValueChange = viewModel::onResetCodeChange,
                        label = { Text(stringResource(R.string.auth_reset_code_label)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordField(
                        value = newPassword,
                        onValueChange = viewModel::onNewPasswordChange,
                        label = stringResource(R.string.auth_new_password_label),
                        helper = stringResource(R.string.auth_password_hint),
                        enabled = !busy,
                    )
                    AuthMessages(error = error, notice = null, busy = busy)
                    LargeButton(
                        text = stringResource(R.string.auth_reset_button),
                        enabled = !busy && resetCode.isNotBlank() && newPassword.isNotEmpty(),
                        onClick = viewModel::completeReset,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.auth_forgot_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = viewModel::onResetEmailChange,
                        label = { Text(stringResource(R.string.auth_forgot_email_label)) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AuthMessages(error = error, notice = null, busy = busy)
                    LargeButton(
                        text = stringResource(R.string.auth_forgot_send),
                        enabled = !busy && resetEmail.isNotBlank(),
                        onClick = viewModel::sendResetCode,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = { viewModel.setMode(AuthMode.SIGN_IN) }) {
                        Text(stringResource(R.string.auth_signin_link))
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.auth_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    helper: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = helper?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.auth_or),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AuthMessages(error: AuthError?, notice: AuthNotice?, busy: Boolean) {
    if (busy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.auth_signing_in),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
    error?.let {
        NoticeCard(text = authErrorText(it))
    }
    if (notice == AuthNotice.RESET_COMPLETED) {
        NoticeCard(
            text = stringResource(R.string.auth_reset_done),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Maps a normalised auth failure to user-facing copy (never leaks server internals). */
@Composable
fun authErrorText(error: AuthError): String = when (error) {
    AuthError.INVALID_CREDENTIALS -> stringResource(R.string.auth_error_invalid_credentials)
    AuthError.USERNAME_TAKEN -> stringResource(R.string.auth_error_username_taken)
    AuthError.EMAIL_TAKEN -> stringResource(R.string.auth_error_email_taken)
    AuthError.WEAK_PASSWORD -> stringResource(R.string.auth_error_weak_password)
    AuthError.INVALID_INPUT -> stringResource(R.string.auth_error_invalid_input)
    AuthError.RATE_LIMITED -> stringResource(R.string.auth_error_rate_limited)
    AuthError.SESSION_EXPIRED -> stringResource(R.string.auth_error_session_expired)
    AuthError.NETWORK -> stringResource(R.string.auth_error_network)
    AuthError.SERVER_UNREACHABLE,
    AuthError.TLS_ERROR,
    AuthError.SERVER,
    -> stringResource(R.string.auth_error_server)
    AuthError.TIMEOUT -> stringResource(R.string.auth_error_timeout)
    AuthError.INVALID_RESPONSE,
    AuthError.UNKNOWN,
    -> stringResource(R.string.auth_error_unknown)
    AuthError.GOOGLE_NOT_CONFIGURED,
    AuthError.GOOGLE_UNAVAILABLE,
    -> stringResource(R.string.auth_error_google_unavailable)
    AuthError.GOOGLE_INVALID -> stringResource(R.string.auth_error_google_invalid)
}
