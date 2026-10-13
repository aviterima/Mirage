package com.mirage.spike

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class VerifiedModelDownloadTest {
    @get:Rule val folder = TemporaryFolder()
    private val content = "A small synthetic model download".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private fun downloader(server: MockWebServer) = VerifiedModelDownload(OkHttpClient(), server.url("/pinned-model").toString(), content.size.toLong(), hash)
    private fun target() = File(folder.root, "model")

    @Test fun resumesVerifiedPartialDownloadAndInstallsExactBytes() = MockWebServer().use { server ->
        val file = target()
        File(file.path + ".part").writeBytes(content.take(7).toByteArray())
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 7-${content.size - 1}/${content.size}")
            .setBody(Buffer().write(content.copyOfRange(7, content.size))))
        downloader(server).download(file)
        assertArrayEquals(content, file.readBytes())
        assertEquals("bytes=7-", server.takeRequest().getHeader("Range"))
        assertFalse(File(file.path + ".part").exists())
    }

    @Test fun aServerIgnoringRangeRestartsInsteadOfAppending() = MockWebServer().use { server ->
        val file = target()
        File(file.path + ".part").writeBytes(content.take(7).toByteArray())
        server.enqueue(MockResponse().setBody(Buffer().write(content)))
        downloader(server).download(file)
        assertArrayEquals(content, file.readBytes())
    }

    @Test fun rejectsWrongRangeWithoutOverwritingPartialBytes() = MockWebServer().use { server ->
        val file = target()
        val part = File(file.path + ".part")
        val original = content.take(7).toByteArray()
        part.writeBytes(original)
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 8-${content.size - 1}/${content.size}")
            .setBody(Buffer().write(content.copyOfRange(8, content.size))))
        assertThrows(IllegalStateException::class.java) { downloader(server).download(file) }
        assertArrayEquals(original, part.readBytes())
        assertFalse(file.exists())
    }

    @Test fun rejectsCorruptionAndRemovesInvalidPartialFile() = MockWebServer().use { server ->
        val file = target()
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(content.size) { 42 })))
        assertThrows(IllegalStateException::class.java) { downloader(server).download(file) }
        assertFalse(file.exists())
        assertFalse(File(file.path + ".part").exists())
    }

    @Test fun cancellationAfterTransferKeepsBytesAndRetryVerifiesWithoutNetwork() = MockWebServer().use { server ->
        val file = target()
        server.enqueue(MockResponse().setBody(Buffer().write(content)))
        var cancel = false
        assertThrows(CancellationException::class.java) {
            downloader(server).download(file, checkActive = { if (cancel) throw CancellationException() }, onProgress = { cancel = it == content.size.toLong() })
        }
        assertFalse(file.exists())
        assertArrayEquals(content, File(file.path + ".part").readBytes())
        downloader(server).download(file)
        assertArrayEquals(content, file.readBytes())
        assertEquals(1, server.requestCount)
    }
}
