# ADR-000: 알림 도메인과 평범한 구현 (baseline)

- 상태: 채택 (Accepted) — 2026-11-19 구현 · 검증 끝
- 날짜: 2026-11-18
- 성격: **베이스라인**. 정합성 · 격리 장치 없이 "기능이 되는" 가장 평범한 알림 발송을 만든다. ADR-001부터는 이 베이스라인에서 문제를 관측하고 하나씩 해결한다.
- 아키텍처: 레이어드(api / service / domain) + 바깥 발송사 어댑터(infra)
- 인터뷰 기록: `docs/plans/2026-11-18/adr-000-baseline/interview.md`

## 1. 왜 베이스라인부터 만드는가

README 아키텍처(멱등키 · Outbox → 우선순위 큐 → 토큰 버킷 → DLQ)는 이미 **해법**이다. 해법부터 만들면 "왜 큐를 나누는가 · 왜 토큰 버킷인가"가 추론으로만 남는다.

베이스라인 원칙:

- **평범할 것**: 처음 알림을 붙이는 개발자가 짜는 코드. 알림 API는 `@Transactional` 안에서 **기록 저장 → 발송사 호출(실패하면 즉시 재시도 3회) → 결과 기록**. 캠페인은 스레드 풀로 병렬 발송한다. 정상 경로에서는 올바르게 동작한다.
- **문제를 가리지 않을 것**: 멱등키 · `event_id` 유니크 · 아웃박스 · 큐 · 우선순위 · 속도 제한 · 백오프 · DLQ · 서킷을 넣지 않는다. 스키마에는 PK와 FK만 둔다.
- **mock 발송사도 최소일 것**: 실제 발송사가 하는 일(채널별 접수 · 분당 쿼터 · 429)만 한다. 받은 메시지를 꺼내 보는 덤프 · 중복 집계 · 결정적 주입은 필요가 드러나는 ADR에서 넣는다.

## 2. 도메인 모델

```
campaign (캠페인)                 ← 대상 N명에게 같은 내용을 한 채널로
  1
  │
  0..N
notification (알림 1건)          ← (이벤트 또는 캠페인) × 사용자 × 채널 하나
```

| 결정 | 내용 | 이유 |
|------|------|------|
| 알림 1행 = 채널 하나 | 트랜잭셔널 요청의 `channels[]`마다 행 1개 · 순서대로 발송 | 채널마다 결과 · 시도 횟수가 다르다 |
| 종류 컬럼 | `kind` = `TRANSACTIONAL` · `CAMPAIGN` — **기록만** 하고 처리 우선순위에는 쓰지 않는다 | Q3(캠페인 중 긴급 알림 지연)을 종류별로 세기 위해서. 우선순위 처리는 해법이다 |
| `event_id` | 저장하지만 **중복 검사를 하지 않는다** | 같은 이벤트가 두 번 오면 두 번 보낸다(Q1 관측) |
| 실패도 기록 | 발송 실패(재시도 소진)는 예외로 롤백하지 않고 `FAILED`로 커밋한다 | 알림은 실패가 '결과'다. 다만 발송 성공 뒤 커밋 전에 죽으면 기록이 사라진다(Q4) |
| 사용자 | 사용자 테이블 없이 `user_id` 숫자만(수신 주소 = 사용자 id) | 수신자 관리는 범위 밖 |
| 캠페인 대상 | 사용자 id 범위(`userIdStart` 부터 `targetCount`명) | 대상 조회는 범위 밖. 100만 명도 요청 하나로 만들 수 있게 |

### 2.1 애그리거트 · 불변식

| 애그리거트 | 루트 | 지키는 불변식 |
|-----------|------|--------------|
| **Notification** | `Notification` | ① 전이는 `REQUESTED → SENT` · `REQUESTED → FAILED`만 ② `SENT`면 `providerMessageId`가 있다 ③ 1 ≤ `attempts` ≤ 1 + `max-retries`(커밋된 행) ④ `FAILED`면 `lastError`가 있다 |
| **Campaign** | `Campaign` | ⑤ 전이는 `RUNNING → COMPLETED`만 ⑥ `COMPLETED`면 그 캠페인 알림이 `targetCount`개 있고 모두 `SENT` 또는 `FAILED`다(정상 경로 — 서버가 죽지 않았을 때) |

- 캠페인 진행 수(보냄 · 실패 · 대기)는 **저장하지 않고** 조회 때 알림 행을 센다. 저장 카운터의 동시 갱신 문제는 이 랩의 주제가 아니다.

## 3. 상태 전이 · 절차

