package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpisodeListOrder
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog

@Composable
fun EpisodeListOrderButton(
    order: EpisodeListOrder,
    onOrderSelected: (EpisodeListOrder) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var open by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    SourceStatusFilterChip(
        name = stringResource(
            if (order == EpisodeListOrder.LatestFirst) {
                R.string.episode_list_latest_first
            } else {
                R.string.episode_list_oldest_first
            }
        ),
        isSelected = open,
        onClick = { open = true },
        modifier = modifier.focusRequester(focusRequester),
    )
    if (open) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.episode_list_sort),
            options = listOf(
                SettingsPickerOption(
                    EpisodeListOrder.OldestFirst,
                    stringResource(R.string.episode_list_oldest_first),
                ),
                SettingsPickerOption(
                    EpisodeListOrder.LatestFirst,
                    stringResource(R.string.episode_list_latest_first),
                ),
            ),
            selectedValue = order,
            onOptionSelected = {
                onOrderSelected(it)
                open = false
                restoreFocus = true
            },
            onDismiss = {
                open = false
                restoreFocus = true
            },
        )
    }
    LaunchedEffect(restoreFocus) {
        if (!restoreFocus) return@LaunchedEffect
        restoreFocus = false
        runCatching { focusRequester.requestFocus() }
    }
}
