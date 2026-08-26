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
- JSON 필드 허용 여부와 알 수 없는 필드 처리는 각 endpoint의 runtime 계약을 따른다.

---

## 2. OAuth

### `GET /oauth2/authorization/google`

- 인증: 불필요. 브라우저 navigation 전용이며 JSON `ApiResponse` endpoint가 아니다.
- Google OAuth가 enabled이면 Google authorization endpoint로 `302 Found`를 반환한다. disabled이면 `404`다.
- scope는 정확히 `openid email`이다. 서버는 Redis에 일회용 state, PKCE verifier(S256), nonce와 backend redirect URI를 저장한다.
- `NAVER`는 공통 enum/DB 제약에만 존재하며 runtime endpoint·adapter·설정은 없다.

Callback 흐름:

```text
Google 인증
→ Backend /login/oauth2/code/google callback
→ state 원자적 소비, PKCE token 교환, OIDC ID token 검증
→ 1회용 loginCode 발급
→ allowlist된 Front success callback URL?loginCode={code}&provider=GOOGLE 로 302
→ Front가 POST /auth/oauth/exchange 호출
```

- ID token은 issuer(`accounts.google.com`), configured client ID 단일 audience, nonce, email 존재,
  `email_verified=true`, subject를 검증한다. OIDC는 복수 audience를 표현할 수 있지만 Dogether는 추가 audience를
  신뢰하지 않아 실패시킨다. `azp`는 모든 ID Token의 필수 claim이 아니며, 제공되면 configured client ID와
  일치해야 한다. `sub`는 providerSubject/identity key이고 email은 identity key가 아니다. Gmail·Workspace·`hd`
  여부를 추가 요구하지 않으므로 `hd` 없는 third-party verified email도 허용한다.
- Redirect URL에는 Access/Refresh Token, provider token, verified email, providerSubject 및 raw provider
  error/response를 포함하지 않는다. Provider token과 raw provider error/response는 저장하지 않으며 verified
  email은 loginCode/signupToken의 짧은 TTL + cleanup grace 동안에만 transient snapshot으로 DB row에 남을 수 있다.
- callback 실패는 allowlist된 Front error callback URL에 `errorCode` 하나만 붙여 `302`한다. 허용 값은
  `INTERNAL_ERROR`, `OAUTH_STATE_INVALID`, `OAUTH_STATE_EXPIRED`, `OAUTH_AUTHORIZATION_DENIED`,
  `OAUTH_IDENTITY_VERIFICATION_FAILED`, `OAUTH_PROVIDER_UNAVAILABLE`다.

### `GET /login/oauth2/code/google`

- 인증: 불필요. Google이 `state`, `code` 또는 `error`를 전달하는 browser callback이다.
- 성공·실패 모두 위 callback URL로 `302`하며 JSON error envelope를 직접 반환하지 않는다.
- OAuth가 disabled이면 `404`다.

### `POST /auth/oauth/exchange`

- 인증: 불필요
- 성공: 기존 OAuth Identity는 `200`, 신규 Identity는 `202`
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
  "message": "OAuth 로그인이 완료되었습니다.",
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
  "message": "OAuth 가입을 위한 추가 정보 입력이 필요합니다.",
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
- `403 ACCOUNT_NOT_ACTIVE`
- `409 OAUTH_ACCOUNT_LINK_DECISION_REQUIRED` (동일 email의 미연결 기존 계정; 자동 연결·신규 User 생성·link endpoint 없음, loginCode 미소비). 이는 최종 account-link 정책이 아닌 현재 안전 경계다.
- loginCode는 logical expiry 뒤 약 1분 cleanup grace 안에는 `OAUTH_LOGIN_CODE_EXPIRED`, physical delete 뒤에는
  `OAUTH_LOGIN_CODE_INVALID`다.

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

- 오류: `400 VALIDATION_FAILED`, `401 OAUTH_SIGNUP_TOKEN_INVALID`, `410 OAUTH_SIGNUP_TOKEN_EXPIRED`,
  `422 NEIGHBORHOOD_NOT_FOUND`, `409 CONCURRENT_UPDATE_CONFLICT`, `409 PUBLIC_TAG_GENERATION_FAILED`.
