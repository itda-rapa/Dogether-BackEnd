# 같이놀개 M3 API 상세명세

> 이 문서는 M3 신규·변경 API의 JSON 계약 정본 초안이다.  
> 기존 API는 현재 Controller 계약을 유지하며 M3 필드를 하위 호환 방식으로 추가한다.

## 1. 공통 규칙

### 경로

- 아래 Path는 애플리케이션 Controller 경로다.
- Gateway가 외부 `/api/v1` prefix를 제공하면 Controller에 다시 붙이지 않는다.
- 기존 Media Controller의 `/api/v1/media/**`는 현재 코드 호환 경로이며 후속 정리 시 redirect가 아니라 명시적 계약 변경이 필요하다.

### 인증 Header

```http
Authorization: Bearer {accessToken}
Content-Type: application/json
```

### 성공 envelope

```json
{
  "success": true,
  "message": "요청이 처리되었습니다.",
  "data": {},
  "error": null
}
```

### 실패 envelope

```json
{
  "success": false,
  "message": "해당 요청이 실패되었습니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "검증에 실패했습니다."
  }
}
```

### 공통 타입

- ID: 양의 정수
- UUID: RFC 4122 문자열
- Instant: ISO 8601 UTC, 예: `2026-08-20T09:00:00Z`
- 좌표: WGS84 decimal degree
- Cursor: 서버가 발급한 opaque 문자열을 Client가 해석하지 않음
- 알 수 없는 JSON 필드는 `400 VALIDATION_FAILED`로 거절하는 것을 권장

---

## 2. OAuth

### `GET /oauth2/authorization/{provider}`

- 인증: 불필요
- Provider: `google`, `naver`
- 응답: Provider 인증 페이지로 `302`
- 서버는 State·PKCE·Callback 정보를 짧은 TTL로 저장한다.

Callback 흐름:

```text
Provider 인증
→ Backend /login/oauth2/code/{provider} callback
→ State·Provider 사용자 검증
→ 1회용 loginCode 발급
→ Front callback URL?loginCode={code}&provider={provider} 로 302
→ Front가 POST /auth/oauth/exchange 호출
```

- Access/Refresh Token은 Redirect URL에 포함하지 않는다.
- callback 실패 시 Front 오류 URL에는 민감한 Provider 응답을 포함하지 않고 표준 오류 코드만 전달한다.

오류: `400 OAUTH_PROVIDER_UNSUPPORTED`, `503 OAUTH_PROVIDER_UNAVAILABLE`.

### `POST /auth/oauth/exchange`

- 인증: 불필요
- 성공: `200`
- 멱등: loginCode는 1회용이므로 재사용 불가

요청:

```json
{
  "provider": "GOOGLE",
  "loginCode": "ZHVtbXktb25lLXRpbWUtbG9naW4tY29kZQ"
}
```

기존 OAuth 연결 사용자 응답은 현재 `AuthTokensResponse`를 그대로 재사용한다.

```json
{
  "success": true,
  "message": "OAuth 로그인 성공",
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi...",
    "accessTokenExpiresAt": "2026-08-20T09:15:00Z"
  },
  "error": null
}
```

신규 사용자이면 `202 Accepted`로 가입 완료용 Token만 반환한다.

```json
{
  "success": true,
  "message": "OAuth 사용자 프로필 입력이 필요합니다.",
  "data": {
    "profileCompletionRequired": true,
    "signupToken": "opaque-one-time-signup-token",
    "signupTokenExpiresAt": "2026-08-20T09:10:00Z"
  },
  "error": null
}
```

오류:

- `400 OAUTH_PROVIDER_UNSUPPORTED`
- `401 OAUTH_LOGIN_CODE_INVALID`
- `410 OAUTH_LOGIN_CODE_EXPIRED`
- `410 OAUTH_LOGIN_CODE_CONSUMED`
- `409 OAUTH_ACCOUNT_LINK_DECISION_REQUIRED`
- `503 OAUTH_PROVIDER_UNAVAILABLE`

### `POST /auth/oauth/signup`

- 인증: Access Token 불필요, 1회용 `signupToken` 필수
- 성공: `201`
- 이메일·Provider 식별자는 검증된 signupToken에서 가져오며 Client 입력을 신뢰하지 않는다.

요청:

```json
{
  "signupToken": "opaque-one-time-signup-token",
  "nickname": "동훈",
  "neighborhoodCode": "1114010100"
}
```

응답은 기존 `AuthTokensResponse`를 재사용한다.

```json
{
  "success": true,
  "message": "OAuth 회원가입이 완료되었습니다.",
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi...",
    "accessTokenExpiresAt": "2026-08-20T09:15:00Z"
  },
  "error": null
}
```

오류: `401 OAUTH_SIGNUP_TOKEN_INVALID`, `410 OAUTH_SIGNUP_TOKEN_EXPIRED`, `409 NICKNAME_ALREADY_EXISTS`, `404 NEIGHBORHOOD_NOT_FOUND`.

### `POST /auth/oauth/link` — D-02에서 명시적 연결 선택 시

- 인증: 동일 이메일의 기존 계정 JWT 필수
- 목적: 이메일 일치만으로 자동 연결하지 않고 기존 계정 본인 확인 뒤 OAuth Identity를 연결
- D-02에서 자동 연결 또는 연결 거부를 선택하면 이 Endpoint는 구현하지 않는다.

요청:

```json
{
  "provider": "GOOGLE",
  "loginCode": "unconsumed-one-time-login-code"
}
```

응답:

```json
{
  "success": true,
  "message": "OAuth 계정이 연결되었습니다.",
  "data": {
    "provider": "GOOGLE",
    "linked": true
  },
  "error": null
}
```

---

## 3. Pet 삭제·이미지

### `DELETE /pets/{petId}`

- 인증: Pet 소유 User
- 성공: `204 No Content`
- Body 없음

규칙:

