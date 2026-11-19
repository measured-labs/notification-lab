package com.jun.labs.notification.impl

import com.jun.labs.notification.service.CampaignUseCase
import com.jun.labs.notification.support.Postgres
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doThrow
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 계약 밖 예외 → 500 INTERNAL_ERROR · 본문은 정확히 {code, message} · 고정 문구 · 예외 내용 미노출 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalErrorApiTest {
    @LocalServerPort
    private var port: Int = 0

    @MockitoBean
    private lateinit var campaignUseCase: CampaignUseCase

    @Test
    fun `유스케이스 예외는 500 고정 문구`() {
        doThrow(IllegalStateException("secret-internal-detail jdbc:postgresql://db")).`when`(campaignUseCase).get(anyLong())
        val r = Http("http://localhost:$port").get("/campaigns/5")
        assertEquals(500, r.status)
        assertEquals(setOf("code", "message"), r.body.fieldNames().asSequence().toSet())
        assertEquals("INTERNAL_ERROR", r.body["code"].asText())
        assertEquals("서버 오류가 발생했습니다.", r.body["message"].asText())
        assertFalse(r.body.toString().contains("secret"))
        assertFalse(r.body.toString().contains("jdbc"))
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = Postgres.register(registry)
    }
}
