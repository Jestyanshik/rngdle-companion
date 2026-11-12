package app.rngdle.companion.update

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.pm.PackageInfoCompat
import app.rngdle.companion.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Release(
    val title: String,
    val changelog: String,
    val versionCode: Long,
    val size: Long,
    val hash: String,
    val download: URI,
) {
    val fileName: String get() = "rngdle-companion-vc$versionCode.apk"
}

class GitHubReleaseRepository(private val context: Context) {
    private val repository = BuildConfig.RELEASE_REPOSITORY
    val configured = repository.isNotEmpty() && !BuildConfig.DEBUG

    @Suppress("DEPRECATION")
    private fun installedVersion(): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        check(configured)
        val url = URI("https://api.github.com/repos/$repository/releases/latest")
        val connection = connect(url, setOf("api.github.com"), "application/vnd.github+json")
        try {
            if (connection.responseCode == 404) return@withContext null
            val release = parseRelease(JSONObject(connection.body(::readMetadata)))
                ?: throw IOException("Invalid release metadata")
            release.takeIf { ReleasePolicy.isUpdate(installedVersion(), it.versionCode) }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun history(): List<Release> = withContext(Dispatchers.IO) {
        check(configured)
        val releases = mutableListOf<Release>()
        var page = 1
        var more: Boolean
        do {
            currentCoroutineContext().ensureActive()
            val url = URI("https://api.github.com/repos/$repository/releases?per_page=25&page=$page")
            val connection = connect(url, setOf("api.github.com"), "application/vnd.github+json")
            try {
                val entries = JSONArray(connection.body(::readMetadata))
                for (index in 0 until entries.length()) {
                    entries.optJSONObject(index)?.let(::parseRelease)?.let(releases::add)
                }
                more = connection.getHeaderField("Link")?.contains("rel=\"next\"") == true
            } finally {
                connection.disconnect()
            }
            page++
        } while (more)
        releases.sortedByDescending(Release::versionCode)
    }

    private fun parseRelease(release: JSONObject): Release? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val assets = release.optJSONArray("assets") ?: return null
        val candidates = (0 until assets.length()).mapNotNull { index ->
            val asset = assets.optJSONObject(index) ?: return@mapNotNull null
            val version = ReleasePolicy.versionCode(asset.optString("name")) ?: return@mapNotNull null
            version to asset
        }
        val (version, asset) = candidates.singleOrNull() ?: return null
        val size = asset.optLong("size")
        if (size !in 1..MAX_APK_SIZE) return null
        val digest = ReleasePolicy.sha256(asset.optString("digest")) ?: return null
        val tag = release.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val download = runCatching { URI(asset.optString("browser_download_url")) }.getOrNull() ?: return null
        if (download.scheme != "https" || download.host != "github.com" ||
            download.path != "/$repository/releases/download/$tag/${asset.optString("name")}" ||
            download.rawQuery != null || download.rawFragment != null
        ) return null
        return Release(
            title = release.optString("name").ifBlank { tag },
            changelog = release.optString("body"),
            versionCode = version,
            size = size,
            hash = digest,
            download = download,
        )
    }

    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update directory")
        val temporary = File.createTempFile("rngdle-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = connect(
                release.download,
                setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
                "application/octet-stream",
            )
            try {
                connection.body { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32_768)
                        var count = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > release.size) throw IOException("Download exceeded its declared size")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            onProgress(count.toFloat() / release.size)
                        }
                        if (count != release.size) throw IOException("Incomplete APK")
                    }
                }
            } finally {
                connection.disconnect()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (actual != release.hash) throw IOException("APK digest mismatch")
            verifyPackage(temporary, release.versionCode)
            directory.listFiles()?.filter { it != temporary }?.forEach { it.delete() }
            temporary
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    suspend fun saveArchive(release: Release, file: File, destination: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        try {
            check(ReleasePolicy.isRollback(installedVersion(), release.versionCode))
            val output = resolver.openOutputStream(destination, "wt") ?: throw IOException("Cannot open document")
            output.use { target ->
                file.inputStream().buffered().use { input ->
                    if (input.copyTo(target) != release.size) throw IOException("Incomplete APK copy")
                }
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, expectedVersion: Long) {
        val packages = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = packages.getPackageInfo(context.packageName, flags)
        val downloaded = packages.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Invalid APK")
        val installedSigners = installed.signingInfo?.apkContentsSigners?.toSet()
        val downloadedSigners = downloaded.signingInfo?.apkContentsSigners?.toSet()
        if (downloaded.packageName != context.packageName ||
            PackageInfoCompat.getLongVersionCode(downloaded) != expectedVersion ||
            installedSigners.isNullOrEmpty() || installedSigners != downloadedSigners
        ) throw IOException("APK package, version or signer mismatch")
    }

    private fun connect(start: URI, allowedHosts: Set<String>, accept: String): HttpsURLConnection {
        var url = start
        repeat(5) {
            if (url.scheme != "https" || url.host !in allowedHosts || url.rawUserInfo != null ||
                url.port !in listOf(-1, 443)
            ) throw IOException("Unexpected download host")
            val connection = url.toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "RNGdle-Companion")
            if (url.host == "api.github.com") connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
                url = url.resolve(connection.getHeaderField("Location") ?: throw IOException("Missing redirect"))
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many redirects")
    }

    private inline fun <T> HttpsURLConnection.body(read: (InputStream) -> T): T {
        if (responseCode != 200) throw IOException("Unexpected HTTP status")
        return inputStream.use(read)
    }

    private fun readMetadata(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return output.toByteArray().decodeToString()
            if (output.size() + size > 1_000_000) throw IOException("Release response exceeds limit")
            output.write(buffer, 0, size)
        }
    }

    companion object {
        private const val MAX_APK_SIZE = 200L * 1024 * 1024
    }
}
