package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.repository.CallSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import android.util.Base64
import kotlinx.coroutines.Job
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Small authenticated P2P file host. It never uploads files to a central
 * server: the downloader connects directly to this phone and asks for a
 * manifest or a byte range.
 */
class P2pShareService : Service() {
    private lateinit var repository: CallSyncRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = true
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    companion object {
        private const val CHANNEL = "CallSyncP2P"
        private const val NOTIFICATION_ID = 1101
        const val PORT = 43821
    }

    override fun onCreate() {
        super.onCreate()
        repository = CallSyncRepository(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Partage pair-à-pair actif"))
        acceptJob = scope.launch {
            try {
                server = ServerSocket(PORT, 32, InetAddress.getByName("0.0.0.0"))
                while (running) {
                    val client = server?.accept() ?: break
                    launch { handle(client) }
                }
            } catch (error: Exception) {
                if (running) {
                    repository.addLog("P2P", "Hôte arrêté: ${error.message}", true)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY is important here: the host is a durable pairing endpoint,
        // not a one-shot upload task.
        startForeground(NOTIFICATION_ID, notification("Partage pair-à-pair actif"))
        return START_STICKY
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            // A paired client may keep the control connection open.  Individual
            // transfers are bounded by the client and can be resumed on a new
            // connection after a mobile network switch.
            client.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()
            val hello = JSONObject(reader.readLine() ?: return)
            val peerId = hello.optString("peerId")
            if (hello.optString("type") != "hello" ||
                peerId.isBlank() ||
                !isAuthorized(hello.optString("nonce"), hello.optString("auth"))) {
                send(output, JSONObject().put("type", "error").put("error", "Pair refusé"))
                return
            }
            // The first successful handshake permanently links this peer on the
            // source device.  There is deliberately no TTL or expiry check.
            repository.rememberP2pPeer(peerId)
            send(output, JSONObject()
                .put("type", "ready")
                .put("peerId", repository.getPhoneId())
                .put("persistent", true))

            while (running && !client.isClosed) {
                val request = reader.readLine() ?: break
                when (JSONObject(request).optString("type")) {
                    "ping" -> send(output, JSONObject().put("type", "pong"))
                    "manifest" -> sendManifest(output)
                    "download" -> {
                        val payload = JSONObject(request)
                        sendFile(output, payload.optString("path"), payload.optLong("offset", 0))
                    }
                    else -> send(output, JSONObject()
                        .put("type", "error")
                        .put("error", "Commande P2P inconnue"))
                }
            }
        }
    }

    private fun sendManifest(output: OutputStream) {
        val root = File(repository.getMonitorFolderPath())
        val files = JSONArray()
        if (root.exists() && root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.length() > 0 }.forEach { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                files.put(JSONObject()
                    .put("path", relative)
                    .put("name", file.name)
                    .put("size", file.length())
                    .put("sha256", sha256(file))
                    .put("modifiedAt", file.lastModified()))
            }
        }
        send(output, JSONObject().put("type", "manifest").put("files", files))
    }

    private fun sendFile(output: OutputStream, relativePath: String, offset: Long) {
        val root = File(repository.getMonitorFolderPath()).canonicalFile
        val target = File(root, relativePath).canonicalFile
        if (!target.toPath().startsWith(root.toPath()) ||
            !target.isFile || offset < 0 || offset > target.length()) {
            send(output, JSONObject().put("type", "error").put("error", "Fichier refusé"))
            return
        }
        send(output, JSONObject().put("type", "file")
            .put("size", target.length() - offset)
            .put("sha256", sha256(target)))
        target.inputStream().use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val count = input.skip(offset - skipped)
                if (count <= 0) break
                skipped += count
            }
            if (skipped != offset) {
                send(output, JSONObject().put("type", "error").put("error", "Offset invalide"))
                return
            }
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                if (read > 0) output.write(buffer, 0, read)
            }
            output.flush()
        }
    }

    private fun isAuthorized(nonce: String, auth: String): Boolean {
        if (nonce.isBlank() || auth.isBlank()) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(repository.getP2pSecret().toByteArray(), "HmacSHA256"))
        val expected = Base64.encodeToString(
            mac.doFinal(nonce.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return MessageDigest.isEqual(expected.toByteArray(), auth.toByteArray())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun send(output: OutputStream, payload: JSONObject) {
        output.write("${payload}\n".toByteArray())
        output.flush()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Partage pair-à-pair",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("CallSync")
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        acceptJob?.cancel()
        scope.cancel()
        scheduleRestart()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleRestart() {
        try {
            val alarm = getSystemService(android.content.Context.ALARM_SERVICE)
                as android.app.AlarmManager
            val intent = Intent(this, ServiceRestartReceiver::class.java)
                .setAction("com.example.RESTART_SERVICE")
            val pending = PendingIntent.getBroadcast(
                this, 9101, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 1_000L,
                pending
            )
        } catch (_: Exception) {
            // Android may reject alarms while the app is force-stopped.  No
            // application can restart itself from that state.
        }
    }
}