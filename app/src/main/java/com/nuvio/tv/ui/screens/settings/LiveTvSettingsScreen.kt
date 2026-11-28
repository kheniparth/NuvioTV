@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.data.LiveTvConnectionTarget
import com.nuvio.tv.livetv.data.local.LiveTvEpgSource
import com.nuvio.tv.livetv.data.local.LiveTvPlaylist
import com.nuvio.tv.livetv.data.local.LiveTvPlaylistType
import com.nuvio.tv.livetv.data.local.LiveTvStalkerSettings
import com.nuvio.tv.livetv.data.local.LiveTvXtreamSettings
import com.nuvio.tv.livetv.data.local.liveTvSourceHost
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.UUID

private sealed interface LiveTvEditor {
    data class Playlist(val value: LiveTvPlaylist?) : LiveTvEditor
    data object Xtream : LiveTvEditor
    data object Stalker : LiveTvEditor
    data class Epg(val value: LiveTvEpgSource?) : LiveTvEditor
}

@Composable
fun LiveTvSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: LiveTvSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<LiveTvEditor?>(null) }
    val testStateFor: (String) -> LiveTvTestState? = { state.testStates[it] }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_live_tv),
            subtitle = stringResource(R.string.settings_live_tv_subtitle),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SettingsGroupCard(modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "livetv_m3u_label") {
                            ServerSectionLabel(stringResource(R.string.livetv_m3u_section))
                        }
                        itemsIndexed(state.playlists, key = { _, it -> "playlist_${it.id}" }) { index, playlist ->
                            SettingsActionRow(
                                title = playlist.name,
                                subtitle = sourceSubtitle(
                                    playlist.source,
                                    stringResource(
                                        if (playlist.type == LiveTvPlaylistType.Url) R.string.livetv_type_url
                                        else R.string.livetv_type_file
                                    ),
                                ),
                                value = disabledLabel(playlist.isEnabled),
                                leadingIcon = Icons.Default.PlaylistPlay,
                                onClick = { editor = LiveTvEditor.Playlist(playlist) },
                                modifier = if (index == 0 && initialFocusRequester != null) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                        }
                        item(key = "livetv_m3u_add") {
                            SettingsActionRow(
                                title = stringResource(R.string.livetv_add_playlist),
                                subtitle = null,
                                leadingIcon = Icons.Default.Add,
                                onClick = { editor = LiveTvEditor.Playlist(null) },
                                modifier = if (state.playlists.isEmpty() && initialFocusRequester != null) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                        }

                        item(key = "livetv_xtream_label") {
                            ServerSectionLabel(stringResource(R.string.livetv_xtream_section))
                        }
                        item(key = "livetv_xtream") {
                            SettingsActionRow(
                                title = if (state.xtream.serverUrl.isBlank()) {
                                    stringResource(R.string.livetv_add_xtream)
                                } else {
                                    stringResource(R.string.livetv_xtream_account)
                                },
                                subtitle = sourceSubtitle(
                                    state.xtream.serverUrl,
                                    stringResource(R.string.livetv_xtream_section),
                                ),
                                value = if (state.xtream.serverUrl.isBlank()) null else disabledLabel(state.xtream.isEnabled),
                                leadingIcon = Icons.Default.LiveTv,
                                onClick = { editor = LiveTvEditor.Xtream },
                            )
                        }

                        item(key = "livetv_stalker_label") {
                            ServerSectionLabel(stringResource(R.string.livetv_stalker_section))
                        }
                        item(key = "livetv_stalker") {
                            SettingsActionRow(
                                title = if (state.stalker.portalUrl.isBlank()) {
                                    stringResource(R.string.livetv_add_stalker)
                                } else {
                                    stringResource(R.string.livetv_stalker_portal)
                                },
                                subtitle = sourceSubtitle(
                                    state.stalker.portalUrl,
                                    stringResource(R.string.livetv_stalker_section),
                                ),
                                value = if (state.stalker.portalUrl.isBlank()) null else disabledLabel(state.stalker.isEnabled),
                                leadingIcon = Icons.Default.LiveTv,
                                onClick = { editor = LiveTvEditor.Stalker },
                            )
                        }

                        item(key = "livetv_epg_label") {
                            ServerSectionLabel(stringResource(R.string.livetv_epg_section))
                        }
                        items(state.epgSources, key = { "epg_${it.id}" }) { source ->
                            SettingsActionRow(
                                title = source.name,
                                subtitle = sourceSubtitle(
                                    source.url,
                                    stringResource(R.string.livetv_epg_section),
                                ),
                                value = disabledLabel(source.isEnabled),
                                leadingIcon = Icons.Default.LiveTv,
                                onClick = { editor = LiveTvEditor.Epg(source) },
                            )
                        }
                        item(key = "livetv_epg_add") {
                            SettingsActionRow(
                                title = stringResource(R.string.livetv_add_epg),
                                subtitle = null,
                                leadingIcon = Icons.Default.Add,
                                onClick = { editor = LiveTvEditor.Epg(null) },
                            )
                        }
                    }
                    SettingsVerticalScrollIndicators(state = listState)
                }
            }
        }
    }

    when (val current = editor) {
        null -> Unit
        is LiveTvEditor.Playlist -> PlaylistEditorDialog(
            initial = current.value,
            viewModel = viewModel,
            testStateFor = testStateFor,
            onDismiss = { editor = null },
        )
        LiveTvEditor.Xtream -> XtreamEditorDialog(
            viewModel = viewModel,
            testState = testStateFor("xtream"),
            onDismiss = { editor = null },
        )
        LiveTvEditor.Stalker -> StalkerEditorDialog(
            viewModel = viewModel,
            testState = testStateFor("stalker"),
            onDismiss = { editor = null },
        )
        is LiveTvEditor.Epg -> EpgEditorDialog(
            initial = current.value,
            viewModel = viewModel,
            testStateFor = testStateFor,
            onDismiss = { editor = null },
        )
    }
}