```
Notification:  REQUESTED ──발송 성공(시도 ≤ 4)──▶ SENT
                   │
                   └──재시도 소진 · 재시도 안 하는 오류──▶ FAILED

Campaign:      RUNNING ──모든 대상 발송 끝──▶ COMPLETED
```

| 동작 | 트리거 | 베이스라인 절차 |
|------|--------|------------|
| 트랜잭셔널 발송 | `POST /notifications` | **한 트랜잭션**: 채널마다 알림 INSERT(`REQUESTED`) → 발송사 호출(아래 재시도) → `SENT` 또는 `FAILED` → 다음 채널 → 커밋 → `201` |
| 캠페인 접수 | `POST /campaigns` | 캠페인 INSERT(`RUNNING`) 커밋 → 디스패처에 캠페인 id를 넘기고 **바로** `202`(접수 시점 스냅샷: `RUNNING` · 수 0) |
| 캠페인 디스패치 | 디스패처 스레드(캠페인마다 1개) | 대상 id를 차례로 만들어 발송 풀(고정 `pool-size` 스레드 · 큐 `pool-size × 2`)에 제출한다. 큐가 차면 **빈자리가 날 때까지 기다린다**(블로킹 제출 — 거절 없음 · 작업 객체가 한꺼번에 쌓이지 않는다) |
| 캠페인 대상 1명 | 스레드 풀 작업 | **대상마다 한 트랜잭션**: 알림 INSERT(`kind = CAMPAIGN`) → 발송사 호출(재시도) → `SENT` · `FAILED` → 커밋 |
| 캠페인 완료 | 디스패처가 제출한 `targetCount`개 작업이 모두 끝남 | 캠페인 `COMPLETED` · `completedAt` |

- **재시도**(트랜잭셔널 · 캠페인 같음): 429 · 5xx · 연결 실패 · 읽기 타임아웃이면 **기다리지 않고** 바로 다시 보낸다. 최초 1회 + 재시도 최대 3회 = 최대 4회. `Retry-After` 헤더는 보지 않는다. 그 밖의 4xx는 재시도하지 않고 `FAILED`.
- 읽기 타임아웃 뒤 재시도는 발송사가 이미 접수한 메시지를 **다시 보낸다**(응답 유실 → 중복 발송 — Q1).
- 발송사를 기다리는 동안 DB 커넥션을 잡는다. 캠페인 스레드가 커넥션 풀보다 많으면 트랜잭셔널 요청이 커넥션을 기다린다(Q3 · Q5).
- 서버가 캠페인 도중 죽으면 캠페인은 `RUNNING`으로 남고 나머지 대상은 보내지지 않는다(재개 없음 — 관측 대상). 정상 종료(SIGTERM)도 같다: 발송 풀을 멈추고 잠시 기다린 뒤 끊으며, 끊긴 대상은 재시도하지 않고 그 트랜잭션을 롤백한다(기록 없음). 접수를 커밋한 뒤 디스패치를 시작하지 못하면(스레드 생성 실패 · 풀이 이미 닫힘) 캠페인은 대상 0건인 `RUNNING`으로 남고 ERROR 로그가 남는다.
- 재시도 분류(구현에서 정함): 429 · 5xx · 연결 실패 · 연결 타임아웃 · 읽기 타임아웃 · 그 밖의 I/O 오류는 바로 다시 보낸다. 그 밖의 4xx와 2xx인데 `messageId`가 없거나 저장할 수 없는 응답은 재시도 없이 `FAILED`다(`lastError` = `HTTP_<상태> <발송사 code>` · `READ_TIMEOUT` · `CONNECT_FAILED` · `IO_ERROR` · `INVALID_RESPONSE`).
- 캠페인 대상 1명의 처리가 예외(예: DB 커넥션을 기다리다 시간 초과)로 끝나면 디스패처는 로그만 남기고 그 대상도 끝난 것으로 센다. 그래서 캠페인은 `COMPLETED`인데 알림 행이 `targetCount`보다 적을 수 있다. 불변식 ⑥은 정상 경로에서만 지켜진다(§9).
- 발송 풀은 앱 전체가 함께 쓰는 고정 풀 하나다(`pool-size` 스레드 · 큐 `pool-size × 2`). 디스패처는 캠페인마다 스레드 하나다.
- 시각은 애플리케이션 `Clock`에서 얻는다.

## 4. 규칙 · 설정

