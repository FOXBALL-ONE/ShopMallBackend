package top.foxball.shopmall.logging

import org.springframework.util.unit.DataSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoggingPropertiesTest {
    @Test
    fun `async queue minimum includes captured string and event overhead`() {
        val maxRecordBytes = DataSize.ofKilobytes(1).toBytes()
        val minimum = LoggingProperties.minimumAsyncQueueBytes(maxRecordBytes)

        assertFailsWith<IllegalArgumentException> {
            LoggingProperties(
                maxRecordSize = DataSize.ofKilobytes(1),
                asyncQueueBytes = minimum - 1,
            )
        }

        val properties = LoggingProperties(
            maxRecordSize = DataSize.ofKilobytes(1),
            asyncQueueBytes = minimum,
        )
        assertEquals(minimum, properties.asyncQueueBytes)
    }

    @Test
    fun `file queue minimum includes rendered record overhead`() {
        val maxRecordBytes = DataSize.ofKilobytes(1).toBytes()
        val minimum = LoggingProperties.minimumFileQueueBytes(maxRecordBytes)

        assertFailsWith<IllegalArgumentException> {
            LoggingProperties(
                maxRecordSize = DataSize.ofKilobytes(1),
                fileQueueBytes = minimum - 1,
            )
        }

        val properties = LoggingProperties(
            maxRecordSize = DataSize.ofKilobytes(1),
            fileQueueBytes = minimum,
        )
        assertEquals(minimum, properties.fileQueueBytes)
    }
}
