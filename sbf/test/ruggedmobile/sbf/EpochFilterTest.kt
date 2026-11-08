package ruggedmobile.sbf

import ruggedmobile.sbf.testkit.SbfEncoder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpochFilterTest {
    private fun b(tow: Long, wnc: Int = 2300) =
        SbfBlock().wrap(SbfEncoder.block(BlockNumber.DOP_2, tow = tow, wnc = wnc) { u8(0) })

    @Test
    fun interval() {
        val f = EpochFilter(intervalMs = 5000)
        assertTrue(f.includes(b(10_000)))
        assertFalse(f.includes(b(10_100)))
    }

    @Test
    fun timeOfDayWindowAppliesEveryDay() {
        val f = EpochFilter(firstEpochMs = 3_600_000, lastEpochMs = 7_200_000)
        assertTrue(f.includes(b(86_400_000L + 3_600_000)))
        assertFalse(f.includes(b(86_400_000L + 3_599_999)))
        assertFalse(f.includes(b(7_200_001)))
    }

    @Test
    fun absoluteWindow() {
        val start = 2300L * 604_800_000L + 1000
        val f = EpochFilter(firstEpochMs = start)
        assertFalse(f.includes(b(999)))
        assertTrue(f.includes(b(1000)))
    }

    @Test
    fun invalidTime() {
        val invalid = b(0xFFFFFFFFL, 65535)
        assertTrue(EpochFilter().includes(invalid))
        assertFalse(EpochFilter(acceptInvalidTime = false).includes(invalid))
        // As in C, TOW -1 fails any interval check other than 1 ms.
        assertFalse(EpochFilter(intervalMs = 1000).includes(invalid))
    }

    @Test
    fun rawFieldOverloadMatchesBlockOverload() {
        val f = EpochFilter(firstEpochMs = 3_600_000, intervalMs = 100)
        for (tow in listOf(0L, 3_599_900L, 3_600_000L, 3_600_050L, 0xFFFFFFFFL)) {
            val blk = b(tow)
            kotlin.test.assertEquals(f.includes(blk), f.includes(blk.tow, blk.wnc))
        }
    }
}
