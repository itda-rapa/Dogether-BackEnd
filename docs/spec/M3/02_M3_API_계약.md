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
| POST/PATCH | `/boards/{boardId}/posts`, `/posts/{postId}` | `placeId`, mediaIds 지원 |

댓글 mutation 응답 `CommentResponse`는 기존 필드를 유지하면서 `parentCommentId`, `depth`를 추가한다. Root는 `parentCommentId=null`, `depth=0`; 대댓글은 직접 부모 ID와 `depth=1~3`이다. 내부 `rootCommentId`는 외부 API에 노출하지 않는다.

댓글 목록의 `size`는 댓글 행 수가 아니라 Root thread 수이며 기본 20, 최대 100이다. Root와 sibling은 모두 `(createdAt ASC, id ASC)`로 정렬한다. 목록 `items`는 `replies`를 재귀로 포함하는 tree DTO이고, cursor는 마지막 반환 Root의 불변 `(createdAt, id)` 키다. 이 GET 계약은 기존 flat all-row 목록을 대체하는 breaking change다.

대댓글 생성에서 부모가 없거나 soft delete·차단으로 보이지 않으면 `404 BOARD_POST_COMMENT_NOT_FOUND`를 사용한다. 부모가 depth 3이면 `409 COMMENT_DEPTH_EXCEEDED`다. `PARENT_COMMENT_NOT_FOUND`는 도입하지 않는다.

반응 actor는 Active Pet이고 duplicate key는 Pet+target+type이다. self 판단은 User 기준이다. Post/Comment의 지원하지 않는 reaction type은 mutation에 진입하지 않고 `400 VALIDATION_FAILED`다. 차단·공개 범위 실패는 Post `404 BOARD_POST_NOT_FOUND`, Comment target/ancestor `404 BOARD_POST_COMMENT_NOT_FOUND`로 은닉한다. 기존 Post LIKE 계약은 유지한다.

## 4. Media·Chat·Setlog 공유

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/media/init` | M3 purpose·metadata를 포함한 업로드 초기화 |
| POST | `/api/v1/media/uploaded` | multipart 완료·검증 |
| POST | `/chat/rooms/{roomId}/messages` | typed message 전송 |
| GET | `/chat/rooms/{roomId}/messages` | attachment/Setlog hydrate 이력 |

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
- `enqueue`는 원천 도메인의 DB 트랜잭션 안에서 `risk_signal_outbox`에만 적재한다. Kafka relay/Consumer는 별도 작업이다.
- 현재 Source/Signal 조합: `USER_BLOCK/USER_BLOCKED`, `GREETING/GREETING_EXPIRED`
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