- `signupToken`은 10분 TTL의 1회용이고, nickname은 trim 후 2~20자, neighborhoodCode는 trim 후
  최대 20자여야 한다. email/provider subject는 request body가 아니라 token에서만 가져온다. logical expiry
  뒤 약 1분 cleanup grace 안에는 `OAUTH_SIGNUP_TOKEN_EXPIRED`, physical delete 뒤에는
  `OAUTH_SIGNUP_TOKEN_INVALID`다. 가입 transaction 직전/중 동일 email User가 먼저 생성되는 late race는
  User·OAuthIdentity·RefreshToken·signupToken consume을 모두 rollback하고
  `409 CONCURRENT_UPDATE_CONFLICT`를 반환한다. 이 경우 signupToken을 account-link context로 재해석하지
  않으며 새 OAuth authorization부터 다시 시작한다.

---

## 3. Pet 삭제·이미지

`PetResponse`를 반환하는 API는 `version`을 포함한다. `status`는 현재 Pet 상태
(`ACTIVE`, `SUSPENDED`, `DELETED`)이며, 삭제된 Pet은 일반적인 본인 Pet 조회에서
`PET_NOT_FOUND`로 은닉된다. `profileUrl`은 연결된 업로드 완료 IMAGE Media의
조회 시점 presigned URL이고, 연결되지 않았으면 `null`이다.

### `GET /pets/{petId}/profile`

- 인증: 로그인 User
- 성공: `200 ApiResponse<PetPublicProfileResponse>`, message는 `Pet 공개 프로필이 조회되었습니다.`
- `data`는 `petId`, `publicTag`, `nickname`, `profileUrl`, `verified`, `breedName`, `sex`,
  `neutered`, `birthDate`, `sizeCode`, `bio`, `personalityTags`, `helpfulReceivedCount`,
  `relationship`만 반환한다. `personalityTags`는 빈 경우에도 `[]`이며, `profileUrl`은 profile Media가
  없으면 `null`이다.
- 공개 대상은 `status=ACTIVE`, `deletedAt=null`, 소유자 `accountStatus=ACTIVE`를 모두 만족해야 한다.
  Pet 부재, 정지·삭제·soft-delete Pet, 비활성 소유자, 조회자→소유자 또는 소유자→조회자 Block은 모두
  `404 PET_NOT_FOUND`로 existence hiding 한다.
- 자기 Pet은 조회 가능하되 `relationship=null`이며 Active Pet·FriendRelationship 조회를 하지 않는다.
  비자기 대상도 조회자의 Active Pet이 없으면 성공하고 `relationship=null`이다. 유효 Active Pet이 있을
  때만 기존 FriendRelationship의 `NONE`, `REQUEST_SENT`, `REQUEST_RECEIVED`, `FRIEND`를 계산한다.
- `verified`는 기존 verification badge 결과만 공개하고 `verifiedAt`은 반환하지 않는다. `helpfulReceivedCount`는
  기존 HELPFUL 집계 기준을 변경하지 않는다.

### `POST /pets/{petId}/profile-image`

- 인증: Pet 소유 User
- 성공: `201 Created`와 `PetResponse`
- body: `{"mediaId":123}` (`mediaId`는 양의 정수)
- 최초 1회만 연결할 수 있으며 이미 연결된 Pet은 `409 PET_PROFILE_IMAGE_ALREADY_SET`이다.
- Media는 요청자 소유의 IMAGE이고 `UPLOADED` 또는 `COMPLETED` 상태여야 한다.
- 오류: `400 VALIDATION_FAILED`, `403 PET_NOT_OWNED`·`MEDIA_NOT_OWNED`,
  `404 PET_NOT_FOUND`·`MEDIA_NOT_FOUND`, `409 PET_PROFILE_IMAGE_ALREADY_SET`,
  `422 INVALID_MEDIA_TYPE`·`MEDIA_NOT_UPLOADED`.

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
- `If-Match`는 큰따옴표로 감싼 0 이상의 10진수 strong ETag 하나만 허용한다. 예: `If-Match: "3"`.
- 헤더가 없거나 blank, weak ETag(`W/"3"`), wildcard(`*`), 반복 헤더, comma-separated 값,
  부호·공백·숫자가 아닌 값·범위를 벗어난 값이면 `400 VALIDATION_FAILED`이며 service를 호출하지 않는다.
