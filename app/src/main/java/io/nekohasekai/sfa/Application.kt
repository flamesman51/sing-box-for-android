package io.nekohasekai.sfa

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.getSystemService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.sfa.bg.AppChangeReceiver
import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.compose.screen.tools.TaildropFiles
import io.nekohasekai.sfa.constant.Bugs
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.diagnostics.DiagnosticsInstalledVersion
import io.nekohasekai.sfa.diagnostics.DiagnosticsJournal
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection
import io.nekohasekai.sfa.diagnostics.DiagnosticsReportDiscovery
import io.nekohasekai.sfa.diagnostics.DiagnosticsWork
import io.nekohasekai.sfa.update.ServerUpdateNotifier
import io.nekohasekai.sfa.update.ServerUpdates
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.HookModuleUpdateNotifier
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.utils.PrivilegeSettingsClient
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale
import io.nekohasekai.sfa.Application as BoxApplication

class Application : Application() {
    val coreReady = CompletableDeferred<Unit>()
    internal val processStartedAtElapsedMillis = SystemClock.elapsedRealtime()

    internal val installedVersion = DiagnosticsInstalledVersion()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        AppLifecycleObserver.register(this)
        runCatching {
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    installedVersion.load({ getSharedPreferences("diagnostics-lifecycle", Context.MODE_PRIVATE) }, BuildConfig.VERSION_CODE)
                }
            }
        }
        runCatching { DiagnosticsJournal.record("process_start", mapOf("version_code" to BuildConfig.VERSION_CODE)) }

//        Seq.setContext(this)
        runCatching {
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
        }.onFailure {
            Log.d("Application", "set locale: ${it.message}")
        }

        val baseDir = filesDir
        baseDir.mkdirs()
        val workingDir = getExternalFilesDir(null)
        val tempDir = cacheDir
        tempDir.mkdirs()
        if (workingDir != null) {
            workingDir.mkdirs()
            runCatching { CrashReportManager.install(workingDir, baseDir) }
            runCatching { OOMReportManager.install(workingDir) }
            runCatching { PowerReportManager.install(workingDir) }
        }

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Settings.dataStore.initialize()
                runCatching { DiagnosticsJournal.preferencesReady() }
                runCatching { DiagnosticsWork.schedule(this@Application) }
                initialize(baseDir, workingDir, tempDir)
                coreReady.complete(Unit)
                runCatching { DiagnosticsJournal.record("core_ready") }
            } catch (error: Exception) {
                coreReady.completeExceptionally(error)
                runCatching { DiagnosticsJournal.record("core_setup_failed", DiagnosticsProjection.failure(error)) }
                runCatching { DiagnosticsWork.enqueue(this@Application) }
                Log.e("Application", "Core initialization failed")
                return@launch
            }
            ServerUpdates.initialize()
            ServerUpdates.reconcileInstalled()
            ServerUpdateNotifier.refresh(this@Application)
            Vendor.scheduleAutoUpdate()
            HookStatusClient.register(this@Application)
            PrivilegeSettingsClient.register(this@Application)
            UpdateProfileWork.reconfigureUpdater()
            HookModuleUpdateNotifier.sync(this@Application)
            TaildropFiles.cleanCache()
        }

        if (Vendor.isPerAppProxyAvailable()) {
            registerReceiver(
                AppChangeReceiver(),
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                },
            )
        }
    }

    private suspend fun initialize(baseDir: File, workingDir: File?, tempDir: File) {
        val actualWorkingDir = workingDir ?: error("App storage is unavailable")
        setupLibbox(baseDir, actualWorkingDir, tempDir)
    }

    fun reloadSetupOptions() {
        val baseDir = filesDir
        val workingDir = getExternalFilesDir(null) ?: return
        val tempDir = cacheDir
        Libbox.reloadSetupOptions(createSetupOptions(baseDir, workingDir, tempDir))
    }

    private suspend fun setupLibbox(baseDir: File, workingDir: File, tempDir: File) {
        DiagnosticsReportDiscovery.shared.afterPromotion {
            Libbox.setup(createSetupOptions(baseDir, workingDir, tempDir))
        }
    }

    private fun createSetupOptions(baseDir: File, workingDir: File, tempDir: File): SetupOptions = SetupOptions().also {
        it.basePath = baseDir.path
        it.workingPath = workingDir.path
        it.tempPath = tempDir.path
        it.fixAndroidStack = Bugs.fixAndroidStack
        it.logMaxLines = 3000
        it.debug = BuildConfig.DEBUG
        it.crashReportSource = "Application"
        it.appVersion = BuildConfig.VERSION_CODE.toString()
        it.appMarketingVersion = BuildConfig.VERSION_NAME
        it.oomKillerEnabled = Settings.oomKillerEnabled
        it.oomKillerDisabled = Settings.oomKillerDisabled
        it.oomMemoryLimit = Settings.oomMemoryLimitMB.toLong() * 1024L * 1024L
        it.powerReportEnabled = Settings.powerReportEnabled
        it.platformMetadata = JSONObject().apply {
            put("os", "Android " + Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
        }.toString()
    }

    companion object {
        lateinit var application: BoxApplication
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }
}
