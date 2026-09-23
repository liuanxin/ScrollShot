package io.github.liuanxin.scrollshot

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** 只开放分享缓存中的单个图片, 访问权限由系统按 URI 临时授予. */
class ShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val root = File(requireNotNull(context).cacheDir, "shares")
        val file = File(root, name)
        if (uri.pathSegments.size != 1 || file.canonicalFile.parentFile != root.canonicalFile ||
            (file.extension != "jpg" && file.extension != "png") || !file.isFile) {
            throw FileNotFoundException()
        }
        return file
    }
    override fun getType(uri: Uri): String = if (file(uri).extension == "png") { "image/png" } else { "image/jpeg" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }.toTypedArray<Any?>())
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") { throw SecurityException("分享图片只允许读取") }
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { throw UnsupportedOperationException() }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { throw UnsupportedOperationException() }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { throw UnsupportedOperationException() }

    companion object {
        const val RETENTION = 24 * 60 * 60 * 1000L
        fun clean(context: Context) {
            val cutoff = System.currentTimeMillis() - RETENTION
            for (file in File(context.cacheDir, "shares").listFiles() ?: emptyArray()) {
                if (file.lastModified() <= cutoff) { file.delete() }
            }
        }
    }
}
