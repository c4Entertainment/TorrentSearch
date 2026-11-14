package com.prajwalch.torrentsearch.domain

import com.prajwalch.torrentsearch.domain.model.SearchTarget

import java.text.Normalizer
import kotlin.math.abs

/**
 * Decides whether a torrent's release name is the [SearchTarget], the way
 * Radarr and Sonarr do: parse the name into title, year and season/episode,
 * then compare each part.
 *
 * Examples for "The Matrix" (1999):
 *  - `The.Matrix.1999.1080p.BluRay.x264` matches.
 *  - `The.Matrix.Reloaded.2003.720p` doesn't: different title.
 *  - `Matrix (1999) [720p] [bluray]` matches: leading articles are ignored.
 */
object ReleaseNameMatcher {

    fun matches(releaseName: String, target: SearchTarget): Boolean {
        if (target.kind == SearchTarget.Kind.Anime) return animeMatches(releaseName, target)

        val release = parse(releaseName)

        return release.titleCandidates.any { candidate ->
            titleMatches(candidate.title, target) && when (target.kind) {
                SearchTarget.Kind.Movie -> movieMatches(release, candidate.year, target)
                SearchTarget.Kind.Series -> seriesMatches(release, target)
                SearchTarget.Kind.Anime -> false
            }
        }
    }

    private fun movieMatches(release: ParsedRelease, year: Int?, target: SearchTarget): Boolean {
        // An episode is never the movie.
        if (release.episodes != null) return false
        // Years are off by one often enough (festival vs. general release).
        if (year == null) return true
        val targetYear = target.year ?: return true
        return abs(year - targetYear) <= 1
    }

    private fun seriesMatches(release: ParsedRelease, target: SearchTarget): Boolean {
        val season = target.season ?: return true
        val seasons = release.seasons ?: return false
        if (season !in seasons) return false

        val episode = target.episode
            // A season search takes season packs and that season's episodes.
            ?: return true
        // An episode search wants that episode, not the whole season pack.
        val episodes = release.episodes ?: return false
        return episode in episodes
    }

    private fun titleMatches(title: String, target: SearchTarget): Boolean {
        val releaseTitle = normalise(title)
        if (releaseTitle.isEmpty()) return false

        return (listOf(target.title) + target.alternativeTitles)
            .map(::normalise)
            .filter { it.isNotEmpty() }
            .any { candidate -> titlesEqual(releaseTitle, candidate, target) }
    }

    private fun titlesEqual(releaseTitle: String, targetTitle: String, target: SearchTarget): Boolean {
        if (releaseTitle == targetTitle) return true
        if (target.kind != SearchTarget.Kind.Series) return false

        // Shows are often tagged with a year or country to tell remakes apart:
        // "Doctor Who 2005", "The Office US". Accept one such trailing tag on
        // either side.
        return stripShowTag(releaseTitle) == targetTitle || releaseTitle == stripShowTag(targetTitle)
    }

    private fun stripShowTag(title: String): String {
        val words = title.split(' ')
        if (words.size < 2) return title
        val last = words.last()
        val isTag = YEAR.matches(last) || last in COUNTRY_TAGS
        return if (isTag) words.dropLast(1).joinToString(" ") else title
    }

    // ---- Parsing ------------------------------------------------------------

    private data class ParsedRelease(
        /** Possible titles, since names are ambiguous ("Blade Runner 2049"). */
        val titleCandidates: List<TitleCandidate>,
        /** Seasons the release covers, `null` if it names none. */
        val seasons: IntRange?,
        /** Episodes the release covers, `null` for packs and movies. */
        val episodes: IntRange?,
    )

    private data class TitleCandidate(val title: String, val year: Int?)

    private fun cleanName(releaseName: String): String = releaseName
        // Dots and underscores separate words in scene names.
        .replace('_', ' ')
        .replace(Regex("""(?<=\S)\.(?=\S)"""), " ")
        // "[YTS] Title", "www.site.org - Title"
        .replace(Regex("""^\s*(\[[^\]]*]\s*)+"""), "")
        .replace(Regex("""(?i)^\s*www \S+(?: \S+)? -\s*"""), "")
        .trim()