- 사용자의 현재 대표 반려견은 다른 Pet을 먼저 대표로 변경하기 전 삭제할 수 없다.
- 이미 삭제된 Pet은 `404`로 수렴하는 것을 권장한다.
- Open Chat 참여 기록과 과거 데이터의 보존·표시 방식은 D-03을 따른다.
- Friendship·Pending Request 정리는 확정 요구사항이 아니며 별도 영향도 검토 대상으로 둔다.

오류: `404 PET_NOT_FOUND`, `403 PET_NOT_OWNED`, `409 ACTIVE_PET_DELETE_FORBIDDEN`, `409 PET_DELETE_REFERENCE_CONFLICT`.

### `PUT /pets/{petId}/profile-image`

- Header: `If-Match: "{petVersion}"` 필수
- PUT/DELETE 모두 같은 Pet version 낙관적 잠금 정책을 사용한다.

요청:

```json
{
  "mediaId": 501
}
```

응답:

```json
{
  "success": true,
  "message": "프로필 이미지가 변경되었습니다.",
  "data": {
    "petId": 12,
    "profileImage": {
      "mediaId": 501,
      "contentType": "image/jpeg",
      "url": "https://storage.example/presigned...",
      "expiresAt": "2026-08-20T09:15:00Z"
    },
    "version": 4
  },
  "error": null
}
```

오류: `404 PET_NOT_FOUND`, `404 MEDIA_NOT_FOUND`, `403 MEDIA_NOT_OWNED`, `422 INVALID_MEDIA_TYPE`, `409 CONCURRENT_UPDATE_CONFLICT`.

### `DELETE /pets/{petId}/profile-image`

- Header: `If-Match: "{petVersion}"` 필수
- 성공: `204`
- 기존 Media link를 해제하고, 다른 유효 참조 또는 Evidence 보존 정책이 없는 경우 Media 정책에 따라 StorageDeleteJob 대상에 등록한다.
- 오류: `409 CONCURRENT_UPDATE_CONFLICT`를 PUT과 동일하게 적용한다.

---

## 4. 게시판 변경

### `PUT /posts/{postId}/reactions/HELPFUL`

- 인증: Active Pet 필수
- 멱등: 이미 반응했으면 상태를 유지하고 200
- actor는 요청 시점 Active Pet이며, 동일 Pet·Post·type은 한 행이다. self는 User 기준이므로 Pet 변경으로 자신의 Post 반응을 우회할 수 없다.
- Post Reaction은 `LIKE`, `HELPFUL`만 지원한다. 이 endpoint의 type 이외의 값은 `400 VALIDATION_FAILED`이고 mutation에 진입하지 않는다.

응답:

```json
{
  "success": true,
  "message": "게시글 반응 상태가 변경되었습니다.",
  "data": {
    "postId": 41,
    "type": "HELPFUL",
    "reacted": true,
    "reactionCount": 24
  },
  "error": null
}
```

### `DELETE /posts/{postId}/reactions/HELPFUL`

응답은 `reacted=false`와 현재 `reactionCount`를 반환한다. 존재하지 않아도 200이다.

오류: `404 BOARD_POST_NOT_FOUND`, `403 ACTIVE_PET_REQUIRED`, `403 BOARD_POST_SELF_REACTION_FORBIDDEN`, `400 VALIDATION_FAILED`.

삭제·비공개·지역 외 또는 양방향 차단 Post는 모두 `404 BOARD_POST_NOT_FOUND`로 은닉한다. 차단은 기존 Reaction/평판을 소급 제거하지 않는다.

### `POST /posts/{postId}/comments`

Root 댓글 작성이다. 요청 본문은 strict JSON이며 서버 관리 필드(`parentCommentId`, `rootCommentId`, `depth`, 작성자, version)를 받지 않는다.

```json
{
  "content": "정보 감사합니다."
}
```

응답은 `201 ApiResponse<CommentResponse>`다. Root의 hierarchy 필드는 `parentCommentId: null`, `depth: 0`이다.

### `POST /comments/{parentCommentId}/replies`

직접 부모 ID는 path로만 받고, body는 Root 작성과 동일하게 `content`만 허용한다.

```json
{
  "content": "저도 같은 경험이 있었어요."
}
```

대댓글은 Root부터 직접 부모까지의 조상 path가 같은 게시글·정상 hierarchy인지 확인하고, path 중 어느 작성자와도 양방향 차단 관계가 없어야 한다. deleted 조상 자체는 숨김 사유가 아니지만, blocked 조상은 그 subtree를 숨기므로 404다. 부모가 depth 3이면 `409 COMMENT_DEPTH_EXCEEDED`다.

응답은 `201 ApiResponse<CommentResponse>`다.

```json
{
  "success": true,
  "message": "대댓글이 등록되었습니다.",
  "data": {
    "commentId": 302,
    "postId": 41,
    "parentCommentId": 301,
    "depth": 1,
    "content": "저도 같은 경험이 있었어요.",
    "authorPet": {
      "petId": 12,
      "publicTag": "dog-12",
      "nickname": "보리",
      "profileUrl": null,
      "verified": false
    },
    "version": 0,
    "createdAt": "2026-08-20T09:00:00Z",
    "updatedAt": "2026-08-20T09:00:00Z"
  },
  "error": null
}
```

오류: 게시글이 없거나 공개 범위 밖이면 `404 BOARD_POST_NOT_FOUND`; 부모가 없거나 soft delete·차단으로 보이지 않으면 `404 BOARD_POST_COMMENT_NOT_FOUND`; depth 3 부모 아래 생성은 `409 COMMENT_DEPTH_EXCEEDED`; 요청 형식·content 검증 실패는 `400 VALIDATION_FAILED`다. `PARENT_COMMENT_NOT_FOUND`와 `BLOCKED_USER`는 이 API에 사용하지 않는다.

`PATCH /comments/{commentId}`도 같은 `CommentResponse`를 반환하므로 Root/대댓글의 `parentCommentId`, `depth`를 보존한다. `DELETE /comments/{commentId}`는 Root와 대댓글 모두 soft delete하며 `204`다.

### `PUT /comments/{commentId}/reactions/HELPFUL`

