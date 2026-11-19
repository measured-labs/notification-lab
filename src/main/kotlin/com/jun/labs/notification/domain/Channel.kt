package com.jun.labs.notification.domain

/** 발송 채널 — 발송사 경로는 소문자(`/v1/push/send`) */
enum class Channel {
    PUSH, SMS, EMAIL;

    val path: String get() = name.lowercase()

    companion object {
        /** 대문자 이름 그대로만 받는다 */
        fun parse(raw: String): Channel? = entries.firstOrNull { it.name == raw }
    }
}
