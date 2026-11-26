package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType

/**
 * Ry's own category rows (from the Plume Picks launcher), shown as a built-in collection.
 * It isn't stored or synced; editing it in the collection editor saves a normal copy.
 * Dates use {today}/{d-N} so "new" rows stay current; the region is the device's country.
 */
object PlumePicks {
    const val COLLECTION_ID = "ry-plume-picks"

    private const val WATCHABLE = "flatrate|free|ads|rent|buy"
    private const val NOT_TALK_NEWS_REALITY_SOAP = "10767|10763|10764|10766"

    private fun movies(title: String, votes: Int, f: TmdbCollectionFilters = TmdbCollectionFilters(), sort: String = TmdbCollectionSort.POPULAR_DESC.value) =
        TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = title,
            mediaType = TmdbCollectionMediaType.MOVIE,
            sortBy = sort,
            filters = f.copy(voteCountGte = votes, withWatchMonetizationTypes = WATCHABLE)
        )

    private fun series(title: String, votes: Int, f: TmdbCollectionFilters = TmdbCollectionFilters(), sort: String = TmdbCollectionSort.POPULAR_DESC.value) =
        TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = title,
            mediaType = TmdbCollectionMediaType.TV,
            sortBy = sort,
            filters = f.copy(
                voteCountGte = votes,
                withWatchMonetizationTypes = WATCHABLE,
                withoutGenres = f.withoutGenres ?: NOT_TALK_NEWS_REALITY_SOAP
            )
        )

    private fun genre(movieGenres: String, seriesGenres: String) =
        TmdbCollectionFilters(withGenres = movieGenres) to TmdbCollectionFilters(withGenres = seriesGenres)

    val collection: Collection by lazy {
        val (comedyM, comedyS) = genre("35", "35")
        val (actionM, actionS) = genre("28", "10759")
        val (dramaM, dramaS) = genre("18", "18")
        val (crimeM, crimeS) = genre("53|80", "80")
        val (scifiM, scifiS) = genre("878|14", "10765")
        Collection(
            id = COLLECTION_ID,
            title = "Plume Picks",
            viewMode = FolderViewMode.ROWS,
            showAllTab = false,
            folders = listOf(
                CollectionFolder(
                    id = "$COLLECTION_ID-new",
                    title = "New & Popular",
                    coverEmoji = "🆕",
                    tileShape = PosterShape.LANDSCAPE,
                    sources = listOf(
                        movies(
                            "New releases · Movies", 5,
                            TmdbCollectionFilters(withReleaseType = "4|5", releaseDateGte = "{d-60}", releaseDateLte = "{today}")
                        ),
                        series(
                            "New releases · Series", 10,
                            TmdbCollectionFilters(releaseDateGte = "{d-21}", releaseDateLte = "{today}")
                        ),
                        movies("Popular movies", 150),
                        series("Popular series", 150)
                    )
                ),
                CollectionFolder(
                    id = "$COLLECTION_ID-genres",
                    title = "Genres",
                    coverEmoji = "🎭",
                    tileShape = PosterShape.LANDSCAPE,
                    sources = listOf(
                        movies("Comedy · Movies", 300, comedyM), series("Comedy · Series", 150, comedyS),
                        movies("Action · Movies", 300, actionM), series("Action · Series", 150, actionS),
                        movies("Drama · Movies", 300, dramaM), series("Drama · Series", 150, dramaS),
                        movies("Crime & Thriller · Movies", 300, crimeM), series("Crime & Thriller · Series", 150, crimeS),
                        movies("Sci-Fi & Fantasy · Movies", 300, scifiM), series("Sci-Fi & Fantasy · Series", 150, scifiS)
                    )
                ),
                CollectionFolder(
                    id = "$COLLECTION_ID-kids",
                    title = "Kids",
                    coverEmoji = "🧸",
                    tileShape = PosterShape.LANDSCAPE,
                    sources = listOf(
                        movies("Popular kids movies", 100, TmdbCollectionFilters(withGenres = "10751", certificationLte = "PG")),
                        series("Popular kids series", 20, TmdbCollectionFilters(withGenres = "10762")),
                        series(
                            "Old school cartoons", 0,
                            TmdbCollectionFilters(withGenres = "16,10762", releaseDateGte = "2000-01-01", releaseDateLte = "2019-12-31"),
                            sort = TmdbCollectionSort.VOTE_COUNT_DESC.value
                        )
                    )
                )
            )
        )
    }

    /** Puts the built-in collection first unless a saved collection already uses its id. */
    fun withPicks(saved: List<Collection>): List<Collection> =
        if (saved.any { it.id == COLLECTION_ID }) saved else listOf(collection) + saved
}
