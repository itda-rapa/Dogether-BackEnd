# 같이놀개 M3 통합 ERD

> 표는 M3 신규·변경분 중심이다. 기존 M1/M2 테이블은 현재 Flyway를 계승하며 적용된 Migration을 수정하지 않는다.

## 1. 관계 요약

```text
users 1 ─ N oauth_identities
users 1 ─ N pets
pets 1 ─ N board_posts / comments / chat_messages
board_post_comments 1 ─ 0..N board_post_comments (parent)
board_post_comments 1 ─ 0..N board_post_comments (root)
board_post_comments 1 ─ N board_post_comment_reactions

chat_messages 1 ─ 0..N chat_message_attachments N ─ 1 media
chat_messages N ─ 0..1 setlogs

meeting_cards 1 ─ N meeting_verifications
meeting_cards 1 ─ N meeting_verification_requests
meeting_cards 1 ─ 0..1 meetings
meetings 1 ─ N meeting_reviews
meetings 1 ─ N footprints

chat_rooms 1 ─ N meeting_suggestion_scans 1 ─ N meeting_suggestions

places 1 ─ N board_posts / meeting_cards / walk_sessions
walk_sessions 1 ─ N walk_points

users 1 ─ N risk_signal_events
users 1 ─ N safety_review_cases
safety_review_cases 1 ─ N safety_case_actions
safety_review_cases 1 ─ N evidence_access_audits
```

## 2. 인증

### `oauth_identities`

| 컬럼 | 형식 | 제약 |
|---|---|---|
| id | BIGSERIAL | PK |
| user_id | BIGINT | FK users, NOT NULL |
| provider | VARCHAR(20) | DB CHECK: GOOGLE/NAVER; runtime adapter는 GOOGLE·NAVER |
| provider_subject | VARCHAR(255) | NOT NULL |
| created_at | TIMESTAMPTZ | NOT NULL |
| updated_at | TIMESTAMPTZ | NOT NULL |

제약:

- `UNIQUE(provider, provider_subject)`
- `UNIQUE(user_id, provider)`
- Provider Access/Refresh Token과 ID Token은 저장하지 않는다.
- `oauth_login_codes`: hash, provider, provider subject, verified email, 상태, 만료시각을 저장하는 5분 TTL 1회용 교환 자원이다. logical expiry 뒤 약 1분 cleanup grace 동안 row와 transient verified email snapshot을 보존한 뒤 physical delete한다.
- `oauth_signup_tokens`: hash, provider, provider subject, verified email, 상태, 만료시각을 저장하는 10분 TTL 1회용 가입 자원이다. logical expiry 뒤 약 1분 cleanup grace 동안 row와 transient verified email snapshot을 보존한 뒤 physical delete한다.
- browser authorization transaction은 DB가 아니라 Redis에 state hash, PKCE verifier, provider별 필요한 nonce(Google만), backend redirect URI 및 만료시각을 보관하고 callback에서 원자적으로 소비한다. Naver는 nonce를 사용하지 않는다.
- `OAuthArtifactCleanupScheduler`가 주기적으로 logical expiry + 약 1분 grace를 지난 loginCode/signupToken row를 physical delete한다.

## 3. 게시판 변경

### `board_post_comments`

| 컬럼 | 형식 | 제약·의미 |
|---|---|---|
| parent_comment_id | BIGINT | nullable, self FK. 대댓글의 직접 부모 ID |
| root_comment_id | BIGINT | nullable, self FK. 대댓글이 속한 Root ID |
| depth | SMALLINT | NOT NULL DEFAULT 0 |

- DB CHECK `ck_board_post_comments_hierarchy`: Root는 `parent_comment_id/root_comment_id IS NULL AND depth=0`, 대댓글은 두 ID가 모두 있고 `depth BETWEEN 1 AND 3`이다.
- self FK는 cascade를 지정하지 않는다. V32 적용 전 행은 추가 컬럼의 기본값에 따라 Root(`null/null/0`)로 보존된다.
- Root는 자신의 ID를 `root_comment_id`에 저장하지 않는다. 대댓글은 직접 부모 ID와 최상위 Root ID를 각각 저장한다.
- Service는 부모·조상 경로의 같은 `post_id`, `parent.depth + 1`, Root의 `depth=0` 및 parent/root chain 정합성을 검증한다. DB trigger로 same-post·chain 정합성을 강제하지 않는다.
- 실제 조회 인덱스: Root cursor용 `(post_id, created_at ASC, id ASC) WHERE parent_comment_id IS NULL`, 대댓글 일괄 조회용 `(root_comment_id, created_at ASC, id ASC) WHERE parent_comment_id IS NOT NULL`.

