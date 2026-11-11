package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.diagnostics.DiagnosticsSources
import io.nekohasekai.sfa.diagnostics.ReportCapabilities
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

@Suppress("RedundantSuspendModifier")
object ProfileManager {
    private val callbacks = mutableListOf<() -> Unit>()
    private val profileAccess = Any()
    private val remoteValidator by lazy {
        RemoteProfileValidator(File(Application.application.noBackupFilesDir, "profile_validation"), Libbox::checkConfig)
    }
    private val diagnosticsSources = DiagnosticsSources(profileAccess) { id ->
        instance.profileDao().get(id)?.typed?.takeIf { it.type == TypedProfile.Type.Remote }?.remoteURL
    }

    internal fun captureDiagnosticsSource(id: Long): () -> String? = diagnosticsSources.capture(id)

    fun registerCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val instance by lazy {
        Application.application.getDatabasePath(Path.PROFILES_DATABASE_PATH).parentFile?.mkdirs()
        Room
            .databaseBuilder(
                Application.application,
                ProfileDatabase::class.java,
                Path.PROFILES_DATABASE_PATH,
            )
            .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .enableMultiInstanceInvalidation()
            .setQueryExecutor { GlobalScope.launch { it.run() } }
            .build()
    }

    suspend fun nextOrder(): Long = instance.profileDao().nextOrder() ?: 0

    suspend fun nextFileID(): Long = instance.profileDao().nextFileID() ?: 1

    suspend fun get(id: Long): Profile? = instance.profileDao().get(id)

    suspend fun create(
        profile: Profile,
        andSelect: Boolean = false,
        fetchedProfile: HTTPClient.ProfileResponse? = null,
    ): Profile {
        synchronized(profileAccess) {
            profile.id = instance.profileDao().insert(profile)
            diagnosticsSources.created(profile.id)
            ReportCapabilities.clear(profile.id)
            remoteValidator.clear(profile.id)
            if (profile.typed.type == TypedProfile.Type.Remote && fetchedProfile != null) {
                ReportCapabilities.accepted(
                    profile.id,
                    profile.typed.remoteURL,
                    fetchedProfile.finalURL,
                    fetchedProfile.reportHeader,
                )
            }
        }
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        notifyProfilesChanged()
        return profile
    }

    suspend fun update(profile: Profile): Int {
        try {
            return synchronized(profileAccess) {
                diagnosticsSources.changing(profile.id)
                ReportCapabilities.profileChanged(
                    profile.id,
                    profile.typed.remoteURL.takeIf { profile.typed.type == TypedProfile.Type.Remote },
                )
                instance.profileDao().update(profile)
            }
        } finally {
            notifyProfilesChanged()
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return synchronized(profileAccess) {
                for (profile in profiles) {
                    diagnosticsSources.changing(profile.id)
                    ReportCapabilities.profileChanged(
                        profile.id,
                        profile.typed.remoteURL.takeIf { profile.typed.type == TypedProfile.Type.Remote },
                    )
                }
                instance.profileDao().update(profiles)
            }
        } finally {
            notifyProfilesChanged()
        }
    }

    suspend fun delete(profile: Profile): Int {
        try {
            return synchronized(profileAccess) {
                diagnosticsSources.changing(profile.id)
                ReportCapabilities.clear(profile.id)
                remoteValidator.clear(profile.id)
                instance.profileDao().delete(profile)
            }
        } finally {
            notifyProfilesChanged()
        }
    }

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            return synchronized(profileAccess) {
                profiles.forEach {
                    diagnosticsSources.changing(it.id)
                    ReportCapabilities.clear(it.id)
                    remoteValidator.clear(it.id)
                }
                instance.profileDao().delete(profiles)
            }
        } finally {
            notifyProfilesChanged()
        }
    }

    suspend fun updateRemote(profile: Profile): Boolean {
        val remoteURL = profile.typed.remoteURL
        val contentChanged =
            try {
                val fetchedProfile = HTTPClient().use { it.getProfile(remoteURL) }
                val stored = synchronized(profileAccess) {
                    val current = instance.profileDao().get(profile.id) ?: return false
                    if (current.typed.type != TypedProfile.Type.Remote || current.typed.remoteURL != remoteURL) return false
                    ReportCapabilities.withdrawIfUnsupported(
                        current.id,
                        remoteURL,
                        fetchedProfile.finalURL,
                        fetchedProfile.reportHeader,
                    )
                    File(current.typed.path).takeIf { it.exists() }?.readText()
                }
                remoteValidator.validate(profile.id, stored, fetchedProfile.content, BuildConfig.VERSION_CODE)
                synchronized(profileAccess) {
                    val current = instance.profileDao().get(profile.id) ?: return false
                    if (current.typed.type != TypedProfile.Type.Remote || current.typed.remoteURL != remoteURL) return false
                    val file = File(current.typed.path)
                    val changed = !file.exists() || file.readText() != fetchedProfile.content
                    if (changed) {
                        file.writeText(fetchedProfile.content)
                    }
                    current.typed.markUpdateSuccess()
                    instance.profileDao().update(current)
                    ReportCapabilities.accepted(
                        current.id,
                        remoteURL,
                        fetchedProfile.finalURL,
                        fetchedProfile.reportHeader,
                    )
                    profile.typed = current.typed
                    changed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    val currentTyped = instance.profileDao().updateRemoteError(
                        profile.id,
                        remoteURL,
                        e.message ?: e.toString(),
                    )
                    if (currentTyped != null) {
                        profile.typed = currentTyped
                        notifyProfilesChanged()
                    }
                } catch (saveError: CancellationException) {
                    throw saveError
                } catch (saveError: Exception) {
                    e.addSuppressed(saveError)
                }
                throw e
            }
        notifyProfilesChanged()
        return contentChanged
    }

    private suspend fun notifyProfilesChanged() {
        UpdateProfileWork.reconfigureUpdater()
        for (callback in callbacks.toList()) {
            callback()
        }
    }

    suspend fun list(): List<Profile> = instance.profileDao().list()

    fun remoteServerDao(): RemoteServer.Dao = instance.remoteServerDao()
}
