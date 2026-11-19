package com.jun.labs.notification.api

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 모르는 키 · 뒤에 남은 토큰 · 중복 키는 읽기 실패. 정수 자리는 JSON 정수만, 문자열 자리는 JSON 문자열만 받는다 — 1.5 · "1" · true(정수 자리), 1 · 1.5 · true(문자열 자리)는 읽기 실패(400 · ADR-000 §6.1) */
@Configuration
class JacksonConfig {
    @Bean
    fun strictInputCustomizer() = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        // 모르는 키 · 본문 뒤에 남은 토큰 · 같은 키 두 번 = 읽기 실패
        builder.featuresToEnable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            JsonParser.Feature.STRICT_DUPLICATE_DETECTION,
        )
        builder.postConfigurer { mapper ->
            mapper.coercionConfigFor(LogicalType.Integer).apply {
                setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }
            mapper.coercionConfigFor(LogicalType.Textual).apply {
                setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }
        }
    }
}
