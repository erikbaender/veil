package app.veil.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.veil.core.settings.SettingsStore
import app.veil.core.update.Release
import app.veil.core.update.Releases
import app.veil.core.update.UpdateStatus
import app.veil.core.update.Updates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates Veil from its GitHub releases: asks for the latest release (at most once a day, or when
 * the user asks), downloads its APK, checks it is a newer Veil signed with the same key, and hands
 * it to the system installer, which asks the user to confirm.
 *
 * Only GitHub is contacted, without any account, token or device details.
 */
class AppUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) : Updates {
    private val supported = !BuildConfig.DEBUG
    private val _status = MutableStateFlow<UpdateStatus>(if (supported) UpdateStatus.Idle else UpdateStatus.Unsupported)
    override val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** The installer's confirmation screen, waiting for the activity to show it. */
    private val _confirmation = MutableStateFlow<Intent?>(null)
    val confirmation: StateFlow<Intent?> = _confirmation.asStateFlow()

    private var job: Job? = null
    private val dir = File(context.cacheDir, "update")
    private val statusAction = "${context.packageName}.UPDATE_STATUS"

    init {
        // Whatever an earlier run left behind (a finished update, an abandoned download) goes.
        scope.launch(Dispatchers.IO) {
            context.packageManager.packageInstaller.mySessions.forEach { abandon(it.sessionId) }
            dir.deleteRecursively()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onInstallerStatus(intent)
        }
        val filter = IntentFilter(statusAction)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, null)
        }
    }

    /** The automatic check: runs when the app comes to the front, if it is on and due. */
    fun checkIfDue() {
        if (!supported || job?.isActive == true || _status.value !is UpdateStatus.Idle) return
        scope.launch {
            val s = settings.load()
            val since = System.currentTimeMillis() - s.lastUpdateCheck
            if (s.checkForUpdates && (since !in 0 until Updates.CHECK_INTERVAL_MS)) check()
        }
    }

    override fun check() {
        if (!supported || job?.isActive == true) return
        job = scope.launch {
            _status.value = UpdateStatus.Checking
            _status.value = try {
                val release = withContext(Dispatchers.IO) { latest() }
                checked()
                if (release == null) UpdateStatus.UpToDate else UpdateStatus.Available(release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "update check failed: $e")
                // GitHub answered (no release, rate limit, ...): the next automatic try is tomorrow.
                // No connection: it is tried again the next time the app comes to the front.
                if (e !is IOException) checked()
                UpdateStatus.Failed(message(e))
            }
        }
    }

    private suspend fun checked() = settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }

    override fun install() {
        val release = when (val s = _status.value) {
            is UpdateStatus.Available -> s.release
            is UpdateStatus.Failed -> s.release
            else -> null
        } ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { download(release) }
                _status.value = UpdateStatus.Installing(release)
                withContext(Dispatchers.IO) { verify(apk, release.versionCode) }
                withContext(Dispatchers.IO) { commit(apk) }
            } catch (e: CancellationException) {
                _status.value = UpdateStatus.Available(release)
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "update failed: $e")
                _status.value = UpdateStatus.Failed(message(e), release)
            }
        }
    }

    override fun cancel() {
        if (_status.value is UpdateStatus.Downloading) job?.cancel()
    }

    /** The activity showed (or gave up on) the installer's confirmation screen. */
    fun confirmationShown() {
        _confirmation.value = null
    }

    /** Debug builds: installs an APK from the app's cache directory, to test the install path. */
    fun installLocal(file: File, versionCode: Int) {
        if (job?.isActive == true) return
        val release = Release(file.name, versionCode, "", file.length(), null, "Local test build.", "")
        job = scope.launch {
            try {
                _status.value = UpdateStatus.Installing(release)
                withContext(Dispatchers.IO) {
                    val apk = File(dir.apply { mkdirs() }, "veil.apk")
                    file.copyTo(apk, overwrite = true)
                    verify(apk, versionCode)
                    commit(apk)
                }
            } catch (e: Exception) {
                Log.i(TAG, "local update failed: $e")
                _status.value = UpdateStatus.Failed(message(e), release)
            }
        }
    }

    private fun latest(): Release? {
        val connection = open(Releases.LATEST_URL).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> throw UpdateException("No release found on GitHub.")
                403, 429 -> throw UpdateException("GitHub is limiting requests right now. Try again later.")
                else -> throw UpdateException("GitHub answered with an error ($code).")
            }
            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            return Releases.newer(body, BuildConfig.VERSION_CODE)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(release: Release): File {
        _status.value = UpdateStatus.Downloading(release, null)
        dir.deleteRecursively()
        dir.mkdirs()
        val apk = File(dir, "veil.apk")
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw UpdateException("The download failed (${connection.responseCode}).")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            if ((total ?: 0) > MAX_APK_BYTES) throw UpdateException("The download is unexpectedly large.")
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var shown = -1
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (read > MAX_APK_BYTES) throw UpdateException("The download is unexpectedly large.")
                        if (total != null) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                _status.value = UpdateStatus.Downloading(release, (read.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && read != release.apkSize) throw UpdateException("The download is incomplete.")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && hash != release.sha256) {
                throw UpdateException("The download doesn't match the release's checksum.")
            }
            return apk
        } catch (e: Exception) {
            apk.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Refuses anything but a newer Veil signed with the key of the installed one. */
    private fun verify(apk: File, versionCode: Int) {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateException("The download isn't a valid app.")
        if (info.packageName != context.packageName) throw UpdateException("The download isn't Veil.")
        if (info.longVersionCode != versionCode.toLong() || info.longVersionCode <= BuildConfig.VERSION_CODE) {
            throw UpdateException("The download isn't the expected version.")
        }
        val signing = info.signingInfo ?: throw UpdateException("The download isn't signed.")
        val certificates = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        val trusted = !certificates.isNullOrEmpty() && certificates.all {
            pm.hasSigningCertificate(context.packageName, it.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)
        }
        if (!trusted) throw UpdateException("The download isn't signed with Veil's key.")
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("veil.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(statusAction).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            abandon(id)
            throw e
        }
    }

    private fun onInstallerStatus(intent: Intent) {
        val release = (_status.value as? UpdateStatus.Installing)?.release
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                _confirmation.value = confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // On success the system replaces this process; there's nothing left to do.
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "update installed")
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                _status.value = release?.let { UpdateStatus.Available(it) } ?: UpdateStatus.Idle
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.i(TAG, "install failed: $status $detail")
                _status.value = UpdateStatus.Failed(installFailure(status), release)
            }
        }
    }

    private fun installFailure(status: Int) = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installing was blocked on this device."
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "The update conflicts with the installed app."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The update isn't compatible with this device."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
        else -> "The update couldn't be installed."
    }

    private fun abandon(id: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true // release downloads redirect to GitHub's file host (HTTPS only)
            useCaches = false
            setRequestProperty("User-Agent", "Veil") // GitHub requires one; no version or device
        }

    private fun message(e: Exception): String = when (e) {
        is UpdateException -> e.message.orEmpty()
        is IOException -> "Couldn't reach GitHub. Check your connection."
        else -> "Something went wrong."
    }

    /** A failure GitHub or the download itself reported; not a connection problem. */
    private class UpdateException(message: String) : Exception(message)

    companion object {
        private const val TAG = "Veil"
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
}
