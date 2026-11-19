package com.jun.labs.notification.service.impl

import com.jun.labs.notification.domain.Campaign
import com.jun.labs.notification.domain.Channel
import com.jun.labs.notification.domain.ErrorCode
import com.jun.labs.notification.domain.Notification
import com.jun.labs.notification.domain.NotificationException
import com.jun.labs.notification.domain.repository.CampaignRepository
import com.jun.labs.notification.domain.repository.NotificationRepository
import com.jun.labs.notification.service.CampaignResult
import com.jun.labs.notification.service.StartCampaignCommand
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** 디스패처가 넘겨받는 캠페인 내용 — 대상마다 캠페인 행을 다시 읽지 않는다 */
data class CampaignPlan(
    val campaignId: Long,
    val channel: Channel,
    val userIdStart: Long,
    val targetCount: Int,
    val title: String,
    val body: String,
)

/** 캠페인 쪽 트랜잭션 경계 — 접수 · 대상 1명 · 완료가 각각 한 트랜잭션 */
@Component
class CampaignTransactions(
    private val campaignRepository: CampaignRepository,
    private val notificationRepository: NotificationRepository,
    private val providerDelivery: ProviderDelivery,
    private val clock: Clock,
) {
    /** 접수 — RUNNING으로 커밋하고 접수 시점 스냅샷(수 0)을 돌려준다 */
    @Transactional
    fun accept(command: StartCampaignCommand): Pair<CampaignResult, CampaignPlan> {
        val campaign = campaignRepository.save(
            Campaign.create(
                command.name, command.channel, command.userIdStart, command.targetCount, command.title, command.body, clock.instant(),
            ),
        )
        val id = checkNotNull(campaign.id)
        return CampaignResult.of(campaign, emptyMap()) to
            CampaignPlan(id, campaign.channel, campaign.userIdStart, campaign.targetCount, campaign.title, campaign.body)
    }

    /** 대상 1명 — INSERT(CAMPAIGN) → 발송사 호출(즉시 재시도) → SENT · FAILED → 커밋 */
    @Transactional
    fun sendTarget(plan: CampaignPlan, userId: Long) {
        val notification = notificationRepository.save(
            Notification.forCampaign(plan.campaignId, userId, plan.channel, plan.title, plan.body, clock.instant()),
        )
        providerDelivery.deliver(notification)
    }

    @Transactional
    fun complete(campaignId: Long) {
        val campaign = campaignRepository.findById(campaignId) ?: error("캠페인 없음: $campaignId")
        campaign.complete(clock.instant())
    }

    @Transactional(readOnly = true)
    fun get(campaignId: Long): CampaignResult {
        val campaign = campaignRepository.findById(campaignId) ?: throw NotificationException(ErrorCode.CAMPAIGN_NOT_FOUND)
        val counts = notificationRepository.countByStatusOfCampaign(campaignId).associate { it.status to it.count }
        return CampaignResult.of(campaign, counts)
    }
}
