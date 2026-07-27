# 같이놀개 M1 API 계약 요약

> REST 계약 정본: `04_M1_OpenAPI.yaml`
> 사람이 읽는 상세 설명: `04_M1_API_명세.md`
> 이 문서는 빠른 확인용 요약이며, 충돌 시 정적 OpenAPI를 따른다.

## 인증·회원

| Method | Path | 설명 |
|---|---|---|
| POST | `/auth/signup` | 이메일 회원가입 + 동네 직접 선택 |
| POST | `/auth/login` | 이메일 로그인 |
| POST | `/auth/refresh` | Refresh Token 회전 |
| POST | `/auth/logout` | 현재 사용자의 Refresh Token 폐기 |
| GET | `/me` | 내 정보·권한 단계·Active Pet |
| PUT | `/me/active-pet` | Active Pet 변경 |

M1 제외: Google 로그인, 이메일 인증, 비밀번호 찾기·재설정, GPS 확인, 회원탈퇴.
`DELETE /me`와 탈퇴 Cleanup·관계 정리는 POST-M1에서 별도 설계한다.

## Pet

| Method | Path | 설명 |
|---|---|---|
| POST | `/pets` | 직접 입력 Pet 등록 |
| GET | `/pets/me` | Active Pet 우선, 나머지 생성순 내 Pet 목록 |
| GET | `/pets/{petId}` | Pet 상세 |
| PATCH | `/pets/{petId}` | Pet 수정, 인증 근거 변경 시 배지 해제 |
| DELETE | `/pets/{petId}` | Active Pet 삭제 금지 |
| GET | `/pets/search?publicTag=` | Pet 공개 태그 정확 검색 |
| POST | `/pet-registration/attempts` | 등록번호/RFID 조회 |
| POST | `/pet-registration/attempts/{attemptId}/consume` | 조회 결과 확인 후 Pet에 적용 |

- 같은 owner의 `deleted_at IS NULL` Pet은 최대 5마리이며 `SUSPENDED`도 포함한다.
- 한도 초과는 `409 PET_LIMIT_EXCEEDED`다.
- `status = DELETED ↔ deleted_at IS NOT NULL`을 보장한다.
- Pet 생성 트랜잭션에서 내부 `firstPetCandidate`를 확정하고 Commit한다.
- 생성 Commit 뒤 후보인 경우에만 별도 트랜잭션으로 Active 지정을 시도한다.
- 예상 가능한 일시적 Active 지정 실패에는 `201 Created`와 생성된 Pet,
  `RETRY_REQUIRED`를 반환한다. DB 장애·무결성 오류·코딩 오류는 정상 오류로 처리한다.
- L1 사용자는 `PUT /me/active-pet`으로 본인 소유의 `ACTIVE`·미삭제 Pet을 선택할 수 있다.
- 등록정보 Provider는 M1에서 동기 처리하며 canonical 등록번호가 없으면 `REJECTED`로 종결한다.
- Attempt는 Provider 완료 후 최종 상태만 저장하고 DB에 `PENDING`을 저장하지 않는다.
- consume은 `SUCCEEDED` Attempt에서만 허용하고 중복 consume은 `409`다.
- PublicTag는 User와 별도 Namespace이며 nickname 앞 25개 Unicode code point와
  `#XXXX`로 생성한다. 충돌 시 새 트랜잭션에서 최대 5회 재시도한다.
- nickname은 trim 후 1자 이상, M1 `profileUrl`은 null이다.
- M1은 Pet 생성 Idempotency-Key를 제공하지 않는다. timeout·5xx 뒤에는
  `GET /pets/me`로 생성 여부를 확인하고 POST를 무작정 재시도하지 않는다.

## 시드 셋로그·반응·인사

| Method | Path | 설명 |
|---|---|---|
| GET | `/setlogs` | L1 이상 공통 시드 셋로그 3개 조회 |
| PUT | `/setlogs/{setlogId}/reactions/{type}` | CUTE 또는 LIKE 추가 |
| DELETE | `/setlogs/{setlogId}/reactions/{type}` | 반응 취소 |
| POST | `/setlogs/{setlogId}/greetings` | DIRECT 방·최초 인사 메시지 생성 |

`인사해요`는 Reaction이 아니다. M1 일반 사용자의 Setlog 업로드 API는 없다.
인사는 요청 본문 없이 서버 고정 문구 `안녕하세요! 같이 놀아요.`를 저장한다.
시드 영상은 Presigned GET URL과 만료 시각을 함께 반환한다.

## 친구

