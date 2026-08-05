# M2 WebSocket 채팅 계약

상태: **구현 전 검토용 초안**
범위: M2 DIRECT 실시간 채팅 기반 계약 및 BE-4 그룹 채팅 연계 경계

이 문서는 BE-2, BE-4, 프론트가 WebSocket 구현 전에 공유하는 계약이다. REST·DB 정본을 대체하지 않으며, 구현 중 변경은 이 문서와 함께 리뷰한다.

## 1. 범위와 전제

- M2에서 WebSocket/STOMP를 도입한다.
- DIRECT 메시지는 기존 REST 폴링과 병행한다. 연결 실패·끊김 시 프론트는 REST로 복구한다.
- M2 SimpleBroker는 `replicas=1`을 전제로 한다. sticky session만으로 다중 인스턴스 문제가 해결되지 않는다.
- 다중 replica가 필요해지면 외부 broker relay, Kafka 또는 Redis 방식을 BE-2와 BE-4가 먼저 확정한다.
- SockJS, DIRECT Kafka, Outbox, 읽음 표시, 타이핑, 온라인 상태, 메시지 수정·삭제, 미디어 메시지는 이 계약 범위에 없다.

## 2. STOMP destination

| 목적 | Destination |
|---|---|
| WebSocket handshake | `/ws` |
| 클라이언트 → 서버 prefix | `/app` |
| 개인 큐 prefix | `/user` |
| 서버 broker prefix | `/queue`, `/topic` |
| DIRECT 전송 | `/app/chat/direct/rooms/{roomId}/messages` |
| DIRECT 메시지 수신 | `/user/queue/chat/messages` |
| 전송 ACK·메시지 이벤트 수신 | `/user/queue/chat/messages` |
| 오류 수신 | `/user/queue/errors` |
| 향후 GROUP 전송 | `/app/chat/group/rooms/{roomId}/messages` |

GROUP destination은 BE-4가 구현하되, 공통 인증·Principal·이벤트 형식·오류 큐를 별도로 복제하지 않는다.

## 3. 인증·세션

1. HTTP handshake 경로 `/ws`는 `SecurityConfig`에서 연결을 허용한다.
2. 인증은 STOMP `CONNECT`의 `Authorization: Bearer <access-token>` native header에서 수행한다.
3. `Principal.getName()`은 **User ID의 문자열 표현**으로 고정한다. 이메일을 사용하지 않는다.
4. CONNECT에서 JWT 서명·만료·사용자 활성 상태를 확인한다. 실패하면 STOMP ERROR를 보내고 연결을 종료한다.
5. SEND마다 토큰 만료와 사용자 활성 상태를 재검사한다. 정지 또는 만료 세션은 `UNAUTHORIZED` 오류 후 종료한다.
6. 로그아웃 이후에도 기존 WebSocket 세션은 토큰 만료 또는 다음 SEND 재검사 전까지 서버가 즉시 회수하지 못할 수 있다. Access Token TTL이 상한이다.
7. Origin은 기존 CORS 설정(`CorsProperties`)을 사용한다. 임의의 `*` 허용을 추가하지 않는다.

## 4. DIRECT SEND 요청

```json
{
  "clientMessageId": "client-generated-id",
  "body": "메시지 내용"
}
```

- 사용자 입력은 `TEXT`만 허용한다. `CARD`, `SYSTEM`은 서버가 발행한다.
- `clientMessageId`는 REST와 WebSocket에서 같은 멱등성 키로 사용한다.
- WebSocket 컨트롤러는 `ChatMessageService.sendText()`를 직접 호출하지 않는다.
- 반드시 `ChatQueryService.sendMessage(userId, roomId, request)`를 호출한다.
- 이 경로를 통해 Active Pet, 참여자, 차단, 인사 답변 게이트, 멱등성, ARCHIVED 방 복구를 동일하게 적용한다.
- 친구 여부는 채팅 시작 게이트가 아니다.

## 5. 저장과 실시간 발행

- 저장은 기존 도메인 서비스의 트랜잭션을 사용한다.
- 메시지 저장 트랜잭션 안에서 완성된 immutable `ChatMessageResponse` DTO를 만든다.
- `AFTER_COMMIT` 이벤트에는 JPA 엔티티를 싣지 않는다. 커밋 이후 lazy 연관 접근의 동작이 보장되지 않기 때문이다.
- `ChatMessageService`의 공통 insert 경로에서 이벤트를 발행한다. 따라서 REST TEXT, WebSocket TEXT, 인사 TEXT, CARD, SYSTEM 메시지가 동일한 실시간 발행 경로를 사용한다.
- 이벤트 발행 실패가 이미 커밋된 DB 저장을 실패로 바꾸면 안 된다. 실패는 로그로 남기고 REST 폴링으로 복구한다.
- 신규 메시지(`created=true`)만 대화 이벤트를 발행한다. 멱등 재전송(`created=false`)은 대화 이벤트를 다시 발행하지 않는다.

## 6. 이벤트 계약

### 6.1 전송 ACK

ACK는 요청을 처리한 WebSocket 세션에만 같은 메시지 큐로 보낸다. ACK와 신규 메시지 이벤트는 별도 destination을 만들지 않고 `eventType`으로 구분한다.

