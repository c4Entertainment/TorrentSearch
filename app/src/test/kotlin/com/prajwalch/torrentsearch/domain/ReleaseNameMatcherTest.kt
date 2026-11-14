package com.prajwalch.torrentsearch.domain

import com.prajwalch.torrentsearch.domain.model.SearchTarget
import com.prajwalch.torrentsearch.domain.model.SearchTarget.Kind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNameMatcherTest {
    private fun movie(title: String, year: Int, vararg alt: String) =
        SearchTarget(Kind.Movie, title, alt.toList(), year)

    private fun show(title: String, season: Int? = null, episode: Int? = null) =
        SearchTarget(Kind.Series, title, season = season, episode = episode)

    private fun assertMatch(name: String, target: SearchTarget) =
        assertTrue("expected match: $name", ReleaseNameMatcher.matches(name, target))

    private fun assertNoMatch(name: String, target: SearchTarget) =
        assertFalse("expected no match: $name", ReleaseNameMatcher.matches(name, target))

    @Test
    fun movieMatchesSceneAndYtsNames() {
        val matrix = movie("The Matrix", 1999)
        assertMatch("The.Matrix.1999.1080p.BluRay.x264-GROUP", matrix)
        assertMatch("The Matrix (1999) [720p] [bluray] [x264]", matrix)
        assertMatch("Matrix 1999 2160p UHD", matrix)
        assertMatch("[YTS.MX] The Matrix (1999)", matrix)
        assertMatch("The Matrix 2000 720p", matrix) // year ±1
    }

    @Test
    fun movieRejectsOtherTitlesYearsAndEpisodes() {
        val matrix = movie("The Matrix", 1999)
        assertNoMatch("The.Matrix.Reloaded.2003.720p", matrix)
        assertNoMatch("The Matrix Resurrections (2021) [1080p]", matrix)
        assertNoMatch("The Matrix 2005 1080p", matrix)
        assertNoMatch("The.Matrix.S01E01.720p", matrix)
        assertNoMatch("Making The Matrix 1999", matrix)
    }

    @Test
    fun movieTitlesWithPunctuationYearsAndTagWords() {
        assertMatch(
            "Spider-Man.No.Way.Home.2021.1080p.WEB-DL",
            movie("Spider-Man: No Way Home", 2021),
        )
        assertMatch("Fast and Furious 2009 720p", movie("Fast & Furious", 2009))
        assertMatch("Amelie 2001 1080p", movie("Amélie", 2001))
        assertMatch("1917.2019.1080p.BluRay", movie("1917", 2019))
        assertMatch("Blade.Runner.2049.2017.2160p", movie("Blade Runner 2049", 2017))
        assertMatch("Blade Runner 2049 1080p BluRay", movie("Blade Runner 2049", 2017))
        assertMatch("Charlottes Web 1080p", movie("Charlotte's Web", 2006))
        assertMatch("Le.Fabuleux.Destin.2001.1080p", movie("Amélie", 2001, "Le Fabuleux Destin"))
    }

    @Test
    fun episodeSearch() {
        val target = show("Game of Thrones", season = 1, episode = 2)
        assertMatch("Game.of.Thrones.S01E02.720p.HDTV.x264-CTU", target)
        assertMatch("Game of Thrones 1x02 The Kingsroad", target)
        assertMatch("Game.of.Thrones.S01E01E02.1080p", target)
        assertNoMatch("Game.of.Thrones.S01E03.720p", target)
        assertNoMatch("Game.of.Thrones.S02E02.720p", target)
        assertNoMatch("Game of Thrones Season 1 Complete 1080p", target)
        assertNoMatch("House.of.the.Dragon.S01E02.720p", target)
    }

    @Test
    fun seasonSearch() {
        val target = show("Breaking Bad", season = 2)
        assertMatch("Breaking.Bad.S02.1080p.BluRay", target)
        assertMatch("Breaking Bad Season 2 Complete 720p", target)
        assertMatch("Breaking.Bad.S01-S05.Complete", target)
        assertMatch("Breaking.Bad.S02E05.720p", target)
        assertNoMatch("Breaking.Bad.S03.1080p", target)
        assertNoMatch("Breaking Bad 2008 1080p", target)
    }

    @Test
    fun torrentioReleaseNames() {
        val episode = show("Game of Thrones", season = 1, episode = 2)
        assertMatch("Game of Thrones S01E02 The Kingsroad REPACK 2160p MAX WEB-DL TrueHD 7 1 Atm", episode)
        assertMatch("Game.of.Thrones.S01E02.2011.Multi.UHD.BluRay.2160p.x265.HDR.Atmo", episode)
        assertNoMatch("Game of Thrones (2011) S01 (2160p x265 10bit HDR UHD BD Atmos) [Prof]", episode)

        assertMatch("Game of Thrones (2011) S01 (2160p x265 10bit HDR UHD BD Atmos) [Prof]", show("Game of Thrones", season = 1))
        assertMatch("Game.of.Thrones.S01-S08.2160p.UHD.BluRay", show("Game of Thrones", season = 1))
    }

    private fun anime(title: String, vararg alt: String, episode: Int? = null) =
        SearchTarget(Kind.Anime, title, alt.toList(), episode = episode)

    @Test
    fun animeEpisodeSearch() {
        val ep1 = anime("Death Note", episode = 1)
        assertMatch("[AnimeIn4K] Death Note - E01 (DeNoised (4K Upscale) (Dual-Audio)", ep1)
        assertMatch("[Erai-raws] Death Note - 01 ~ 37 [1080p][BATCH][Multiple Subtitle]", ep1)
        assertMatch("Death Note [BDRip 1080p][HEVC x265 10bit][Dual-Audio]", ep1)
        assertMatch("[Ryuuga] - Death Note (2006) S01 - [1080p-AAC LC-x264]-[WEB-DL]", ep1)
        assertMatch("Тетрадь Смерти / Death Note [TV] [37 из 37] [RUS(ext) JAP+Sub] [2006 BDRip]", ep1)
        assertMatch("Death Note (2006) S01 E01-37 COMPLETE 1080p H.264 DUAL AUDIO", ep1)
        assertMatch("Death Note The Complete TV Series (2006–2007) 1080P BluRay", ep1)
        assertNoMatch("[SubsPlease] Death Note - 02 (1080p) [ABCDEF12].mkv", ep1)
        assertNoMatch("[Odji-san] Death Note Kaï Films 1 à 6 (intégrale)", ep1)

        val frieren = anime("Sousou no Frieren", "Frieren: Beyond Journey's End", episode = 5)
        assertMatch("[SubsPlease] Sousou no Frieren - 05 (1080p) [290AD3B3].mkv", frieren)
        assertMatch("[Erai-raws] Frieren - Beyond Journey's End - 05v2 [1080p]", frieren)
        assertNoMatch("[SubsPlease] Sousou no Frieren - 12 (1080p) [290AD3B3].mkv", frieren)
    }

    @Test
    fun animeSeasonsLiveInTheTitle() {
        val mushoku3 = anime("Mushoku Tensei III: Isekai Ittara Honki Dasu", "Mushoku Tensei S3", episode = 12)
        assertMatch("[SubsPlease] Mushoku Tensei S3 - 12 (1080p) [290AD3B3].mkv", mushoku3)
        assertMatch("[Erai-raws] Mushoku Tensei III - Isekai Ittara Honki Dasu - 12 [1080p CR WEB-DL]", mushoku3)

        val slime4 = anime("Tensei Shitara Slime Datta Ken 4th Season", episode = 12)
        assertMatch("[SubsPlease] Tensei Shitara Slime Datta Ken S4 - 12 (1080p) [CF045EDF].mkv", slime4)
        assertMatch("[Erai-raws] Tensei Shitara Slime Datta Ken 4th Season - 12 [1080p]", slime4)
        assertNoMatch("[SubsPlease] Tensei Shitara Slime Datta Ken S3 - 12 (1080p).mkv", slime4)

        assertNoMatch("[SubsPlease] Death Note S2 - 01 (1080p).mkv", anime("Death Note"))
        assertNoMatch("[SubsPlease] Tongari Boushi no Atelier - 12 [1080p]", anime("Death Note"))
    }

    @Test
    fun seriesSearchAcceptsYearAndCountryTags() {
        assertMatch("Doctor.Who.2005.S01E01.720p", show("Doctor Who"))
        assertMatch("The.Office.US.S02E01.720p", show("The Office"))
        assertMatch("Mr.Robot.S01E01.1080p", show("Mr. Robot"))
        assertNoMatch("The.Office.Ladies.S01E01", show("The Office"))
    }

    @Test
    fun normalise() {
        assertEquals(
            "lord of the rings the two towers",
            ReleaseNameMatcher.normalise("The Lord of the Rings: The Two Towers"),
        )
        assertEquals("fast and furious", ReleaseNameMatcher.normalise("Fast & Furious"))
    }

    @Test
    fun imdbTtId() {
        assertEquals("tt0111161", movie("x", 1).copy(imdbId = "tt0111161").imdbTtId)
        assertEquals("tt0111161", movie("x", 1).copy(imdbId = "0111161").imdbTtId)
        assertEquals(null, movie("x", 1).copy(imdbId = "").imdbTtId)
    }
}
