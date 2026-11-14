package com.prajwalch.torrentsearch.providers

import android.util.Log

import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.SearchTarget
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.domain.model.TorrentDetails
import com.prajwalch.torrentsearch.extension.asObject
import com.prajwalch.torrentsearch.extension.getArray
import com.prajwalch.torrentsearch.extension.getString
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.SearchProvider
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.provider.TorrentDetailsProvider
import com.prajwalch.torrentsearch.util.FileSizeUtils
import com.prajwalch.torrentsearch.util.TorrentDateParser
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class Eztv(private val networkClient: NetworkClient) : SearchProvider, TorrentDetailsProvider {
    override val id = "eztvx"
    override val name = "Eztv"
    override val url = "https://eztvx.to"
    override val cloudflareSolverUrl = "$url/home"
    override val supportedCategories = setOf(Category.Series)
    override val safety = SearchProviderSafety.Safe
    override val isCloudflareProtected = true
    override val enabledByDefault = true

    private val resultsPageParser = EztvResultsPageParser(id, name)
    private val apiResultParser = EztvApiResultParser(id, name)

    private companion object {
        private const val TAG = "Eztv"
        private const val API_PAGE_SIZE = 100
        private const val MAX_API_PAGES = 5
    }

    override suspend fun search(query: String, category: Category): List<Torrent> {
        val requestUrl = "$url/search/$query"
        // Without setting that cookie, it returns results without magnet links.
        val responseHtml = networkClient.getText(
            url = requestUrl,
            headers = mapOf("Cookie" to "layout=def_wlinks"),
        )

        return resultsPageParser.parse(html = responseHtml, pageUrl = requestUrl)
    }

    /**
     * Looks the show up by IMDb ID through EZTV's JSON API when [target] has
     * one, falling back to the text search if the API fails or has nothing
     * for the requested season/episode.
     */
    override suspend fun search(
        query: String,
        category: Category,
        target: SearchTarget?,
    ): List<Torrent> {
        val imdbDigits = target
            ?.takeIf { it.kind == SearchTarget.Kind.Series }
            ?.imdbTtId
            ?.removePrefix("tt")
            ?: return search(query, category)

        val byImdbId = try {
            searchByImdbId(imdbDigits, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "IMDb lookup failed, falling back to text search", e)
            emptyList()
        }

        return byImdbId.ifEmpty { search(query, category) }
    }

    private suspend fun searchByImdbId(imdbDigits: String, target: SearchTarget): List<Torrent> {
        val torrents = mutableListOf<Torrent>()

        // The API pages newest first and can't filter by season, so an old
        // episode of a long show may be past the pages we fetch; the text
        // search fallback covers that.
        for (page in 1..MAX_API_PAGES) {
            val requestUrl = "$url/api/get-torrents?imdb_id=$imdbDigits&limit=$API_PAGE_SIZE&page=$page"
            val response = networkClient.getJson(requestUrl)?.asObject() ?: break
            val items = response.getArray("torrents")?.map { it.asObject() }.orEmpty()
            if (items.isEmpty()) break

            items.filter { it.isEpisodeOf(target) }.mapNotNullTo(torrents, apiResultParser::parse)

            val total = response.getString("torrents_count")?.toIntOrNull() ?: break
            if (page * API_PAGE_SIZE >= total) break
        }

        return torrents
    }

    /** Uses the API's own season/episode fields; "0" or missing means unknown. */
    private fun JsonObject.isEpisodeOf(target: SearchTarget): Boolean {
        val season = getString("season")?.toIntOrNull()?.takeIf { it > 0 }
        val episode = getString("episode")?.toIntOrNull()?.takeIf { it > 0 }

        if (target.season != null && season != null && season != target.season) return false
        if (target.episode != null && episode != null && episode != target.episode) return false
        return true
    }

    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        val responseHtml = networkClient.getText(detailsPageUrl)
        return EztvDetailsPageParser.parse(html = responseHtml, pageUrl = detailsPageUrl)
    }
}

private class EztvResultsPageParser(
    private val providerId: SearchProviderId,
    private val providerName: String,
) {
    suspend fun parse(html: String, pageUrl: String): List<Torrent> =
        withContext(Dispatchers.Default) {
            Jsoup.parse(html, pageUrl)
                .select(LIST_ITEM)
                .drop(2)
                .mapNotNull(::parseListItem)
        }

    private fun parseListItem(listItem: Element): Torrent? {
        val torrentName = listItem.selectFirst(TORRENT_NAME)?.ownText() ?: return null
        val magnetUri = listItem.selectFirst(MAGNET_URI)?.attr("href") ?: return null
        val torrentId = TorrentUtils.createTorrentId(
            providerId = providerId,
            sourceId = TorrentUtils.getInfoHashFromMagnetUri(magnetUri),
        )
        val size = listItem.selectFirst(SIZE)?.ownText()
        val seeders = listItem.selectFirst(SEEDERS)?.ownText()?.toUIntOrNull()
        val fileDownloadLink = listItem.selectFirst(FILE_DOWNLOAD_LINK)?.attr("abs:href")
        val detailsPageUrl = listItem.selectFirst(DETAILS_PAGE_URL)?.attr("abs:href")

        return Torrent(
            id = torrentId,
            name = torrentName,
            size = size,
            seeders = seeders,
            peers = 0u,
            category = Category.Series,
            providerName = providerName,
            magnetUri = MagnetUri.Available(magnetUri),
            fileDownloadLink = fileDownloadLink,
            detailsPageUrl = detailsPageUrl,
        )
    }

    private companion object {
        private const val LIST_ITEM = "table:last-of-type > tbody > tr"
        private const val TORRENT_NAME = "td:nth-child(2) > a.epinfo"
        private const val SIZE = "td:nth-child(4)"
        private const val SEEDERS = "td:nth-child(6)"
        private const val MAGNET_URI = "td:nth-child(3) > a.magnet"
        private const val FILE_DOWNLOAD_LINK = "td:nth-child(3) > a:nth-child(2)"
        private const val DETAILS_PAGE_URL = TORRENT_NAME
    }
}

