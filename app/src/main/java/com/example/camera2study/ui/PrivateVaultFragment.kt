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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.camera2study.util.RecordingJournal
import com.example.camera2study.util.VideoFiles
import com.example.camera2study.util.VaultExports
import com.example.camera2study.R
import com.example.camera2study.databinding.FragmentPrivateVaultBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class PrivateVaultFragment : Fragment(), com.example.camera2study.EntryLockListener {
    private val dialogs = mutableSetOf<android.app.Dialog>()
    override fun onEntryLocked() { dialogs.toList().forEach { it.dismiss() }; dialogs.clear() }
    override fun onStop() { onEntryLocked(); super.onStop() }
    private var _binding: FragmentPrivateVaultBinding? = null
    private val binding get() = _binding!!
    private var loadVersion = 0
    private var refreshKey = ""
    private val mediaFiles = arrayListOf<File>()
    private val mediaInfo = mutableMapOf<String, VideoFiles.Info>()
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
        setupChoice(binding.statusFilter,listOf("모든 상태","완료","기록 중","확인 필요"),filter) { filter = it; renderRows() }
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
            val key = state.sessionId + ":" + state.segment + ":" + state.phase
            if (key != refreshKey) { refreshKey = key; loadMediaFiles() }
        }
    }
    private fun renderExports(jobs: List<VaultExports.Job>) {
        (binding.exportStatus.parent as View).visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        val visible = jobs.filter { it.busy || it.state != "COMPLETE" }.take(3) + jobs.filter { it.state == "COMPLETE" }.take(1)
        if (exportButtons.keys.toList() != visible.map { it.id }) {
            binding.exportStatus.removeAllViews(); exportButtons.clear()
            binding.exportStatus.addView(TextView(requireContext()).apply {
                text = "내보내기 · 원본 유지 / 앱 종료 시 다음 실행에서 재시도"
                setTextColor(Color.WHITE); textSize = 14f
            })
            visible.forEach { job ->
                val button = Button(requireContext())
                exportButtons[job.id] = button
                binding.exportStatus.addView(button)
            }
        }
        visible.forEach { job ->
            val status = when (job.state) { "QUEUED" -> "대기"; "COPYING" -> "복사"; "VERIFYING" -> "검증"; "COMPLETE" -> "완료"; "CANCELED" -> "취소"; else -> "실패" }
            exportButtons[job.id]?.apply {
                text = File(job.path).name + " · " + status + " " + (job.done * 100 / job.size.coerceAtLeast(1)) + "%" +
                    if (job.busy) " · 취소" else if (job.state != "COMPLETE") " · 재시도: " + job.error else ""
                contentDescription = if (job.busy) "내보내기 취소 " + File(job.path).name
                    else if (job.state != "COMPLETE") "내보내기 재시도 " + File(job.path).name else "내보내기 완료 " + File(job.path).name
                setOnClickListener { if (job.busy) VaultExports.cancel(job.id) else VaultExports.retry(job.id) }
            }
        }
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
                    (sessionId == null || it.name.contains(sessionId)) }.map { it to VideoFiles.inspect(it) }
            }
            if (_binding == null || version != loadVersion) return@launch
            mediaFiles.clear(); mediaFiles.addAll(files.map { it.first })
            mediaInfo.clear(); files.forEach { mediaInfo[it.first.absolutePath] = it.second }
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
                when (filter) { 1 -> info?.playable == true; 2 -> file.absolutePath in RecordingJournal.activePaths; 3 -> info?.playable != true && file.absolutePath !in RecordingJournal.activePaths; else -> true }
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
        binding.selectionSummary.text = groups.size.toString() + "개 촬영 · " + selected.size + "개 선택"
        binding.exportSelected.isEnabled = selected.isNotEmpty(); binding.deleteSelected.isEnabled = selected.isNotEmpty()
        adapter.notifyDataSetChanged()
    }
    private fun export(files: List<File>) {
        val eligible = files.filter { mediaInfo[it.absolutePath]?.playable == true && it.absolutePath !in RecordingJournal.activePaths }
        eligible.forEach { runCatching { VaultExports.enqueue(requireContext(),it) }.onFailure { toast(it.message.orEmpty()) } }
        toast(eligible.size.toString() + "개 내보내기 요청 · 원본 유지" + if (eligible.size < files.size) " · 기록 중/손상 파일 제외" else "")
    }
    private fun delete(files: List<File>) {
        MaterialAlertDialogBuilder(requireContext()).setTitle("선택 파일 영구 삭제")
            .setMessage(files.size.toString() + "개 파일을 삭제합니다. 복구할 수 없으며 기록·내보내기 중인 파일은 제외합니다.")
            .setPositiveButton("삭제") { _,_ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val count = withContext(Dispatchers.IO) { files.count { VaultExports.deleteSource(it) } }
                    toast(count.toString() + "개 삭제"); loadMediaFiles()
                }
            }.setNegativeButton("취소",null).create().also { dialogs.add(it); it.show() }
    }
    private fun manage(file: File) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(file.name).setItems(arrayOf("공개 갤러리로 내보내기","영구 삭제")) { _,which ->
            if (which == 0) export(listOf(file)) else delete(listOf(file))
        }.create().also { dialogs.add(it); it.show() }
    }
    private fun toast(text: String) { context?.let { Toast.makeText(it,text,Toast.LENGTH_LONG).show() } }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun showMediaDetail(file: File) {
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

        if (file.name.endsWith(".jpg")) {
            val img = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            img.setImageBitmap(bitmap)
            container.addView(img)
        } else if (file.name.endsWith(".mp4")) {
            val videoView = VideoView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            container.addView(videoView)

            val controller = MediaController(ctx)
            controller.setAnchorView(videoView)
            videoView.setMediaController(controller)
            videoView.setVideoPath(file.absolutePath)

            videoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                videoView.start()
            }

            // 다이얼로그가 닫힐 때 MediaPlayer/audio focus 누수를 방지한다.
            dialog.setOnDismissListener {
                try {
                    videoView.stopPlayback()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        dialogs.add(dialog)
        dialog.show()
    }


    private inner class VaultAdapter : RecyclerView.Adapter<VaultAdapter.Holder>() {
        private val io = java.util.concurrent.Executors.newFixedThreadPool(2)
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
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
            val r = rows[p]; val f = r.file
            h.path = f?.absolutePath.orEmpty(); h.select.setOnCheckedChangeListener(null)
            val selectable = r.files.filter { it.absolutePath !in RecordingJournal.activePaths }
            h.select.isChecked = if (f == null) selectable.isNotEmpty() && selectable.all { it.absolutePath in selected } else f.absolutePath in selected
            h.select.isEnabled = if (f == null) selectable.isNotEmpty() else f.absolutePath !in RecordingJournal.activePaths
            h.select.contentDescription = if (f == null) "촬영 전체 선택 " + r.title else "파일 선택 " + r.title
            h.select.setOnCheckedChangeListener { _,checked ->
                (if (f == null) selectable else listOf(f)).forEach { if (checked) selected.add(it.absolutePath) else selected.remove(it.absolutePath) }
                main.post { renderRows() }
            }
            h.thumbnail.visibility = if (f == null) View.GONE else View.VISIBLE
            h.more.visibility = if (f == null) View.GONE else View.VISIBLE
            h.text.text = if (f == null) r.title else r.title + " · " + mediaInfo[f.absolutePath]?.label.orEmpty() + " · %.1f MiB".format(f.length()/1048576.0) + "\n" + f.name
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
                else if (mediaInfo[f.absolutePath]?.playable == true) io.execute {
                    val bitmap = runCatching {
                        if (f.extension == "jpg") BitmapFactory.decodeFile(f.absolutePath,BitmapFactory.Options().apply { inSampleSize = 8 })
                        else if (android.os.Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(f,Size(128,128),CancellationSignal())
                        else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(f.absolutePath,MediaStore.Images.Thumbnails.MINI_KIND)
                    }.getOrNull()
                    if (bitmap != null) cache.put(key,bitmap)
                    main.post { if (h.path == f.absolutePath && bitmap != null) h.thumbnail.setImageBitmap(bitmap) }
                }
            }
        }
        fun close() { io.shutdownNow(); main.removeCallbacksAndMessages(null); cache.evictAll() }
    }
    override fun onDestroyView() { exportButtons.clear(); onEntryLocked(); if (::adapter.isInitialized) adapter.close(); _binding = null; super.onDestroyView() }
}
