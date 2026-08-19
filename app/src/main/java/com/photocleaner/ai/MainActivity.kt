package com.photocleaner.ai

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var selectedTree: Uri? = null
    private var results: List<PhotoResult> = emptyList()
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var list: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var deleteButton: Button

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            selectedTree = uri
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            status.text = "Папка выбрана: ${DocumentFile.fromTreeUri(this, uri)?.name ?: uri}"
            scanButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val title = TextView(this).apply {
            text = "Photo Cleaner AI — Similar Photos v4"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 14)
        }
        status = TextView(this).apply { setTextColor(Color.LTGRAY); text = "Выберите папку с фотографиями" }
        progress = TextView(this).apply { setTextColor(Color.rgb(120, 220, 120)); text = "Готов" }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val choose = Button(this).apply { text = "ВЫБРАТЬ ПАПКУ"; setOnClickListener { folderPicker.launch(null) } }
        scanButton = Button(this).apply { text = "СКАНИРОВАТЬ"; isEnabled = false; setOnClickListener { startScan() } }
        deleteButton = Button(this).apply { text = "В КОРЗИНУ"; isEnabled = false; setOnClickListener { moveCandidatesToTrash() } }
        buttons.addView(choose, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(scanButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(deleteButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(title)
        root.addView(status)
        root.addView(progress)
        root.addView(buttons)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun startScan() {
        val tree = selectedTree ?: return
        scanButton.isEnabled = false
        deleteButton.isEnabled = false
        list.removeAllViews()
        progress.text = "Подготовка анализа..."
        executor.execute {
            try {
                val data = PhotoAnalyzer.scan(this, tree) { message -> runOnUiThread { progress.text = message } }
                results = data
                runOnUiThread {
                    renderResults(data)
                    scanButton.isEnabled = true
                    deleteButton.isEnabled = data.any { it.decision == "CANDIDATE" }
                    val candidates = data.count { it.decision == "CANDIDATE" }
                    val keep = data.count { it.decision == "KEEP" }
                    val best = data.count { it.decision == "BEST" }
                    val groups = data.map { it.groupId }.filter { it.isNotEmpty() }.distinct().size
                    progress.text = "Готово: ${data.size} фото. Групп: $groups. Лучших: $best. Оставляем: $keep. В корзину: $candidates."
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    scanButton.isEnabled = true
                    progress.text = "ОШИБКА: ${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }
    }

    private fun renderResults(data: List<PhotoResult>) {
        val sorted = data.sortedWith(compareBy<PhotoResult> { it.groupId.ifEmpty { "ZZZZZZ" } }.thenBy { it.rank }.thenBy { it.name })
        sorted.take(500).forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 8, 4, 8)
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(110, 110)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.DKGRAY)
            }
            val text = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                val mb = p.size / 1024.0 / 1024.0
                val match = if (p.groupId.isNotEmpty()) " | Match: ${p.similarity}%" else ""
                val action = when (p.decision) {
                    "BEST" -> "BEST — лучший кадр"
                    "KEEP" -> "KEEP — оставить в серии"
                    "CANDIDATE" -> "CANDIDATE — в корзину"
                    else -> p.decision
                }
                text = "$action  ${p.groupId.ifEmpty { "—" }}  #${p.rank}$match\n" +
                    "Качество: ${"%.1f".format(PhotoAnalyzer.quality(p))} | Blur: ${"%.1f".format(p.blurScore)} | Exp: ${"%.2f".format(p.exposure)}\n" +
                    "${p.width}×${p.height} | ${"%.2f".format(mb)} MB\n${p.name}"
                setPadding(12, 0, 0, 0)
            }
            row.addView(image)
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)

            executor.execute {
                val bitmap = decodeThumbnail(p.uri, 220, 220)
                if (bitmap != null && !isFinishing) {
                    runOnUiThread {
                        if (!isFinishing) image.setImageBitmap(bitmap)
                    }
                }
            }
        }
        if (sorted.size > 500) {
            val more = TextView(this).apply {
                text = "Показано 500 из ${sorted.size} фото."
                setTextColor(Color.LTGRAY)
                setPadding(8, 16, 8, 24)
            }
            list.addView(more)
        }
    }

    private fun decodeThumbnail(uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun moveCandidatesToTrash() {
        val tree = selectedTree ?: return
        val candidates = results.filter { it.decision == "CANDIDATE" }
        if (candidates.isEmpty()) return

        deleteButton.isEnabled = false
        scanButton.isEnabled = false
        progress.text = "Перемещение в корзину..."

        executor.execute {
            var moved = 0
            var failed = 0
            candidates.forEach { p ->
                val source = DocumentFile.fromSingleUri(this, p.uri)
                if (source != null && PhotoTrash.moveToTrash(this, tree, source)) moved++ else failed++
            }

            runOnUiThread {
                scanButton.isEnabled = true
                results = results.filterNot { it.decision == "CANDIDATE" }
                progress.text = if (failed == 0) {
                    "В корзину: $moved. Файлы не удалены окончательно."
                } else {
                    "В корзину: $moved. Ошибок перемещения: $failed."
                }
                deleteButton.isEnabled = false
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
