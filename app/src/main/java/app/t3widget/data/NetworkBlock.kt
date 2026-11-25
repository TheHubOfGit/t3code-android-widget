package app.t3widget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.PowerManager

/**
 * Battery Saver (and Doze) cut background apps off the network entirely, which looks exactly like
 * an unreachable server. Tell the two apart so the widget can say what's actually going on.
 */
object NetworkBlock {
    const val MESSAGE = "Battery Saver paused updates — tap to allow"

    fun isBlocked(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val blocked = runCatching {
            context.getSystemService(ConnectivityManager::class.java).activeNetworkInfo?.detailedState ==
                NetworkInfo.DetailedState.BLOCKED
        }.getOrDefault(false)
        return blocked || power?.isPowerSaveMode == true || power?.isDeviceIdleMode == true
    }
}