    private fun parse(releaseName: String): ParsedRelease {
        val name = cleanName(releaseName)

        var seasons: IntRange? = null
        var episodes: IntRange? = null
        var seasonMarkerAt: Int? = null

        // S01E02, S01E02E03, S01E02-E04, S01E02-03
        SEASON_EPISODE.find(name)?.let { match ->
            val season = match.groupValues[1].toInt()
            val first = match.groupValues[2].toInt()
            val last = match.groupValues[3].ifEmpty { match.groupValues[2] }.toInt()
            seasons = season..season
            episodes = first..maxOf(first, last)
            seasonMarkerAt = match.range.first
        }
        // 1x02
        if (episodes == null) {
            CROSS_EPISODE.find(name)?.let { match ->
                val season = match.groupValues[1].toInt()
                val episode = match.groupValues[2].toInt()
                seasons = season..season
                episodes = episode..episode
                seasonMarkerAt = match.range.first
            }
        }
        // S01, S01-S03, S01-03, then Season 1, Seasons 1-3, Season 1 to 3
        for (pattern in listOf(SEASON_PACK, SEASON_WORD)) {
            if (seasons != null) break
            pattern.find(name)?.let { match ->
                val first = match.groupValues[1].toInt()
                val last = match.groupValues[2].ifEmpty { match.groupValues[1] }.toInt()
                seasons = first..maxOf(first, last)
                seasonMarkerAt = match.range.first
            }
        }

        val titleLimit = seasonMarkerAt ?: name.length

        // The last year before the season marker. A year at the very start is
        // the title itself ("1917", "2012").
        val yearMatch = YEAR_IN_NAME.findAll(name)
            .filter { it.range.first < titleLimit && name.substring(0, it.range.first).isNotBlank() }
            .lastOrNull()

        val candidates = when {
            yearMatch != null -> listOf(
                TitleCandidate(name.substring(0, yearMatch.range.first), yearMatch.groupValues[1].toInt()),
                // "Blade Runner 2049 1080p": the "year" was part of the title.
                TitleCandidate(name.substring(0, yearMatch.range.last + 1), null),
            )

            seasonMarkerAt != null -> listOf(TitleCandidate(name.substring(0, titleLimit), null))

            // No year or season: the title ends at some quality tag or bracket,
            // but tags can be real words ("Charlotte's Web"), so try each.
            else -> {
                val cuts = QUALITY_TOKEN.findAll(name).map { it.range.first } +
                    name.indices.asSequence().filter { name[it] == '[' || name[it] == '(' }
                (cuts.filter { it > 0 }.sorted().toList() + name.length)
                    .distinct()
                    .map { TitleCandidate(name.substring(0, it), null) }
            }
        }

        return ParsedRelease(titleCandidates = candidates, seasons = seasons, episodes = episodes)
    }

    // ---- Anime --------------------------------------------------------------

    /**
     * Anime names look different: `[Group] Title - 12 (1080p) [CRC].mkv`,
     * `Title - 01 ~ 37 [BATCH]`, `Title S2 - 05`, `Russian / Title [TV]`.
     * Seasons live in the title ("Overlord II", "Slime 4th Season"), and
     * batches are the normal way to release, so an episode search keeps
     * batches that contain the episode.
     */
    private fun animeMatches(releaseName: String, target: SearchTarget): Boolean {
        val name = cleanName(releaseName).removePrefix("-").trim()
        val episodes = animeEpisodes(name)
        // "Title S2 - 05": the season applies to every title candidate,
        // including "Title" cut before it.
        val namedSeason = listOf(SEASON_EPISODE, SEASON_PACK, SEASON_WORD, ANIME_ORDINAL_SEASON)
            .firstNotNullOfOrNull { it.find(name)?.groupValues?.get(1)?.toIntOrNull() }

        val targetTitles = (listOf(target.title) + target.alternativeTitles)
            .map(::normalise)
            .filter { it.isNotEmpty() }
            .map(::splitAnimeSeason)
        val releaseTitles = animeTitleCandidates(name)
            .map(::normalise)
            .map { it.removeSuffix(" the").trim() } // "Title The Complete Series"
            .filter { it.isNotEmpty() }
            .map(::splitAnimeSeason)

        val titleMatches = releaseTitles.any { (releaseBase, titleSeason) ->
            val releaseSeason = titleSeason ?: namedSeason
            targetTitles.any { (targetBase, targetSeason) ->
                releaseBase == targetBase &&
                    // An unnamed season can be any; named ones must agree.
                    (releaseSeason == null || releaseSeason == (targetSeason ?: 1))
            }
        }
        if (!titleMatches) return false

        val episode = target.episode ?: return true
        // No episode number: a batch or complete series.
        return episodes == null || episode in episodes
    }

    /** Episodes named in an anime release, `null` for batches without numbers. */
    private fun animeEpisodes(name: String): IntRange? {
        val match = SEASON_EPISODE.find(name)?.let { it.groupValues[2] to it.groupValues[3] }
            ?: ANIME_E_EPISODE.find(name)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: ANIME_DASH_EPISODE.find(name)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: return null
        val first = match.first.toInt()
        val last = match.second.ifEmpty { match.first }.toInt()
        return first..maxOf(first, last)
    }