| 규칙 | 값 | 설정 |
|------|----|------|
| 앱 포트 | 8106 (상위 README) | `server.port` |
| 발송사 주소 | `http://localhost:8116` · compose `http://mock-provider:8116` | `notification.provider.base-url` |
| 연결 · 읽기 타임아웃 | 1초 · 5초 (README 발송사 지연 p99 2초보다 길게) | `notification.provider.connect-timeout` · `read-timeout` |
| 즉시 재시도 횟수 | 3 | `notification.retry.max-retries` |
| 캠페인 스레드 풀 | 32 | `notification.campaign.pool-size` |
| DB 커넥션 풀 | Hikari 기본(10) | `spring.datasource.hikari.maximum-pool-size` |

## 5. DB 스키마 (Flyway `V1__init.sql`)

```sql
CREATE TABLE campaign (
    id             BIGSERIAL     PRIMARY KEY,
    name           VARCHAR(100)  NOT NULL,
    channel        VARCHAR(10)   NOT NULL,   -- PUSH · SMS · EMAIL
    user_id_start  BIGINT        NOT NULL,
    target_count   INT           NOT NULL,
    title          VARCHAR(100)  NOT NULL,
    body           VARCHAR(1000) NOT NULL,
    status         VARCHAR(20)   NOT NULL,   -- RUNNING · COMPLETED
    created_at     TIMESTAMPTZ   NOT NULL,
    completed_at   TIMESTAMPTZ
);

CREATE TABLE notification (
    id                   BIGSERIAL     PRIMARY KEY,
    kind                 VARCHAR(20)   NOT NULL,   -- TRANSACTIONAL · CAMPAIGN
    event_id             VARCHAR(64),              -- TRANSACTIONAL만
    event_type           VARCHAR(50),              -- TRANSACTIONAL만
    campaign_id          BIGINT        REFERENCES campaign (id),
    user_id              BIGINT        NOT NULL,
    channel              VARCHAR(10)   NOT NULL,
    title                VARCHAR(100)  NOT NULL,
    body                 VARCHAR(1000) NOT NULL,
    status               VARCHAR(20)   NOT NULL,   -- REQUESTED · SENT · FAILED
    attempts             INT           NOT NULL,
    provider_message_id  VARCHAR(64),
    last_error           VARCHAR(200),
    requested_at         TIMESTAMPTZ   NOT NULL,
    sent_at              TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ   NOT NULL
);
```

- 의도적으로 **없는 것**: `event_id` 유니크(또는 `(event_id, channel)` 유니크), 상태 CHECK, 조회용 인덱스(`campaign_id` · `event_id` 포함). 인덱스 부재가 측정을 왜곡하면 해당 ADR에서 V2 이후로 추가하고 before/after를 기록한다.

## 6. API (앱)

오류 본문은 `{code, message}`.

| 메서드 | 경로 | 요청 | 성공 | 실패 |
|--------|------|------|------|------|
| POST | `/notifications` | `{eventId, eventType, userId, channels[], title, body}` | `201 [알림 응답]`(채널 순서대로 · 각 `SENT` 또는 `FAILED`) | `400 INVALID_REQUEST` |
| GET | `/notifications/{id}` | — | `200` 알림 응답 | `404 NOTIFICATION_NOT_FOUND` |
| GET | `/notifications?eventId=` | — | `200 [알림 응답]`(id 순) | `400` |
| POST | `/campaigns` | `{name, channel, userIdStart, targetCount, title, body}` | `202` 캠페인 응답(`RUNNING`) | `400` |
| GET | `/campaigns/{id}` | — | `200` 캠페인 응답 | `404 CAMPAIGN_NOT_FOUND` |

- 알림 응답: `{notificationId, kind, eventId, eventType, campaignId, userId, channel, status, attempts, providerMessageId, lastError, requestedAt, sentAt}`.
- `POST /campaigns`의 `202` 본문은 **접수 시점 스냅샷**이다(`status = RUNNING` · `requested = sent = failed = 0`) — 대상이 1명이라 응답 전에 끝나도 같다. 진행은 `GET`으로 본다.
- 캠페인 응답: `{campaignId, name, channel, targetCount, status, requested, sent, failed, createdAt, completedAt}` — `requested` · `sent` · `failed`는 그 캠페인 알림 행을 상태별로 센 값. `REQUESTED` 행은 대상 1명을 처리하는 트랜잭션 안에만 있다가 커밋 때 `SENT` · `FAILED`로 바뀌므로, 다른 트랜잭션인 조회에서는 `requested`가 언제나 0으로 보인다. 아직 처리하지 않은 대상 수는 `targetCount − sent − failed`다.
- 트랜잭셔널 발송에서 발송사 실패는 HTTP 오류가 아니다 — `201`에 그 채널이 `FAILED`로 들어간다.

