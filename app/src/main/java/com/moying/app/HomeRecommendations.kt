package com.moying.app

import java.text.Normalizer

/** 轮流展示各分类，同一分类内保留片源顺序。 */
internal fun mixHomeRecommendations(movies: List<Movie>): List<Movie> {
    if (movies.size < 2) return movies

    val categories = movies.groupBy { it.category.trim().ifBlank { "未分类" } }
    val broadGroups = movies.groupBy { movie ->
        val category = movie.category.trim().ifBlank { "未分类" }
        standardChannel(category) ?: category
    }
    val groups = when {
        broadGroups.size > 1 -> broadGroups
        categories.size > 1 -> categories
        else -> movies.groupBy { metadataValues(it.genres).firstOrNull().orEmpty() }
    }
    if (groups.size == 1) return movies

    val remaining = ArrayDeque(groups.values.map { it.iterator() })
    return buildList(movies.size) {
        while (remaining.isNotEmpty()) {
            val group = remaining.removeFirst()
            add(group.next())
            if (group.hasNext()) remaining.addLast(group)
        }
    }
}

private val recommendationWhitespace = Regex("\\s+")

internal fun Movie.recommendationTitleKey(): Pair<String, String> {
    val normalizedTitle = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .trim().lowercase().replace(recommendationWhitespace, " ")
    val normalizedYear = Normalizer.normalize(year, Normalizer.Form.NFKC).trim()
    return normalizedTitle to normalizedYear
}
