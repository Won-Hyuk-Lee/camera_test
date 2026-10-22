package com.example.camera2study.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import java.io.File

object VideoFiles {
    data class Info(val label: String, val playable: Boolean, val durationMs: Long = 0)

    fun inspect(file: File): Info {
        if (file.absolutePath in RecordingJournal.activePaths) return Info("기록 중", false)
        if (file.length() == 0L) return Info("확인 필요 · 0바이트", false)
        if (file.extension == "jpg") return Info("기존 사진", true)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val video = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (video && duration > 0) Info("완료 · %02d:%02d".format(duration / 60_000, duration / 1_000 % 60), true, duration)
            else Info("확인 필요 · 영상 정보 없음", false)
        } catch (_: Exception) { Info("확인 필요 · 재생 정보 손상", false) }
        finally { retriever.release() }
    }

    fun exportCopy(context: Context, file: File) {
        check(file.absolutePath !in RecordingJournal.activePaths) { "기록 중인 파일입니다" }
        check(inspect(file).playable) { "재생 가능한 완료 파일만 내보낼 수 있습니다" }
        check(Build.VERSION.SDK_INT >= 29) { "Android 10 이상에서 갤러리 내보내기를 지원합니다" }
        val resolver = context.contentResolver
        val image = file.extension == "jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (image) "image/jpeg" else "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (image) "Pictures/StudyVault" else "Movies/StudyVault")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = checkNotNull(resolver.insert(collection, values)) { "갤러리 항목 생성 실패" }
        try {
            val expected = file.length()
            val copied = file.inputStream().use { input ->
                checkNotNull(resolver.openOutputStream(uri, "w")) { "갤러리 쓰기 실패" }.use { output -> input.copyTo(output) }
            }
            check(copied == expected && expected > 0L) { "복사 크기 불일치" }
            val actual = checkNotNull(resolver.openInputStream(uri)).use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n }
                total
            }
            check(actual == expected) { "갤러리 복사 검증 실패" }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // Export is a copy. The source always remains in the vault.
    }
}