@Composable
private fun PlaylistEditorDialog(
    initial: LiveTvPlaylist?,
    viewModel: LiveTvSettingsViewModel,
    testStateFor: (String) -> LiveTvTestState?,
    onDismiss: () -> Unit,
) {
    val id = remember(initial) { initial?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: LiveTvPlaylistType.Url) }
    var source by remember { mutableStateOf(initial?.source.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.isEnabled ?: true) }
    val playlist = LiveTvPlaylist(
        id = id,
        name = name.trim().ifBlank { "M3U playlist" },
        type = type,
        source = source.trim(),
        isEnabled = enabled,
    )
    val canSubmit = source.isNotBlank()

    NuvioDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.livetv_add_playlist) else stringResource(R.string.livetv_m3u_section),
        width = 760.dp,
        suppressFirstKeyUp = false,
    ) {
        LiveTvTextField(stringResource(R.string.livetv_field_name), name, { name = it })
        Text(
            text = stringResource(R.string.livetv_field_type),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            SettingsChoiceChip(
                label = stringResource(R.string.livetv_type_url),
                selected = type == LiveTvPlaylistType.Url,
                onClick = { type = LiveTvPlaylistType.Url },
            )
            SettingsChoiceChip(
                label = stringResource(R.string.livetv_type_file),
                selected = type == LiveTvPlaylistType.LocalFile,
                onClick = { type = LiveTvPlaylistType.LocalFile },
            )
        }
        LiveTvTextField(
            label = if (type == LiveTvPlaylistType.Url) {
                stringResource(R.string.livetv_field_url)
            } else {
                stringResource(R.string.livetv_field_path)
            },
            value = source,
            onValueChange = { source = it },
            isSecret = type == LiveTvPlaylistType.Url,
        )
        SettingsToggleRow(
            title = stringResource(R.string.livetv_enabled),
            subtitle = null,
            checked = enabled,
            onToggle = { enabled = !enabled },
        )
        LiveTvTestResult(testStateFor(id))
        SettingsDialogActionRow {
            RemoveButton(visible = initial != null) {
                viewModel.removePlaylist(id)
                onDismiss()
            }
            SettingsDialogActionButton(
                text = testButtonText(testStateFor(id)),
                onClick = { viewModel.testConnection(LiveTvConnectionTarget.Playlist(playlist)) },
                enabled = canSubmit && testStateFor(id)?.isTesting != true,
            )
            SettingsDialogActionButton(stringResource(R.string.action_cancel), onDismiss)
            SettingsDialogActionButton(stringResource(R.string.action_save), primary = true, enabled = canSubmit, onClick = {
                viewModel.savePlaylist(playlist)
                onDismiss()
            })
        }
    }
}

