package com.jun.labs.notification.service.impl

import com.jun.labs.notification.service.CampaignResult
import com.jun.labs.notification.service.CampaignUseCase
import com.jun.labs.notification.service.StartCampaignCommand
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 트랜잭션을 열지 않는다 — 접수 커밋이 끝난 뒤에 디스패처로 넘긴다 */
@Service
class CampaignService(
    private val campaignTransactions: CampaignTransactions,
    private val campaignDispatcher: CampaignDispatcher,
) : CampaignUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun start(command: StartCampaignCommand): CampaignResult {
        val (snapshot, plan) = campaignTransactions.accept(command)
        try {
            campaignDispatcher.dispatch(plan)
        } catch (e: Throwable) {
            // 접수는 이미 커밋됐다 — 캠페인은 RUNNING으로 남고 아무도 보내지 않는다
            log.error("캠페인 {} 접수 커밋 뒤 디스패치 시작 실패 — RUNNING으로 남는다", plan.campaignId, e)
            throw e
        }
        return snapshot
    }

    override fun get(campaignId: Long): CampaignResult = campaignTransactions.get(campaignId)
}
