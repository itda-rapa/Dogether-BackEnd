# Safety Review Queue 운영 계약

## 목적

RiskSignal을 관리자 검토 대상으로 전환하되 Kafka 소비와 Case 평가를 분리한다. 위험 신호 저장은 Case 평가 장애와 무관하게 완료되고, 별도의 평가 Job이 재시도와 복구를 담당한다. M3에서는 자동 계정 정지나 자동 제재를 수행하지 않는다.

## 처리 흐름

1. `RiskSignalIngestionService`가 `eventId`를 기준으로 `risk_signal_events`를 멱등 저장한다.
2. 새 이벤트인 경우 같은 DB transaction에서 `safety_case_evaluation_jobs`를 한 건 적재한다.
3. Evaluator Worker가 `FOR UPDATE SKIP LOCKED`로 Job을 claim한다.
4. `actorUserId`를 검토 대상인 `subjectUserId`, `targetUserId`를 반복 접촉 대상으로 사용한다. 지연 도착 이벤트도 반영되도록 Risk 도메인의 `RiskSignalAggregateService` 공개 계약으로 현재 평가 가능한 최신 원천 이벤트를 anchor로 찾고 `(anchor-window, anchor]` 구간을 집계한다.
5. 합계가 임계값 이상이면 열린 Case를 생성한다. 이미 `OPEN/REVIEWING` Case가 있으면 합계가 임계값 아래로 내려가도 rolling window의 최신 snapshot으로 갱신하며, 종료된 Case는 자동 재개하지 않는다.
6. Case 반영과 Job 완료는 같은 transaction에서 처리한다.
7. 실패는 지수 backoff로 재시도하며 횟수를 소진하면 `FAILED`로 남긴다. 운영자가 명시적으로 재등록하면 retry budget을 초기화한다.

기존 `risk_signal_events`에 Job이 없는 경우 Worker의 reconcile 단계가 누락 Job을 다시 만든다. 미래 `occurredAt` 이벤트는 해당 시각 이후로 평가를 미룬다.

## 활성화 설정

Evaluator는 기본적으로 비활성화된다. 팀이 M3 점수 fixture를 확정하기 전 임계값을 추정해서 활성화하지 않는다.

필수 활성화 변수:

- `SAFETY_EVALUATOR_ENABLED=true`
- `SAFETY_EVALUATOR_THRESHOLD`: Case 생성 점수 경계
- `SAFETY_EVALUATOR_WINDOW`: 집계 기간
- `SAFETY_EVALUATOR_POLICY_VERSION`: Case 평가 정책 버전

Case의 `evaluationPolicyVersion`은 임계값·집계 기간 등 SafetyCase 생성 정책의 버전이다. 각 RiskSignal의 `scorePolicyVersion`은 점수 산정 정책 snapshot으로 별도 유지하며 두 값을 같은 의미로 사용하지 않는다.

운영 조정 변수:

- `SAFETY_EVALUATOR_BATCH_SIZE` 기본 50
- `SAFETY_EVALUATOR_DELAY_MS` 기본 5000
- `SAFETY_EVALUATOR_LEASE` 기본 1분
- `SAFETY_EVALUATOR_MAX_ATTEMPTS` 기본 10
- `SAFETY_EVALUATOR_BASE_BACKOFF` 기본 5초
- `SAFETY_EVALUATOR_MAX_BACKOFF` 기본 10분

임계값·기간·정책 버전이 없거나 집계 기간이 90일을 넘거나 duration과 backoff 순서가 올바르지 않으면 활성화 설정을 거부한다.

## 상태와 동시성

Case 상태:

- `OPEN`: 관리자 검토 대기
- `REVIEWING`: 검토 중
- `DISMISSED`: 오탐 또는 조치 불필요로 종료
- `WARNING_RECORDED`: 경고 기록 후 종료

동일 `subjectUserId`·`targetUserId` 조합에는 열린 Case가 최대 한 건만 존재한다. PostgreSQL partial unique index가 마지막 방어선이며 Case 처리에는 version과 expected status 조건을 사용한다. 두 관리자가 동시에 종료하면 한 요청만 성공하고 나머지는 `SAFETY_CASE_ALREADY_CLOSED`를 반환한다. 열린 Case snapshot이 먼저 갱신된 경우에는 `CONCURRENT_UPDATE_CONFLICT`를 반환한다.

Case는 `lastEvaluatedEventId` 워터마크를 저장한다. 발생 시각이 같은 별도 신호도 이 값으로 구분하며, 상세·Evidence 조회 역시 Case 평가 당시 워터마크를 넘는 신호를 섞지 않는다.

`safety_case_actions`와 `evidence_access_audits`는 append-only다. DB trigger가 UPDATE와 DELETE를 거부한다.

## 관리자 API

모든 경로는 `ADMIN` 또는 `SUPER_ADMIN`만 사용할 수 있다. Security filter 이후 서비스에서도 DB의 현재 Role과 활성 상태를 다시 검사한다.

- `GET /admin/safety/cases`
  - 기본 상태 `OPEN`, 기본 size 20, 최대 100
  - 상태·신호 유형·subject·target·기간 필터
  - 페이지 사이 Case 재평가에도 순서가 변하지 않는 `(createdAt DESC, caseId DESC)` keyset cursor
- `GET /admin/safety/cases/{caseId}`
  - Case snapshot, 공개 사용자 태그, 관련 RiskSignal과 action 이력
  - 최근 RiskSignal은 최대 100건이며 추가 항목은 `hasMoreSignals`로 표시
- `POST /admin/safety/cases/{caseId}/actions`
  - `DISMISSED`, `WARNING_RECORDED`만 허용
  - `OPEN`에서 바로 종료하거나 `REVIEWING`을 거쳐 종료 가능
- `GET /admin/safety/cases/{caseId}/evidence`
  - 공백이 아닌 `purpose` 필수, 최대 500자
  - `(occurredAt DESC, signalId DESC)` keyset cursor

## Evidence 및 개인정보

RiskSignal과 SafetyCase에는 대화 원문이나 Media URL을 복제하지 않는다. Evidence는 `sourceType/sourceId`로 원천을 조회한다.

- `USER_BLOCK`: 차단 관계의 공개 태그, 상태, 발생 시각
- `GREETING`: 인사 참여자의 공개 태그, 상태, 발생 시각

응답과 감사 데이터에는 이메일, JWT, OAuth code, AI prompt, Risk metadata 전체, 대화 원문과 Media URL을 포함하지 않는다. 원천이 삭제된 경우 `SOURCE_NOT_FOUND` 상태로 반환하고 실패 감사 이력을 남긴다. 감사 저장에 실패하면 Evidence를 반환하지 않는 fail-closed 정책을 적용한다.

## 배포 순서

1. Flyway를 활성화하고 선행 migration(V37, V38) 다음에 V39 적용. 운영 프로필은 `db/migration`만 실행하며 Flyway 기본 활성화와 Hibernate `validate`를 사용한다. 개발 seed/demo repeatable migration은 운영에서 실행하지 않는다.
2. 애플리케이션 배포 후 Evaluator 비활성 상태에서 API·Job 적재 확인
3. 팀이 임계값·기간·정책 버전 확정
4. 필수 환경변수 설정 후 Evaluator 활성화
5. `PENDING/PROCESSING/FAILED` Job, 열린 Case, action·audit 증가량 확인

운영 장애 시 Evaluator만 비활성화해도 RiskSignal 저장은 계속된다. 복구 후 reconcile과 retry로 Case 평가를 이어간다.
