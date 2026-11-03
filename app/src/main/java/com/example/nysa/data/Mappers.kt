package com.example.nysa.data

import com.example.nysa.data.local.TitleEntity
import com.example.nysa.data.model.Family
import com.example.nysa.data.model.TitleItem

fun TitleItem.toEntity(): TitleEntity = TitleEntity(
    id = id,
    family = family.name,
    sourceId = sourceId,
    slug = slug,
    name = name,
    originName = originName,
    posterUrl = posterUrl,
    thumbUrl = thumbUrl,
    quality = quality,
    versionBadge = versionBadge,
    latestLabel = latestLabel,
    episodeCount = episodeCount,
    year = year,
    updatedEpochSec = updatedEpochSec
)

fun TitleEntity.toItem(): TitleItem = TitleItem(
    id = id,
    family = runCatching { Family.valueOf(family) }.getOrDefault(Family.PHIM),
    sourceId = sourceId,
    slug = slug,
    name = name,
    originName = originName,
    posterUrl = posterUrl,
    thumbUrl = thumbUrl,
    quality = quality,
    versionBadge = versionBadge,
    latestLabel = latestLabel,
    episodeCount = episodeCount,
    year = year,
    updatedEpochSec = updatedEpochSec
)

/** Unit word for progress labels — NySa is video-only, always "tập". */
fun Family.unitWord(): String = "tập"
