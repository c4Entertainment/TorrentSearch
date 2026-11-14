package com.prajwalch.torrentsearch.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The exact movie or show a host app is searching for.
 *
 * When a search carries a target, providers that can look up by IMDb ID do
 * so, and results whose release names don't match the target are dropped.
 */
@Serializable
data class SearchTarget(
    val kind: Kind,
    /** Main title, e.g. "The Matrix". */
    val title: String,
    /** Other names releases may use, e.g. the original-language title. */
    val alternativeTitles: List<String> = emptyList(),
    /** Release year for movies, first-air year for shows. */
    val year: Int? = null,
    /** IMDb ID, with or without the "tt" prefix. */
    val imdbId: String? = null,
    /** Kitsu anime ID, used by Torrentio for anime. */
    val kitsuId: String? = null,
    /** Season to match. `null` for movies, anime and whole-series searches. */
    val season: Int? = null,
    /**
     * Episode to match within [season]; for anime, the episode number within
     * the entry. `null` for season packs and whole-series searches.
     */
    val episode: Int? = null,
) {
    enum class Kind { Movie, Series, Anime }

    /** The IMDb ID with its "tt" prefix, or `null` if it isn't usable. */
    val imdbTtId: String?
        get() {
            val digits = imdbId?.trim()?.removePrefix("tt")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            return digits?.let { "tt$it" }
        }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decodeOrNull(encoded: String?): SearchTarget? {
            if (encoded.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), encoded) }.getOrNull()
        }
    }
}
