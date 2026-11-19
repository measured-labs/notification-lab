# 테스트 계약 — notification-lab ADR-000 (구현 워커 · blind 워커 공통)

> ADR-000(`docs/adr/ADR-000-baseline-domain.md`)이 정하지 않은 시험 접점만 여기서 정한다. 나머지는 ADR-000 §2~§7과 명세 `requirement-spec.md`가 정본이다.

## 1. 시각

- 앱은 현재 시각을 주입된 `java.time.Clock` 빈에서만 얻는다. 앱 기본 빈 이름은 `systemClock`(`Clock.systemUTC()`). 테스트는 `@Bean @Primary fun clock(): Clock = MutableClock(...)`(`src/test/kotlin/com/jun/labs/notification/support/MutableClock.kt`)로 바꿔 끼울 수 있다.

## 2. mock 발송사 띄우기

- 하위 모듈 `mock-provider`의 메인 클래스는 `com.jun.labs.mockprovider.MockProviderApplication`이다. 테스트는 `SpringApplicationBuilder(MockProviderApplication::class.java).run("--server.port=0", …설정)`으로 같은 JVM 랜덤 포트에 띄우고, 실제 포트는 `local.server.port`로 읽는다.
- 앱이 발송사를 부르는 주소는 `notification.provider.base-url`(테스트는 `@DynamicPropertySource`로 주입).
- 발송사 설정 키는 ADR §7 그대로: `mockprovider.channels.<push|sms|email>.quota-per-minute` · `.latency-min-ms` · `.latency-max-ms` · `.error-rate` · `.drop-rate` · `.drop-hold-ms`. 비율 1.0은 "항상", 0은 "없음"이라 결정적으로 시험할 수 있다.
- 발송사는 접수한 메시지를 꺼내 보는 API가 없다(ADR §7). 시험은 앱 쪽 기록(DB 행 · 응답)과 발송사 응답 동작(쿼터 · 429 · Retry-After)만으로 확인한다. 발송사 자체 시험은 HTTP로만 한다.

## 3. 앱 설정 키 (ADR §4)

- `notification.provider.base-url` · `.connect-timeout` · `.read-timeout`
- `notification.retry.max-retries`(기본 3 — 최초 1회 + 재시도 3회 = 최대 4회)
- `notification.campaign.pool-size`(기본 32)
- 테스트는 읽기 타임아웃 · drop-hold를 짧게 줄여도 된다(예: read-timeout 300ms · drop-hold-ms 1000).

## 4. HTTP

- JSON 필드 이름 · 상태 코드 · 오류 코드는 ADR §6 그대로. 시각은 ISO-8601(UTC `Z` 또는 오프셋).
- 오류 본문은 언제나 `{code, message}`(정확히 두 필드) — Spring MVC 처리기 안에서 난 오류 범위다. Tomcat 단계에서 거절되는 요청(예: 인코딩된 `/`가 든 경로)이나 `/error`를 직접 부르는 경우는 이 계약 밖이다(리뷰 루프 1 추가). ADR 표에 없는 오류: Spring 표준 예외(없는 경로 404 · 405 · 415 등)는 `code = "HTTP_<상태>"`(응답 헤더 유지 — 405의 `Allow`), 그 밖의 예외는 `500 INTERNAL_ERROR`. 5xx `message`는 고정 문구. 406은 본문을 보장하지 않는다.
- 정수 필드(`userId` · `userIdStart` · `targetCount`)는 JSON 정수만(`1.5` · `"1"` · `true` = 400). 문자열 필드는 JSON 문자열만(숫자 · 불리언 = 400). 모르는 필드가 있으면 400. 문자열에 제어 문자 · 짝 없는 서로게이트가 있으면 400. 길이는 코드 포인트로 센다.
- 경로 id는 숫자만(`+5` · `-1` · `0` · `abc` · Long 초과 = 400).
- `POST /campaigns`는 `202`와 접수 시점 스냅샷(`status = RUNNING` · `requested = sent = failed = 0`)을 돌려준다. 끝났는지는 `GET /campaigns/{id}`를 다시 불러 `COMPLETED`를 기다린다(시험은 최대 대기 시간을 두고 폴링).

## 5. DB 직접 확인

- 테스트는 `JdbcTemplate`으로 표 `notification` · `campaign`(ADR §5 열 이름)을 읽어 확인해도 된다. 쓰기(시드)도 허용.
- 이벤트 id · 사용자 id는 테스트마다 새 값을 써서 클래스끼리 섞이지 않게 한다(DB 컨테이너는 JVM당 하나).

## 6. 파일 경계

- 구현 워커: `src/main/**`, `mock-provider/**`, `src/test/kotlin/com/jun/labs/notification/impl/**`, `NotificationApplicationTests.kt`. `blind/`는 열지 않는다.
- blind 워커: `src/test/kotlin/com/jun/labs/notification/blind/**`만 쓴다. `src/main/**` · `mock-provider/src/main/**` · `impl/`은 열지 않는다(메인 클래스 이름 · 설정 키는 이 문서로 안다).
- 공통(읽기만): `build.gradle.kts` · `settings.gradle.kts` · `mock-provider/build.gradle.kts` · `src/main/resources/application.yml` · `src/test/kotlin/com/jun/labs/notification/support/**` · 이 문서.
