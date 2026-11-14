package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.SearchTarget
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.asObject
import com.prajwalch.torrentsearch.extension.getArray
import com.prajwalch.torrentsearch.extension.getString
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.SearchProvider
import com.prajwalch.torrentsearch.util.FileSizeUtils
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Torrentio, the Stremio add-on: it indexes many sites (YTS, EZTV, RARBG,
 * 1337x, TPB, ...) by IMDb ID, season and episode.
 *
 * It only works for searches that carry a [SearchTarget] with an IMDb ID
 * (movies, shows) or a Kitsu ID (anime),
 * i.e. ones started from a host app's movie or show page. Typed searches
 * return nothing.
 */
class Torrentio(private val networkClient: NetworkClient) : SearchProvider {
    override val id = ID
    override val name = "Torrentio"
    override val url = "https://torrentio.strem.fun"
    override val supportedCategories = setOf(Category.Movies, Category.Series, Category.Anime)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    override suspend fun search(query: String, category: Category): List<Torrent> = emptyList()

    override suspend fun search(
        query: String,
        category: Category,
        target: SearchTarget?,
    ): List<Torrent> {
        if (target == null) return emptyList()
        val imdbId = target.imdbTtId
        val kitsuId = target.kitsuId?.takeIf { it.isNotBlank() }

        // Torrentio only answers per episode. For a season or the whole
        // series, asking for its first episode also returns the packs that
        // contain it; the release-name filter keeps what fits.
        val (type, streamId) = when (target.kind) {
            SearchTarget.Kind.Movie -> "movie" to (imdbId ?: return emptyList())
            SearchTarget.Kind.Series ->
                "series" to "${imdbId ?: return emptyList()}:${target.season ?: 1}:${target.episode ?: 1}"
            SearchTarget.Kind.Anime ->
                "series" to "kitsu:${kitsuId ?: return emptyList()}:${target.episode ?: 1}"
        }
        val category = when (target.kind) {
            SearchTarget.Kind.Movie -> Category.Movies
            SearchTarget.Kind.Series -> Category.Series
            SearchTarget.Kind.Anime -> Category.Anime
        }

        val responseJson = networkClient.getJson("$url/stream/$type/$streamId.json")
            ?: return emptyList()
        return parse(responseJson, category)
    }

    private suspend fun parse(json: JsonElement, category: Category): List<Torrent> =
        withContext(Dispatchers.Default) {
            json.asObject()
                .getArray("streams")
                ?.mapNotNull { parseStream(it.asObject(), category) }
                ?.distinctBy { it.id }
                .orEmpty()
        }

    /**
     * Parses one stream object.
     *
     * Object layout (only shown necessary fields):
     *
     *     infoHash: <info hash>
     *     title:    <release name>\n[<file name>\n]👤 <seeders> 💾 <size> ⚙️ <source site>[\n<flags>]
     *
     * For a pack, the second line is the requested episode's file; the
     * release name (first line) is used since the magnet fetches the pack.
     */
    private fun parseStream(stream: JsonObject, category: Category): Torrent? {
        val infoHash = stream.getString("infoHash")?.lowercase() ?: return null
        val lines = stream.getString("title")?.lines() ?: return null
        val releaseName = lines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Not always the last line: language flags ("🇬🇧 / 🇷🇺") may follow it.
        val stats = lines.firstOrNull { "👤" in it }.orEmpty()

        val seeders = SEEDERS.find(stats)?.groupValues?.get(1)?.toUIntOrNull()
        val size = SIZE.find(stats)?.let { match ->
            val bytes = match.groupValues[1].toFloatOrNull() ?: return@let null
            val multiplier = SIZE_UNITS[match.groupValues[2].uppercase()] ?: return@let null
            FileSizeUtils.formatBytes(bytes * multiplier)
        }

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId = id, sourceId = infoHash),
            name = releaseName,
            size = size,
            seeders = seeders,
            peers = 0u,
            providerName = name,
            category = category,
            magnetUri = MagnetUri.Available(TorrentUtils.createMagnetUri(infoHash)),
        )
    }

    companion object {
        const val ID = "torrentio"

        private val SEEDERS = Regex("""👤\s*(\d+)""")
        private val SIZE = Regex("""💾\s*([\d.]+)\s*([KMGT]?B)""", RegexOption.IGNORE_CASE)
        private val SIZE_UNITS = mapOf(
            "B" to 1f,
            "KB" to 1024f,
            "MB" to 1024f * 1024,
            "GB" to 1024f * 1024 * 1024,
            "TB" to 1024f * 1024 * 1024 * 1024,
        )
    }
}
