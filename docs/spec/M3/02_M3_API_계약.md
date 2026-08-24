# 같이놀개 M3 API 계약 요약

> 상세 JSON과 오류는 `04_M3_API_상세명세.md`를 따른다.

## 1. 공통

- 인증: Bearer JWT
- 성공 envelope: `success=true, message, data, error=null`
- 실패 envelope: `success=false, message, data=null, error={code,message}`
- 생성은 201, 조회·멱등 재요청은 200, 삭제는 204를 기본으로 한다.
- 별도 명시가 없는 목록 API는 기본 20, 최대 100이다. Chat·Place 등 개별 Endpoint의 값은 `04_M3_API_상세명세.md`를 따른다. 관리자 최근 목록은 최대 10이다.

## 2. 인증·Pet

| Method | Path | 설명 |
|---|---|---|
| GET | `/oauth2/authorization/{provider}` | OAuth 시작·Redirect |
| POST | `/auth/oauth/exchange` | 1회용 loginCode→기존 사용자 JWT 또는 신규 사용자 signupToken |
| POST | `/auth/oauth/signup` | 신규 OAuth 사용자 프로필 입력·가입 완료 |
| POST | `/auth/oauth/link` | 동일 이메일 기존 계정 본인 확인 후 Provider 연결(D-02 선택 시) |
| DELETE | `/pets/{petId}` | Pet Soft Delete |
| PUT | `/pets/{petId}/profile-image` | 프로필 이미지 교체 |
| DELETE | `/pets/{petId}/profile-image` | 프로필 이미지 제거 |

기존 `PetResponse` 소비 endpoint는 `helpfulReceivedCount`를 포함한다. 이는 삭제되지 않은 게시글과 댓글이 받은 HELPFUL 합계이며 LIKE는 포함하지 않는다. 타 사용자 public Pet profile endpoint를 새로 만들지 않는다.

## 3. 게시판

| Method | Path | 설명 |
|---|---|---|
| PUT/DELETE | `/posts/{postId}/reactions/HELPFUL` | 도움 표시 멱등 변경 |
| PUT/DELETE | `/comments/{commentId}/reactions/HELPFUL` | 댓글·대댓글 도움 표시 멱등 변경; LIKE 미지원 |
| POST | `/posts/{postId}/comments` | Root 댓글 생성. strict body는 `content`만 허용 |
| POST | `/comments/{parentCommentId}/replies` | 직접 부모 아래 대댓글 생성. strict body는 `content`만 허용 |
| GET | `/posts/{postId}/comments` | Root cursor 기반 중첩 댓글 thread 목록 |
| POST/PATCH | `/boards/{boardId}/posts`, `/posts/{postId}` | M3 Place 제품 계획 계약: `placeId`, mediaIds 지원 |

댓글 mutation 응답 `CommentResponse`는 기존 필드를 유지하면서 `parentCommentId`, `depth`를 추가한다. Root는 `parentCommentId=null`, `depth=0`; 대댓글은 직접 부모 ID와 `depth=1~3`이다. 내부 `rootCommentId`는 외부 API에 노출하지 않는다.

댓글 목록의 `size`는 댓글 행 수가 아니라 Root thread 수이며 기본 20, 최대 100이다. Root와 sibling은 모두 `(createdAt ASC, id ASC)`로 정렬한다. 목록 `items`는 `replies`를 재귀로 포함하는 tree DTO이고, cursor는 마지막 반환 Root의 불변 `(createdAt, id)` 키다. 이 GET 계약은 기존 flat all-row 목록을 대체하는 breaking change다.

대댓글 생성에서 부모가 없거나 soft delete·차단으로 보이지 않으면 `404 BOARD_POST_COMMENT_NOT_FOUND`를 사용한다. 부모가 depth 3이면 `409 COMMENT_DEPTH_EXCEEDED`다. `PARENT_COMMENT_NOT_FOUND`는 도입하지 않는다.

