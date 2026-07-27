# 같이놀개 M1 API

> 버전: `1.3.0-m1`
> 형식: 정적 OpenAPI를 사람이 읽도록 풀어쓴 상세 설명
> REST 계약 정본: `04_M1_OpenAPI.yaml`

최신 제품 정책과 M1 통합 ERD를 기준으로 작성한 REST 설명 문서다.<br>
계약 충돌 시 정적 `04_M1_OpenAPI.yaml`을 따른다. 런타임 `/v3/api-docs`는 현재 구현 관찰용이며 필수 CI Gate가 아니다.<br>
M1은 폴링 방식이며 WebSocket, Push, 읽음 표시, 메시지 수정·삭제,<br>
사용자 셋로그 업로드, 회원탈퇴, 욕설 자동 차단 및 AI 대화 검열을 제공하지 않는다.<br>
최상위 bearerAuth가 적용되는 모든 operation은 인증 실패 시 401 ErrorEnvelope를 반환한다.<br>

## 1. 공통 규칙

- Base URL: `/`
- 인증: 보호 API는 `Authorization: Bearer <accessToken>` 사용
- 성공·오류 응답은 공통 Envelope 구조 사용
- 시간: UTC ISO-8601, 화면에서 Asia/Seoul로 변환
- M1은 REST 폴링 방식이며 WebSocket·Push를 사용하지 않음

## 2. 엔드포인트 목록

| 그룹 | Method | Path | 설명 | 인증 |
|---|---|---|---|---|
| Neighborhood | `GET` | `/neighborhoods` | 가입 가능한 동네 목록 | 불필요 |
| Auth | `POST` | `/auth/signup` | 이메일 회원가입 | 불필요 |
| Auth | `POST` | `/auth/login` | 이메일 로그인 | 불필요 |
| Auth | `POST` | `/auth/refresh` | Refresh Token 회전 | 불필요 |
| Auth | `POST` | `/auth/logout` | 로그아웃 | Bearer |
| Me | `GET` | `/me` | 내 정보 조회 | Bearer |
| Me | `PUT` | `/me/active-pet` | Active Pet 변경 | Bearer |
| Pet | `POST` | `/pets` | Pet 직접 등록 | Bearer |
| Pet | `GET` | `/pets/me` | 내 Pet 목록 | Bearer |
| Pet | `GET` | `/pets/search` | Pet 공개 태그 검색 | Bearer |
| Pet | `DELETE` | `/pets/{petId}` | Pet 삭제 | Bearer |
| Pet | `GET` | `/pets/{petId}` | Pet 상세 조회 | Bearer |
| Pet | `PATCH` | `/pets/{petId}` | Pet 수정 | Bearer |
| PetVerification | `POST` | `/pet-registration/attempts` | 동물 등록정보 조회 | Bearer |
| PetVerification | `POST` | `/pet-registration/attempts/{attemptId}/consume` | 조회 결과를 Pet에 적용 | Bearer |
| Setlog | `GET` | `/setlogs` | M1 공통 시드 셋로그 3개 조회 | Bearer |
| Setlog | `DELETE` | `/setlogs/{setlogId}/reactions/{type}` | 셋로그 반응 취소 | Bearer |
| Setlog | `PUT` | `/setlogs/{setlogId}/reactions/{type}` | 셋로그 반응 추가 | Bearer |
| Greeting | `POST` | `/setlogs/{setlogId}/greetings` | 고정 인사 전송 및 DIRECT 방 생성 | Bearer |
| Friend | `POST` | `/friend-requests` | 친구 요청 | Bearer |
| Friend | `GET` | `/friend-requests/received` | 받은 친구 요청 목록 | Bearer |
| Friend | `GET` | `/friend-requests/sent` | 보낸 친구 요청 목록 | Bearer |
| Friend | `POST` | `/friend-requests/{requestId}/accept` | 친구 요청 수락 | Bearer |
| Friend | `POST` | `/friend-requests/{requestId}/reject` | 친구 요청 거절 | Bearer |
| Friend | `DELETE` | `/friend-requests/{requestId}` | 보낸 친구 요청 취소 | Bearer |
| Friend | `GET` | `/pets/{petId}/friends` | Pet 친구 목록 | Bearer |
| Friend | `DELETE` | `/pets/{petId}/friends/{friendPetId}` | 친구 삭제 | Bearer |
| Chat | `GET` | `/chat/rooms` | DIRECT 방 목록 폴링 | Bearer |
| Chat | `GET` | `/chat/rooms/{roomId}` | DIRECT 방 상세 | Bearer |
| Chat | `GET` | `/chat/rooms/{roomId}/messages` | 새 메시지 폴링 | Bearer |
| Chat | `POST` | `/chat/rooms/{roomId}/messages` | TEXT 메시지 전송 | Bearer |
| MeetingCard | `POST` | `/chat/rooms/{roomId}/card-drafts` | AI 약속 카드 초안 생성 | Bearer |
| MeetingCard | `POST` | `/meeting-cards` | 약속 카드 확정 | Bearer |
| MeetingCard | `GET` | `/meeting-cards/{cardId}` | 약속 카드 상세 | Bearer |
| MeetingCard | `POST` | `/meeting-cards/{cardId}/cancel` | 약속 카드 취소 | Bearer |
| Block | `GET` | `/me/blocks` | 차단 목록 | Bearer |
| Block | `POST` | `/me/blocks` | User 단위 차단 | Bearer |
| Report | `POST` | `/reports` | DIRECT 방 신고 | Bearer |
| Admin | `GET` | `/admin/reports` | 관리자 신고 큐 | Bearer |
| Admin | `GET` | `/admin/reports/{reportId}` | 신고 상세 및 방 전체 대화 조회 | Bearer |
| Admin | `POST` | `/admin/reports/{reportId}/actions` | 관리자 신고 처리 | Bearer |

## 3. API 상세

### 3.1. Neighborhood

#### `GET /neighborhoods`

