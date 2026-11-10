package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

@Suppress("RedundantSuspendModifier")
object ProfileManager {
    private val callbacks = mutableListOf<() -> Unit>()

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

    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile {
        profile.id = instance.profileDao().insert(profile)
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        notifyProfilesChanged()
        return profile
    }

    suspend fun update(profile: Profile): Int {
        try {
            return instance.profileDao().update(profile)
        } finally {
            notifyProfilesChanged()
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return instance.profileDao().update(profiles)
        } finally {
            notifyProfilesChanged()
        }
    }

    suspend fun delete(profile: Profile): Int {
        try {
            return instance.profileDao().delete(profile)
        } finally {
            notifyProfilesChanged()
        }
    }

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            return instance.profileDao().delete(profiles)
        } finally {
            notifyProfilesChanged()
        }
    }

    suspend fun updateRemote(profile: Profile): Boolean {
        val contentChanged =
            try {
                val content = HTTPClient().use { it.getProfile(profile.typed.remoteURL) }
                Libbox.checkConfig(content)
                val file = File(profile.typed.path)
                val changed = !file.exists() || file.readText() != content
                if (changed) {
                    file.writeText(content)
                }
                changed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    val currentTyped = instance.profileDao().updateRemoteError(
                        profile.id,
                        profile.typed.remoteURL,
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
        profile.typed.markUpdateSuccess()
        update(profile)
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