- PUT/DELETE 모두 같은 Pet `version` 낙관적 잠금 정책을 사용한다.
- 요청 body는 `{"mediaId":123}` 형식이며 `mediaId`는 양의 정수다. 알 수 없는 필드는 이 endpoint에서 별도 오류로 처리하지 않는다.
- Media는 요청자 소유의 IMAGE이고 상태가 `UPLOADED` 또는 `COMPLETED`여야 한다.

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
  "message": "Pet 프로필 이미지가 교체되었습니다.",
  "data": {
    "petId": 10,
    "publicTag": "pet#TAG1",
    "nickname": "초코",
    "profileUrl": "https://storage.example/presigned...",
    "status": "ACTIVE",
    "version": 4,
    "verified": true,
    "active": true,
    "helpfulReceivedCount": 12
  },
  "error": null
}
```

- 실제 Media 교체이면 Pet row의 `version`이 정확히 1 증가한다. 현재 연결과 같은
  `mediaId`를 다시 지정하면 Media 검증을 먼저 수행한 뒤 no-op으로 처리하며 `version`은 증가하지 않는다.
- 응답 `data`는 일반 `PetResponse`이며 `version`은 저장된 Pet version과 같다.
- 이 요청은 Pet의 Media link만 변경한다. Media row, `deletedAt`, S3 객체와
  `StorageDeleteJob` 생성 여부는 변경하지 않는다.

오류: `400 VALIDATION_FAILED`, `403 PET_NOT_OWNED`·`MEDIA_NOT_OWNED`,
`404 PET_NOT_FOUND`·`MEDIA_NOT_FOUND`, `409 CONCURRENT_UPDATE_CONFLICT`,
`422 INVALID_MEDIA_TYPE`·`MEDIA_NOT_UPLOADED`.

### `DELETE /pets/{petId}/profile-image`

- Header: `If-Match: "{petVersion}"` 필수
- `If-Match` 문법과 version 검사는 PUT과 동일하다. 누락·malformed header는 `400 VALIDATION_FAILED`,
  stale version은 `409 CONCURRENT_UPDATE_CONFLICT`다.
- Request body는 없고, 성공은 `204 No Content`다. 응답 body와 DELETE ETag를 반환하지 않는다.
- 실제 link 해제이면 Pet row의 `version`이 정확히 1 증가한다. 이미 link가 없으면 no-op으로
  `version`은 증가하지 않는다.
- 별도의 `MediaRepository` command lookup 및 Media 소유권/type/status validation 없이 Pet의
  profile link만 해제한다.
- 이 요청도 Pet의 Media link만 변경한다. Media row, `deletedAt`, S3 객체와
  `StorageDeleteJob`은 변경하지 않는다. Media 물리 삭제 lifecycle은 이 API 계약에 포함하지 않는다.
- 오류: `403 PET_NOT_OWNED`, `404 PET_NOT_FOUND`, `409 CONCURRENT_UPDATE_CONFLICT`.

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

### `POST /posts/{postId}/comments/{commentId}/direct-room`

게시글에 직접 작성된 Root 댓글 작성자 Pet과 게시글 작성자 Pet 사이의 기존 DIRECT 채팅방을 조회하거나 생성한다. 새 DIRECT room이 생성되면 `origin=BOARD_COMMENT`로 저장하며, 기존 room 재사용 시 origin은 변경하지 않는다.

- 인증: Bearer JWT
- Request body: 없음
- Controller 입력: `CurrentUser`, `postId`, `commentId`만 사용한다. Pet ID를 body·header·JWT claim에서 받지 않는다.
- 대상 Comment: 요청 Post에 속한 active Root(`depth=0`, `parentCommentId=null`, `rootCommentId=null`)만 허용하며 Reply는 `404 BOARD_POST_COMMENT_NOT_FOUND`다.
- 호출 권한: Active Pet이 Post author Pet 또는 Comment author Pet이 아니면 기존 `FORBIDDEN`(403)으로 거부하고 Chat Core를 호출하지 않는다.
- same Pet: `CHAT_ROOM_SAME_PET_FORBIDDEN`(400)
- same-owner Pet: `SAME_OWNER_INTERACTION_FORBIDDEN`(400)
- Block: 양방향 Block이면 `CHAT_ROOM_NOT_FOUND`(404)로 existence hiding한다.
- 삭제/비공개: 삭제 Post는 `BOARD_POST_NOT_FOUND`, 삭제 Comment는 `BOARD_POST_COMMENT_NOT_FOUND`로 Board visibility 계약을 따른다. 기존 room은 Chat 경로로만 접근한다.

응답은 `200 ApiResponse<EnsureDirectRoomResult>`다.

```json
{
  "success": true,
  "message": "DIRECT 채팅방이 연결되었습니다.",
  "data": { "roomId": 1, "isNew": true },
  "error": null
}
```

Board 계층은 Post/Comment identity·visibility와 호출 권한만 확인한다. DIRECT pair 정규화, 기존 room 재사용, Participant 생성, DB 중복 방지, Chat existence hiding은 기존 `ChatRoomService.ensureDirectRoom(...)`과 Chat 조회 계약의 책임이다.

### `POST /boards/{boardId}/posts`

아래 `placeId` 요청·응답 계약은 기존 M3 Place 제품 계획 계약이다. Issue #124는 Place를 구현하거나 변경하지 않으며, 현재 runtime POST parser는 `title`·`content`·선택 `mediaIds`만, PATCH parser는 이 절의 `title`·`content`·`mediaIds`·`version` 계약만 받는다.

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

기존 M3 Place 제품 계획 요청 예시이며, 아래 `placeId`는 현재 Issue #124 runtime PATCH parser의 허용 필드가 아니다.

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

현재 Issue #124 runtime PATCH는 strict JSON이며 `title`, `content`, `mediaIds`, `version` 외 필드는 `400 VALIDATION_FAILED`다. `version`은 필수인 0 이상의 integral long이고, `title`·`content`·`mediaIds` 중 하나 이상이 필요하다. `mediaIds`는 null이 아닌 중복 없는 양의 integral long 배열로 최대 5개다. decimal, overflow, 0 이하 값, null item도 `400 VALIDATION_FAILED`다. 아래 `placeId` 항목은 기존 M3 Place 제품 계획 계약이며 Issue #124 runtime parser에는 포함되지 않는다.

PATCH 필드 의미 (기존 M3 Place 제품 계획 계약과 현재 Issue #124 media 계약을 함께 표기):

- `title`, `content` 생략: 해당 text 유지
- `placeId` 생략: 기존 Place 연결 유지
- `placeId: null`: Place 연결 제거
- `placeId: 91`: Place 교체
- `mediaIds` 생략: 기존 이미지 링크를 그대로 유지하고 비교·교체하지 않음
- `mediaIds: null`: `400 VALIDATION_FAILED`
- `mediaIds: []`: 기존 링크를 모두 제거
- `mediaIds: [501, 502]`: 검증된 이미지 링크를 전달 순서(`displayOrder` 0부터)로 전체 교체

stale version 검사는 작성자·게시글 확인 뒤 Media DB 조회와 no-op 판단보다 먼저 수행하며, 불일치면 `409 CONCURRENT_UPDATE_CONFLICT`다. `mediaIds`가 present이면 같은 순서의 현재 목록인지 비교하고 command Media validation을 유지한다. 동일한 title/content와 동일 순서 목록은 no-op으로 link DML과 version 증가가 없다. 같은 집합이어도 순서가 다르면 실제 변경이다.

실제 text 또는 이미지 변경은 BoardPost의 domain-specific attachment touch와 text 변경을 한 aggregate dirty update로 합쳐 `posts.flush()` 한 번으로 parent optimistic claim을 먼저 완료한다. flush 후 응답의 managed version과 DB version은 동일하고 정확히 1 증가한다. 이후 이미지 교체는 기존 `BoardPostMedia`를 delete, flush, 요청 순서로 insert한다. 따라서 `[A,B] -> [B,A]`도 unique 제약 충돌 없이 처리되며 rollback 시 parent와 link 변경은 함께 원복된다.

이미지 command validation은 기존 계약을 유지한다: missing/deleted는 `MEDIA_NOT_FOUND`, 타 소유자는 `MEDIA_NOT_OWNED`, non-image는 `INVALID_MEDIA_TYPE`, 업로드 완료 전 상태는 `MEDIA_NOT_UPLOADED`다. 읽기 hydrate는 별도 경계로, 링크 Media를 batch로 모두 load한 뒤 collection signing한다. missing, soft-deleted 또는 not-downloadable Media가 하나라도 있으면 일부 images를 반환하지 않고 기존 단건 다운로드와 같은 읽기 실패로 요청 전체가 실패한다.

N+1 방지: feed는 페이지 전체 `BoardPostMedia`의 distinct Media ID를 `findAllById` 한 번으로 hydrate하고 collection signing도 한 번만 수행한다. detail과 PATCH의 `mediaIds` omitted 경로도 link 집합 단위로 hydrate한다. create와 `mediaIds` present PATCH는 validation에서 이미 load한 `Media`를 링크 생성·응답 signing에 재사용한다. Board feed 작성자의 PetDisplay batch path는 fetch join된 profile asset을 collection signing해 추가 MediaRepository lookup 없이 URL을 조립한다. 이 작업은 Media entity·repository·service/controller/lifecycle 및 Media Flyway migration을 변경하지 않는다.

게시글 생성·수정·목록·상세 응답에서 기존 `reactionCount`·`reactedByMe`는 LIKE 의미를 유지한다. HELPFUL 상태는 `helpfulCount`·`helpfulByMe`로 별도 제공한다.

기존 `PetResponse`를 반환하는 내 Pet 생성·목록·상세·수정·초기 프로필 이미지 설정·교체 응답에는
`version`과 `helpfulReceivedCount`가 포함된다. HELPFUL만 합산하며 삭제 target 자신의 row만 제외한다.
프로필 이미지 PUT/DELETE는 Pet link만 변경하고 Media lifecycle을 변경하지 않는다. 공개 타 사용자 Pet
profile endpoint는 별도 `PetPublicProfileResponse`로 제공하며, `PetSearchItemResponse`와
`PetDisplaySummary`는 확장하지 않는다.

오류: `400 VALIDATION_FAILED`, `404 BOARD_POST_NOT_FOUND`, `404 PLACE_NOT_FOUND`, `404 MEDIA_NOT_FOUND`, `403 MEDIA_NOT_OWNED`, `422 INVALID_MEDIA_TYPE`, `409 CONCURRENT_UPDATE_CONFLICT`.

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
- 사용자 전송 타입(`TEXT`, `IMAGE`, `VIDEO`, `SETLOG_SHARE`)은 모두 `clientMessageId`가 필요하다. 누락하면 `400 CHAT_CLIENT_MESSAGE_ID_REQUIRED`다.

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
| IMAGE/VIDEO | 반드시 `null` (caption 미지원) | 필수 | 금지 |
| SETLOG_SHARE | 금지 | 금지 | 필수 |
| CARD/SYSTEM | Client 전송 금지 | 금지 | 금지 |

오류:

- `404 CHAT_ROOM_NOT_FOUND`
- `403 CHAT_SENDER_NOT_PARTICIPANT`
- `403 BLOCKED_USER`
- `400 CHAT_CLIENT_MESSAGE_ID_REQUIRED`는 모든 사용자 전송 타입의 `clientMessageId` 누락에 사용
- `409 CHAT_DUPLICATE_MESSAGE`는 같은 ID·다른 payload일 때만 사용
- `409 CHAT_MEDIA_ALREADY_ATTACHED`는 다른 `clientMessageId`로 이미 첨부된 Media를 재사용할 때 사용. 같은 ID·같은 payload의 멱등 재시도는 기존 메시지를 반환한다.
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
- `from`, `to`를 모두 생략하면 KST 오늘을 포함한 최근 7일, 최대 90일
- 한쪽 날짜만 입력하거나 `from > to`이면 `INVALID_DATE_RANGE`
- 날짜 범위는 KST `[from 00:00, to 다음 날 00:00)`를 UTC `Instant`로 변환해 집계

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
    "pets": { "total": 14310, "newInPeriod": 188 },
    "setlogs": { "total": 102301, "newInPeriod": 1322 },
    "boardPosts": { "total": 12004, "newInPeriod": 407 },
    "reports": { "createdInPeriod": 31, "open": 7 },
    "safety": {
      "detectedUsers": 12,
      "openCases": 4,
      "signalsByType": {
        "USER_BLOCKED": 9,
        "GREETING_EXPIRED": 3
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
        "reason": "USER_BLOCKED",
        "createdAt": "2026-08-20T08:30:00Z"
      }
    ]
  },
  "error": null
}
```