### 6.1 입력 검증 (위반 = `400 INVALID_REQUEST` · 아무것도 저장 · 발송하지 않는다)

| 필드 | 규칙 |
|------|------|
| `eventId` | 공백 아님 · 1~64자 |
| `eventType` | 공백 아님 · 1~50자 |
| `userId` · `userIdStart` | JSON 정수 · 1 이상 |
| `channels` | 1~3개 · 각 `PUSH` · `SMS` · `EMAIL` · 중복 없음 |
| `channel` | `PUSH` · `SMS` · `EMAIL` |
| `title` | 공백 아님 · 1~100자 |
| `body` | 공백 아님 · 1~1,000자 |
| `name` | 공백 아님 · 1~100자 |
| `targetCount` | JSON 정수 · 1~1,000,000 · `userIdStart + targetCount − 1` ≤ Long 최대 |
| 경로 id | 양의 정수 · Long 범위 |
| 본문 | JSON으로 읽을 수 없으면 400 |

## 7. mock 발송사 (`mock-provider` 하위 모듈 · 포트 8116)

| 메서드 | 경로 | 요청 | 성공 | 거절 |
|--------|------|------|------|------|
| POST | `/v1/{channel}/send` (`push` · `sms` · `email`) | `{to, title, body}` | `200 {messageId}` | `400 INVALID_REQUEST`(`to` ≤ 0 · 빈 제목 · 본문) · `404`(모르는 채널) · `429 QUOTA_EXCEEDED`(+ `Retry-After` 초) |

- **쿼터**: 채널마다 **분당** 한도. 창은 발송사 프로세스가 뜬 시각 `t0`부터 60초씩 — **정각과 맞춰지지 않는다**(FCM 문서). 창 k = [`t0` + 60k초, `t0` + 60(k+1)초) — 경계 시각은 **다음 창**이다. `Retry-After` = 현재 창 끝까지 남은 초를 올림(최소 1). 한도를 넘은 요청은 처리하지 않고 429. 기본값 push 600,000 · sms 60,000 · email 120,000(sms · email은 가정 — README 근거는 FCM뿐).
- **장애 주입**(채널마다 · 기본 0 · 기동 설정으로만 · **시드 없는 무작위**): `latency-min-ms` · `latency-max-ms`(지연 ~ U) · `error-rate`(처리하지 않고 500) · `drop-rate`(접수한 뒤 `drop-hold-ms` 동안 응답하지 않음 — 응답 유실) · `drop-hold-ms`(기본 10,000).
- 설정 이름: `mockprovider.channels.<push|sms|email>.quota-per-minute` · `.latency-min-ms` · `.latency-max-ms` · `.error-rate` · `.drop-rate` · `.drop-hold-ms`.
- 접수한 메시지는 세지만(쿼터용) **꺼내 보는 API는 없다**. 중복 발송을 셀 길이 없다는 것이 ADR-001의 출발점이다.
- 지금은 모듈 하나가 세 채널을 맡는다. 한 채널 장애(지연)가 같은 프로세스의 다른 채널을 느리게 하는지도 관측 대상이고, 대용량에서 채널별 분리는 그 ADR에서 다룬다(사용자 결정).

## 8. 코드 구조

```
notification-lab
├── src/main/kotlin/com/jun/labs/notification
│   ├── api/                 컨트롤러 · DTO · 예외 → HTTP
│   ├── service/             UseCase · 커맨드/결과
│   │   └── impl/            발송 조율 · 재시도 · 캠페인 스레드 풀 디스패처
│   ├── domain/              Notification · Campaign · 상태 · 채널 · 종류 · 도메인 예외
│   │   └── repository/      Spring Data JPA
│   └── infra/provider/      ProviderClient 인터페이스 · RestClient 구현 · 응답 → 결과 변환
├── src/main/resources/db/migration/V1__init.sql
└── mock-provider/           하위 모듈 (Spring Boot · 메모리 쿼터 · 장애 주입)
```

## 9. 베이스라인이 일부러 남겨 둔 약점 → 가설 ADR

번호는 관측 순서대로 붙인다(ADR-001만 먼저 정한다).