### `board_posts`

- `place_id BIGINT NULL FK places(id)` 추가

### `board_post_reactions`

- `reaction_type` 허용값에 `HELPFUL` 추가
- 기존 `UNIQUE(post_id, reactor_pet_id, reaction_type)` 유지
- 작성 당시 `board_posts.author_pet_id`가 HELPFUL 수신 Pet이며 별도 receiver 컬럼을 저장하지 않는다.

### `board_post_comment_reactions`

| 컬럼 | 형식 | 제약·의미 |
|---|---|---|
| comment_id | BIGINT | FK `board_post_comments`, NOT NULL |
| reactor_pet_id | BIGINT | FK `pets`, NOT NULL |
| reaction_type | VARCHAR(20) | `HELPFUL`만 허용 |
| created_at | TIMESTAMPTZ | NOT NULL |

- `UNIQUE(comment_id, reactor_pet_id, reaction_type)`으로 동일 Pet·댓글·타입 중복을 막는다.
- FK에 delete cascade를 두지 않는다. target soft delete는 Reaction row를 삭제하지 않고 aggregate에서 target 자신의 `deleted_at`만 제외한다.
- 댓글 작성 당시 `author_pet_id`가 HELPFUL 수신 Pet이다. Comment aggregate는 부모 댓글·부모 게시글, 차단 관계, reactor Pet의 현재 상태를 조건으로 사용하지 않는다.

## 4. Chat·Media 변경

### `chat_messages`

- `type` 허용값: `TEXT`, `CARD`, `IMAGE`, `VIDEO`, `SETLOG_SHARE`, `SYSTEM`
- `body`는 TEXT만 필수, 다른 타입은 nullable. IMAGE/VIDEO는 반드시 `NULL`이며 caption을 저장하지 않는다.
- `shared_setlog_id BIGINT NULL FK setlogs(id)` 추가
- 타입별 허용 필드는 DB CHECK 또는 Service+통합 테스트로 보장

### `chat_message_attachments`

| 컬럼 | 형식 | 제약 |
|---|---|---|
| id | BIGSERIAL | PK |
| message_id | BIGINT | FK chat_messages, NOT NULL |
| media_id | BIGINT | FK media, NOT NULL |
| attachment_type | VARCHAR(20) | IMAGE/VIDEO |
| display_order | SMALLINT | NOT NULL DEFAULT 0 |
| created_at | TIMESTAMPTZ | NOT NULL |

제약:

- `UNIQUE(message_id, display_order)`
- `UNIQUE(media_id)` 정책/제약: 한 업로드 Media는 단 한 Chat 메시지에만 첨부할 수 있으며 재사용하지 않음
- M3 기본 완료선은 메시지당 1개, 스키마는 다중 첨부 확장 가능

Media의 `attributes`에는 원본 파일명, contentType, durationMs 등 검증 metadata를 저장할 수 있으나 JWT·URL은 저장하지 않는다.

## 5. Place·Location·Walk

### `places`

| 컬럼 | 형식 | 제약 |
|---|---|---|
| id | BIGSERIAL | PK |
| provider | VARCHAR(30) | NOT NULL |
| provider_place_id | VARCHAR(255) | NOT NULL |
| type | VARCHAR(30) | HOSPITAL/PHARMACY/PARK/ETC |
| name | VARCHAR(200) | NOT NULL |
| address | VARCHAR(500) | NOT NULL |
| phone | VARCHAR(50) | nullable |
| latitude/longitude | NUMERIC | NOT NULL |
| created_at/updated_at | TIMESTAMPTZ | NOT NULL |