오류: `400 INVALID_DATE_RANGE`, `400 DATE_RANGE_TOO_LARGE`, `401 UNAUTHORIZED`, `403 FORBIDDEN`.

집계 기준(D-08):

- User: `role=USER`이면서 탈퇴하지 않은 계정. 정지 계정은 포함한다.
- Pet: 논리 삭제되지 않은 Pet이며 정지 Pet·소유자는 포함하되, 소유 User가 `WITHDRAWN`이면 제외한다.
- Setlog: `VISIBLE`이며 Seed가 아닌 콘텐츠. 작성 Pet/User의 이후 삭제·탈퇴 상태는 집계에 영향을 주지 않는다.
- BoardPost: `PUBLISHED`이며 논리 삭제되지 않은 게시글. 작성 Pet/User의 이후 삭제·탈퇴 상태는 집계에 영향을 주지 않는다.
- Report: `createdInPeriod`는 생성 당시 상태와 무관하고, `open`은 조회 시점의 현재 `OPEN` 수다.
- Safety: `detectedUsers`는 기간 내 고유 `actorUserId` 수이고 `signalsByType`은 기간 내 `occurredAt` 기준이다. 서버가 지원하는 모든 Signal type을 반환하며 0건은 `0`이다. `openCases`는 현재 `OPEN`, `REVIEWING` 수다.
- StorageCleanup: 기간과 무관한 현재 `PENDING`, `RETRY`, `FAILED` backlog다.
- `recentItems`는 기간과 무관한 Report·SafetyCase 전체 최신 10건이며 `createdAt DESC`, `source ASC`, `id DESC`로 정렬한다.
- 이메일, 토큰, 신고 원문, Risk metadata, Media URL은 조회하거나 반환하지 않는다.