- 인증: Active Pet 필수, body 없음, `200 ApiResponse<CommentReactionResponse>`.
- actor는 요청 시점 Active Pet이고 self는 User 기준이다. 같은 Pet·Comment·HELPFUL은 하나이며 이미 존재해도 `reacted=true`로 200이다.
- Comment Reaction은 `HELPFUL`만 허용한다. `LIKE` 또는 기타 type은 `400 VALIDATION_FAILED`이고 mutation에 진입하지 않는다.
- target이 deleted이거나 hierarchy가 잘못되었거나 target/Root→target 조상 작성자와 양방향 차단이면 `404 BOARD_POST_COMMENT_NOT_FOUND`다. 부모 Post가 deleted·비공개이거나 작성자와 양방향 차단이면 `404 BOARD_POST_NOT_FOUND`다.

```json
{"success":true,"message":"댓글 반응 상태가 변경되었습니다.","data":{"commentId":302,"type":"HELPFUL","reacted":true,"reactionCount":7},"error":null}
```

### `DELETE /comments/{commentId}/reactions/HELPFUL`

동일 visibility·Active Pet·self 규칙을 적용한다. 존재하지 않는 상태의 취소도 `reacted=false`와 현재 `reactionCount`를 포함해 200이다.

### `GET /posts/{postId}/comments`

- 기존 envelope의 `items`, `page.nextCursor`, `page.hasNext`는 유지하지만, `items`는 nested Root thread 목록으로 변경된다.
- `size`는 댓글 row 수가 아닌 Root thread 수다. 기본 20, 최대 100이며 cursor는 마지막 반환 Root의 불변 `(createdAt, id)` 키다. Root 및 sibling은 `(createdAt, id)` 오름차순이다. 새 대댓글은 Root cursor key를 바꾸지 않는다.
- 최종 Root visibility는 blocked Root 제외, deleted Root의 최종 visible active descendant 유무까지 반영한 candidate에 대해 `size + 1`로 계산한다. 따라서 `hasNext`·`nextCursor`는 최종 Root candidate에서 나온다.
- 양방향 차단된 노드는 descendants까지 제거하며, 차단되지 않은 descendant를 상위로 승격하지 않는다. deleted node는 활성 자손을 연결하는 경우에만 tombstone으로 남고, deleted leaf는 숨긴다.
- `rootCommentId`는 노출하지 않는다. 활성 노드는 non-null `helpfulCount`, `helpfulByMe`를 반환한다. tombstone은 tree 연결에 필요한 `commentId`, `postId`, `parentCommentId`, `depth`, `deleted=true`, `replies`만 반환하고 `authorPet`, `content`, `version`, `createdAt`, `updatedAt`, `helpfulCount`, `helpfulByMe`은 null이다.

Query: `cursor` optional, `size` 기본 20·최대 100.

```json
{
  "success": true,
  "message": "댓글 목록이 조회되었습니다.",
  "data": {
    "items": [
      {
        "commentId": 301,
        "postId": 41,
        "parentCommentId": null,
        "depth": 0,
        "deleted": false,
        "authorPet": {
          "petId": 8,
          "publicTag": "dog-8",
          "nickname": "두부",
          "profileUrl": null,
          "verified": false
        },
        "content": "정보 감사합니다.",
        "version": 0,
        "createdAt": "2026-08-20T08:59:00Z",
        "updatedAt": "2026-08-20T08:59:00Z",
        "helpfulCount": 3,
        "helpfulByMe": false,
        "replies": [
          {
            "commentId": 302,
            "postId": 41,
            "parentCommentId": 301,
            "depth": 1,
            "deleted": false,
            "authorPet": {
              "petId": 12,
              "publicTag": "dog-12",
              "nickname": "보리",
              "profileUrl": null,
              "verified": false
            },
            "content": "저도 같은 경험이 있었어요.",
            "version": 0,
            "createdAt": "2026-08-20T09:00:00Z",
            "updatedAt": "2026-08-20T09:00:00Z",
            "helpfulCount": 1,
            "helpfulByMe": true,
            "replies": []
          }
        ]
      }
    ],
    "page": {
      "nextCursor": null,
      "hasNext": false
    }
  },
  "error": null
}
```

### `POST /boards/{boardId}/posts`

`POST /boards/{boardId}/posts` 요청 예시:

```json
{
  "title": "야간 진료 병원 후기",
  "content": "늦은 시간에도 진료를 받았습니다.",
  "mediaIds": [501, 502],
  "placeId": 91
}
```

응답에 다음을 추가한다.

```json
{
  "place": {
    "placeId": 91,
    "type": "HOSPITAL",
    "name": "같이동물병원",
    "address": "서울특별시 중구 ...",
    "latitude": 37.5665,
    "longitude": 126.978
  }
}
```

전체 응답 예시:

```json
{
  "success": true,
  "message": "게시글이 등록되었습니다.",
  "data": {
    "postId": 41,
    "boardId": 3,
    "authorPet": {
      "petId": 12,
      "publicTag": "dog-12",
      "nickname": "보리",
      "profileUrl": null,
      "verified": false
    },
    "title": "야간 진료 병원 후기",
    "content": "늦은 시간에도 진료를 받았습니다.",
    "images": [
      {
        "mediaId": 501,
        "url": "https://storage.example/presigned...",
        "displayOrder": 0
      }
    ],
    "place": {
      "placeId": 91,
      "type": "HOSPITAL",
      "name": "같이동물병원",
      "address": "서울특별시 중구 ...",
      "latitude": 37.5665,
      "longitude": 126.978
    },
    "reactionCount": 4,
    "reactedByMe": true,
    "helpfulCount": 24,
    "helpfulByMe": false,
    "version": 0,
    "createdAt": "2026-08-20T09:00:00Z",
    "updatedAt": "2026-08-20T09:00:00Z"
  },
  "error": null
}
```

### `PATCH /posts/{postId}`

```json
{
  "title": "야간 진료 병원 후기 수정",
  "content": "주차 정보도 추가합니다.",
  "mediaIds": [501],
  "placeId": 91,
  "version": 2
}
```

