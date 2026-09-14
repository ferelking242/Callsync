package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import com.example.data.api.CallSyncApi
import com.example.data.api.LoginRequest
import com.example.data.api.RecordingResponse
import com.example.data.database.AppDatabase
import com.example.data.model.LogEntry
import com.example.data.model.Upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import android.util.Base64
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.TimeUnit

data class ScanSummary(
    val totalAudioFiles: Int,
    val newlyIndexed: Int,
    val alreadyIndexed: Int
)

/** An audio item discovered from a filesystem folder or an SAF document URI. */
private data class AudioSource(
    val path: String,
    val name: String,
    val size: Long,
    val modifiedAt: Long,
    val mimeType: String? = null
)

class CallSyncRepository(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    val uploadDao           = database.uploadDao()
    val logDao              = database.logDao()

    val allUploads: Flow<List<Upload>>    = uploadDao.getAllUploads()
    val allLogs:    Flow<List<LogEntry>>  = logDao.getAllLogs()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("callsync_prefs", Context.MODE_PRIVATE)

    init {
        if (getPhoneId().isEmpty()) {
            prefs.edit().putString("phone_id", UUID.randomUUID().toString().take(8)).apply()
        }
        // The server upload path is the reliable default. P2P remains
        // available as an optional sharing channel from the home screen.
        if (!prefs.getBoolean("server_mode_migrated", false)) {
            prefs.edit()
                .putBoolean("legacy_server_mode", true)
                .putBoolean("server_mode_migrated", true)
                .apply()
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun getServerUrl(): String {
        var url = prefs.getString("server_url", "https://vapid-pleasing-drawings--koyih59365.replit.app/")
                  ?: "https://vapid-pleasing-drawings--koyih59365.replit.app/"
        if (!url.endsWith("/")) url += "/"
        return url
    }
    fun setServerUrl(url: String) { prefs.edit().putString("server_url", url).apply(); resetApi() }

    fun getUsername(): String = prefs.getString("username", "admin") ?: "admin"
    fun setUsername(u: String) { prefs.edit().putString("username", u).apply() }

    fun getPassword(): String = prefs.getString("password", "admin123") ?: "admin123"
    fun setPassword(p: String) { prefs.edit().putString("password", p).apply() }

    fun getAuthToken(): String = prefs.getString("auth_token", "") ?: ""
    fun setAuthToken(t: String) { prefs.edit().putString("auth_token", t).apply() }

    fun getPhoneId(): String = prefs.getString("phone_id", "") ?: ""

    fun getP2pSecret(): String {
        val current = prefs.getString("p2p_secret", null)
        if (!current.isNullOrBlank()) return current
        val created = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("p2p_secret", created).apply()
        return created
    }

    fun getP2pPort(): Int = 43821

    /**
     * Pairing is intentionally durable.  The QR code is a capability, not a
     * short-lived login: once the receiver stores it, it can reconnect after
     * reboot, network changes, or an app update without pairing again.
     */
    fun isLegacyServerMode(): Boolean =
        prefs.getBoolean("legacy_server_mode", true)

    fun setLegacyServerMode(enabled: Boolean) {
        val wasEnabled = isLegacyServerMode()
        prefs.edit()
            .putBoolean("legacy_server_mode", enabled)
            .apply()
        if (enabled && !wasEnabled) {
            prefs.edit().putBoolean("server_index_queue_migrated", false).apply()
        }
    }

    fun getPairedPeerIds(): Set<String> =
        prefs.getStringSet("p2p_paired_peer_ids", emptySet())?.toSet() ?: emptySet()

    fun rememberP2pPeer(peerId: String) {
        if (peerId.isBlank()) return
        val peers = getPairedPeerIds().toMutableSet()
        if (peers.add(peerId)) {
            prefs.edit().putStringSet("p2p_paired_peer_ids", peers).apply()
        }
    }

    fun isP2pPeerPaired(peerId: String): Boolean =
        peerId.isNotBlank() && getPairedPeerIds().contains(peerId)

    fun getP2pPairingCode(): String {
        val hosts = getP2pHostCandidates()
        val host = hosts.firstOrNull() ?: "127.0.0.1"
        val payload = JSONObject()
            .put("version", 2)
            .put("mode", "p2p")
            .put("id", getPhoneId())
            .put("name", getDeviceName())
            .put("host", host)
            .put("port", getP2pPort())
            .put("secret", getP2pSecret())
            .put("persistent", true)
            .put("candidates", org.json.JSONArray(hosts))
            // The relay only forwards encrypted/authenticated P2P messages in
            // memory. It lets two phones connect even when neither one has a
            // publicly reachable address.
            .put("relay", getServerUrl().trimEnd('/'))
            .put("folder", if (isSafFolderSelected()) "SAF" else File(getMonitorFolderPath()).name)
            .toString()
        return "callsync://pair/" + Base64.encodeToString(
            payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun getP2pHostCandidates(): List<String> =
        try {
            NetworkInterface.getNetworkInterfaces()?.let { interfaces ->
                Collections.list(interfaces)
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { Collections.list(it.inetAddresses) }
                    .filter {
                        !it.isLoopbackAddress &&
                            !it.isLinkLocalAddress &&
                            it.hostAddress?.contains(':') == false
                    }
                    .mapNotNull { it.hostAddress }
                    .distinct()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    fun getDeviceName(): String {
        val mfr   = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(mfr, ignoreCase = true)) model.replaceFirstChar { it.uppercase() }
               else "${mfr.replaceFirstChar { it.uppercase() }} $model"
    }

    fun getAndroidVersion(): String = Build.VERSION.RELEASE

    fun getMonitorFolderPath(): String {
        return prefs.getString("monitor_folder", "") ?: ""
    }
    fun setMonitorFolderPath(path: String) {
        prefs.edit().putString("monitor_folder", path).apply()
    }
    fun isSafFolderSelected(): Boolean = isTreeUri(getMonitorFolderPath())

    fun isOnboardingCompleted(): Boolean = prefs.getBoolean("onboarding_completed", false)
    fun setOnboardingCompleted(b: Boolean) { prefs.edit().putBoolean("onboarding_completed", b).apply() }

    // ── Réseau ────────────────────────────────────────────────────────────────

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net  = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // ── Scan timestamp — index incrémentiel ────────────────────────────────────

    fun getLastScanTimestamp(): Long = prefs.getLong("last_scan_ts", 0L)
    private fun setLastScanTimestamp(ts: Long) { prefs.edit().putLong("last_scan_ts", ts).apply() }
    fun resetScanTimestamp() { prefs.edit().putLong("last_scan_ts", 0L).apply() }

    // ── Known-hashes cache (dedup) ────────────────────────────────────────────
    //
    // We use an in-memory HashSet instead of SharedPrefs.
    // Reason: after an Autoscale server restart the server DB is wiped, so a
    // persisted SharedPrefs set would claim files "already on server" forever →
    // they'd never be re-uploaded.  An in-memory set resets on each app launch,
    // so the recorder always does a fresh /known-hashes fetch on startup.
    //
    // /known-hashes returns:
    //   • SHA256s of files currently stored on the server (Recording table)
    //   • SHA256s of files already downloaded by Flutter clients (ClientDownload table)
    // → both categories mean "don't upload this file again".

    @Volatile private var knownHashesCache: HashSet<String> = HashSet()

    /** Returns true if sha256 is already known to the server (uploaded or downloaded). */
    fun isOnServer(sha256: String): Boolean = knownHashesCache.contains(sha256)

    /** Fetches /known-hashes and replaces the in-memory cache. */
    suspend fun refreshKnownHashesCache() = withContext(Dispatchers.IO) {
        try {
            if (getAuthToken().isEmpty()) {
                val (ok, _) = login()
                if (!ok) return@withContext
            }
            val resp = getApi().getKnownHashes("Bearer ${getAuthToken()}")
            if (resp.isSuccessful) {
                val list = resp.body()?.sha256List ?: emptyList()
                knownHashesCache = HashSet(list)
                addLog("Cache", "Known hashes: ${knownHashesCache.size} entrée(s) (serveur + clients)")
            }
        } catch (e: Exception) {
            addLog("Cache", "Refresh known-hashes échoué: ${e.message}", true)
        }
    }

    /** Backward-compat alias used by autoConnectIfNeeded and testConnection. */
    suspend fun refreshServerSha256Cache() = refreshKnownHashesCache()

    /** Add a hash to the in-memory cache after a successful upload. */
    private fun addToServerCache(sha256: String) {
        knownHashesCache.add(sha256)
    }

    // ── Retrofit / API ────────────────────────────────────────────────────────
    // OkHttp configuré pour 20 connexions parallèles (défaut = 5, trop peu pour 16 uploads)

    @Volatile private var cachedApi: CallSyncApi? = null
    @Volatile private var cachedUrl: String? = null

    private fun getApi(): CallSyncApi {
        val url = getServerUrl()
        synchronized(this) {
            if (cachedApi != null && cachedUrl == url) return cachedApi!!

            // Dispatcher : 20 requêtes simultanées max (vs défaut 5 par host)
            val dispatcher = Dispatcher().apply {
                maxRequests        = 20
                maxRequestsPerHost = 20
            }
            // Pool de connexions persistantes : 20 sockets, TTL 5 min
            val connectionPool = ConnectionPool(20, 5, TimeUnit.MINUTES)

            val client = OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)   // Long pour gros fichiers
                .writeTimeout(180, TimeUnit.SECONDS)  // Long pour gros fichiers en upload
                .retryOnConnectionFailure(true)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
            cachedApi = retrofit.create(CallSyncApi::class.java)
            cachedUrl = url
            return cachedApi!!
        }
    }

    fun resetApi() { synchronized(this) { cachedApi = null; cachedUrl = null } }

    // ── Auth ──────────────────────────────────────────────────────────────────

    suspend fun login(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().login(LoginRequest(getUsername(), getPassword()))
            if (response.isSuccessful) {
                val token = response.body()?.token ?: ""
                setAuthToken(token)
                addLog("Auth", "Connexion réussie")
                Pair(true, "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                addLog("Auth", "Connexion échouée: $err", true)
                Pair(false, "Login failed: $err")
            }
        } catch (e: Exception) {
            addLog("Auth", "Connexion exception: ${e.message}", true)
            Pair(false, e.message ?: "Unknown error")
        }
    }

    /** Connexion automatique + refresh du cache SHA256 (silencieux). */
    suspend fun autoConnectIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val (ok, _) = login()
            if (ok) refreshServerSha256Cache()
        } catch (e: Exception) {
            addLog("AutoConnect", "Auto-connexion échouée: ${e.message}", true)
        }
    }

    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().checkHealth()
            if (response.isSuccessful) {
                addLog("Auth", "Serveur joignable")
                Pair(true, "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Pair(false, "HTTP ${response.code()}: $err")
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }

    // ── Upload (vrai parallélisme via Semaphore, pas des batches séries) ───────
    //
    // AVANT  : chunked(concurrency).forEach { batch -> awaitAll() }
    //          → chaque batch attend son fichier le plus lent avant de démarrer le suivant
    // APRÈS  : Semaphore(concurrency) — dès qu'un slot se libère, le suivant démarre immédiatement
    //          → vrais N uploads simultanés en streaming

    suspend fun uploadPendingFiles(): Int = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext 0

        if (getAuthToken().isEmpty()) {
            val (ok, err) = login()
            if (!ok) {
                addLog("Uploader", "Upload impossible — auth échouée: $err", true)
                return@withContext 0
            }
        }

        val pending = uploadDao.getPendingUploads()
        if (pending.isEmpty()) return@withContext 0

        // Parallélisme dynamique selon la taille de la queue
        val concurrency = when {
            pending.size >= 100 -> 16
            pending.size >= 30  -> 12
            pending.size >= 10  -> 8
            else                -> 4
        }
        addLog("Uploader", "Upload de ${pending.size} fichier(s) — $concurrency slots parallèles")

        // Semaphore : vrai streaming — un nouveau slot démarre immédiatement quand un finit
        val semaphore = Semaphore(concurrency)
        val uploaded: Int = coroutineScope {
            pending
                .map { upload -> async { semaphore.withPermit { uploadSingle(upload) } } }
                .awaitAll()
                .count { it }
        }
        uploaded
    }

    private suspend fun uploadSingle(upload: Upload): Boolean {
        if (!sourceExists(upload.path)) {
            addLog("Uploader", "Fichier manquant, marqué FAILED: ${upload.name}", true)
            uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = "File not found"))
            return false
        }
        return try {
            // SHA256 déjà calculé lors du scan et stocké en DB — pas de recalcul inutile
            val sha256 = upload.sha256

            if (isOnServer(sha256)) {
                uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                addLog("Uploader", "Ignoré (déjà sur serveur): ${upload.name}")
                return true
            }

            val token        = "Bearer ${getAuthToken()}"
            val mediaType    = getMediaType(upload.name).toMediaTypeOrNull()
            val fileBody     = sourceRequestBody(upload, mediaType)
            val filePart     = MultipartBody.Part.createFormData("file", upload.name, fileBody)
            val phoneIdBody  = getPhoneId().toRequestBody("text/plain".toMediaTypeOrNull())
            val deviceBody   = getDeviceName().toRequestBody("text/plain".toMediaTypeOrNull())
            val versionBody  = getAndroidVersion().toRequestBody("text/plain".toMediaTypeOrNull())
            val tsBody       = sourceModifiedAt(upload.path).toString()
                .toRequestBody("text/plain".toMediaTypeOrNull())
            val sha256Body   = sha256.toRequestBody("text/plain".toMediaTypeOrNull())

            uploadDao.updateUpload(upload.copy(status = "UPLOADING"))
            val response = getApi().uploadFile(token, filePart, phoneIdBody, deviceBody, versionBody, tsBody, sha256Body)

            when {
                response.isSuccessful -> {
                    uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                    addToServerCache(sha256)
                    addLog("Uploader", "Envoyé: ${upload.name}")
                    true
                }
                response.code() == 409 -> {
                    uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                    addToServerCache(sha256)
                    addLog("Uploader", "Ignoré (doublon serveur): ${upload.name}")
                    true
                }
                response.code() == 401 -> {
                    setAuthToken("")
                    val (ok, _) = login()
                    uploadDao.updateUpload(upload.copy(status = if (ok) "PENDING" else "FAILED",
                        errorMessage = if (ok) null else "Auth failed"))
                    false
                }
                else -> {
                    val errBody = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                    uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = errBody))
                    addLog("Uploader", "Échec upload (${response.code()}): ${upload.name}", true)
                    false
                }
            }
        } catch (e: Exception) {
            uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = e.message))
            addLog("Uploader", "Exception upload: ${upload.name} — ${e.message}", true)
            false
        }
    }

    // ── Server records ────────────────────────────────────────────────────────

    suspend fun getServerRecords(): Pair<List<RecordingResponse>, String> = withContext(Dispatchers.IO) {
        try {
            if (getAuthToken().isEmpty()) login()
            val response = getApi().getRecords("Bearer ${getAuthToken()}")
            if (response.isSuccessful) Pair(response.body() ?: emptyList(), "")
            else Pair(emptyList(), response.errorBody()?.string() ?: "HTTP ${response.code()}")
        } catch (e: Exception) {
            Pair(emptyList<RecordingResponse>(), e.message ?: "Unknown error")
        }
    }

    // ── Index incrémentiel ────────────────────────────────────────────────────

    /**
     * Scan incrémentiel : 1er appel = scan complet, suivants = seulement les fichiers
     * modifiés depuis le dernier scan. Le FileObserver gère le temps réel.
     * L'index Room est la source de vérité — on ne le recharge jamais en entier.
     */
    suspend fun scanFolderIncremental(): Int = withContext(Dispatchers.IO) {
        val folderPath = getMonitorFolderPath()

        if (!isTreeUri(folderPath)) {
            addLog("Scanner", "Sélectionnez un dossier avec Parcourir (autorisation SAF requise)", true)
            return@withContext 0
        }

        // ── Purge orphan DB entries (file deleted externally while PENDING/FAILED) ──
        val orphans = uploadDao.getAllUploadsList()
            .filter { it.status in listOf("PENDING", "FAILED") && !sourceExists(it.path) }
        if (orphans.isNotEmpty()) {
            orphans.forEach { uploadDao.deleteUploadById(it.id) }
            addLog("Scanner", "${orphans.size} entrée(s) orpheline(s) nettoyée(s)")
        }

        val lastScanTs  = getLastScanTimestamp()
        val now         = System.currentTimeMillis()
        val isFirstScan = lastScanTs == 0L
        val cutoff      = if (isFirstScan) 0L else lastScanTs - 5_000L

        val filesToCheck = collectAudioSources(folderPath)
            .filter { it.modifiedAt >= cutoff }

        if (isFirstScan) {
            addLog("Scanner", "Premier scan: ${filesToCheck.size} fichier(s) audio")
        } else if (filesToCheck.isNotEmpty()) {
            addLog("Scanner", "Scan delta: ${filesToCheck.size} fichier(s) à vérifier")
        }

        var addedCount = 0
        for (source in filesToCheck) {
            if (uploadDao.getUploadByPath(source.path) != null) continue
            val sha256 = calculateSHA256(source)

            val existingBySha = uploadDao.getUploadBySha256(sha256)
            if (existingBySha != null && existingBySha.status == "COMPLETED") {
                uploadDao.insertUpload(
                    Upload(sha256 = sha256, path = source.path, name = source.name,
                        size = source.size, status = initialIndexedStatus(),
                        uploadedAt = if (isLegacyServerMode()) null else existingBySha.uploadedAt)
                )
                continue
            }

            val inserted = uploadDao.insertUpload(
                Upload(sha256 = sha256, path = source.path, name = source.name,
                    size = source.size, status = initialIndexedStatus(),
                    uploadedAt = initialIndexedAt())
            )
            if (inserted > 0) {
                addedCount++
                addLog(
                    "Scanner",
                    "Indexé ${if (isLegacyServerMode()) "pour envoi serveur" else "pour partage pair-à-pair"}: ${source.name}"
                )
            }
        }

        setLastScanTimestamp(now)
        if (addedCount > 0) addLog("Scanner", "Scan: $addedCount nouveau(x) fichier(s) en queue")
        addedCount
    }

    /** Scan complet (bouton manuel dans l'UI). */
    suspend fun scanFolderManually(): ScanSummary = withContext(Dispatchers.IO) {
        val folderPath = getMonitorFolderPath()
        addLog("Scanner", "Scan manuel: $folderPath")

        if (!isTreeUri(folderPath)) {
            addLog("Scanner", "Sélectionnez un dossier avec Parcourir (autorisation SAF requise)", true)
            return@withContext ScanSummary(0, 0, 0)
        }

        // ── Purge orphan DB entries ────────────────────────────────────────────
        val orphans = uploadDao.getAllUploadsList()
            .filter { it.status in listOf("PENDING", "FAILED") && !sourceExists(it.path) }
        if (orphans.isNotEmpty()) {
            orphans.forEach { uploadDao.deleteUploadById(it.id) }
            addLog("Scanner", "${orphans.size} entrée(s) orpheline(s) nettoyée(s)")
        }

        val allFiles = collectAudioSources(folderPath)
        addLog("Scanner", "${allFiles.size} fichier(s) audio trouvé(s)")

        var addedCount = 0
        var alreadyIndexedCount = 0
        for (source in allFiles) {
            val existingByPath = uploadDao.getUploadByPath(source.path)
            if (existingByPath != null) {
                // A previous P2P scan stored the file as COMPLETED locally.
                // Switching to server mode must put that same file back in
                // the upload queue instead of treating it as invisible.
                if (isLegacyServerMode() && existingByPath.status == "COMPLETED") {
                    uploadDao.updateUpload(
                        existingByPath.copy(
                            status = "PENDING",
                            uploadedAt = null,
                            errorMessage = null
                        )
                    )
                }
                alreadyIndexedCount++
                continue
            }
            val sha256 = calculateSHA256(source)

            val existingBySha = uploadDao.getUploadBySha256(sha256)
            if (existingBySha != null && existingBySha.status == "COMPLETED") {
                uploadDao.insertUpload(
                    Upload(sha256 = sha256, path = source.path, name = source.name,
                        size = source.size, status = initialIndexedStatus(),
                        uploadedAt = if (isLegacyServerMode()) null else existingBySha.uploadedAt)
                )
                alreadyIndexedCount++
                continue
            }

            val inserted = uploadDao.insertUpload(
                Upload(sha256 = sha256, path = source.path, name = source.name,
                    size = source.size, status = initialIndexedStatus(),
                    uploadedAt = initialIndexedAt())
            )
            if (inserted > 0) {
                addedCount++
                addLog(
                    "Scanner",
                    "Indexé ${if (isLegacyServerMode()) "pour envoi serveur" else "pour partage pair-à-pair"}: ${source.name}"
                )
            } else {
                alreadyIndexedCount++
            }
        }
        addLog("Scanner", "Scan manuel terminé — $addedCount nouveau(x)")
        ScanSummary(allFiles.size, addedCount, alreadyIndexedCount)
    }

    private fun initialIndexedStatus(): String =
        if (isLegacyServerMode()) "PENDING" else "COMPLETED"

    private fun initialIndexedAt(): Long? =
        if (isLegacyServerMode()) null else System.currentTimeMillis()

    suspend fun queueIndexedFilesForServer() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean("server_index_queue_migrated", false)) return@withContext
        val indexed = uploadDao.getAllUploadsList()
            .filter { it.status == "COMPLETED" && sourceExists(it.path) }
        indexed.forEach {
            uploadDao.updateUpload(
                it.copy(status = "PENDING", uploadedAt = null, errorMessage = null)
            )
        }
        if (indexed.isNotEmpty()) {
            addLog("Uploader", "${indexed.size} fichier(s) remis en file pour le serveur")
        }
        prefs.edit().putBoolean("server_index_queue_migrated", true).apply()
    }

    /**
     * Scan the selected folder through the Storage Access Framework.
     *
     * The selected tree URI is the source of truth. Child document URIs are
     * stored in Room and later opened with ContentResolver, so this code never
     * needs to reconstruct /storage/emulated/0 paths.
     */
    private fun collectAudioSources(folderPath: String): List<AudioSource> {
        if (!isTreeUri(folderPath)) return emptyList()
        return collectAudioSourcesFromTree(Uri.parse(folderPath))
    }

    private fun collectAudioSourcesFromTree(treeUri: Uri): List<AudioSource> {
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (error: Exception) {
            Log.w("CallSync/Scanner", "URI SAF invalide: ${error.message}")
            return emptyList()
        }

        val result = mutableListOf<AudioSource>()
        walkSafDocuments(
            treeUri = treeUri,
            documentId = rootDocumentId,
            result = result,
            visitedDocumentIds = mutableSetOf()
        )
        return result
    }

    private fun walkSafDocuments(
        treeUri: Uri,
        documentId: String,
        result: MutableList<AudioSource>,
        visitedDocumentIds: MutableSet<String>
    ) {
        if (!visitedDocumentIds.add(documentId)) return

        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        } catch (error: Exception) {
            Log.w("CallSync/Scanner", "Impossible d'ouvrir le dossier SAF: ${error.message}")
            return
        }

        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) return@use

                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: continue
                    val mimeType = cursor.getString(mimeIndex)

                    if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                        walkSafDocuments(treeUri, childId, result, visitedDocumentIds)
                        continue
                    }
                    if (!isAudioExtension(name) && mimeType?.startsWith("audio/") != true) continue

                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        -1L
                    }
                    val modifiedAt = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        cursor.getLong(modifiedIndex)
                    } else {
                        0L
                    }
                    result += AudioSource(
                        path = childUri.toString(),
                        name = name,
                        size = size,
                        modifiedAt = modifiedAt,
                        mimeType = mimeType
                    )
                }
            }
        } catch (error: Exception) {
            Log.w("CallSync/Scanner", "Lecture SAF impossible: ${error.message}")
        }
    }

    fun isAudioFile(file: File): Boolean =
        isAudioExtension(file.name)

    private fun isAudioExtension(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf(
            "m4a", "mp3", "wav", "amr", "3gp", "ogg", "aac",
            "opus", "flac", "webm", "mp4"
        )

    private fun getMediaType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "m4a" -> "audio/mp4";  "wav" -> "audio/wav";  "ogg" -> "audio/ogg"
        "amr" -> "audio/amr";  "3gp" -> "video/3gpp"; "aac" -> "audio/aac"
        "opus" -> "audio/opus"; "flac" -> "audio/flac"; "webm" -> "audio/webm"
        "mp4" -> "video/mp4"
        else  -> "audio/mpeg"
    }

    fun calculateSHA256(file: File): String {
        return calculateSHA256(
            AudioSource(
                path = file.absolutePath,
                name = file.name,
                size = file.length(),
                modifiedAt = file.lastModified()
            )
        )
    }

    private fun calculateSHA256(source: AudioSource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openSource(source.path).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isContentUri(path: String): Boolean =
        path.startsWith("content://", ignoreCase = true)

    private fun isTreeUri(path: String): Boolean {
        if (!isContentUri(path)) return false
        return try {
            DocumentsContract.isTreeUri(Uri.parse(path))
        } catch (_: Exception) {
            false
        }
    }

    private fun sourceExists(path: String): Boolean {
        return if (isContentUri(path)) {
            try {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")
                    ?.use { it.length < 0L || it.length > 0L } == true
            } catch (_: Exception) {
                false
            }
        } else {
            File(path).isFile && File(path).canRead()
        }
    }

    private fun sourceModifiedAt(path: String): Long {
        if (!isContentUri(path)) return File(path).lastModified()
        return try {
            context.contentResolver.query(
                Uri.parse(path),
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun openSource(path: String): InputStream {
        if (isContentUri(path)) {
            return context.contentResolver.openInputStream(Uri.parse(path))
                ?: throw IllegalStateException("Source SAF indisponible")
        }
        return FileInputStream(File(path))
    }

    private fun sourceRequestBody(upload: Upload, mediaType: MediaType?): RequestBody =
        object : RequestBody() {
            override fun contentType(): MediaType? = mediaType
            override fun contentLength(): Long = upload.size

            override fun writeTo(sink: BufferedSink) {
                openSource(upload.path).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (read > 0) sink.write(buffer, 0, read)
                    }
                }
            }
        }

    // ── Logs ──────────────────────────────────────────────────────────────────

    suspend fun addLog(tag: String, message: String, isError: Boolean = false) =
        withContext(Dispatchers.IO) {
            Log.d("CallSync/$tag", message)
            logDao.insertLog(LogEntry(tag = tag, message = message, isError = isError,
                timestamp = System.currentTimeMillis()))
        }

    suspend fun clearLogs()    = withContext(Dispatchers.IO) { logDao.clearAllLogs() }
    suspend fun clearUploads() = withContext(Dispatchers.IO) { uploadDao.clearAllUploads() }

    suspend fun resetStuckUploads() = withContext(Dispatchers.IO) {
        val stuck = uploadDao.getUploadingUploads()
        stuck.forEach { uploadDao.updateUpload(it.copy(status = "PENDING", errorMessage = null)) }
        if (stuck.isNotEmpty()) addLog("Uploader", "Reset ${stuck.size} upload(s) bloqué(s) → PENDING")
    }

    suspend fun retryFailedUploads() = withContext(Dispatchers.IO) {
        val failed = uploadDao.getFailedUploads()
        failed.forEach {
            uploadDao.updateUpload(it.copy(status = "PENDING", errorMessage = null,
                retryCount = it.retryCount + 1))
        }
        if (failed.isNotEmpty()) addLog("Uploader", "Retry ${failed.size} upload(s) en échec")
    }

    // ── Suppression locale totale ─────────────────────────────────────────────

    suspend fun deleteAllLocalFilesAndIndex(): Int = withContext(Dispatchers.IO) {
        val uploads = uploadDao.getAllUploadsList()
        var deleted = 0
        for (upload in uploads) {
            try {
                if (isContentUri(upload.path)) {
                    if (context.contentResolver.delete(Uri.parse(upload.path), null, null) > 0) {
                        deleted++
                    }
                } else {
                    val file = File(upload.path)
                    if (file.exists()) { file.delete(); deleted++ }
                }
            } catch (e: Exception) {
                addLog("DeleteAll", "Erreur suppression ${upload.name}: ${e.message}", true)
            }
        }
        uploadDao.clearAllUploads()
        resetScanTimestamp()
        // Clear in-memory cache so next scan does a fresh /known-hashes fetch
        knownHashesCache = HashSet()
        addLog("DeleteAll", "$deleted fichier(s) supprimé(s) + index vidé")
        deleted
    }

    // ── Delete-at-source polling ───────────────────────────────────────────────

    suspend fun pollAndExecuteDeleteCommands() = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext
        try {
            val phoneId = getPhoneId()
            if (phoneId.isEmpty()) return@withContext
            if (getAuthToken().isEmpty()) login()
            val token = "Bearer ${getAuthToken()}"

            // GET /delete-commands/{deviceId} returns sha256_list and marks all as done atomically
            val response = getApi().getPendingCommands(token, phoneId)
            if (!response.isSuccessful) return@withContext

            val body = response.body() ?: return@withContext
            val sha256List = body.sha256List
            if (sha256List.isEmpty()) return@withContext

            addLog("DeleteCmd", "${sha256List.size} ordre(s) de suppression reçu(s)")

            for (sha256 in sha256List) {
                try {
                    val upload = uploadDao.getUploadBySha256(sha256)
                    if (upload != null) {
                        val deleted = if (isContentUri(upload.path)) {
                            context.contentResolver.delete(Uri.parse(upload.path), null, null) > 0
                        } else {
                            val file = File(upload.path)
                            file.exists() && file.delete()
                        }
                        if (deleted) addLog("DeleteCmd", "Supprimé: ${upload.name}")
                        uploadDao.deleteUploadById(upload.id)
                    }
                } catch (e: Exception) {
                    addLog("DeleteCmd", "Échec SHA $sha256: ${e.message}", true)
                }
            }
        } catch (e: Exception) {
            addLog("DeleteCmd", "Polling échoué: ${e.message}", true)
        }
    }
}