- `UNIQUE(provider, provider_place_id)`
- 좌표 범위 CHECK 적용
- Provider 검색 결과를 `(provider, provider_place_id)`로 upsert한 뒤 내부 `id`를 검색 응답의 `placeId`로 반환한다.

### `walk_sessions`

| 컬럼 | 형식 | 설명 |
|---|---|---|
| id | BIGSERIAL | PK |
| pet_id/user_id | BIGINT | 소유자 snapshot |
| status | VARCHAR(20) | RECORDING/COMPLETED/CANCELED |
| started_at/ended_at | TIMESTAMPTZ | 경로 시간 |
| distance_meters | NUMERIC | 서버 계산 결과 |
| duration_seconds | BIGINT | 서버 계산 결과 |
| place_id | BIGINT | nullable |

### `walk_points`

- `(walk_session_id, sequence)` UNIQUE
- latitude, longitude, accuracy_meters, captured_at
- batch upload의 `clientBatchId` UNIQUE 또는 별도 inbox 사용

## 6. Meeting

### `meeting_verifications`

Pet 별 최신 제출 1행. 같은 Pet 의 새 제출은 이 행을 대체한다.

| 컬럼 | 형식 | 설명 |
|---|---|---|
| id | BIGSERIAL | PK |
| meeting_card_id | BIGINT | composite FK → meeting_participants |
| participant_pet_id/user_id | BIGINT | 제출자 snapshot |
| latitude/longitude | NUMERIC | raw 보관. SUBMITTED 만 필수, 나머지 상태는 null(scrub) |
| accuracy_meters | NUMERIC | raw 보관. SUBMITTED 만 필수 |
| captured_at | TIMESTAMPTZ | GPS 측위시각. SUBMITTED 만 필수 |
| submitted_at | TIMESTAMPTZ | 서버 수신시각. 양쪽 제출 간격 정본 |
| status | VARCHAR(30) | SUBMITTED/CODE_REQUIRED/ACCEPTED/REJECTED/EXPIRED |
| client_request_id | UUID | 최신 제출이 사용한 멱등키(영구 정본은 meeting_verification_requests) |

- `UNIQUE(meeting_card_id, participant_pet_id)`
- `FOREIGN KEY(meeting_card_id, participant_pet_id) → meeting_participants(meeting_card_id, pet_id)`
- CHECK: `SUBMITTED` 에만 raw 좌표 필수, `CODE_REQUIRED/ACCEPTED/REJECTED/EXPIRED` 는 raw null

### `meeting_verification_requests`

immutable request 멱등 원장. 행을 대체하지 않으므로 과거 request ID 의 멱등성을 보존한다.
raw 좌표는 보관하지 않고 서버 비밀키 HMAC-SHA-256 fingerprint 만 남긴다.

| 컬럼 | 형식 | 설명 |
|---|---|---|
| client_request_id | UUID | PK, 전역 UNIQUE |
| meeting_card_id | BIGINT | FK |
| participant_pet_id | BIGINT | FK, 제출자 snapshot |
| fingerprint | VARCHAR(64) | canonical payload 의 HMAC-SHA-256 hex. raw 좌표 없음 |
| status | VARCHAR(30) | SUBMITTED/CODE_REQUIRED |
| created_at | TIMESTAMPTZ | NOT NULL |

- `PRIMARY KEY(client_request_id)`
- synthetic ID·배열 순번 없음

### `meeting_confirmation_codes`

- card_id, issuer_pet_id, code_hash, expires_at, attempts, consumed_at
- 평문 코드 저장 금지
- 카드당 활성 코드 1개

### `meetings`

- card_id UNIQUE, confirmed_at, verification_method(GPS/CODE)
- `distance_meters`: GPS 확정의 실제 계산 거리(non-null), CODE 확정은 null
- 정본은 meetingCardId / verificationMethod / confirmedAt / distanceMeters 만 둔다. 참여자·장소·시각 snapshot 은 이번 범위에서 추가하지 않는다.

### `meeting_reviews`

- meeting_id, reviewer_pet_id, content nullable, created_at
- `UNIQUE(meeting_id, reviewer_pet_id)`
- 별점 없음

### `footprints`

