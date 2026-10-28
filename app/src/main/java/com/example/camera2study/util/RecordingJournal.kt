package com.example.camera2study.util

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Small atomic metadata files; video bytes are never rewritten or deleted here. */
object RecordingJournal {
    private var recovered = false
    @Volatile var recoveredInterruption = false
        private set
    val activePaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun directory(context: Context) = File(context.filesDir, "recording_sessions").apply { mkdirs() }

    @Synchronized fun recover(context: Context) {
        if (recovered) return
        recovered = true
        directory(context).listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            runCatching {
                val session = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                if (session.optString("status") == "ACTIVE") {
                    recoveredInterruption = true
                    session.put("status", "INTERRUPTED").put("reason", "이전 프로세스에서 저장 완료를 확인하지 못했습니다")
                    val segments = session.getJSONArray("segments")
                    for (i in 0 until segments.length()) {
                        val segment = segments.getJSONObject(i)
                        if (segment.optString("status") == "RECORDING") segment.put("status", "CHECK")
                    }
                    save(context, session)
                }
            }.onFailure { Log.w("RecordingJournal", "Cannot inspect ${file.name}", it) }
        }
    }

    fun begin(context: Context, source: String, settings: String): JSONObject {
        recover(context)
        return JSONObject().put("id", "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
            .put("startedAt", System.currentTimeMillis()).put("source", source).put("settings", settings)
            .put("status", "ACTIVE").put("segments", JSONArray()).also { save(context, it) }
    }

    fun segment(context: Context, session: JSONObject, path: String, name: String): JSONObject {
        val item = JSONObject().put("name", name).put("path", path).put("status", "RECORDING")
            .put("startedAt", System.currentTimeMillis()).put("durationMs", 0).put("bytes", 0)
        session.getJSONArray("segments").put(item)
        if (path.isNotBlank()) activePaths.add(path)
        save(context, session)
        return item
    }

    fun finishSegment(context: Context, session: JSONObject, item: JSONObject, duration: Long,
                      bytes: Long, error: String?, uri: String) {
        activePaths.remove(item.optString("path"))
        item.put("status", if (error == null && bytes > 0 && duration > 0) "COMPLETE" else "CHECK")
            .put("durationMs", duration).put("bytes", bytes).put("error", error ?: "")
            .put("uri", uri).put("endedAt", System.currentTimeMillis())
        item.put("encodedBytes", bytes)
        refreshFileSize(context, item)
        save(context, session)
    }

    fun finish(context: Context, session: JSONObject, error: String?, reason: String) {
        session.put("status", if (error == null) "COMPLETE" else "ERROR")
            .put("reason", reason).put("error", error ?: "").put("endedAt", System.currentTimeMillis())
        save(context, session)
    }

    @Synchronized fun save(context: Context, session: JSONObject) {
        val atomic = AtomicFile(File(directory(context), "${session.getString("id")}.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(session.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            Log.e("RecordingJournal", "Recording diagnostics could not be saved", e)
        }
    }

    private fun refreshFileSize(context: Context, item: JSONObject) {
        val size = runCatching {
            val path = item.optString("path")
            if (path.isNotBlank()) File(path).takeIf { it.isFile }?.length()
            else item.optString("uri").takeIf { it.startsWith("content://") }?.let { value ->
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(value), "r")?.use { it.statSize.takeIf { bytes -> bytes >= 0 } }
            }
        }.getOrNull()
        item.put("bytes", size ?: 0).put("sizeKnown", size != null)
    }

    @Synchronized fun sessions(context: Context): List<JSONObject> = directory(context).listFiles()
        ?.filter { it.extension == "json" }?.sortedByDescending { it.name }?.mapNotNull {
            runCatching { JSONObject(AtomicFile(it).openRead().bufferedReader().use { reader -> reader.readText() }).also { session ->
                val files = session.getJSONArray("segments")
                for (index in 0 until files.length()) {
                    val item = files.getJSONObject(index)
                    if (item.optString("status") == "COMPLETE") refreshFileSize(context, item)
                }
            } }.getOrNull()
        }.orEmpty()
}
