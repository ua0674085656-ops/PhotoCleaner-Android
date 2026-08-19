package com.photocleaner.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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
    val pHash: Long,
    val dHash: Long,
    val verticalHash: Long,
    val meanHash: Long,
    var groupId: String = "",
    var rank: Int = 1,
    var decision: String = "REVIEW",
    var similarity: Int = 0
)

object PhotoAnalyzer {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    // More permissive than the old single dHash threshold. Burst photos can
    // differ in framing, focus, exposure and small object movement.
    private const val MIN_SIMILARITY = 0.72
    private const val STRONG_PHASH_DISTANCE = 12

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
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@forEachIndexed

            val exactHash = sha256(context, uri)
            val features = imageFeatures(context, uri)
            results += PhotoResult(
                uri = uri,
                name = file.name ?: "unnamed",
                size = file.length(),
                width = bounds.outWidth,
                height = bounds.outHeight,
                blurScore = features.blur,
                exposure = features.exposure,
                hash = exactHash,
                pHash = features.pHash,
                dHash = features.dHash,
                verticalHash = features.verticalHash,
                meanHash = features.meanHash
            )
        }

        groupDuplicates(results, onProgress)
        return results
    }

    private data class Features(
        val blur: Double,
        val exposure: Double,
        val pHash: Long,
        val dHash: Long,
        val verticalHash: Long,
        val meanHash: Long
    )

    private fun groupDuplicates(results: MutableList<PhotoResult>, onProgress: (String) -> Unit) {
        if (results.isEmpty()) return

        val parent = IntArray(results.size) { it }
        val bestScore = DoubleArray(results.size)

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

        // Exact duplicates first.
        results.withIndex().groupBy { it.value.hash }.values
            .filter { it.size > 1 }
            .forEach { group ->
                val first = group.first().index
                group.drop(1).forEach { union(first, it.index) }
            }

        val totalPairs = results.size.toLong() * (results.size - 1L) / 2L
        var checked = 0L

        for (i in results.indices) {
            for (j in i + 1 until results.size) {
                val score = similarityScore(results[i], results[j])
                if (score >= MIN_SIMILARITY) {
                    union(i, j)
                    bestScore[i] = max(bestScore[i], score)
                    bestScore[j] = max(bestScore[j], score)
                }
                checked++
                if (checked % 50000L == 0L) {
                    onProgress("Поиск похожих фото: $checked/$totalPairs сравнений")
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
                val idx = results.indexOf(item)
                item.similarity = if (bestScore[idx] > 0.0) {
                    (bestScore[idx] * 100.0).toInt()
                } else 100
            }
        }
    }

    /**
     * Combined visual similarity. pHash is the main signal; horizontal and
     * vertical gradients help with structural changes; mean hash stabilizes
     * brightness/compression changes.
     */
    private fun similarityScore(a: PhotoResult, b: PhotoResult): Double {
        val pd = hamming(a.pHash, b.pHash)
        val dh = hamming(a.dHash, b.dHash)
        val vh = hamming(a.verticalHash, b.verticalHash)
        val mh = hamming(a.meanHash, b.meanHash)

        val p = 1.0 - pd / 64.0
        val d = 1.0 - dh / 64.0
        val v = 1.0 - vh / 64.0
        val m = 1.0 - mh / 64.0

        val aspectA = a.width.toDouble() / a.height.toDouble()
        val aspectB = b.width.toDouble() / b.height.toDouble()
        val aspectRatio = max(aspectA, aspectB) / min(aspectA, aspectB)

        // A strong pHash match survives a moderate crop/reframe.
        if (pd <= STRONG_PHASH_DISTANCE && aspectRatio <= 1.20) {
            return max(0.0, 0.52 * p + 0.20 * d + 0.16 * v + 0.12 * m)
        }

        // For burst shots require agreement from at least one gradient hash.
        val score = 0.48 * p + 0.22 * d + 0.18 * v + 0.12 * m
        val aspectPenalty = min(0.10, max(0.0, aspectRatio - 1.08) * 0.18)
        val gradientAgreement = dh <= 18 || vh <= 18
        return if (gradientAgreement) score - aspectPenalty else 0.0
    }

    private fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

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
            if (child.isDirectory) {
                // The app's own trash is an archive, not a photo source.
                // Never scan it back into the active collection.
                if (child.name != PhotoTrash.TRASH_FOLDER_NAME) {
                    collect(child, out)
                }
            } else {
                out += child
            }
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

    private fun imageFeatures(context: Context, uri: Uri): Features {
        val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return Features(0.0, 0.5, 0L, 0L, 0L, 0L)

        val bmp = Bitmap.createScaledBitmap(decoded, 32, 32, true)
        if (bmp !== decoded) decoded.recycle()

        val gray = DoubleArray(32 * 32)
        var mean = 0.0
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val g = luminance(bmp.getPixel(x, y)) / 255.0
                gray[y * 32 + x] = g
                mean += g
            }
        }
        mean /= gray.size

        var lapMean = 0.0
        var lapSq = 0.0
        var count = 0
        for (y in 1 until 31) for (x in 1 until 31) {
            val i = y * 32 + x
            val lap = gray[i - 32] + gray[i - 1] - 4 * gray[i] + gray[i + 1] + gray[i + 32]
            lapMean += lap
            lapSq += lap * lap
            count++
        }
        val lm = lapMean / count
        val variance = lapSq / count - lm * lm

        val ph = pHash(gray)
        val dh = horizontalHash(gray)
        val vh = verticalHash(gray)
        val mh = meanHash(gray, mean)
        bmp.recycle()
        return Features(variance * 10000.0, mean, ph, dh, vh, mh)
    }

    /** Standard 32x32 -> 8x8 low-frequency DCT perceptual hash. */
    private fun pHash(gray: DoubleArray): Long {
        val size = 32
        val low = DoubleArray(8 * 8)
        for (v in 0 until 8) for (u in 0 until 8) {
            var sum = 0.0
            for (y in 0 until size) for (x in 0 until size) {
                sum += gray[y * size + x] *
                    cos((2 * x + 1) * u * PI / (2.0 * size)) *
                    cos((2 * y + 1) * v * PI / (2.0 * size))
            }
            low[v * 8 + u] = sum
        }

        val values = DoubleArray(63)
        var k = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (x == 0 && y == 0) continue
            values[k++] = low[y * 8 + x]
        }
        values.sort()
        val median = values[values.size / 2]

        // Exclude DC and encode 63 AC coefficients + one deterministic bit.
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            if (x == 0 && y == 0) continue
            hash = hash shl 1
            if (low[y * 8 + x] > median) hash = hash or 1L
        }
        hash = hash shl 1
        if (low[0] > median) hash = hash or 1L
        return hash
    }

    private fun horizontalHash(gray: DoubleArray): Long {
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val x0 = x * 4
            val y0 = y * 4
            val left = gray[y0 * 32 + x0]
            val right = gray[y0 * 32 + min(31, x0 + 4)]
            hash = hash shl 1
            if (left > right) hash = hash or 1L
        }
        return hash
    }

    private fun verticalHash(gray: DoubleArray): Long {
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val x0 = x * 4
            val y0 = y * 4
            val top = gray[y0 * 32 + x0]
            val bottom = gray[min(31, y0 + 4) * 32 + x0]
            hash = hash shl 1
            if (top > bottom) hash = hash or 1L
        }
        return hash
    }

    private fun meanHash(gray: DoubleArray, mean: Double): Long {
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val g = gray[(y * 4) * 32 + x * 4]
            hash = hash shl 1
            if (g > mean) hash = hash or 1L
        }
        return hash
    }

    private fun luminance(c: Int): Int =
        (0.299 * ((c shr 16) and 255) +
            0.587 * ((c shr 8) and 255) +
            0.114 * (c and 255)).toInt()
}
