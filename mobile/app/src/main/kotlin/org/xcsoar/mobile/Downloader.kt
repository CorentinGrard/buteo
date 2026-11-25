// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import org.xcsoar.mobile.ui.MessageException
import org.xcsoar.mobile.ui.text

/**
 * Downloads files from XCSoar's repository and the vector map's
 * server.  The platform does the I/O; the core parses XCSoar's index
 * (D1).
 */
class Downloader(private val cacheDir: File) {
    /**
     * An index of files, downloaded again when older than a day, or
     * when [fresh].
     *
     * @param name the local copy's, one for each index
     * @return the local copy
     */
    suspend fun index(uri: String, name: String = "repository",
                      fresh: Boolean = false): File = withContext(Dispatchers.IO) {
        val file = File(cacheDir, name)
        if (fresh || !file.exists() || System.currentTimeMillis() - file.lastModified() > DAY_MS)
            fetch(uri, file, null) {}
        file
    }

    /**
     * Download [uri] to [target] (replacing it only when complete and,
     * with [sha256], intact).
     *
     * @param progress 0..1, or -1 when the size is unknown
     */
    suspend fun download(uri: String, target: File, sha256: String?,
                         progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        fetch(uri, target, sha256, progress)
    }

    private suspend fun fetch(uri: String, target: File, sha256: String?,
                              progress: (Float) -> Unit) {
        val connection = open(uri)
        val partial = File(target.path + ".part")
        try {
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        progress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            if (sha256 != null && !digest.digest().toHex().equals(sha256, ignoreCase = true))
                throw MessageException(text(R.string.download_damaged, target.name),
                    "Download of ${target.name} is damaged (checksum)")
            if (!partial.renameTo(target))
                throw MessageException(text(R.string.download_cannot_write, target.path),
                    "Cannot write ${target.path}")
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    /**
     * Opens [uri], following redirects (also from http to https, which
     * [HttpURLConnection] does not).  Plain http is upgraded to https:
     * Android blocks clear-text traffic.  Not to the phone itself: a
     * test server through `adb reverse`, which only the test app (l5)
     * may reach in clear text.
     */
    private fun open(uri: String): HttpURLConnection {
        var url = URL(if (uri.startsWith("http://127.0.0.1:")) uri
                      else uri.replaceFirst(Regex("^http://"), "https://"))
        repeat(MAX_REDIRECTS) {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw MessageException(text(R.string.download_no_location, url.toString()),
                            "Redirect without location from $url")
                    connection.disconnect()
                    url = URL(URL(url, location).toString().replaceFirst(Regex("^http://"), "https://"))
                }
                else -> {
                    connection.disconnect()
                    throw MessageException(text(R.string.download_http, code, url.toString()),
                        "HTTP $code for $url")
                }
            }
        }
        throw MessageException(text(R.string.download_redirects, uri),
            "Too many redirects for $uri")
    }

    private companion object {
        const val DAY_MS = 24 * 3600 * 1000L
        const val MAX_REDIRECTS = 5

        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
