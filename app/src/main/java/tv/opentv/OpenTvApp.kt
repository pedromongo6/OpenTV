// language: Kotlin, file: OpenTvApp.kt, target: Android 14
// *Application entry. Wires the repository and prunes the EPG cache on start.*

package tv.opentv

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tv.opentv.data.Repository

class OpenTvApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val repo = Repository.get(this)

        scope.launch {
            repo.loadSources()
            repo.refreshAccount()
            // Guide data older than a day is stale for every provider we speak to.
            runCatching {
                repo.db().epg().prune(System.currentTimeMillis() - 86_400_000L)
            }
        }
    }
}
