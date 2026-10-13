package com.mirage.spike

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Resumes an immutable model download; only verified bytes become an installed model. */
internal class VerifiedModelDownload(
    private val client: OkHttpClient,
    private val url: String,
    private val bytes: Long,
    private val sha256: String
) {
    fun download(
        destination: File,
        checkActive: () -> Unit = {},
        onCall: (Call?) -> Unit = {},
        onProgress: (Long) -> Unit = {}
    ) {
        val part = File(destination.path + ".part")
        if (part.length() > bytes) check(part.delete()) { "Could not replace an invalid partial download" }
        var offset = part.length()
        if (offset < bytes) {
            check(destination.parentFile!!.usableSpace > bytes - offset + 128L * 1024 * 1024) {
                "More free storage is needed to finish the voice download"
            }
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
                if (offset > 0) header("Range", "bytes=$offset-")
            }.build()
            val call = client.newCall(request)
            onCall(call)
            try {
                checkActive()
                call.execute().use { response ->
                    check(response.isSuccessful) { "Download failed (${response.code})" }
                    if (response.code == 206) {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                        check(range != null && range.groupValues[1].toLong() == offset &&
                            range.groupValues[2].toLong() == bytes - 1 && range.groupValues[3].toLong() == bytes) {
                            "The download server returned an unexpected range; retry the download"
                        }
                    } else {
                        check(response.code == 200) { "Unexpected download response" }
                        offset = 0 // A server may ignore Range; never append a full response.
                    }
                    val body = checkNotNull(response.body) { "The download response was empty" }
                    check(body.contentLength() < 0 || body.contentLength() == bytes - offset) { "Unexpected model size" }
                    var total = offset
                    onProgress(total)
                    body.byteStream().use { input -> FileOutputStream(part, offset > 0).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(total + count <= bytes) { "Unexpected model size" }
                            output.write(buffer, 0, count)
                            total += count
                            onProgress(total)
                        }
                    } }
                    check(total == bytes) { "Download interrupted; retry to continue from the saved progress" }
                }
            } finally {
                onCall(null)
            }
        }
        checkActive()
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (part.length() != bytes || digest.digest().joinToString("") { "%02x".format(it) } != sha256) {
            check(part.delete()) { "Could not remove an invalid download" }
            error("Model verification failed; retry to download a fresh copy")
        }
        checkActive()
        check(part.renameTo(destination)) { "Could not finish saving the verified model" }
    }
}
