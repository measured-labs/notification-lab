# 작업 기록 — notification-lab ADR-000 베이스라인

> 명세: `requirement-spec.md` · 브랜치 `feat/adr-000-baseline`(기준 5df3e48) · stakes 중간 · MODE auto

## 타임라인 (시각 | 사건 | 결과)

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-11-18 | 인터뷰 7문항 · ADR-000 설계 초안 · 설계 선검증(codex medium 4건 반영) — interview.md | 명세 대기 |
| 2026-11-18 23:5x | 명세 작성 · 사용자 "진행하자 그리고 우선 지금 하고 있는 측정은 마무리하고 문서 정리해서 명확히 나누고 노티피케이션을 하면 되니깐" → set-state spec-approved · mode auto · 착수는 concurrency 마감 뒤 | SPEC=1 · MODE=auto |
| 2026-11-19 13:0x | concurrency 마감(PR 7 병합 · 프로필 push) 뒤 착수 · 설계 선검증 생략 근거: 인터뷰 때 수행(codex medium 4건 반영) · 추가 Q/A 8~9는 검사 위치 · 문서 방식이라 새 동작 불변식 없음 | 착수 |
| 2026-11-19 13:1x | 메인 골격: settings에 mock-provider 포함 · build.gradle.kts(JPA · Flyway · PostgreSQL · Testcontainers 1.21.4 · plugin.jpa · allOpen · testImplementation mock-provider) · mock-provider/build.gradle.kts · application.yml(DB 기본값 없음) · support/Postgres · MutableClock · NotificationApplicationTests(Testcontainers) · compileTestKotlin rc=0 · test-contract.md(Clock · mock-provider 메인 클래스 · 설정 키 · 엄격 JSON · 폴링 · 파일 경계) → 구현 워커 ∥ blind 워커 착수 | 착수 |
| 2026-11-19 13:3x | task 01·03 회수(구현 워커 · Opus): impl 26/26 · mock-provider 8/8 · 앱 기동 1/1 · blind 294/295(실패 1 = blind 쪽 빈 테스트 이름 · JUnit PreconditionViolation) · 설계: 재시도 분류는 RestProviderClient(429 · 5xx · 연결 실패 · 읽기 타임아웃 · 기타 I/O) · 캠페인 = 접수 커밋 → 디스패처(캠페인마다 1) → 공용 풀(pool · 큐 pool×2) 블로킹 제출 · 대상마다 트랜잭션 · CountDownLatch 뒤 COMPLETED · 넘긴 판단 7(기본값은 코드 · 쿼터 nullable · 2xx인데 messageId 없음 = FAILED INVALID_RESPONSE · 대상 작업 예외는 로그 뒤 완료로 셈 → COMPLETED인데 행 < targetCount 가능 — ADR §3 · §9에 적고 재현으로 확인 · 경로 형식 등) → 메인: 전부 채택(문서화) | blind 회수 대기 |
| 2026-11-19 13:4x | task 02 회수(blind 워커 · Opus 154,179 토큰): blind 12파일 314건 · ScriptedProvider(호출 수 · 응답 순서를 정한 발송사 대역) + 실제 mock 결합 시험 · 해석 9(requested = REQUESTED 행 수 등) · 지시 명령은 `:test`로 돌려야 함(mock-provider에 같은 필터) | 회수 |
| 2026-11-19 13:5x | 전체 `cleanTest test --continue`(메인 단독): 앱 341/0(blind 314 + impl 26 + 기동 1) · mock-provider 8/0 · red 확인: 재시도 루프 `maxAttempts - 1` → blind 15 · impl 7 실패 → 원복 | green |
| 2026-11-19 14:0x | 로컬 스모크(임시 postgres:16 127.0.0.1:25434 · 앱 · mock-provider jar): 트랜잭셔널 3채널 SENT · attempts 1 · 캠페인 500 → 202 스냅샷 → ~1.3초 COMPLETED · sent 500 · ERROR 0 | 스모크 통과 |
| 2026-11-19 14:1x | 재현 스모크: Q1a 같은 eventId 2회 → SENT 2 · Q1b sms drop 1.0 · 읽기 타임아웃 5초 → FAILED · attempts 4 · READ_TIMEOUT · 20.1초(발송사는 설계상 4건 접수 — 사용자는 4통 · 우리 기록은 실패) · Q2 email 쿼터 100/분에 200건 → SENT 100 · FAILED 100(attempts 합 400) → 요청 200에 발송사 호출 500(2.5배, 재시도 300 모두 같은 창 429) · Q5 email 지연 2초 × 30 동시 → 2.04 / 4.07 / 6.09초(커넥션 10개 3바퀴) · 그 사이 PUSH 첫 요청 3.59초(평소 0.03) · Q3 캠페인 10만(push 지연 5~10ms) 중 트랜잭셔널 SMS 134건 중앙 55ms · p90 122 · 최대 324(평소 중앙 27ms) · 가정 1: 10만 대상 157초 · sent 100,000 · RSS 최대 863MB(평소 576) · 스레드 최대 119(평소 67) | 가정 1 · 2 실증 |
| 2026-11-19 14:2x | 측정 실수: Q5 첫 시도에서 PUSH 시점이 일러 0.09초(커넥션 다 차기 전) → 같은 스크립트 안에서 2.5초 뒤 측정으로 다시 · 대기 루프 `pgrep -f`가 자기 명령줄에 걸려 끝나지 않음 → TaskStop · 그 셸이 띄운 앱 · 발송사도 함께 종료 → 다시 띄워 측정 · 정리(JVM 0 · 컨테이너 0) | 기록 |
| 2026-11-19 14:3x | ADR-000 채택 · §3 구현 결정(재시도 분류 · 대상 예외 시 행 부족 · 공용 풀) · §9 재현 수치(Q1 · Q2 · Q3 · Q5) · §10 검증 · 낱말(흉내 → mock · 쥔다 → 잡는다 · 판정 기준 → 합격 기준) · 듀얼 리뷰 1패스(codex high 6 ∥ Opus 205,049 토큰 medium 1 · low 12) → 종합 NR-1~13 | 수정 |
| 2026-11-19 14:4x | 수정 분배: 구현 워커 재개(messageId 코드 포인트 · checkChannels 서비스 · 인터럽트 재시도 금지 + 종료 await · 엄격 JSON 2 · 본문 I/O 분류 · 최종 실패 WARN · 대상 예외 로그 묶음 · 테스트(블로킹 제출 증명 · 500 · 대상 예외 행 부족 · INVALID_RESPONSE · 인터럽트 · 불안정 시험 2)) ∥ blind 워커 재개(재시도 간격 상한 200ms · 큰 Retry-After · 닫힌 포트 · 스냅샷 시험 의미 · 뒤따르는 토큰 · 중복 키) · 메인: ADR §6 requested 항상 0 · §3 정상 종료 · 디스패치 실패 · test-contract §4 오류 본문 범위 | 수정 중 |
| 2026-11-19 15:1x | 수정 회수: blind 워커(166,812 토큰) blind 321건 · 구현 워커(191,077 토큰) impl 33건(CampaignDispatcherTest — 디스패처 스레드 WAITING · ArrayBlockingQueue.put 스택 · 발송 20건 모두 campaign-send- 스레드 / InternalErrorApiTest / 이모지 messageId SENT / 인터럽트 재시도 없음 / 본문 Content-Length 거짓 → IO_ERROR 4회) · 정한 것: 본문 읽기 타임아웃은 READ_TIMEOUT과 구분 못 해 IO_ERROR(둘 다 재시도) · 어댑터 별도 관대 매퍼 · 디스패치 시작 실패 = 500 + RUNNING | 회수 |
| 2026-11-19 15:2x | 메인 전체 `cleanTest :test :mock-provider:test --continue`: 앱 355/0(blind 321 + impl 33 + 기동 1) · mock-provider 8/0 · jar 스모크(클라이언트 재작성 확인): push · email SENT · sms drop → FAILED 4 READ_TIMEOUT · WARN 1줄 · 캠페인 300 COMPLETED · ERROR 0 · 정리 · post-fix 재점검(codex medium) 실행 | 재점검 |
| 2026-11-19 15:3x | post-fix 재점검(codex medium): 신규 finding 0(항목 1~7 근거 확인) · 리뷰 마감 · NEXT · 측정 로그 · code · docs 커밋 | 마감 |

