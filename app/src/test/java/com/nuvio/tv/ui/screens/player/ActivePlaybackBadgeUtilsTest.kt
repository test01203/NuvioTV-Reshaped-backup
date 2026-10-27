package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePlaybackBadgeUtilsTest {

    @Test
    fun `formatVideoQuality maps standard and ultrawide cinema dimensions`() {
        assertEquals("4K", ActivePlaybackBadgeUtils.formatVideoQuality(3840, 2160))
        assertEquals("4K", ActivePlaybackBadgeUtils.formatVideoQuality(3840, 1606))
        assertEquals("1440p", ActivePlaybackBadgeUtils.formatVideoQuality(2560, 1440))
        assertEquals("1080p", ActivePlaybackBadgeUtils.formatVideoQuality(1920, 1080))
        assertEquals("1080p", ActivePlaybackBadgeUtils.formatVideoQuality(1920, 800))
        assertEquals("720p", ActivePlaybackBadgeUtils.formatVideoQuality(1280, 720))
        assertEquals("720p", ActivePlaybackBadgeUtils.formatVideoQuality(1280, 534))
        assertEquals("480p", ActivePlaybackBadgeUtils.formatVideoQuality(854, 480))
        assertNull(ActivePlaybackBadgeUtils.formatVideoQuality(null, null))
        assertNull(ActivePlaybackBadgeUtils.formatVideoQuality(0, 0))
    }

    @Test
    fun `detectHdrFormat identifies Dolby Vision, HDR10+, HDR10, and HLG`() {
        assertEquals(
            "Dolby Vision",
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_DOLBY_VISION,
                codecs = "dvhe.08.06",
                colorTransfer = C.COLOR_TRANSFER_ST2084
            )
        )
        assertEquals(
            "HDR10+",
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_H265,
                codecs = "hev1.2.4.L153.B0",
                colorTransfer = C.COLOR_TRANSFER_ST2084,
                streamHints = listOf("Movie.2022.2160p.UHD.BluRay.HDR10+.HEVC.mkv")
            )
        )
        assertEquals(
            "HDR10",
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_H265,
                codecs = "hev1.2.4.L153.B0",
                colorTransfer = C.COLOR_TRANSFER_ST2084
            )
        )
        assertEquals(
            "HLG",
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_H265,
                codecs = null,
                colorTransfer = C.COLOR_TRANSFER_HLG
            )
        )
        assertNull(
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_H264,
                codecs = "avc1.640028",
                colorTransfer = C.COLOR_TRANSFER_SDR
            )
        )
    }

    @Test
    fun `detectAudioFormat identifies Atmos, TrueHD, DD+, DTS-HD, DTS, and Stereo`() {
        val atmos = ActivePlaybackBadgeUtils.detectAudioFormat(
            mimeType = MimeTypes.AUDIO_E_AC3_JOC,
            codecs = "ec+3",
            label = "English Atmos",
            channelCount = 6
        )
        assertEquals("Atmos", atmos?.label)
        assertTrue(atmos?.isDolby == true)

        val trueHdAtmos = ActivePlaybackBadgeUtils.detectAudioFormat(
            mimeType = MimeTypes.AUDIO_TRUEHD,
            codecs = "truehd",
            label = "TrueHD 7.1 Atmos",
            channelCount = 8
        )
        assertEquals("Atmos", trueHdAtmos?.label)
        assertTrue(trueHdAtmos?.isDolby == true)

        val ddp = ActivePlaybackBadgeUtils.detectAudioFormat(
            mimeType = MimeTypes.AUDIO_E_AC3,
            codecs = "ec-3",
            label = "English",
            channelCount = 6
        )
        assertEquals("Dolby Digital+", ddp?.label)
        assertTrue(ddp?.isDolby == true)

        val dtsHd = ActivePlaybackBadgeUtils.detectAudioFormat(
            mimeType = MimeTypes.AUDIO_DTS_HD,
            codecs = "dtshd",
            label = "DTS-HD MA 5.1",
            channelCount = 6
        )
        assertEquals("DTS-HD", dtsHd?.label)
        assertFalse(dtsHd?.isDolby == true)

        val stereo = ActivePlaybackBadgeUtils.detectAudioFormat(
            mimeType = MimeTypes.AUDIO_AAC,
            codecs = "mp4a.40.2",
            label = "Hindi",
            channelCount = 2
        )
        assertEquals("Stereo", stereo?.label)
        assertFalse(stereo?.isDolby == true)
    }

    @Test
    fun `formatAudioLanguageAndChannels formats language and numeric channels`() {
        assertEquals(
            "English 5.1",
            ActivePlaybackBadgeUtils.formatAudioLanguageAndChannels("en", null, 6)
        )
        assertEquals(
            "Hindi 2.0",
            ActivePlaybackBadgeUtils.formatAudioLanguageAndChannels("hi", null, 2)
        )
        assertEquals(
            "5.1",
            ActivePlaybackBadgeUtils.formatAudioLanguageAndChannels("und", null, 6)
        )
    }
}
