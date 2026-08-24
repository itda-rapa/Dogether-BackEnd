# RiskSignal Outbox Kafka Relay

## 목적과 보장 범위

원천 도메인 트랜잭션에서 저장한 `risk_signal_outbox` 이벤트를 `risk-signal-topic`으로 전달한다.

- Topic: `risk-signal-topic`
- Key: `actorUserId` 문자열
- Value: Outbox에 저장된 `RiskSignalEventV1` JSON 원문
- 전달 보장: at-least-once
- Consumer 멱등 기준: `eventId`

Kafka 전달 성공과 Outbox의 `SENT` 변경은 하나의 원자적 트랜잭션이 아니다. Kafka 응답 후 DB 변경이 실패하거나 전송 timeout 뒤 broker가 늦게 성공하면 같은 `eventId`가 다시 전달될 수 있다.

## 처리 흐름

1. `PENDING`, due `RETRY`, lease가 만료된 `PROCESSING` 중 한 건을 `FOR UPDATE SKIP LOCKED`로 선점한다.
2. 별도 DB 트랜잭션에서 `PROCESSING`, `claim_token`, `claimed_at`, 증가한 `attempts`를 확정한다.
3. DB 트랜잭션 밖에서 Kafka broker 응답을 timeout까지 기다린다.
4. 성공 시 `SENT`, 실패 시 backoff를 적용한 `RETRY` 또는 `FAILED`로 변경한다.
5. 모든 완료 변경은 `id + PROCESSING + claim_token` 조건으로 stale worker를 차단한다.

한 주기의 `batch-size`는 최대 처리량이며, lease 만료 위험을 줄이기 위해 실제 선점은 한 건씩 수행한다.

## 운영 설정

기본적으로 Relay는 비활성화된다. Topic 및 Consumer 준비와 배포 순서를 확인한 뒤 운영 환경에서 활성화한다.

프로젝트 공용 `itda.common.config.SchedulingConfig`에 `@EnableScheduling`이 등록되어 있다. Relay는 이 공용 설정을 재사용하며, `RISK_SIGNAL_OUTBOX_RELAY_ENABLED=true`일 때만 `RiskSignalOutboxRelayScheduler` Bean과 예약 작업이 등록된다.

| 환경 변수 | 기본값 | 설명 |
|---|---:|---|
| `RISK_SIGNAL_OUTBOX_RELAY_ENABLED` | `false` | 스케줄러 활성화 |
| `RISK_SIGNAL_OUTBOX_RELAY_DELAY_MS` | `1000` | 처리 주기(ms) |
| `RISK_SIGNAL_OUTBOX_RELAY_BATCH_SIZE` | `50` | 주기당 최대 처리 건수 |
| `RISK_SIGNAL_OUTBOX_RELAY_LEASE` | `1m` | `PROCESSING` 선점 유효 시간 |
| `RISK_SIGNAL_OUTBOX_RELAY_SEND_TIMEOUT` | `10s` | Kafka 응답 대기 시간 |
| `RISK_SIGNAL_OUTBOX_RELAY_MAX_BLOCK` | `5s` | Producer `send()` 동기 블로킹 상한 |
| `RISK_SIGNAL_OUTBOX_RELAY_MAX_ATTEMPTS` | `10` | 최대 전송 시도 횟수 |
| `RISK_SIGNAL_OUTBOX_RELAY_BASE_BACKOFF` | `5s` | 최초 재시도 지연 |
| `RISK_SIGNAL_OUTBOX_RELAY_MAX_BACKOFF` | `10m` | 재시도 지연 상한 |
| `KAFKA_BOOTSTRAP_SERVER` | 없음 | 운영 Kafka bootstrap 주소 |

`lease`는 `max-block + send-timeout + 5초 상태 변경 여유` 이상이어야 한다. backoff에는 이벤트별 안정적인 jitter가 적용된다. Risk 전용 Producer만 `acks=all`, idempotence, retry 및 timeout 설정을 사용하므로 기존 Chat Producer에는 영향을 주지 않는다.

운영 활성화 전에는 Kafka 관리 측에서 topic 파티션 수, replication factor, retention, Producer/Consumer ACL을 확정해야 한다. 애플리케이션이 topic을 임의 생성하는 것을 운영 provisioning으로 간주하지 않는다.

`KAFKA_BOOTSTRAP_SERVER` 주입만으로 연결 검증이 끝나는 것은 아니다. Kafka가 metadata로 반환하는 advertised listener 주소를 애플리케이션 컨테이너가 해석하고 접속할 수 있어야 한다. DB/Kafka Compose와 Server Compose를 별도로 실행하면 같은 network key도 서로 다른 Docker network로 생성될 수 있으므로, 공유 external network 또는 INTERNAL/EXTERNAL listener 구성은 인프라 배포에서 별도로 확정한다.

## 관측 지표

- `risk.signal.outbox.backlog`: `PENDING + PROCESSING + RETRY`
- `risk.signal.outbox.failed`: 운영 확인이 필요한 `FAILED`
- `risk.signal.outbox.relay`: `result`, `signal` 태그별 처리 결과
- `risk.signal.outbox.publish.latency`: Kafka acknowledgement 지연
- `risk.signal.outbox.delivery.delay`: 원천 이벤트 발생부터 전달 성공까지 지연

로그와 `last_error`에는 payload, metadata, 예외 메시지를 남기지 않고 이벤트 식별자와 예외 타입만 기록한다.

## 데이터베이스 변경

다른 작업의 V35 사용을 고려해 Relay 인덱스 변경은 `V36__index_risk_signal_outbox_stale_claim.sql`에 작성했다.

- due `PENDING/RETRY`: `(next_retry_at, id)` 부분 인덱스
- stale `PROCESSING`: `(claimed_at, id)` 부분 인덱스

## 검증 명령

    .\gradlew.bat test --tests "itda.risk.service.RiskSignalKafkaPublisherTest" --tests "itda.risk.service.RiskSignalOutboxRelayWorkerTest" --tests "itda.risk.service.RiskSignalOutboxRelayPropertiesTest"

    .\gradlew.bat postgresTest --tests "itda.risk.service.RiskSignalOutboxPostgreSqlIntegrationTest"

    .\gradlew.bat kafkaTest --tests "itda.risk.service.RiskSignalOutboxRelayKafkaIntegrationTest"

PostgreSQL 및 Kafka 통합 테스트는 Docker 또는 Embedded Kafka가 필요하므로 기본 `test` task와 분리했다. 이번 PR에서는 CI job을 추가하지 않으며, 병합 전 위 명령을 수동으로 실행하고 결과를 PR에 기록한다.

실제 broker 중지/재시작은 timeout 이후 늦은 성공으로 테스트가 불안정해질 수 있어 자동화하지 않았다. 대신 Spring + PostgreSQL 통합 테스트에서 첫 발행 실패를 결정적으로 주입해 `RETRY → 재선점 → SENT` 전체 상태 전이를 검증하고, Embedded Kafka E2E에서는 실제 topic/key/JSON/SENT를 검증한다.
