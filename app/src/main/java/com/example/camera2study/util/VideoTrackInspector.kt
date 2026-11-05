package com.example.camera2study.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Call on an IO dispatcher. Reads sample timestamps, never decodes every video frame. */
object VideoTrackInspector {
    data class Report(val checkedAt: Long, val size: Long, val modified: Long, val durationMs: Long,
                      val timeline: VideoTimeline, val audioTrack: Boolean, val stopReason: String = "") {
        val label: String get() = when {
            !timeline.complete -> "시간정보 일부 검사 · 전체 미확인"
            timeline.needsReview || durationMs <= 0 -> "시간정보 확인 필요"
            else -> "시간정보 검사 완료"
        }
        fun details(): String = buildString {
            append(label)
            append("\n검사 범위: ").append(if (timeline.complete) "영상 트랙 전체" else "영상 트랙 일부")
            if (stopReason.isNotBlank()) append(" · ").append(stopReason)
            append("\n파일 길이: %.2f초".format(durationMs / 1000.0))
            append("\n영상 샘플: ").append(timeline.samples).append("개")
            append("\n첫/마지막 영상 시각: %.2f / %.2f초".format(timeline.firstUs / 1_000_000.0, timeline.lastUs / 1_000_000.0))
            append("\n가장 긴 영상 간격: %.3f초".format(timeline.maxGapUs / 1_000_000.0))
            if (timeline.complete) append("\n영상 끝 이후 남은 길이: %.2f초".format(timeline.missingTailUs / 1_000_000.0))
            append("\n음성 트랙: ").append(if (audioTrack) "있음 · 소리 내용은 미검사" else "없음")
            append("\n\n시간정보만 확인합니다. 화면이 같은 장면인지, 영상 디코딩이나 소리가 정상인지는 판정하지 않습니다. 긴 간격이 있으면 해당 위치를 직접 재생해 확인하세요.")
        }
    }

    fun inspect(file: File, canceled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Report {
        check(file.isFile && file.length() > 0) { "파일이 없거나 비어 있습니다" }
        check(file.absolutePath !in RecordingJournal.activePaths) { "녹화 중인 영상은 검사할 수 없습니다" }
        val originalSize = file.length()
        val originalModified = file.lastModified()
        val durationMs = VideoFiles.inspect(file).durationMs
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoIndex = -1
            var audio = false
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (videoIndex < 0 && mime.startsWith("video/")) videoIndex = index
                if (mime.startsWith("audio/")) audio = true
            }
            check(videoIndex >= 0) { "영상 트랙이 없습니다" }
            extractor.selectTrack(videoIndex)
            var samples = LongArray(4096)
            var count = 0
            val started = SystemClock.elapsedRealtime()
            var lastProgress = started
            var stopReason = ""
            while (extractor.sampleTime >= 0) {
                if (canceled() || Thread.currentThread().isInterrupted) throw CancellationException("검사를 취소했습니다")
                if (count >= 1_000_000 || SystemClock.elapsedRealtime() - started >= 30_000) {
                    stopReason = "검사 시간·샘플 한도 도달"
                    break
                }
                if (count == samples.size) samples = samples.copyOf(minOf(samples.size * 2, 1_000_000))
                samples[count++] = extractor.sampleTime
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgress >= 300) {
                    lastProgress = now
                    progress(if (durationMs > 0) (extractor.sampleTime / (durationMs * 10)).toInt().coerceIn(0, 99) else 0)
                }
                if (!extractor.advance()) break
            }
            check(file.length() == originalSize && file.lastModified() == originalModified) { "검사 중 파일이 변경됐습니다" }
            val full = stopReason.isEmpty()
            val timeline = VideoTimeline.analyze(samples.copyOf(count), durationMs * 1000, full)
            progress(if (full) 100 else 99)
            return Report(System.currentTimeMillis(), originalSize, originalModified, durationMs, timeline, audio, stopReason)
        } finally { extractor.release() }
    }

    fun save(context: Context, file: File, report: Report) {
        val data = JSONObject().put("checkedAt", report.checkedAt).put("size", report.size).put("modified", report.modified)
            .put("durationMs", report.durationMs).put("audioTrack", report.audioTrack).put("stopReason", report.stopReason)
            .put("samples", report.timeline.samples).put("firstUs", report.timeline.firstUs).put("lastUs", report.timeline.lastUs)
            .put("maxGapUs", report.timeline.maxGapUs).put("missingTailUs", report.timeline.missingTailUs).put("complete", report.timeline.complete)
        val atomic = AtomicFile(resultFile(context, file))
        val stream = atomic.startWrite()
        try { stream.write(data.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    fun load(context: Context, file: File): Report? = runCatching {
        val j = JSONObject(AtomicFile(resultFile(context, file)).openRead().bufferedReader().use { it.readText() })
        if (j.getLong("size") != file.length() || j.getLong("modified") != file.lastModified()) return null
        Report(j.getLong("checkedAt"), j.getLong("size"), j.getLong("modified"), j.getLong("durationMs"),
            VideoTimeline(j.getInt("samples"), j.getLong("firstUs"), j.getLong("lastUs"), j.getLong("maxGapUs"),
                j.getLong("missingTailUs"), j.getBoolean("complete")), j.getBoolean("audioTrack"), j.optString("stopReason"))
    }.getOrNull()

    fun remove(context: Context, file: File) { AtomicFile(resultFile(context, file)).delete() }

    private fun resultFile(context: Context, file: File): File {
        val key = MessageDigest.getInstance("SHA-256").digest(file.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "video_checks").apply { mkdirs() }, "$key.json")
    }
}