```json
{
  "eventType": "CHAT_SEND_ACK",
  "roomId": 123,
  "messageId": 456,
  "clientMessageId": "client-generated-id",
  "replayed": false
}
```

- 신규 저장이면 `replayed=false`, 동일 멱등 요청 재시도면 `replayed=true`다.
- ACK는 대화 타임라인 메시지가 아니다.
- 같은 User의 다른 탭·세션도 개인 큐를 수신할 수 있으므로, 프론트는 자신이 보낸 `clientMessageId`와 연결되지 않은 ACK를 무시한다.

### 6.2 신규 메시지 이벤트

```json
{
  "eventType": "CHAT_MESSAGE_CREATED",
  "messageId": 456,
  "roomId": 123,
  "roomType": "DIRECT",
  "senderType": "PET",
  "senderPetId": 11,
  "type": "TEXT",
  "body": "메시지 내용",
  "meetingCardId": null,
  "clientMessageId": "client-generated-id",
  "createdAt": "2026-08-05T12:00:00Z"
}
```

- `CHAT_MESSAGE_CREATED`에는 `replayed` 필드를 넣지 않는다.
- 신규 메시지만 각 참여자의 현재 활성 Pet 소유 User 개인 큐에 발행한다.
- 송신자도 자신의 메시지 이벤트를 받는다. ACK와 메시지 이벤트는 별개의 사실이며 도착 순서는 보장하지 않는다.
- 프론트는 ACK와 메시지 이벤트를 서로 다른 이벤트로 처리한다.

### 6.3 수신자 조회

`roomId → participant pet → pet.owner user → users.active_pet_id가 participant pet과 일치`하는 User를 수신자로 계산한다.

- 현재 활성 Pet을 선택하지 않은 User는 실시간 수신 대상이 아니다.
- 커밋 후 활성 Pet을 변경하는 경합으로 이벤트를 놓칠 수 있다. 이는 허용된 레이스이며 REST 폴링으로 복구한다.

## 7. 오류 계약

- 오류는 `/user/queue/errors`로 보낸다.
- 오류 코드는 REST와 동일한 `ErrorCode`를 사용한다. WebSocket 전용 오류 코드를 새로 만들지 않는다.
- 일반적인 검증·권한·도메인 오류는 오류 이벤트 후 연결을 유지한다.
- CONNECT 실패, 토큰 만료, 정지 사용자 SEND는 `UNAUTHORIZED` 후 세션을 종료한다.
- WebSocket payload 검증 실패는 `VALIDATION_FAILED`로 매핑한다. `@MessageExceptionHandler`에서 raw validation exception을 노출하지 않는다.

## 8. 프론트 복구·heartbeat

- SimpleBroker에 TaskScheduler를 설정하고 heartbeat를 `10000ms / 10000ms`로 사용한다.
- 연결 실패·heartbeat timeout·세션 종료 시 지수 백오프로 재연결한다.
- 재연결 전후 누락 메시지는 `GET /chat/rooms/{roomId}/messages?afterMessageId={lastMessageId}`로 보충한다.
- WebSocket 전송이 실패하면 기존 REST `POST /chat/rooms/{roomId}/messages`로 fallback한다.

## 9. BE-2 / BE-4 책임 경계

| 담당 | 책임 |
|---|---|
| BE-2 | 공통 WebSocket 설정, CONNECT 인증, Principal, ErrorCode 매핑, 개인 큐 발행, DIRECT controller/adapter, immutable DTO 이벤트 |
| BE-4 | GROUP 전송 경로, 그룹 멤버십·권한, Kafka partition key를 `roomId`로 하는 그룹 이벤트 연계 |
| 공통 | 기존 도메인 서비스 경유, 동일한 오류·이벤트 계약, 프론트 destination 공유 |

BE-4는 `SecurityConfig`, JWT interceptor, `Principal` 규칙, 공통 publisher, `/user/queue/errors`를 복제하지 않는다. GROUP의 저장·Kafka consumer 이후에도 최종 사용자 전달 형식은 이 문서의 개인 큐·오류 계약과 맞춘다.

## 10. 수용 기준

- CONNECT에 유효한 Bearer token이 없으면 연결이 거부된다.
- 정지 사용자와 만료 토큰은 SEND마다 차단되고 세션이 종료된다.
- 비참여자·차단 관계·Active Pet 없음은 REST와 같은 오류 의미로 처리된다.
- 새 메시지는 ACK 1건과 신규 메시지 이벤트 1건으로 분리된다.
- 멱등 재전송은 ACK만 보내고 신규 메시지 이벤트는 보내지 않는다.
- REST에서 저장된 TEXT/CARD/SYSTEM도 동일한 신규 메시지 이벤트를 발행한다.
- `AFTER_COMMIT` 이벤트에 JPA 엔티티가 아닌 DTO가 들어간다.
- WebSocket이 끊겨도 REST 폴링으로 누락 메시지를 복구할 수 있다.
- 단일 인스턴스 전제와 다중 replica 전환 조건이 배포 문서에 전달된다.

## 11. 구현 제외

- SockJS/STOMP 외 별도 transport
- DIRECT 메시지의 Kafka 경유
- Outbox 도입
- Read receipt, typing indicator, online presence
- 메시지 수정·삭제
- 이미지·파일 메시지
- 다중 replica WebSocket 라우팅
