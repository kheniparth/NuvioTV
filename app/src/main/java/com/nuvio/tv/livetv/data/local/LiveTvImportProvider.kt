package com.nuvio.tv.livetv.data.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Shell/root-only Live TV proxy import via `content call`. Never logs arg or payload.
 * UID gate lives in [handleLiveTvImport] before parse/apply.
 */
class LiveTvImportProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LiveTvImportEntryPoint {
        fun liveTvSettingsDataStore(): LiveTvSettingsDataStore
        fun profileManager(): ProfileManager
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = try {
            val appContext = context?.applicationContext
                ?: return resultBundle("error:init")
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext,
                LiveTvImportEntryPoint::class.java,
            )
            val store = entryPoint.liveTvSettingsDataStore()
            val profiles = entryPoint.profileManager()
            handleLiveTvImport(Binder.getCallingUid(), method, arg) { import ->
                if (!profileExists(profiles, import.profileId)) {
                    throw LiveTvImportProfileException()
                }
                runBlocking(Dispatchers.IO) { store.importSources(import) }
            }
        } catch (_: LiveTvImportProfileException) {
            "error:profile"
        } catch (e: SecurityException) {
            throw e
        } catch (_: Throwable) {
            "error:init"
        }
        return resultBundle(result)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun resultBundle(result: String): Bundle =
        Bundle().apply { putString("result", result) }

    private fun profileExists(profiles: ProfileManager, profileId: Int): Boolean {
        if (profileId < 1 || profileId > ProfileManager.MAX_PROFILES) return false
        if (profileId == 1) return true
        return profiles.profiles.value.any { it.id == profileId }
    }
}

private class LiveTvImportProfileException : Exception()
