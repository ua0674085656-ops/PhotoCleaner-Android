package com.photocleaner.ai

import android.content.Intent
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
            text = "Photo Cleaner AI — Android MVP-1"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 14)
        }
        status = TextView(this).apply { setTextColor(Color.LTGRAY); text = "Выберите папку с фотографиями" }
        progress = TextView(this).apply { setTextColor(Color.rgb(120, 220, 120)); text = "Готов" }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val choose = Button(this).apply { text = "ВЫБРАТЬ ПАПКУ"; setOnClickListener { folderPicker.launch(null) } }
        scanButton = Button(this).apply { text = "СКАНИРОВАТЬ"; isEnabled = false; setOnClickListener { startScan() } }
        deleteButton = Button(this).apply { text = "УДАЛИТЬ КАНДИДАТОВ"; isEnabled = false; setOnClickListener { deleteCandidates() } }
        buttons.addView(choose, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(scanButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(deleteButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
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
                    progress.text = "Готово: ${data.size} фото. Кандидатов: ${data.count { it.decision == "CANDIDATE" }}."
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
        sorted.take(300).forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 8, 4, 8)
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(110, 110)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(p.uri)
            }
            val text = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                val mb = p.size / 1024.0 / 1024.0
                text = "${p.decision}  ${p.groupId.ifEmpty { "—" }}  #${p.rank}\n" +
                    "Blur: ${"%.1f".format(p.blurScore)} | Exp: ${"%.2f".format(p.exposure)}\n" +
                    "${p.width}×${p.height} | ${"%.2f".format(mb)} MB\n${p.name}"
                setPadding(12, 0, 0, 0)
            }
            row.addView(image)
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
    }

    private fun deleteCandidates() {
        val candidates = results.filter { it.decision == "CANDIDATE" }
        var deleted = 0
        candidates.forEach { p ->
            try {
                if (DocumentFile.fromSingleUri(this, p.uri)?.delete() == true) deleted++
            } catch (_: Throwable) { }
        }
        progress.text = "Удалено: $deleted. Ничего не удалялось из уникальных групп."
        deleteButton.isEnabled = false
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