@Composable
private fun XtreamEditorDialog(
    viewModel: LiveTvSettingsViewModel,
    testState: LiveTvTestState?,
    onDismiss: () -> Unit,
) {
    val stored = viewModel.uiState.value.xtream
    var serverUrl by remember { mutableStateOf(stored.serverUrl) }
    var username by remember { mutableStateOf(stored.username) }
    var password by remember { mutableStateOf(stored.password) }
    var enabled by remember { mutableStateOf(stored.isEnabled) }
    val settings = LiveTvXtreamSettings(
        serverUrl = serverUrl.trim(),
        username = username.trim(),
        password = password.trim(),
        isEnabled = enabled,
    )
    val canSubmit = settings.serverUrl.isNotBlank() &&
        settings.username.isNotBlank() &&
        settings.password.isNotBlank()

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.livetv_xtream_section),
        width = 760.dp,
        suppressFirstKeyUp = false,
    ) {
        LiveTvTextField(stringResource(R.string.livetv_field_server), serverUrl, { serverUrl = it })
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            LiveTvTextField(
                label = stringResource(R.string.livetv_field_username),
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.weight(1f),
            )
            LiveTvTextField(
                label = stringResource(R.string.livetv_field_password),
                value = password,
                onValueChange = { password = it },
                isSecret = true,
                modifier = Modifier.weight(1f),
            )
        }
        SettingsToggleRow(
            title = stringResource(R.string.livetv_enabled),
            subtitle = null,
            checked = enabled,
            onToggle = { enabled = !enabled },
        )
        LiveTvTestResult(testState)
        SettingsDialogActionRow {
            RemoveButton(visible = stored.serverUrl.isNotBlank()) {
                viewModel.saveXtream(LiveTvXtreamSettings())
                onDismiss()
            }
            SettingsDialogActionButton(
                text = testButtonText(testState),
                onClick = { viewModel.testConnection(LiveTvConnectionTarget.Xtream(settings)) },
                enabled = canSubmit && testState?.isTesting != true,
            )
            SettingsDialogActionButton(stringResource(R.string.action_cancel), onDismiss)
            SettingsDialogActionButton(stringResource(R.string.action_save), primary = true, enabled = canSubmit, onClick = {
                viewModel.saveXtream(settings)
                onDismiss()
            })
        }
    }
}

@Composable
private fun StalkerEditorDialog(
    viewModel: LiveTvSettingsViewModel,
    testState: LiveTvTestState?,
    onDismiss: () -> Unit,
) {
    val stored = viewModel.uiState.value.stalker
    var portalUrl by remember { mutableStateOf(stored.portalUrl) }
    var macAddress by remember { mutableStateOf(stored.macAddress) }
    var username by remember { mutableStateOf(stored.username) }
    var password by remember { mutableStateOf(stored.password) }
    var enabled by remember { mutableStateOf(stored.isEnabled) }
    val settings = LiveTvStalkerSettings(
        portalUrl = portalUrl.trim(),
        macAddress = macAddress.trim(),
        username = username.trim(),
        password = password.trim(),
        isEnabled = enabled,
    )
    val canSubmit = settings.portalUrl.isNotBlank() && settings.macAddress.isNotBlank()

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.livetv_stalker_section),
        width = 760.dp,
        suppressFirstKeyUp = false,
    ) {
        LiveTvTextField(stringResource(R.string.livetv_field_portal), portalUrl, { portalUrl = it })
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            LiveTvTextField(
                label = stringResource(R.string.livetv_field_mac),
                value = macAddress,
                onValueChange = { macAddress = it },
                modifier = Modifier.weight(1f),
            )
            LiveTvTextField(
                label = stringResource(R.string.livetv_field_username),
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.weight(1f),
            )
        }
        LiveTvTextField(
            label = stringResource(R.string.livetv_field_password),
            value = password,
            onValueChange = { password = it },
            isSecret = true,
        )
        SettingsToggleRow(
            title = stringResource(R.string.livetv_enabled),
            subtitle = null,
            checked = enabled,
            onToggle = { enabled = !enabled },
        )
        LiveTvTestResult(testState)
        SettingsDialogActionRow {
            RemoveButton(visible = stored.portalUrl.isNotBlank()) {
                viewModel.saveStalker(LiveTvStalkerSettings())
                onDismiss()
            }
            SettingsDialogActionButton(
                text = testButtonText(testState),
                onClick = { viewModel.testConnection(LiveTvConnectionTarget.Stalker(settings)) },
                enabled = canSubmit && testState?.isTesting != true,
            )
            SettingsDialogActionButton(stringResource(R.string.action_cancel), onDismiss)
            SettingsDialogActionButton(stringResource(R.string.action_save), primary = true, enabled = canSubmit, onClick = {
                viewModel.saveStalker(settings)
                onDismiss()
            })
        }
    }
}

