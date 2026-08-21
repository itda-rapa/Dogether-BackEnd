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

## 3. 게시판

| Method | Path | 설명 |
|---|---|---|
| PUT/DELETE | `/posts/{postId}/reactions/HELPFUL` | 도움 표시 멱등 변경 |
| POST | `/posts/{postId}/comments` | `parentCommentId`로 대댓글 생성 |
| GET | `/posts/{postId}/comments` | flat cursor 목록과 parent/depth 반환 |
| POST/PATCH | `/boards/{boardId}/posts`, `/posts/{postId}` | `placeId`, mediaIds 지원 |

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
- Producer: Greeting/Friend/Block/Chat/AI
- Consumer: Safety 운영 계층
- 원문을 event에 포함하지 않는다.

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
