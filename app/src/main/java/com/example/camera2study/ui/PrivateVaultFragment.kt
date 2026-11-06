package com.example.camera2study.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.camera2study.util.RecordingJournal
import com.example.camera2study.util.VideoFiles
import com.example.camera2study.util.VaultExports
import com.example.camera2study.util.VideoTrackInspector
import com.example.camera2study.R
import com.example.camera2study.databinding.FragmentPrivateVaultBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class PrivateVaultFragment : Fragment(), com.example.camera2study.EntryLockListener {
    private val dialogs = mutableSetOf<android.app.Dialog>()
    private var inspectionJob: Job? = null
    override fun onEntryLocked() { inspectionJob?.cancel(); dialogs.toList().forEach { it.dismiss() }; dialogs.clear() }
    override fun onStop() { onEntryLocked(); super.onStop() }
    private var _binding: FragmentPrivateVaultBinding? = null
    private val binding get() = _binding!!
    private var loadVersion = 0
    private var refreshKey = ""
    private val mediaFiles = arrayListOf<File>()
    private val mediaInfo = mutableMapOf<String, VideoFiles.Info>()
    private val inspectionResults = mutableMapOf<String, VideoTrackInspector.Report>()
    private val selected = mutableSetOf<String>()
    private val collapsed = mutableSetOf<String>()
    private val rows = arrayListOf<Row>()
    private var query = ""
    private var sort = 0
    private var filter = 0
    private val exportButtons = linkedMapOf<String, Button>()
    private lateinit var adapter: VaultAdapter
    private data class Row(val title: String, val files: List<File>, val file: File? = null, val key: String = "")
    private val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        query = state?.getString("query").orEmpty(); sort = state?.getInt("sort") ?: 0; filter = state?.getInt("filter") ?: 0
        selected.addAll(state?.getStringArrayList("selected").orEmpty())
        collapsed.addAll(state?.getStringArrayList("collapsed").orEmpty())
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("query",query); out.putInt("sort",sort); out.putInt("filter",filter)
        out.putStringArrayList("selected",ArrayList(selected)); out.putStringArrayList("collapsed",ArrayList(collapsed))
        super.onSaveInstanceState(out)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentPrivateVaultBinding.inflate(inflater,container,false); return binding.root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.searchQuery.setText(query)
        binding.searchQuery.doAfterTextChanged { query = it.toString(); renderRows() }
        setupChoice(binding.sortOrder,listOf("최신 촬영순","오래된 촬영순","용량 큰 순","길이 긴 순"),sort) { sort = it; renderRows() }
        setupChoice(binding.statusFilter,listOf("모든 상태","저장 완료","기록 중","확인 필요"),filter) { filter = it; renderRows() }
        binding.selectVisible.setOnClickListener {
            val visible = rows.filter { it.file == null }.flatMap { it.files }.filter { it.absolutePath !in RecordingJournal.activePaths }
            if (visible.all { it.absolutePath in selected }) visible.forEach { selected.remove(it.absolutePath) }
            else visible.forEach { selected.add(it.absolutePath) }
            renderRows()
        }
        binding.exportSelected.setOnClickListener { export(mediaFiles.filter { it.absolutePath in selected }) }
        binding.deleteSelected.setOnClickListener { delete(mediaFiles.filter { it.absolutePath in selected }) }
        VaultExports.initialize(requireContext())
        VaultExports.state.observe(viewLifecycleOwner, ::renderExports)
        adapter = VaultAdapter()
        binding.rvVaultMedia.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVaultMedia.adapter = adapter
        loadMediaFiles()
        com.example.camera2study.RecordingRuntime.state.observe(viewLifecycleOwner) { state ->
            if (state.active) inspectionJob?.cancel()
            val key = state.sessionId + ":" + state.segment + ":" + state.phase
            if (key != refreshKey) { refreshKey = key; loadMediaFiles() }
        }
    }
    private fun renderExports(jobs: List<VaultExports.Job>) {
        if (_binding == null) return
        (binding.exportStatus.parent as View).visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        val visible = jobs.filter { it.busy || it.state != "COMPLETE" }.take(3) + jobs.filter { it.state == "COMPLETE" }.take(1)
        if (exportButtons.keys.toList() != visible.map { it.id }) {
            binding.exportStatus.removeAllViews(); exportButtons.clear()
            binding.exportStatus.addView(TextView(requireContext()).apply {
                text = "내보내기 · 원본 유지 / 앱 종료 시 다음 실행에서 재시도"
                setTextColor(Color.WHITE); textSize = 14f
            })
            binding.exportStatus.addView(Button(requireContext()).apply {
                text = "내보내기 전체 작업 보기"
                setOnClickListener { showExportQueue() }
            })
            visible.forEach { job ->
                val button = Button(requireContext())
                exportButtons[job.id] = button
                binding.exportStatus.addView(button)
            }
        }
        visible.forEach { job ->
            exportButtons[job.id]?.let { bindExportButton(it, job) }
        }
    }

    private fun bindExportButton(button: Button, job: VaultExports.Job) {
        val status = when (job.state) { "QUEUED" -> "대기"; "COPYING" -> "복사"; "VERIFYING" -> "복사 검증";
            "COMPLETE" -> "갤러리 복사 완료"; "CANCELED" -> "취소 완료"; "CANCEL_REQUESTED" -> "취소 처리 중"; else -> "실패" }
        val action = when {
            job.state == "COMPLETE" -> ""
            job.state == "CANCEL_REQUESTED" -> if (job.error.isNotBlank()) " · 정리 재시도" else ""
            job.busy -> " · 취소"
            else -> " · 재시도"
        }
        button.text = File(job.path).name + "\n" + status + " " + (job.done * 100 / job.size.coerceAtLeast(1)) + "%" +
            action + if (job.error.isBlank()) "" else "\n" + job.error
        button.contentDescription = button.text
        button.isEnabled = job.state != "COMPLETE" && (job.state != "CANCEL_REQUESTED" || job.error.isNotBlank())
        button.setOnClickListener {
            if (!authenticated()) return@setOnClickListener
            runCatching {
                if (job.busy && !job.cancelRequested) {
                    if (!VaultExports.cancel(job.id)) toast("이미 완료되었거나 취소 요청한 작업입니다")
                } else VaultExports.retry(job.id)
            }.onFailure { toast("작업 상태를 저장하지 못했습니다: ${it.message}") }
        }
    }

    private fun showExportQueue() {
        if (!authenticated()) return
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle("내보내기 전체 작업")
        val dialogContext = builder.context
        val content = LinearLayout(dialogContext).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val scroll = dialogScroll(dialogContext, content)
        val buttons = linkedMapOf<String, Button>()
        val observer = androidx.lifecycle.Observer<List<VaultExports.Job>> { jobs ->
            if (buttons.keys.toList() != jobs.map { it.id }) {
                content.removeAllViews(); buttons.clear()
                if (jobs.isEmpty()) content.addView(TextView(dialogContext).apply { text = "내보내기 작업이 없습니다" })
                jobs.forEach { job -> Button(dialogContext).also { buttons[job.id] = it; content.addView(it) } }
            }
            jobs.forEach { job -> buttons[job.id]?.let { bindExportButton(it, job) } }
        }
        val dialog = builder.setView(scroll).setPositiveButton("닫기", null).create()
        trackDialog(dialog) { VaultExports.state.removeObserver(observer); buttons.clear() }
        VaultExports.state.observe(viewLifecycleOwner, observer)
    }

    /** Leave room for the title and dialog actions. The body scrolls even in landscape or with large text. */
    private fun dialogScroll(context: Context, content: View) = object : ScrollView(context) {
        init { isScrollbarFadingEnabled = false; addView(content) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (resources.displayMetrics.heightPixels * .55f).toInt().coerceAtLeast(1)
            val allowed = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) cap
                else minOf(cap, View.MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(allowed, View.MeasureSpec.AT_MOST))
        }
    }

    private fun authenticated() = (activity as? com.example.camera2study.MainActivity)?.isEntryAuthenticated() == true
    private fun trackDialog(dialog: android.app.Dialog, cleanup: () -> Unit = {}) {
        if (!authenticated()) return
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog); cleanup() }
        if (com.example.camera2study.util.SecurityPrefs.blockScreenCapture(requireContext())) {
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.show()
    }
    private fun setupChoice(spinner: Spinner, labels: List<String>, index: Int, action: (Int) -> Unit) {
        spinner.adapter = object : ArrayAdapter<String>(requireContext(),android.R.layout.simple_spinner_dropdown_item,labels) {
            override fun getView(p: Int,v: View?,parent: ViewGroup): View = super.getView(p,v,parent).also { (it as TextView).setTextColor(Color.WHITE) }
            override fun getDropDownView(p: Int,v: View?,parent: ViewGroup): View = super.getDropDownView(p,v,parent).also { (it as TextView).setTextColor(Color.WHITE); it.setBackgroundColor(Color.DKGRAY); it.minimumHeight = dp(48) }
        }
        spinner.setSelection(index)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?,v: View?,position: Int,id: Long) { action(position) }
        }
    }
    private fun loadMediaFiles() {
        val ctx = context ?: return; val version = ++loadVersion
        val privateDir = File(ctx.getExternalFilesDir(null),"private_vault")
        val sessionId = arguments?.getString("sessionId")
        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                privateDir.listFiles().orEmpty().filter { it.isFile && it.extension in listOf("jpg","mp4") &&
                    (sessionId == null || it.name.contains(sessionId)) }.map {
                    Triple(it, VideoFiles.inspect(it), if (it.extension == "mp4") VideoTrackInspector.load(ctx, it) else null)
                }
            }
            if (_binding == null || version != loadVersion) return@launch
            mediaFiles.clear(); mediaFiles.addAll(files.map { it.first })
            mediaInfo.clear(); files.forEach { mediaInfo[it.first.absolutePath] = it.second }
            inspectionResults.clear(); files.forEach { item -> item.third?.let { inspectionResults[item.first.absolutePath] = it } }
            selected.retainAll(mediaFiles.map { it.absolutePath }.toSet())
            renderRows()
        }
    }
    private fun groupKey(file: File): String = if (file.name.startsWith("StudyVault_") && file.nameWithoutExtension.substringAfterLast('_').toIntOrNull() != null)
        file.nameWithoutExtension.substringBeforeLast('_') else file.name
    private fun renderRows() {
        if (_binding == null || !::adapter.isInitialized) return
        val groups = mediaFiles.filter { file ->
            val info = mediaInfo[file.absolutePath]
            (query.isBlank() || (file.name + " " + date.format(java.util.Date(file.lastModified()))).contains(query,true)) &&
                 when (filter) {
                     1 -> info?.playable == true
                     2 -> file.absolutePath in RecordingJournal.activePaths
                     3 -> (info?.playable != true || inspectionResults[file.absolutePath]?.let {
                         !it.timeline.complete || it.timeline.needsReview || it.durationMs <= 0
                     } == true) && file.absolutePath !in RecordingJournal.activePaths
                     else -> true
                 }
        }.groupBy(::groupKey).entries.sortedWith(Comparator { a,b ->
            when (sort) {
                1 -> a.value.minOf { it.lastModified() }.compareTo(b.value.minOf { it.lastModified() })
                2 -> b.value.sumOf { it.length() }.compareTo(a.value.sumOf { it.length() })
                3 -> b.value.sumOf { mediaInfo[it.absolutePath]?.durationMs ?: 0 }.compareTo(a.value.sumOf { mediaInfo[it.absolutePath]?.durationMs ?: 0 })
                else -> b.value.maxOf { it.lastModified() }.compareTo(a.value.maxOf { it.lastModified() })
            }
        })
        rows.clear()
        groups.forEach { group ->
            val files = group.value.sortedBy { it.nameWithoutExtension.substringAfterLast('_').toIntOrNull() ?: 0 }
            val ms = files.sumOf { mediaInfo[it.absolutePath]?.durationMs ?: 0 }
            val title = date.format(java.util.Date(files.minOf { it.lastModified() })) + " · " + files.size + "개 · " +
                "%02d:%02d".format(ms / 60000,ms / 1000 % 60) + " · %.1f MiB".format(files.sumOf { it.length() } / 1048576.0)
            rows.add(Row((if (group.key in collapsed) "▶ " else "▼ ") + title,files,key = group.key))
            if (group.key !in collapsed) files.forEachIndexed { i,file -> rows.add(Row((i+1).toString() + "/" + files.size,files,file,group.key)) }
        }
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val visiblePaths = groups.flatMap { it.value }.map { it.absolutePath }.toSet()
        val hiddenSelected = selected.count { it !in visiblePaths }
        binding.selectionSummary.text = groups.size.toString() + "개 촬영 · " + selected.size + "개 선택" +
            if (hiddenSelected > 0) " · 필터 밖 선택 ${hiddenSelected}개 포함" else ""
        binding.exportSelected.isEnabled = selected.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 29
        binding.exportSelected.contentDescription = if (android.os.Build.VERSION.SDK_INT >= 29) "선택 영상 갤러리로 복사" else "갤러리 복사는 Android 10 이상 필요"
        binding.deleteSelected.isEnabled = selected.isNotEmpty()
        adapter.notifyDataSetChanged()
    }
    private fun export(files: List<File>) {
        if (!authenticated()) return
        if (android.os.Build.VERSION.SDK_INT < 29) { toast("갤러리 복사는 Android 10 이상에서 지원합니다"); return }
        val eligible = files.filter { mediaInfo[it.absolutePath]?.playable == true && it.absolutePath !in RecordingJournal.activePaths }
        eligible.forEach { runCatching { VaultExports.enqueue(requireContext(),it) }.onFailure { toast(it.message.orEmpty()) } }
        toast(eligible.size.toString() + "개 내보내기 요청 · 원본 유지" + if (eligible.size < files.size) " · 기록 중/손상 파일 제외" else "")
    }
    private fun delete(files: List<File>) {
        if (!authenticated()) return
        val ctx = requireContext().applicationContext
        MaterialAlertDialogBuilder(requireContext()).setTitle("선택 파일 영구 삭제")
            .setMessage(files.size.toString() + "개 파일을 삭제합니다. 복구할 수 없으며 기록·내보내기 중인 파일은 제외합니다.")
            .setPositiveButton("삭제") { _,_ ->
                if (!authenticated()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    val count = withContext(Dispatchers.IO) { files.count { file ->
                        VaultExports.deleteSource(file).also { deleted -> if (deleted) VideoTrackInspector.remove(ctx, file) }
                    } }
                    toast(count.toString() + "개 삭제"); loadMediaFiles()
                }
            }.setNegativeButton("취소",null).create().also { trackDialog(it) }
    }
    private fun manage(file: File) {
        if (!authenticated()) return
        val exportLabel = if (android.os.Build.VERSION.SDK_INT >= 29) "공개 갤러리로 내보내기" else "갤러리 내보내기 · Android 10 이상 필요"
        MaterialAlertDialogBuilder(requireContext()).setTitle(file.name).setItems(arrayOf(exportLabel,"영상 시간정보 검사","영구 삭제")) { _,which ->
            if (!authenticated()) return@setItems
            when (which) { 0 -> export(listOf(file)); 1 -> inspectVideo(file); else -> delete(listOf(file)) }
        }.create().also { trackDialog(it) }
    }
    private fun inspectVideo(file: File) {
        if (!authenticated()) return
        if (file.extension != "mp4") { toast("동영상 파일만 검사할 수 있습니다"); return }
        if (com.example.camera2study.RecordingRuntime.state.value?.active == true) {
            toast("녹화가 끝난 뒤 검사해 주세요. 촬영 중 저장장치 사용을 줄입니다"); return
        }
        if (inspectionJob?.isActive == true) { toast("진행 중인 검사를 먼저 닫아 주세요"); return }
        val ctx = requireContext().applicationContext
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle("영상 시간정보 검사")
        val dialogContext = builder.context
        val content = LinearLayout(dialogContext).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val message = TextView(dialogContext).apply {
            text = "영상 시간정보 검사 준비 중\n최대 30초 동안 확인하며, 한도에 도달하면 일부 검사로 표시합니다."
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            textSize = 15f
        }
        val progress = ProgressBar(dialogContext, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addView(message); content.addView(progress)
        val scroll = dialogScroll(dialogContext, content)
        val dialog = builder.setView(scroll).setNegativeButton("검사 취소", null).create()
        var work: Job? = null
        var running = true
        trackDialog(dialog) { running = false; work?.cancel() }
        fun finishInspection(text: String) {
            running = false
            if (!dialog.isShowing) return
            message.text = text
            progress.visibility = View.GONE
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.text = "닫기"
            scroll.scrollTo(0, 0)
        }
        work = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val operation = currentCoroutineContext()
                val report = withContext(Dispatchers.IO) {
                    val result = VideoTrackInspector.inspect(file, canceled = { !operation.isActive }, progress = { percent ->
                        progress.post { if (running && dialog.isShowing) { progress.progress = percent; message.text = "영상 시간정보 검사 중 · $percent%\n닫으면 검사를 취소합니다." } }
                    })
                    operation.ensureActive()
                    VideoTrackInspector.save(ctx, file, result)
                    result
                }
                inspectionResults[file.absolutePath] = report
                finishInspection(report.details())
                renderRows()
            } catch (_: CancellationException) {
                finishInspection("검사를 중지했습니다. 녹화가 끝난 뒤 다시 검사할 수 있습니다.")
            } catch (error: Exception) {
                finishInspection("검사하지 못했습니다: ${error.message}\n원본 영상은 변경하지 않았습니다.")
            } finally { if (inspectionJob === work) inspectionJob = null }
        }
        inspectionJob = work
    }

    private fun mediaLabel(file: File): String {
        val info = mediaInfo[file.absolutePath] ?: return "파일 확인 필요"
        val report = inspectionResults[file.absolutePath] ?: return info.label
        return info.label.replace("시간정보 검사 전", report.label)
    }
    private fun toast(text: String) { context?.let { Toast.makeText(it,text,Toast.LENGTH_LONG).show() } }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun showMediaDetail(file: File) {
        if (!authenticated()) return
        if (!file.isFile) { toast("원본 파일이 삭제되었거나 이동됐습니다"); loadMediaFiles(); return }
        if (file.absolutePath in RecordingJournal.activePaths || mediaInfo[file.absolutePath]?.playable != true) {
            Toast.makeText(requireContext(), mediaInfo[file.absolutePath]?.label ?: "파일 확인 필요", Toast.LENGTH_LONG).show()
            return
        }
        val ctx = requireContext()
        val customView = LayoutInflater.from(ctx).inflate(R.layout.dialog_media_viewer, null)
        val container = customView.findViewById<FrameLayout>(R.id.mediaContainer)

        val dialog = MaterialAlertDialogBuilder(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(customView)
            .create()

        customView.findViewById<View>(R.id.btnViewerClose).setOnClickListener {
            dialog.dismiss()
        }

        val txtTitle = customView.findViewById<TextView>(R.id.txtViewerTitle)
        txtTitle.text = file.name
        val playbackStatus = customView.findViewById<TextView>(R.id.txtPlaybackStatus)
        val inspectionButton = customView.findViewById<Button>(R.id.btnInspectVideo)
        inspectionButton.visibility = if (file.extension == "mp4") View.VISIBLE else View.GONE
        inspectionButton.setOnClickListener { dialog.dismiss(); inspectVideo(file) }
        var cleanup: () -> Unit = {}

        if (file.name.endsWith(".jpg")) {
            val img = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            img.setImageBitmap(bitmap)
            container.addView(img)
            playbackStatus.text = "기존 사진"
            cleanup = { img.setImageDrawable(null) }
        } else if (file.name.endsWith(".mp4")) {
            val videoView = VideoView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            container.addView(videoView)

            val controller = MediaController(ctx)
            controller.setAnchorView(videoView)
            videoView.setMediaController(controller)
            videoView.setVideoPath(file.absolutePath)
            var errorText: String? = null
            val ticker = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing) return
                    playbackStatus.text = errorText ?: "${playbackTime(videoView.currentPosition)} / ${playbackTime(videoView.duration.coerceAtLeast(0))}\n${mediaLabel(file)}"
                    playbackStatus.postDelayed(this, 500)
                }
            }
            playbackStatus.text = "영상을 여는 중 · ${mediaLabel(file)}"
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                videoView.start()
                playbackStatus.removeCallbacks(ticker)
                playbackStatus.post(ticker)
            }
            videoView.setOnErrorListener { _, what, extra ->
                errorText = "이 플레이어에서 재생하지 못했습니다 ($what/$extra).\n시간정보 검사 또는 갤러리 복사 후 다른 플레이어로 확인해 주세요. 원본 손상 여부는 아직 확인하지 않았습니다."
                playbackStatus.text = errorText
                true
            }
            videoView.setOnCompletionListener {
                playbackStatus.text = "재생 끝 · ${playbackTime(videoView.duration.coerceAtLeast(0))}\n${mediaLabel(file)}"
                playbackStatus.removeCallbacks(ticker)
            }
            cleanup = { playbackStatus.removeCallbacks(ticker); videoView.setOnPreparedListener(null); videoView.setOnErrorListener(null); videoView.setOnCompletionListener(null); runCatching { videoView.stopPlayback() }; container.removeAllViews() }
        }
        trackDialog(dialog, cleanup)
    }

    private fun playbackTime(ms: Int): String = "%02d:%02d".format(ms / 60_000, ms / 1000 % 60)


    private inner class VaultAdapter : RecyclerView.Adapter<VaultAdapter.Holder>() {
        private val io = java.util.concurrent.Executors.newFixedThreadPool(2)
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private val pending = java.util.concurrent.ConcurrentHashMap<String, CancellationSignal>()
        @Volatile private var closed = false
        private val cache = object : androidx.collection.LruCache<String,android.graphics.Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String,value: android.graphics.Bitmap) = value.byteCount
        }
        inner class Holder(val row: LinearLayout,val select: CheckBox,val thumbnail: ImageView,val text: TextView,val more: Button) : RecyclerView.ViewHolder(row) { var path = "" }
        override fun onCreateViewHolder(parent: ViewGroup,type: Int): Holder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0,dp(6),0,dp(6)); layoutParams = RecyclerView.LayoutParams(-1,-2) }
            val select = CheckBox(ctx); row.addView(select,LinearLayout.LayoutParams(dp(48),dp(48)))
            val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }; row.addView(image,LinearLayout.LayoutParams(dp(64),dp(64)))
            val text = TextView(ctx).apply { textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(8),0,dp(4),0); minimumHeight = dp(48) }
            row.addView(text,LinearLayout.LayoutParams(0,-2,1f))
            val more = Button(ctx).apply { this.text = "⋮"; contentDescription = "파일 관리" }; row.addView(more,LinearLayout.LayoutParams(dp(48),dp(48)))
            return Holder(row,select,image,text,more)
        }
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(h: Holder,p: Int) {
            if (closed) return
            val r = rows[p]; val f = r.file
            h.path = f?.absolutePath.orEmpty(); h.select.setOnCheckedChangeListener(null)
            val selectable = r.files.filter { it.absolutePath !in RecordingJournal.activePaths }
            h.select.isChecked = if (f == null) selectable.isNotEmpty() && selectable.all { it.absolutePath in selected } else f.absolutePath in selected
            h.select.isEnabled = if (f == null) selectable.isNotEmpty() else f.absolutePath !in RecordingJournal.activePaths
            h.select.contentDescription = if (f == null) "촬영 전체 선택 " + r.title else "파일 선택 " + f.name
            h.select.setOnCheckedChangeListener { _,checked ->
                (if (f == null) selectable else listOf(f)).forEach { if (checked) selected.add(it.absolutePath) else selected.remove(it.absolutePath) }
                main.post { renderRows() }
            }
            h.thumbnail.visibility = if (f == null) View.GONE else View.VISIBLE
            h.more.visibility = if (f == null) View.GONE else View.VISIBLE
            h.text.text = if (f == null) r.title else r.title + " · " + mediaLabel(f) + " · %.1f MiB".format(f.length()/1048576.0) + "\n" + f.name
            h.text.isFocusable = true
            h.text.contentDescription = if (f == null) (if (r.key in collapsed) "촬영 펼치기 " else "촬영 접기 ") + r.title else "영상 재생 " + h.text.text
            h.more.contentDescription = f?.let { "파일 관리 " + it.name }
            h.thumbnail.contentDescription = f?.let { "영상 재생 " + it.name }
            h.thumbnail.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            h.text.setOnClickListener {
                if (f == null) { if (!collapsed.add(r.key)) collapsed.remove(r.key); renderRows() } else showMediaDetail(f)
            }
            h.more.setOnClickListener { if (f != null) manage(f) }
            h.thumbnail.setOnClickListener { if (f != null) showMediaDetail(f) }
            if (f != null) {
                h.thumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
                val key = f.absolutePath + ":" + f.length()
                val cached = cache.get(key)
                if (cached != null) h.thumbnail.setImageBitmap(cached)
                else if (mediaInfo[f.absolutePath]?.playable == true) {
                    val signal = CancellationSignal()
                    if (pending.putIfAbsent(key, signal) == null) io.execute {
                        try {
                            if (closed) return@execute
                            val bitmap = runCatching {
                                if (f.extension == "jpg") BitmapFactory.decodeFile(f.absolutePath,BitmapFactory.Options().apply { inSampleSize = 8 })
                                else if (android.os.Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(f,Size(128,128),signal)
                                else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(f.absolutePath,MediaStore.Images.Thumbnails.MINI_KIND)
                            }.getOrNull()
                            main.post {
                                if (!closed && bitmap != null) {
                                    cache.put(key, bitmap)
                                    // The original holder may have been recycled while a duplicate request was merged.
                                    val position = rows.indexOfFirst { it.file?.absolutePath == f.absolutePath }
                                    if (position >= 0) notifyItemChanged(position)
                                }
                            }
                        } finally { pending.remove(key, signal) }
                    }
                }
            }
        }
        fun close() {
            closed = true
            pending.values.forEach { it.cancel() }; pending.clear()
            io.shutdownNow(); main.removeCallbacksAndMessages(null); cache.evictAll()
        }
    }
    override fun onDestroyView() { exportButtons.clear(); onEntryLocked(); if (::adapter.isInitialized) adapter.close(); _binding = null; super.onDestroyView() }
}
