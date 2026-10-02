package com.fbvd.downloader

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: TextInputEditText
    private lateinit var rvHistory: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var historyManager: DownloadHistoryManager
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyManager = DownloadHistoryManager(this)
        etUrl = findViewById(R.id.etUrl)
        rvHistory = findViewById(R.id.rvHistory)
        tvEmptyState = findViewById(R.id.tvEmptyState)

        setupRecyclerView()

        findViewById<View>(R.id.btnDownload).setOnClickListener {
            val url = etUrl.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                initiateDownload(url)
            } else {
                showToast("Please enter or paste a valid Facebook URL")
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                etUrl.setText(sharedText)
                showToast("URL detected from Share. Extracting video...")
                initiateDownload(sharedText)
            }
        }
    }

    private fun initiateDownload(url: String) {
        showToast("Fetching video stream & metadata...")
        lifecycleScope.launch {
            try {
                val data = VideoExtractor.extract(url)
                if (data != null) {
                    startDownloadManager(data.videoDownloadUrl, data.title)
                    val newItem = DownloadItem(
                        title = data.title,
                        description = data.description,
                        hashtags = data.hashtags,
                        originalUrl = url
                    )
                    historyManager.addItem(newItem)
                    refreshHistoryList()
                    showToast("Download started: ${data.title}")
                    etUrl.text?.clear()
                } else {
                    showToast("Failed to parse video. Ensure it is public and accessible.")
                }
            } catch (e: Exception) {
                showToast("Error: ${e.localizedMessage ?: "Unknown error occurred"}")
            }
        }
    }

    private fun startDownloadManager(videoUrl: String, title: String) {
        try {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(40)
            val fileName = "FBVD_${cleanTitle}_${System.currentTimeMillis()}.mp4"

            val request = DownloadManager.Request(Uri.parse(videoUrl)).apply {
                setTitle(title.take(30))
                setDescription("Downloading via FBVD")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "FBVD/$fileName")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            downloadManager.enqueue(request)
        } catch (e: Exception) {
            showToast("Download error: ${e.message}")
        }
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            items = historyManager.getHistory(),
            onCopy = { text, label -> copyToClipboard(text, label) },
            onDelete = { item ->
                historyManager.removeItem(item)
                refreshHistoryList()
                showToast("Entry removed")
            }
        )
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter
        updateEmptyState()
    }

    private fun refreshHistoryList() {
        adapter.updateList(historyManager.getHistory())
        updateEmptyState()
    }

    private fun updateEmptyState() {
        tvEmptyState.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun copyToClipboard(text: String, label: String) {
        if (text.isBlank()) {
            showToast("Nothing to copy")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        showToast("Copied $label to clipboard")
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    class HistoryAdapter(
        private var items: MutableList<DownloadItem>,
        private val onCopy: (text: String, label: String) -> Unit,
        private val onDelete: (item: DownloadItem) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvMainSummary: TextView = v.findViewById(R.id.tvMainSummary)
            val btnCopyAll: ImageView = v.findViewById(R.id.btnCopyAll)
            val btnDelete: ImageView = v.findViewById(R.id.btnDelete)
            val layoutExpanded: LinearLayout = v.findViewById(R.id.layoutExpanded)
            val tvTitle: TextView = v.findViewById(R.id.tvTitle)
            val tvDescription: TextView = v.findViewById(R.id.tvDescription)
            val tvHashtags: TextView = v.findViewById(R.id.tvHashtags)
            val btnCopyTitle: ImageView = v.findViewById(R.id.btnCopyTitle)
            val btnCopyDesc: ImageView = v.findViewById(R.id.btnCopyDesc)
            val btnCopyHashtags: ImageView = v.findViewById(R.id.btnCopyHashtags)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvMainSummary.text = item.title.ifBlank { item.description.ifBlank { "Facebook Reel" } }
            holder.tvTitle.text = "Title: ${item.title.ifBlank { "N/A" }}"
            holder.tvDescription.text = "Description: ${item.description.ifBlank { "N/A" }}"
            holder.tvHashtags.text = "Tags: ${item.hashtags.ifBlank { "None" }}"

            holder.layoutExpanded.visibility = if (item.isExpanded) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    items[currentPos].isExpanded = !items[currentPos].isExpanded
                    notifyItemChanged(currentPos)
                }
            }

            holder.btnCopyAll.setOnClickListener {
                onCopy(item.allCombined, "All Info")
            }

            holder.btnCopyTitle.setOnClickListener {
                onCopy(item.title, "Title")
            }

            holder.btnCopyDesc.setOnClickListener {
                onCopy(item.description, "Description")
            }

            holder.btnCopyHashtags.setOnClickListener {
                onCopy(item.hashtags, "Hashtags")
            }

            holder.btnDelete.setOnClickListener {
                onDelete(item)
            }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newList: List<DownloadItem>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
