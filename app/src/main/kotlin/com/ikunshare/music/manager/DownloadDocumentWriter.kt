package com.ikunshare.music.manager

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal fun downloadDocumentParent(root: DocumentFile, subDir: String, create: Boolean): DocumentFile? {
    var parent = root
    for (part in subDir.split('/', '\\').filter { it.isNotBlank() }) {
        require(part != "." && part != "..") { "Invalid download directory" }
        parent = parent.findFile(part) ?: if (create) parent.createDirectory(part) ?: return null else return null
        if (!parent.isDirectory) return null
    }
    return parent.takeIf { it.isDirectory }
}

/** A failed SAF write must never be reported as success or redirected to another directory. */
internal suspend fun writeDownloadDocument(root: DocumentFile, subDir: String, fileName: String,
    sourceFile: File, overwrite: Boolean, openOutput: (Uri) -> OutputStream?): Uri {
    currentCoroutineContext().ensureActive()
    val parent = downloadDocumentParent(root, subDir, create = true)
        ?: throw IOException("无法创建下载子目录: $subDir")
    if (!parent.canWrite()) throw IOException("下载目录不可写")
    val existing = parent.findFile(fileName)
    if (existing != null && !overwrite) throw IOException("目标文件已存在: $fileName")
    if (existing?.isDirectory == true) throw IOException("目标名称已被目录占用: $fileName")
    val mime = when (fileName.substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "lrc" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
    val target = existing ?: parent.createFile(mime, fileName)
        ?: throw IOException("无法创建下载文件: $fileName")
    try {
        if (target.name != fileName) throw IOException("文件提供器更改了目标名称: $fileName")
        val output = openOutput(target.uri) ?: throw IOException("无法打开下载文件: $fileName")
        output.use { stream ->
            sourceFile.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    stream.write(buffer, 0, read)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return target.uri
    } catch (failure: Throwable) {
        if (existing == null) runCatching { target.delete() }
        throw failure
    }
}
