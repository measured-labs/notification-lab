package com.jun.labs.mockprovider

import org.springframework.boot.context.properties.ConfigurationProperties

/** 발송 채널 — 경로 이름(`/v1/push/send`) */
enum class ProviderChannel(val path: String, val defaultQuotaPerMinute: Long) {
    /** FCM 프로젝트당 분당 600,000(README) */
    PUSH("push", 600_000),
    /** 가정 — README 근거 없음 */
    SMS("sms", 60_000),
    /** 가정 — README 근거 없음 */
    EMAIL("email", 120_000);

    companion object {
        fun ofPath(path: String): ProviderChannel? = entries.firstOrNull { it.path == path }
    }
}

/** 기동 설정으로만 바꾼다(관리 API 없음 — ADR-000 §7) · `mockprovider.channels.<push|sms|email>.*` */
@ConfigurationProperties("mockprovider")
data class MockProviderProperties(
    val channels: Channels = Channels(),
) {
    data class Channels(
        val push: ChannelSettings = ChannelSettings(),
        val sms: ChannelSettings = ChannelSettings(),
        val email: ChannelSettings = ChannelSettings(),
    ) {
        fun of(channel: ProviderChannel): ChannelSettings = when (channel) {
            ProviderChannel.PUSH -> push
            ProviderChannel.SMS -> sms
            ProviderChannel.EMAIL -> email
        }
    }

    /** 장애 주입 기본값은 모두 0 · 시드 없는 무작위 */
    data class ChannelSettings(
        /** 생략 = 채널 기본값(ProviderChannel.defaultQuotaPerMinute) — 세 채널이 한 클래스를 쓰므로 null로 '생략'을 구분한다 */
        val quotaPerMinute: Long? = null,
        val latencyMinMs: Long = 0,
        val latencyMaxMs: Long = 0,
        /** 이 확률로 처리하지 않고 500 */
        val errorRate: Double = 0.0,
        /** 이 확률로 접수한 뒤 dropHoldMs 동안 응답하지 않는다(응답 유실) */
        val dropRate: Double = 0.0,
        val dropHoldMs: Long = 10_000,
    ) {
        init {
            require(quotaPerMinute == null || quotaPerMinute >= 0) { "quota-per-minute >= 0" }
            require(latencyMinMs >= 0 && latencyMaxMs >= latencyMinMs) { "latency: 0 <= min <= max" }
            require(errorRate in 0.0..1.0) { "error-rate: 0..1" }
            require(dropRate in 0.0..1.0) { "drop-rate: 0..1" }
            require(dropHoldMs >= 0) { "drop-hold-ms >= 0" }
        }
    }
}