- operationId: `listNeighborhoods`
- 인증: 불필요

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 활성 동네 목록 | [`NeighborhoodListEnvelope`](#schema-neighborhoodlistenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": [
    {
      "code": "VALIDATION_FAILED",
      "sidoName": "string",
      "sigunguName": "string",
      "eupmyeondongName": "string"
    }
  ],
  "error": null
}
```

### 3.2. Auth

#### `POST /auth/signup`

- operationId: `signup`
- 인증: 불필요
- 설명: 이메일 인증 없이 가입하며 동네를 직접 선택한다.

**요청 본문**

- 필수: ㅇ
- 스키마: [`SignupRequest`](#schema-signuprequest)

**Request JSON**

```json
{
  "email": "user@example.com",
  "password": "example-password",
  "nickname": "몽이",
  "neighborhoodCode": "4113510600"
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `201` | 가입 및 토큰 발급 완료 | [`AuthTokensEnvelope`](#schema-authtokensenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 201**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "accessToken": "example-token",
    "refreshToken": "example-token",
    "accessTokenExpiresAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 400, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /auth/login`

- operationId: `login`
- 인증: 불필요

**요청 본문**

- 필수: ㅇ
- 스키마: [`LoginRequest`](#schema-loginrequest)

**Request JSON**

```json
{
  "email": "user@example.com",
  "password": "example-password"
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 로그인 완료 | [`AuthTokensEnvelope`](#schema-authtokensenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "accessToken": "example-token",
    "refreshToken": "example-token",
    "accessTokenExpiresAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 403**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /auth/refresh`

- operationId: `refreshTokens`
- 인증: 불필요

**요청 본문**

- 필수: ㅇ
- 스키마: [`RefreshRequest`](#schema-refreshrequest)

**Request JSON**

```json
{
  "refreshToken": "example-token"
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 토큰 회전 완료 | [`AuthTokensEnvelope`](#schema-authtokensenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "accessToken": "example-token",
    "refreshToken": "example-token",
    "accessTokenExpiresAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 400, 401**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /auth/logout`

- operationId: `logout`
- 인증: Bearer Token 필요
- 설명: 현재 사용자의 활성 Refresh Token을 모두 폐기한다.

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `204` | 로그아웃 완료 | object |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Error Response JSON — 401**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.3. Me

#### `GET /me`

- operationId: `getMe`
- 인증: Bearer Token 필요

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 내 정보 | [`MeEnvelope`](#schema-meenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "몽이",
    "publicTag": "몽이#A7K2",
    "role": "USER",
    "accountStatus": "ACTIVE",
    "accessLevel": "L2",
    "neighborhoodCode": "4113510600",
    "activePetId": 1
  },
  "error": null
}
```

**Error Response JSON — 401**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `PUT /me/active-pet`

- operationId: `selectActivePet`
- 인증: Bearer Token 필요
- 설명: Active Pet이 없는 L1 사용자도 호출할 수 있으며 `ACTIVE_PET_REQUIRED`를 선행 적용하지 않는다.<br>대상은 요청 User가 소유하고 `status=ACTIVE`, `deletedAt=null`인 Pet이어야 한다.<br>

**요청 본문**

- 필수: ㅇ
- 스키마: object

**Request JSON**

```json
{
  "petId": 1
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | Active Pet 변경 완료 | [`MeEnvelope`](#schema-meenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "몽이",
    "publicTag": "몽이#A7K2",
    "role": "USER",
    "accountStatus": "ACTIVE",
    "accessLevel": "L2",
    "neighborhoodCode": "4113510600",
    "activePetId": 1
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.4. Pet

#### `POST /pets`

- operationId: `createPet`
- 인증: Bearer Token 필요
- 설명: owner User를 직렬화한 생성 트랜잭션에서 `deleted_at IS NULL` Pet 수를 조회한다.<br>생성 전 수가 0이면 내부 `firstPetCandidate=true`로 확정하고 Pet을 Commit한다.<br>생성 Commit 뒤 후보인 경우에만 별도 트랜잭션으로 Active 지정을 시도한다.<br>Active 지정 실패에도 생성된 Pet은 유지되며 `201 + RETRY_REQUIRED`를 반환한다.<br>같은 owner의 미삭제 Pet은 SUSPENDED를 포함해 최대 5마리이며 초과 시 `409 PET_LIMIT_EXCEEDED`다.<br>

**요청 본문**

- 필수: ㅇ
- 스키마: [`PetCreateRequest`](#schema-petcreaterequest)

**Request JSON**

```json
{
  "nickname": "몽이",
  "breedCode": "string",
  "breedName": "string",
  "sex": "MALE",
  "neutered": true,
  "birthDate": "2026-07-24",
  "weightKg": 0,
  "sizeCode": "SMALL",
  "bio": "string",
  "personalityTags": [
    "string"
  ],
  "careNote": "string"
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `201` | Pet 등록 완료 | [`PetCreateEnvelope`](#schema-petcreateenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 미삭제 Pet 5마리 한도 초과 또는 생성 상태 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 201**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "pet": {
      "petId": 1,
      "ownerUserId": 1,
      "publicTag": "몽이#A7K2",
      "ownerPublicTag": "몽이#A7K2",
      "nickname": "몽이",
      "breedCode": "string",
      "breedName": "string",
      "sex": "MALE",
      "neutered": true,
      "birthDate": "2026-07-24",
      "weightKg": 1,
      "sizeCode": "string",
      "bio": "string",
      "personalityTags": [
        "string"
      ],
      "careNote": "string",
      "profileUrl": "https://example.com/resource",
      "status": "ACTIVE",
      "verified": true,
      "verifiedAt": "2026-07-24T09:00:00Z",
      "active": true
    },
    "activeAssignmentStatus": "ASSIGNED"
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "PET_LIMIT_EXCEEDED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /pets/me`

- operationId: `listMyPets`
- 인증: Bearer Token 필요

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 내 Pet 목록 | [`PetListEnvelope`](#schema-petlistenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": [
    {
      "petId": 1,
      "ownerUserId": 1,
      "publicTag": "몽이#A7K2",
      "ownerPublicTag": "몽이#A7K2",
      "nickname": "몽이",
      "breedCode": "string",
      "breedName": "string",
      "sex": "MALE",
      "neutered": true,
      "birthDate": "2026-07-24",
      "weightKg": 1,
      "sizeCode": "string",
      "bio": "string",
      "personalityTags": [
        "string"
      ],
      "careNote": "string",
      "profileUrl": "https://example.com/resource",
      "status": "ACTIVE",
      "verified": true,
      "verifiedAt": "2026-07-24T09:00:00Z",
      "active": true
    }
  ],
  "error": null
}
```

**Error Response JSON — 401**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /pets/search`

- operationId: `searchPetByPublicTag`
- 인증: Bearer Token 필요
- 설명: 친구 요청 진입점이다. 차단 관계와 자기 소유 Pet은 결과에서 제외한다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `publicTag` | query | ㅇ | string | maxLength: 30 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 검색 결과 | [`PetSearchEnvelope`](#schema-petsearchenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "petId": 1,
    "publicTag": "몽이#A7K2",
    "nickname": "몽이",
    "profileUrl": "https://example.com/resource",
    "verified": true,
    "relationship": "NONE"
  },
  "error": null
}
```

**Error Response JSON — 401, 403**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `DELETE /pets/{petId}`

- operationId: `deletePet`
- 인증: Bearer Token 필요
- 설명: Active Pet은 삭제할 수 없다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `petId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `204` | 삭제 완료 | object |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Error Response JSON — 401, 403, 404, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /pets/{petId}`

- operationId: `getMyPet`
- 인증: Bearer Token 필요
- 설명: 본인 소유 Pet의 전체 정보를 조회한다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `petId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | Pet 상세 | [`PetEnvelope`](#schema-petenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "petId": 1,
    "ownerUserId": 1,
    "publicTag": "몽이#A7K2",
    "ownerPublicTag": "몽이#A7K2",
    "nickname": "몽이",
    "breedCode": "string",
    "breedName": "string",
    "sex": "MALE",
    "neutered": true,
    "birthDate": "2026-07-24",
    "weightKg": 1,
    "sizeCode": "string",
    "bio": "string",
    "personalityTags": [
      "string"
    ],
    "careNote": "string",
    "profileUrl": "https://example.com/resource",
    "status": "ACTIVE",
    "verified": true,
    "verifiedAt": "2026-07-24T09:00:00Z",
    "active": true
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `PATCH /pets/{petId}`

- operationId: `updatePet`
- 인증: Bearer Token 필요
- 설명: nickname, breedCode, breedName, sex, neutered, birthDate 중<br>인증 당시 스냅샷과 다른 값이 생기면 인증 배지를 해제한다.<br>

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `petId` | path | ㅇ | integer | format: int64 | - |

**요청 본문**

- 필수: ㅇ
- 스키마: [`PetUpdateRequest`](#schema-petupdaterequest)

**Request JSON**

```json
{
  "nickname": "몽이",
  "breedCode": "string",
  "breedName": "string",
  "sex": "MALE",
  "neutered": true,
  "birthDate": "2026-07-24",
  "weightKg": 0,
  "sizeCode": "SMALL",
  "bio": "string",
  "personalityTags": [
    "string"
  ],
  "careNote": "string"
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 수정 완료 | [`PetEnvelope`](#schema-petenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "petId": 1,
    "ownerUserId": 1,
    "publicTag": "몽이#A7K2",
    "ownerPublicTag": "몽이#A7K2",
    "nickname": "몽이",
    "breedCode": "string",
    "breedName": "string",
    "sex": "MALE",
    "neutered": true,
    "birthDate": "2026-07-24",
    "weightKg": 1,
    "sizeCode": "string",
    "bio": "string",
    "personalityTags": [
      "string"
    ],
    "careNote": "string",
    "profileUrl": "https://example.com/resource",
    "status": "ACTIVE",
    "verified": true,
    "verifiedAt": "2026-07-24T09:00:00Z",
    "active": true
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.5. PetVerification

#### `POST /pet-registration/attempts`

- operationId: `lookupPetRegistration`
- 인증: Bearer Token 필요
- 설명: M1 Provider는 동기 처리한다.<br>보호자 이름과 생년월일은 Provider 요청에만 사용하고 저장하거나 로그에 남기지 않는다.<br>canonical 등록번호가 없으면 `REJECTED`로 응답하고 배지를 발급하지 않되 직접 Pet 등록은 허용한다.<br>

**요청 본문**

- 필수: ㅇ
- 스키마: [`PetVerificationAttemptRequest`](#schema-petverificationattemptrequest)

**Request JSON**

```json
{
  "identifierType": "REGISTRATION_NUMBER",
  "identifier": "string",
  "ownerName": "string",
  "ownerBirthDate": "2026-07-24",
  "consentVersion": "string"
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 조회 결과 및 일회용 적용 토큰 | [`PetVerificationAttemptEnvelope`](#schema-petverificationattemptenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 요청 상태 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "attemptId": 1,
    "status": "SUCCEEDED",
    "resultCode": "string",
    "verificationToken": "example-token",
    "expiresAt": "2026-07-24T09:00:00Z",
    "petPrefill": {
      "nickname": "몽이",
      "breedCode": "string",
      "breedName": "string",
      "sex": "MALE",
      "neutered": true,
      "birthDate": "2026-07-24",
      "weightKg": 0,
      "sizeCode": "SMALL",
      "bio": "string",
      "personalityTags": [
        "string"
      ],
      "careNote": "string"
    }
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /pet-registration/attempts/{attemptId}/consume`

- operationId: `consumePetRegistrationAttempt`
- 인증: Bearer Token 필요
- 설명: `SUCCEEDED` Attempt에서만 허용하며 이미 `CONSUMED`된 Attempt는 `409`를 반환한다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `attemptId` | path | ㅇ | integer | format: int64 | - |

**요청 본문**

- 필수: ㅇ
- 스키마: object

**Request JSON**

```json
{
  "petId": 1,
  "verificationToken": "example-token"
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 인증 배지 적용 완료 | [`PetEnvelope`](#schema-petenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "petId": 1,
    "ownerUserId": 1,
    "publicTag": "몽이#A7K2",
    "ownerPublicTag": "몽이#A7K2",
    "nickname": "몽이",
    "breedCode": "string",
    "breedName": "string",
    "sex": "MALE",
    "neutered": true,
    "birthDate": "2026-07-24",
    "weightKg": 1,
    "sizeCode": "string",
    "bio": "string",
    "personalityTags": [
      "string"
    ],
    "careNote": "string",
    "profileUrl": "https://example.com/resource",
    "status": "ACTIVE",
    "verified": true,
    "verifiedAt": "2026-07-24T09:00:00Z",
    "active": true
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 404, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.6. Setlog

#### `GET /setlogs`

- operationId: `listSeedSetlogs`
- 인증: Bearer Token 필요
- 설명: L1 이상 모든 사용자에게 동일한 시드 영상 3개를 반환한다.

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 시드 셋로그 | [`SetlogListEnvelope`](#schema-setloglistenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": [
    {
      "setlogId": 1,
      "authorPet": {
        "petId": 1,
        "publicTag": "몽이#A7K2",
        "nickname": "몽이",
        "profileUrl": "https://example.com/resource",
        "verified": true,
        "relationship": "NONE"
      },
      "mediaUrl": "https://example.com/resource",
      "mediaUrlExpiresAt": "2026-07-24T09:00:00Z",
      "caption": "string",
      "cuteCount": 0,
      "likeCount": 0,
      "myReactions": [
        "CUTE"
      ],
      "canInteract": true,
      "createdAt": "2026-07-24T09:00:00Z"
    }
  ],
  "error": null
}
```

**Error Response JSON — 401**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `DELETE /setlogs/{setlogId}/reactions/{type}`

- operationId: `removeSetlogReaction`
- 인증: Bearer Token 필요
- 설명: 같은 버튼을 다시 누르면 클라이언트가 이 API를 호출한다.<br>반응이 이미 없어도 200과 reacted=false를 반환하는 멱등 API다.<br>

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `setlogId` | path | ㅇ | integer | format: int64 | - |
| `type` | path | ㅇ | [`ReactionType`](#schema-reactiontype) | - | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 반응 상태 | [`SetlogReactionEnvelope`](#schema-setlogreactionenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "setlogId": 1,
    "type": "CUTE",
    "reacted": false,
    "cuteCount": 1,
    "likeCount": 1
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `PUT /setlogs/{setlogId}/reactions/{type}`

- operationId: `addSetlogReaction`
- 인증: Bearer Token 필요
- 설명: 동일 반응 추가는 멱등 처리한다. CUTE와 LIKE는 동시에 존재할 수 있다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `setlogId` | path | ㅇ | integer | format: int64 | - |
| `type` | path | ㅇ | [`ReactionType`](#schema-reactiontype) | - | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 반응 상태 | [`SetlogReactionEnvelope`](#schema-setlogreactionenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "setlogId": 1,
    "type": "CUTE",
    "reacted": true,
    "cuteCount": 1,
    "likeCount": 1
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.7. Greeting

#### `POST /setlogs/{setlogId}/greetings`

- operationId: `sendGreeting`
- 인증: Bearer Token 필요
- 설명: 요청 본문은 없다. 서버가 "안녕하세요! 같이 놀아요." TEXT를 저장한다.<br>상대가 답변하기 전에는 추가 메시지를 보낼 수 없다.<br>하루 10명 제한은 Asia/Seoul 날짜 경계로 계산한다.<br>

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `setlogId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `201` | 인사 및 방 생성 완료 | [`GreetingEnvelope`](#schema-greetingenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `429` | 일일 제한 초과 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 201**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "greetingId": 1,
    "roomId": 1,
    "status": "SENT",
    "fixedMessage": "안녕하세요! 같이 놀아요.",
    "expiresAt": "2026-07-24T09:00:00Z",
    "createdAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404, 409, 429**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.8. Friend

#### `POST /friend-requests`

- operationId: `createFriendRequest`
- 인증: Bearer Token 필요
- 설명: Active Pet이 요청한다. 반대 방향 PENDING이 있으면 자동수락한다.

**요청 본문**

- 필수: ㅇ
- 스키마: object

**Request JSON**

```json
{
  "targetPetId": 2
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 반대 방향 요청 자동수락 및 DIRECT 방 보장 | [`FriendRequestEnvelope`](#schema-friendrequestenvelope) |
| `201` | PENDING 요청 생성 | [`FriendRequestEnvelope`](#schema-friendrequestenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "requestId": 1,
    "requesterPet": {
      "petId": 1,
      "publicTag": "몽이#A7K2",
      "nickname": "몽이",
      "profileUrl": "https://example.com/resource",
      "verified": true,
      "relationship": "NONE"
    },
    "targetPet": {
      "petId": 1,
      "publicTag": "몽이#A7K2",
      "nickname": "몽이",
      "profileUrl": "https://example.com/resource",
      "verified": true,
      "relationship": "NONE"
    },
    "status": "PENDING",
    "requestedAt": "2026-07-24T09:00:00Z",
    "respondedAt": null,
    "expiresAt": "2026-07-24T09:00:00Z",
    "directRoomId": 1
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 403, 404, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /friend-requests/received`

- operationId: `listReceivedFriendRequests`
- 인증: Bearer Token 필요
- 설명: 현재 Active Pet이 받은 요청만 반환한다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `cursor` | query | ㄴ | string | - | - |
| `limit` | query | ㄴ | integer | minimum: 1<br>maximum: 100<br>default: 20 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 받은 요청 | [`FriendRequestListEnvelope`](#schema-friendrequestlistenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "items": [
      {
        "requestId": 1,
        "requesterPet": {
          "petId": 1,
          "publicTag": "몽이#A7K2",
          "nickname": "몽이",
          "profileUrl": "https://example.com/resource",
          "verified": true,
          "relationship": "NONE"
        },
        "targetPet": {
          "petId": 1,
          "publicTag": "몽이#A7K2",
          "nickname": "몽이",
          "profileUrl": "https://example.com/resource",
          "verified": true,
          "relationship": "NONE"
        },
        "status": "PENDING",
        "requestedAt": "2026-07-24T09:00:00Z",
        "respondedAt": null,
        "expiresAt": "2026-07-24T09:00:00Z",
        "directRoomId": 1
      }
    ],
    "page": {
      "nextCursor": "string",
      "hasNext": true
    }
  },
  "error": null
}
```

**Error Response JSON — 401, 403**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /friend-requests/sent`

- operationId: `listSentFriendRequests`
- 인증: Bearer Token 필요
- 설명: 현재 Active Pet이 보낸 요청만 반환한다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `cursor` | query | ㄴ | string | - | - |
| `limit` | query | ㄴ | integer | minimum: 1<br>maximum: 100<br>default: 20 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 보낸 요청 | [`FriendRequestListEnvelope`](#schema-friendrequestlistenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "items": [
      {
        "requestId": 1,
        "requesterPet": {
          "petId": 1,
          "publicTag": "몽이#A7K2",
          "nickname": "몽이",
          "profileUrl": "https://example.com/resource",
          "verified": true,
          "relationship": "NONE"
        },
        "targetPet": {
          "petId": 1,
          "publicTag": "몽이#A7K2",
          "nickname": "몽이",
          "profileUrl": "https://example.com/resource",
          "verified": true,
          "relationship": "NONE"
        },
        "status": "PENDING",
        "requestedAt": "2026-07-24T09:00:00Z",
        "respondedAt": null,
        "expiresAt": "2026-07-24T09:00:00Z",
        "directRoomId": 1
      }
    ],
    "page": {
      "nextCursor": "string",
      "hasNext": true
    }
  },
  "error": null
}
```

**Error Response JSON — 401, 403**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /friend-requests/{requestId}/accept`

- operationId: `acceptFriendRequest`
- 인증: Bearer Token 필요

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `requestId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 수락, Friendship 생성 및 DIRECT 방 보장 | [`FriendRequestEnvelope`](#schema-friendrequestenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "requestId": 1,
    "requesterPet": {
      "petId": 1,
      "publicTag": "몽이#A7K2",
      "nickname": "몽이",
      "profileUrl": "https://example.com/resource",
      "verified": true,
      "relationship": "NONE"
    },
    "targetPet": {
      "petId": 1,
      "publicTag": "몽이#A7K2",
      "nickname": "몽이",
      "profileUrl": "https://example.com/resource",
      "verified": true,
      "relationship": "NONE"
    },
    "status": "ACCEPTED",
    "requestedAt": "2026-07-24T09:00:00Z",
    "respondedAt": "2026-07-24T09:00:00Z",
    "expiresAt": "2026-07-24T09:00:00Z",
    "directRoomId": 1
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /friend-requests/{requestId}/reject`

- operationId: `rejectFriendRequest`
- 인증: Bearer Token 필요

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `requestId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 거절 완료 | [`FriendRequestEnvelope`](#schema-friendrequestenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "requestId": 1,
    "requesterPet": {
      "petId": 1,
      "publicTag": "몽이#A7K2",
      "nickname": "몽이",
      "profileUrl": "https://example.com/resource",
      "verified": true,
      "relationship": "NONE"
    },
    "targetPet": {
      "petId": 1,
      "publicTag": "몽이#A7K2",
      "nickname": "몽이",
      "profileUrl": "https://example.com/resource",
      "verified": true,
      "relationship": "NONE"
    },
    "status": "REJECTED",
    "requestedAt": "2026-07-24T09:00:00Z",
    "respondedAt": "2026-07-24T09:00:00Z",
    "expiresAt": "2026-07-24T09:00:00Z",
    "directRoomId": 1
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `DELETE /friend-requests/{requestId}`

- operationId: `cancelFriendRequest`
- 인증: Bearer Token 필요

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `requestId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `204` | 취소 완료 | object |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Error Response JSON — 401, 403, 404, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /pets/{petId}/friends`

- operationId: `listPetFriends`
- 인증: Bearer Token 필요

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `petId` | path | ㅇ | integer | format: int64 | - |
| `cursor` | query | ㄴ | string | - | - |
| `limit` | query | ㄴ | integer | minimum: 1<br>maximum: 100<br>default: 20 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 친구 목록 | [`PetSearchListEnvelope`](#schema-petsearchlistenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "items": [
      {
        "petId": 1,
        "publicTag": "몽이#A7K2",
        "nickname": "몽이",
        "profileUrl": "https://example.com/resource",
        "verified": true,
        "relationship": "NONE"
      }
    ],
    "page": {
      "nextCursor": "string",
      "hasNext": true
    }
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `DELETE /pets/{petId}/friends/{friendPetId}`

- operationId: `deletePetFriend`
- 인증: Bearer Token 필요
- 설명: 기존 DIRECT 방과 메시지는 유지하며 계속 대화할 수 있다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `petId` | path | ㅇ | integer | format: int64 | - |
| `friendPetId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `204` | 친구 삭제 완료 | object |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.9. Chat

#### `GET /chat/rooms`

- operationId: `listChatRooms`
- 인증: Bearer Token 필요
- 설명: 현재 Active Pet이 Participant인 방을 lastMessageAt 내림차순으로 반환한다.<br>차단 관계 방은 숨기되 증거 보존을 위해 삭제하지 않는다.<br>

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `cursor` | query | ㄴ | string | - | - |
| `limit` | query | ㄴ | integer | minimum: 1<br>maximum: 100<br>default: 20 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 방 목록 | [`ChatRoomListEnvelope`](#schema-chatroomlistenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "items": [
      {
        "roomId": 1,
        "status": "ACTIVE",
        "origin": "GREETING",
        "counterpartPet": {
          "petId": 1,
          "publicTag": "몽이#A7K2",
          "nickname": "몽이",
          "profileUrl": "https://example.com/resource",
          "verified": true,
          "relationship": "NONE"
        },
        "canSend": true,
        "sendBlockedReason": "GREETING_REPLY_REQUIRED",
        "lastMessage": {
          "messageId": 1,
          "roomId": 1,
          "senderType": "PET",
          "senderPetId": 1,
          "type": "TEXT",
          "body": "안녕하세요.",
          "meetingCardId": 1,
          "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
          "createdAt": "2026-07-24T09:00:00Z"
        },
        "lastMessageAt": "2026-07-24T09:00:00Z",
        "updatedAt": "2026-07-24T09:00:00Z"
      }
    ],
    "page": {
      "nextCursor": "string",
      "hasNext": true
    }
  },
  "error": null
}
```

**Error Response JSON — 401, 403**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /chat/rooms/{roomId}`

- operationId: `getChatRoom`
- 인증: Bearer Token 필요

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `roomId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 방 상세 | [`ChatRoomEnvelope`](#schema-chatroomenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "roomId": 1,
    "status": "ACTIVE",
    "origin": "GREETING",
    "counterpartPet": {
      "petId": 1,
      "publicTag": "몽이#A7K2",
      "nickname": "몽이",
      "profileUrl": "https://example.com/resource",
      "verified": true,
      "relationship": "NONE"
    },
    "canSend": true,
    "sendBlockedReason": "GREETING_REPLY_REQUIRED",
    "lastMessage": {
      "messageId": 1,
      "roomId": 1,
      "senderType": "PET",
      "senderPetId": 1,
      "type": "TEXT",
      "body": "안녕하세요.",
      "meetingCardId": 1,
      "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
      "createdAt": "2026-07-24T09:00:00Z"
    },
    "lastMessageAt": "2026-07-24T09:00:00Z",
    "updatedAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /chat/rooms/{roomId}/messages`

- operationId: `listChatMessages`
- 인증: Bearer Token 필요
- 설명: afterMessageId보다 큰 메시지를 ID 오름차순으로 반환한다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `roomId` | path | ㅇ | integer | format: int64 | - |
| `afterMessageId` | query | ㄴ | integer | format: int64<br>minimum: 0 | - |
| `limit` | query | ㄴ | integer | minimum: 1<br>maximum: 100<br>default: 50 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 메시지 목록 | [`ChatMessageListEnvelope`](#schema-chatmessagelistenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "items": [
      {
        "messageId": 1,
        "roomId": 1,
        "senderType": "PET",
        "senderPetId": 1,
        "type": "TEXT",
        "body": "안녕하세요.",
        "meetingCardId": 1,
        "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
        "createdAt": "2026-07-24T09:00:00Z"
      }
    ],
    "nextAfterMessageId": 1,
    "hasMore": false
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /chat/rooms/{roomId}/messages`

- operationId: `sendChatMessage`
- 인증: Bearer Token 필요
- 설명: clientMessageId로 재시도 멱등성을 보장한다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `roomId` | path | ㅇ | integer | format: int64 | - |

**요청 본문**

- 필수: ㅇ
- 스키마: [`ChatMessageCreateRequest`](#schema-chatmessagecreaterequest)

**Request JSON**

```json
{
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
  "body": "안녕하세요."
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 동일 clientMessageId의 기존 메시지 반환 | [`ChatMessageEnvelope`](#schema-chatmessageenvelope) |
| `201` | 메시지 생성 | [`ChatMessageEnvelope`](#schema-chatmessageenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "messageId": 1,
    "roomId": 1,
    "senderType": "PET",
    "senderPetId": 1,
    "type": "TEXT",
    "body": "안녕하세요.",
    "meetingCardId": 1,
    "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
    "createdAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 403, 404, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.10. MeetingCard

#### `POST /chat/rooms/{roomId}/card-drafts`

- operationId: `createCardDraft`
- 인증: Bearer Token 필요
- 설명: 프런트는 최근 24시간 내 사용자 TEXT 메시지가 2개 이상일 때 버튼을 활성화한다.<br>서버는 0~1개여도 거절하지 않고 `200 + 빈 폼 + INSUFFICIENT_CONTEXT`를 반환한다.<br>AI에는 최근 24시간 내 사용자 TEXT를 최대 30개만 전달하며 CARD·SYSTEM은 제외한다.<br>AI 실패·지연도 200 빈 폼으로 처리한다.<br>

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `roomId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 카드 초안 또는 빈 폼 | [`CardDraftEnvelope`](#schema-carddraftenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "draftId": 1,
    "roomId": 1,
    "cardType": "WALK",
    "placeText": "판교 공원",
    "meetAt": "2026-07-24T09:00:00Z",
    "fallback": true,
    "fallbackReason": "INSUFFICIENT_CONTEXT",
    "createdAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /meeting-cards`

- operationId: `createMeetingCard`
- 인증: Bearer Token 필요

**요청 본문**

- 필수: ㅇ
- 스키마: [`MeetingCardCreateRequest`](#schema-meetingcardcreaterequest)

**Request JSON**

```json
{
  "roomId": 1,
  "draftId": 1,
  "cardType": "WALK",
  "placeText": "판교 공원",
  "meetAt": "2026-07-24T09:00:00Z"
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `201` | 카드 및 CARD 메시지 생성 | [`MeetingCardEnvelope`](#schema-meetingcardenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 201**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "cardId": 1,
    "roomId": 1,
    "creatorPetId": 1,
    "participantPetIds": [
      1,
      2
    ],
    "cardType": "WALK",
    "placeText": "판교 공원",
    "meetAt": "2026-07-24T09:00:00Z",
    "status": "OPEN",
    "canceledByPetId": null,
    "canceledAt": null,
    "createdAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /meeting-cards/{cardId}`

- operationId: `getMeetingCard`
- 인증: Bearer Token 필요

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `cardId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 카드 상세 | [`MeetingCardEnvelope`](#schema-meetingcardenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "cardId": 1,
    "roomId": 1,
    "creatorPetId": 1,
    "participantPetIds": [
      1,
      2
    ],
    "cardType": "WALK",
    "placeText": "판교 공원",
    "meetAt": "2026-07-24T09:00:00Z",
    "status": "OPEN",
    "canceledByPetId": null,
    "canceledAt": null,
    "createdAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /meeting-cards/{cardId}/cancel`

- operationId: `cancelMeetingCard`
- 인증: Bearer Token 필요
- 설명: 참여 Pet 양쪽 모두 취소할 수 있다.

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `cardId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 취소 및 SYSTEM 메시지 생성 | [`MeetingCardEnvelope`](#schema-meetingcardenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "cardId": 1,
    "roomId": 1,
    "creatorPetId": 1,
    "participantPetIds": [
      1,
      2
    ],
    "cardType": "WALK",
    "placeText": "판교 공원",
    "meetAt": "2026-07-24T09:00:00Z",
    "status": "CANCELED",
    "canceledByPetId": 1,
    "canceledAt": "2026-07-24T09:00:00Z",
    "createdAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.11. Block

#### `GET /me/blocks`

- operationId: `listBlocks`
- 인증: Bearer Token 필요

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `cursor` | query | ㄴ | string | - | - |
| `limit` | query | ㄴ | integer | minimum: 1<br>maximum: 100<br>default: 20 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 차단 목록 | [`BlockListEnvelope`](#schema-blocklistenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "items": [
      {
        "blockId": 1,
        "blockedUserId": 2,
        "blockedUserPublicTag": "몽이#A7K2",
        "createdAt": "2026-07-24T09:00:00Z"
      }
    ],
    "page": {
      "nextCursor": "string",
      "hasNext": true
    }
  },
  "error": null
}
```

**Error Response JSON — 401**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /me/blocks`

- operationId: `createBlock`
- 인증: Bearer Token 필요
- 설명: Friendship 삭제, 양방향 PENDING 요청 취소, 상호작용과 노출을 차단한다.

**요청 본문**

- 필수: ㅇ
- 스키마: [`BlockCreateRequest`](#schema-blockcreaterequest)

**Request JSON**

```json
{
  "targetPetId": 2
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 기존 차단 관계 반환 | [`BlockEnvelope`](#schema-blockenvelope) |
| `201` | 차단 완료 | [`BlockEnvelope`](#schema-blockenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "blockId": 1,
    "blockedUserId": 2,
    "blockedUserPublicTag": "몽이#A7K2",
    "createdAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.12. Report

#### `POST /reports`

- operationId: `createReport`
- 인증: Bearer Token 필요
- 설명: 신고만으로 상대를 자동 차단하지 않는다.<br>동일 reporter·room에 OPEN 신고가 이미 있으면 새 행을 만들지 않고 기존 신고를 반환한다.<br>중복 요청의 reasonCode와 detail은 반영하지 않고 최초 OPEN 신고 내용을 유지한다.<br>reasonCode=OTHER이면 detail은 공백이 아닌 값으로 반드시 입력한다.<br>

**요청 본문**

- 필수: ㅇ
- 스키마: [`ReportCreateRequest`](#schema-reportcreaterequest)

**Request JSON**

```json
{
  "roomId": 1,
  "reasonCode": "HARASSMENT",
  "detail": "처리 내용입니다."
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 동일 reporter·room의 기존 OPEN 신고 반환 | [`ReportEnvelope`](#schema-reportenvelope) |
| `201` | 신규 신고 접수 | [`ReportEnvelope`](#schema-reportenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "reportId": 1,
    "roomId": 1,
    "reporterUserId": 1,
    "reportedUserId": 2,
    "reasonCode": "HARASSMENT",
    "detail": "처리 내용입니다.",
    "status": "OPEN",
    "reviewedByAdminId": null,
    "reviewedAt": null,
    "resolutionNote": null,
    "createdAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

### 3.13. Admin

#### `GET /admin/reports`

- operationId: `listAdminReports`
- 인증: Bearer Token 필요

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `status` | query | ㄴ | [`ReportStatus`](#schema-reportstatus) | - | - |
| `page` | query | ㄴ | integer | minimum: 0<br>default: 0 | - |
| `size` | query | ㄴ | integer | minimum: 1<br>maximum: 100<br>default: 20 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 신고 큐 | [`AdminReportPageEnvelope`](#schema-adminreportpageenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "items": [
      {
        "reportId": 1,
        "roomId": 1,
        "reporterUserId": 1,
        "reportedUserId": 2,
        "reasonCode": "HARASSMENT",
        "detail": "처리 내용입니다.",
        "status": "OPEN",
        "reviewedByAdminId": null,
        "reviewedAt": null,
        "resolutionNote": null,
        "createdAt": "2026-07-24T09:00:00Z"
      }
    ],
    "page": {
      "page": 1,
      "size": 1,
      "totalElements": 1,
      "totalPages": 1
    }
  },
  "error": null
}
```

**Error Response JSON — 401, 403**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `GET /admin/reports/{reportId}`

- operationId: `getAdminReport`
- 인증: Bearer Token 필요
- 설명: 조회 자체는 상태를 변경하지 않는다.<br>사용자용 `counterpartPet` DTO를 재사용하지 않고 신고자·피신고자 User와 양쪽 Pet, 방, 전체 메시지를 관리자 전용 Evidence DTO로 반환한다.<br>

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `reportId` | path | ㅇ | integer | format: int64 | - |

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 신고와 증거 전체 | [`AdminReportDetailEnvelope`](#schema-adminreportdetailenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "report": {
      "reportId": 1,
      "roomId": 1,
      "reporterUserId": 1,
      "reportedUserId": 2,
      "reasonCode": "HARASSMENT",
      "detail": "처리 내용입니다.",
      "status": "OPEN",
      "reviewedByAdminId": null,
      "reviewedAt": null,
      "resolutionNote": null,
      "createdAt": "2026-07-24T09:00:00Z"
    },
    "reporter": {
      "userId": 1,
      "userPublicTag": "보호자#A7K2",
      "userNickname": "보호자",
      "petId": 1,
      "petPublicTag": "몽이#A7K2",
      "petNickname": "몽이"
    },
    "reported": {
      "userId": 2,
      "userPublicTag": "상대#B8M3",
      "userNickname": "상대",
      "petId": 2,
      "petPublicTag": "초코#B8M3",
      "petNickname": "초코"
    },
    "room": {
      "roomId": 1,
      "status": "ACTIVE",
      "participantPetIds": [1, 2],
      "createdAt": "2026-07-24T09:00:00Z"
    },
    "messages": [
      {
        "messageId": 1,
        "roomId": 1,
        "senderType": "PET",
        "senderPetId": 1,
        "type": "TEXT",
        "body": "안녕하세요.",
        "meetingCardId": 1,
        "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
        "createdAt": "2026-07-24T09:00:00Z"
      }
    ]
  },
  "error": null
}
```

**Error Response JSON — 401, 403, 404**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

#### `POST /admin/reports/{reportId}/actions`

- operationId: `resolveAdminReport`
- 인증: Bearer Token 필요
- 설명: M1은 DISMISSED와 WARNING만 제공한다.<br>DISMISSED는 NO_ACTION, WARNING은 ACTIONED로 종결한다.<br>WARNING은 계정 상태를 바꾸지 않고 관리자 경고 이력만 기록한다.<br>종결된 신고에는 추가 WARNING을 포함한 어떤 조치도 다시 적용할 수 없으며 409를 반환한다.<br>WARNING 결과를 사용자에게 노출하는 API·알림·화면은 M1에 없다.<br>

**파라미터**

| 이름 | 위치 | 필수 | 타입 | 제약·기본값 | 설명 |
|---|---|---:|---|---|---|
| `reportId` | path | ㅇ | integer | format: int64 | - |

**요청 본문**

- 필수: ㅇ
- 스키마: [`AdminReportActionRequest`](#schema-adminreportactionrequest)

**Request JSON**

```json
{
  "actionType": "DISMISSED",
  "reason": "처리 내용입니다."
}
```

**응답**

| HTTP | 설명 | 응답 스키마 |
|---:|---|---|
| `200` | 처리 완료 | [`ReportEnvelope`](#schema-reportenvelope) |
| `400` | 잘못된 요청 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `401` | 인증 실패 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `403` | 권한 또는 정책 위반 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `404` | 리소스 없음 | [`ErrorEnvelope`](#schema-errorenvelope) |
| `409` | 상태 또는 중복 충돌 | [`ErrorEnvelope`](#schema-errorenvelope) |

**Response JSON — 200**

```json
{
  "success": true,
  "message": "처리 내용입니다.",
  "data": {
    "reportId": 1,
    "roomId": 1,
    "reporterUserId": 1,
    "reportedUserId": 2,
    "reasonCode": "HARASSMENT",
    "detail": "처리 내용입니다.",
    "status": "NO_ACTION",
    "reviewedByAdminId": 1,
    "reviewedAt": "2026-07-24T09:00:00Z",
    "resolutionNote": "처리 내용입니다.",
    "createdAt": "2026-07-24T09:00:00Z"
  },
  "error": null
}
```

**Error Response JSON — 400, 401, 403, 404, 409**

```json
{
  "success": false,
  "message": "처리 내용입니다.",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "처리 내용입니다."
  }
}
```

## 4. 스키마

<a id="schema-errorenvelope"></a>
### `ErrorEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: False | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | null | - | - |
| `error` | ㅇ | object | - | - |

<a id="schema-signuprequest"></a>
### `SignupRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `email` | ㅇ | string | format: email<br>maxLength: 254 | - |
| `password` | ㅇ | string | minLength: 10<br>maxLength: 128 | - |
| `nickname` | ㅇ | string | minLength: 2<br>maxLength: 20 | - |
| `neighborhoodCode` | ㅇ | string | maxLength: 20 | - |

<a id="schema-loginrequest"></a>
### `LoginRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `email` | ㅇ | string | format: email | - |
| `password` | ㅇ | string | - | - |

<a id="schema-refreshrequest"></a>
### `RefreshRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `refreshToken` | ㅇ | string | - | - |

<a id="schema-authtokens"></a>
### `AuthTokens`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `accessToken` | ㅇ | string | - | - |
| `refreshToken` | ㅇ | string | - | - |
| `accessTokenExpiresAt` | ㅇ | string | format: date-time | - |

<a id="schema-authtokensenvelope"></a>
### `AuthTokensEnvelope`

- 별칭: [`EnvelopeAuthTokens`](#schema-envelopeauthtokens)

<a id="schema-neighborhood"></a>
### `Neighborhood`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `code` | ㅇ | string | - | - |
| `sidoName` | ㅇ | string | - | - |
| `sigunguName` | ㅇ | string / null | - | - |
| `eupmyeondongName` | ㅇ | string / null | - | - |

<a id="schema-me"></a>
### `Me`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `userId` | ㅇ | integer | format: int64 | - |
| `email` | ㅇ | string | format: email | - |
| `nickname` | ㅇ | string | - | - |
| `publicTag` | ㅇ | string | - | - |
| `role` | ㅇ | string | enum: USER, ADMIN, SUPER_ADMIN | - |
| `accountStatus` | ㅇ | string | enum: ACTIVE, SUSPENDED, WITHDRAWN | - |
| `accessLevel` | ㅇ | string | enum: L1, L2 | - |
| `neighborhoodCode` | ㅇ | string | - | - |
| `activePetId` | ㅇ | integer / null | format: int64 | - |

<a id="schema-petcreaterequest"></a>
### `PetCreateRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `nickname` | ㅇ | string | maxLength: 30 | - |
| `breedCode` | ㄴ | string / null | - | - |
| `breedName` | ㄴ | string / null | - | - |
| `sex` | ㄴ | [`NullablePetSex`](#schema-nullablepetsex) | - | [`NullablePetSex`](#schema-nullablepetsex) |
| `neutered` | ㄴ | boolean / null | - | - |
| `birthDate` | ㄴ | string / null | format: date | - |
| `weightKg` | ㄴ | number / null | minimum: 0 | - |
| `sizeCode` | ㄴ | string / null | enum: SMALL, MEDIUM, LARGE, null | - |
| `bio` | ㄴ | string / null | maxLength: 500 | - |
| `personalityTags` | ㄴ | array<string> | maxItems: 10 | - |
| `careNote` | ㄴ | string / null | maxLength: 500 | - |

<a id="schema-petupdaterequest"></a>
### `PetUpdateRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `nickname` | ㄴ | string | maxLength: 30 | - |
| `breedCode` | ㄴ | string / null | - | - |
| `breedName` | ㄴ | string / null | - | - |
| `sex` | ㄴ | [`NullablePetSex`](#schema-nullablepetsex) | - | [`NullablePetSex`](#schema-nullablepetsex) |
| `neutered` | ㄴ | boolean / null | - | - |
| `birthDate` | ㄴ | string / null | format: date | - |
| `weightKg` | ㄴ | number / null | minimum: 0 | - |
| `sizeCode` | ㄴ | string / null | enum: SMALL, MEDIUM, LARGE, null | - |
| `bio` | ㄴ | string / null | maxLength: 500 | - |
| `personalityTags` | ㄴ | array<string> | maxItems: 10 | - |
| `careNote` | ㄴ | string / null | maxLength: 500 | - |

<a id="schema-nullablepetsex"></a>
### `NullablePetSex`

- 타입: `string / null`
- 값: `MALE`, `FEMALE`, `UNKNOWN`, `null`

<a id="schema-pet"></a>
### `Pet`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `petId` | ㅇ | integer | format: int64 | - |
| `ownerUserId` | ㅇ | integer | format: int64 | - |
| `publicTag` | ㅇ | string | - | 서버가 생성한 Pet 공개 검색 태그 |
| `ownerPublicTag` | ㄴ | string | - | - |
| `nickname` | ㅇ | string | - | - |
| `breedCode` | ㄴ | string / null | - | - |
| `breedName` | ㄴ | string / null | - | - |
| `sex` | ㄴ | [`NullablePetSex`](#schema-nullablepetsex) | - | [`NullablePetSex`](#schema-nullablepetsex) |
| `neutered` | ㄴ | boolean / null | - | - |
| `birthDate` | ㄴ | string / null | format: date | - |
| `weightKg` | ㄴ | number / null | - | - |
| `sizeCode` | ㄴ | string / null | - | - |
| `bio` | ㄴ | string / null | - | - |
| `personalityTags` | ㄴ | array<string> | - | - |
| `careNote` | ㄴ | string / null | - | - |
| `profileUrl` | ㄴ | string / null | format: uri | M1은 업로드 API를 제공하지 않으며 서버 기본 이미지 URL을 반환할 수 있다. |
| `status` | ㅇ | string | enum: ACTIVE, SUSPENDED, DELETED | DELETED이면 deletedAt 필수, ACTIVE/SUSPENDED이면 deletedAt은 null |
| `deletedAt` | ㅇ | string / null | format: date-time | 삭제 시각. ACTIVE/SUSPENDED이면 null, DELETED이면 필수 값 |
| `verified` | ㅇ | boolean | - | - |
| `verifiedAt` | ㄴ | string / null | format: date-time | - |
| `active` | ㅇ | boolean | - | - |

<a id="schema-petsearchitem"></a>
### `PetSearchItem`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `petId` | ㅇ | integer | format: int64 | - |
| `publicTag` | ㅇ | string | - | Pet 공개 태그 |
| `nickname` | ㅇ | string | - | - |
| `profileUrl` | ㄴ | string / null | format: uri | - |
| `verified` | ㅇ | boolean | - | - |
| `relationship` | ㅇ | string / null | enum: NONE, REQUEST_SENT, REQUEST_RECEIVED, FRIEND, null | Active Pet이 없는 L1은 null |

<a id="schema-petverificationattemptrequest"></a>
### `PetVerificationAttemptRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `identifierType` | ㅇ | string | enum: REGISTRATION_NUMBER, RFID | - |
| `identifier` | ㅇ | string | maxLength: 100 | - |
| `ownerName` | ㅇ | string | maxLength: 100 | - |
| `ownerBirthDate` | ㅇ | string | format: date | - |
| `consentVersion` | ㅇ | string | maxLength: 30 | - |

<a id="schema-petverificationattempt"></a>
### `PetVerificationAttempt`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `attemptId` | ㅇ | integer | format: int64 | - |
| `status` | ㅇ | string | enum: SUCCEEDED, REJECTED, FAILED | canonical 등록번호가 없거나 정보가 불일치하면 REJECTED |
| `resultCode` | ㅇ | string | - | - |
| `verificationToken` | ㄴ | string / null | - | - |
| `expiresAt` | ㄴ | string / null | format: date-time | - |
| `petPrefill` | ㄴ | [`PetCreateRequest`](#schema-petcreaterequest) / null | - | - |

<a id="schema-reactiontype"></a>
### `ReactionType`

- 타입: `string`
- 값: `CUTE`, `LIKE`

<a id="schema-setlog"></a>
### `Setlog`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `setlogId` | ㅇ | integer | format: int64 | - |
| `authorPet` | ㅇ | [`PetSearchItem`](#schema-petsearchitem) | - | [`PetSearchItem`](#schema-petsearchitem) |
| `mediaUrl` | ㅇ | string | format: uri | 비공개 S3 객체를 재생하기 위한 Presigned GET URL |
| `mediaUrlExpiresAt` | ㅇ | string | format: date-time | 만료 후 GET /setlogs를 다시 호출해 URL을 갱신한다. |
| `caption` | ㅇ | string / null | - | - |
| `cuteCount` | ㅇ | integer | minimum: 0 | - |
| `likeCount` | ㅇ | integer | minimum: 0 | - |
| `myReactions` | ㅇ | array<[`ReactionType`](#schema-reactiontype)> | uniqueItems | - |
| `canInteract` | ㄴ | boolean | - | L2, 자기 소유 아님, 차단 관계 아님일 때 true |
| `createdAt` | ㅇ | string | format: date-time | - |

<a id="schema-setlogreaction"></a>
### `SetlogReaction`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `setlogId` | ㅇ | integer | format: int64 | - |
| `type` | ㅇ | [`ReactionType`](#schema-reactiontype) | - | [`ReactionType`](#schema-reactiontype) |
| `reacted` | ㅇ | boolean | - | - |
| `cuteCount` | ㅇ | integer | - | - |
| `likeCount` | ㅇ | integer | - | - |

<a id="schema-greeting"></a>
### `Greeting`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `greetingId` | ㅇ | integer | format: int64 | - |
| `roomId` | ㅇ | integer | format: int64 | - |
| `status` | ㅇ | string | enum: SENT, RESPONDED, EXPIRED | - |
| `fixedMessage` | ㅇ | string | const: 안녕하세요! 같이 놀아요. | - |
| `expiresAt` | ㅇ | string | format: date-time | - |
| `createdAt` | ㅇ | string | format: date-time | - |

<a id="schema-friendrequest"></a>
### `FriendRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `requestId` | ㅇ | integer | format: int64 | - |
| `requesterPet` | ㅇ | [`PetSearchItem`](#schema-petsearchitem) | - | [`PetSearchItem`](#schema-petsearchitem) |
| `targetPet` | ㅇ | [`PetSearchItem`](#schema-petsearchitem) | - | [`PetSearchItem`](#schema-petsearchitem) |
| `status` | ㅇ | string | enum: PENDING, ACCEPTED, REJECTED, CANCELED, EXPIRED | - |
| `requestedAt` | ㅇ | string | format: date-time | - |
| `respondedAt` | ㄴ | string / null | format: date-time | - |
| `expiresAt` | ㅇ | string | format: date-time | - |
| `directRoomId` | ㄴ | integer / null | format: int64 | ACCEPTED 상태이면 생성 또는 재사용한 DIRECT 방 ID |

<a id="schema-chatroom"></a>
### `ChatRoom`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `roomId` | ㅇ | integer | format: int64 | - |
| `status` | ㅇ | string | enum: ACTIVE, ARCHIVED | - |
| `origin` | ㄴ | string | enum: GREETING, FRIEND | - |
| `counterpartPet` | ㅇ | [`PetSearchItem`](#schema-petsearchitem) | - | [`PetSearchItem`](#schema-petsearchitem) |
| `canSend` | ㅇ | boolean | - | - |
| `sendBlockedReason` | ㄴ | string / null | enum: GREETING_REPLY_REQUIRED, BLOCKED_USER, ACCOUNT_NOT_ACTIVE, null | - |
| `lastMessage` | ㄴ | [`ChatMessage`](#schema-chatmessage) / null | - | - |
| `lastMessageAt` | ㅇ | string / null | format: date-time | - |
| `updatedAt` | ㅇ | string | format: date-time | - |

<a id="schema-chatmessagecreaterequest"></a>
### `ChatMessageCreateRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `clientMessageId` | ㅇ | string | maxLength: 64 | - |
| `body` | ㅇ | string | minLength: 1<br>maxLength: 2000 | - |

<a id="schema-chatmessage"></a>
### `ChatMessage`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `messageId` | ㅇ | integer | format: int64 | - |
| `roomId` | ㅇ | integer | format: int64 | - |
| `senderType` | ㅇ | string | enum: PET, SYSTEM | - |
| `senderPetId` | ㄴ | integer / null | format: int64 | - |
| `type` | ㅇ | string | enum: TEXT, CARD, SYSTEM | - |
| `body` | ㄴ | string / null | - | - |
| `meetingCardId` | ㄴ | integer / null | format: int64 | - |
| `clientMessageId` | ㄴ | string / null | - | - |
| `createdAt` | ㅇ | string | format: date-time | - |

<a id="schema-carddraft"></a>
### `CardDraft`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `draftId` | ㅇ | integer | format: int64 | - |
| `roomId` | ㅇ | integer | format: int64 | - |
| `cardType` | ㄴ | string / null | enum: WALK, PLAY, OTHER, null | - |
| `placeText` | ㄴ | string / null | maxLength: 500 | - |
| `meetAt` | ㄴ | string / null | format: date-time | - |
| `fallback` | ㅇ | boolean | - | - |
| `fallbackReason` | ㄴ | string / null | enum: TIMEOUT, MODEL_ERROR, INSUFFICIENT_CONTEXT, null | - |
| `createdAt` | ㅇ | string | format: date-time | - |

<a id="schema-meetingcardcreaterequest"></a>
### `MeetingCardCreateRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `roomId` | ㅇ | integer | format: int64 | - |
| `draftId` | ㄴ | integer / null | format: int64 | - |
| `cardType` | ㅇ | string | enum: WALK, PLAY, OTHER | - |
| `placeText` | ㅇ | string | minLength: 1<br>maxLength: 500 | - |
| `meetAt` | ㅇ | string | format: date-time | - |

<a id="schema-meetingcard"></a>
### `MeetingCard`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `cardId` | ㅇ | integer | format: int64 | - |
| `roomId` | ㅇ | integer | format: int64 | - |
| `creatorPetId` | ㅇ | integer | format: int64 | - |
| `participantPetIds` | ㅇ | array<integer> | minItems: 2<br>maxItems: 2 | - |
| `cardType` | ㅇ | string | enum: WALK, PLAY, OTHER | - |
| `placeText` | ㅇ | string | - | - |
| `meetAt` | ㅇ | string | format: date-time | - |
| `status` | ㅇ | string | enum: OPEN, CANCELED | - |
| `canceledByPetId` | ㄴ | integer / null | format: int64 | - |
| `canceledAt` | ㄴ | string / null | format: date-time | - |
| `createdAt` | ㅇ | string | format: date-time | - |

<a id="schema-blockcreaterequest"></a>
### `BlockCreateRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `targetPetId` | ㅇ | integer | format: int64 | 서버가 소유 User를 찾아 User 단위 차단으로 변환한다. |

<a id="schema-block"></a>
### `Block`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `blockId` | ㅇ | integer | format: int64 | - |
| `blockedUserId` | ㅇ | integer | format: int64 | - |
| `blockedUserPublicTag` | ㄴ | string | - | - |
| `createdAt` | ㅇ | string | format: date-time | - |

<a id="schema-reportreason"></a>
### `ReportReason`

- 타입: `string`
- 값: `HARASSMENT`, `SPAM`, `OTHER`

<a id="schema-reportstatus"></a>
### `ReportStatus`

- 타입: `string`
- 값: `OPEN`, `ACTIONED`, `NO_ACTION`

<a id="schema-reportcreaterequest"></a>
### `ReportCreateRequest`

- 조건부/조합 규칙:

  - 조건 `{'reasonCode': {'const': 'OTHER'}}`이면 필수 필드: `detail`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `roomId` | ㅇ | integer | format: int64 | - |
| `reasonCode` | ㅇ | [`ReportReason`](#schema-reportreason) | - | [`ReportReason`](#schema-reportreason) |
| `detail` | ㄴ | string / null | maxLength: 500 | - |

<a id="schema-report"></a>
### `Report`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `reportId` | ㅇ | integer | format: int64 | - |
| `roomId` | ㅇ | integer | format: int64 | - |
| `reporterUserId` | ㄴ | integer | format: int64 | - |
| `reportedUserId` | ㄴ | integer | format: int64 | - |
| `reasonCode` | ㅇ | [`ReportReason`](#schema-reportreason) | - | [`ReportReason`](#schema-reportreason) |
| `detail` | ㄴ | string / null | maxLength: 500 | - |
| `status` | ㅇ | [`ReportStatus`](#schema-reportstatus) | - | [`ReportStatus`](#schema-reportstatus) |
| `reviewedByAdminId` | ㄴ | integer / null | format: int64 | - |
| `reviewedAt` | ㄴ | string / null | format: date-time | - |
| `resolutionNote` | ㄴ | string / null | maxLength: 500 | - |
| `createdAt` | ㅇ | string | format: date-time | - |

<a id="schema-adminreportactionrequest"></a>
### `AdminReportActionRequest`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `actionType` | ㅇ | string | enum: DISMISSED, WARNING | - |
| `reason` | ㅇ | string | minLength: 1<br>maxLength: 500 | - |

<a id="schema-adminreportdetail"></a>
### `AdminReportDetail`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `report` | ㅇ | [`Report`](#schema-report) | - | [`Report`](#schema-report) |
| `reporter` | ㅇ | [`AdminReportPartyEvidence`](#schema-adminreportpartyevidence) | - | 신고자 User·Pet |
| `reported` | ㅇ | [`AdminReportPartyEvidence`](#schema-adminreportpartyevidence) | - | 피신고자 User·Pet |
| `room` | ㅇ | [`AdminReportRoomEvidence`](#schema-adminreportroomevidence) | - | 관리자 전용 방 증거 |
| `messages` | ㅇ | array<[`ChatMessage`](#schema-chatmessage)> | - | - |

<a id="schema-adminreportpartyevidence"></a>
### `AdminReportPartyEvidence`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `userId` | ㅇ | integer | format: int64 | - |
| `userPublicTag` | ㅇ | string | - | - |
| `userNickname` | ㅇ | string | - | - |
| `petId` | ㅇ | integer | format: int64 | - |
| `petPublicTag` | ㅇ | string | - | - |
| `petNickname` | ㅇ | string | - | - |

<a id="schema-adminreportroomevidence"></a>
### `AdminReportRoomEvidence`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `roomId` | ㅇ | integer | format: int64 | - |
| `status` | ㅇ | string | enum: ACTIVE, ARCHIVED | - |
| `participantPetIds` | ㅇ | array<integer> | minItems: 2, maxItems: 2 | - |
| `createdAt` | ㅇ | string | format: date-time | - |

<a id="schema-cursorpage"></a>
### `CursorPage`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `nextCursor` | ㅇ | string / null | - | - |
| `hasNext` | ㅇ | boolean | - | - |

<a id="schema-offsetpage"></a>
### `OffsetPage`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `page` | ㅇ | integer | - | - |
| `size` | ㅇ | integer | - | - |
| `totalElements` | ㅇ | integer | format: int64 | - |
| `totalPages` | ㅇ | integer | - | - |

<a id="schema-neighborhoodlistenvelope"></a>
### `NeighborhoodListEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | array<[`Neighborhood`](#schema-neighborhood)> | - | - |
| `error` | ㄴ | null | - | - |

<a id="schema-envelopeauthtokens"></a>
### `EnvelopeAuthTokens`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`AuthTokens`](#schema-authtokens) | - | [`AuthTokens`](#schema-authtokens) |
| `error` | ㄴ | null | - | - |

<a id="schema-meenvelope"></a>
### `MeEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`Me`](#schema-me) | - | [`Me`](#schema-me) |
| `error` | ㄴ | null | - | - |

<a id="schema-petenvelope"></a>
### `PetEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`Pet`](#schema-pet) | - | [`Pet`](#schema-pet) |
| `error` | ㄴ | null | - | - |

<a id="schema-petcreateresult"></a>
### `PetCreateResult`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `pet` | ㅇ | [`Pet`](#schema-pet) | - | [`Pet`](#schema-pet) |
| `activeAssignmentStatus` | ㅇ | string | enum: ASSIGNED, RETRY_REQUIRED, NOT_APPLICABLE | firstPetCandidate=true이고 자동 지정 성공이면 ASSIGNED, 후보지만 별도 지정 트랜잭션 실패면 RETRY_REQUIRED,<br>후보가 아니거나 이미 Active Pet이 있으면 NOT_APPLICABLE이다. firstPetCandidate는 외부에 노출하지 않는다.<br> |

<a id="schema-petcreateenvelope"></a>
### `PetCreateEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`PetCreateResult`](#schema-petcreateresult) | - | [`PetCreateResult`](#schema-petcreateresult) |
| `error` | ㄴ | null | - | - |

<a id="schema-petlistenvelope"></a>
### `PetListEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | array<[`Pet`](#schema-pet)> | - | - |
| `error` | ㄴ | null | - | - |

<a id="schema-petsearchenvelope"></a>
### `PetSearchEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`PetSearchItem`](#schema-petsearchitem) / null | - | - |
| `error` | ㄴ | null | - | - |

<a id="schema-petsearchlistenvelope"></a>
### `PetSearchListEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | object | - | - |
| `error` | ㄴ | null | - | - |

<a id="schema-petverificationattemptenvelope"></a>
### `PetVerificationAttemptEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`PetVerificationAttempt`](#schema-petverificationattempt) | - | [`PetVerificationAttempt`](#schema-petverificationattempt) |
| `error` | ㄴ | null | - | - |

<a id="schema-setloglistenvelope"></a>
### `SetlogListEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | array<[`Setlog`](#schema-setlog)> | minItems: 0<br>maxItems: 3 | - |
| `error` | ㄴ | null | - | - |

<a id="schema-setlogreactionenvelope"></a>
### `SetlogReactionEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`SetlogReaction`](#schema-setlogreaction) | - | [`SetlogReaction`](#schema-setlogreaction) |
| `error` | ㄴ | null | - | - |

<a id="schema-greetingenvelope"></a>
### `GreetingEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`Greeting`](#schema-greeting) | - | [`Greeting`](#schema-greeting) |
| `error` | ㄴ | null | - | - |

<a id="schema-friendrequestenvelope"></a>
### `FriendRequestEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`FriendRequest`](#schema-friendrequest) | - | [`FriendRequest`](#schema-friendrequest) |
| `error` | ㄴ | null | - | - |

<a id="schema-friendrequestlistenvelope"></a>
### `FriendRequestListEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | object | - | - |
| `error` | ㄴ | null | - | - |

<a id="schema-chatroomenvelope"></a>
### `ChatRoomEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`ChatRoom`](#schema-chatroom) | - | [`ChatRoom`](#schema-chatroom) |
| `error` | ㄴ | null | - | - |

<a id="schema-chatroomlistenvelope"></a>
### `ChatRoomListEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | object | - | - |
| `error` | ㄴ | null | - | - |

<a id="schema-chatmessageenvelope"></a>
### `ChatMessageEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`ChatMessage`](#schema-chatmessage) | - | [`ChatMessage`](#schema-chatmessage) |
| `error` | ㄴ | null | - | - |

<a id="schema-chatmessagelistenvelope"></a>
### `ChatMessageListEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | object | - | - |
| `error` | ㄴ | null | - | - |

<a id="schema-carddraftenvelope"></a>
### `CardDraftEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`CardDraft`](#schema-carddraft) | - | [`CardDraft`](#schema-carddraft) |
| `error` | ㄴ | null | - | - |

<a id="schema-meetingcardenvelope"></a>
### `MeetingCardEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`MeetingCard`](#schema-meetingcard) | - | [`MeetingCard`](#schema-meetingcard) |
| `error` | ㄴ | null | - | - |

<a id="schema-blockenvelope"></a>
### `BlockEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`Block`](#schema-block) | - | [`Block`](#schema-block) |
| `error` | ㄴ | null | - | - |

<a id="schema-blocklistenvelope"></a>
### `BlockListEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | object | - | - |
| `error` | ㄴ | null | - | - |

<a id="schema-reportenvelope"></a>
### `ReportEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`Report`](#schema-report) | - | [`Report`](#schema-report) |
| `error` | ㄴ | null | - | - |

<a id="schema-adminreportpageenvelope"></a>
### `AdminReportPageEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | object | - | - |
| `error` | ㄴ | null | - | - |

<a id="schema-adminreportdetailenvelope"></a>
### `AdminReportDetailEnvelope`

| 필드 | 필수 | 타입 | 제약 | 설명 |
|---|---:|---|---|---|
| `success` | ㅇ | boolean | const: True | - |
| `message` | ㅇ | string | - | - |
| `data` | ㅇ | [`AdminReportDetail`](#schema-adminreportdetail) | - | [`AdminReportDetail`](#schema-adminreportdetail) |
| `error` | ㄴ | null | - | - |

## 5. M1 제외 범위

- Google OAuth, 이메일 인증, 비밀번호 찾기·재설정
- 회원탈퇴·`DELETE /me`·탈퇴 Cleanup
- GPS 동네 확인
- 사용자 셋로그·Pet 프로필 미디어 업로드
- 이웃 목록, 지도·장소, 만남 확인, 후기·발자국
- 그룹 채팅, 메시지 읽음·수정·삭제·첨부
- Push·WebSocket, 욕설 자동 차단·AI 대화 검열
- 차단 해제, 카드 수정