---

## 8. Safety 관리자 API

### `GET /admin/safety/cases`

Query:

- `status`: 기본 `OPEN`
- `signalType`, `subjectUserId`, `targetUserId`, `from`, `to`
- `cursor`, `size` 기본 20·최대 100
- Queue cursor는 Case 평가로 변경되는 `lastDetectedAt`이 아니라 `(createdAt, caseId)`를 인코딩한다.

응답:

```json
{
  "success": true,
  "message": "안전 검토 Queue 조회 성공",
  "data": {
    "items": [
      {
        "caseId": 81,
        "subject": { "userId": 701, "publicTag": "이웃#A120F8" },
        "target": { "userId": 820, "publicTag": "이웃#B920D1" },
        "status": "OPEN",
        "totalScore": 90,
        "signalCount": 3,
        "primarySignalType": "USER_BLOCKED",
        "evaluationPolicyVersion": 7,
        "firstDetectedAt": "2026-08-15T02:00:00Z",
        "lastDetectedAt": "2026-08-20T08:30:00Z",
        "evaluatedAt": "2026-08-20T08:30:01Z",
        "version": 2,
        "createdAt": "2026-08-20T08:30:01Z",
        "updatedAt": "2026-08-20T08:30:01Z"
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
  "message": "안전 검토 건을 조회했습니다.",
  "data": {
    "safetyCase": {
      "caseId": 81,
      "subject": { "userId": 701, "publicTag": "이웃#A120F8" },
      "target": { "userId": 820, "publicTag": "이웃#B920D1" },
      "status": "OPEN",
      "totalScore": 90,
      "signalCount": 3,
      "primarySignalType": "USER_BLOCKED",
      "evaluationPolicyVersion": 7,
      "firstDetectedAt": "2026-08-15T02:00:00Z",
      "lastDetectedAt": "2026-08-20T08:30:00Z",
      "evaluatedAt": "2026-08-20T08:30:01Z",
      "version": 2,
      "createdAt": "2026-08-20T08:30:01Z",
      "updatedAt": "2026-08-20T08:30:01Z"
    },
    "recentSignals": [
      {
        "signalId": 901,
        "eventId": "1a548b88-2fd0-4be9-9418-03e11e9a6c6f",
        "sourceType": "USER_BLOCK",
        "sourceId": 4201,
        "signalType": "USER_BLOCKED",
        "score": 30,
        "scorePolicyVersion": 1,
        "occurredAt": "2026-08-15T02:00:00Z"
      }
    ],
    "hasMoreSignals": false,
    "actions": []
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
  "message": "안전 검토 건을 처리했습니다.",
  "data": {
    "caseId": 81,
    "subject": { "userId": 701, "publicTag": "이웃#A120F8" },
    "target": { "userId": 820, "publicTag": "이웃#B920D1" },
    "status": "WARNING_RECORDED",
    "totalScore": 90,
    "signalCount": 3,
    "primarySignalType": "USER_BLOCKED",
    "evaluationPolicyVersion": 7,
    "firstDetectedAt": "2026-08-15T02:00:00Z",
    "lastDetectedAt": "2026-08-20T08:30:00Z",
    "evaluatedAt": "2026-08-20T08:30:01Z",
    "version": 3,
    "createdAt": "2026-08-20T08:30:01Z",
    "updatedAt": "2026-08-20T09:10:00Z"
  },
  "error": null
}
```

