package com.utility.dlmsreader.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One published GitHub release that carries an installable APK. */
data class MeterLinkRelease(
    val tagName: String,
    val version: String,
    val apkUrl: String,
    val apkName: String
)

/**
 * GitHub Releases based self-update for sideloaded builds.
 *
 * The workflow publishes every build as tag `v1.0.<run>` with
 * `versionCode = 100 + run`, so the installed build number is recovered from
 * the version code instead of the (static) version name.
 */
object MeterLinkUpdater {

    /** Public releases repository this app ships from (`origin`). */
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/professordoom58-art/MeterLink-Encore/releases/latest"

    /** Workflow convention: versionCode = 100 + build number. */
    private const val VERSION_CODE_BASE = 100

    private const val USER_AGENT = "MeterLink-Android"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Installed build label, e.g. `v1.0.58`. */
    fun installedVersion(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // Builds from 1.0.58 on carry the release line as versionName; older
        // installs used a static name, so their build number comes from the code.
        val named = info.versionName?.trim()?.removePrefix("v").orEmpty()
        if (named.isNotBlank() && versionParts(named) != null) return "v$named"
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
        val build = (code - VERSION_CODE_BASE).coerceAtLeast(0)
        return "v1.0.$build"
    }

    /**
     * Queries the latest release and returns it only when it is newer than the
     * installed build and contains an `.apk` asset. Returns null when the app is
     * already current or the release has nothing installable.
     */
    suspend fun checkForUpdate(context: Context): MeterLinkRelease? =
        withContext(Dispatchers.IO) {
            val release = fetchLatestRelease()
            if (!isNewer(release.version, installedVersion(context))) return@withContext null
            release
        }

    private fun fetchLatestRelease(): MeterLinkRelease {
        val connection = openConnection(LATEST_RELEASE_URL, "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                error("No MeterLink release has been published yet.")
            }
            if (code != HttpURLConnection.HTTP_OK) error("GitHub returned HTTP $code.")
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            if (tag.isBlank()) error("The latest release has no version tag.")

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkName = ""
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                        apkUrl = url
                        apkName = name
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) error("The latest release ($tag) has no APK file.")

            return MeterLinkRelease(
                tagName = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                apkUrl = apkUrl,
                apkName = apkName
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads the release APK into the app cache and returns the file. */
    suspend fun downloadApk(context: Context, release: MeterLinkRelease): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeName = release.apkName
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .ifBlank { "MeterLink-${release.version}.apk" }
            val target = File(directory, safeName)

            val connection = openConnection(release.apkUrl, "application/octet-stream")
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    error("Download failed: GitHub returned HTTP $code.")
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            if (!target.isFile || target.length() == 0L) {
                target.delete()
                error("The downloaded APK was empty.")
            }
            target
        }

    /** True when the app may launch the system package installer. */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user allows installs from this app. */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /** System package-installer intent for a downloaded APK. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
        }

    /** Compares dotted versions; a leading `v` and missing segments are ignored. */
    internal fun isNewer(latest: String, installed: String): Boolean {
        val latestParts = versionParts(latest) ?: return false
        val installedParts = versionParts(installed) ?: return false
        val size = maxOf(latestParts.size, installedParts.size)
        for (index in 0 until size) {
            val left = latestParts.getOrElse(index) { 0 }
            val right = installedParts.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun versionParts(version: String): List<Int>? {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isBlank()) return null
        val parts = cleaned.split('.', '-', '_')
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers.ifEmpty { null }
    }
}
