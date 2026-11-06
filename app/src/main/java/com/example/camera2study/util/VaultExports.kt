package com.example.camera2study.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Application-owned copy queue. Cancellation is durable before it is acknowledged to the UI. */
object VaultExports {
    data class Job(val id: String, val path: String, val size: Long, val modified: Long,
        val state: String = "QUEUED", val done: Long = 0, val uri: String = "", val error: String = "") {
        val busy get() = state in listOf("QUEUED", "COPYING", "VERIFYING", "CANCEL_REQUESTED")
        val cancelRequested get() = state == "CANCEL_REQUESTED" || state == "CANCELED"
    }
    val state = MutableLiveData<List<Job>>(emptyList())
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private val jobs = linkedMapOf<String, Job>()
    private var context: Context? = null
    private lateinit var journal: AtomicFile

    fun initialize(ctx: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        context = ctx.applicationContext
        journal = AtomicFile(File(ctx.filesDir, "vault_exports.json"))
        runCatching {
            val array = JSONArray(journal.openRead().bufferedReader().use { it.readText() })
            for (i in 0 until array.length()) {
                val j = array.getJSONObject(i)
                val job = Job(j.getString("id"), j.getString("path"), j.getLong("size"), j.getLong("modified"),
                    j.getString("state"), j.optLong("done"), j.optString("uri"), j.optString("error"))
                jobs[job.id] = job
            }
        }
        publish()
        // Cancellation is resumed only to remove its pending public row, never to copy.
        jobs.values.filter { it.busy }.toList().forEach { worker.execute { perform(it.id) } }
    }

    fun enqueue(ctx: Context, file: File): String = synchronized(lock) {
        initialize(ctx)
        check(file.absolutePath !in RecordingJournal.activePaths) { "기록 중인 파일입니다" }
        check(file.isFile) { "원본 파일이 없습니다" }
        jobs.values.firstOrNull { it.path == file.absolutePath && it.size == file.length() && it.modified == file.lastModified() &&
            (it.busy || (it.state == "COMPLETE" && published(it))) }?.let { return@synchronized it.id }
        val job = Job(java.util.UUID.randomUUID().toString(), file.absolutePath, file.length(), file.lastModified())
        replace(job)
        worker.execute { perform(job.id) }
        job.id
    }

    fun isBusy(path: String) = synchronized(lock) { jobs.values.any { it.path == path && it.busy } }
    fun deleteSource(file: File): Boolean = synchronized(lock) {
        if (isBusy(file.absolutePath) || file.absolutePath in RecordingJournal.activePaths) false else file.delete()
    }

    /** A completed publication wins the race. Cancellation never deletes a completed export. */
    fun cancel(id: String): Boolean = synchronized(lock) {
        val job = jobs[id] ?: return@synchronized false
        if (!job.busy || job.cancelRequested) return@synchronized false
        replace(job.copy(state = "CANCEL_REQUESTED", error = ""))
        worker.execute { perform(id) }
        true
    }

    fun retry(id: String) = synchronized(lock) {
        val job = jobs[id] ?: return@synchronized
        if (job.state == "CANCEL_REQUESTED") {
            worker.execute { perform(id) }
            return@synchronized
        }
        if (job.busy || job.state == "COMPLETE") return@synchronized
        replace(job.copy(state = "QUEUED", error = "", done = 0))
        worker.execute { perform(id) }
    }

    /** Called with lock held. Roll back memory too when durable state cannot be written. */
    private fun replace(job: Job) {
        val previous = jobs.put(job.id, job)
        try { persist() } catch (error: Exception) {
            if (previous == null) jobs.remove(job.id) else jobs[job.id] = previous
            throw error
        }
        publish()
    }

    private fun canceled(id: String) = synchronized(lock) { jobs[id]?.cancelRequested != false }

