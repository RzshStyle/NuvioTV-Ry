package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.PlumePicks
import com.nuvio.tv.domain.model.CatalogRow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Ry: Home rows from the Plume Picks lists (TMDB). "See all" opens the list's folder. */
internal const val PLUME_ADDON_ID = "ry-plume"

private const val PLUME_REFRESH_MS = 6 * 60 * 60 * 1000L

internal fun HomeViewModel.observePlumeRows() {
    viewModelScope.launch {
        while (true) {
            val rows = PlumePicks.homeSources().mapIndexed { i, (folderId, source) ->
                async {
                    runCatching {
                        val result = tmdbCollectionSourceResolver.resolve(source).first { it !is NetworkResult.Loading }
                        (result as? NetworkResult.Success<CatalogRow>)?.data?.copy(
                            addonId = PLUME_ADDON_ID,
                            addonName = "Plume Picks",
                            addonBaseUrl = "",
                            catalogId = "$folderId#$i",
                            catalogName = source.title,
                            hasMore = true,
                            supportsSkip = false
                        )
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
            if (rows.isNotEmpty() || plumeRowsCache.isEmpty()) {
                plumeRowsCache = rows
                scheduleUpdateCatalogRows()
            }
            delay(if (rows.isEmpty()) 60_000L else PLUME_REFRESH_MS)
        }
    }
}
