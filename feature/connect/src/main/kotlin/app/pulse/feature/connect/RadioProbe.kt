package app.pulse.feature.connect

import android.app.Activity

fun interface RadioProbe {
    fun read(activity: Activity): RadioSnapshot
}
