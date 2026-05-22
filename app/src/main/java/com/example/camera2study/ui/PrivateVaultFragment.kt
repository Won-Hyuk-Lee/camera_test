package com.example.camera2study.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.collection.LruCache
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.camera2study.R
import com.example.camera2study.databinding.FragmentPrivateVaultBinding
import com.example.camera2study.databinding.ItemVaultMediaBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class PrivateVaultFragment : Fragment() {

    private var _binding: FragmentPrivateVaultBinding? = null
    private val binding get() = _binding!!

    private var mediaFiles = ArrayList<File>()
    private lateinit var adapter: VaultAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPrivateVaultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupRecyclerView()
        loadMediaFiles()
    }

    private fun setupRecyclerView() {
        adapter = VaultAdapter(requireContext(), mediaFiles,
            onItemClick = { file -> showMediaDetail(file) },
            onItemLongClick = { file -> showMediaManageOptions(file) }
        )
        binding.rvVaultMedia.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvVaultMedia.adapter = adapter
    }

    private fun loadMediaFiles() {
        val ctx = context ?: return
        val privateDir = File(ctx.getExternalFilesDir(null), "private_vault")

        viewLifecycleOwner.lifecycleScope.launch {
            val sorted = withContext(Dispatchers.IO) {
                if (!privateDir.exists()) privateDir.mkdirs()
                privateDir.listFiles()
                    ?.filter { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".mp4")) }
                    ?.sortedByDescending { it.lastModified() }
                    .orEmpty()
            }
            if (_binding == null) return@launch
            mediaFiles.clear()
            mediaFiles.addAll(sorted)
            if (mediaFiles.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.rvVaultMedia.visibility = View.GONE
            } else {
                binding.emptyView.visibility = View.GONE
                binding.rvVaultMedia.visibility = View.VISIBLE
            }
            adapter.notifyDataSetChanged()
        }
    }

    // --- 인앱 뷰어 / 비디오 안전 플레이어 다이얼로그 ---
    private fun showMediaDetail(file: File) {
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

        dialog.show()
    }

    // --- 롱클릭 파일 관리 (내보내기 / 삭제) ---
    private fun showMediaManageOptions(file: File) {
        val options = arrayOf("공개 갤러리로 내보내기 (Export)", "영구 삭제 (Delete)")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("파일 관리 (${file.name})")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportToPublicGallery(file)
                    1 -> deleteMediaFile(file)
                }
            }
            .show()
    }

    private fun exportToPublicGallery(file: File) {
        val ctx = requireContext()
        try {
            val resolver = ctx.contentResolver
            val isVideo = file.name.endsWith(".mp4")
            
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Exported_${file.name}")
                if (isVideo) {
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StudyVault")
                } else {
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/StudyVault")
                }
            }

            val targetUri = if (isVideo) {
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }

            if (targetUri == null) {
                Toast.makeText(ctx, "갤러리 인서트 실패", Toast.LENGTH_SHORT).show()
                return
            }

            // 파일 복사
            FileInputStream(file).use { input ->
                resolver.openOutputStream(targetUri).use { output ->
                    if (output != null) {
                        input.copyTo(output)
                    }
                }
            }

            // 원본 비공개 파일 삭제
            if (file.delete()) {
                Toast.makeText(ctx, "갤러리로 정상 배출 및 보관함에서 제거됨", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "갤러리로 내보냈으나 보관함 원본 삭제에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
            
            loadMediaFiles()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(ctx, "내보내기 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteMediaFile(file: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("영구 삭제")
            .setMessage("이 영상/사진을 비공개 보관함에서 완전히 영구 삭제하시겠습니까? 복구할 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(requireContext(), "파일이 안전하게 소거되었습니다.", Toast.LENGTH_SHORT).show()
                    loadMediaFiles()
                } else {
                    Toast.makeText(requireContext(), "파일 소거 실패", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // --- Recycler Grid Adapter ---
    private class VaultAdapter(
        private val context: Context,
        private val list: List<File>,
        private val onItemClick: (File) -> Unit,
        private val onItemLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<VaultAdapter.MediaViewHolder>() {

        // 8MB 캐시. 같은 폴더에서 스크롤할 때 매번 디코드하지 않게 한다.
        private val thumbCache = object : LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
        }

        private val ioExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        class MediaViewHolder(val binding: ItemVaultMediaBinding) : RecyclerView.ViewHolder(binding.root) {
            var boundPath: String? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val binding = ItemVaultMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MediaViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            val file = list[position]
            val path = file.absolutePath
            holder.boundPath = path
            holder.binding.txtMediaName.text = file.name
            holder.binding.imgPlayMark.visibility =
                if (file.name.endsWith(".mp4")) View.VISIBLE else View.GONE

            val cached = thumbCache.get(path)
            if (cached != null) {
                holder.binding.imgThumbnail.setImageBitmap(cached)
            } else {
                holder.binding.imgThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
                ioExecutor.execute {
                    val bitmap = decodeThumbnail(file)
                    if (bitmap != null) thumbCache.put(path, bitmap)
                    mainHandler.post {
                        // 스크롤로 인해 holder가 다른 항목에 재바인딩된 경우 결과를 버린다.
                        if (holder.boundPath == path && bitmap != null) {
                            holder.binding.imgThumbnail.setImageBitmap(bitmap)
                        }
                    }
                }
            }

            holder.binding.mediaCard.setOnClickListener { onItemClick(file) }
            holder.binding.mediaCard.setOnLongClickListener {
                onItemLongClick(file)
                true
            }
        }

        private fun decodeThumbnail(file: File): android.graphics.Bitmap? = try {
            if (file.name.endsWith(".jpg")) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } else if (file.name.endsWith(".mp4")) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(file, Size(128, 128), CancellationSignal())
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                }
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        override fun getItemCount(): Int = list.size
    }
}
