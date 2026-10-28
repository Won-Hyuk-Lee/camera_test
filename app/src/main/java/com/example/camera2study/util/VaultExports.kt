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

/** Persisted application-owned queue. Process interruption resumes when the app next opens. */
object VaultExports {
    data class Job(val id: String, val path: String, val size: Long, val modified: Long,
        val state: String = "QUEUED", val done: Long = 0, val uri: String = "", val error: String = "") {
        val busy get() = state in listOf("QUEUED", "COPYING", "VERIFYING")
    }
    val state = MutableLiveData<List<Job>>(emptyList())
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private val jobs = linkedMapOf<String, Job>()
    private val canceled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var context: Context? = null
    private lateinit var journal: AtomicFile
    fun initialize(ctx: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        context = ctx.applicationContext
        journal = AtomicFile(File(ctx.filesDir,"vault_exports.json"))
        runCatching {
            val array = JSONArray(journal.openRead().bufferedReader().use { it.readText() })
            for (i in 0 until array.length()) {
                val j = array.getJSONObject(i)
                val job = Job(j.getString("id"),j.getString("path"),j.getLong("size"),j.getLong("modified"),
                    j.getString("state"),j.optLong("done"),j.optString("uri"),j.optString("error"))
                jobs[job.id] = job
            }
        }
        publish()
        jobs.values.filter { it.busy }.toList().forEach { worker.execute { perform(it.id) } }
    }
    fun enqueue(ctx: Context, file: File): String = synchronized(lock) {
        initialize(ctx)
        check(file.absolutePath !in RecordingJournal.activePaths) { "기록 중인 파일입니다" }
        jobs.values.firstOrNull { it.path == file.absolutePath && it.size == file.length() && it.modified == file.lastModified() &&
            (it.busy || (it.state == "COMPLETE" && published(it))) }?.let { return@synchronized it.id }
        val job = Job(java.util.UUID.randomUUID().toString(),file.absolutePath,file.length(),file.lastModified())
        jobs[job.id] = job; persist(); publish()
        worker.execute { perform(job.id) }; job.id
    }
    fun isBusy(path: String) = synchronized(lock) { jobs.values.any { it.path == path && it.busy } }
    fun deleteSource(file: File): Boolean = synchronized(lock) {
        if (isBusy(file.absolutePath) || file.absolutePath in RecordingJournal.activePaths) false else file.delete()
    }
    fun cancel(id: String) { canceled.add(id) }
    fun retry(id: String) = synchronized(lock) {
        val job = jobs[id] ?: return@synchronized
        if (job.busy || job.state == "COMPLETE") return@synchronized
        canceled.remove(id); jobs[id] = job.copy(state = "QUEUED",error = "",done = 0)
        persist(); publish(); worker.execute { perform(id) }
    }
    private fun update(id: String, change: (Job) -> Job) = synchronized(lock) {
        jobs[id]?.let { jobs[id] = change(it) }; persist(); publish()
    }
    private fun perform(id: String) {
        val ctx = context ?: return
        val initial = synchronized(lock) { jobs[id] } ?: return
        try {
            // Publication may have succeeded just before a process interruption.
            if (initial.uri.isNotEmpty() && published(initial)) {
                update(id) { it.copy(state = "COMPLETE",done = it.size) }; return
            }
            if (initial.uri.isNotEmpty()) runCatching { ctx.contentResolver.delete(Uri.parse(initial.uri),null,null) }
            check(!canceled.contains(id)) { "사용자 취소" }
            val file = File(initial.path)
            check(file.isFile && file.length() == initial.size && file.lastModified() == initial.modified) { "원본 파일이 삭제되거나 변경됐습니다" }
            update(id) { it.copy(state = "COPYING",uri = "",done = 0,error = "") }
            var last = 0L
            val uri = VideoFiles.exportCopy(ctx,file,
                created = { uri -> update(id) { it.copy(uri = uri.toString()) } },
                progress = { done,total,verify ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last > 400 || done == total) {
                        last = now; update(id) { it.copy(state = if (verify) "VERIFYING" else "COPYING",done = done) }
                    }
                }, canceled = { canceled.contains(id) })
            update(id) { it.copy(state = "COMPLETE",done = it.size,uri = uri.toString()) }
        } catch (e: Exception) {
            update(id) { it.copy(state = if (canceled.contains(id)) "CANCELED" else "ERROR",error = e.message.orEmpty()) }
        }
    }
    private fun published(job: Job): Boolean = runCatching {
        if (job.uri.isEmpty()) return false
        val resolver = context!!.contentResolver
        if (resolver.openFileDescriptor(Uri.parse(job.uri), "r")?.use { it.statSize == job.size } != true) return false
        resolver.query(Uri.parse(job.uri),arrayOf(MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.IS_PENDING),null,null,null)?.use {
            it.moveToFirst() && it.getLong(0) == job.size && it.getInt(1) == 0
        } == true
    }.getOrDefault(false)
    private fun publish() { state.postValue(jobs.values.toList().reversed()) }
    private fun persist() {
        val array = JSONArray()
        jobs.values.forEach { j -> array.put(JSONObject().put("id",j.id).put("path",j.path).put("size",j.size)
            .put("modified",j.modified).put("state",j.state).put("done",j.done).put("uri",j.uri).put("error",j.error)) }
        val output = journal.startWrite()
        try { output.write(array.toString().toByteArray()); journal.finishWrite(output) }
        catch (e: Exception) { journal.failWrite(output); throw e }
    }
}