## 리뷰 ledger

| id | 루프 | 출처 | 위치 | 판정 | 상태 |
|----|------|------|------|------|------|
| NR-1 | 1 | codex · opus | Notification.kt:104 · RestProviderClient.kt:61-69 | 채택 — messageId 길이 판정 불일치(이모지 → 500 · 롤백) | fixed |
| NR-2 | 1 | codex · opus | SendNotificationService.kt:26 · NotificationRules.kt:32-35 | 채택 — 채널 검사 서비스에도 | fixed |
| NR-3 | 1 | opus | CampaignDispatcher.kt:90-94 · RestProviderClient.kt:53-55 | 채택 — 종료 인터럽트를 재시도로 분류 → 롤백 · await | fixed |
| NR-4 | 1 | opus | JacksonConfig.kt:15-30 | 채택 — 뒤따르는 토큰 · 중복 키 400 | fixed |
| NR-5 | 1 | opus | RestProviderClient.kt:48 · :74 | 채택 — 본문 I/O 오류 분류 | fixed |
| NR-6 | 1 | codex · opus | ProviderDelivery.kt:33 · CampaignDispatcher.kt:70 | 채택 — 최종 실패 WARN · 대상 예외 로그 묶음 | fixed |
| NR-7 | 1 | codex | MockProviderController.kt:26 | 채택 — 오류 순서 주석 · 시험 | fixed |
| NR-8 | 1 | codex · opus | NotificationApiTest.kt:157-194 | 채택 — 블로킹 제출 증명 시험 | fixed |
| NR-9 | 1 | codex · opus | 테스트 공백 | 채택 — 500 · 대상 예외 · INVALID_RESPONSE · IO_ERROR | fixed |
| NR-10 | 1 | opus | ProviderRetryBlindTest · ProviderDeliveryTest · QuotaRetryBlindTest | 채택 — 즉시 재시도 상한 좁힘 · 큰 Retry-After | fixed |
| NR-11 | 1 | opus | ConnectionFailureBlindTest · ProviderDeliveryTest:119 · MockProviderHttpTest:55-61 · CampaignBlindTest:33-36 | 채택 — 불안정 시험 | fixed |
| NR-12 | 1 | opus | ADR §6 · §2.1 | 채택(문서) — requested 항상 0 | fixed |
| NR-13 | 1 | opus | CampaignService.kt:15-18 · test-contract §4 | 채택(문서) — 디스패치 실패 · 오류 본문 범위 | fixed |

