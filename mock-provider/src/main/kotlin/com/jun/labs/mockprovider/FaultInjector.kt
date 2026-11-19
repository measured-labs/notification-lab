package com.jun.labs.mockprovider

import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom

/** 채널마다 시드 없는 무작위 — 같은 장애를 다시 만들 수 없다(기준선 · ADR-000 §7) */
@Component
class FaultInjector(private val properties: MockProviderProperties) {

    /** 응답 전 지연 ~ U[min, max] */
    fun delay(channel: ProviderChannel) {
        val s = properties.channels.of(channel)
        if (s.latencyMaxMs <= 0) return
        val ms = ThreadLocalRandom.current().nextLong(s.latencyMinMs, s.latencyMaxMs + 1)
        if (ms > 0) Thread.sleep(ms)
    }

    fun shouldError(channel: ProviderChannel): Boolean = hit(properties.channels.of(channel).errorRate)

    /** 접수한 뒤 부른다 — 걸리면 drop-hold-ms 동안 응답을 쥔다 */
    fun holdIfDropped(channel: ProviderChannel) {
        val s = properties.channels.of(channel)
        if (hit(s.dropRate)) Thread.sleep(s.dropHoldMs)
    }

    private fun hit(rate: Double): Boolean = rate > 0.0 && ThreadLocalRandom.current().nextDouble() < rate
}