- meeting_id, receiver_pet_id, counterpart_pet_id, earned_date(KST), created_at
- `UNIQUE(meeting_id, receiver_pet_id)`
- 정규화 상대쌍+earned_date 중복 방지는 Service 또는 별도 pair key로 보장

## 7. 아침 약속 제안 스케줄러

> 구현: V41 migration. Scan(방+날짜) → TEXT 선별 → 기존 Meeting Draft AI 호출 → 후보 Suggestion 저장.
> 후보 목록 조회·사용자 수락/거절 UI 는 이번 범위 제외(별도 범위).

### `meeting_suggestion_scans`

| 컬럼 | 형식 | 설명 |
|---|---|---|
| id | BIGINT | PK |
| room_id | BIGINT | FK chat_rooms, ON DELETE CASCADE |
| source_date | DATE | 분석 대상 KST 전날 |
| reference_date | DATE | AI 에 전달하는 실행일. 최초 Scan 생성 값 고정, retry 에서 재계산하지 않음 |
| status | VARCHAR(20) | PENDING/PROCESSING/COMPLETED/FAILED_RETRYABLE/FAILED_FINAL |
| attempts | INTEGER | claim 시 1씩 증가 |
| next_retry_at | TIMESTAMPTZ | PENDING/FAILED_RETRYABLE 의 claim 자격 시각 |
| claim_token/claimed_at | UUID/TIMESTAMPTZ | claim fencing·lease |
| last_error | VARCHAR(500) | 최근 실패 사유 |
| completed_at | TIMESTAMPTZ | COMPLETED/FAILED_FINAL 확정 시각 |

- `UNIQUE(room_id, source_date)` — 동일 방+날짜 Scan 멱등성(DB 최종 방어선)
- claim/lease/stale fencing 은 RiskSignal Outbox 패턴(V34/V36)과 동일
- due/stale claim 조회용 partial index 2개(`next_retry_at`, `claimed_at`)

### `meeting_suggestions`

| 컬럼 | 형식 | 설명 |
|---|---|---|
| id | BIGINT | PK |
| scan_id | BIGINT | FK meeting_suggestion_scans, ON DELETE CASCADE |
| fingerprint | VARCHAR(64) | canonical 후보 의미의 SHA-256, UNIQUE |
| card_type | VARCHAR(20) | WALK/PLAY/HOSPITAL/OTHER, nullable |
| meet_date/meet_time | VARCHAR(100) | 정규화된 날짜/시각. 저장 전제가 combinedInstant 파싱 성공이므로 실제 저장값은 항상 canonical(ISO 날짜 / HH:mm) |
| place_text | VARCHAR(500) | trim + 500자 제한 |
| created_at | TIMESTAMPTZ | NOT NULL |

- `UNIQUE(fingerprint)` — 후보 멱등성 최종 방어선. 같은 의미 후보가 배열 순서 변경·retry 재응답으로 다시 와도 1건
- fingerprint = scanId + canonical type/date/time/place 정규화 값의 SHA-256 (배열 index 기반 식별 금지)

### `chat_messages` 인덱스 (V41 추가)

- `idx_chat_message_scheduler_text ON chat_messages (room_id, created_at DESC, id DESC) WHERE type = 'TEXT' AND sender_type = 'PET'`
- 스케줄러 TEXT 조회(최신 30·sender 집계) 전용 partial index. 기존 `idx_chat_message_room_id` 는 유지

## 8. Safety

### `risk_signal_outbox`

원천 도메인의 DB 변경과 같은 트랜잭션에서 적재하고, Outbox Relay가 Kafka로 전달하는 발행 대기 테이블이다. Consumer 저장·집계는 별도 범위다.

| 컬럼 | 형식 | 설명 |
|---|---|---|
| id | BIGINT | PK |
| event_id | UUID | 이벤트 멱등키 UNIQUE |
| schema_version | INTEGER | 현재 `1` |
| source_type/source_id/signal_type | VARCHAR/BIGINT/VARCHAR | `UNIQUE` 논리 멱등키 |
| actor_user_id/target_user_id | BIGINT | 행위자·대상, Kafka key는 actor_user_id |
| occurred_at | TIMESTAMPTZ | 원천 이벤트 발생 시각 |
| payload | JSONB | `RiskSignalEventV1` JSON 전체 |
| status/attempts/next_retry_at | VARCHAR/INTEGER/TIMESTAMPTZ | relay 상태·시도 횟수·다음 재시도 시각 |
| claim_token/claimed_at/published_at | UUID/TIMESTAMPTZ | relay claim fencing·lease·전송 완료 기록 |

