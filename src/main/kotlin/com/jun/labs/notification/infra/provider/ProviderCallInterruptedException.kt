package com.jun.labs.notification.infra.provider

/**
 * 발송사 호출 중 스레드가 인터럽트됐다(종료 중) — 재시도하지 않고 올려서 그 트랜잭션을 롤백한다.
 * 발송사가 이미 접수했을 수 있어도 기록은 남지 않는다(ADR-000 §3 '커밋 전 다운'과 같은 모습). 인터럽트 상태는 보존한다.
 */
class ProviderCallInterruptedException(cause: Throwable? = null) : RuntimeException("발송사 호출 중 인터럽트", cause)
