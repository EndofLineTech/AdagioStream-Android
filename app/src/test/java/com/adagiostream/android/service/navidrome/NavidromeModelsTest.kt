package com.adagiostream.android.service.navidrome

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Subsonic ID3 model layer. All fixtures are inline JSON strings. */
class NavidromeModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Subsonic envelope: outer key "subsonic-response" (hyphen)
    // -------------------------------------------------------------------------

    @Test
    fun `subsonic-response envelope with hyphen decodes with status ok`() {
        val fixture = """
            {"subsonic-response":{"status":"ok","version":"1.16.1"}}
        """.trimIndent()
        val envelope = json.decodeFromString<SubsonicStatusEnvelope>(fixture)
        assertEquals("ok", envelope.status)
        assertEquals("1.16.1", envelope.version)
        assertNull(envelope.error)
    }

    @Test
    fun `subsonic-response envelope with failed status decodes error code and message`() {
        val fixture = """
            {"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}
        """.trimIndent()
        val envelope = json.decodeFromString<SubsonicStatusEnvelope>(fixture)
        assertEquals("failed", envelope.status)
        assertEquals(40, envelope.error?.code)
        assertEquals("Wrong username or password", envelope.error?.message)
    }

    // -------------------------------------------------------------------------
    // Artist / SubsonicArtistDto
    // -------------------------------------------------------------------------

    @Test
    fun `SubsonicArtistDto decodes id name albumCount coverArt`() {
        val fixture = """
            {"id":"ar1","name":"Radiohead","albumCount":9,"coverArt":"art-ar1"}
        """.trimIndent()
        val dto = json.decodeFromString<SubsonicArtistDto>(fixture)
        assertEquals("ar1", dto.id)
        assertEquals("Radiohead", dto.name)
        assertEquals(9, dto.albumCount)
        assertEquals("art-ar1", dto.coverArt)
        assertFalse("starred must be false when field absent", dto.starred)
    }

    @Test
    fun `SubsonicArtistDto starred is true when field present`() {
        val fixture = """
            {"id":"ar2","name":"Beatles","albumCount":13,"starred":"2024-01-15T12:00:00"}
        """.trimIndent()
        val dto = json.decodeFromString<SubsonicArtistDto>(fixture)
        assertTrue("starred must be true when date-string present", dto.starred)
    }

    @Test
    fun `SubsonicArtistDto toRecord produces correct Artist`() {
        val dto = SubsonicArtistDto(
            id = "ar3", name = "Blur", albumCount = 7, coverArt = null, starred = false,
        )
        val artist = dto.toRecord(updatedAt = 1000)
        assertEquals("ar3", artist.id)
        assertEquals("Blur", artist.name)
        assertEquals(7, artist.albumCount)
        assertNull(artist.coverArt)
        assertEquals(1000, artist.updatedAt)
    }

    // -------------------------------------------------------------------------
    // Album / SubsonicAlbumDto — name vs title duality
    // -------------------------------------------------------------------------

    @Test
    fun `SubsonicAlbumDto decodes name field as resolvedTitle (list context)`() {
        val fixture = """
            {"id":"al1","artistId":"ar1","name":"OK Computer","songCount":12,"year":1997}
        """.trimIndent()
        val dto = json.decodeFromString<SubsonicAlbumDto>(fixture)
        assertEquals("OK Computer", dto.resolvedTitle)
        assertEquals(12, dto.songCount)
        assertEquals(1997, dto.year)
    }

    @Test
    fun `SubsonicAlbumDto decodes title field as resolvedTitle (detail context)`() {
        val fixture = """
            {"id":"al2","artistId":"ar1","title":"Kid A","songCount":10,"year":2000}
        """.trimIndent()
        val dto = json.decodeFromString<SubsonicAlbumDto>(fixture)
        assertEquals("Kid A", dto.resolvedTitle)
    }

    @Test
    fun `SubsonicAlbumDto title wins over name when both present`() {
        val fixture = """
            {"id":"al3","artistId":"ar1","name":"NameField","title":"TitleField","songCount":5}
        """.trimIndent()
        val dto = json.decodeFromString<SubsonicAlbumDto>(fixture)
        assertEquals("TitleField", dto.resolvedTitle)
    }

    @Test
    fun `SubsonicAlbumDto starred is presence-based`() {
        val withStar = """
            {"id":"al4","artistId":"ar1","name":"Starred Album","starred":"2024-06-01T00:00:00"}
        """.trimIndent()
        val withoutStar = """
            {"id":"al5","artistId":"ar1","name":"Unstarred Album"}
        """.trimIndent()
        assertTrue(json.decodeFromString<SubsonicAlbumDto>(withStar).starred)
        assertFalse(json.decodeFromString<SubsonicAlbumDto>(withoutStar).starred)
    }

    @Test
    fun `SubsonicAlbumDto toRecord produces correct Album`() {
        val fixture = """
            {"id":"al6","artistId":"ar2","name":"Hail to the Thief","songCount":14,"year":2003,"genre":"Alternative","coverArt":"art-al6"}
        """.trimIndent()
        val dto = json.decodeFromString<SubsonicAlbumDto>(fixture)
        val album = dto.toRecord(updatedAt = 2000)
        assertEquals("al6", album.id)
        assertEquals("ar2", album.artistId)
        assertEquals("Hail to the Thief", album.title)
        assertEquals(14, album.trackCount)
        assertEquals(2003, album.year)
        assertEquals("Alternative", album.genre)
        assertEquals("art-al6", album.coverArt)
        assertEquals(2000, album.updatedAt)
    }

    // -------------------------------------------------------------------------
    // Track / SubsonicTrackDto
    // -------------------------------------------------------------------------

    @Test
    fun `SubsonicTrackDto decodes all fields including track→trackNumber mapping`() {
        val fixture = """
            {"id":"t1","albumId":"al1","artistId":"ar1","title":"Airbag","track":1,"duration":228,"genre":"Rock","bitRate":320,"suffix":"flac","contentType":"audio/flac","coverArt":"art-al1","artist":"Radiohead"}
        """.trimIndent()
        val dto = json.decodeFromString<SubsonicTrackDto>(fixture)
        assertEquals("t1", dto.id)
        assertEquals("al1", dto.albumId)
        assertEquals("ar1", dto.artistId)
        assertEquals("Airbag", dto.title)
        assertEquals(1, dto.trackNumber)
        assertEquals(228, dto.duration)
        assertEquals("Rock", dto.genre)
        assertEquals(320, dto.bitRate)
        assertEquals("flac", dto.suffix)
        assertEquals("audio/flac", dto.contentType)
        assertEquals("art-al1", dto.coverArt)
        assertEquals("Radiohead", dto.artistName)
        assertFalse(dto.starred)
    }

    @Test
    fun `SubsonicTrackDto starred is presence-based`() {
        val withStar = """
            {"id":"t2","albumId":"al1","artistId":"ar1","title":"Track","starred":"2024-01-01T00:00:00"}
        """.trimIndent()
        val withoutStar = """
            {"id":"t3","albumId":"al1","artistId":"ar1","title":"Track"}
        """.trimIndent()
        assertTrue(json.decodeFromString<SubsonicTrackDto>(withStar).starred)
        assertFalse(json.decodeFromString<SubsonicTrackDto>(withoutStar).starred)
    }

    @Test
    fun `SubsonicTrackDto toRecord maps track to trackNumber and defaults discNumber to 1`() {
        val fixture = """
            {"id":"t4","albumId":"al1","artistId":"ar1","title":"Paranoid Android","track":2,"duration":387}
        """.trimIndent()
        val dto = json.decodeFromString<SubsonicTrackDto>(fixture)
        val track = dto.toRecord(updatedAt = 3000)
        assertEquals("t4", track.id)
        assertEquals(2, track.trackNumber)
        assertEquals(387, track.duration)
        assertEquals(1, track.discNumber)
        assertEquals(3000, track.updatedAt)
    }

    // -------------------------------------------------------------------------
    // Genre: name comes from JSON key "value"
    // -------------------------------------------------------------------------

    @Test
    fun `Genre decodes value field as name`() {
        val fixture = """
            {"value":"Rock","songCount":42,"albumCount":8}
        """.trimIndent()
        val genre = json.decodeFromString<SubsonicGenre>(fixture)
        assertEquals("Rock", genre.name)
        assertEquals(42, genre.songCount)
        assertEquals(8, genre.albumCount)
    }

    @Test
    fun `Genre list decodes from genres wrapper`() {
        val fixture = """
            {"genres":{"genre":[
                {"value":"Rock","songCount":42,"albumCount":8},
                {"value":"Jazz","songCount":17,"albumCount":3}
            ]}}
        """.trimIndent()

        // Decode just the inner array using the wrapper
        val wrapper = json.decodeFromString<GenresWrapper>(fixture)
        assertEquals(2, wrapper.genres.genre.size)
        assertEquals("Rock", wrapper.genres.genre[0].name)
        assertEquals("Jazz", wrapper.genres.genre[1].name)
    }
}