- 현재 상태: `PENDING/PROCESSING/SENT/RETRY/FAILED`
- Relay 보장: at-least-once, Consumer는 `event_id`로 멱등 처리
- 논리 멱등키: `(source_type, source_id, signal_type)`
- 원문·JWT·이메일·정확 위치는 payload에 저장하지 않는다.

### `risk_signal_events`

| 컬럼 | 형식 | 설명 |
|---|---|---|
| id | BIGSERIAL | PK |
| event_id | UUID | 전역 멱등키 UNIQUE |
| source_type/source_id | VARCHAR/BIGINT | 원천 추적 |
| signal_type | VARCHAR(50) | GREETING_EXPIRED 등 |
| actor_user_id/target_user_id | BIGINT | 행위자·대상 |
| score | INTEGER | 정책 점수 |
| occurred_at/created_at | TIMESTAMPTZ | 시각 |
| metadata | JSONB | 원문·토큰 제외 |

### `safety_review_cases`

- subject_user_id NOT NULL, target_user_id nullable
- status `OPEN/REVIEWING/DISMISSED/WARNING_RECORDED`
- total_score, signal_count, primary_signal_type, evaluation_policy_version
- first/last_detected_at, last_evaluated_event_id, evaluated_at
- version 조건부 update와 subject/target advisory lock으로 평가·관리자 동시 처리 방지
- `(subject_user_id, target_user_id) NULLS NOT DISTINCT WHERE status IN ('OPEN','REVIEWING')` partial unique index
- Queue cursor는 갱신되는 `last_detected_at`이 아니라 `(created_at DESC, id DESC)`를 사용

### `safety_case_actions`

- case_id, admin_user_id, action_type, reason, before_state/after_state JSONB, created_at
- append-only

### `evidence_access_audits`

- case_id, admin_user_id, evidence_type, resource_id, purpose
- access_result, failure_code, accessed_at
- 대화 원문·Media URL을 저장하지 않음
- UPDATE/DELETE를 DB trigger로 거부하는 append-only 테이블

### `safety_case_evaluation_jobs`

- risk_signal_event_id UNIQUE, status `PENDING/PROCESSING/COMPLETED/FAILED`
- attempts, available_at, claimed_at, worker_id, claim_token, last_error_code
- `FOR UPDATE SKIP LOCKED` claim, lease 회수와 claimToken fencing 적용

## 9. 인덱스

- Dashboard: 각 도메인의 `(created_at)`과 상태+created_at
- Chat history: `(room_id, id)` 및 attachment message_id
- Setlog share hydration: chat_messages.shared_setlog_id
- Meeting: `(meeting_card_id, participant_pet_id)`
- Risk Queue: `(status, created_at DESC, id DESC)`
- Risk subject: `(subject_user_id, occurred_at DESC)`
- Walk points: `(walk_session_id, sequence)`

모든 인덱스는 실제 Query와 `EXPLAIN ANALYZE`로 확인하고 중복 인덱스를 피한다.

HELPFUL 평판 aggregate는 `/pets/me`의 최대 5개 Pet ID를 batch 처리한다. 10,500+ row·background Pet 분산 fixture의 `EXPLAIN ANALYZE`에서 Post/Comment 모두 target table의 author Pet filter가 약 10,000 rows를 제거하는 Seq Scan으로 나타났고, undeleted `(author_pet_id, id)` partial index가 scan 범위·buffer·cost·실행 시간을 유의미하게 줄였다. 따라서 V33에 `ix_board_posts_visible_author_pet_id`와 `ix_board_post_comments_visible_author_pet_id`를 추가한다. 이는 모든 운영 분포를 보장한다는 뜻이 아니라, 이번 현실적 fixture 범위에서는 speculative index가 아니라는 근거에 따른 결정이다.
