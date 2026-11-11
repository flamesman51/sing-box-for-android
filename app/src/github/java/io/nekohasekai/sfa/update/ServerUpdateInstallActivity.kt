package io.nekohasekai.sfa.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.Theme
import io.nekohasekai.sfa.diagnostics.DiagnosticsJournal
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection
import io.nekohasekai.sfa.vendor.InstallCancellation
import io.nekohasekai.sfa.vendor.SystemPackageInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class ServerUpdateInstallModel : ViewModel() {
    var initialized = false
    var ready: ReadyServerUpdate? = null

    @Volatile
    var sessionId = -1
    var resumeSession = false
    var preparing by mutableStateOf(false)
    var committed by mutableStateOf(false)
    var permissionExplanation by mutableStateOf(false)
    var waitingForPermission = false
    var confirmation: Intent? = null
    var confirmationLaunched = false
    var error by mutableStateOf<Int?>(null)
    var resultStatus: Int? = null
    private var cancellation = InstallCancellation()
    private var preparationJob: Job? = null

    fun permissionResult(granted: Boolean) {
        waitingForPermission = false
        permissionExplanation = !granted
    }

    fun recordResult(
        sessionId: Int,
        ready: ReadyServerUpdate?,
        status: Int,
        confirmation: Intent?,
    ): Boolean {
        if (sessionId < 0 || this.sessionId != sessionId || this.ready != ready) return false
        resultStatus = status
        this.confirmation = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) confirmation else null
        committed = true
        resumeSession = false
        return true
    }

    fun cancelInstall() {
        cancellation.cancel()
        preparationJob?.cancel()
    }

    override fun onCleared() {
        cancelInstall()
        super.onCleared()
    }

    fun install(
        context: Context,
        identity: ReadyServerUpdate,
    ) {
        if (preparing || (committed && !resumeSession)) return
        preparing = true
        resumeSession = false
        val signal = InstallCancellation().also { cancellation = it }
        preparationJob =
            viewModelScope.launch {
                try {
                    runInterruptible(Dispatchers.IO) {
                        SystemPackageInstaller.installServer(context, identity, sessionId, signal) { sessionId = it }
                    }
                    committed = true
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    DiagnosticsJournal.record("update_failed", mapOf("stage" to "install") + DiagnosticsProjection.failure(exception))
                    Log.e("ServerUpdateInstall", "Failed to prepare update session", exception)
                    error = R.string.server_update_install_failed
                } finally {
                    preparing = false
                    ServerUpdateNotifier.refresh(context)
                }
            }
    }
}

class ServerUpdateInstallActivity : AppCompatActivity() {
    companion object {
        private var visible = WeakReference<ServerUpdateInstallActivity>(null)
        private var coordinator = WeakReference<ServerUpdateInstallModel>(null)

        fun deliverResult(
            sessionId: Int,
            ready: ReadyServerUpdate?,
            status: Int,
            confirmation: Intent?,
        ): Boolean {
            val model = coordinator.get() ?: return false
            if (!model.recordResult(sessionId, ready, status, confirmation)) return false
            val activity = visible.get() ?: return false
            if (activity.model !== model || !activity.stateInitialized ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                return false
            }
            activity.continueCoordinator()
            return true
        }
    }

