package com.example.camera2study.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.camera2study.R
import com.example.camera2study.RecordingRuntime
import com.example.camera2study.util.RecordingJournal
import com.example.camera2study.util.VideoTrackInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

class RecordingHistoryFragment : Fragment() {
    private var list: LinearLayout? = null
    private var refreshKey = ""
    private var loadVersion = 0
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    checkNotNull(ctx.contentResolver.openOutputStream(uri)).use { output ->
                        val sessions = RecordingJournal.sessions(ctx)
                        sessions.forEach { session ->
                            val segments = session.getJSONArray("segments")
                            for (index in 0 until segments.length()) {
                                val item = segments.getJSONObject(index)
                                item.optString("path").takeIf { it.isNotBlank() }?.let { path ->
                                    VideoTrackInspector.load(ctx, File(path))?.let { item.put("manualVideoCheck", it.details()) }
                                }
                            }
                        }
                        output.write(JSONArray(sessions).toString(2).toByteArray(Charsets.UTF_8))
                    }
                } }
                Toast.makeText(ctx, if (result.isSuccess) "진단 기록을 저장했습니다" else "진단 저장 실패", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 32); setBackgroundColor(android.graphics.Color.rgb(18, 20, 25)) }
        list = content
        return ScrollView(requireContext()).apply { fitsSystemWindows = true; addView(content) }
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        RecordingRuntime.state.observe(viewLifecycleOwner) { state ->
            val key = state.sessionId + ":" + state.segment + ":" + state.phase
            if (key != refreshKey) { refreshKey = key; reload() }
        }
        reload()
    }
    private fun reload() {
        val ctx = context ?: return
        val version = ++loadVersion
        viewLifecycleOwner.lifecycleScope.launch {
            val selectedSession = arguments?.getString("sessionId")
            val sessions = withContext(Dispatchers.IO) {
                RecordingJournal.recover(ctx)
                RecordingJournal.sessions(ctx, limit = 100, sessionId = selectedSession).onEach { session ->
                    val files = session.getJSONArray("segments")
                    for (index in 0 until files.length()) {
                        val item = files.getJSONObject(index)
                        val path = item.optString("path")
                        val uri = item.optString("uri")
                        val currentState = when {
                            item.optString("status") == "RECORDING" -> "RECORDING"
                            path.isNotBlank() -> if (File(path).isFile) "AVAILABLE" else "MISSING"
                            uri.startsWith("content://") -> if (runCatching {
                                ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "r")?.use { true } == true
                            }.getOrDefault(false)) "AVAILABLE" else "UNAVAILABLE"
                            else -> "UNKNOWN"
                        }
                        item.put("currentFileState", currentState)
                        if (path.isNotBlank() && currentState == "AVAILABLE") {
                            VideoTrackInspector.load(ctx, File(path))?.let { item.put("manualCheckLabel", it.label) }
                        }
                    }
                }
            }
            if (version != loadVersion) return@launch
            val container = list ?: return@launch
            container.removeAllViews()
            fun button(text: String, action: () -> Unit) {
                container.addView(Button(ctx).apply { this.text = text; setOnClickListener { action() } })
            }
            fun text(value: String) {
                container.addView(TextView(ctx).apply { text = value; setTextColor(android.graphics.Color.WHITE); textSize = 15f; setPadding(0, 20, 0, 12) })
            }
            button("뒤로") { parentFragmentManager.popBackStack() }
            text(if (selectedSession == null) "촬영 결과 · 최근 100회" else "선택한 촬영 결과")
            text("저장 완료는 녹화 당시 저장 결과입니다. 영상·소리 내용까지 정상이라는 뜻은 아닙니다. 보관함에서 재생하거나 시간정보 검사를 실행하세요.")
            button("새로고침") { reload() }
            button("진단 기록 내보내기") { export.launch("recording-diagnostics.json") }
            if (sessions.isEmpty()) text("아직 촬영 기록이 없습니다")
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            for (session in sessions) {
                val files = session.getJSONArray("segments")
                var duration = 0L
                var bytes = 0L
                var complete = 0
                var sizeKnown = true
                val details = mutableListOf<String>()
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    duration += file.optLong("durationMs")
                    bytes += file.optLong("bytes")
                    sizeKnown = sizeKnown && file.optBoolean("sizeKnown", false)
                    if (file.optString("status") == "COMPLETE") complete++
                    val current = when (file.optString("currentFileState")) {
                        "AVAILABLE" -> if (file.optBoolean("fileUnavailable")) "원본 있음 · 영상 정보 확인 필요" else "원본 있음"
                        "MISSING" -> "현재 원본 파일 없음"
                        "UNAVAILABLE" -> "현재 원본 접근 불가 · 삭제 또는 권한 확인"
                        "RECORDING" -> "기록 중"
                        else -> "현재 파일 확인 필요"
                    }
                    val check = file.optString("manualCheckLabel").takeIf { it.isNotBlank() } ?: "시간정보 검사 전"
                    details += "${i + 1}. 녹화 당시 ${status(file.optString("status"))} · ${file.optLong("durationMs") / 1000}초\n$current · $check\n${file.optString("name")}"
                }
                text("${formatter.format(Date(session.optLong("startedAt")))} · ${status(session.optString("status"))}\n" +
                    "총 %02d:%02d · 저장 완료 %d/%d개 · ".format(duration / 60_000, duration / 1000 % 60, complete, files.length()) +
                    (if (sizeKnown) "%.1f MiB".format(bytes / 1_048_576.0) else "용량 확인 필요") + "\n" +
                    "시작: ${sourceLabel(session.optString("source"))} · 종료: ${reasonLabel(session.optString("reason"))}\n" +
                    audioSummary(session.optJSONObject("audioSummary")) + "\n" + session.optString("error") + "\n" + details.joinToString("\n\n"))
                val privateFiles = (0 until files.length()).map { files.getJSONObject(it) }.filter { it.optString("path").isNotBlank() && it.optString("currentFileState") == "AVAILABLE" }
                if (privateFiles.isNotEmpty()) button("이 촬영의 보관함 파일") {
                    val vault = PrivateVaultFragment().apply { arguments = Bundle().apply { putString("sessionId", session.getString("id")) } }
                    parentFragmentManager.beginTransaction().replace(R.id.container, vault).addToBackStack(null).commit()
                }
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    if (file.optString("path").isBlank() && file.optString("uri").startsWith("content://") && file.optString("currentFileState") == "AVAILABLE") {
                        button((i + 1).toString() + ". 공개 저장 영상 열기") { openPublicVideo(file.optString("uri")) }
                    }
                }
            }
        }
    }
    private fun audioSummary(audio: org.json.JSONObject?): String {
        if (audio == null) return "소리 진단: 이전 버전 기록 없음"
        if (!audio.optBoolean("enabled")) return "소리: 마이크 끔"
        if (!audio.optBoolean("hadIssues")) return "소리: 녹음 오류 신호 없음 · 실제 소리 내용은 미검사"
        val intervals = audio.optJSONArray("intervals") ?: JSONArray()
        val details = (0 until minOf(intervals.length(), 4)).map { index ->
            val item = intervals.getJSONObject(index)
            val label = when (item.optString("state")) {
                "SOURCE_SILENCED" -> "마이크 입력 무음 신호"
                "SOURCE_ERROR" -> "마이크 입력 오류"
                "ENCODER_ERROR" -> "음성 저장 오류"
                "MUTED" -> "녹음 음소거"
                else -> item.optString("state")
            }
            "%.1f–%.1f초 · %s".format(item.optLong("startMs") / 1000.0, item.optLong("endMs") / 1000.0, label)
        }
        return "소리 확인 필요 · ${audio.optInt("issueCount")}회 · 합계 %.1f초".format(audio.optLong("issueDurationMs") / 1000.0) +
            (if (details.isEmpty()) "" else "\n" + details.joinToString("\n")) +
            (if (intervals.length() > 4) "\n나머지 구간은 진단 기록 내보내기에서 확인하세요" else "")
    }
    private fun openPublicVideo(value: String) {
        val uri = android.net.Uri.parse(value)
        try {
            requireContext().contentResolver.openFileDescriptor(uri, "r")?.use { } ?: error("파일이 없습니다")
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(requireContext(), "영상을 열 수 있는 갤러리 또는 플레이어가 필요합니다", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "영상이 삭제되었거나 이동되어 열 수 없습니다", Toast.LENGTH_LONG).show()
        }
    }
    private fun sourceLabel(value: String) = when(value) {
        "app" -> "일반 촬영"; "app_quick" -> "앱 빠른 녹화"; "widget" -> "홈 위젯"; "test" -> "10초 시험"; else -> value
    }
    private fun reasonLabel(value: String) = when(value) {
        "user", "app" -> "촬영 화면에서 중지"; "widget" -> "홈 위젯에서 중지"; "notification" -> "알림에서 중지"
        "file_limit" -> "설정한 파일 수 도달"; "time_limit" -> "설정한 총 시간 도달"; "resource_error" -> "기기 자원 부족"
        "progress_timeout" -> "영상 기록 진행 없음"; "finalize_timeout" -> "저장 완료 응답 없음"; "start_rejected" -> "시작 조건 불충족"
        "configuration_error", "preflight_error", "start_error" -> "촬영 준비 실패"; "recording_error" -> "촬영 오류"; else -> value
    }
    private fun status(value: String) = when (value) {
        "ACTIVE", "RECORDING" -> "기록 중"
        "COMPLETE" -> "완료"
        "INTERRUPTED" -> "예상치 못한 중단"
        "ERROR" -> "실패"
        else -> "확인 필요"
    }
    override fun onDestroyView() { list = null; super.onDestroyView() }
}
