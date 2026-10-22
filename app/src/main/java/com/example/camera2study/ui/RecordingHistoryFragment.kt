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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingHistoryFragment : Fragment() {
    private var list: LinearLayout? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    checkNotNull(ctx.contentResolver.openOutputStream(uri)).use { output ->
                        output.write(JSONArray(RecordingJournal.sessions(ctx)).toString(2).toByteArray(Charsets.UTF_8))
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
        RecordingRuntime.state.observe(viewLifecycleOwner) { if (!it.active) reload() }
        reload()
    }
    private fun reload() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { RecordingJournal.recover(ctx); RecordingJournal.sessions(ctx).take(100) }
            val container = list ?: return@launch
            container.removeAllViews()
            fun button(text: String, action: () -> Unit) {
                container.addView(Button(ctx).apply { this.text = text; setOnClickListener { action() } })
            }
            fun text(value: String) {
                container.addView(TextView(ctx).apply { text = value; setTextColor(android.graphics.Color.WHITE); textSize = 15f; setPadding(0, 20, 0, 12) })
            }
            button("뒤로") { parentFragmentManager.popBackStack() }
            text("촬영 결과 · 최근 100회")
            button("새로고침") { reload() }
            button("진단 기록 내보내기") { export.launch("recording-diagnostics.json") }
            if (sessions.isEmpty()) text("아직 촬영 기록이 없습니다")
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            for (session in sessions) {
                val files = session.getJSONArray("segments")
                var duration = 0L
                var bytes = 0L
                var complete = 0
                val details = mutableListOf<String>()
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    duration += file.optLong("durationMs")
                    bytes += file.optLong("bytes")
                    if (file.optString("status") == "COMPLETE") complete++
                    details += "${i + 1}. ${status(file.optString("status"))} · ${file.optLong("durationMs") / 1000}초\n${file.optString("name")}"
                }
                text("${formatter.format(Date(session.optLong("startedAt")))} · ${status(session.optString("status"))}\n" +
                    "총 %02d:%02d · 완료 %d/%d개 · %.1f MB\n".format(duration / 60_000, duration / 1000 % 60, complete, files.length(), bytes / 1_048_576.0) +
                    "시작: ${session.optString("source")} · 종료: ${session.optString("reason")}\n" +
                    session.optString("error") + "\n" + details.joinToString("\n"))
                button("이 촬영의 보관함 파일") {
                    val vault = PrivateVaultFragment().apply { arguments = Bundle().apply { putString("sessionId", session.getString("id")) } }
                    parentFragmentManager.beginTransaction().replace(R.id.container, vault).addToBackStack(null).commit()
                }
            }
        }
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
