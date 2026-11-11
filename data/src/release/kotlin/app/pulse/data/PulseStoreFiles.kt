package app.pulse.data

import android.content.Context

/** Release builds always open the real strap database. */
object PulseStoreFiles {
    fun name(context: Context): String = "pulse.db"
}
