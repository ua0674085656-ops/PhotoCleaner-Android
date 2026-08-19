package com.photocleaner.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
    val perceptualHash: Long,
    var groupId: String = "",
    var rank: Int = 1,
    var decision: String = "REVIEW"
)

object PhotoAnalyzer {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private const val PERCEPTUAL_DISTANCE_THRESHOLD = 10

    fun scan(context: Context, rootUri: Uri, onProgress: (String) -> Unit): List<PhotoResult> {
        val files = mutableListOf<DocumentFile>()
        collect(DocumentFile.fromTreeUri(context, rootUri), files)
        val images = files.filter {
            it.isFile && imageExt.contains(it.name?.substringAfterLast('.', "")?.lowercase())
        }
        val results = mutableListOf<PhotoResult>()

        images.forEachIndexed { index, file ->
            onProgress("Анализ фото ${index + 1}/${images.size}: ${file.name}")
            val uri = file.uri
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return@forEachIndexed

            val exactHash = sha256(context, uri)
            val metrics = bitmapMetrics(context, uri)
            val pHash = perceptualHash(context, uri)

            results += PhotoResult(
                uri = uri,
                name = file.name ?: "unnamed",
                size = file.length(),
                width = opts.outWidth,
                height = opts.outHeight,
                blurScore = metrics.first,
                exposure = metrics.second,
                hash = exactHash,
                perceptualHash = pHash
            )
        }

        groupDuplicates(results, onProgress)
        return results
    }

    private fun groupDuplicates(results: MutableList<PhotoResult>, onProgress: (String) -> Unit) {
        if (results.isEmpty()) return
        val parent = IntArray(results.size) { it }

        fun find(x0: Int): Int {
            var x = x0
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        results.withIndex().groupBy { it.value.hash }.values.filter { it.size > 1 }.forEach { group ->
            val first = group.first().index
            group.drop(1).forEach { union(first, it.index) }
        }

        val totalPairs = results.size.toLong() * (results.size - 1L) / 2L
        var checked = 0L
        for (i in results.indices) {
            for (j in i + 1 until results.size) {
                val a = results[i]
                val b = results[j]
                if (sameAspect(a, b) &&
                    java.lang.Long.bitCount(a.perceptualHash xor b.perceptualHash) <= PERCEPTUAL_DISTANCE_THRESHOLD
                ) {
                    union(i, j)
                }
                checked++
                if (checked % 50000L == 0L) {
                    onProgress("Поиск похожих дублей: $checked/$totalPairs сравнений")
                }
            }
        }

        val groups = results.indices.groupBy { find(it) }.values.filter { it.size > 1 }
        groups.forEachIndexed { groupIndex, indexes ->
            val ordered = indexes.map { results[it] }.sortedByDescending { quality(it) }
            val gid = "G" + (groupIndex + 1).toString().padStart(4, '0')
            ordered.forEachIndexed { rank, item ->
                item.groupId = gid
                item.rank = rank + 1
                item.decision = if (rank == 0) "BEST" else "CANDIDATE"
            }
        }
    }

    private fun sameAspect(a: PhotoResult, b: PhotoResult): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return false
        val arA = a.width.toDouble() / a.height
        val arB = b.width.toDouble() / b.height
        val ratio = max(arA, arB) / min(arA, arB)
        return ratio <= 1.08
    }

    fun quality(p: PhotoResult): Double {
        val resolution = min(20.0, (p.width.toDouble() * p.height.toDouble()) / 1_000_000.0)
        val sharpness = min(40.0, p.blurScore / 20.0)
        val exposureScore = max(0.0, 20.0 - abs(p.exposure - 0.50) * 40.0)
        val fileDensity = min(
            20.0,
            if (p.width > 0 && p.height > 0)
                p.size.toDouble() / (p.width * p.height).toDouble() * 100.0
            else 0.0
        )
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

    private fun perceptualHash(context: Context, uri: Uri): Long {
        val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return 0L
        val bmp = Bitmap.createScaledBitmap(decoded, 9, 8, true)
        if (bmp !== decoded) decoded.recycle()
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = luminance(bmp.getPixel(x, y))
                val right = luminance(bmp.getPixel(x + 1, y))
                hash = hash shl 1
                if (left > right) hash = hash or 1L
            }
        }
        bmp.recycle()
        return hash
    }

    private fun luminance(c: Int): Int =
        (0.299 * ((c shr 16) and 255) +
            0.587 * ((c shr 8) and 255) +
            0.114 * (c and 255)).toInt()

    private fun bitmapMetrics(context: Context, uri: Uri): Pair<Double, Double> {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return 0.0 to 0.5
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) {
            bmp.recycle()
            return 0.0 to 0.5
        }
        var mean = 0.0
        val gray = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val g = luminance(bmp.getPixel(x, y)) / 255.0
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
