package com.example.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// ── State ─────────────────────────────────────────────────────────────────────

sealed class UpdateState {
    object Idle       : UpdateState()
    object Checking   : UpdateState()
    data class Available(val version: String, val downloadUrl: String) : UpdateState()
    data class Downloading(val progress: Int)  : UpdateState()   // 0–100
    data class Error(val message: String)      : UpdateState()
    object UpToDate   : UpdateState()
    object Installing : UpdateState()
}

// ── Manager ───────────────────────────────────────────────────────────────────

class UpdateManager(private val context: Context) {

    companion object {
        private const val GITHUB_REPO       = "ferelking242/Callsync"
        private const val RELEASES_API      = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        private const val CHANNEL_ID        = "CallSyncUpdateChannel"
        private const val NOTIF_ID_CHECK    = 2001
        private const val NOTIF_ID_DOWNLOAD = 2002
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    init { createNotificationChannel() }

    // ── Check ─────────────────────────────────────────────────────────────────

    suspend fun checkForUpdate(): UpdateState = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout    = 10_000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "CallSync/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != 200) {
                val err = UpdateState.Error("HTTP ${conn.responseCode}")
                _state.value = err
                return@withContext err
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json       = JSONObject(body)
            val tagName    = json.getString("tag_name") // e.g. "v2.2" or "2.2"
            val remoteVer  = tagName.trimStart('v')

            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (apkUrl == null) {
                val err = UpdateState.Error("Aucun APK dans la release $remoteVer")
                _state.value = err
                return@withContext err
            }

            val currentVer = BuildConfig.VERSION_NAME
            val newState   = if (isNewer(remoteVer, currentVer)) {
                UpdateState.Available(remoteVer, apkUrl)
            } else {
                UpdateState.UpToDate
            }
            _state.value = newState
            newState
        } catch (e: Exception) {
            val err = UpdateState.Error(e.message ?: "Erreur inconnue")
            _state.value = err
            err
        }
    }

    // ── Download + Install ────────────────────────────────────────────────────

    suspend fun downloadAndInstall(downloadUrl: String) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(0)

        val updateDir = File(context.cacheDir, "updates").also { it.mkdirs() }
        val apkFile   = File(updateDir, "callsync-update.apk")

        try {
            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout    = 60_000
                setRequestProperty("User-Agent", "CallSync/${BuildConfig.VERSION_NAME}")
            }

            // Follow redirects (GitHub redirects to CDN)
            var redirectConn = conn
            var responseCode = redirectConn.responseCode
            var redirectUrl  = downloadUrl
            var redirectCount = 0
            while ((responseCode == 301 || responseCode == 302 || responseCode == 307 || responseCode == 308)
                   && redirectCount < 5) {
                redirectUrl  = redirectConn.getHeaderField("Location")
                redirectConn.disconnect()
                redirectConn = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout    = 60_000
                }
                responseCode = redirectConn.responseCode
                redirectCount++
            }

            if (responseCode != 200) {
                _state.value = UpdateState.Error("Téléchargement échoué: HTTP $responseCode")
                return@withContext
            }

            val totalBytes = redirectConn.contentLength.toLong()
            var downloaded = 0L

            val input: InputStream = redirectConn.inputStream
            val output = FileOutputStream(apkFile)
            val buffer = ByteArray(8192)
            var read: Int

            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                downloaded += read
                if (totalBytes > 0) {
                    val pct = ((downloaded * 100) / totalBytes).toInt()
                    _state.value = UpdateState.Downloading(pct)
                    showDownloadNotification(pct)
                }
            }
            output.flush()
            output.close()
            input.close()
            redirectConn.disconnect()

            _state.value = UpdateState.Installing
            installApk(apkFile)

        } catch (e: Exception) {
            apkFile.delete()
            _state.value = UpdateState.Error("Téléchargement échoué: ${e.message}")
        }
    }

    // ── Install via PackageInstaller ──────────────────────────────────────────

    private fun installApk(apkFile: File) {
        try {
            val pm         = context.packageManager
            val installer  = pm.packageInstaller
            val params     = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(context.packageName)

            val sessionId = installer.createSession(params)
            val session   = installer.openSession(sessionId)

            apkFile.inputStream().use { apkStream ->
                session.openWrite("callsync_update", 0, apkFile.length()).use { out ->
                    apkStream.copyTo(out)
                    session.fsync(out)
                }
            }

            val intentSender = PendingIntent.getBroadcast(
                context, sessionId,
                Intent("com.example.INSTALL_STATUS").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).intentSender

            session.commit(intentSender)
            session.close()
        } catch (e: Exception) {
            _state.value = UpdateState.Error("Installation échouée: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Compares two version strings like "2.1", "2.1.3", "10.0".
     * Returns true if [remote] is strictly newer than [current].
     */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Mises à jour CallSync", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications de mise à jour de l'application" }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun showDownloadNotification(progress: Int) {
        try {
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Mise à jour CallSync")
                .setContentText("Téléchargement… $progress%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_DOWNLOAD, notif)
        } catch (_: Exception) {}
    }

    fun showUpdateAvailableNotification(version: String) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("show_update", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Mise à jour disponible")
                .setContentText("CallSync $version est disponible — appuyez pour mettre à jour")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_CHECK, notif)
        } catch (_: Exception) {}
    }

    fun dismissDownloadNotification() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIF_ID_DOWNLOAD)
    }
}