성공 응답은 생성과 같은 Post 상세에 증가한 `version`을 포함한다.

PATCH 필드 의미:

- 필드 생략: 기존 값 유지
- `placeId: null`: Place 연결 제거
- `placeId: 91`: Place 교체
- `mediaIds` 생략: 기존 이미지 유지
- `mediaIds: []`: 첨부 이미지 전체 제거
- `mediaIds: [...]`: 전달한 순서의 목록으로 전체 교체

게시글 생성·수정·목록·상세 응답에서 기존 `reactionCount`·`reactedByMe`는 LIKE 의미를 유지한다. HELPFUL 상태는 `helpfulCount`·`helpfulByMe`로 별도 제공한다.

기존 `PetResponse`를 반환하는 내 Pet 생성·목록·상세·수정·초기 프로필 이미지 설정 응답에는 `helpfulReceivedCount`가 포함된다. HELPFUL만 합산하며 삭제 target 자신의 row만 제외한다. 공개 타 사용자 Pet profile endpoint, `PetSearchItemResponse`, `PetDisplaySummary` 확장은 이번 범위가 아니다.

오류: `404 PLACE_NOT_FOUND`, `404 MEDIA_NOT_FOUND`, `403 MEDIA_NOT_OWNED`, `422 INVALID_MEDIA_TYPE`.

---

## 5. Media 업로드

### `POST /api/v1/media/init`

- 인증: 로그인 User
- 성공: `201`
- M3 변경: 목적·파일명·contentType·duration metadata 추가

요청:

```json
{
  "purpose": "CHAT_ATTACHMENT",
  "mediaType": "VIDEO",
  "fileName": "walk-together.mp4",
  "contentType": "video/mp4",
  "fileSize": 5242880,
  "durationMs": 4200
}
```

단일 PUT 응답 예시:

```json
{
  "success": true,
  "message": "Media 객체가 초기화되었습니다.",
  "data": {
    "id": 501,
    "purpose": "CHAT_ATTACHMENT",
    "mediaType": "VIDEO",
    "status": "INIT",
    "presignedUrl": "https://storage.example/upload...",
    "uploadId": null,
    "presignedUrlParts": null,
    "requiredHeaders": {
      "Content-Type": "video/mp4"
    },
    "expiresAt": "2026-08-20T09:15:00Z"
  },
  "error": null
}
```

Multipart 응답은 `presignedUrl=null`, `uploadId`와 `presignedUrlParts[]`를 반환한다.

오류: `400 VALIDATION_FAILED`, `413 MEDIA_SIZE_INVALID`, `415 INVALID_MEDIA_TYPE`, `403 MEDIA_PURPOSE_FORBIDDEN`, `503 MEDIA_STORAGE_UNAVAILABLE`.

### `POST /api/v1/media/uploaded`

요청:

```json
{
  "mediaId": 501,
  "parts": [
    { "partNumber": 1, "eTag": "etag-part-1" },
    { "partNumber": 2, "eTag": "etag-part-2" }
  ]
}
```

응답:

```json
{
  "success": true,
  "message": "성공적으로 업로드되었습니다.",
  "data": {
    "id": 501,
    "mediaType": "VIDEO",
    "purpose": "CHAT_ATTACHMENT",
    "status": "COMPLETED",
    "fileSize": 5242880,
    "attributes": {
      "contentType": "video/mp4",
      "durationMs": 4200,
      "originalFileName": "walk-together.mp4"
    },
    "createdAt": "2026-08-20T08:58:00Z",
    "modifiedAt": "2026-08-20T09:00:00Z"
  },
  "error": null
}
```

오류: `404 MEDIA_NOT_FOUND`, `403 MEDIA_NOT_OWNED`, `409 MEDIA_STATE_CONFLICT`, `422 MEDIA_METADATA_MISMATCH`, `502 MEDIA_STORAGE_REJECTED`, `503 MEDIA_STORAGE_UNAVAILABLE`.

---

## 6. Chat typed message

### `POST /chat/rooms/{roomId}/messages`

- 인증: 방 참여 User의 Active Pet
- 신규 생성: `201`
- 같은 `clientMessageId`: 기존 메시지와 `200`

TEXT 요청:

```json
{
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "TEXT",
  "body": "안녕하세요.",
  "mediaId": null,
  "setlogId": null
}
```

IMAGE 요청:

```json
{
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440001",
  "type": "IMAGE",
  "body": null,
  "mediaId": 501,
  "setlogId": null
}
```

VIDEO 요청:

```json
{
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440002",
  "type": "VIDEO",
  "body": null,
  "mediaId": 502,
  "setlogId": null
}
```

SETLOG_SHARE 요청:

```json
{
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440003",
  "type": "SETLOG_SHARE",
  "body": null,
  "mediaId": null,
  "setlogId": 77
}
```

IMAGE 응답:

```json
{
  "success": true,
  "message": "메시지 전송 성공",
  "data": {
    "messageId": 9001,
    "roomId": 31,
    "senderType": "PET",
    "senderPetId": 12,
    "senderPetNickname": "보리",
    "type": "IMAGE",
    "body": null,
    "attachment": {
      "mediaId": 501,
      "mediaType": "IMAGE",
      "contentType": "image/jpeg",
      "fileSize": 231044,
      "url": "https://storage.example/presigned...",
      "expiresAt": "2026-08-20T09:15:00Z"
    },
    "sharedSetlog": null,
    "clientMessageId": "550e8400-e29b-41d4-a716-446655440001",
    "createdAt": "2026-08-20T09:00:00Z"
  },
  "error": null
}
```

SETLOG_SHARE 응답:

