package com.adagiostream.android.service.library.db

import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.Artist
import com.adagiostream.android.service.navidrome.Track

// ============================================================================
// Domain <-> Room entity mappers (E6 offline cache).
//
// The domain records (Artist/Album/Track in NavidromeModels.kt) stay pure —
// no Room annotations — so these free functions bridge to the persistence layer.
// ============================================================================

fun Artist.toEntity(): ArtistEntity = ArtistEntity(
    id = id,
    name = name,
    sortName = sortName,
    albumCount = albumCount,
    coverArt = coverArt,
    starred = false,
    updatedAt = updatedAt,
)

fun ArtistEntity.toRecord(): Artist = Artist(
    id = id,
    name = name,
    sortName = sortName,
    albumCount = albumCount,
    coverArt = coverArt,
    updatedAt = updatedAt,
)

fun Album.toEntity(): AlbumEntity = AlbumEntity(
    id = id,
    artistId = artistId,
    title = title,
    sortTitle = sortTitle,
    year = year,
    genre = genre,
    trackCount = trackCount,
    coverArt = coverArt,
    starred = false,
    playCount = 0,
    updatedAt = updatedAt,
)

fun AlbumEntity.toRecord(): Album = Album(
    id = id,
    artistId = artistId,
    title = title,
    sortTitle = sortTitle,
    year = year,
    genre = genre,
    trackCount = trackCount,
    coverArt = coverArt,
    updatedAt = updatedAt,
)

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    albumId = albumId,
    artistId = artistId,
    title = title,
    artist = artist,
    trackNumber = trackNumber,
    discNumber = discNumber,
    duration = duration,
    genre = genre,
    bitRate = bitRate,
    suffix = suffix,
    contentType = contentType,
    coverArt = coverArt,
    path = path,
    starred = starred ?: false,
    playCount = playCount,
    updatedAt = updatedAt,
)

fun TrackEntity.toRecord(): Track = Track(
    id = id,
    albumId = albumId,
    artistId = artistId,
    title = title,
    artist = artist,
    trackNumber = trackNumber,
    discNumber = discNumber,
    duration = duration,
    genre = genre,
    bitRate = bitRate,
    suffix = suffix,
    contentType = contentType,
    coverArt = coverArt,
    path = path,
    starred = starred,
    playCount = playCount,
    updatedAt = updatedAt,
)
