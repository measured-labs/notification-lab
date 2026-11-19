package com.jun.labs.notification.domain.repository

import com.jun.labs.notification.domain.Campaign
import org.springframework.data.repository.Repository

/** save · find만 — 삭제는 노출하지 않는다 */
interface CampaignRepository : Repository<Campaign, Long> {
    fun save(campaign: Campaign): Campaign

    fun findById(id: Long): Campaign?
}
