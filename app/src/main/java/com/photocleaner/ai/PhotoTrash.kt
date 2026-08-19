package com.photocleaner.ai

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * Safe intermediate trash for files removed by Photo Cleaner.
 * Files are moved into a dedicated folder instead of being permanently deleted.
 */
object PhotoTrash {
    const val TRASH_FOLDER_NAME = "PhotoCleaner Trash"

    fun getOrCreate(context: Context, rootUri: Uri): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        if (!root.isDirectory) return null
        return root.findFile(TRASH_FOLDER_NAME)?.takeIf { it.isDirectory }
            ?: root.createDirectory(TRASH_FOLDER_NAME)
    }

    fun moveToTrash(context: Context, rootUri: Uri, source: DocumentFile): Boolean {
        val trash = getOrCreate(context, rootUri) ?: return false
        val name = source.name ?: return false
        val mime = source.type ?: "application/octet-stream"
        val targetName = uniqueName(trash, name)
        val target = trash.createFile(mime, targetName) ?: return false

        return try {
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri)?.use { output ->
                    input.copyTo(output, 64 * 1024)
                } ?: throw IOException("Cannot open trash output")
            } ?: throw IOException("Cannot open source input")

            if (!source.delete()) {
                target.delete()
                false
            } else {
                true
            }
        } catch (_: Throwable) {
            target.delete()
            false
        }
    }

    private fun uniqueName(trash: DocumentFile, original: String): String {
        if (trash.findFile(original) == null) return original
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var index = 2
        while (trash.findFile("$base ($index)$ext") != null) index++
        return "$base ($index)$ext"
    }
}