    private lateinit var model: ServerUpdateInstallModel
    private var checkingConfirmation = false
    private var stateInitialized = false
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            model.permissionResult(canInstallPackages())
            continueCoordinator()
        }
    private val confirmationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ServerUpdateInstallModel::class.java]
        coordinator = WeakReference(model)
        if (!model.initialized) {
            model.initialized = true
            model.ready = ServerUpdateNotifier.readyIdentity(intent)
            model.sessionId = savedInstanceState?.getInt("sessionId", -1)
                ?: intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            model.committed = savedInstanceState?.getBoolean("committed")
                ?: (intent.action == ServerUpdateNotifier.ACTION_CONFIRM)
            model.waitingForPermission = savedInstanceState?.getBoolean("waitingForPermission") ?: false
            model.confirmationLaunched = savedInstanceState?.getBoolean("confirmationLaunched") ?: false
            model.confirmation = savedInstanceState?.confirmationIntent("confirmation") ?: intent.confirmationIntent(Intent.EXTRA_INTENT)
            model.resultStatus = savedInstanceState?.getInt("resultStatus", Int.MIN_VALUE)?.takeUnless { it == Int.MIN_VALUE }
            model.error = savedInstanceState?.getInt("error")?.takeUnless { it == 0 }
            model.resumeSession = model.sessionId >= 0 && model.confirmation == null && !model.confirmationLaunched
        }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        visible = WeakReference(this)
                        if (model.waitingForPermission) model.permissionResult(canInstallPackages())
                        continueCoordinator()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        if (visible.get() === this) visible.clear()
                    }
                    else -> {
                        Unit
                    }
                }
            },
        )
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    io.nekohasekai.sfa.database.Settings.dataStore
                        .initialize()
                    ServerUpdates.initialize()
                }
                stateInitialized = true
                continueCoordinator()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("ServerUpdateInstall", "Cannot initialize update state", exception)
                model.error = R.string.server_update_install_failed
            }
        }
        setContent {
            Theme {
                Surface {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        Text(stringResource(R.string.server_update_reminder_title))
                        CircularProgressIndicator()
                        Text(stringResource(if (model.committed) R.string.server_update_waiting else R.string.server_update_preparing))
                        TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) }
                    }
                }
                if (model.permissionExplanation) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(stringResource(R.string.server_update_permission_title)) },
                        text = { Text(stringResource(R.string.server_update_permission_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                model.permissionExplanation = false
                                model.waitingForPermission = true
                                try {
                                    permissionLauncher.launch(
                                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                                    )
                                } catch (exception: Exception) {
                                    Log.e("ServerUpdateInstall", "Cannot open install permission settings", exception)
                                    model.waitingForPermission = false
                                    model.error = R.string.server_update_install_failed
                                }
                            }) { Text(stringResource(R.string.server_update_open_settings)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) }
                        },
                    )
                }
                model.error?.let { message ->
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(stringResource(R.string.server_update_reminder_title)) },
                        text = { Text(stringResource(message)) },
                        confirmButton = { TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.ok)) } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ServerUpdateNotifier.ACTION_CONFIRM) {
            model.ready = ServerUpdateNotifier.readyIdentity(intent)
            model.sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            model.committed = true
            model.confirmationLaunched = false
            model.confirmation = intent.confirmationIntent(Intent.EXTRA_INTENT)
            model.resultStatus = PackageInstaller.STATUS_PENDING_USER_ACTION
            model.error = null
            continueCoordinator()
        } else if (intent.action == ServerUpdateNotifier.ACTION_INSTALL && !model.preparing &&
            (!model.committed || model.error != null)
        ) {
            model.ready = ServerUpdateNotifier.readyIdentity(intent)
            model.sessionId = -1
            model.committed = false
            model.confirmation = null
            model.confirmationLaunched = false
            model.error = null
            model.resultStatus = null
            continueCoordinator()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("sessionId", model.sessionId)
        outState.putBoolean("committed", model.committed || model.sessionId != -1)
        outState.putBoolean("waitingForPermission", model.waitingForPermission)
        outState.putBoolean("confirmationLaunched", model.confirmationLaunched)
        outState.putParcelable("confirmation", model.confirmation)
        outState.putInt("resultStatus", model.resultStatus ?: Int.MIN_VALUE)
        outState.putInt("error", model.error ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if (::model.isInitialized) model.cancelInstall()
        super.finish()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && !model.confirmationLaunched) {
            model.confirmation?.let {
                ServerUpdateNotifier.postConfirmation(this, model.sessionId, it, model.ready)
            }
        }
        super.onDestroy()
    }

    private fun continueCoordinator() {
        if (!stateInitialized || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val status = model.resultStatus
        model.resultStatus = null
        when (status) {
            null -> {
                Unit
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (model.confirmation == null) model.error = R.string.server_update_confirmation_unavailable
            }
            PackageInstaller.STATUS_SUCCESS -> {
                finish()
                return
            }
            else -> {
                model.error = R.string.server_update_install_failed
            }
        }
        if (model.error != null) return
        if (model.confirmation != null) launchConfirmation() else continueInstall()
    }

    private fun canInstallPackages(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun continueInstall() {
        if (!stateInitialized || model.preparing || (model.committed && !model.resumeSession) ||
            model.waitingForPermission || model.error != null
        ) {
            return
        }
        val ready = model.ready
        if (ready == null || ready != ServerUpdates.state.value.ready) {
            model.error = R.string.server_update_unavailable
            return
        }
        if (!canInstallPackages()) {
            model.permissionExplanation = true
            return
        }
        model.permissionExplanation = false
        model.install(applicationContext, ready)
    }

    private fun launchConfirmation() {
        if (!stateInitialized || checkingConfirmation || model.confirmationLaunched) return
        val confirmation =
            model.confirmation ?: run {
                model.error = R.string.server_update_confirmation_unavailable
                return
            }
        checkingConfirmation = true
        lifecycleScope.launch {
            try {
                val ready = model.ready
                val valid =
                    withContext(Dispatchers.IO) {
                        val session = packageManager.packageInstaller.getSessionInfo(model.sessionId)
                        session?.installerPackageName == packageName &&
                            (
                                ready == null ||
                                    (
                                        ServerUpdateChecker.verifiedReadyApk(this@ServerUpdateInstallActivity)?.absolutePath == ready.path &&
                                            ServerUpdates.state.value.ready == ready
                                    )
                            )
                    }
                if (!valid) {
                    model.confirmation = null
                    ServerUpdateNotifier.cancelConfirmation(this@ServerUpdateInstallActivity, model.sessionId)
                    model.error = R.string.server_update_confirmation_unavailable
                } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    confirmationLauncher.launch(confirmation)
                    model.confirmationLaunched = true
                    model.confirmation = null
                    ServerUpdateNotifier.cancelConfirmation(this@ServerUpdateInstallActivity, model.sessionId)
                } else {
                    ServerUpdateNotifier.postConfirmation(this@ServerUpdateInstallActivity, model.sessionId, confirmation, ready)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("ServerUpdateInstall", "Cannot launch install confirmation", exception)
                model.error = R.string.server_update_confirmation_unavailable
            } finally {
                checkingConfirmation = false
                ServerUpdateNotifier.refresh(this@ServerUpdateInstallActivity)
            }
        }
    }

    private fun Intent.confirmationIntent(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }

    private fun Bundle.confirmationIntent(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(key)
        }
}
