package com.homenurse.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homenurse.R
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.LargeOutlinedButton
import com.homenurse.ui.components.NoticeCard
import com.homenurse.ui.components.SectionCard

/**
 * Third-party licenses / AI model licenses.
 *
 * Gemma compliance (Gemma Terms of Use, Section 3.1 — Distribution and
 * Redistribution): every distribution of the Gemma model must
 *  * accompany the distribution with a "Notice" text file containing the
 *    required notice (shown here and shipped as a file with every model
 *    download),
 *  * give recipients a copy of the Gemma Terms of Use (linked here), and
 *  * notify recipients that Gemma is subject to the use restrictions in
 *    Section 3.2 / the Gemma Prohibited Use Policy (linked here).
 *
 * Nothing on this screen claims Google endorses HomeNurse.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(R.string.licenses_title),
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
            SectionCard {
                Text(
                    text = stringResource(R.string.licenses_gemma_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.licenses_gemma_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.licenses_gemma_notice_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                NoticeCard(
                    text = stringResource(R.string.licenses_gemma_notice),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LargeOutlinedButton(
                    text = stringResource(R.string.licenses_gemma_terms),
                    onClick = { open(GEMMA_TERMS_URL) },
                )
                LargeOutlinedButton(
                    text = stringResource(R.string.licenses_gemma_prohibited),
                    onClick = { open(GEMMA_PROHIBITED_USE_URL) },
                )
            }

            SectionCard {
                Text(
                    text = stringResource(R.string.licenses_app_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.licenses_app_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_licenses_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private const val GEMMA_TERMS_URL = "https://ai.google.dev/gemma/terms"
private const val GEMMA_PROHIBITED_USE_URL = "https://ai.google.dev/gemma/prohibited_use_policy"
