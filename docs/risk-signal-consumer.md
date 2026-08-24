# RiskSignal Consumer와 위험 점수 집계

## 보장 범위

`risk-signal-topic`의 `RiskSignalEventV1`을 검증하고 `risk_signal_events`에 불변 이벤트로 저장한다.

- 전달 방식: at-least-once
- 멱등 기준: `eventId` UUID UNIQUE
- Kafka key: `actorUserId` 문자열
- DB commit이 끝난 뒤 offset acknowledgement
- 계약 오류: 재시도 없이 DLT
- 처리 오류: 제한 재시도 후 DLT
- 자동 제재 및 SafetyCase 생성: 이 PR 범위 밖

DB commit 뒤 offset commit 전에 프로세스가 중단되면 같은 이벤트가 재전달될 수 있다. `ON CONFLICT (event_id) DO NOTHING`이 중복 행과 점수 중복 반영을 차단한다.

## 계약과 점수 정책

현재 허용되는 조합은 다음과 같다.

| Source | Signal | V1 임시 점수 |
|---|---|---:|
| `USER_BLOCK` | `USER_BLOCKED` | 30 |
| `GREETING` | `GREETING_EXPIRED` | 10 |

점수와 정책 버전은 `RiskScorePolicyV1`에 함께 고정된 V1 fixture이다. 저장 시 `score`와 `score_policy_version`을 함께 고정하므로 정책 변경 후에도 과거 집계가 달라지지 않는다. 정책 변경 시 환경변수로 값을 덮어쓰지 말고 새 정책 버전을 코드로 추가해야 한다.

Consumer는 unknown JSON 필드를 거부한다. Producer가 임의 `score`를 전달할 수 없으며, 점수는 Consumer의 서버 정책만 적용한다. Kafka key 불일치와 현재보다 허용 범위를 넘은 미래 `occurredAt`도 계약 오류다.

## DLT와 민감정보

기본 DLT는 `risk-signal-topic.DLT`이다.

- 잘못된 계약은 공격자 입력이나 비허용 metadata를 포함할 수 있어 원문을 DLT에 복사하지 않는다. 원본 topic/partition/offset과 오류 분류만 담은 envelope를 보낸다.
- 정상 계약이지만 처리가 계속 실패한 이벤트는 운영 재처리를 위해 검증된 원문을 DLT에 보낸다.
- DLT 발행이 실패하면 원본 offset을 처리하지 않아 다음 poll에서 다시 시도한다.
- DLT header에는 원본 topic/partition/offset과 오류 분류를 기록한다. 재시도 소진 이벤트는 원래 key와 partition도 보존한다.
- 애플리케이션 로그에는 raw payload, metadata, 사용자 ID를 남기지 않는다.

운영에서는 원본과 DLT topic을 사전에 생성하고 replication factor, partition 수, retention, Producer/Consumer ACL을 확정해야 한다.

## 집계 규칙

`risk_signal_events`의 저장된 점수를 직접 `COUNT/SUM/MIN/MAX`한다. 별도 mutable 누계 테이블은 만들지 않는다.

- 기준 시각: UTC `occurredAt`
- 기간: 시작 포함, 종료 미포함
- 대상: actor, target, actor+target
- 기본 최대 조회 범위: 90일
- 빈 결과: count/score 0, first/last null

이번 PR에는 외부 조회 API가 포함되지 않는다. Dashboard API는 이 집계 서비스를 사용해 별도 작업에서 연결한다.

## 운영 설정

| 환경 변수 | 기본값 | 설명 |
|---|---:|---|
| `RISK_SIGNAL_CONSUMER_ENABLED` | `false` | Listener 활성화 |
| `RISK_SIGNAL_CONSUMER_GROUP_ID` | `dogether-risk-signal-v1` | 전용 Consumer group |
| `RISK_SIGNAL_CONSUMER_CONCURRENCY` | `1` | Listener concurrency |
| `RISK_SIGNAL_CONSUMER_MAX_POLL_RECORDS` | `100` | poll 최대 레코드 |
| `RISK_SIGNAL_CONSUMER_MAX_ATTEMPTS` | `3` | 최초 처리를 포함한 최대 시도 횟수 |
| `RISK_SIGNAL_CONSUMER_RETRY_BACKOFF` | `1s` | 처리 오류 재시도 간격 |
| `RISK_SIGNAL_CONSUMER_DLT_TOPIC` | `risk-signal-topic.DLT` | DLT 이름 |
| `RISK_SIGNAL_CONSUMER_DLT_PUBLISH_TIMEOUT` | `10s` | DLT broker 응답 대기 |
| `RISK_SIGNAL_MAX_FUTURE_SKEW` | `5m` | 미래 시각 허용 오차 |
| `RISK_SIGNAL_MAX_AGGREGATION_RANGE` | `90d` | 집계 최대 기간 |

Consumer를 먼저 배포하고 DB migration 및 topic 준비를 확인한 뒤 활성화한다. 이후 Outbox Relay를 활성화한다.

## 데이터베이스

사용자 지시에 따라 다른 팀원의 V37과 충돌하지 않도록 `V38__create_risk_signal_events.sql`을 사용한다.

- `event_id` UNIQUE
- `score`, `score_policy_version` snapshot
- source 추적 및 actor/target/기간 집계 인덱스
- 탈퇴 사용자 이벤트 보존과 재처리를 위해 users FK는 두지 않음

## 검증 명령

    .\gradlew.bat test --tests "itda.risk.consumer.*" --tests "itda.risk.service.RiskSignalIngestionServiceTest"

    .\gradlew.bat postgresTest --tests "itda.risk.service.RiskSignalConsumerPostgreSqlIntegrationTest"

    .\gradlew.bat kafkaTest --tests "itda.risk.consumer.RiskSignalConsumerKafkaIntegrationTest"

기존 결정대로 CI workflow는 추가하지 않는다. PostgreSQL과 Kafka 검증은 Docker가 필요한 별도 task로 로컬에서 실행하고 결과를 PR에 기록한다.
