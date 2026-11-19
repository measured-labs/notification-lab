package com.jun.labs.notification.domain.repository

import com.jun.labs.notification.domain.Notification
import com.jun.labs.notification.domain.NotificationStatus
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.Repository

/** 상태별 알림 수 */
interface StatusCount {
    val status: NotificationStatus
    val count: Long
}

/** save · find만 — 삭제는 노출하지 않는다 */
interface NotificationRepository : Repository<Notification, Long> {
    fun save(notification: Notification): Notification

    fun findById(id: Long): Notification?

    /** 같은 eventId의 알림 — id 순(event_id 인덱스는 일부러 없다) */
    fun findByEventIdOrderByIdAsc(eventId: String): List<Notification>

    /** 캠페인 진행 수 — 저장 카운터 없이 알림 행을 상태별로 센다(한 번 훑기) */
    @Query("select n.status as status, count(n) as count from Notification n where n.campaignId = :campaignId group by n.status")
    fun countByStatusOfCampaign(@Param("campaignId") campaignId: Long): List<StatusCount>
}
