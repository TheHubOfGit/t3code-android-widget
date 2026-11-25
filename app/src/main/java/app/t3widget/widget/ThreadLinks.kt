package app.t3widget.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.t3widget.data.T3Constants
import app.t3widget.settings.SettingsActivity

/**
 * Tap-through targets (HANDOFF.md §7.4).
 *
 * The official app routes `threads/:environmentId/:threadId` (apps/mobile/src/Stack.tsx) and claims
 * https://app.t3.codes/threads/ links. A link without the environment id leaves it stuck on
 * "Opening thread…", so without an id we just open the app.
 */
object ThreadLinks {
    fun threadIntent(context: Context, environmentId: String?, threadId: String, serverUrl: String?): Intent {
        if (environmentId.isNullOrBlank()) return appIntent(context, serverUrl)
        val link = T3Constants.THREAD_LINK_PREFIX + Uri.encode(environmentId) + "/" + Uri.encode(threadId)
        return Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Official app if installed, else the server's web UI, else settings. */
    fun appIntent(context: Context, serverUrl: String?): Intent {
        val pm = context.packageManager
        T3Constants.OFFICIAL_APP_PACKAGES.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
            ?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (serverUrl != null) {
            return Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