허용 action: `DISMISSED`, `WARNING_RECORDED`.

`OPEN`에서 바로 종료하거나 `REVIEWING`을 거쳐 종료할 수 있다. Action 상세 이력은 Case 상세 응답의 `actions`에서 확인한다.

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
  "message": "안전 검토 증거를 조회했습니다.",
  "data": {
    "items": [
      {
        "signalId": 901,
        "signalType": "USER_BLOCKED",
        "sourceType": "USER_BLOCK",
        "sourceId": 4201,
        "occurredAt": "2026-08-20T08:20:00Z",
        "accessStatus": "AVAILABLE",
        "source": {
          "subjectPublicTag": "이웃#A120F8",
          "targetPublicTag": "이웃#B920D1",
          "sourceStatus": "ACTIVE",
          "sourceOccurredAt": "2026-08-20T08:20:00Z"
        }
      }
    ],
    "page": { "nextCursor": null, "hasNext": false }
  },
  "error": null
}
```

현재 원천 요약을 지원하는 `sourceType`은 `USER_BLOCK`, `GREETING`이다. 지원하지 않거나 삭제된 원천은 `UNSUPPORTED` 또는 `SOURCE_NOT_FOUND`로 반환한다. 채팅 원문과 Media URL은 반환하지 않는다.

조회 성공·실패와 무관하게 권한이 확인된 실제 Evidence 접근 시 감사 이력을 남긴다. 감사에는 목적·resource 식별자·결과만 저장하며 JWT·이메일·AI prompt·원문을 포함하지 않는다.

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
- `RiskSourceEventPublisher.enqueue`는 원천 DB 트랜잭션 안에서 Outbox를 적재한다. Relay는 due/stale 이벤트를 한 건씩 선점해 기존 JSON을 발행하고 Kafka ack를 기다린다.
- 상태는 `PENDING → PROCESSING → SENT/RETRY/FAILED`이며 lease 만료 `PROCESSING`은 새 `claimToken`으로 재선점한다. 상태 변경은 `id + PROCESSING + claimToken`으로 fencing한다.
- 전달은 at-least-once다. Kafka ack 후 `SENT` 갱신 실패나 timeout 뒤 늦은 broker 성공으로 중복될 수 있으므로 Consumer는 `eventId` 멱등 처리가 필수다.
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
