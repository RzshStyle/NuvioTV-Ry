package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.iptv.model.PlaylistSource
import com.nuvio.iptv.model.XtreamOutput
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.data.iptv.IptvState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IptvAddState(val connecting: Boolean = false, val error: String? = null)

@HiltViewModel
class IptvSettingsViewModel @Inject constructor(
    private val repository: IptvRepository,
) : ViewModel() {
    val state: StateFlow<IptvState> = repository.state

    private val mutableAdd = MutableStateFlow(IptvAddState())
    val addState: StateFlow<IptvAddState> = mutableAdd.asStateFlow()

    fun resetAdd() { mutableAdd.value = IptvAddState() }

    fun addXtream(
        name: String,
        server: String,
        username: String,
        password: String,
        epgUrl: String,
        useHls: Boolean,
        onAdded: (String) -> Unit,
    ) = add(
        PlaylistSource.Xtream(
            id = "",
            name = name.trim().ifEmpty { hostOf(server) },
            serverUrl = server.trim(),
            username = username.trim(),
            password = password,
            epgUrls = epgList(epgUrl),
            outputFormat = if (useHls) XtreamOutput.HLS else XtreamOutput.TS,
        ),
        onAdded,
    )

    fun addM3u(name: String, url: String, epgUrl: String, userAgent: String, onAdded: (String) -> Unit) = add(
        PlaylistSource.M3u(
            id = "",
            name = name.trim().ifEmpty { hostOf(url) },
            url = url.trim().let { if (it.contains("://")) it else "http://$it" },
            epgUrls = epgList(epgUrl),
            userAgent = userAgent.trim().ifEmpty { null },
        ),
        onAdded,
    )

    private fun add(source: PlaylistSource, onAdded: (String) -> Unit) {
        if (mutableAdd.value.connecting) return
        mutableAdd.value = IptvAddState(connecting = true)
        viewModelScope.launch {
            repository.addSource(source)
                .onSuccess {
                    mutableAdd.value = IptvAddState()
                    onAdded(it.id)
                }
                .onFailure { mutableAdd.value = IptvAddState(error = it.message) }
        }
    }

    fun refresh(sourceId: String) = repository.refresh(sourceId)
    fun remove(sourceId: String) = repository.removeSource(sourceId)

    private fun epgList(text: String) = text.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    private fun hostOf(url: String) = url.trim().substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { "Playlist" }
}