```json
{
  "success": true,
  "message": "메시지 전송 성공",
  "data": {
    "messageId": 9002,
    "roomId": 31,
    "senderType": "PET",
    "senderPetId": 12,
    "senderPetNickname": "보리",
    "type": "SETLOG_SHARE",
    "body": null,
    "attachment": null,
    "sharedSetlog": {
      "setlogId": 77,
      "available": true,
      "unavailableReason": null,
      "authorPetId": 12,
      "authorPetNickname": "보리",
      "caption": "오늘 한강 산책",
      "media": {
        "mediaId": 700,
        "mediaType": "VIDEO",
        "url": "https://storage.example/presigned...",
        "expiresAt": "2026-08-20T09:15:00Z"
      },
      "reactionCount": 18,
      "detailPath": "/setlogs/77"
    },
    "clientMessageId": "550e8400-e29b-41d4-a716-446655440003",
    "createdAt": "2026-08-20T09:00:01Z"
  },
  "error": null
}
```

타입 검증:

| type | body | mediaId | setlogId |
|---|---|---|---|
| TEXT | 필수 | 금지 | 금지 |
| IMAGE/VIDEO | 금지 또는 결정된 caption | 필수 | 금지 |
| SETLOG_SHARE | 금지 | 금지 | 필수 |
| CARD/SYSTEM | Client 전송 금지 | 금지 | 금지 |

오류:

- `404 CHAT_ROOM_NOT_FOUND`
- `403 CHAT_SENDER_NOT_PARTICIPANT`
- `403 BLOCKED_USER`
- `409 CHAT_DUPLICATE_MESSAGE`는 같은 ID·다른 payload일 때만 사용
- `400 CHAT_MESSAGE_TYPE_INVALID`
- `400 CHAT_MESSAGE_PAYLOAD_INVALID`
- `404 MEDIA_NOT_FOUND`, `403 MEDIA_NOT_OWNED`, `409 MEDIA_NOT_READY`
- `404 SETLOG_NOT_FOUND`, `403 SETLOG_SHARE_FORBIDDEN`

### `GET /chat/rooms/{roomId}/messages`

Query:

- `afterMessageId`: nullable, 0 이상
- `limit`: 기본 50, 최대 100

응답:

```json
{
  "success": true,
  "message": "메시지 목록 조회 성공",
  "data": {
    "items": [
      {
        "messageId": 9001,
        "roomId": 31,
        "senderType": "PET",
        "senderPetId": 12,
        "senderPetNickname": "보리",
        "type": "IMAGE",
        "body": null,
        "attachment": {
          "mediaId": 501,
          "mediaType": "IMAGE",
          "url": "https://storage.example/presigned...",
          "expiresAt": "2026-08-20T09:15:00Z"
        },
        "sharedSetlog": null,
        "clientMessageId": "550e8400-e29b-41d4-a716-446655440001",
        "createdAt": "2026-08-20T09:00:00Z"
      }
    ],
    "nextAfterMessageId": 9001,
    "hasMore": false
  },
  "error": null
}
```

삭제·차단된 Setlog 예시:

```json
{
  "setlogId": 77,
  "available": false,
  "unavailableReason": "SETLOG_UNAVAILABLE",
  "authorPetId": null,
  "authorPetNickname": null,
  "caption": null,
  "media": null,
  "reactionCount": null,
  "detailPath": null
}
```

### DIRECT WebSocket SEND

- Publish: `/app/chat/direct/rooms/{roomId}/messages`
- Payload는 REST 요청과 동일하다.

Ack:

```json
{
  "eventType": "CHAT_SEND_ACK",
  "roomId": 31,
  "messageId": 9001,
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440001",
  "duplicate": false
}
```

Error:

```json
{
  "eventType": "CHAT_ERROR",
  "code": "MEDIA_NOT_READY",
  "message": "업로드가 완료된 미디어만 전송할 수 있습니다.",
  "roomId": 31,
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440001"
}
```

### Open Chat Kafka Event

Topic: `chat-message-topic`, key=`roomId`.

```json
{
  "schemaVersion": 2,
  "eventId": "550e8400-e29b-41d4-a716-446655440001",
  "messageId": 9001,
  "roomId": 31,
  "senderUserId": 7,
  "senderPetId": 12,
  "type": "IMAGE",
  "body": null,
  "mediaId": 501,
  "setlogId": null,
  "createdAt": "2026-08-20T09:00:00Z"
}
```

Consumer는 DB/Backend가 검증한 ID를 기준으로 hydrate하거나 event에 포함된 안전한 summary를 사용한다. Presigned URL을 Kafka에 넣지 않는다.

---

## 7. 관리자 Dashboard

### `GET /admin/dashboard`

- 인증: `ADMIN`, `SUPER_ADMIN`
- Query: `from=2026-08-14`, `to=2026-08-20`
- 생략 시 최근 7일, 최대 90일

응답:

```json
{
  "success": true,
  "message": "관리자 Dashboard 조회 성공",
  "data": {
    "period": {
      "from": "2026-08-14",
      "to": "2026-08-20",
      "zoneId": "Asia/Seoul"
    },
    "users": { "total": 10042, "newInPeriod": 214 },
    "pets": { "total": 14310 },
    "setlogs": { "total": 102301, "newInPeriod": 1322 },
    "boardPosts": { "total": 12004, "newInPeriod": 407 },
    "reports": { "createdInPeriod": 31, "open": 7 },
    "safety": {
      "detectedUsers": 12,
      "openCases": 4,
      "signalsByType": {
        "REPEATED_CONTACT": 9,
        "AI_ACCOUNT_REQUEST": 2,
        "AI_EXTORTION": 1
      }
    },
    "storageCleanup": {
      "pending": 18,
      "retry": 2,
      "failed": 0
    },
    "recentItems": [
      {
        "source": "SAFETY_CASE",
        "id": 81,
        "status": "OPEN",
        "subjectUserId": 701,
        "reason": "REPEATED_CONTACT",
        "createdAt": "2026-08-20T08:30:00Z"
      }
    ]
  },
  "error": null
}
```

오류: `400 INVALID_DATE_RANGE`, `400 DATE_RANGE_TOO_LARGE`, `401 UNAUTHORIZED`, `403 FORBIDDEN`.

---

## 8. Safety 관리자 API

### `GET /admin/safety/cases`

Query:

- `status`: 기본 `OPEN`
- `signalType`, `subjectUserId`, `from`, `to`
- `cursor`, `size` 기본 20·최대 100

응답:

