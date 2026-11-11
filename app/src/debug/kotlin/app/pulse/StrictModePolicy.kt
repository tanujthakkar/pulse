package app.pulse

import android.os.StrictMode

/** Debug builds log disk and slow calls on the main thread. */
fun installStrictMode() {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectCustomSlowCalls()
            .penaltyLog()
            .build(),
    )
    StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .penaltyLog()
            .build(),
    )
}
