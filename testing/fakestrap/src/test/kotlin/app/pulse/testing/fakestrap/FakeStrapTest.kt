package app.pulse.testing.fakestrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class FakeStrapTest {
    @Test
    fun placeholdersConstruct() {
        assertEquals("0", FakeStrap().specVersion)
        assertNotNull(FakeGattSession())
    }
}