```json
{
  "success": true,
  "message": "안전 검토 Queue 조회 성공",
  "data": {
    "items": [
      {
        "caseId": 81,
        "subjectUserId": 701,
        "subjectPublicTag": "이웃#A120F8",
        "status": "OPEN",
        "totalScore": 90,
        "signalCount": 3,
        "primarySignalType": "REPEATED_CONTACT",
        "firstDetectedAt": "2026-08-15T02:00:00Z",
        "lastDetectedAt": "2026-08-20T08:30:00Z"
      }
    ],
    "page": {
      "nextCursor": "opaque-cursor",
      "hasNext": false
    }
  },
  "error": null
}
```

### `GET /admin/safety/cases/{caseId}`

```json
{
  "success": true,
  "message": "안전 검토 상세 조회 성공",
  "data": {
    "caseId": 81,
    "subject": {
      "userId": 701,
      "publicTag": "이웃#A120F8",
      "accountStatus": "ACTIVE"
    },
    "status": "OPEN",
    "totalScore": 90,
    "signals": [
      {
        "signalId": 901,
        "signalType": "FRIEND_REQUEST_REJECTED",
        "targetUserId": 820,
        "score": 30,
        "occurredAt": "2026-08-15T02:00:00Z",
        "sourceType": "FRIEND_REQUEST",
        "sourceId": "4201"
      }
    ],
    "actions": [],
    "createdAt": "2026-08-20T08:30:00Z",
    "updatedAt": "2026-08-20T08:30:00Z"
  },
  "error": null
}
```

### `POST /admin/safety/cases/{caseId}/actions`

요청:

```json
{
  "actionType": "WARNING_RECORDED",
  "reason": "동일 사용자에게 반복 접촉한 이력이 확인되었습니다."
}
```

응답:

```json
{
  "success": true,
  "message": "안전 검토가 처리되었습니다.",
  "data": {
    "caseId": 81,
    "status": "WARNING_RECORDED",
    "actionId": 301,
    "actionType": "WARNING_RECORDED",
    "reason": "동일 사용자에게 반복 접촉한 이력이 확인되었습니다.",
    "processedByAdminId": 3,
    "processedAt": "2026-08-20T09:10:00Z"
  },
  "error": null
}
```

허용 action: `DISMISSED`, `WARNING_RECORDED`.

오류: `404 SAFETY_CASE_NOT_FOUND`, `409 SAFETY_CASE_ALREADY_CLOSED`, `400 SAFETY_ACTION_INVALID`.

### `GET /admin/safety/cases/{caseId}/evidence`

Query: `purpose` 필수, `cursor`, `size` 최대 100.

요청 예시:

```http
GET /admin/safety/cases/81/evidence?purpose=반복접촉%20사실관계%20확인&size=50
```

응답:

```json
{
  "success": true,
  "message": "검토 Evidence 조회 성공",
  "data": {
    "auditId": 9901,
    "caseId": 81,
    "items": [
      {
        "resourceType": "CHAT_MESSAGE",
        "resourceId": 9001,
        "roomId": 31,
        "senderPetId": 12,
        "type": "TEXT",
        "body": "다시 이야기하고 싶어요.",
        "createdAt": "2026-08-20T08:20:00Z"
      }
    ],
    "page": { "nextCursor": null, "hasNext": false }
  },
  "error": null
}
```

조회 성공·실패와 무관하게 권한이 확인된 실제 Evidence 접근 시 감사 이력을 남긴다. 응답에 JWT·이메일·AI prompt를 포함하지 않는다.

---

## 9. RiskSignal Event

Topic: `risk-signal-topic`, key=`actorUserId`.

```json
{
  "schemaVersion": 1,
  "eventId": "1a548b88-2fd0-4be9-9418-03e11e9a6c6f",
  "sourceType": "USER_BLOCK",
  "sourceId": 4201,
  "signalType": "USER_BLOCKED",
  "actorUserId": 701,
  "targetUserId": 820,
  "occurredAt": "2026-08-20T08:00:00Z",
  "metadata": {
    "reasonCode": "USER_REQUEST"
  }
}
```

Producer 계약:

- Source/Signal 조합: `USER_BLOCK/USER_BLOCKED`, `GREETING/GREETING_EXPIRED`
- 위 두 조합을 교차하거나 정의되지 않은 조합은 Command/Event 생성 시 거부한다.
- `sourceId`, `actorUserId`, `targetUserId`는 양의 정수이며 `sourceId`는 JSON number다.
- `eventId`는 Publisher가 UUID로 생성한다. Outbox는 `eventId`와 `(sourceType, sourceId, signalType)`을 모두 멱등키로 사용한다.
- `RiskSourceEventPublisher.enqueue`는 원천 DB 트랜잭션 안에서 Outbox만 적재한다. Kafka publish 실패 재시도는 후속 relay가 처리한다.
- metadata allowlist:
  - `USER_BLOCKED`: 선택적 `reasonCode`, 대문자 영문·숫자·underscore 코드, 최대 64자
  - `GREETING_EXPIRED`: 선택적 `ttlHours`, 문자열로 표현한 1~168 정수
- allowlist 외 metadata key와 위 형식을 벗어난 value는 거부한다. message body, JWT, email, exact location, OAuth code 같은 원문 값은 허용하지 않는다.

Consumer 규칙:

- eventId 중복은 ack 후 무시
- score는 서버 정책이 정본이며 event에 score를 넣지 않음
- Case 생성 실패는 event 저장과 분리해 재처리 가능
- 원문이 필요한 경우 resource ID만 사용해 관리자 Evidence API에서 조회

---

## 10. Place API

### `GET /places/search`

Query:

- `q` 1~100자
- `latitude`, `longitude`
- `radiusMeters` 기본 3000, 최대 결정 필요
- `type`: HOSPITAL/PHARMACY/PARK/ETC
- `cursor`, `size` 최대 50

내부 `placeId` 생성:

