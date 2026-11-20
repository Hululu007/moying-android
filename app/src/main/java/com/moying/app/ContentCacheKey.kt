package com.moying.app

import java.net.URI
import java.security.MessageDigest

/**
 * 缓存键的唯一生成入口。
 * 根条目按内容身份（影片 / 线路 / 集数）归并；HLS 的子清单与分片在根键上叠加变体标识。
 * 原始地址只作为回源元数据保存，不参与键计算，避免签名参数变化导致缓存整体失效。
 * 迁移 Media3 时可把这里的实现直接包装成自定义 CacheKeyFactory。
 */
internal object ContentCacheKey {
    /** 键格式版本；旧版以 SHA-256(URL) 命名，升级时据此识别并清理。 */
    private const val Schema = "c2"

    /** 输入：影片 / 线路 / 集数；输出：稳定的根内容键。 */
    fun root(movieId: String, routeId: String, episode: Int): String =
        "$Schema-${digest("$movieId\u0000$routeId\u0000$episode")}"

    /** 输入：父键与子清单/分片地址；输出：叠加变体标识后的子键。 */
    fun child(parentKey: String, childUrl: String): String =
        "$parentKey-${digest(canonical(childUrl)).take(24)}"

    /** 输入：无内容身份的地址；输出：兜底键，仅在非播放链路使用。 */
    fun fallback(url: String): String = "u$Schema-${digest(canonical(url))}"

    /** 输入：回源地址；输出：去掉签名参数的标识，只用于判断是否换片，不参与键计算。 */
    fun sourcePath(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.host}${uri.path}"
    }.getOrDefault(url.substringBefore('?'))

    /** 输入：磁盘文件名主体；输出：是否是升级前的 URL 指纹命名。 */
    fun isLegacyId(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))

    /** 输入：远程地址；输出：主机与路径组成的稳定标识，查询参数不参与。 */
    private fun canonical(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.host}${uri.path}"
    }.getOrDefault(url.substringAfter("://", url).substringBefore('?'))

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
}
