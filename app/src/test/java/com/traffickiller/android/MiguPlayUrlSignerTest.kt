package com.traffickiller.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiguPlayUrlSignerTest {

    @Test
    fun computeSign_matchesAndroidPlayUrlFormula() {
        val sign = MiguPlayUrlSigner.computeSign(
            timestampMs = "1700000000000",
            contId = "738323833",
            salt = "12345625"
        )
        assertEquals("c9679ac9127e9f1cd576bcd8b2d20d12", sign)
    }

    @Test
    fun computeDdCalcu_foldsPuDataAndUsesNumericKeyIndices() {
        val ddCalcu = MiguPlayUrlSigner.computeDdCalcu(
            puData = "5e5bf043a917b05748939db897225990",
            programId = "738323833",
            dateStr = "20261129"
        )
        assertEquals(36, ddCalcu.length)
        assertEquals("059ev95a5bk2fa2074938ab9d1973b908547", ddCalcu)
    }

    @Test
    fun buildPlayUrlRequestUrl_includesRequiredParamsAndOptionalH265() {
        val url = MiguPlayUrlSigner.buildPlayUrlRequestUrl(
            target = MiguDynamicTarget(contId = "624878356", rateType = "3", h265 = true),
            timestampMs = 1700000000000L,
            salt = "12345625"
        )
        assertTrue(url.startsWith("${MiguPlayUrlSigner.PLAYURL_ENDPOINT}?"))
        assertTrue(url.contains("contId=624878356"))
        assertTrue(url.contains("rateType=3"))
        assertTrue(url.contains("timestamp=1700000000000"))
        assertTrue(url.contains("salt=12345625"))
        assertTrue(url.contains("flvEnable=true&super4k=true"))
        assertTrue(url.contains("&h265N=true"))
    }

    @Test
    fun parseDynamicTarget_supportsPlayUrlAndDetailUrls() {
        val liveTarget = MiguPlayUrlSigner.parseDynamicTarget(PresetNodes.MIGU_LIVE_LOCAL_URL)
        assertNotNull(liveTarget)
        assertEquals("608807420", liveTarget!!.contId)
        assertEquals("3", liveTarget.rateType)
        assertFalse(liveTarget.h265)

        val vodTarget = MiguPlayUrlSigner.parseDynamicTarget(PresetNodes.MIGU_VOD_LOCAL_URL)
        assertNotNull(vodTarget)
        assertEquals("738323833", vodTarget!!.contId)
        assertFalse(vodTarget.h265)

        val detailTarget = MiguPlayUrlSigner.parseDynamicTarget(
            "https://m.miguvideo.com/m/detail/738323833?channelId=10010001005"
        )
        assertNotNull(detailTarget)
        assertEquals("738323833", detailTarget!!.contId)

        assertNull(
            MiguPlayUrlSigner.parseDynamicTarget(
                "https://cloudphoneh5.buy.139.com/js/chunk-vendors.47eadd9a.js"
            )
        )
    }

    @Test
    fun extractPuData_and_appendDdCalcu_formatGslbUrl() {
        val raw = "http://gslbmgsplive.miguvideo.com/wd_r2/cctv/cctv1hd/600/index.m3u8?a=1&puData=5e5bf043a917b05748939db897225990"
        val puData = MiguPlayUrlSigner.extractPuData(raw)
        assertEquals("5e5bf043a917b05748939db897225990", puData)

        val appended = MiguPlayUrlSigner.appendDdCalcu(raw, "059ev95a5bk2fa2074938ab9d1973b908547")
        assertTrue(appended.endsWith("&ddCalcu=059ev95a5bk2fa2074938ab9d1973b908547&sv=10004&ct=android"))
    }

    @Test
    fun isDynamicLiveUrl_recognizesLocalAndExternalPresets() {
        assertTrue(PresetNodes.isDynamicLiveUrl(PresetNodes.MIGU_LIVE_LOCAL_URL))
        assertTrue(PresetNodes.isDynamicLiveUrl(PresetNodes.MIGU_VOD_LOCAL_URL))
        assertTrue(PresetNodes.isDynamicLiveUrl(PresetNodes.MIGU_LIVE_EXTERNAL_URL))
        assertFalse(PresetNodes.isDynamicLiveUrl("https://img.cmvideo.cn/publish/noms/output/2026/10/09/1O7H81IQNJTSO.gif"))
    }
}