1. Provider 검색 결과를 정규화한다.
2. `(provider, providerPlaceId)` unique key로 `places`를 upsert한다.
3. upsert된 내부 PK를 `placeId`로 검색 응답에 포함한다.
4. Board·Meeting·Walk는 Provider ID가 아니라 이 내부 `placeId`를 저장한다.

응답:

```json
{
  "success": true,
  "message": "장소 검색 성공",
  "data": {
    "items": [
      {
        "placeId": 91,
        "provider": "KAKAO",
        "providerPlaceId": "kakao-12345",
        "type": "HOSPITAL",
        "name": "같이동물병원",
        "address": "서울특별시 중구 ...",
        "phone": "02-123-4567",
        "latitude": 37.5665,
        "longitude": 126.978,
        "distanceMeters": 412
      }
    ],
    "page": { "nextCursor": null, "hasNext": false }
  },
  "error": null
}
```

오류: `400 LOCATION_INVALID`, `429 PLACE_PROVIDER_RATE_LIMITED`, `503 PLACE_PROVIDER_UNAVAILABLE`.

### `GET /places/{placeId}`

```json
{
  "success": true,
  "message": "장소 조회 성공",
  "data": {
    "placeId": 91,
    "type": "HOSPITAL",
    "name": "같이동물병원",
    "address": "서울특별시 중구 ...",
    "phone": "02-123-4567",
    "latitude": 37.5665,
    "longitude": 126.978
  },
  "error": null
}
```

---

## 11. 만남 확인·후기·발자국

### `POST /meeting-cards/{cardId}/meeting-verifications`

요청:

```json
{
  "clientRequestId": "b0bbdc4d-4174-4abe-b837-e364866a9308",
  "latitude": 37.5665,
  "longitude": 126.978,
  "accuracyMeters": 24.5,
  "capturedAt": "2026-08-20T09:00:00Z"
}
```

첫 제출 응답:

```json
{
  "success": true,
  "message": "상대방의 확인을 기다리고 있습니다.",
  "data": {
    "cardId": 51,
    "status": "WAITING_COUNTERPART",
    "meetingId": null,
    "submittedPetId": 12,
    "counterpartSubmitted": false,
    "verificationMethod": null,
    "codeRequired": false,
    "expiresAt": "2026-08-20T09:05:00Z"
  },
  "error": null
}
```

GPS 성공 응답:

```json
{
  "success": true,
  "message": "만남이 확인되었습니다.",
  "data": {
    "cardId": 51,
    "status": "CONFIRMED",
    "meetingId": 61,
    "counterpartSubmitted": true,
    "verificationMethod": "GPS",
    "distanceMeters": 42.7,
    "confirmedAt": "2026-08-20T09:02:00Z",
    "reviewAvailable": true
  },
  "error": null
}
```

정확도 부족 응답:

```json
{
  "success": true,
  "message": "위치 정확도가 낮아 확인 코드가 필요합니다.",
  "data": {
    "cardId": 51,
    "status": "CODE_REQUIRED",
    "meetingId": null,
    "verificationMethod": "CODE",
    "codeRequired": true,
    "displayCode": "4821",
    "codeExpiresAt": "2026-08-20T09:07:00Z"
  },
  "error": null
}
```

`displayCode`는 발급 대상 한쪽에게만 반환한다.

오류:

- `404 MEETING_CARD_NOT_FOUND`
- `403 MEETING_NOT_PARTICIPANT`
- `409 MEETING_CARD_NOT_OPEN`
- `400 LOCATION_INVALID`, `400 LOCATION_STALE`
- `409 MEETING_TIME_WINDOW_EXCEEDED`
- `409 MEETING_DISTANCE_EXCEEDED`

### `GET /meeting-cards/{cardId}/meeting-verification`

현재 사용자에게 필요한 상태만 반환한다. 좌표는 반환하지 않는다.

```json
{
  "success": true,
  "message": "만남 확인 상태 조회 성공",
  "data": {
    "cardId": 51,
    "status": "WAITING_COUNTERPART",
    "meetingId": null,
    "mySubmitted": true,
    "counterpartSubmitted": false,
    "codeRequired": false,
    "expiresAt": "2026-08-20T09:05:00Z"
  },
  "error": null
}
```

### `POST /meeting-cards/{cardId}/confirmation-code/verify`

```json
{
  "clientRequestId": "601f87bb-31d9-4f56-95bb-74016fb709f9",
  "code": "4821"
}
```

응답:

```json
{
  "success": true,
  "message": "확인 코드로 만남이 확인되었습니다.",
  "data": {
    "cardId": 51,
    "meetingId": 61,
    "status": "CONFIRMED",
    "verificationMethod": "CODE",
    "confirmedAt": "2026-08-20T09:04:00Z"
  },
  "error": null
}
```

오류: `400 MEETING_CODE_MISMATCH`, `410 MEETING_CODE_EXPIRED`, `429 MEETING_CODE_ATTEMPTS_EXCEEDED`, `409 MEETING_ALREADY_CONFIRMED`.

### `POST /meetings/{meetingId}/reviews`

```json
{
  "clientRequestId": "9eb374ad-e81d-433a-8934-93faf399d48e",
  "content": "즐겁게 산책했어요."
}
```

응답:

```json
{
  "success": true,
  "message": "만남 후기가 등록되었습니다.",
  "data": {
    "reviewId": 71,
    "meetingId": 61,
    "content": "즐겁게 산책했어요.",
    "createdAt": "2026-08-20T09:10:00Z",
    "footprint": {
      "granted": true,
      "footprintId": 81,
      "duplicateDay": false,
      "earnedDate": "2026-08-20"
    }
  },
  "error": null
}
```

오류: `404 MEETING_NOT_FOUND`, `403 MEETING_NOT_PARTICIPANT`, `409 REVIEW_ALREADY_EXISTS`.

### `GET /footprints`

Query: `cursor`, `size` 기본 20·최대 100.

```json
{
  "success": true,
  "message": "발자국 조회 성공",
  "data": {
    "items": [
      {
        "footprintId": 81,
        "meetingId": 61,
        "counterpartPet": {
          "petId": 22,
          "nickname": "초코"
        },
        "earnedDate": "2026-08-20",
        "createdAt": "2026-08-20T09:10:00Z"
      }
    ],
    "page": { "nextCursor": null, "hasNext": false }
  },
  "error": null
}
```

