package app.pulse.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoredHistoryAckTest {
    @Test
    fun aMissingChoiceIsOnAndALaterObjectKeepsTheChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pulse.history_ack", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(StoredHistoryAck(context).enabled())
        StoredHistoryAck(context).setEnabled(false)
        assertFalse(StoredHistoryAck(context).enabled())
        StoredHistoryAck(context).setEnabled(true)
        assertTrue(StoredHistoryAck(context).enabled())
    }
}
