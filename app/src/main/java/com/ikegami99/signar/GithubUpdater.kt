package com.ikegami99.signar

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(val tag: String, val url: String, val sha256: String, val bytes: Long, val notes: String)

class GithubUpdater(private val context: Context, private val status: (String) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val base = "https://api.github.com/repos/" + BuildConfig.GITHUB_REPO
    private var readyFile: File? = null
    private fun report(s: String) { ui.post { status(s) } }

    fun check(onFound: (UpdateInfo?) -> Unit) {
        report("GitHub Releasesを確認中…")
        Thread {
            try {
                val json = JSONObject(getJson(base + "/releases/latest"))
                val tag = json.getString("tag_name")
                if (!isNewer(tag.removePrefix("v"), BuildConfig.VERSION_NAME.removeSuffix("-debug"))) {
                    report("最新版です（" + BuildConfig.VERSION_NAME + "）")
                    ui.post { onFound(null) }
                    return@Thread
                }
                val assets = json.getJSONArray("assets")
                var info: UpdateInfo? = null
                for (i in 0 until assets.length()) {
                    val obj = assets.getJSONObject(i)
                    if (obj.getString("name") == "signar.apk") {
                        val hash = obj.optString("digest", "").removePrefix("sha256:")
                        require(hash.matches(Regex("[0-9a-fA-F]{64}"))) {
                            "Release APK の SHA-256 が見つかりません"
                        }
                        info = UpdateInfo(tag, obj.getString("browser_download_url"), hash,
                            obj.optLong("size", 0L), json.optString("body", ""))
                    }
                }
                require(info != null) { "Releaseに signar.apk がありません" }
                report("更新が見つかりました: " + tag)
                ui.post { onFound(info) }
            } catch (e: Exception) { report("更新確認に失敗: " + (e.message ?: e.javaClass.simpleName)); ui.post { onFound(null) } }
        }.start()
    }

    fun downloadAndInstall(info: UpdateInfo) {
        val prefix = "https://github.com/" + BuildConfig.GITHUB_REPO + "/releases/download/"
        if (!info.url.startsWith(prefix)) { report("不正なダウンロードURL"); return }
        Thread {
            try {
                val folder = File(context.cacheDir, "updates").also { it.mkdirs() }
                val tmp = File(folder, "signar.tmp")
                val apk = File(folder, "signar.apk")
                val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000
                    setRequestProperty("User-Agent", "SIGNAR-Android")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                try {
                    check(conn.responseCode == 200) { "ダウンロード HTTP " + conn.responseCode }
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                check(count < 200_000_000L) { "APKが大きすぎます" }
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                if (count % (1024 * 1024) < 32768) {
                                    report("ダウンロード: " + count / (1024 * 1024) + " MB")
                                }
                            }
                        }
                    }
                } finally { conn.disconnect() }
                if (info.bytes > 0L) check(count == info.bytes) { "APKサイズが一致しません" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(info.sha256, ignoreCase = true)) { "SHA-256不一致。更新を中止しました" }
                check(hasSameSignature(tmp)) { "APKの署名がインストール済みSIGNARと一致しません" }
                if (apk.exists()) apk.delete()
                check(tmp.renameTo(apk)) { "APKを保存できません" }
                readyFile = apk
                report("検証完了。Androidのインストール確認へ進みます")
                ui.post { launchInstaller(apk) }
            } catch (e: Exception) { report("更新を中止: " + (e.message ?: e.javaClass.simpleName)) }
        }.start()
    }

    // Prevent a compromised or misconfigured release from changing app identity.
    @Suppress("DEPRECATION")
    private fun hasSameSignature(apk: File): Boolean {
        val pm = context.packageManager
        val installed = if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val candidate = if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        else pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        if (candidate == null || candidate.packageName != context.packageName) return false
        if (candidate.longVersionCode <= installed.longVersionCode) return false
        val current = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val incoming = candidate.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        return current != null && current.isNotEmpty() && current == incoming
    }

    fun retryInstall() { readyFile?.takeIf { it.exists() }?.let { launchInstaller(it) } }
    private fun launchInstaller(apk: File) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                report("このアプリからのインストールを許可してください。許可後、もう一度「インストール」を押します")
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName)))
                return
            }
            val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) { report("インストーラーを起動できません: " + e.message) }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split('.').map { it.toIntOrNull() ?: return false }
        val b = local.split('.').map { it.toIntOrNull() ?: return false }
        for (i in 0 until maxOf(a.size, b.size)) {
            val ai = a.getOrElse(i) { 0 }; val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai > bi
        }
        return false
    }
    private fun getJson(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SIGNAR-Android")
        }
        return try {
            check(conn.responseCode == 200) { "GitHub API HTTP " + conn.responseCode }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}
