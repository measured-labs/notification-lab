package com.jun.labs.notification.api

import com.jun.labs.notification.domain.Channel
import com.jun.labs.notification.domain.ErrorCode
import com.jun.labs.notification.domain.NotificationException
import com.jun.labs.notification.domain.NotificationRules
import com.jun.labs.notification.service.SendNotificationCommand
import com.jun.labs.notification.service.StartCampaignCommand

/** 필드는 모두 nullable — 빠진 값도 INVALID_REQUEST(400)로 직접 판정한다(ADR-000 §6.1) */
data class SendNotificationRequest(
    val eventId: String? = null,
    val eventType: String? = null,
    val userId: Long? = null,
    val channels: List<String?>? = null,
    val title: String? = null,
    val body: String? = null,
) {
    fun toCommand(): SendNotificationCommand {
        val eventId = text(eventId, "eventId", NotificationRules.EVENT_ID_MAX)
        val eventType = text(eventType, "eventType", NotificationRules.EVENT_TYPE_MAX)
        val userId = userId ?: throw invalid("userId는 1 이상의 정수")
        NotificationRules.checkUserId(userId)
        val channels = (channels ?: throw invalid("channels는 1~${NotificationRules.CHANNELS_MAX}개"))
            .map { raw -> raw?.let(Channel::parse) ?: throw invalid("channels의 값은 PUSH · SMS · EMAIL") }
        NotificationRules.checkChannels(channels)
        val title = text(title, "title", NotificationRules.TITLE_MAX)
        val body = text(body, "body", NotificationRules.BODY_MAX)
        return SendNotificationCommand(eventId, eventType, userId, channels, title, body)
    }
}

data class StartCampaignRequest(
    val name: String? = null,
    val channel: String? = null,
    val userIdStart: Long? = null,
    val targetCount: Int? = null,
    val title: String? = null,
    val body: String? = null,
) {
    fun toCommand(): StartCampaignCommand {
        val name = text(name, "name", NotificationRules.NAME_MAX)
        val channel = channel?.let(Channel::parse) ?: throw invalid("channel은 PUSH · SMS · EMAIL")
        val userIdStart = userIdStart ?: throw invalid("userIdStart는 1 이상의 정수")
        val targetCount = targetCount ?: throw invalid("targetCount는 1~${NotificationRules.TARGET_COUNT_MAX}의 정수")
        NotificationRules.checkTargets(userIdStart, targetCount)
        val title = text(title, "title", NotificationRules.TITLE_MAX)
        val body = text(body, "body", NotificationRules.BODY_MAX)
        return StartCampaignCommand(name, channel, userIdStart, targetCount, title, body)
    }
}

/** 쿼리의 eventId — 본문과 같은 규칙 */
fun parseEventId(raw: String?): String = text(raw, "eventId", NotificationRules.EVENT_ID_MAX)

/** 경로 id — 숫자만 · 양의 정수 · Long 범위 */
fun parsePathId(raw: String, name: String): Long =
    raw.takeIf { DIGITS.matches(it) }?.toLongOrNull()?.takeIf { it > 0 } ?: throw invalid("${name}는 양의 정수")

private fun text(value: String?, field: String, max: Int): String {
    if (value == null) throw invalid("${field}는 공백 아님 · 1~${max}자")
    NotificationRules.checkText(value, field, max)
    return value
}

/** 숫자만 — toLongOrNull은 앞의 '+' · '-'를 받아 준다 */
private val DIGITS = Regex("[0-9]+")

private fun invalid(detail: String) = NotificationException(ErrorCode.INVALID_REQUEST, detail)
