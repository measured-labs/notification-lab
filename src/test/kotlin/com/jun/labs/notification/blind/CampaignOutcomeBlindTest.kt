package com.jun.labs.notification.blind

import com.jun.labs.notification.blind.ScriptedProvider.ok
import com.jun.labs.notification.blind.ScriptedProvider.status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 캠페인 대상별 결과 · 재시도 — 시나리오 발송사 대역. ADR-000 §3(대상마다 한 트랜잭션 · 재시도는 트랜잭셔널과 같음 · 완료) ·
 * §2.1 ⑥ · §6(requested · sent · failed = 상태별 행 수).
 */
class CampaignOutcomeBlindTest : ScriptedProviderBlindTest() {

    @Test
    fun `대상마다 결과가 다르다 - 성공 · 4xx · 5xx 소진 · 429 뒤 성공, 수는 상태별로 센다`() {
        val start = newUserRange(4)
        provider.script(start, "sms", ok("c-0"))
        provider.script(start + 1, "sms", status(400))
        provider.script(start + 2, "sms", status(500), status(500), status(500), status(500))
        provider.script(start + 3, "sms", status(429, "Retry-After" to "30"), ok("c-3"))

        val c = createCampaignOk(targetCount = 4, channel = "SMS", userIdStart = start, title = "캠페인 제목", body = "캠페인 본문")
        val id = c["campaignId"].asLong()
        val done = awaitCompleted(id)

        assertThat(done["requested"].asLong()).isEqualTo(0L)
        assertThat(done["sent"].asLong()).isEqualTo(2L)
        assertThat(done["failed"].asLong()).isEqualTo(2L)
        assertThat(done["targetCount"].asLong()).isEqualTo(4L)

        val byUser = rowsByCampaign(id).associateBy { it.long("user_id") }
        assertThat(byUser.keys).containsExactlyInAnyOrder(start, start + 1, start + 2, start + 3)
        assertThat(byUser.getValue(start).str("status")).isEqualTo("SENT")
        assertThat(byUser.getValue(start).long("attempts")).isEqualTo(1L)
        assertThat(byUser.getValue(start).str("provider_message_id")).isEqualTo("c-0")
        assertThat(byUser.getValue(start + 1).str("status")).isEqualTo("FAILED")
        assertThat(byUser.getValue(start + 1).long("attempts")).isEqualTo(1L)
        assertThat(byUser.getValue(start + 1).str("last_error")).isNotBlank()
        assertThat(byUser.getValue(start + 2).str("status")).isEqualTo("FAILED")
        assertThat(byUser.getValue(start + 2).long("attempts")).isEqualTo(4L)
        assertThat(byUser.getValue(start + 2).str("last_error")).isNotBlank()
        assertThat(byUser.getValue(start + 3).str("status")).isEqualTo("SENT")
        assertThat(byUser.getValue(start + 3).long("attempts")).isEqualTo(2L)
        assertThat(byUser.getValue(start + 3).str("provider_message_id")).isEqualTo("c-3")

        // 실제 호출 수 = attempts
        assertThat(provider.callsFor(start)).hasSize(1)
        assertThat(provider.callsFor(start + 1)).hasSize(1)
        assertThat(provider.callsFor(start + 2)).hasSize(4)
        assertThat(provider.callsFor(start + 3)).hasSize(2)
        (start until start + 4).flatMap { provider.callsFor(it) }.forEach { call ->
            assertThat(call.path).isEqualTo("/v1/sms/send")
            assertThat(call.title).isEqualTo("캠페인 제목")
            assertThat(call.body).isEqualTo("캠페인 본문")
        }
        assertThat(campaignRow(id).str("status")).isEqualTo("COMPLETED")
    }

    @Test
    fun `모든 대상이 실패해도 COMPLETED - failed = targetCount`() {
        val start = newUserRange(3)
        (start until start + 3).forEach { provider.script(it, "email", status(403)) }

        val c = createCampaignOk(targetCount = 3, channel = "EMAIL", userIdStart = start)
        val done = awaitCompleted(c["campaignId"].asLong())

        assertThat(done["requested"].asLong()).isEqualTo(0L)
        assertThat(done["sent"].asLong()).isEqualTo(0L)
        assertThat(done["failed"].asLong()).isEqualTo(3L)
        assertThat(done.textOrNull("completedAt")).isNotNull()
    }

    @Test
    fun `대상 범위 밖으로는 보내지 않는다 - 대상마다 정확히 한 번`() {
        val start = newUserRange(8)

        val c = createCampaignOk(targetCount = 8, channel = "PUSH", userIdStart = start)
        awaitCompleted(c["campaignId"].asLong())

        (start until start + 8).forEach { assertThat(provider.callsFor(it)).describedAs("user $it").hasSize(1) }
        assertThat(provider.callsFor(start - 1)).isEmpty()
        assertThat(provider.callsFor(start + 8)).isEmpty()
    }
}
