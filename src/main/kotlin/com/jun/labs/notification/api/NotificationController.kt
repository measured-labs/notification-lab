package com.jun.labs.notification.api

import com.jun.labs.notification.domain.ErrorCode
import com.jun.labs.notification.domain.NotificationException
import com.jun.labs.notification.service.CampaignResult
import com.jun.labs.notification.service.CampaignUseCase
import com.jun.labs.notification.service.GetNotificationUseCase
import com.jun.labs.notification.service.NotificationResult
import com.jun.labs.notification.service.SendNotificationUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** ADR-000 §6 — 경로 id · 쿼리는 문자열로 받아 직접 판정한다(+5 · -1 · abc · Long 범위 밖 = 400) */
@RestController
class NotificationController(
    private val sendNotificationUseCase: SendNotificationUseCase,
    private val getNotificationUseCase: GetNotificationUseCase,
    private val campaignUseCase: CampaignUseCase,
) {
    /** 발송사 실패는 HTTP 오류가 아니다 — 201 안에 그 채널이 FAILED로 들어간다 */
    @PostMapping("/notifications")
    @ResponseStatus(HttpStatus.CREATED)
    fun send(@RequestBody(required = false) request: SendNotificationRequest?): List<NotificationResult> =
        sendNotificationUseCase.send((request ?: throw noBody()).toCommand())

    @GetMapping("/notifications/{notificationId}")
    fun get(@PathVariable notificationId: String): NotificationResult =
        getNotificationUseCase.get(parsePathId(notificationId, "notificationId"))

    @GetMapping("/notifications")
    fun findByEventId(@RequestParam(required = false) eventId: String?): List<NotificationResult> =
        getNotificationUseCase.findByEventId(parseEventId(eventId))

    /** 202 = 접수 시점 스냅샷(RUNNING · 수 0) — 진행은 GET으로 본다 */
    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun startCampaign(@RequestBody(required = false) request: StartCampaignRequest?): CampaignResult =
        campaignUseCase.start((request ?: throw noBody()).toCommand())

    @GetMapping("/campaigns/{campaignId}")
    fun getCampaign(@PathVariable campaignId: String): CampaignResult =
        campaignUseCase.get(parsePathId(campaignId, "campaignId"))

    private fun noBody() = NotificationException(ErrorCode.INVALID_REQUEST, "본문 없음")
}
