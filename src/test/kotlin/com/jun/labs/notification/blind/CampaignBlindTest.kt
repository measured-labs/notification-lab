package com.jun.labs.notification.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 캠페인 — 실제 mock 발송사(장애 없음). ADR-000 §2(대상 = userIdStart부터 targetCount명) · §2.1 ⑤⑥ · §3(접수 · 디스패치 · 완료) · §5 · §6.
 * 진행 수 requested · sent · failed = 그 캠페인 알림 행을 상태(REQUESTED · SENT · FAILED)별로 센 값.
 * REQUESTED 행은 대상 트랜잭션 안에만 있어 밖에서는 0으로 보인다 — 진행 중 requested > 0은 기대하지 않는다.
 */
class CampaignBlindTest : HealthyProviderBlindTest() {

    @Test
    fun `접수 - 202, 접수 시점 스냅샷(RUNNING, 수 0, completedAt 없음), 캠페인 행 RUNNING 으로 저장`() {
        val start = newUserRange(3)
        val name = newCampaignName()

        val c = createCampaignOk(targetCount = 3, channel = "EMAIL", userIdStart = start, name = name, title = "제목", body = "본문")
        val id = c["campaignId"].asLong()

        val row = campaignRow(id)
        assertThat(row.str("name")).isEqualTo(name)
        assertThat(row.str("channel")).isEqualTo("EMAIL")
        assertThat(row.long("user_id_start")).isEqualTo(start)
        assertThat(row.long("target_count")).isEqualTo(3L)
        assertThat(row.str("title")).isEqualTo("제목")
        assertThat(row.str("body")).isEqualTo("본문")
        assertThat(row.time("created_at")).isEqualTo(now())
        assertThat(row.str("status")).isIn("RUNNING", "COMPLETED")
        awaitCompleted(id)
    }

    /** 202 본문은 진행 상황이 아니라 접수 시점 스냅샷이다(ADR §6) — 끝나는 속도와 무관하게 RUNNING · 수 0 · completedAt 없음. */
    @Test
    fun `202 본문은 언제나 접수 시점 스냅샷 - RUNNING, requested · sent · failed 0, completedAt 없음`() {
        val c = createCampaignOk(targetCount = 1)
        awaitCompleted(c["campaignId"].asLong())
    }

    @Test
    fun `완료 - COMPLETED, requested 0 · sent = targetCount · failed 0, completedAt`() {
        val start = newUserRange(5)
        val c = createCampaignOk(targetCount = 5, channel = "SMS", userIdStart = start, title = "쿠폰", body = "쿠폰이 도착했습니다")
        val id = c["campaignId"].asLong()

        val done = awaitCompleted(id)

        assertThat(done.fieldNames().asSequence().toSet()).containsAll(CAMPAIGN_FIELDS.filter { it != "completedAt" })
        assertThat(done["campaignId"].asLong()).isEqualTo(id)
        assertThat(done["targetCount"].asLong()).isEqualTo(5L)
        assertThat(done["requested"].asLong()).isEqualTo(0L)
        assertThat(done["sent"].asLong()).isEqualTo(5L)
        assertThat(done["failed"].asLong()).isEqualTo(0L)
        assertThat(done.instant("createdAt")).isEqualTo(now())
        assertThat(done.instant("completedAt")).isEqualTo(now())
        val row = campaignRow(id)
        assertThat(row.str("status")).isEqualTo("COMPLETED")
        assertThat(row.time("completed_at")).isEqualTo(now())
    }

    @Test
    fun `완료 - 대상마다 알림 행 1개(kind CAMPAIGN), 대상 id 범위 그대로, 모두 SENT`() {
        val start = newUserRange(6)
        val c = createCampaignOk(targetCount = 6, channel = "PUSH", userIdStart = start, title = "세일", body = "세일 시작")
        val id = c["campaignId"].asLong()
        awaitCompleted(id)

        val rows = rowsByCampaign(id)

        assertThat(rows).hasSize(6)
        assertThat(rows.map { it.long("user_id") }).containsExactlyInAnyOrderElementsOf((start until start + 6).toList())
        rows.forEach { r ->
            assertThat(r.str("kind")).isEqualTo("CAMPAIGN")
            assertThat(r.long("campaign_id")).isEqualTo(id)
            assertThat(r["event_id"]).isNull()
            assertThat(r["event_type"]).isNull()
            assertThat(r.str("channel")).isEqualTo("PUSH")
            assertThat(r.str("title")).isEqualTo("세일")
            assertThat(r.str("body")).isEqualTo("세일 시작")
            assertThat(r.str("status")).isEqualTo("SENT")
            assertThat(r.long("attempts")).isEqualTo(1L)
            assertThat(r.str("provider_message_id")).isNotBlank()
            assertThat(r.time("requested_at")).isEqualTo(now())
        }
        assertThat(rows.map { it.str("provider_message_id") }).doesNotHaveDuplicates()
        // 범위 밖 사용자에게는 보내지 않는다
        assertThat(rowsByUser(start - 1)).isEmpty()
        assertThat(rowsByUser(start + 6)).isEmpty()
    }

    @Test
    fun `캠페인 알림 단건 조회 - kind CAMPAIGN, campaignId, eventId · eventType 없음`() {
        val c = createCampaignOk(targetCount = 2, channel = "EMAIL")
        val id = c["campaignId"].asLong()
        awaitCompleted(id)
        val rowId = rowsByCampaign(id).first().long("id")

        val res = getNotification(rowId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        val n = res.json
        assertThat(n.textOrNull("kind")).isEqualTo("CAMPAIGN")
        assertThat(n["campaignId"].asLong()).isEqualTo(id)
        assertThat(n.textOrNull("eventId")).isNull()
        assertThat(n.textOrNull("eventType")).isNull()
        assertThat(n.textOrNull("channel")).isEqualTo("EMAIL")
        assertSent(n, 1)
        assertMatchesRow(n)
    }

    @Test
    fun `대상 끝 경계 - userIdStart + targetCount - 1 = Long 최대는 받는다`() {
        val c = createCampaignOk(targetCount = 2, userIdStart = Long.MAX_VALUE - 1)
        val id = c["campaignId"].asLong()

        val done = awaitCompleted(id)

        assertThat(done["sent"].asLong() + done["failed"].asLong()).isEqualTo(2L)
        assertThat(rowsByCampaign(id).map { it.long("user_id") }).containsExactlyInAnyOrder(Long.MAX_VALUE - 1, Long.MAX_VALUE)
    }

    @Test
    fun `캠페인 조회 - 없는 id 는 404 CAMPAIGN_NOT_FOUND`() {
        assertError(getCampaign(Long.MAX_VALUE), 404, "CAMPAIGN_NOT_FOUND")
    }

    @Test
    fun `캠페인 알림은 트랜잭셔널 eventId 목록에 섞이지 않는다`() {
        val eventId = newEventId()
        sendOk(eventId = eventId)
        val c = createCampaignOk(targetCount = 2)
        awaitCompleted(c["campaignId"].asLong())

        val res = listByEvent(eventId)

        assertThat(res.json.size()).isEqualTo(1)
        assertThat(res.json[0].textOrNull("kind")).isEqualTo("TRANSACTIONAL")
    }
}
