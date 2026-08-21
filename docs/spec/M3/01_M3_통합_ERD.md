# 같이놀개 M3 통합 ERD

> 표는 M3 신규·변경분 중심이다. 기존 M1/M2 테이블은 현재 Flyway를 계승하며 적용된 Migration을 수정하지 않는다.

## 1. 관계 요약

```text
users 1 ─ N oauth_identities
users 1 ─ N pets
pets 1 ─ N board_posts / comments / chat_messages

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

- `parent_comment_id BIGINT NULL FK board_post_comments(id)` 추가
- `depth SMALLINT NOT NULL DEFAULT 0`
- 권고 제약: `depth IN (0,1)`
- 부모와 자식은 같은 `post_id`여야 하며 Service에서 검증한다.

### `board_posts`

- `place_id BIGINT NULL FK places(id)` 추가

### `board_post_reactions`

- `reaction_type` 허용값에 `HELPFUL` 추가
- 기존 `UNIQUE(post_id, reactor_pet_id, reaction_type)` 유지

## 4. Chat·Media 변경

### `chat_messages`

- `type` 허용값: `TEXT`, `CARD`, `IMAGE`, `VIDEO`, `SETLOG_SHARE`, `SYSTEM`
- `body`는 TEXT만 필수, 다른 타입은 nullable
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
- `UNIQUE(media_id)` 권고: 한 업로드를 여러 메시지에 재사용하지 않음
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
