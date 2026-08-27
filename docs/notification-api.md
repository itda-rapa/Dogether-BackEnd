# Notification API

M2 알림은 polling만 사용한다. WebSocket·Push 전달은 이 계약에 포함하지 않는다.

## `GET /notifications`

현재 Active Pet의 최신 100개 알림을 최신순으로 반환한다. 각 항목은 `notificationId`, `type`,
`targetType`, `targetId`, `roomId`, `postId`, `setlogId`, actor snapshot(`actorPetId`,
`actorPetNickname`, `actorProfileAssetId`), `commentPreview`, `targetAvailable`, `createdAt`,
`readAt`을 포함한다.

- 게시글 반응·댓글 알림은 `postId`로 `/board/:postId`로 이동한다.
- Setlog 알림은 `setlogId`만 제공한다. route/modal 결정은 Front의 책임이다.
- Open Chat 초대는 기존 `roomId`와 `/chat/open/:roomId/room` 계약을 유지한다.
- `targetAvailable=false`는 삭제·차단·권한 변경·방 나감 등을 구분하지 않는다. Front는 비활성 표시하고
  클릭 시 안내 후 읽음 처리한다.

## `GET /notifications/unread-count`

현재 Active Pet 소유의 `readAt IS NULL` 알림 수를 반환한다.

## `PATCH /notifications/{notificationId}/read`

수신 Pet 본인의 알림만 읽음 처리한다. 대상이 더 이상 접근 불가해도 읽음 처리는 허용한다.

## Creation rules

- 게시글: `BOARD_POST_LIKE`, `BOARD_POST_HELPFUL`
- 댓글: `BOARD_COMMENT_HELPFUL`, `BOARD_COMMENT_CREATED`, `BOARD_REPLY_CREATED`
- Setlog: `SETLOG_LIKE`, `SETLOG_CUTE`
- 반응 알림은 `(actor_pet_id, target_pet_id, type, target_type, target_id)` partial unique index와
  `ON CONFLICT DO NOTHING`으로 재시도·동시 요청·취소 후 재반응에도 한 건만 남긴다.
- 자기 행동은 저장하지 않는다. Root 댓글은 게시글 작성자, 대댓글은 직접 부모 댓글 작성자만 수신자다.