반응 actor는 Active Pet이고 duplicate key는 Pet+target+type이다. self 판단은 User 기준이다. Post/Comment의 지원하지 않는 reaction type은 mutation에 진입하지 않고 `400 VALIDATION_FAILED`다. 차단·공개 범위 실패는 Post `404 BOARD_POST_NOT_FOUND`, Comment target/ancestor `404 BOARD_POST_COMMENT_NOT_FOUND`로 은닉한다. 기존 Post LIKE 계약은 유지한다.

`placeId` 표기와 상세 명세는 기존 M3 Place 제품 계획 계약이며 Issue #124가 이를 구현하거나 변경하지 않는다. 현재 Issue #124 runtime PATCH parser는 strict JSON으로 `title`, `content`, `mediaIds`, `version`만 허용한다. `version`은 필수인 0 이상의 정수이고, 나머지 세 필드 중 하나 이상이 필요하다. `mediaIds`는 null이 아닌 양의 정수의 중복 없는 배열(최대 5개)이다. 생략하면 기존 링크를 보존하고, `[]`는 전체 제거하며, 값 배열은 요청 순서대로 전체 교체한다. 같은 title/content와 같은 순서의 이미지 목록은 no-op으로 version을 올리지 않는다. 배열 순서만 달라도 실제 변경이다. stale version은 `409 CONCURRENT_UPDATE_CONFLICT`다.

게시글 이미지 읽기는 Post별 조회를 반복하지 않는다. feed는 페이지 전체 링크의 Media를 한 번 batch hydrate·sign하고, detail 및 PATCH 보존 경로도 link 집합 단위로 hydrate한다. 하나라도 missing, soft-deleted 또는 다운로드 불가면 부분 이미지를 반환하지 않고 기존 단건 다운로드와 같은 읽기 실패로 전체 요청을 실패시킨다. create/PATCH의 command validation은 기존 Media ErrorCode를 유지하며, Media entity·repository·lifecycle·migration은 변경하지 않는다. feed 작성자 Pet profile URL도 fetch-join된 Media를 한 번 collection signing하여 Pet 수에 비례한 Media 조회를 만들지 않는다.