/**
 * Parses one torrent object from `/api/get-torrents`.
 *
 * Object layout (only shown necessary fields; numbers may come as strings):
 *
 *     hash:               <info hash>
 *     title:              <release name>
 *     magnet_url:         <magnet uri>
 *     torrent_url:        <.torrent file url>
 *     episode_url:        <details page url>
 *     seeds:              <number>
 *     peers:              <number>
 *     size_bytes:         <number>
 *     date_released_unix: <number>
 */
private class EztvApiResultParser(
    private val providerId: SearchProviderId,
    private val providerName: String,
) {
    fun parse(item: JsonObject): Torrent? {
        val name = item.getString("title") ?: return null
        val magnetUri = item.getString("magnet_url") ?: return null
        val infoHash = item.getString("hash")?.lowercase()
            ?: TorrentUtils.getInfoHashFromMagnetUri(magnetUri)

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId = providerId, sourceId = infoHash),
            name = name,
            size = item.getString("size_bytes")?.toFloatOrNull()?.let(FileSizeUtils::formatBytes),
            seeders = item.getString("seeds")?.toUIntOrNull(),
            peers = item.getString("peers")?.toUIntOrNull() ?: 0u,
            category = Category.Series,
            providerName = providerName,
            uploadDate = item.getString("date_released_unix")
                ?.toLongOrNull()
                ?.let(TorrentDateParser::epochSecondToInstant),
            magnetUri = MagnetUri.Available(magnetUri),
            fileDownloadLink = item.getString("torrent_url"),
            detailsPageUrl = item.getString("episode_url"),
        )
    }
}

private object EztvDetailsPageParser {
    private const val TORRENT_NAME =
        "#header_holder > table > tbody > tr:nth-child(1) > td > h1 > span"
    private const val TORRENT_INFO_CONTAINER =
        "#header_holder > table > tbody > tr:nth-child(2) > td > table.episode_columns_holder > tbody > tr:nth-child(1) > td:nth-child(3) > table > tbody > tr:nth-child(2) > td > table > tbody > tr:nth-child(1) > td"
    private const val SEEDERS =
        "#header_holder > table > tbody > tr:nth-child(2) > td > table.episode_columns_holder > tbody > tr:nth-child(1) > td.episode_middle_column > table:nth-child(2) > tbody > tr:nth-child(2) > td > div > table > tbody > tr > td:nth-child(2) > span.stat_red"
    private const val PEERS =
        "#header_holder > table > tbody > tr:nth-child(2) > td > table.episode_columns_holder > tbody > tr:nth-child(1) > td.episode_middle_column > table:nth-child(2) > tbody > tr:nth-child(2) > td > div > table > tbody > tr > td:nth-child(2) > span.stat_green"
    private const val MAGNET_URI = """a[title="Magnet Link"]"""
    private const val FILE_DOWNLOAD_LINK = """a[title="Download Torrent"]"""
    private const val DESCRIPTION = "div.desc_big"
    private const val POSTER_URL =
        "#header_holder > table > tbody > tr:nth-child(2) > td > table.episode_columns_holder > tbody > tr:nth-child(1) > td.episode_left_column > table > tbody > tr:nth-child(2) > td > a:nth-child(1) > img"

    suspend fun parse(html: String, pageUrl: String): TorrentDetails? =
        withContext(Dispatchers.Default) {
            val html = Jsoup.parse(html, pageUrl)

            val torrentName = html.selectFirst(TORRENT_NAME)?.ownText() ?: return@withContext null
            val magnetUri = html.selectFirst(MAGNET_URI)?.attr("href") ?: return@withContext null
            val seeders = html.selectFirst(SEEDERS)?.ownText()?.toUIntOrNull()
            val peers = html.selectFirst(PEERS)?.ownText()?.toUIntOrNull()

            val torrentInfoContainer = html.selectFirst(TORRENT_INFO_CONTAINER)
            val size = torrentInfoContainer
                ?.find { it.ownText() == "Filesize:" }
                ?.nextSibling()
                ?.takeIf { it is TextNode }
                ?.nodeValue()
                ?.trim()
            val uploadDate = torrentInfoContainer
                ?.find { it.ownText() == "Released:" }
                ?.nextSibling()
                ?.takeIf { it is TextNode }
                ?.nodeValue()
                ?.trim()
                ?.replace(Regex("(\\d+)(st|nd|rd|th)"), "$1")
                ?.let { TorrentDateParser.parse(date = it, format = "d MMM yyyy") }
            val fileDownloadLink = html.selectFirst(FILE_DOWNLOAD_LINK)?.attr("abs:href")
            val description = html.selectFirst(DESCRIPTION)?.html()
            val posterUrl = html.selectFirst(POSTER_URL)?.attr("abs:src")

            TorrentDetails(
                infoHash = TorrentUtils.getInfoHashFromMagnetUri(magnetUri),
                name = torrentName,
                size = size,
                seeders = seeders,
                peers = peers,
                uploadDate = uploadDate,
                category = Category.Series,
                magnetUri = magnetUri,
                fileDownloadLink = fileDownloadLink,
                description = description,
                posterUrl = posterUrl,
            )
        }
}