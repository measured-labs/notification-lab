package com.jun.labs.notification.domain

enum class ErrorCode(val message: String) {
    INVALID_REQUEST("요청 값이 올바르지 않습니다."),
    NOTIFICATION_NOT_FOUND("알림을 찾을 수 없습니다."),
    CAMPAIGN_NOT_FOUND("캠페인을 찾을 수 없습니다."),
    /** 계약 밖 서버 오류 — 본문 모양만 {code, message}로 맞춘다 */
    INTERNAL_ERROR("서버 오류가 발생했습니다."),
}

class NotificationException(
    val errorCode: ErrorCode,
    detail: String? = null,
) : RuntimeException(detail ?: errorCode.message)
