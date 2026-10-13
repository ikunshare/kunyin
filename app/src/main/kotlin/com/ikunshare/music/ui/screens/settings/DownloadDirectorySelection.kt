package com.ikunshare.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.File

internal suspend fun authorizeDownloadDirectory(uri: Uri,
    openTree: (Uri) -> DocumentFile?, persist: (Uri, Int) -> Unit): String {
    currentCoroutineContext().ensureActive()
    require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Invalid document tree" }
    val tree = openTree(uri) ?: throw IOException("Directory unavailable")
    if (!tree.exists() || !tree.isDirectory || !tree.canWrite()) throw IOException("Directory is not writable")
    currentCoroutineContext().ensureActive()
    persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    return uri.toString()
}

internal fun downloadDirectoryDisplay(treeUri: String, nativePath: String, defaultPath: String): String {
    if (treeUri.isBlank()) return nativePath.ifBlank { defaultPath }
    return runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)).takeIf { it.isNotBlank() }
    }.getOrNull() ?: treeUri
}

internal fun downloadDirectoryViewIntent(treeUri: String, nativePath: String, defaultPath: String,
    externalStorage: File): Intent? = runCatching {
    val documentUri = if (treeUri.isNotBlank()) {
        val tree = Uri.parse(treeUri)
        if (tree.scheme != "content" || !DocumentsContract.isTreeUri(tree)) return@runCatching null
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    } else {
        val root = externalStorage.canonicalPath
        val target = File(nativePath.ifBlank { defaultPath }).canonicalPath
        val prefix = root.trimEnd(File.separatorChar) + File.separator
        if (target != root && !target.startsWith(prefix)) return@runCatching null
        val relative = if (target == root) "" else target.removePrefix(prefix).replace('\\', '/')
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relative")
    }
    Intent(Intent.ACTION_VIEW).setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}.getOrNull()
