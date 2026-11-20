package com.moying.app

internal const val CatalogRecommend = "推荐"
internal const val CatalogSubtype = "subcategory"

internal data class CatalogChannel(val id: String, val label: String)

internal data class CatalogOption(val value: String, val label: String)

internal data class CatalogFacet(
    val key: String,
    val label: String,
    val options: List<CatalogOption>,
    val defaultValue: String = "",
)

internal data class SourceCatalog(
    val channels: List<CatalogChannel>,
    val facetsByChannel: Map<String, List<CatalogFacet>> = emptyMap(),
    val facetsBySubtype: Map<String, Map<String, List<CatalogFacet>>> = emptyMap(),
) {
    fun facets(channelId: String, filters: Map<String, String> = emptyMap()): List<CatalogFacet> {
        val facets = facetsByChannel[channelId].orEmpty()
        val subtype = filters[CatalogSubtype] ?: facets.firstOrNull { it.key == CatalogSubtype }?.defaultValue
        return facets + facetsBySubtype[channelId]?.get(subtype).orEmpty()
    }

    companion object {
        val Recommendation = SourceCatalog(listOf(CatalogChannel(CatalogRecommend, CatalogRecommend)))
    }
}