## 생략한 검증

- (없음)

## 완료 요약 (2026-11-19)

- 알림 베이스라인: 트랜잭셔널 발송(한 트랜잭션 · 채널 순서 · 즉시 재시도 최대 4회) · 캠페인(접수 커밋 → 디스패처 → 공용 풀 블로킹 제출 · 대상마다 트랜잭션) · API 5종 · mock 발송사(채널별 분당 쿼터 · 429 + Retry-After · 시드 없는 지연 · 500 · 응답 유실) · 멱등 · 백오프 · 큐 없음(일부러).
- 검증: 앱 355/0(계약 321 · 구현 33 · 기동 1) · mock 발송사 8/0 · red(재시도 1회 줄이면 계약 15 · 구현 7 실패) · jar 스모크 2회 · 재현 스모크(Q1 · Q2 · Q3 · Q5 · 대상 10만 메모리) · 듀얼 리뷰 1패스 + post-fix 재점검(신규 0).
- 재현 수치: 응답 유실 → FAILED인데 발송사 4건 접수 · 쿼터 100/분 200건 → 호출 500 · 캠페인 10만 중 트랜잭셔널 중앙 27 → 55ms · 발송사 지연 2초 × 30 → 2 / 4 / 6초 · 대상 10만 RSS 최대 863MB · 스레드 119.
- CS 이슈 아카이브: 보류(study-note 쓰기는 사용자 확인 — 후보 1건 NEXT에).