    private fun perform(id: String) {
        val ctx = context ?: return
        val initial = synchronized(lock) { jobs[id] } ?: return
        if (!initial.busy) return
        try {
            if (initial.cancelRequested) { finishCancellation(id); return }
            synchronized(lock) {
                val job = jobs[id] ?: return
                check(!job.cancelRequested) { "사용자 취소" }
                // Publication may have succeeded immediately before process interruption.
                if (job.uri.isNotEmpty() && published(job)) {
                    replace(job.copy(state = "COMPLETE", done = job.size, error = ""))
                    return
                }
            }
            if (initial.uri.isNotEmpty()) deleteOutput(initial.uri)
            val file = File(initial.path)
            check(file.isFile && file.length() == initial.size && file.lastModified() == initial.modified) { "원본 파일이 삭제되거나 변경됐습니다" }
            synchronized(lock) {
                val job = jobs[id] ?: return
                check(!job.cancelRequested) { "사용자 취소" }
                replace(job.copy(state = "COPYING", uri = "", done = 0, error = ""))
            }
            var last = 0L
            VideoFiles.exportCopy(ctx, file,
                created = { uri -> synchronized(lock) {
                    val job = jobs[id] ?: error("내보내기 작업이 없습니다")
                    // Keep the URI even if cancellation won during MediaStore insertion.
                    replace(job.copy(uri = uri.toString()))
                } },
                progress = { done, total, verify ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last > 400 || done == total) {
                        last = now
                        synchronized(lock) {
                            val job = jobs[id] ?: error("내보내기 작업이 없습니다")
                            check(!job.cancelRequested) { "사용자 취소" }
                            replace(job.copy(state = if (verify) "VERIFYING" else "COPYING", done = done))
                        }
                    }
                }, canceled = { canceled(id) },
                commitPublication = { uri, publish -> synchronized(lock) {
                    val job = jobs[id] ?: error("내보내기 작업이 없습니다")
                    check(!job.cancelRequested) { "사용자 취소" }
                    publish()
                    replace(job.copy(state = "COMPLETE", done = job.size, uri = uri.toString(), error = ""))
                } })
        } catch (error: Exception) {
            runCatching {
                val needsCleanup = synchronized(lock) {
                    val job = jobs[id] ?: return@synchronized false
                    if (job.cancelRequested) true else {
                        replace(job.copy(state = "ERROR", error = error.message.orEmpty()))
                        false
                    }
                }
                if (needsCleanup) finishCancellation(id)
            }.onFailure { android.util.Log.e("VaultExports", "Cannot save export result", it) }
        }
    }

    private fun finishCancellation(id: String) {
        val job = synchronized(lock) { jobs[id] } ?: return
        if (!job.cancelRequested) return
        try {
            if (job.uri.isNotEmpty()) deleteOutput(job.uri)
            synchronized(lock) {
                jobs[id]?.takeIf { it.cancelRequested }?.let {
                    replace(it.copy(state = "CANCELED", uri = "", done = 0, error = "사용자 취소"))
                }
            }
        } catch (error: Exception) {
            synchronized(lock) {
                jobs[id]?.let { replace(it.copy(state = "CANCEL_REQUESTED", error = "공개 항목 정리 실패: ${error.message}")) }
            }
        }
    }

    private fun deleteOutput(value: String) {
        val resolver = context!!.contentResolver
        val uri = Uri.parse(value)
        if (!outputExists(resolver, uri)) return
        try {
            if (resolver.delete(uri, null, null) > 0) return
        } catch (denied: SecurityException) {
            // Another cleanup may have removed the row after our query. A denied collection
            // query (or a row that is still present) must remain a retryable cleanup failure.
            if (outputExists(resolver, uri)) throw denied
            return
        }
        check(!outputExists(resolver, uri)) { "내보내기 항목을 삭제할 수 없습니다" }
    }

    private fun outputExists(resolver: android.content.ContentResolver, uri: Uri): Boolean {
        val collection = uri.buildUpon().path(uri.path?.substringBeforeLast('/')).clearQuery().fragment(null).build()
        val volume = uri.pathSegments.firstOrNull() ?: error("내보내기 저장소 이름이 없습니다")
        check(collection == MediaStore.Video.Media.getContentUri(volume) || collection == MediaStore.Images.Media.getContentUri(volume)) {
            "알 수 없는 내보내기 저장 위치입니다"
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns._ID} = ?"
        val ids = arrayOf(android.content.ContentUris.parseId(uri).toString())
        val cursor = if (android.os.Build.VERSION.SDK_INT >= 30) {
            val query = android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, ids)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, projection, query, null)
        } else if (android.os.Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), projection, selection, ids, null)
        } else error("Android 10 이상에서 내보내기 항목을 정리할 수 있습니다")
        return checkNotNull(cursor) { "내보내기 항목 존재 여부를 확인할 수 없습니다" }.use { it.moveToFirst() }
    }

    private fun published(job: Job): Boolean = runCatching {
        if (job.uri.isEmpty()) return false
        val resolver = context!!.contentResolver
        if (resolver.openFileDescriptor(Uri.parse(job.uri), "r")?.use { it.statSize == job.size } != true) return false
        resolver.query(Uri.parse(job.uri), arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
            it.moveToFirst() && it.getLong(0) == job.size && it.getInt(1) == 0
        } == true
    }.getOrDefault(false)
    private fun publish() { state.postValue(jobs.values.toList().reversed()) }
    private fun persist() {
        val array = JSONArray()
        jobs.values.forEach { j -> array.put(JSONObject().put("id", j.id).put("path", j.path).put("size", j.size)
            .put("modified", j.modified).put("state", j.state).put("done", j.done).put("uri", j.uri).put("error", j.error)) }
        val output = journal.startWrite()
        try { output.write(array.toString().toByteArray()); journal.finishWrite(output) }
        catch (error: Exception) { journal.failWrite(output); throw error }
    }
}
