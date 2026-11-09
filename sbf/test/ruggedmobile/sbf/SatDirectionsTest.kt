package ruggedmobile.sbf

import ruggedmobile.sbf.testkit.LeBytes
import ruggedmobile.sbf.testkit.SbfEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

class SatDirectionsTest {
    private val dirs = SatDirections()

    /** One ChannelSatInfo (SB1 = 12) with its ChannelStateInfo sub-blocks (SB2 = 16). */
    private fun LeBytes.channel(svid: Int, azDeg: Int, riseSet: Int, elDeg: Int, pvtStatus: List<Int>) {
        u8(svid); u8(0); u16(0) // SVID, FreqNr, SVIDFull
        u16(azDeg or (riseSet shl 14)) // Azimuth/RiseSet
        u16(0) // HealthStatus
        u8(elDeg and 0xFF); u8(pvtStatus.size); u8(0); u8(0) // Elevation, N2, RxChannel, padding
        for (s in pvtStatus) {
            u8(0); u8(0); u16(0); u16(s); u16(0) // Antenna, padding, TrackingStatus, PVTStatus, PVTInfo
            repeat(8) { u8(0) } // padding to SB2 = 16
        }
    }

    private fun channelStatus(body: LeBytes.() -> Unit, n: Int) = SbfBlock().wrap(
        SbfEncoder.block(BlockNumber.CHANNEL_STATUS, tow = 1000, wnc = 2390) {
            u8(n); u8(12); u8(16); u8(0); u8(0); u8(0) // N, SB1Length, SB2Length, reserved
            body()
        },
    )

    @Test
    fun channelStatusMergesChannelsOfOneSatellite() {
        val b = channelStatus(n = 4, body = {
            channel(svid = 5, azDeg = 120, riseSet = 1, elDeg = 40, pvtStatus = listOf(0x0001)) // tracked, not used
            channel(svid = 71, azDeg = 300, riseSet = 0, elDeg = 10, pvtStatus = listOf(0x0002)) // Galileo E01, used
            channel(svid = 5, azDeg = 121, riseSet = 3, elDeg = 41, pvtStatus = listOf(0x0000, 0x0200)) // G05 again, used on signal 4
            channel(svid = 6, azDeg = 511, riseSet = 0, elDeg = 10, pvtStatus = emptyList()) // azimuth unknown: skipped
        })
        assertEquals(2, dirs.readChannelStatus(b))
        assertEquals(5, dirs.prn[0]) // first channel's position
        assertEquals(121f, dirs.azimuthDeg[0]) // last channel's direction
        assertEquals(41f, dirs.elevationDeg[0])
        assertEquals(SatDirections.YES, dirs.usedInPvt[0])
        assertEquals(SatDirections.UNKNOWN, dirs.rising[0])
        assertEquals(Svid.GAL_MIN, dirs.prn[1])
        assertEquals(SatDirections.NO, dirs.rising[1])

        // Reuse: the PRN index is reset, so the next block starts fresh.
        val again = channelStatus(n = 1, body = { channel(svid = 5, azDeg = 10, riseSet = 1, elDeg = -5, pvtStatus = listOf(0x0001)) })
        assertEquals(1, dirs.readChannelStatus(again))
        assertEquals(SatDirections.NO, dirs.usedInPvt[0])
        assertEquals(-5f, dirs.elevationDeg[0])
    }

    @Test
    fun satVisibilityKeepsEveryEntry() {
        val b = SbfBlock().wrap(SbfEncoder.block(BlockNumber.SAT_VISIBILITY, tow = 1000, wnc = 2390) {
            u8(3); u8(8) // N, SBLength
            u8(12); u8(0); u16(12345); u16(4500); u8(1); u8(0) // G12 at 123.45 / 45.00, rising
            u8(13); u8(0); u16(Dnu.U16); u16(100); u8(0); u8(0) // azimuth unknown: skipped
            u8(12); u8(0); u16(100); u16(-250 and 0xFFFF); u8(2); u8(0) // duplicate PRN is kept
        })
        assertEquals(2, dirs.readSatVisibility(b))
        assertEquals(12, dirs.prn[0])
        assertEquals(123.45f, dirs.azimuthDeg[0])
        assertEquals(45f, dirs.elevationDeg[0])
        assertEquals(SatDirections.UNKNOWN, dirs.usedInPvt[0])
        assertEquals(SatDirections.YES, dirs.rising[0])
        assertEquals(-2.5f, dirs.elevationDeg[1])
        assertEquals(SatDirections.UNKNOWN, dirs.rising[1])
    }

    @Test
    fun otherBlockTypesReadAsEmpty() {
        val b = SbfBlock().wrap(SbfEncoder.block(BlockNumber.DOP_2, tow = 1000, wnc = 2390) { repeat(16) { u8(0) } })
        assertEquals(0, dirs.readChannelStatus(b))
        assertEquals(0, dirs.readSatVisibility(b))
    }
}
