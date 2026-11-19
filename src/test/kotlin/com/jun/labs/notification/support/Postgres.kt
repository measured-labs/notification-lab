package com.jun.labs.notification.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer

/** JVM당 PostgreSQL 16 하나 — 테스트 클래스끼리 공유한다(이벤트 id · 사용자 id를 매번 새로 만들어 섞이지 않게) */
object Postgres {
    private val container: PostgreSQLContainer<*> by lazy { PostgreSQLContainer<Nothing>("postgres:16").apply { start() } }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { container.jdbcUrl }
        registry.add("spring.datasource.username") { container.username }
        registry.add("spring.datasource.password") { container.password }
        // 테스트 클래스마다 @DynamicPropertySource가 달라 컨텍스트가 클래스 수만큼 캐시된다 — 기본 풀 10 × 클래스 수가 max_connections 100을 넘지 않게
        registry.add("spring.datasource.hikari.maximum-pool-size") { "4" }
    }
}
