package com.jun.labs.notification.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/**
 * 입력 검증 — ADR-000 §6.1 표 전부 + test-contract §4(정수 자리는 JSON 정수만 · 문자열 자리는 JSON 문자열만 · 모르는 필드 ·
 * 제어 문자 · 짝 없는 서로게이트 · 길이는 코드 포인트). 위반 = 400 INVALID_REQUEST {code, message} · 아무것도 저장 · 발송하지 않음.
 * "발송하지 않음"은 시나리오 발송사 대역의 호출 수로 확인한다.
 */
class ValidationBlindTest : ScriptedProviderBlindTest() {

    data class Case(val label: String, val fields: List<Pair<String, Any?>>) {
        override fun toString() = label
    }

    private fun resolve(fields: List<Pair<String, Any?>>, userId: Long, eventId: String, name: String): String =
        json(*fields.map { (k, v) ->
            k to when {
                v === FRESH_USER -> userId
                v === FRESH_EVENT -> eventId
                v === FRESH_NAME -> name
                else -> v
            }
        }.toTypedArray())

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidNotificationCases")
    fun `트랜잭셔널 발송 입력 위반 - 400, 저장 · 발송 없음`(case: Case) {
        val userId = newUserId()
        val eventId = newEventId()
        val callsBefore = provider.totalCalls()

        val res = app.post("/notifications", resolve(case.fields, userId, eventId, ""))

        assertError(res, 400, "INVALID_REQUEST")
        assertThat(rowsByUser(userId)).isEmpty()
        assertThat(rowsByEvent(eventId)).isEmpty()
        assertThat(provider.totalCalls()).describedAs("발송사 호출 없음").isEqualTo(callsBefore)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCampaignCases")
    fun `캠페인 입력 위반 - 400, 캠페인 · 알림 저장 없음, 발송 없음`(case: Case) {
        val userId = newUserRange(3)
        val name = newCampaignName()
        val campaignsBefore = count("campaign")
        val callsBefore = provider.totalCalls()

        val res = app.post("/campaigns", resolve(case.fields, userId, "", name))

        assertError(res, 400, "INVALID_REQUEST")
        assertThat(count("campaign")).isEqualTo(campaignsBefore)
        assertThat(countWhere("select count(*) from campaign where name = ?", name)).isEqualTo(0L)
        Thread.sleep(200) // 접수됐다면 디스패처가 보냈을 시간
        assertThat(rowsByUser(userId)).isEmpty()
        assertThat(provider.totalCalls()).describedAs("발송사 호출 없음").isEqualTo(callsBefore)
    }

    @ParameterizedTest(name = "본문=[{0}]")
    @ValueSource(strings = ["{", "abc", "[]", "null", "", """{"eventId": }""", "[1, 2", "\"text\""])
    fun `읽을 수 없거나 객체가 아닌 JSON 본문 - 발송 · 캠페인 모두 400`(body: String) {
        val notificationsBefore = count("notification")
        val campaignsBefore = count("campaign")
        val callsBefore = provider.totalCalls()

        assertError(app.post("/notifications", body), 400, "INVALID_REQUEST")
        assertError(app.post("/campaigns", body), 400, "INVALID_REQUEST")

        assertThat(count("notification")).isEqualTo(notificationsBefore)
        assertThat(count("campaign")).isEqualTo(campaignsBefore)
        assertThat(provider.totalCalls()).isEqualTo(callsBefore)
    }

    /** 올바른 객체 뒤에 토큰이 남거나 같은 키가 두 번 나오면 읽을 수 없는 본문(test-contract §4) — 400 · 저장 · 발송 없음. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["뒤에 xyz", "객체 두 개", "뒤에 }", "뒤에 쉼표", "뒤에 숫자", "같은 키 두 번(다른 값)", "같은 키 두 번(같은 값)"])
    fun `본문 뒤 남은 토큰 · 같은 키 두 번 - 발송 · 캠페인 모두 400`(label: String) {
        val userId = newUserId()
        val eventId = newEventId()
        val start = newUserRange(2)
        val name = newCampaignName()
        val notification = json(
            "eventId" to eventId, "eventType" to "ORDER_PAID", "userId" to userId,
            "channels" to listOf("PUSH"), "title" to "제목", "body" to "본문",
        )
        val campaign = json(
            "name" to name, "channel" to "PUSH", "userIdStart" to start, "targetCount" to 2,
            "title" to "제목", "body" to "본문",
        )
        fun malformed(valid: String, dupKey: String, dupOther: String, dupSame: String): String = when (label) {
            "뒤에 xyz" -> "$valid xyz"
            "객체 두 개" -> valid + valid
            "뒤에 }" -> "$valid}"
            "뒤에 쉼표" -> "$valid,"
            "뒤에 숫자" -> "$valid 1"
            "같은 키 두 번(다른 값)" -> valid.dropLast(1) + ",\"$dupKey\":$dupOther}"
            "같은 키 두 번(같은 값)" -> valid.dropLast(1) + ",\"$dupKey\":$dupSame}"
            else -> error(label)
        }
        val campaignsBefore = count("campaign")
        val callsBefore = provider.totalCalls()

        assertError(app.post("/notifications", malformed(notification, "userId", "${userId + 1}", "$userId")), 400, "INVALID_REQUEST")
        assertError(app.post("/campaigns", malformed(campaign, "targetCount", "1", "2")), 400, "INVALID_REQUEST")

        Thread.sleep(200) // 접수됐다면 디스패처가 보냈을 시간
        assertThat(rowsByEvent(eventId)).isEmpty()
        assertThat(rowsByUser(userId)).isEmpty()
        assertThat(rowsByUser(userId + 1)).isEmpty()
        assertThat(rowsByUser(start)).isEmpty()
        assertThat(countWhere("select count(*) from campaign where name = ?", name)).isEqualTo(0L)
        assertThat(count("campaign")).isEqualTo(campaignsBefore)
        assertThat(provider.totalCalls()).describedAs("발송사 호출 없음").isEqualTo(callsBefore)
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("badPathIds")
    fun `경로 id 가 양의 정수 · Long 범위가 아니면 400`(endpoint: String, rawId: String) {
        val res = when (endpoint) {
            "GET notification" -> getNotification(rawId)
            "GET campaign" -> getCampaign(rawId)
            else -> error(endpoint)
        }
        assertError(res, 400, "INVALID_REQUEST")
    }

    // ---- 경계 안쪽은 받는다 ----

    @Test
    fun `최대 길이 - eventId 64 · eventType 50 · title 100 · body 1000 자는 201`() {
        val userId = newUserId()
        val eventId = newEventId().take(40).padEnd(64, 'x')
        val res = send(eventId, "t".repeat(50), userId, listOf("PUSH"), "a".repeat(100), "b".repeat(1000))

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertSent(res.json[0], 1)
        assertThat(rowsByEvent(eventId)).hasSize(1)
    }

    @Test
    fun `길이는 코드 포인트로 센다 - 이모지(UTF-16 2자) 64 · 50 · 100 · 1000개는 201`() {
        val userId = newUserId()
        val e = "😀"
        val eventId = e.repeat(54) + newEventId().takeLast(10)
        val res = send(eventId, e.repeat(50), userId, listOf("SMS"), e.repeat(100), e.repeat(1000))

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertSent(res.json[0], 1)
        val row = rowsByEvent(eventId).single()
        assertThat(row.str("title")).isEqualTo(e.repeat(100))
        assertThat(row.str("body")).isEqualTo(e.repeat(1000))
        assertThat(provider.callsFor(userId).single().title).isEqualTo(e.repeat(100))
    }

    @Test
    fun `userId Long 최대 · 채널 세 개(중복 없음)는 201`() {
        val eventId = newEventId()
        val res = send(eventId = eventId, userId = Long.MAX_VALUE, channels = listOf("SMS", "EMAIL", "PUSH"))

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertThat(res.json.map { it["userId"].asLong() }).containsOnly(Long.MAX_VALUE)
        assertThat(rowsByEvent(eventId)).hasSize(3)
    }

    @Test
    fun `캠페인 최대 길이(코드 포인트) - name 100 · title 100 · body 1000 이모지는 202`() {
        val e = "😀"
        val start = newUserRange(1)
        val res = createCampaign(e.repeat(100), "EMAIL", start, 1, e.repeat(100), e.repeat(1000))

        assertThat(res.status).describedAs(res.body).isEqualTo(202)
        val id = res.json["campaignId"].asLong()
        awaitCompleted(id)
        assertThat(campaignRow(id).str("name")).isEqualTo(e.repeat(100))
        assertThat(rowsByCampaign(id).single().str("body")).isEqualTo(e.repeat(1000))
    }

    @Test
    fun `캠페인 userIdStart Long 최대 · targetCount 1 은 202`() {
        val res = createCampaign(userIdStart = Long.MAX_VALUE, targetCount = 1)

        assertThat(res.status).describedAs(res.body).isEqualTo(202)
        val id = res.json["campaignId"].asLong()
        awaitCompleted(id)
        assertThat(rowsByCampaign(id).single().long("user_id")).isEqualTo(Long.MAX_VALUE)
    }

    companion object {
        private val FRESH_USER = Any()
        private val FRESH_EVENT = Any()
        private val FRESH_NAME = Any()

        private fun notificationCase(label: String, vararg overrides: Pair<String, Any?>): Case {
            val base = linkedMapOf<String, Any?>(
                "eventId" to FRESH_EVENT,
                "eventType" to "ORDER_PAID",
                "userId" to FRESH_USER,
                "channels" to listOf("PUSH"),
                "title" to "제목",
                "body" to "본문",
            )
            overrides.forEach { (k, v) -> base[k] = v }
            return Case(label, base.entries.filter { it.value !== Omit }.map { it.key to it.value })
        }

        private fun campaignCase(label: String, vararg overrides: Pair<String, Any?>): Case {
            val base = linkedMapOf<String, Any?>(
                "name" to FRESH_NAME,
                "channel" to "PUSH",
                "userIdStart" to FRESH_USER,
                "targetCount" to 3,
                "title" to "제목",
                "body" to "본문",
            )
            overrides.forEach { (k, v) -> base[k] = v }
            return Case(label, base.entries.filter { it.value !== Omit }.map { it.key to it.value })
        }

        /** 문자열 필드 공통 위반(필드 이름, 최대 길이). */
        private fun stringViolations(field: String, max: Int, make: (String, Array<Pair<String, Any?>>) -> Case): List<Case> = listOf(
            make("$field 생략", arrayOf(field to Omit)),
            make("$field null", arrayOf(field to null)),
            make("$field 빈 문자열", arrayOf(field to "")),
            make("$field 공백", arrayOf(field to "   ")),
            make("$field 탭 · 줄바꿈만", arrayOf(field to " \t\n ")),
            make("$field ${max + 1}자", arrayOf(field to "x".repeat(max + 1))),
            make("$field 이모지 ${max + 1}개(코드 포인트)", arrayOf(field to "😀".repeat(max + 1))),
            make("$field 숫자", arrayOf(field to 123)),
            make("$field 불리언", arrayOf(field to true)),
            make("$field 배열", arrayOf(field to listOf("a"))),
            make("$field 객체", arrayOf(field to mapOf("a" to "b"))),
            make("$field 제어 문자 U+0000", arrayOf(field to "a\u0000b")),
            make("$field 제어 문자 U+0001", arrayOf(field to "a\u0001b")),
            make("$field 제어 문자 U+001B", arrayOf(field to "a\u001Bb")),
            make("$field 제어 문자 U+007F", arrayOf(field to "a\u007Fb")),
            make("$field 제어 문자 U+009F", arrayOf(field to "a\u009Fb")),
            make("$field 줄바꿈 포함", arrayOf(field to "a\nb")),
            make("$field 탭 포함", arrayOf(field to "a\tb")),
            make("$field 짝 없는 high 서로게이트", arrayOf(field to Raw("\"a\\uD83Db\""))),
            make("$field 짝 없는 low 서로게이트", arrayOf(field to Raw("\"a\\uDE00b\""))),
            make("$field 뒤바뀐 서로게이트 쌍", arrayOf(field to Raw("\"\\uDE00\\uD83D\""))),
            make("$field 끝에 남은 high 서로게이트", arrayOf(field to Raw("\"abc\\uD83D\""))),
        )

        private fun positiveIntegerViolations(field: String, make: (String, Array<Pair<String, Any?>>) -> Case): List<Case> = listOf(
            make("$field 생략", arrayOf(field to Omit)),
            make("$field null", arrayOf(field to null)),
            make("$field 0", arrayOf(field to 0)),
            make("$field 음수", arrayOf(field to -1)),
            make("$field 소수 1.5", arrayOf(field to 1.5)),
            make("$field 정수 모양 소수 1.0", arrayOf(field to Raw("1.0"))),
            make("$field 지수 표기 1e3", arrayOf(field to Raw("1e3"))),
            make("$field 문자열 \"1\"", arrayOf(field to "1")),
            make("$field true", arrayOf(field to true)),
            make("$field 배열", arrayOf(field to listOf(1))),
            make("$field Long 초과", arrayOf(field to Raw("9223372036854775808"))),
            make("$field Long 최소", arrayOf(field to Long.MIN_VALUE)),
        )

        @JvmStatic
        fun invalidNotificationCases(): Stream<Case> {
            val n: (String, Array<Pair<String, Any?>>) -> Case = { label, o -> notificationCase(label, *o) }
            return (
                stringViolations("eventId", 64, n) +
                    stringViolations("eventType", 50, n) +
                    stringViolations("title", 100, n) +
                    stringViolations("body", 1000, n) +
                    positiveIntegerViolations("userId", n) +
                    listOf(
                        notificationCase("channels 생략", "channels" to Omit),
                        notificationCase("channels null", "channels" to null),
                        notificationCase("channels 빈 배열", "channels" to emptyList<String>()),
                        notificationCase("channels 중복", "channels" to listOf("PUSH", "PUSH")),
                        notificationCase("channels 중복(세 개 중)", "channels" to listOf("SMS", "EMAIL", "SMS")),
                        notificationCase("channels 4개", "channels" to listOf("PUSH", "SMS", "EMAIL", "PUSH")),
                        notificationCase("channels 소문자", "channels" to listOf("push")),
                        notificationCase("channels 모르는 채널", "channels" to listOf("FAX")),
                        notificationCase("channels 빈 문자열 원소", "channels" to listOf("")),
                        notificationCase("channels null 원소", "channels" to listOf(null)),
                        notificationCase("channels 숫자 원소", "channels" to listOf(1)),
                        notificationCase("channels 앞뒤 공백 원소", "channels" to listOf(" PUSH")),
                        notificationCase("channels 배열 아닌 문자열", "channels" to "PUSH"),
                        notificationCase("channels 객체", "channels" to mapOf("PUSH" to true)),
                        notificationCase("모르는 필드", "priority" to "HIGH"),
                        notificationCase("모르는 필드 null", "campaignId" to null),
                    )
                ).stream()
        }

        @JvmStatic
        fun invalidCampaignCases(): Stream<Case> {
            val c: (String, Array<Pair<String, Any?>>) -> Case = { label, o -> campaignCase(label, *o) }
            return (
                stringViolations("name", 100, c) +
                    stringViolations("title", 100, c) +
                    stringViolations("body", 1000, c) +
                    positiveIntegerViolations("userIdStart", c) +
                    listOf(
                        campaignCase("channel 생략", "channel" to Omit),
                        campaignCase("channel null", "channel" to null),
                        campaignCase("channel 빈 문자열", "channel" to ""),
                        campaignCase("channel 소문자", "channel" to "push"),
                        campaignCase("channel 모르는 채널", "channel" to "FAX"),
                        campaignCase("channel 배열", "channel" to listOf("PUSH")),
                        campaignCase("channel 숫자", "channel" to 1),
                        campaignCase("channel 불리언", "channel" to true),
                        campaignCase("targetCount 생략", "targetCount" to Omit),
                        campaignCase("targetCount null", "targetCount" to null),
                        campaignCase("targetCount 0", "targetCount" to 0),
                        campaignCase("targetCount 음수", "targetCount" to -1),
                        campaignCase("targetCount 1,000,001", "targetCount" to 1_000_001),
                        campaignCase("targetCount Int 최대", "targetCount" to Int.MAX_VALUE),
                        campaignCase("targetCount Long 초과", "targetCount" to Raw("9223372036854775808")),
                        campaignCase("targetCount 소수 1.5", "targetCount" to 1.5),
                        campaignCase("targetCount 정수 모양 소수 2.0", "targetCount" to Raw("2.0")),
                        campaignCase("targetCount 문자열", "targetCount" to "3"),
                        campaignCase("targetCount true", "targetCount" to true),
                        campaignCase("범위 끝 Long 초과 - start Long 최대 · count 2", "userIdStart" to Long.MAX_VALUE, "targetCount" to 2),
                        campaignCase("범위 끝 Long 초과 - start Long 최대-9 · count 11", "userIdStart" to Long.MAX_VALUE - 9, "targetCount" to 11),
                        campaignCase("모르는 필드", "priority" to "LOW"),
                        campaignCase("모르는 필드 channels", "channels" to listOf("PUSH")),
                    )
                ).stream()
        }

        private val BAD_IDS = listOf("+5", "-1", "0", "00", "abc", "1.5", "1e3", "9223372036854775808")

        @JvmStatic
        fun badPathIds(): Stream<Arguments> =
            listOf("GET notification", "GET campaign").flatMap { e -> BAD_IDS.map { Arguments.of(e, it) } }.stream()
    }
}
