package com.ikunshare.music.model

private val qualityNames = mapOf(
    "128k" to "普通音质 128K",
    "320k" to "高品音质 320K",
    "flac" to "无损音质 FLAC",
    "hires" to "无损音质 HiRes",
    "master" to "臻品母带",
    "atmos" to "臻品全景声",
    "atmos_plus" to "高清臻音",
    "dolby" to "杜比全景声",
    "vivid" to "臻音全景声"
)

private val sourceQualityNames = mapOf(
    "wy" to mapOf("master" to "超清母带", "atmos" to "沉浸环绕声"),
    "kw" to mapOf("master" to "至臻母带", "atmos" to "至臻全景声"),
    "kg" to mapOf("master" to "蝰蛇超清"),
    "am" to mapOf(
        "320k" to "高品音质 AAC 256K",
        "flac" to "无损音质 ALAC",
        "hires" to "高解析无损 ALAC"
    )
)

fun qualityDisplayName(id: String, source: String? = null): String =
    sourceQualityNames[source]?.get(id) ?: qualityNames[id] ?: id