    /**
     * Possible titles: each " / "-separated name, cut at every point a title
     * could end (" - ", brackets, episode and season markers, years, tags).
     */
    private fun animeTitleCandidates(name: String): List<String> =
        name.split(" / ", " | ").flatMap { segment ->
            val cuts = buildList {
                add(segment.length)
                Regex(""" - |[\[(]""").findAll(segment).forEach { add(it.range.first) }
                listOf(SEASON_EPISODE, ANIME_E_EPISODE, SEASON_PACK, YEAR_IN_NAME, QUALITY_TOKEN)
                    .forEach { regex -> regex.findAll(segment).forEach { add(it.range.first) } }
            }
            cuts.filter { it > 0 }.distinct().map { segment.substring(0, it) }
        }

    /**
     * Splits a normalised anime title into its base and season number:
     * "overlord ii" → ("overlord", 2), "slime 4th season" → ("slime", 4),
     * "mushoku tensei s3" → ("mushoku tensei", 3). "Part 2" (a second cour)
     * is dropped without counting as a season.
     */
    private fun splitAnimeSeason(title: String): Pair<String, Int?> {
        var base = title.replace(ANIME_PART_SUFFIX, "").trim()
        ANIME_SEASON_SUFFIX.find(base)?.let { match ->
            val number = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@let
            val season = number.toIntOrNull() ?: ROMAN_NUMERALS[number] ?: return@let
            base = base.substring(0, match.range.first).trim()
            if (base.isNotEmpty()) return base to season
        }
        return base to null
    }

    /**
     * Lowercases, strips accents and punctuation, spells "&" as "and" and
     * drops a leading article: "The Lord of the Rings: The Two Towers" and
     * "Lord.of.the.Rings.The.Two.Towers" both become
     * "lord of the rings the two towers".
     */
    internal fun normalise(title: String): String {
        val words = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("""['’`]"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }

        val withoutArticle = if (words.size > 1 && words.first() in LEADING_ARTICLES) words.drop(1) else words
        return withoutArticle.joinToString(" ")
    }

    private val LEADING_ARTICLES = setOf("the", "a", "an")

    // "Death Note - E01", "S01 E01-37", "E01~12"
    private val ANIME_E_EPISODE = Regex("""(?i)\bE(\d{1,4})(?:\s?[-~]\s?E?(\d{1,4}))?\b""")
    // "Title - 12", "Title - 12v2", "Title - 01 ~ 37"
    private val ANIME_DASH_EPISODE =
        Regex("""\s-\s(\d{1,4})(?:v\d)?(?:\s?[-~]\s?(\d{1,4})(?:v\d)?)?(?=[\s\[(.]|$)""")
    // On normalised titles: "season 2", "2nd season", "s2", "ii".
    private val ANIME_SEASON_SUFFIX =
        Regex("""\s(?:season (\d{1,2})|(\d{1,2})(?:st|nd|rd|th) season|s(\d{1,2})|(ii|iii|iv|v|vi))$""")
    private val ANIME_ORDINAL_SEASON = Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th) Season\b""")
    private val ANIME_PART_SUFFIX = Regex("""\s(?:part|cour) \d{1,2}$""")
    private val ROMAN_NUMERALS = mapOf("ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6)
    private val COUNTRY_TAGS = setOf("us", "uk", "au", "nz", "ca")
    private val YEAR = Regex("""(19|20)\d{2}""")

    private val SEASON_EPISODE =
        Regex("""(?i)\bS(\d{1,2})[ .]?E(\d{1,3})(?:(?:-?E|-)(\d{1,3}))?""")
    private val CROSS_EPISODE = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
    private val SEASON_PACK = Regex("""(?i)\bS(\d{1,2})(?:\s?-\s?S?(\d{1,2}))?\b""")
    private val SEASON_WORD =
        Regex("""(?i)\bSeasons?\s?(\d{1,2})(?:\s?(?:-|to|&)\s?(\d{1,2}))?\b""")
    private val YEAR_IN_NAME = Regex("""[\s(\[]((?:19|20)\d{2})(?=[\s)\]]|$)""")
    private val QUALITY_TOKEN = Regex(
        """(?i)\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr|bluray|blu-ray|brrip|bdrip|bdremux|""" +
            """remux|web-?dl|webrip|web|hdtv|hdrip|dvdrip|dvdscr|dvd|x264|x265|h[ .]?26[45]|""" +
            """hevc|avc|xvid|proper|repack|extended|unrated|uncut|remastered|imax|complete|""" +
            """multi|dual|hc|cam|ts|telesync|10bit|yify|yts|rarbg)\b""",
    )
}
