@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.iptv.model.PlaylistSource
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvSourceStatus
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date

private enum class IptvDialog { XTREAM, M3U }

/** Settings › Content discovery › Live TV playlists. Users add their own provider here. */
@Composable
internal fun IptvSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: IptvSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<IptvDialog?>(null) }
    var manage by remember { mutableStateOf<String?>(null) }
    val focusModifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.iptv_settings_title),
            subtitle = stringResource(R.string.iptv_settings_subtitle),
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "iptv_label") { ServerSectionLabel(stringResource(R.string.iptv_section_playlists)) }
                    if (state.sources.isEmpty()) {
                        item(key = "iptv_empty") {
                            Text(
                                text = stringResource(R.string.iptv_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm),
                            )
                        }
                    }
                    items(state.sources, key = { "iptv_${it.id}" }) { source ->
                        SettingsActionRow(
                            title = source.name,
                            subtitle = statusText(source, state.status[source.id]),
                            onClick = { manage = source.id },
                            leadingIcon = Icons.Default.LiveTv,
                            modifier = if (source == state.sources.first()) focusModifier else Modifier,
                        )
                    }
                    item(key = "iptv_add_label") { ServerSectionLabel(stringResource(R.string.iptv_section_add)) }
                    item(key = "iptv_add_xtream") {
                        SettingsActionRow(
                            title = stringResource(R.string.iptv_add_xtream),
                            subtitle = stringResource(R.string.iptv_add_xtream_subtitle),
                            onClick = { dialog = IptvDialog.XTREAM },
                            leadingIcon = Icons.Default.VpnKey,
                            modifier = if (state.sources.isEmpty()) focusModifier else Modifier,
                        )
                    }
                    item(key = "iptv_add_m3u") {
                        SettingsActionRow(
                            title = stringResource(R.string.iptv_add_m3u),
                            subtitle = stringResource(R.string.iptv_add_m3u_subtitle),
                            onClick = { dialog = IptvDialog.M3U },
                            leadingIcon = Icons.Default.PlaylistAdd,
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    when (dialog) {
        IptvDialog.XTREAM -> XtreamDialog(viewModel, onDone = { dialog = null })
        IptvDialog.M3U -> M3uDialog(viewModel, onDone = { dialog = null })
        null -> Unit
    }
    manage?.let { id ->
        val source = state.sources.firstOrNull { it.id == id } ?: return@let
        ManagePlaylistDialog(
            source = source,
            status = state.status[id],
            onRefresh = { viewModel.refresh(id); manage = null },
            onRemove = { viewModel.remove(id); manage = null },
            onDismiss = { manage = null },
        )
    }
}

@Composable
private fun statusText(source: PlaylistSource, status: IptvSourceStatus?): String {
    val kind = when (source) {
        is PlaylistSource.Xtream -> stringResource(R.string.iptv_kind_xtream)
        is PlaylistSource.M3u -> stringResource(R.string.iptv_kind_m3u)
    }
    val parts = mutableListOf(kind)
    when {
        status == null -> Unit
        status.loadingPlaylist -> parts += stringResource(R.string.iptv_status_loading)
        status.error != null -> parts += status.error
        else -> {
            parts += stringResource(R.string.iptv_status_channels, status.channelCount)
            if (status.loadingEpg) parts += stringResource(R.string.iptv_status_loading_guide)
            else if (status.epgChannelCount > 0) parts += stringResource(R.string.iptv_status_guide, status.epgChannelCount)
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ManagePlaylistDialog(
    source: PlaylistSource,
    status: IptvSourceStatus?,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    NuvioDialog(onDismiss = onDismiss, title = source.name, subtitle = statusText(source, status), width = 640.dp) {
        val lines = buildList {
            status?.playlistUpdatedAt?.let { add(stringResource(R.string.iptv_updated_playlist, df.format(Date(it)))) }
            status?.epgUpdatedAt?.let { add(stringResource(R.string.iptv_updated_guide, df.format(Date(it)))) }
            status?.expiresAtMs?.let { add(stringResource(R.string.iptv_expires, df.format(Date(it)))) }
            status?.maxConnections?.let { add(stringResource(R.string.iptv_connections, it)) }
            status?.epgErrors?.forEach { (url, err) -> add(stringResource(R.string.iptv_guide_error, url, err)) }
        }
        lines.forEach {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(
                text = stringResource(if (confirmRemove) R.string.iptv_remove_confirm else R.string.iptv_remove),
                onClick = { if (confirmRemove) onRemove() else confirmRemove = true },
            )
            SettingsDialogActionButton(text = stringResource(R.string.iptv_refresh), onClick = onRefresh, primary = true)
        }
    }
}

@Composable
private fun XtreamDialog(viewModel: IptvSettingsViewModel, onDone: () -> Unit) {
    val add by viewModel.addState.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var hls by remember { mutableStateOf(false) }
    val canAdd = !add.connecting && server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    val submit = { if (canAdd) viewModel.addXtream(name, server, username, password, epg, hls) { password = ""; onDone() } }
    LaunchedEffect(Unit) { viewModel.resetAdd() }

    NuvioDialog(
        onDismiss = { if (!add.connecting) onDone() },
        title = stringResource(R.string.iptv_add_xtream),
        subtitle = stringResource(R.string.iptv_xtream_dialog_subtitle),
        width = 680.dp,
    ) {
        IptvField(stringResource(R.string.iptv_field_name)) {
            InputField(name, { name = it }, stringResource(R.string.iptv_field_name_hint), imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
        }
        IptvField(stringResource(R.string.iptv_field_server)) {
            LtrInput(server, { server = it }, stringResource(R.string.iptv_field_server_hint))
        }
        IptvField(stringResource(R.string.servers_username)) {
            InputField(username, { username = it }, stringResource(R.string.servers_username), imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
        }
        IptvField(stringResource(R.string.servers_password)) {
            InputField(
                password, { password = it }, stringResource(R.string.servers_password),
                keyboardType = KeyboardType.Password, isPassword = true, imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IptvField(stringResource(R.string.iptv_field_epg_optional)) {
            LtrInput(epg, { epg = it }, stringResource(R.string.iptv_field_epg_xtream_hint), ImeAction.Done, submit)
        }
        SettingsToggleRow(
            title = stringResource(R.string.iptv_field_hls),
            subtitle = stringResource(R.string.iptv_field_hls_subtitle),
            checked = hls,
            onToggle = { hls = !hls },
        )
        AddFooter(add, canAdd, onCancel = onDone, onSubmit = submit)
    }
}

@Composable
private fun M3uDialog(viewModel: IptvSettingsViewModel, onDone: () -> Unit) {
    val add by viewModel.addState.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    val canAdd = !add.connecting && url.isNotBlank()
    val submit = { if (canAdd) viewModel.addM3u(name, url, epg, userAgent) { onDone() } }
    LaunchedEffect(Unit) { viewModel.resetAdd() }

    NuvioDialog(
        onDismiss = { if (!add.connecting) onDone() },
        title = stringResource(R.string.iptv_add_m3u),
        subtitle = stringResource(R.string.iptv_m3u_dialog_subtitle),
        width = 680.dp,
    ) {
        IptvField(stringResource(R.string.iptv_field_name)) {
            InputField(name, { name = it }, stringResource(R.string.iptv_field_name_hint), imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
        }
        IptvField(stringResource(R.string.iptv_field_m3u)) {
            LtrInput(url, { url = it }, stringResource(R.string.iptv_field_m3u_hint))
        }
        IptvField(stringResource(R.string.iptv_field_epg_optional)) {
            LtrInput(epg, { epg = it }, stringResource(R.string.iptv_field_epg_hint))
        }
        IptvField(stringResource(R.string.iptv_field_user_agent)) {
            InputField(userAgent, { userAgent = it }, stringResource(R.string.iptv_field_user_agent_hint), imeAction = ImeAction.Done, onImeAction = submit, modifier = Modifier.fillMaxWidth())
        }
        AddFooter(add, canAdd, onCancel = onDone, onSubmit = submit)
    }
}

@Composable
private fun AddFooter(add: IptvAddState, canAdd: Boolean, onCancel: () -> Unit, onSubmit: () -> Unit) {
    add.error?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.Error)
    }
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel, enabled = !add.connecting)
        SettingsDialogActionButton(
            text = stringResource(if (add.connecting) R.string.iptv_adding else R.string.iptv_add),
            onClick = onSubmit,
            primary = true,
            enabled = canAdd,
        )
    }
}

@Composable
private fun LtrInput(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    imeAction: ImeAction = ImeAction.Next,
    onIme: () -> Unit = {},
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        InputField(
            value = value,
            onValueChange = onChange,
            placeholder = hint,
            keyboardType = KeyboardType.Uri,
            imeAction = imeAction,
            onImeAction = onIme,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun IptvField(label: String, field: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        field()
    }
}