---

## 12. 산책 기록

### `POST /walks`

```json
{
  "clientRequestId": "870eb768-9c12-4a46-85c5-d27c22f9e159",
  "startedAt": "2026-08-20T08:00:00Z",
  "startLocation": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracyMeters": 18.0,
    "capturedAt": "2026-08-20T08:00:00Z"
  }
}
```

```json
{
  "success": true,
  "message": "산책 기록이 시작되었습니다.",
  "data": {
    "walkId": 101,
    "status": "RECORDING",
    "startedAt": "2026-08-20T08:00:00Z"
  },
  "error": null
}
```

### `POST /walks/{walkId}/points`

```json
{
  "clientBatchId": "16a9e9d1-40f5-4efe-8642-96feecb4fc44",
  "points": [
    {
      "sequence": 1,
      "latitude": 37.5665,
      "longitude": 126.978,
      "accuracyMeters": 18.0,
      "capturedAt": "2026-08-20T08:00:00Z"
    },
    {
      "sequence": 2,
      "latitude": 37.5667,
      "longitude": 126.9783,
      "accuracyMeters": 20.0,
      "capturedAt": "2026-08-20T08:00:10Z"
    }
  ]
}
```

응답:

```json
{
  "success": true,
  "message": "산책 위치가 저장되었습니다.",
  "data": {
    "walkId": 101,
    "acceptedCount": 2,
    "duplicateCount": 0,
    "lastSequence": 2
  },
  "error": null
}
```

### `POST /walks/{walkId}/finish`

```json
{
  "clientRequestId": "355cd363-c13e-48aa-aa56-0081b91c3292",
  "endedAt": "2026-08-20T08:30:00Z"
}
```

```json
{
  "success": true,
  "message": "산책 기록이 완료되었습니다.",
  "data": {
    "walkId": 101,
    "status": "COMPLETED",
    "startedAt": "2026-08-20T08:00:00Z",
    "endedAt": "2026-08-20T08:30:00Z",
    "durationSeconds": 1800,
    "distanceMeters": 2140.5,
    "pointCount": 181
  },
  "error": null
}
```

오류: `404 WALK_NOT_FOUND`, `403 WALK_NOT_OWNED`, `409 WALK_NOT_RECORDING`, `400 WALK_POINT_INVALID`, `409 WALK_SEQUENCE_CONFLICT`.

### `GET /walks/{walkId}`

상세 응답은 요약과 단순화한 route points를 반환한다. 전체 원시 points 반환 여부는 보존·성능 정책 후 확정한다.

```json
{
  "success": true,
  "message": "산책 기록 조회 성공",
  "data": {
    "walkId": 101,
    "petId": 12,
    "status": "COMPLETED",
    "startedAt": "2026-08-20T08:00:00Z",
    "endedAt": "2026-08-20T08:30:00Z",
    "durationSeconds": 1800,
    "distanceMeters": 2140.5,
    "simplifiedPath": [
      { "latitude": 37.5665, "longitude": 126.978 },
      { "latitude": 37.5681, "longitude": 126.981 }
    ]
  },
  "error": null
}
```

### `GET /walk-routes/search`

Query: 중심 좌표, 반경, 정렬(`POPULAR/NEARBY`), cursor, size.

```json
{
  "success": true,
  "message": "산책 경로 검색 성공",
  "data": {
    "items": [
      {
        "routeId": 401,
        "title": "한강공원 짧은 코스",
        "distanceMeters": 2100,
        "estimatedMinutes": 30,
        "popularityScore": 82.4,
        "start": { "latitude": 37.5665, "longitude": 126.978 },
        "end": { "latitude": 37.5681, "longitude": 126.981 },
        "simplifiedPath": [
          { "latitude": 37.5665, "longitude": 126.978 },
          { "latitude": 37.5681, "longitude": 126.981 }
        ]
      }
    ],
    "page": { "nextCursor": null, "hasNext": false }
  },
  "error": null
}
```

---

## 13. AI 검열 Event

AI Request:

```json
{
  "requestId": "d2817daa-94e5-431b-9021-23259c2140cb",
  "roomId": 31,
  "messageId": 9003,
  "ruleTypes": ["URL_AND_CREDENTIAL_REQUEST"],
  "messages": [
    {
      "speaker": "SUBJECT",
      "text": "이 링크에서 앱을 설치하고 인증번호를 알려주세요."
    }
  ]
}
```

AI Response:

```json
{
  "requestId": "d2817daa-94e5-431b-9021-23259c2140cb",
  "riskType": "ACCOUNT_CREDENTIAL_REQUEST",
  "score": 0.94,
  "reasonCode": "INSTALL_AND_SHARE_CODE",
  "modelVersion": "safety-m3-1",
  "shouldCreateSignal": true
}
```

원칙:

- 규칙에 걸린 최소 문맥만 전송한다.
- Provider timeout이 채팅 전송을 실패시키지 않는다.
- AI 점수만으로 자동 제재하지 않는다.
- Prompt·원문을 일반 application log에 남기지 않는다.

---

## 14. 오류 응답 예시

```json
{
  "success": false,
  "message": "해당 요청이 실패되었습니다.",
  "data": null,
  "error": {
    "code": "CHAT_MESSAGE_PAYLOAD_INVALID",
    "message": "메시지 타입에 맞지 않는 필드가 포함되었습니다."
  }
}
```

HTTP 원칙:

- 400: 형식·상태값·타입별 payload 불일치
- 401: 인증 실패·만료
- 403: 소유권·역할·차단
- 404: 존재하지 않음 또는 권한 은닉이 필요한 리소스
- 409: 중복·상태전이·동시 변경
- 410: 만료·소비된 일회용 자원
- 413/415/422: 파일 크기·MIME·도메인 검증
- 429: 시도·Provider rate limit
- 502/503: Storage·AI·외부 Provider 장애
