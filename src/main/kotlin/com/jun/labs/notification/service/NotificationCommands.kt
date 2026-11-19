package com.jun.labs.notification.service

import com.jun.labs.notification.domain.Channel

/** 트랜잭셔널 알림 — channels 순서대로 1행씩 발송 */
data class SendNotificationCommand(
    val eventId: String,
    val eventType: String,
    val userId: Long,
    val channels: List<Channel>,
    val title: String,
    val body: String,
)

/** 캠페인 — 사용자 id userIdStart부터 targetCount명 */
data class StartCampaignCommand(
    val name: String,
    val channel: Channel,
    val userIdStart: Long,
    val targetCount: Int,
    val title: String,
    val body: String,
)
