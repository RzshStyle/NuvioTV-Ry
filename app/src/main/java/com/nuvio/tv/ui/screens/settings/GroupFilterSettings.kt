package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.GroupPrefixFilter
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** The Channel filter row's value: "Off", or "Keep: UK, US" / "Hide: 4K". */
@Composable
internal fun groupFilterSummary(s: LiveTvSettings): String {
    val tags = GroupPrefixFilter.parseTags(s.groupFilterTags)
    return when {
        s.groupFilterMode == GroupPrefixFilter.OFF || tags.isEmpty() -> stringResource(R.string.iptv_group_filter_off)
        s.groupFilterMode == GroupPrefixFilter.HIDE -> stringResource(R.string.iptv_group_filter_summary_hide, tags.joinToString(", "))
        else -> stringResource(R.string.iptv_group_filter_summary_keep, tags.joinToString(", "))
    }
}

/**
 * Settings → Live TV → Channel filter. Lists the tags found at the start of your group names
 * ("UK" in "UK | Sports", "4K" in "4K| UHD 3840P") to tick with the remote, plus any you type.
 * Keep: only channels whose group or name starts with a ticked tag. Hide: those are left out.
 * Saved when you press Done or Back; the channel list (and guide) are then made again.
 */
@Composable
internal fun GroupFilterDialog(
    settings: LiveTvSettings,
    loadTags: suspend () -> List<Pair<String, Int>>,
    onSave: (mode: String, tags: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(settings.groupFilterMode) }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(GroupPrefixFilter.parseTags(settings.groupFilterTags)) } }
    var detected by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { detected = runCatching { loadTags() }.getOrDefault(emptyList()) }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    val finish = {
        val tags = chosen.toList()
        val newMode = if (tags.isEmpty()) GroupPrefixFilter.OFF else mode
        if (newMode != settings.groupFilterMode || GroupPrefixFilter.joinTags(tags) != GroupPrefixFilter.joinTags(GroupPrefixFilter.parseTags(settings.groupFilterTags))) {
            onSave(newMode, tags)
        }
        onDismiss()
    }

    if (typing) {
        TextInputDialog(
            title = stringResource(R.string.iptv_group_filter_add_title),
            initial = "",
            hint = stringResource(R.string.iptv_group_filter_add_hint),
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = { typing = false },
            onConfirm = { text ->
                GroupPrefixFilter.parseTags(text).forEach { if (it !in chosen) chosen += it }
                if (chosen.isNotEmpty() && mode == GroupPrefixFilter.OFF) mode = GroupPrefixFilter.KEEP
                typing = false
            }
        )
        return
    }

    val detectedTags = detected.orEmpty()
    val detectedNames = detectedTags.mapTo(HashSet()) { it.first }
    // Your own tags that aren't in any group name come first, then every tag found.
    val rows: List<Pair<String, Int?>> = chosen.filter { it !in detectedNames }.map { it to null } + detectedTags.map { it.first to it.second }

    NuvioDialog(
        onDismiss = finish,
        title = stringResource(R.string.iptv_group_filter_title),
        subtitle = stringResource(R.string.iptv_group_filter_dialog_hint),
        width = 600.dp
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            item(key = "mode") {
                SettingsActionRow(
                    title = stringResource(R.string.iptv_group_filter_mode),
                    subtitle = stringResource(
                        when (mode) {
                            GroupPrefixFilter.KEEP -> R.string.iptv_group_filter_mode_keep_desc
                            GroupPrefixFilter.HIDE -> R.string.iptv_group_filter_mode_hide_desc
                            else -> R.string.iptv_group_filter_mode_off_desc
                        }
                    ),
                    value = stringResource(
                        when (mode) {
                            GroupPrefixFilter.KEEP -> R.string.iptv_group_filter_mode_keep
                            GroupPrefixFilter.HIDE -> R.string.iptv_group_filter_mode_hide
                            else -> R.string.iptv_group_filter_off
                        }
                    ),
                    leadingIcon = Icons.Default.FilterList,
                    onClick = {
                        mode = when (mode) {
                            GroupPrefixFilter.OFF -> GroupPrefixFilter.KEEP
                            GroupPrefixFilter.KEEP -> GroupPrefixFilter.HIDE
                            else -> GroupPrefixFilter.OFF
                        }
                    },
                    modifier = Modifier.focusRequester(first)
                )
            }
            item(key = "done") {
                SettingsActionRow(
                    title = stringResource(R.string.iptv_group_filter_done),
                    subtitle = stringResource(R.string.iptv_group_filter_done_desc),
                    leadingIcon = Icons.Default.Check,
                    onClick = finish
                )
            }
            item(key = "add") {
                SettingsActionRow(
                    title = stringResource(R.string.iptv_group_filter_add),
                    subtitle = stringResource(R.string.iptv_group_filter_add_desc),
                    leadingIcon = Icons.Default.Add,
                    onClick = { typing = true }
                )
            }
            if (chosen.isNotEmpty()) {
                item(key = "clear") {
                    SettingsActionRow(
                        title = stringResource(R.string.iptv_group_filter_clear),
                        subtitle = null,
                        onClick = { chosen.clear() }
                    )
                }
            }
            if (detected == null) {
                item(key = "loading") {
                    SettingsActionRow(title = stringResource(R.string.iptv_group_filter_loading), subtitle = null, onClick = {})
                }
            } else if (rows.isEmpty()) {
                item(key = "none") {
                    SettingsActionRow(title = stringResource(R.string.iptv_group_filter_none_found), subtitle = null, onClick = { typing = true })
                }
            }
            rows.forEach { (tag, count) ->
                item(key = "t:$tag") {
                    SettingsToggleRow(
                        title = tag,
                        subtitle = if (count == null) stringResource(R.string.iptv_group_filter_typed)
                            else pluralStringResource(R.plurals.iptv_group_filter_channels, count, count),
                        checked = tag in chosen,
                        onToggle = {
                            if (tag in chosen) chosen.remove(tag) else {
                                chosen += tag
                                if (mode == GroupPrefixFilter.OFF) mode = GroupPrefixFilter.KEEP
                            }
                        }
                    )
                }
            }
        }
    }
}