@Composable
private fun EpgEditorDialog(
    initial: LiveTvEpgSource?,
    viewModel: LiveTvSettingsViewModel,
    testStateFor: (String) -> LiveTvTestState?,
    onDismiss: () -> Unit,
) {
    val id = remember(initial) { initial?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.isEnabled ?: true) }
    val source = LiveTvEpgSource(
        id = id,
        name = name.trim().ifBlank { "EPG" },
        url = url.trim(),
        isEnabled = enabled,
    )
    val canSubmit = url.isNotBlank()

    NuvioDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.livetv_add_epg) else stringResource(R.string.livetv_epg_section),
        width = 760.dp,
        suppressFirstKeyUp = false,
    ) {
        LiveTvTextField(stringResource(R.string.livetv_field_name), name, { name = it })
        LiveTvTextField(stringResource(R.string.livetv_field_url), url, { url = it }, isSecret = true)
        SettingsToggleRow(
            title = stringResource(R.string.livetv_enabled),
            subtitle = null,
            checked = enabled,
            onToggle = { enabled = !enabled },
        )
        LiveTvTestResult(testStateFor(id))
        SettingsDialogActionRow {
            RemoveButton(visible = initial != null) {
                viewModel.removeEpgSource(id)
                onDismiss()
            }
            SettingsDialogActionButton(
                text = testButtonText(testStateFor(id)),
                onClick = { viewModel.testConnection(LiveTvConnectionTarget.Epg(source)) },
                enabled = canSubmit && testStateFor(id)?.isTesting != true,
            )
            SettingsDialogActionButton(stringResource(R.string.action_cancel), onDismiss)
            SettingsDialogActionButton(stringResource(R.string.action_save), primary = true, enabled = canSubmit, onClick = {
                viewModel.saveEpgSource(source)
                onDismiss()
            })
        }
    }
}

@Composable
private fun LiveTvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isSecret: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundElevated)
                .border(
                    border = if (focused) {
                        NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
                    } else {
                        BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
                    },
                    shape = shape,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        val direction = when (event.key) {
                            Key.DirectionUp -> FocusDirection.Up
                            Key.DirectionDown -> FocusDirection.Down
                            else -> null
                        }
                        if (direction != null && event.type == KeyEventType.KeyDown) {
                            keyboardController?.hide()
                            focusManager.moveFocus(direction)
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                // Hidden while the user is elsewhere, readable while editing the field.
                visualTransformation = if (isSecret && !focused) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                cursorBrush = SolidColor(if (focused) NuvioTheme.colors.Primary else Color.Transparent),
                decorationBox = { innerTextField ->
                    if (value.isBlank()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextTertiary,
                        )
                    }
                    innerTextField()
                },
            )
        }
    }
}

@Composable
private fun LiveTvTestResult(state: LiveTvTestState?) {
    val text = when {
        state == null -> return
        state.isTesting -> stringResource(R.string.livetv_testing)
        state.error != null -> stringResource(R.string.livetv_test_failed, state.error)
        state.channelCount != null -> stringResource(R.string.livetv_test_channels, state.channelCount)
        state.success -> stringResource(R.string.livetv_test_connected)
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.error != null) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
    )
}

@Composable
private fun testButtonText(state: LiveTvTestState?): String =
    if (state?.isTesting == true) {
        stringResource(R.string.livetv_testing)
    } else {
        stringResource(R.string.livetv_test_connection)
    }

@Composable
private fun RemoveButton(visible: Boolean, onClick: () -> Unit) {
    if (visible) {
        SettingsDialogActionButton(stringResource(R.string.action_delete), onClick)
    }
}

@Composable
private fun disabledLabel(enabled: Boolean): String? =
    if (enabled) null else stringResource(R.string.livetv_disabled)

/** Row subtitle with the host only: the stored URL can carry a private token. */
@Composable
private fun sourceSubtitle(url: String, type: String): String? =
    if (url.isBlank()) null else listOfNotNull(liveTvSourceHost(url), type).joinToString(" · ")
