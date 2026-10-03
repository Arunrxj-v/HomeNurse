package com.homenurse.ui.chat

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.domain.model.ConversationMessage
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.SafetyLevel
import com.homenurse.ui.components.AIResponseCard
import com.homenurse.ui.components.ChatUserBubble
import com.homenurse.ui.components.EmptyState
import com.homenurse.ui.components.HomeNursePrimaryButton
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.NoticeCard
import com.homenurse.ui.theme.HomeNurseColors

/**
 * Ask HomeNurse: local chat in the new design language. Responses render as
 * [AIResponseCard]s that visually distinguish grounded / general / safety /
 * doctor-deferral content (presentation only — grounding and the safety
 * engine are untouched), the footer states answers are generated on this
 * phone, and the input row ends in a green circular send button.
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val errorRes by viewModel.errorRes.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(R.string.chat_title),
                onBack = onBack,
                subtitle = stringResource(R.string.chat_subtitle),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding(),
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EmptyState(
                        title = stringResource(R.string.chat_empty),
                        icon = Icons.Outlined.FavoriteBorder,
                    )
                    QuestionIdeas(onPick = viewModel::updateInput)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                }
            }

            errorRes?.let { res ->
                NoticeCard(text = stringResource(res))
                viewModel.consumeError()
            }

            // Footer: plain-language grounding statement.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(HomeNurseColors.InfoContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = HomeNurseColors.OnInfoContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = HomeNurseColors.OnInfoContainer,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            // Input row: pill field + green circular send/stop button.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = viewModel::updateInput,
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HomeNurseColors.Primary,
                        unfocusedBorderColor = HomeNurseColors.Border,
                        focusedContainerColor = HomeNurseColors.Surface,
                        unfocusedContainerColor = HomeNurseColors.Surface,
                    ),
                )
                if (sending) {
                    IconButton(
                        onClick = viewModel::stop,
                        modifier = Modifier
                            .size(52.dp)
                            .background(HomeNurseColors.ErrorContainer, CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.chat_stop),
                            tint = HomeNurseColors.Error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = viewModel::send,
                        enabled = input.isNotBlank(),
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                if (input.isNotBlank()) {
                                    HomeNurseColors.Primary
                                } else {
                                    HomeNurseColors.Border
                                },
                                CircleShape,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = if (input.isNotBlank()) {
                                HomeNurseColors.OnPrimary
                            } else {
                                HomeNurseColors.TextSecondary
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ConversationMessage) {
    when (message.role) {
        ConversationRole.SAFETY, ConversationRole.MODEL -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AIResponseCard(message)
            if (message.role == ConversationRole.SAFETY &&
                message.safetyLevel == SafetyLevel.EMERGENCY
            ) {
                EmergencyCallButton()
            }
        }

        ConversationRole.USER -> ChatUserBubble(content = message.content)
    }
}

/**
 * Emergency assistance: opens the phone dialer (never dials on its own).
 * The number comes from the user's own region/contacts — the safety
 * engine only ever advises "call your local emergency number".
 */
@Composable
private fun EmergencyCallButton() {
    val context = LocalContext.current
    val callingLabel = stringResource(R.string.safety_emergency_calling)
    HomeNursePrimaryButton(
        text = stringResource(R.string.safety_emergency_call),
        leading = Icons.Filled.Call,
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_DIAL))
            }.onSuccess {
                Toast.makeText(context, callingLabel, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

/** Tappable starter questions shown only before the first message. */
@Composable
private fun QuestionIdeas(onPick: (String) -> Unit) {
    val ideas = listOf(
        stringResource(R.string.chat_idea_1),
        stringResource(R.string.chat_idea_2),
        stringResource(R.string.chat_idea_3),
        stringResource(R.string.chat_idea_4),
    )
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ideas.forEach { idea ->
            Text(
                text = idea,
                style = MaterialTheme.typography.bodyMedium,
                color = HomeNurseColors.PrimaryDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(HomeNurseColors.Surface)
                    .border(1.dp, HomeNurseColors.Border, RoundedCornerShape(14.dp))
                    .clickable { onPick(idea) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}