| 약점 (베이스라인에서 예상되는 모습) | README Q | 가설 ADR |
|------|---|---------|
| 합격 기준이 없다 — 발송사 쪽 사실(실제로 몇 번 접수됐나)을 꺼낼 길이 없어 중복 · 유실을 셀 수 없다. 발송사 수신 기록 내보내기 · 결정적 주입이 여기서 필요해질 것이다 | 전체 | **ADR-001** 정합성 판정 · 관측 하네스 |
| 같은 `eventId` 재요청 → 두 번 발송 · 응답 유실 뒤 즉시 재시도 → 중복 발송. 재현(2026-11-19, 로컬): 같은 `eventId`를 두 번 보내자 두 건 모두 `SENT`. SMS 발송사가 접수한 뒤 응답을 잃게 하자(유실 비율 1.0), 앱은 읽기 타임아웃 5초마다 바로 다시 보내 4번 시도한 끝에 `FAILED`(20.1초)로 기록했다. 발송사는 4번 모두 접수했으므로 사용자는 문자를 4통 받을 수 있는데 우리 기록은 '실패'다 | Q1 | 멱등키 · 발송사 멱등 |
| 429에 즉시 재시도 3회 → 쿼터 창 안에서 요청이 최대 4배 · 대부분 다시 429. 재현: email 쿼터 분당 100에 200건을 연달아 보내자 100건 `SENT` · 100건 `FAILED`였고, 실패한 100건이 4번씩 시도해 요청 200건에 발송사 호출이 500번(2.5배)이 됐다. 다시 보낸 300번은 모두 같은 창 안에서 다시 429였다 | Q2 | 백오프 · `Retry-After` 존중 · 재시도 예산 |
| 캠페인 스레드가 쿼터와 DB 커넥션을 차지 → 트랜잭셔널 알림 지연 · 429 실패. 재현: 대상 10만 명 캠페인(push 지연 5~10ms · 발송 풀 32 · DB 커넥션 10)이 도는 157초 동안 트랜잭셔널 SMS 134건의 응답 시간 중앙값이 27ms → 55ms, p90 122ms, 최대 324ms였다. 캠페인 대상 처리가 DB 오류로 끝나면 `COMPLETED`인데 행이 모자랄 수 있다(§3) | Q3 | 우선순위 분리(큐 · 쿼터 몫 나누기) |
| 발송사 접수 뒤 커밋 전 서버 다운 → 보냈는데 기록 없음 · 캠페인 `RUNNING` 방치 | Q4 | 아웃박스 · 캠페인 재개 |
| 발송사 지연 동안 요청 스레드 · DB 커넥션을 잡는다 → 알림을 부른 쪽까지 느려진다. 재현: email 발송사 지연 2초에 30건을 동시에 보내자 응답 시간이 2.0 · 4.1 · 6.1초 세 단계로 나뉘었다(커넥션 10개를 2초씩 잡아 10건씩 차례로 처리). 그 사이 지연이 없는 push 요청도 3.6초를 기다렸다(평소 0.03초) | Q5 | 비동기화 · 타임아웃 · 벌크헤드 |
| 속도 조절이 없어 쿼터를 몇 초 만에 다 쓰고 나머지 시간은 429 | Q6 | 토큰 버킷 · 발송 속도 제한 |
| 한 프로세스가 세 채널을 맡는다 · 한 채널 지연이 다른 채널로 번질 수 있다 | (확장) | 채널별 발송사 분리 |

## 10. 검증

- 계약 테스트(설계 문서와 시험 계약만 보고 구현과 따로 쓴 테스트 — 발송사 호출 수와 응답 순서를 정해 둔 대역과 실제 mock 발송사를 함께 씀) · 구현 테스트 · 로컬 스모크 · 재현 스모크 · 리뷰. 기록은 `docs/plans/2026-11-18/adr-000-baseline/log.md`.

### 10.1 결과 (2026-11-19)

- 테스트: 앱 341개(계약 314 · 구현 26 · 기동 1) · mock 발송사 8개 모두 통과. 재시도 횟수를 하나 줄이는 결함을 넣으면 계약 테스트 15개와 구현 테스트 7개가 실패했다.
- 로컬 스모크(임시 PostgreSQL 16 · 실행 jar 2개): 세 채널 트랜잭셔널 발송이 모두 `SENT`(시도 1회), 대상 500명 캠페인이 202 접수 뒤 약 1.3초에 `COMPLETED`(500건 `SENT`), 서버 로그 ERROR 0.
- 대량 캠페인: 대상 10만 명이 157초에 모두 `SENT`로 끝났고, 앱 메모리는 최대 863MB(평소 576MB), 스레드는 최대 119개(평소 67개)에 머물렀다. 디스패처가 큐가 찰 때 기다리는 방식(블로킹 제출)이라 대상 수만큼 작업이 쌓이지 않는다.
- §9 약점의 실제 모습: 표의 재현 수치(Q1 · Q2 · Q3 · Q5). Q4(커밋 전 서버 다운)는 재현하지 않았다.

