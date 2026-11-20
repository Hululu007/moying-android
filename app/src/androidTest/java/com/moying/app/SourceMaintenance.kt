package com.moying.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Explicitly invoked maintenance only; existing app preferences are backed up before repository use. */
object SourceMaintenance {
    private data class Round(val status: SourceAvailability?, val detail: String, val certain: Boolean = true) {
        fun json() = JSONObject().put("status", status?.name ?: "UNCERTAIN").put("detail", detail).put("certain", certain)
    }
    private data class Checked(val id: String, val first: Round, val second: Round?) {
        val available get() = first.status == SourceAvailability.AVAILABLE || second?.status == SourceAvailability.AVAILABLE
        val candidate get() = first.certain && second?.certain == true &&
            first.status in setOf(SourceAvailability.FAILED, SourceAvailability.UNSUPPORTED) &&
            second?.status in setOf(SourceAvailability.FAILED, SourceAvailability.UNSUPPORTED)
    }

    fun run(instrumentation: Instrumentation, arguments: Bundle) {
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "moying-source-maintenance")
        val reportFile = File(root, "report.json")
        val mode = arguments.getString("maintenance").orEmpty()
        val report = JSONObject().put("mode", mode).put("startedAt", System.currentTimeMillis())
        var failure: Throwable? = null
        var deleted = 0
        var available = 0
        var total = 0
        var invalid = 0; var failed = 0
        var historyBefore: List<String?>? = null
        try {
            require(mode in setOf("dry-run", "apply")) { "maintenance must be dry-run or apply" }
            check(root.isDirectory || root.mkdirs()) { "Cannot create maintenance directory" }
            val backup = File(root, "before/${UUID.randomUUID()}")
            check(backup.mkdirs()) { "Cannot create backup directory" }
            val prefs = File(context.applicationInfo.dataDir, "shared_prefs")
            val files = if (prefs.exists()) checkNotNull(prefs.listFiles()) else emptyArray()
            files.forEach { source ->
                check(source.isFile) { "Unexpected preference directory" }
                val bytes = source.readBytes()
                val destination = File(backup, source.name)
                FileOutputStream(destination).use { it.write(bytes); it.fd.sync() }
                check(destination.readBytes().contentEquals(bytes)) { "Preference backup verification failed" }
            }
            report.put("backupDirectory", backup.absolutePath).put("backedUpFiles", files.size).put("backupVerified", true)
            save(reportFile, report)
            historyBefore = historyStrings(context)
            val configurationBefore = configuration(context)
            val repository = TvBoxMovieRepository(context)
            val originalSelection = repository.selectedSourceId
            val targets = repository.sources.filter { it.id != "local" }.toList()
            total = targets.size
            var completed = 0
            val checked = runBlocking {
                val slots = Semaphore(3)
                coroutineScope {
                    targets.map { source -> async(Dispatchers.IO) {
                        slots.withPermit {
                            val first = try {
                                SourceAvailabilityProbe.check(source).let {
                                    Round(it.status, it.detail, it.detail !in setOf("检测未完成，请重试", "检测任务繁忙，请稍后重试"))
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { Round(null, "Initial check was inconclusive; preserve", false) }
                            val second = if (first.status == SourceAvailability.AVAILABLE) null else retry(source)
                            synchronized(report) {
                                completed++
                                instrumentation.sendStatus(0, Bundle().apply {
                                    putString("stream", "$completed/$total ${(second ?: first).status?.name ?: "UNCERTAIN"}\n")
                                })
                            }
                            Checked(source.id, first, second)
                        }
                    } }.awaitAll()
                }
            }
            available = checked.count { it.available }
            invalid = checked.count { (it.second ?: it.first).status == SourceAvailability.UNSUPPORTED }
            failed = checked.count { (it.second ?: it.first).status == SourceAvailability.FAILED }
            val rows = JSONArray()
            checked.forEach { result -> rows.put(JSONObject().put("sourceId", result.id)
                .put("first", result.first.json()).put("second", result.second?.json() ?: JSONObject.NULL)
                .put("dropCandidate", result.candidate).put("drop", available > 0 && result.candidate).put("deleted", false)) }
            report.put("total", total).put("available", available).put("abortedNoAvailable", available == 0)
                .put("results", rows).put("invalid", invalid).put("failed", failed).put("stage", "checked-before-deletion")
                .put("configurationUnchanged", configuration(context) == configurationBefore)
            check(historyStrings(context) == historyBefore) { "History changed during checks" }
            check(configuration(context) == configurationBefore) { "Source configuration changed during checks" }
            save(reportFile, report)
            if (mode == "apply" && available > 0) {
                val unchanged = configuration(context) == configurationBefore && historyStrings(context) == historyBefore
                report.put("configurationUnchanged", unchanged)
                check(unchanged) { "Source configuration or history changed before deletion" }
                checked.forEachIndexed { index, result ->
                    if (result.candidate) {
                        check(repository.deleteSource(result.id)) { "Source changed before deletion" }
                        rows.getJSONObject(index).put("deleted", true)
                        deleted++
                    }
                }
                if (repository.sources.none { it.id == originalSelection }) {
                    checked.firstOrNull { it.available && repository.sources.any { source -> source.id == it.id } }
                        ?.let { repository.selectSource(it.id) }
                }
                // 等待此前 apply 排队的写盘，再结束 Instrumentation 进程。
                check(context.getSharedPreferences("moying_tvbox_v1", Context.MODE_PRIVATE).edit().commit()) {
                    "Cannot flush source preferences"
                }
                val reloaded = TvBoxMovieRepository(context)
                check(reloaded.sources.map { it.id } == repository.sources.map { it.id } &&
                    reloaded.selectedSourceId == repository.selectedSourceId) { "Source reload verification failed" }
                report.put("preferencesFlushed", true).put("remaining", reloaded.sources.count { it.id != "local" })
            }
            check(historyStrings(context) == historyBefore) { "History changed during maintenance" }
            report.put("stage", if (available == 0) "aborted-no-available" else "complete")
            if (mode == "apply" && available == 0) failure = IllegalStateException("No available sources; cleanup aborted")
        } catch (error: Throwable) {
            failure = error
            report.put("stage", "failed").put("errorClass", error.javaClass.simpleName)
        } finally {
            val unchanged = historyBefore?.let { runCatching { historyStrings(context) == it }.getOrDefault(false) }
            if (unchanged == false && failure == null) failure = IllegalStateException("History changed")
            report.put("deleted", deleted).put("historyUnchanged", unchanged ?: JSONObject.NULL)
                .put("finishedAt", System.currentTimeMillis()).put("success", failure == null)
            try { save(reportFile, report) } catch (error: Throwable) { failure = failure ?: error }
            instrumentation.finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putInt("total", total); putInt("available", available); putInt("deleted", deleted)
                putInt("invalid", invalid); putInt("failed", failed); putBoolean("abortedNoAvailable", available == 0)
                putString("report", reportFile.absolutePath)
                putString("stream", "maintenance=$mode status=${report.optString("stage")} total=$total available=$available invalid=$invalid failed=$failed deleted=$deleted report=${reportFile.absolutePath}\n")
                failure?.let { putString("error", "Maintenance failed (${it.javaClass.simpleName}); inspect local report") }
            })
        }
    }

    private fun retry(source: TvBoxSource): Round {
        if (source.type !in listOf(0, 1) || source.format !in listOf("cms", "m3u") || !SourceImport.validUrl(source.api))
            return Round(SourceAvailability.UNSUPPORTED, "Unsupported source configuration")
        val body = try { SourceImport.download(if (source.format == "m3u") source.api else cmsUrl(source)) }
        catch (interrupted: InterruptedException) { throw CancellationException("Maintenance cancelled").apply { initCause(interrupted) } }
        catch (error: Exception) {
            val known = error is IOException || (error is IllegalArgumentException &&
                (error.message.orEmpty().startsWith("订阅请求失败") || error.message.orEmpty().contains("超过 4 MiB")))
            return Round(if (known) SourceAvailability.FAILED else null,
                if (known) "Retry connection or HTTP request failed" else "Retry was inconclusive; preserve", known)
        }
        if (body.isBlank()) return Round(SourceAvailability.EMPTY, "Empty response; preserve")
        if (source.format == "m3u") {
            if (body.lineSequence().any { it.trimStart().startsWith("#EXT-X-", true) })
                return Round(SourceAvailability.UNSUPPORTED, "HLS media playlist is not a channel directory")
            return if (SourceImport.playlistRows(body, source.api).first.isNotEmpty()) Round(SourceAvailability.AVAILABLE, "Compatible channel directory")
            else if (body.trimStart('\uFEFF').trimStart().startsWith("#EXTM3U", true)) Round(SourceAvailability.EMPTY, "Empty channel directory; preserve")
            else Round(SourceAvailability.UNSUPPORTED, "Incompatible channel directory")
        }
        val document = try { SourceImport.cmsDocument(body) } catch (_: Exception) {
            return Round(SourceAvailability.UNSUPPORTED, "Incompatible AppleCMS JSON/XML directory")
        }
        if (document.rows.isEmpty()) return Round(SourceAvailability.EMPTY, "Empty AppleCMS directory; preserve")
        return if (document.rows.any { SourceImport.clean(it.optString("vod_name")).isNotBlank() && SourceImport.cmsRoutes(it).isNotEmpty() })
            Round(SourceAvailability.AVAILABLE, "Compatible directory with direct-media samples")
        else Round(SourceAvailability.UNSUPPORTED, "Directory samples have no supported direct-media routes")
    }

    private fun cmsUrl(source: TvBoxSource): String {
        val uri = Uri.parse(source.api)
        val builder = uri.buildUpon().clearQuery().fragment(null)
        uri.queryParameterNames.filterNot { it in setOf("ac", "pg", "wd", "t", "ids") }.forEach { name ->
            uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        return builder.appendQueryParameter("ac", "detail").appendQueryParameter("pg", "1").build().toString()
    }
    private fun historyStrings(context: Context): List<String?> = listOf(
        context.getSharedPreferences("moying_tvbox_v1", Context.MODE_PRIVATE).getString("history", null),
        context.getSharedPreferences("moying_tvbox_v1", Context.MODE_PRIVATE).getString("watchedMovies", null),
        context.getSharedPreferences("moying_sources_v1", Context.MODE_PRIVATE).getString("history", null))
    private fun configuration(context: Context): List<Any?> = context.getSharedPreferences("moying_tvbox_v1", Context.MODE_PRIVATE).let {
        listOf(it.getString("importedSources", null), it.getString("source", null), it.getStringSet("deletedSourceKeys", emptySet())?.toSet())
    }

    private fun save(file: File, value: JSONObject) {
        check(file.parentFile?.isDirectory == true) { "Maintenance directory is unavailable" }
        val temporary = File(file.parentFile, "report.json.tmp")
        FileOutputStream(temporary).use { it.write(value.toString(2).toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
