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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
        val privateDir = File(requireContext().getExternalFilesDir(null), "private_vault")
        if (!privateDir.exists()) privateDir.mkdirs()

        val files = privateDir.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".mp4"))
        }

        mediaFiles.clear()
        if (!files.isNullOrEmpty()) {
            // 최신 파일 순 정렬
            mediaFiles.addAll(files.sortedByDescending { it.lastModified() })
            binding.emptyView.visibility = View.GONE
            binding.rvVaultMedia.visibility = View.VISIBLE
        } else {
            binding.emptyView.visibility = View.VISIBLE
            binding.rvVaultMedia.visibility = View.GONE
        }
        adapter.notifyDataSetChanged()
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

        class MediaViewHolder(val binding: ItemVaultMediaBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val binding = ItemVaultMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MediaViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            val file = list[position]
            holder.binding.txtMediaName.text = file.name

            // 썸네일 디코딩 및 생성
            try {
                if (file.name.endsWith(".jpg")) {
                    holder.binding.imgPlayMark.visibility = View.GONE
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    holder.binding.imgThumbnail.setImageBitmap(bitmap)
                } else if (file.name.endsWith(".mp4")) {
                    holder.binding.imgPlayMark.visibility = View.VISIBLE
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val bitmap = ThumbnailUtils.createVideoThumbnail(file, Size(128, 128), CancellationSignal())
                        holder.binding.imgThumbnail.setImageBitmap(bitmap)
                    } else {
                        @Suppress("DEPRECATION")
                        val bitmap = ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                        holder.binding.imgThumbnail.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                holder.binding.imgThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.binding.mediaCard.setOnClickListener { onItemClick(file) }
            holder.binding.mediaCard.setOnLongClickListener {
                onItemLongClick(file)
                true
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