## 4. Media·Chat·Setlog 공유

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/media/init` | M3 purpose·metadata를 포함한 업로드 초기화 |
| POST | `/api/v1/media/uploaded` | multipart 완료·검증 |
| POST | `/chat/rooms/{roomId}/messages` | typed message 전송 |
| GET | `/chat/rooms/{roomId}/messages` | attachment/Setlog hydrate 이력 |

Chat typed message 공통 규칙:

- 사용자 전송 타입(`TEXT`, `IMAGE`, `VIDEO`, `SETLOG_SHARE`)은 모두 `clientMessageId`가 필요하다.
- `IMAGE`/`VIDEO`는 `body=null`만 허용하며 caption은 지원하지 않는다. 상세 메시지 조회에서만 attachment를 hydrate한다.
- 하나의 업로드 Media는 한 Chat 메시지에만 첨부할 수 있다. 같은 Media를 다른 `clientMessageId`로 전송하면 `409 CHAT_MEDIA_ALREADY_ATTACHED`다.
- 같은 `clientMessageId`와 같은 payload의 재시도는 기존 메시지를 반환하며, 이때 Media 재사용 정책을 적용해 거부하지 않는다.

`GET /chat/rooms`의 `lastMessage`는 room-list용 요약이다. `type`과 기본 메시지 필드만 반환하며 `IMAGE`/`VIDEO`의 `attachment`와 `SETLOG_SHARE`의 `sharedSetlog` 상세 hydration은 제공하지 않는다. 클라이언트는 `type`만으로 사진·동영상·셋로그 공유 텍스트 미리보기를 결정한다. 상세는 `GET /chat/rooms/{roomId}/messages`에서 조회한다.

DIRECT WebSocket:

- Publish: `/app/chat/direct/rooms/{roomId}/messages`
- Ack: `/user/queue/chat.messages`
- Event: `/user/queue/chat.messages` 또는 현재 room event 계약
- Error: `/user/queue/chat.errors`

Open Chat:

- Kafka `chat-message-topic`, key=`roomId`, eventId=`clientMessageId`
- Subscribe `/topic/chat/{roomId}`

## 5. Admin·Safety

| Method | Path | 설명 |
|---|---|---|
| GET | `/admin/dashboard` | 기간 Dashboard 통계 |
| GET | `/admin/safety/cases` | 위험 검토 Queue |
| GET | `/admin/safety/cases/{caseId}` | 위험 Case 상세 |
| POST | `/admin/safety/cases/{caseId}/actions` | 오탐·경고 기록 |
| GET | `/admin/safety/cases/{caseId}/evidence` | 감사 로그와 함께 Evidence 조회 |

## 6. RiskSignal 내부 Event

- `risk-signal-topic`, key=`actorUserId`
- 이번 공용 계약의 Producer 진입점: `RiskSourceEventPublisher.enqueue(command)`
- `enqueue`는 원천 도메인의 DB 트랜잭션 안에서 `risk_signal_outbox`에 적재한다. Relay는 별도 트랜잭션으로 선점한 뒤 기존 JSON을 Kafka에 at-least-once로 전달한다.
- Relay 상태는 `PENDING → PROCESSING → SENT/RETRY/FAILED`이고, lease 만료 `PROCESSING`은 새 `claimToken`으로 회수한다.
- 모든 완료 변경은 `id + PROCESSING + claimToken`으로 fencing하며 Consumer는 `eventId`로 멱등 처리한다.
- 현재 Source/Signal 조합: `USER_BLOCK/USER_BLOCKED`, `GREETING/GREETING_EXPIRED`
- 다른 Source/Signal 조합은 Command/Event 생성 시 거부한다.
- metadata는 Signal별 allowlist만 허용한다: `USER_BLOCKED.reasonCode`, `GREETING_EXPIRED.ttlHours`.
- eventId는 Publisher가 UUID로 생성하고, Outbox는 `event_id`와 `(source_type, source_id, signal_type)`을 모두 멱등키로 사용한다.
- Producer: Greeting/Block (Friend/Chat/AI 및 DIRECT source는 제품 의미와 원천이 확정된 뒤 추가)
- Consumer: Safety 운영 계층
- JSON은 camelCase `schemaVersion: 1`이며, 원문·JWT·이메일·정확 위치를 event에 포함하지 않는다.

## 7. Place·Meeting·Footprint

| Method | Path | 설명 |
|---|---|---|
| GET | `/places/search` | 외부 장소 검색 정규화 |
| GET | `/places/{placeId}` | 장소 상세 |
| POST | `/meeting-cards/{cardId}/meeting-verifications` | 위치 제출·판정 |
| GET | `/meeting-cards/{cardId}/meeting-verification` | 상대 대기·결과 조회 |
| POST | `/meeting-cards/{cardId}/confirmation-code/verify` | 4자리 코드 확인 |
| POST | `/meetings/{meetingId}/reviews` | 만남 후기·발자국 |
| GET | `/footprints` | 내 Active Pet 발자국 |

## 8. Walk

| Method | Path | 설명 |
|---|---|---|
| POST | `/walks` | 산책 기록 시작 |
| POST | `/walks/{walkId}/points` | 위치 point batch 업로드 |
| POST | `/walks/{walkId}/finish` | 산책 종료·집계 |
| GET | `/walks/{walkId}` | 산책 기록 상세 |
| GET | `/walk-routes/search` | 인기·검색 경로 조회 |

## 9. 기존 계약 유지

- Login/Refresh/Logout, Me, Pet 생성·조회·수정
- Board/Post/Comment 기본 CRUD
- Setlog upload/feed/reaction/greeting
- Friend/Block/Report
- Chat room, MeetingCard 생성·조회·취소
- Open Chat room·invite·join·leave·card draft

기존 endpoint를 바꾸는 경우 M3 필드 추가가 하위 호환인지 검증한다.
