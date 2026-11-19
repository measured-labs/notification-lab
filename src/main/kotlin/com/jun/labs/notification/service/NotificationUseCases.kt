package com.jun.labs.notification.service

interface SendNotificationUseCase {
    /** 한 트랜잭션 안에서 채널 순서대로 기록 → 발송(즉시 재시도) → 결과. 발송사 실패는 FAILED 행이지 예외가 아니다 */
    fun send(command: SendNotificationCommand): List<NotificationResult>
}

interface GetNotificationUseCase {
    fun get(notificationId: Long): NotificationResult

    /** id 순 */
    fun findByEventId(eventId: String): List<NotificationResult>
}

interface CampaignUseCase {
    /** 접수 커밋 뒤 디스패처에 넘기고 바로 돌아온다 — 결과는 접수 시점 스냅샷(RUNNING · 수 0) */
    fun start(command: StartCampaignCommand): CampaignResult

    fun get(campaignId: Long): CampaignResult
}
