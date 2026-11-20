package com.moying.app

private const val LocalGenre = "genre"
private const val LocalRegion = "region"
private const val LocalYear = "year"
private const val LocalSort = "sort"
private const val SortByYear = "year"
private const val SortByScore = "score"
private const val GroupPrefix = "local:"

private val metadataSeparators = Regex("[/、,，|;；]+")
private val missingMetadata = setOf("未知", "不详", "暂无", "无", "-", "—", "0", "null", "none", "n/a")
private val yearPattern = Regex("""(?<!\d)(?:18|19|20|21)\d{2}(?!\d)""")

internal fun buildLocalCatalog(movies: Collection<Movie>): SourceCatalog {
    val grouped = movies.groupBy { localChannelLabel(it.category) }
    val channels = listOf(CatalogChannel(CatalogRecommend, CatalogRecommend)) +
        grouped.keys.map { CatalogChannel(localChannelId(it), it) }
    val facets = buildMap {
        put(CatalogRecommend, localCatalogFacets(movies))
        for ((label, group) in grouped) put(localChannelId(label), localCatalogFacets(group))
    }
    return SourceCatalog(channels, facets)
}

internal fun localCatalogPage(
    movies: Collection<Movie>,
    page: Int,
    channelId: String,
    query: String,
    filters: Map<String, String>,
): MoviePage {
    val keyword = query.trim()
    val genre = filters[LocalGenre].orEmpty()
    val region = filters[LocalRegion].orEmpty()
    val year = filters[LocalYear].orEmpty()
    val matches = movies.filter { movie ->
        (channelId == CatalogRecommend || localChannelId(localChannelLabel(movie.category)) == channelId) &&
            movie.title.contains(keyword, ignoreCase = true) &&
            (genre.isEmpty() || metadataValues(movie.genres).any { it.equals(genre, ignoreCase = true) }) &&
            (region.isEmpty() || metadataValues(movie.region).any { it.equals(region, ignoreCase = true) }) &&
            (year.isEmpty() || catalogYears(movie.year).any { it.toString() == year })
    }
    val sorted = when (filters[LocalSort]) {
        SortByYear -> matches.map { it to catalogYears(it.year).maxOrNull() }.sortedByDescending { it.second }.map { it.first }
        SortByScore -> matches.map { it to catalogScore(it.score) }.sortedByDescending { it.second }.map { it.first }
        else -> matches
    }
    val start = page.coerceAtLeast(Catalog.FirstPage).toLong() * Catalog.PageSize
    val end = (start + Catalog.PageSize).coerceAtMost(sorted.size.toLong())
    if (start >= end) return MoviePage(emptyList(), false)
    return MoviePage(sorted.subList(start.toInt(), end.toInt()), end < sorted.size)
}

private fun localChannelLabel(category: String): String = category.trim().ifBlank { "未分类" }

// Reserve the synthetic recommendation id without losing a playlist group with the same name.
private fun localChannelId(label: String): String =
    if (label == CatalogRecommend || label.startsWith(GroupPrefix)) GroupPrefix + label else label

private fun localCatalogFacets(movies: Collection<Movie>): List<CatalogFacet> = buildList {
    val genres = movies.flatMap { metadataValues(it.genres) }.distinctBy { it.lowercase() }
    val regions = movies.flatMap { metadataValues(it.region) }.distinctBy { it.lowercase() }
    val years = movies.flatMap { catalogYears(it.year) }.distinct().sortedDescending().map { it.toString() }
    if (genres.isNotEmpty()) add(CatalogFacet(LocalGenre, "类型", genres.map { CatalogOption(it, it) }))
    if (regions.isNotEmpty()) add(CatalogFacet(LocalRegion, "地区", regions.map { CatalogOption(it, it) }))
    if (years.isNotEmpty()) add(CatalogFacet(LocalYear, "年份", years.map { CatalogOption(it, it) }))

    val sorts = buildList {
        add(CatalogOption("", "默认"))
        if (years.isNotEmpty()) add(CatalogOption(SortByYear, "年份从新到旧"))
        if (movies.any { catalogScore(it.score) != null }) add(CatalogOption(SortByScore, "评分"))
    }
    if (sorts.size > 1) add(CatalogFacet(LocalSort, "排序", sorts))
}

private fun metadataValues(text: String): List<String> {
    if (text.trim().lowercase() in missingMetadata) return emptyList()
    return text.split(metadataSeparators).map { it.trim() }.filter {
        it.isNotEmpty() && it.lowercase() !in missingMetadata
    }
}

private fun catalogYears(text: String): List<Int> = yearPattern.findAll(text).map { it.value.toInt() }.toList()

private fun catalogScore(text: String): Double? =
    text.trim().removeSuffix("分").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
