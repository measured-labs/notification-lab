package com.jun.labs.notification.domain

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 입력 규칙(ADR-000 §6.1) — API와 도메인이 같은 함수를 쓴다.
 * 도메인 팩토리는 상태를 만들기 전에 이 검사를 부른다(API 밖 진입점도 같은 규칙).
 */
object NotificationRules {
    const val EVENT_ID_MAX = 64
    const val EVENT_TYPE_MAX = 50
    const val TITLE_MAX = 100
    const val BODY_MAX = 1000
    const val NAME_MAX = 100
    const val CHANNELS_MAX = 3
    const val TARGET_COUNT_MAX = 1_000_000
    const val PROVIDER_MESSAGE_ID_MAX = 64
    const val LAST_ERROR_MAX = 200

    fun checkUserId(userId: Long, field: String = "userId") {
        if (userId < 1) throw invalid("${field}는 1 이상의 정수")
    }

    /** 1~1,000,000 · 마지막 대상 id(userIdStart + targetCount − 1)가 Long 범위 안 */
    fun checkTargets(userIdStart: Long, targetCount: Int) {
        checkUserId(userIdStart, "userIdStart")
        if (targetCount !in 1..TARGET_COUNT_MAX) throw invalid("targetCount는 1~${TARGET_COUNT_MAX}의 정수")
        if (userIdStart > Long.MAX_VALUE - (targetCount - 1)) throw invalid("userIdStart + targetCount − 1이 Long 최대를 넘습니다")
    }

    /** 채널 1~3개 · 중복 없음 */
    fun checkChannels(channels: List<Channel>) {
        if (channels.size !in 1..CHANNELS_MAX) throw invalid("channels는 1~${CHANNELS_MAX}개")
        if (channels.toSet().size != channels.size) throw invalid("channels에 중복이 있습니다")
    }

    /** 공백 아님 · 1~max자(문자 수 = 코드 포인트 — VARCHAR(n)과 같은 셈) · 제어 문자 · 짝 없는 서로게이트 없음 */
    fun checkText(value: String, field: String, max: Int) {
        if (value.isBlank() || value.codePointCount(0, value.length) > max) throw invalid("${field}는 공백 아님 · 1~${max}자")
        if (!isStorable(value)) throw invalid("${field}에 제어 문자 · 짝 없는 서로게이트를 쓸 수 없습니다")
    }

    /** 발송사 메시지 id로 저장할 수 있다 — 공백 아님 · 1~64자(코드 포인트) · 저장 가능한 문자 */
    fun isValidProviderMessageId(value: String): Boolean = fitsColumn(value, PROVIDER_MESSAGE_ID_MAX)

    /** last_error로 저장할 수 있다 — 공백 아님 · 1~200자(코드 포인트) · 저장 가능한 문자 */
    fun isValidLastError(value: String): Boolean = fitsColumn(value, LAST_ERROR_MAX)

    private fun fitsColumn(value: String, max: Int): Boolean =
        value.isNotBlank() && value.codePointCount(0, value.length) <= max && isStorable(value)

    /** 제어 문자(NUL 포함 — PostgreSQL에 저장할 수 없다) · 짝 없는 서로게이트(UTF-8로 저장할 수 없다)가 없다 */
    fun isStorable(value: String): Boolean =
        // codePoints()는 짝이 맞는 서로게이트를 하나로 합치므로, 남은 서로게이트 코드 포인트는 짝이 없는 것이다
        value.codePoints().noneMatch { Character.isISOControl(it) || it in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code }

    /** DB(TIMESTAMPTZ)가 마이크로초까지 저장하므로 메모리 값도 같은 정밀도로 맞춘다 — 응답과 재조회가 같게 */
    fun normalize(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MICROS)

    private fun invalid(detail: String) = NotificationException(ErrorCode.INVALID_REQUEST, detail)
}
