package com.moying.app

internal data class CmsSourceCatalog(
    val directory: SourceCatalog,
    val typeByChannel: Map<String, String>,
)

internal fun buildCmsSourceCatalog(
    categories: List<SourceCmsCategory>,
    nativeFacets: Map<String, List<CatalogFacet>>,
): CmsSourceCatalog {
    val entries = categories.associateBy { it.id }
    fun rootOf(category: SourceCmsCategory): SourceCmsCategory {
        val visited = mutableSetOf(category.id)
        var root = category
        while (true) {
            val parent = entries[root.parentId] ?: return root
            if (!visited.add(parent.id)) return category
            root = parent
        }
    }

    val channels = mutableListOf(CatalogChannel(CatalogRecommend, CatalogRecommend))
    val typeByChannel = mutableMapOf<String, String>()
    val facetsByChannel = mutableMapOf<String, List<CatalogFacet>>()
    val facetsBySubtype = mutableMapOf<String, Map<String, List<CatalogFacet>>>()
    val parentIds = categories.mapNotNull { it.parentId }.toSet()
    val groups = categories.groupBy { category ->
        val root = rootOf(category)
        standardChannel(root.name)?.let { "group:$it" } ?: "cms:${root.id}"
    }

    for ((channelId, types) in groups) {
        val root = rootOf(types.first())
        val grouped = types.size > 1
        val label = if (grouped && channelId.startsWith("group:")) channelId.removePrefix("group:") else root.name
        channels += CatalogChannel(channelId, if (label == CatalogRecommend) "片源推荐" else label)
        if (!grouped) {
            typeByChannel[channelId] = types.single().id
            facetsByChannel[channelId] = nativeFacets[types.single().id].orEmpty()
            continue
        }
        val ordered = types.sortedBy { it.id in parentIds || it.name in BroadCmsCategories }
        typeByChannel[channelId] = ordered.first().id
        facetsByChannel[channelId] = listOf(CatalogFacet(
            CatalogSubtype, "子分类",
            ordered.map { CatalogOption(it.id, if (it.id in parentIds) "${it.name}（直属）" else it.name) },
            defaultValue = ordered.first().id,
        ))
        facetsBySubtype[channelId] = ordered.associate { it.id to nativeFacets[it.id].orEmpty() }
    }
    return CmsSourceCatalog(SourceCatalog(channels, facetsByChannel, facetsBySubtype), typeByChannel)
}

private val BroadCmsCategories = setOf(
    "电影", "电影片", "电视剧", "连续剧", "剧集", "动漫", "动画", "综艺", "纪录片", "短剧",
)

private val StandardCmsChannels = mapOf(
    "电影" to setOf("电影", "电影片", "动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "剧情片",
        "战争片", "犯罪片", "悬疑片", "惊悚片", "灾难片", "武侠片", "奇幻片", "冒险片", "动画电影"),
    "剧集" to setOf("电视剧", "连续剧", "剧集", "国产剧", "大陆剧", "香港剧", "港剧", "港台剧",
        "台剧", "台湾剧", "欧美剧", "美国剧", "英剧", "美剧", "韩剧", "韩国剧", "日剧", "日本剧",
        "日韩剧", "泰剧", "泰国剧", "海外剧", "其他剧", "其它剧"),
    "动漫" to setOf("动漫", "动画", "动漫片", "动画片", "国产动漫", "日韩动漫", "日本动漫", "欧美动漫", "海外动漫"),
    "综艺" to setOf("综艺", "综艺片", "大陆综艺", "港台综艺", "日韩综艺", "欧美综艺", "国产综艺"),
    "纪录片" to setOf("纪录片", "记录片", "纪录"),
    "短剧" to setOf("短剧", "微短剧", "网络短剧"),
)

private fun standardChannel(name: String): String? = StandardCmsChannels.entries.firstOrNull { name in it.value }?.key