| Method | Path | 설명 |
|---|---|---|
| POST | `/friend-requests` | Active Pet으로 요청 |
| GET | `/friend-requests/received` | 받은 요청 |
| GET | `/friend-requests/sent` | 보낸 요청 |
| POST | `/friend-requests/{requestId}/accept` | 수락 |
| POST | `/friend-requests/{requestId}/reject` | 거절 |
| DELETE | `/friend-requests/{requestId}` | 발신 요청 취소 |
| GET | `/pets/{petId}/friends` | 친구 목록 |
| DELETE | `/pets/{petId}/friends/{friendPetId}` | 친구 삭제, 기존 방 유지 |

반대 방향 PENDING 요청은 새 요청을 만들지 않고 자동수락한다.

## 채팅·폴링

| Method | Path | 설명 |
|---|---|---|
| GET | `/chat/rooms` | 방 목록 폴링 |
| GET | `/chat/rooms/{roomId}` | 방 상세 조회 |
| GET | `/chat/rooms/{roomId}/messages` | 메시지 폴링 |
| POST | `/chat/rooms/{roomId}/messages` | TEXT 전송 |

- 최초 인사 후 상대 답변 전 추가 전송은 금지한다.
- 사용자 요청은 TEXT만 허용한다.
- CARD와 SYSTEM은 서버가 생성한다.
- 읽음·수정·삭제·첨부·Push는 M1에 없다.

## 약속 카드

| Method | Path | 설명 |
|---|---|---|
| POST | `/chat/rooms/{roomId}/card-drafts` | 최근 30개·24시간 이내 메시지로 초안 생성 |
| POST | `/meeting-cards` | 필수값 확인 후 카드 확정 |
| GET | `/meeting-cards/{cardId}` | 카드 상세 |
| POST | `/meeting-cards/{cardId}/cancel` | 참여 Pet 양쪽 모두 취소 가능 |

M1에서는 수정·참여·퇴장 API를 제공하지 않는다.
카드 종류는 `WALK`, `PLAY`, `OTHER`다.
프런트는 최근 24시간 내 사용자 `TEXT` 메시지가 2개 이상일 때 버튼을 활성화한다.
서버는 0~1개에서도 요청을 거절하지 않고 `200`과 빈 폼,
`fallbackReason=INSUFFICIENT_CONTEXT`를 반환한다. `CARD`, `SYSTEM`은 개수에서 제외한다.

## 차단·신고·관리자

| Method | Path | 설명 |
|---|---|---|
| POST | `/me/blocks` | User 단위 차단 |
| GET | `/me/blocks` | 차단 목록 |
| POST | `/reports` | DIRECT 방 신고 |
| GET | `/admin/reports` | 신고 큐 |
| GET | `/admin/reports/{reportId}` | 관리자 전용 Evidence DTO로 신고자·피신고자·양쪽 Pet·방·전체 메시지 조회 |
| POST | `/admin/reports/{reportId}/actions` | 관리자 처리 |

M1 차단 해제 API는 없다. 신고는 차단을 자동 수행하지 않는다.
신고 사유는 `HARASSMENT`, `SPAM`, `OTHER`이며 `OTHER`는 상세 사유가 필수다.
동일 신고자·방의 OPEN 신고는 기존 건을 반환하고 후속 입력은 무시한다.
관리자 처리는 `DISMISSED`, `WARNING`이며 사용자에게 WARNING을 노출하지 않는다.

## 공통 오류 코드

기본 HTTP Status는 `400` 형식·검증 오류, `403` 권한·행위 금지,
`404` 조회 불가, `409` 중복·한도·상태 충돌로 단순화한다.
다른 Status가 필요한 endpoint만 계약에 명시적으로 추가한다.
별도 Status는 정적 OpenAPI → ErrorCode → 구현 → 테스트 순으로 같은 PR에서 변경한다.

- `PET_REQUIRED`
- `ACTIVE_PET_REQUIRED`
- `PET_LIMIT_EXCEEDED`
- `ACTIVE_PET_DELETE_FORBIDDEN`
- `SAME_OWNER_INTERACTION_FORBIDDEN`
- `GREETING_ALREADY_USED`
- `GREETING_DAILY_LIMIT_EXCEEDED`
- `GREETING_REPLY_REQUIRED`
- `FRIEND_LIMIT_EXCEEDED`
- `FRIEND_REQUEST_NOT_PENDING`
- `BLOCKED_USER`
- `MEDIA_PURPOSE_FORBIDDEN`
- `SETLOG_SELF_REACTION_FORBIDDEN`
- `MEETING_CARD_NOT_EDITABLE`
- `MEETING_CARD_CANCEL_FORBIDDEN`
- `REPORT_ROOM_REQUIRED`
