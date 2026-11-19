package com.jun.labs.notification.domain

/** 기록만 한다 — 처리 우선순위에는 쓰지 않는다(ADR-000 §2) */
enum class NotificationKind { TRANSACTIONAL, CAMPAIGN }
