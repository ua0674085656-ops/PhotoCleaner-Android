package com.photocleaner.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

 data class PhotoResult(
    val uri: Uri,
    val name: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val blurScore: Double,
    val exposure: Double,
    val hash: String,
    var groupId: String = "",
    var rank: Int = 1,
    var decision: String = "REVIEW"
)

object PhotoAnalyzer {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    fun scan(context: Context, rootUri: Uri, onProgress: (String) -> Unit): List<PhotoResult> {
        val files = mutableListOf<DocumentFile>()
        collect(DocumentFile.fromTreeUri(context, rootUri), files)
        val images = files.filter { it.isFile && imageExt.contains(it.name?.substringAfterLast('.', "")?.lowercase()) }
        val results = mutableListOf<PhotoResult>()

        images.forEachIndexed { index, file ->
            onProgress("Анализ фото ${index + 1}/${images.size}: ${file.name}")
            val uri = file.uri
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return@forEachIndexed

            val hash = sha256(context, uri)
            val score = bitmapMetrics(context, uri)
            results += PhotoResult(
                uri = uri,
                name = file.name ?: "unnamed",
                size = file.length(),
                width = opts.outWidth,
                height = opts.outHeight,
                blurScore = score.first,
                exposure = score.second,
                hash = hash
            )
        }

        results.groupBy { it.hash }.forEach { (_, group) ->
            if (group.size > 1) {
                val ordered = group.sortedByDescending { quality(it) }
                val gid = "G" + group.first().hash.take(6).uppercase()
                ordered.forEachIndexed { i, item ->
                    item.groupId = gid
                    item.rank = i + 1
                    item.decision = if (i == 0) "BEST" else "CANDIDATE"
                }
            }
        }
        return results
    }

    fun quality(p: PhotoResult): Double {
        val resolution = min(20.0, (p.width.toDouble() * p.height.toDouble()) / 1_000_000.0)
        val sharpness = min(40.0, p.blurScore / 20.0)
        val exposureScore = max(0.0, 20.0 - abs(p.exposure - 0.50) * 40.0)
        val fileDensity = min(20.0, if (p.width > 0 && p.height > 0) p.size.toDouble() / (p.width * p.height).toDouble() * 100.0 else 0.0)
        return resolution + sharpness + exposureScore + fileDensity
    }

    private fun collect(dir: DocumentFile?, out: MutableList<DocumentFile>) {
        if (dir == null || !dir.isDirectory) return
        dir.listFiles().forEach { child ->
            if (child.isDirectory) collect(child, out) else out += child
        }
    }

    private fun sha256(context: Context, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bitmapMetrics(context: Context, uri: Uri): Pair<Double, Double> {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return 0.0 to 0.5
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return 0.0 to 0.5
        var mean = 0.0
        val gray = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = bmp.getPixel(x, y)
            val g = (0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)) / 255.0
            gray[y * w + x] = g
            mean += g
        }
        mean /= gray.size
        var lapMean = 0.0
        var lapSq = 0.0
        var count = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = gray[i - w] + gray[i - 1] - 4 * gray[i] + gray[i + 1] + gray[i + w]
            lapMean += lap
            lapSq += lap * lap
            count++
        }
        val m = lapMean / count
        val variance = lapSq / count - m * m
        bmp.recycle()
        return variance * 10000.0 to mean
    }
}
