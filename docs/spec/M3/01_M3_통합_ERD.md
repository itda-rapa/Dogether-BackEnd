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
meeting_cards 1 ─ 0..1 meetings
meetings 1 ─ N meeting_reviews
meetings 1 ─ N footprints

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
| provider | VARCHAR(20) | GOOGLE/NAVER |
| provider_subject | VARCHAR(255) | NOT NULL |
| provider_email | VARCHAR(320) | nullable |
| created_at | TIMESTAMPTZ | NOT NULL |
| updated_at | TIMESTAMPTZ | NOT NULL |

제약:

- `UNIQUE(provider, provider_subject)`
- `UNIQUE(user_id, provider)`
- Provider Access/Refresh Token은 M3 기본 범위에서 저장하지 않는다.
- `loginCode`와 `signupToken`은 원문을 DB에 영구 저장하지 않는 짧은 TTL의 1회용 자원이다. Redis 사용 시 해시값·상태·만료시각만 저장한다.

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

| 컬럼 | 형식 | 설명 |
|---|---|---|
| id | BIGSERIAL | PK |
| meeting_card_id | BIGINT | FK |
| participant_pet_id/user_id | BIGINT | 제출자 snapshot |
| latitude/longitude | NUMERIC | 정책에 따른 보존 |
| accuracy_meters | NUMERIC | NOT NULL |
| captured_at/submitted_at | TIMESTAMPTZ | NOT NULL |
| status | VARCHAR(30) | SUBMITTED/CODE_REQUIRED/ACCEPTED/REJECTED |
| client_request_id | UUID | 멱등키 |

- `UNIQUE(meeting_card_id, participant_pet_id)`
- `UNIQUE(client_request_id)`

### `meeting_confirmation_codes`

- card_id, issuer_pet_id, code_hash, expires_at, attempts, consumed_at
- 평문 코드 저장 금지
- 카드당 활성 코드 1개

### `meetings`

- card_id UNIQUE, confirmed_at, verification_method(GPS/CODE)
- 참여자·장소·시각 snapshot

### `meeting_reviews`

- meeting_id, reviewer_pet_id, content nullable, created_at
- `UNIQUE(meeting_id, reviewer_pet_id)`
- 별점 없음

### `footprints`

- meeting_id, receiver_pet_id, counterpart_pet_id, earned_date(KST), created_at
- `UNIQUE(meeting_id, receiver_pet_id)`
- 정규화 상대쌍+earned_date 중복 방지는 Service 또는 별도 pair key로 보장

## 7. Safety

### `risk_signal_outbox`

원천 도메인의 DB 변경과 같은 트랜잭션에서 적재하는 Kafka 발행 대기 테이블이다. 이번 범위에는 relay/consumer는 포함하지 않는다.

| 컬럼 | 형식 | 설명 |
|---|---|---|
| id | BIGINT | PK |
| event_id | UUID | 이벤트 멱등키 UNIQUE |
| schema_version | INTEGER | 현재 `1` |
| source_type/source_id/signal_type | VARCHAR/BIGINT/VARCHAR | `UNIQUE` 논리 멱등키 |
| actor_user_id/target_user_id | BIGINT | 행위자·대상, Kafka key는 actor_user_id |
| occurred_at | TIMESTAMPTZ | 원천 이벤트 발생 시각 |
| payload | JSONB | `RiskSignalEventV1` JSON 전체 |
| status/attempts/next_retry_at | VARCHAR/INTEGER/TIMESTAMPTZ | 향후 relay 재시도 상태 |
| claim_token/claimed_at/published_at | UUID/TIMESTAMPTZ | 향후 relay claim·전송 완료 기록 |

- 현재 상태: `PENDING/PROCESSING/SENT/RETRY/FAILED`
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

- subject_user_id, target_user_id nullable
- status `OPEN/REVIEWING/DISMISSED/WARNING_RECORDED`
- total_score, signal_count, first/last_detected_at
- version 또는 조건부 update로 동시 처리 방지
- 동일 subject/target/open 정책에 맞는 partial unique index 검토

### `safety_case_actions`

- case_id, admin_user_id, action_type, reason, before_state/after_state JSONB, created_at
- append-only

### `evidence_access_audits`

- case_id, admin_user_id, evidence_type, resource_id, purpose, accessed_at
- 대화 원문·Media URL을 저장하지 않음

## 8. 인덱스

- Dashboard: 각 도메인의 `(created_at)`과 상태+created_at
- Chat history: `(room_id, id)` 및 attachment message_id
- Setlog share hydration: chat_messages.shared_setlog_id
- Meeting: `(meeting_card_id, participant_pet_id)`
- Risk Queue: `(status, last_detected_at DESC, id DESC)`
- Risk subject: `(subject_user_id, occurred_at DESC)`
- Walk points: `(walk_session_id, sequence)`

모든 인덱스는 실제 Query와 `EXPLAIN ANALYZE`로 확인하고 중복 인덱스를 피한다.

HELPFUL 평판 aggregate는 `/pets/me`의 최대 5개 Pet ID를 batch 처리한다. 10,500+ row·background Pet 분산 fixture의 `EXPLAIN ANALYZE`에서 Post/Comment 모두 target table의 author Pet filter가 약 10,000 rows를 제거하는 Seq Scan으로 나타났고, undeleted `(author_pet_id, id)` partial index가 scan 범위·buffer·cost·실행 시간을 유의미하게 줄였다. 따라서 V33에 `ix_board_posts_visible_author_pet_id`와 `ix_board_post_comments_visible_author_pet_id`를 추가한다. 이는 모든 운영 분포를 보장한다는 뜻이 아니라, 이번 현실적 fixture 범위에서는 speculative index가 아니라는 근거에 따른 결정이다.
