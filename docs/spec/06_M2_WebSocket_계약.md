# M2 WebSocket 채팅 계약

상태: **계약 갱신본** — PR #67 구현안을 기준으로 정합화했으며, dev 반영은 #67 병합 후 완료된다.
범위: M2 DIRECT 실시간 채팅 기반 계약 및 BE-4 그룹 채팅 연계 경계

이 문서는 BE-2, BE-4, 프론트가 WebSocket 구현 전·후에 공유하는 계약이다. REST·DB 정본을 대체하지 않으며, 구현 중 변경은 이 문서와 함께 리뷰한다.

> **읽는 법.** 본문에는 두 종류가 섞여 있다. 하나는 **계약 규칙**(destination, 이벤트 형식, 오류, 수신자 조건 등)으로 구현이 따라야 할 대상이다. 다른 하나는 **저장소 현황 스냅샷**(`dev` `ec16ecd` 기준)으로, 구현자가 잘못된 전제를 두지 않도록 적어둔 관찰이다. 아래 다섯 곳이 현황이며 저장소가 고쳐지면 낡는다 — §7의 OpenAPI `BLOCKED_USER` 경고, §7.1(`canSend`·`sendBlockedReason`), §7.2(`findRoomById`), §10의 검증 환경 주의, §10의 배포 문서 부재. (`/ws`의 `permitAll` 등록은 PR #67에서 해소되어 §3 항목 1을 계약 요구로 바꿨다. 남은 다섯 곳은 `ec16ecd` 시점 관찰이며 최신 `dev` 기준 재확인이 필요하다.) **해당 항목이 해소되면 이 문서에서 지우고 그 사실만 남긴다.**

## 1. 범위와 전제

- M2에서 WebSocket/STOMP를 도입한다.
- **DIRECT 메시지는 읽기·쓰기 모두 REST와 WebSocket 두 경로가 함께 열려 있다.** 쓰기는 REST `POST /chat/rooms/{roomId}/messages`와 WebSocket SEND가 같은 `ChatQueryService.sendMessage`를 타고, 읽기는 REST 폴링과 실시간 이벤트가 같은 저장 결과를 본다. 연결 실패·끊김 시 프론트는 REST로 복구한다.
- 쓰기가 두 경로인 만큼 **중복 저장을 막는 것은 `clientMessageId` 하나뿐이다.** WebSocket 전송이 실패해 REST로 fallback할 때는 반드시 같은 키를 쓴다(§8). 키를 새로 만들면 두 건이 저장된다.
- **방 생성은 이 계약의 범위가 아니다.** WebSocket으로 방을 만들 수 없다 — §11 참고.
- M2 SimpleBroker는 `replicas=1`을 전제로 한다. sticky session만으로 다중 인스턴스 문제가 해결되지 않는다.
- 다중 replica가 필요해지면 외부 broker relay, Kafka 또는 Redis 방식을 BE-2와 BE-4가 먼저 확정한다. **`RedisConfig`(이메일 인증·캐시·분산락·멱등성 4개 DB 전용)와 `KafkaProducerConfig`(공용 `KafkaTemplate<String, Object>`)는 이미 저장소에 있지만 둘 다 WebSocket broker relay 용도로 만든 것이 아니다**(`07_M2_마일스톤_WBS.md` `M2-008`). `deployment/local/docker-compose.yml`의 Redis·Kafka도 로컬 단일 노드 구성이라 그 자체가 다중 replica 해결책은 아니다. 다중 replica 전환은 이 문서가 별도로 다루지 않은 새 구성 요소가 필요하다.
- SockJS, DIRECT Kafka, Outbox, 읽음 표시, 타이핑, 온라인 상태, 메시지 수정·삭제, 미디어 메시지는 이 계약 범위에 없다.
- `ChatRoomLifecycleScheduler`(기본 60초 주기, `app.chat-room.lifecycle.*`)는 무응답 인사 방을 물리 삭제하되 **네 가지 예외가 있다** — 신고 이력이 있는 방(`ReportRepository.existsByRoomId`), 이미 답변된 인사, 다른 인사가 아직 대기 중인 방, 친구 관계인 방은 삭제하지 않는다. "무응답이면 반드시 사라진다"고 전제하지 않는다. 또한 **친구가 아닌** 30일 무활동 방을 `ARCHIVED`로 전환한다. 친구 관계인 방은 보관 대상이 아니다 — `03_M1_상태전이.md`의 "답변한 비친구 방 30일 무활동"과 `ChatRoomLifecycleTransactionService.archiveIfEligible`의 친구 관계 검사가 정본이다. 이 배치는 `ChatMessageService`의 공통 insert 경로를 거치지 않으므로 실시간 이벤트를 발행하지 않는다 — §5, §10 참고.

## 2. STOMP destination

| 목적 | Destination |
|---|---|
| WebSocket handshake | `/ws` |
| 클라이언트 → 서버 prefix | `/app` |
| 개인 큐 prefix | `/user` |
| 서버 broker prefix | `/queue`, `/topic` |
| DIRECT 전송 | `/app/chat/direct/rooms/{roomId}/messages` |
| DIRECT 메시지 수신·전송 ACK | `/user/queue/chat/messages` |
| 오류 수신 | `/user/queue/errors` |
| 향후 GROUP 전송 | `/app/chat/group/rooms/{roomId}/messages` |

`/user/queue/chat/messages` 하나에 두 종류가 흐른다. destination을 나누지 않고 `eventType`으로 구분하며, 전달 범위가 다르다.

- `CHAT_SEND_ACK` — 요청을 보낸 그 세션에만
- `CHAT_MESSAGE_CREATED` — 해당 User의 모든 구독 세션에

**프론트는 CONNECT 직후 두 곳을 모두 구독한다** — `/user/queue/chat/messages`와 `/user/queue/errors`. 오류 큐를 구독하지 않으면 SEND 실패가 아무 신호 없이 사라지고, 낙관적으로 그린 메시지가 영영 남는다. 방을 열 때가 아니라 연결 직후에 구독한다. destination이 방별로 나뉘지 않고 User 단위이기 때문이다.

### 2.1 destination 인가 — allowlist + 기본 거부

**`@EnableWebSocketSecurity`를 쓰지 않기로 했으므로(§3 항목 4) destination 인가가 공짜로 따라오지 않는다.** 인증만 하고 인가를 비워 두면, 악성 클라이언트가 `/app/...` 컨트롤러를 거치지 않고 broker destination에 직접 SEND하거나 허용하지 않은 destination을 SUBSCRIBE할 수 있다. **인바운드 `ChannelInterceptor`에서 프레임 종류별로 허용 목록을 검사하고 나머지는 거부한다.**

인터셉터는 **`StompHeaderAccessor.getCommand()`(`StompCommand`)** 로 분기한다. `getCommand()`가 `null`인 인바운드 메시지에 한해 heartbeat 여부를 따로 본다. **기본은 거부이고 아래 목록만 허용한다.**

| 프레임 | 처리 |
|---|---|
| `CONNECT`, `STOMP` | JWT 인증(§3). 성공 시 access token `exp`에 세션 종료를 예약한다. 실패 시 거부 |
| `SEND` | destination이 `/app/chat/direct/rooms/{roomId}/messages`이고 `{roomId}`가 `long`으로 파싱될 때만 허용. 인증 세션 필요. **`/queue/**`·`/topic/**`·`/user/**` 직접 SEND는 거부** |
| `SUBSCRIBE` | `/user/queue/chat/messages`, `/user/queue/errors` 만 허용. 인증 세션 필요 |
| `UNSUBSCRIBE` | 인증된 세션이면 허용 |
| `DISCONNECT` | 예약 종료를 정리한 뒤 허용 |
| heartbeat (`getCommand()`가 `null`이며 heartbeat) | 허용 |
| 그 밖의 command | 거부 |
| `getCommand()`가 `null`인 비-heartbeat 인바운드 | 거부 |

**`roomId`는 정규식만으로 부족하다.** 허용 패턴이 `\d+`라 자릿수 제한이 없어 `long` 범위를 넘는 값도 정규식은 통과한다. 이는 권한이 뚫리는 문제가 아니라 — 그런 방은 존재하지 않는다 — **비정상 destination을 인가 단계에서 조기에 거부**하는 문제다. 파싱까지 성공해야 허용한다.

**`UNSUBSCRIBE`·`DISCONNECT`·heartbeat는 명시적 허용 예외다.** 이 셋까지 거부하면 정상 종료와 구독 해제, heartbeat가 끊기므로 제어 프레임이라는 이유로 막지 않는다. `DISCONNECT`는 클라이언트가 보내기도 하고 연결 종료 과정에서 서버가 만들기도 한다. **예외는 여기까지이며 그 밖의 command는 기본 거부를 따른다** — 위 표에 없는 커맨드와 클라이언트 `ACK`/`NACK`는 destination과 무관하게 거부된다.

- `/user/**`는 Spring이 세션별로 실제 destination을 풀어 주므로 남의 큐를 구독할 수는 없지만, **`/topic/**`·`/queue/**` 직접 구독은 막지 않으면 그대로 열린다.**
- GROUP이 `/topic`을 쓰기 시작하면 이 구멍이 커진다. BE-4는 GROUP destination을 추가할 때 **이 표에 행을 더하는 방식**으로 넓히고, 기본 거부 원칙을 유지한다.
- `M2-012`가 `/user/queue/card-suggestions`를 추가하면 SUBSCRIBE 허용 목록에 함께 넣는다.

**여기서의 거부는 §7의 `CHAT_ERROR`가 아니라 STOMP `ERROR` 프레임이다.** 이유는 §7 머리의 경로 구분을 참고한다.

GROUP destination은 BE-4가 구현하되, 공통 인증·Principal·이벤트 형식·오류 큐를 별도로 복제하지 않는다.

## 3. 인증·세션

1. HTTP handshake 경로 `/ws`를 `SecurityConfig`의 `permitAll` 목록에 둔다. 나머지는 `.anyRequest().authenticated()`이므로 등록하지 않으면 handshake가 `401 UNAUTHORIZED`로 끊긴다.
   - **handshake를 열어 두는 것이 인증 면제를 뜻하지는 않는다.** 브라우저 WebSocket API가 handshake 요청에 임의 헤더를 붙이지 못하므로 인증 시점을 STOMP 프레임 단계로 옮긴 것이며, 검증은 항목 2·4가 담당한다. 토큰을 쿼리 파라미터로 넘기는 우회는 쓰지 않는다 — 접근 로그와 Referer에 남는다.
2. 인증은 STOMP `CONNECT` **또는 `STOMP`** 프레임의 `Authorization: Bearer <access-token>` native header에서 수행한다. 표준 STOMP는 두 커맨드를 모두 연결 개시로 규정하므로 양쪽을 같게 다룬다.
   - 한쪽만 처리해도 **무인증 통과가 되지는 않는다.** §2.1의 기본 거부가 미처리 커맨드를 거부하기 때문이다. 양쪽을 다루는 이유는 구멍을 막기 위해서가 아니라, 표준을 따르는 클라이언트가 `STOMP` 커맨드로 접속했을 때 정상 연결이 거부되지 않게 하기 위해서다.
3. `Principal.getName()`은 **User ID의 문자열 표현**으로 고정한다. 이메일을 사용하지 않는다.
   - **WebSocket은 HTTP `CurrentUser`를 Principal로 재사용하지 않는다.** `CurrentUser.getUsername()`은 email을 반환하므로 그대로 쓰면 이 규칙이 깨진다. `Principal.getName()`이 userId 문자열을 반환하는 WebSocket 전용 Principal 어댑터를 사용한다.
4. `CONNECT`·`STOMP` 프레임에서 JWT 서명·만료·사용자 활성 상태를 확인한다. **실패하면 STOMP `ERROR` 프레임을 보내고 연결을 종료한다.** 이 시점에는 `/user/queue/errors` 구독이 성립하지 않으므로 §7의 `CHAT_ERROR`를 큐로 보낼 수 없다 — 전송 수단이 다르다.
   - **Spring Security의 WebSocket 메시지 보안(`@EnableWebSocketSecurity`)은 쓰지 않는다.** 그 기능은 `spring-security-messaging` 아티팩트에 있는데 `spring-boot-starter-security`가 끌어오지 않아 현재 runtimeClasspath에 없다(`config`·`core`·`crypto`·`web`뿐). 도입하면 inbound `CONNECT`에 CSRF 토큰을 요구하게 되어 이 문서의 Bearer 단독 설계와 충돌한다. 인가는 이 문서의 인터셉터가 담당하고, 의존성을 추가하지 않는 것이 결정이다.
5. CONNECT 검증에 성공하면 **User ID와 access token 만료 시각(`exp`)을 STOMP 세션 attribute에 저장한다.** 이후 프레임은 이 값을 읽는다.
   - **HttpSession을 쓰지 않는다.** `SecurityConfig`가 `SessionCreationPolicy.STATELESS`라 handshake에서 HttpSession이 만들어지지 않는다. `HttpSessionHandshakeInterceptor`로 세션 속성을 넘기는 흔한 예제는 이 프로젝트에서 동작하지 않으며, 그래서 인증 시점을 handshake가 아니라 STOMP `CONNECT` 프레임으로 잡은 것이다.
6. SEND마다 토큰 만료와 사용자 활성 상태를 재검사한다. 결과는 두 축으로 갈린다.
   - **토큰 만료·정지 계정 → `UNAUTHORIZED`, 세션 종료.** 전달은 CONNECT 실패(항목 4)와 **같은 STOMP `ERROR` 프레임**이며 `/user/queue/errors`의 `CHAT_ERROR`를 쓰지 않는다. 이 거부는 `ChannelInterceptor`에서 일어나 `@MessageMapping` 컨트롤러에 도달하지 않으므로 `@MessageExceptionHandler` 흐름을 타지 않는다. 큐로 보내려면 별도 전송 경로를 새로 만들어야 하며, 그렇게 하지 않는다. REST에서도 정지 사용자는 `JwtFilter`가 `user.isActive()`에서 걸러 Authentication을 만들지 않고, `SecurityConfig`의 `authenticationEntryPoint`가 `UNAUTHORIZED`로 응답한다 — 도메인 서비스에 진입조차 하지 않는다. WebSocket SEND 재검사도 같은 코드를 쓰며, 이는 §7의 "REST와 동일한 `ErrorCode`" 원칙과 어긋나지 않는다.
   - **활성 계정인데 Active Pet만 없음 → `ACTIVE_PET_REQUIRED`, 세션 유지.** 이쪽은 인증이 아니라 도메인 게이트(`ChatQueryService.sendMessage` → `requireActivePet`)에서 나오는 오류다.
   - `ACCOUNT_NOT_ACTIVE`는 현재 채팅 REST 경로에서 사용하지 않는다. WebSocket도 쓰지 않는다.
   - 만료 검사는 5의 세션 attribute만 읽는다. **SEND 프레임에 `Authorization` 헤더를 요구하지 않는다** — STOMP는 임의 헤더를 허용하므로 클라이언트가 붙일 수는 있으나, 서버는 이를 신뢰하지 않고 무시한다. 세션 인증 상태를 프레임 단위로 갱신할 수 있게 두면 CONNECT 검증이 무의미해진다.
   - 사용자 활성 상태 재검사는 메시지마다 조회가 발생한다. 캐시를 도입한다면 정지 반영 지연이 곧 캐시 TTL이 되므로, 도입 시 그 지연을 이 문서에 명시한다.
7. **CONNECT 성공 시 `exp` 시각에 해당 세션을 닫는 작업을 예약한다.** SEND 재검사만으로는 부족하다 — 수신만 하고 한 번도 SEND하지 않는 세션은 토큰이 만료돼도 계속 살아 있어서 "Access Token TTL이 상한"이 성립하지 않는다. 예약 종료가 상한을 실제로 강제하고, 항목 6의 SEND 재검사는 그 위의 이중 방어다. 예약 작업이 실제로 소켓을 닫으려면 `sessionId`로 닫을 수 있는 핸들이 필요하다 — `ScheduledFuture`는 실행 시점만 알려줄 뿐이므로, `WebSocketHandlerDecorator` 등으로 **`sessionId → 세션 핸들` 레지스트리**를 함께 둔다. **세션이 `exp` 전에 끝나면 예약 작업을 취소한다** — `sessionId → ScheduledFuture`를 들고 있다가 `DISCONNECT`·세션 종료 시 정리하며, `DISCONNECT`는 한 세션에서 두 번 이상 관찰될 수 있으므로 **정리는 멱등이어야 한다.** 이 종료는 STOMP `ERROR` 프레임 없이 WebSocket close만으로 끝난다. **프론트가 원인을 판정하는 1차 기준은 직전 `ERROR` 프레임의 유무다.** 프레임이 있었으면 프로토콜·인증·destination 거부 경로다. **`ERROR` 없이 닫히는 경우는 예약 만료 하나로 단정할 수 없다** — heartbeat timeout과 네트워크 실패도 같은 모습이다(§8). 프론트는 access token `exp`와 현재 시각을 비교해 만료 여부를 확인한 뒤 처리한다. close code는 보조 신호로만 쓴다. 현재 구현의 측정값은 예약 만료 종료가 `1008`(`POLICY_VIOLATION`), destination 위반과 malformed 프레임이 `1002`(`PROTOCOL_ERROR`)이며 그 세 경우만 회귀 테스트로 고정돼 있다. **관측된 구현 세부이지 클라이언트가 의존해도 되는 계약값이 아니다.**
8. 로그아웃·정지 이후에도 기존 세션은 서버가 즉시 회수하지 못할 수 있다. **정지 계정을 즉시 추방하는 기능은 이 계약 범위가 아니며, 상한은 항목 7의 예약 종료다.**
9. Origin은 기존 `CorsProperties`의 `app.cors.allowed-origins` **값을 그대로 재사용**한다. 임의의 `*` 허용을 추가하지 않는다.
   - **검사가 두 겹이다.** handshake는 HTTP 요청이라 `SecurityConfig`의 `CorsConfigurationSource`(`/**` 등록)를 그대로 지난다. 그런데 Spring WebSocket이 그와 **독립적인 origin 검사**를 하나 더 한다 — `OriginHandshakeInterceptor`가 엔드포인트 등록의 `setAllowedOrigins`/`setAllowedOriginPatterns` 값으로 판정한다. Security CORS를 통과해도 이쪽에서 막힐 수 있으므로, **엔드포인트 등록에도 같은 `app.cors.allowed-origins` 값을 넣는다.** 두 곳에 다른 목록이 생기지 않게 한다.
10. SimpleBroker에 TaskScheduler를 설정하고 heartbeat를 `10000ms / 10000ms`로 사용한다. 서버 설정이며, 이에 대응하는 프론트 재연결 규칙은 §8에 있다.

## 4. DIRECT SEND 요청

```json
{
  "clientMessageId": "client-generated-id",
  "body": "메시지 내용"
}
```

- 사용자 입력은 `TEXT`만 허용한다. `CARD`, `SYSTEM`은 서버가 발행한다. 페이로드에 `type` 필드 자체가 없다 — `ChatMessageCreateRequest`는 위 두 필드뿐이며, 방은 destination에서, 발신 Pet은 인증된 Active Pet에서 온다.
- **모르는 필드는 REST와 똑같이 무시한다.** REST는 본문에 `senderPetId`를 넣어도 무시하고 Active Pet을 발신자로 쓴다(`ChatApiContractPostgreSqlIntegrationTest.senderPetIdInBodyIsIgnored`가 보장). WebSocket도 Boot 자동 구성 덕분에 기본적으로 같지만, 메시지 컨버터를 직접 갈아끼우면 깨진다 — §6.2 참고.
- **제약은 REST와 동일하다** — `clientMessageId`는 `@NotBlank @Size(max = 64)`, `body`는 `@NotBlank @Size(max = 2000)`. 위반은 §7의 `VALIDATION_FAILED`다. 프론트가 전송 전에 같은 값으로 막는다.
- `clientMessageId`는 REST와 WebSocket에서 같은 멱등성 키로 사용한다. **유일성 범위는 방 단위**다 — 제약이 `uk_chat_message_client UNIQUE (room_id, client_message_id)`이므로 다른 방에서는 같은 키를 다시 써도 충돌하지 않는다. 그래도 클라이언트는 전역 유일값(UUID 등)을 쓰는 편이 안전하다.
- WebSocket 컨트롤러는 `ChatMessageService`의 어떤 메서드도 직접 호출하지 않는다. `sendText()`는 물론 `sendGreetingText()`·`postCard()`·`postSystem()`도 마찬가지다. 특히 `sendGreetingText()`는 인사 답변 게이트를 **의도적으로 우회**하는 서버 전용 경로다.
- 반드시 `ChatQueryService.sendMessage(userId, roomId, request)`를 호출한다.
- 이 경로를 통해 Active Pet, 참여자, 차단, 인사 답변 게이트, 멱등성, ARCHIVED 방 복구를 동일하게 적용한다.
- 친구 여부는 채팅 시작 게이트가 아니다.
- `ChatRoomLifecycleScheduler`가 무응답 인사 방을 이미 물리 삭제했다면 `ChatQueryService.sendMessage`는 REST와 동일하게 `404 CHAT_ROOM_NOT_FOUND`를 반환한다. WebSocket 전용 오류 코드나 강제 세션 종료를 추가하지 않는다 — §7의 일반 오류 계약을 그대로 따른다.
- 방이 삭제·보관되는 시점에 이미 그 방을 구독 중인 세션이 있어도 서버가 별도로 알리지 않는다. 다음 SEND 시도에서만 오류로 드러난다.
- **특정 방의 소멸 여부는 `GET /chat/rooms/{roomId}`로 확인한다.** 방 목록(`GET /chat/rooms`)은 `COALESCE(last_message_at, created_at) DESC, id DESC` 정렬의 커서 페이지네이션이라, 삭제된 방은 그냥 목록에서 빠질 뿐이고 첫 페이지만 봐서는 특정 방의 소멸을 판정할 수 없다. 단건 조회가 `404 CHAT_ROOM_NOT_FOUND`면 소멸이다.

## 5. 저장과 실시간 발행

- 저장은 기존 도메인 서비스의 트랜잭션을 사용한다.
- 메시지 저장 트랜잭션 안에서 완성된 immutable DTO를 만든다.
- `AFTER_COMMIT` 이벤트에는 JPA 엔티티를 싣지 않는다. 커밋 이후 lazy 연관 접근의 동작이 보장되지 않기 때문이다.
- **`roomType`은 기존 `ChatMessageResponse`에 없는 필드다.** 현재 `ChatMessageResponse`는 `messageId, roomId, senderType, senderPetId, type, body, meetingCardId, clientMessageId, createdAt` 9개뿐이다. `roomType`을 얻으려면 `message.getRoom().getType()`을 읽어야 하고, 이는 위 항목이 금지한 커밋 이후 lazy 접근에 정확히 해당한다. 따라서 **트랜잭션 안에서 room type을 읽어 이벤트 봉투에 채운다** — §6.2 참고.
- `ChatMessageService`의 공통 insert 경로에서 이벤트를 발행한다. 따라서 REST TEXT, WebSocket TEXT, 인사 TEXT, CARD, SYSTEM 메시지가 동일한 실시간 발행 경로를 사용한다.
- 이벤트 발행 실패가 이미 커밋된 DB 저장을 실패로 바꾸면 안 된다. 실패는 로그로 남기고 REST 폴링으로 복구한다.
- 신규 메시지(`created=true`)만 대화 이벤트를 발행한다. 멱등 재전송(`created=false`)은 대화 이벤트를 다시 발행하지 않는다.
- `ChatRoomLifecycleTransactionService`의 인사 만료·방 삭제·`ARCHIVED` 전환은 이 insert 경로를 거치지 않는다. 방 소멸·보관은 실시간 이벤트로 알리지 않으며, 프론트는 REST 폴링으로만 반영을 확인한다(§4의 단건 조회 기준).
- **위 항목은 채팅방 수명주기 배치에 한정한 규칙이다.** "배치는 실시간 이벤트를 발행하지 않는다"를 모든 배치에 일반화하지 않는다. 이 문서가 다루는 것은 `chat_messages` 저장에 따르는 실시간 발행이며, 채팅 메시지가 아닌 별도 리소스의 실시간 이벤트는 각 기능의 계약에서 정한다.

## 6. 이벤트 계약

**모든 WebSocket 이벤트는 평면 JSON이며 REST의 `ApiResponse` 봉투를 쓰지 않는다.** REST는 `{success, message, data, error}`로 감싸지만 WebSocket 이벤트에는 그 네 필드가 없고 `eventType`으로 종류를 구분한다. 필드 이름·타입이 REST DTO와 같다고 해서 봉투까지 같다고 읽으면 안 된다.

### 6.1 전송 ACK

ACK는 **요청을 보낸 STOMP 세션에만** 전송한다. handler는 `@SendToUser(destinations = "/queue/chat/messages", broadcast = false)` 또는 동등한 세션 한정 방식을 사용한다. ACK와 신규 메시지 이벤트는 별도 destination을 만들지 않고 `eventType`으로 구분한다.

```json
{
  "eventType": "CHAT_SEND_ACK",
  "roomId": 123,
  "messageId": 456,
  "clientMessageId": "client-generated-id",
  "replayed": false
}
```

- 신규 저장이면 `replayed=false`, **같은 `clientMessageId` + 같은 페이로드** 재시도면 `replayed=true`다.
- **같은 `clientMessageId`에 다른 페이로드를 보내면 ACK가 아니라 오류다.** `ChatMessageService.requireSamePayload`가 `CHAT_DUPLICATE_MESSAGE`(409)를 던진다. 프론트가 키를 재사용하거나, 전송 실패 후 본문을 고쳐 같은 키로 다시 보내면 여기에 걸린다. 재전송은 원문 그대로여야 하고, 내용을 바꿨다면 새 `clientMessageId`를 쓴다.
- ACK는 대화 타임라인 메시지가 아니다.
- 세션 한정이므로 같은 User의 다른 탭은 이 ACK를 받지 않는다. 다른 탭은 `CHAT_MESSAGE_CREATED`로만 새 메시지를 알게 된다(§6.2).
- **동시에 같은 키로 두 건이 도착해도 `replayed=false`는 정확히 하나다.** 판정은 애플리케이션이 아니라 `ON CONFLICT (room_id, client_message_id)` upsert의 `xmax = 0`이 한다(`ChatMessageRepository.insertMessageOnConflictWithReturning`). 기존 `ChatConcurrencyPostgreSqlIntegrationTest.concurrentSendsWithOneKeyStoreOneMessageAndOnlyOneReportsCreated`가 `ChatMessageService.sendText`를 여러 스레드로 동시에 불러 이를 보장한다. **서비스 계층 검증이므로 REST·WebSocket 어느 진입점이든 그대로 성립한다.**

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

**`createdAt` 직렬화는 기본적으로 REST와 같다.** `createdAt`은 `Instant`이고 위 예시는 ISO-8601 문자열이다. Spring Boot 4.1의 `WebSocketMessagingAutoConfiguration`(`spring-boot-websocket` 모듈)이 애플리케이션의 `JsonMapper`를 STOMP 메시지 컨버터에 주입하므로, 별도 배선 없이 REST와 같은 직렬화 설정이 적용된다. 모르는 필드 무시(`FAIL_ON_UNKNOWN_PROPERTIES=false`)도 마찬가지다.

**깨뜨리지만 않으면 된다.** `WebSocketMessageBrokerConfigurer.configureMessageConverters(List<MessageConverter>)`를 오버라이드해 컨버터 목록을 비우거나 자체 JSON 컨버터를 앞에 끼워 넣으면, Boot가 연결해 둔 `JsonMapper`가 아닌 다른 매퍼가 쓰일 수 있다. 그때부터 `createdAt`이 epoch 숫자로 나가거나 모르는 필드에서 실패한다. 컨버터를 손봐야 한다면 애플리케이션 `JsonMapper`를 명시적으로 다시 넣는다. 이 프로젝트는 **Jackson 3**(`tools.jackson`)이므로 Jackson 2 시절의 `MappingJackson2*` 예제를 그대로 옮기지 않는다.

이 payload는 REST의 `ChatMessageResponse`를 그대로 직렬화한 것이 **아니다**. `eventType`과 `roomType` 두 필드를 더한 봉투이며, 나머지 9개 필드는 `ChatMessageResponse`와 이름·타입이 같다. REST DTO에 `roomType`을 추가하는 방식은 채택하지 않는다 — REST 응답 계약(`04_M1_API_명세.md`)을 M2가 건드리게 되기 때문이다.

위 예시는 **TEXT 한 가지 경우**다. `ck_chat_message_payload`·`ck_chat_message_sender` CHECK 제약 때문에 `type`마다 채워지는 필드가 다르다.

| `type` | `senderType` | `senderPetId` | `body` | `meetingCardId` |
|---|---|---|---|---|
| `TEXT` | `PET` | 값 있음 | 값 있음(공백 불가) | `null` |
| `CARD` | `PET` | 값 있음 | **`null`** | 값 있음 |
| `SYSTEM` | `SYSTEM` | **`null`** | 값 있음(공백 불가) | `null` |

프론트가 `body`만 보고 렌더링하면 CARD 이벤트가 빈 말풍선이 되고, `senderPetId`로 발신자를 찾으면 SYSTEM에서 깨진다. `type`으로 먼저 분기한다.

- `CHAT_MESSAGE_CREATED`에는 `replayed` 필드를 넣지 않는다.
- **`clientMessageId`는 `null`일 수 있다.** `ChatMessageService.postSystem`은 이 값을 요구하지 않고(PostgreSQL이 UNIQUE에서 NULL을 서로 다르게 취급하므로 반복 공지가 모두 저장된다), 그런 SYSTEM 메시지의 이벤트에는 `clientMessageId`가 없다. **프론트가 `clientMessageId`만으로 중복을 제거하면 SYSTEM 메시지에서 깨진다.** 중복 제거의 기준은 `messageId`이며 `clientMessageId`는 자기가 보낸 메시지를 낙관적 렌더링과 이어붙일 때만 쓴다.
- 신규 메시지만 각 참여자의 현재 활성 Pet 소유 User 개인 큐에 발행한다. 수신자 집합의 정확한 조건은 §6.3이 정본이며, 이 한 줄은 요약이다.
- 송신자도 자신의 메시지 이벤트를 받는다. ACK와 메시지 이벤트는 별개의 사실이며 도착 순서는 보장하지 않는다.
- 프론트는 ACK와 메시지 이벤트를 서로 다른 이벤트로 처리한다.
- **모르는 `roomId`의 이벤트가 올 수 있다.** 상대가 인사를 보내면 그 순간 DIRECT 방이 생기고 고정 인사 TEXT가 공통 insert 경로를 타므로, 수신자는 **자기 방 목록에 없는 방**의 `CHAT_MESSAGE_CREATED`를 먼저 받는다. 프론트는 이 경우 이벤트를 버리지 말고 `GET /chat/rooms/{roomId}` 또는 방 목록을 다시 조회해 방을 추가한다.
- 반대로 **친구 요청 수락으로 생긴 방은 이벤트가 없다.** `FriendRequestAcceptanceService`가 방만 만들고 메시지를 넣지 않으므로 발행할 것이 없다. 그 방은 REST 폴링으로만 나타난다.

### 6.3 DIRECT 수신자 조회

`roomId → participant pet → pet.owner user → users.active_pet_id가 participant pet과 일치`하는 User를 수신자로 계산한다.

**수신자 집합은 REST가 방 접근을 허용하는 집합과 같아야 한다.** 정본은 `ChatRoomRepository.existsAccessibleDirectRoomForPet`이며, 위 조인만으로는 아래 두 조건이 빠진다. 빠뜨리면 REST로는 볼 수 없는 방의 메시지가 WebSocket으로 도달한다.

1. `chat_room_participants.left_at IS NULL` — 방을 나간 참여자는 수신자가 아니다. 현재 DIRECT에는 나가기 기능이 없어 `left_at`을 기록하는 코드가 없으며, DIRECT에서는 이 조건이 사실상 항상 충족된다. 실제 상태 전이는 M2-003의 GROUP 나가기 기능에서 도입한다. **이 계약을 근거로 M2-002에 DIRECT 나가기 기능을 추가하지 않는다.**
2. `user_blocks` 양방향 부재 — 두 User 사이에 어느 방향이든 차단이 있으면 수신자가 아니다. REST는 이 경우 방 자체를 `404 CHAT_ROOM_NOT_FOUND`로 숨긴다 — 방이 없을 때, 참여자가 아닐 때, 차단 관계일 때가 모두 같은 응답이다(`ChatQueryService.requireParticipant`, `04_M1_API_명세.md`의 채팅 오류 표). 실시간 경로가 이를 지키지 않으면 차단당한 사실과 상대의 활동이 그대로 노출된다.

차단은 SEND 시점(`ChatQueryService.requireParticipant`)에 이미 걸러지므로 발신자 쪽은 문제가 없다. 위 2가 필요한 이유는 **수신자 쪽**이다. 서버가 발행하는 `CARD`·`SYSTEM` 메시지, 그리고 차단이 성립하기 직전에 시작된 SEND가 커밋된 경우가 여기에 해당한다.

- 현재 활성 Pet을 선택하지 않은 User는 실시간 수신 대상이 아니다.
- 커밋 후 활성 Pet을 변경하거나 차단이 성립하는 경합으로 이벤트를 놓치거나 한 건 더 받을 수 있다. 이는 허용된 레이스이며 REST 폴링이 최종 상태의 정본이다.

`existsAccessibleDirectRoomForPet`은 쿼리 안에 `room.type = 'DIRECT'`를 포함한 **DIRECT 전용 정본이며 GROUP roomId에 사용하지 않는다.** GROUP의 참여·나가기·차단에 따른 등가 수신자 규칙은 M2-003에서 BE-4가 정의하고, M2-004의 GROUP 실시간 발행이 그 규칙을 사용한다. 공통 계약의 대상은 이벤트 형식과 개인 큐 전달 방식이며, 방 유형별 접근 쿼리 구현은 동일하지 않아도 된다.

## 7. 오류 계약

**오류는 발생 계층에 따라 여섯 갈래로 갈린다.** 하나로 합치면 `clientMessageId` 규칙이 깨지고, 특히 `INTERNAL_ERROR`는 계층에 따라 전달 수단과 세션 결과가 반대가 된다.

| # | 발생 계층 | 전달 | 세션 |
|---|---|---|---|
| 1 | 컨트롤러: 채팅 도메인 오류 (`ACTIVE_PET_REQUIRED`, `CHAT_ROOM_NOT_FOUND`, `GREETING_REPLY_REQUIRED`, `CHAT_DUPLICATE_MESSAGE`, payload 검증 실패) | `/user/queue/errors`의 `CHAT_ERROR` | 유지 |
| 2 | 컨트롤러: 예기치 않은 예외 | `/user/queue/errors`의 `CHAT_ERROR(INTERNAL_ERROR)` | 유지 |
| 3 | 인터셉터: 프레임 인증 실패·허용 밖 destination·미허용 command | STOMP `ERROR` (`UNAUTHORIZED` / `FORBIDDEN`) | 종료 |
| 4 | 전송 계층: malformed STOMP frame | STOMP `ERROR(VALIDATION_FAILED)` | 종료 |
| 5 | 인터셉터·전송 계층: 예기치 않은 예외 | STOMP `ERROR(INTERNAL_ERROR)` | 종료 |
| 6 | 예약된 JWT 만료 종료 | 프레임 없음, WebSocket close | 종료 |

**`eventType`으로 두 갈래를 구분할 수 없다.** STOMP `ERROR` 프레임 body도 큐로 가는 것과 동일한 payload이며 `eventType`이 똑같이 `CHAT_ERROR`다. 따라서 **`CHAT_ERROR`를 받았다는 사실만으로 연결이 유지된다고 판단하면 안 된다.** 구분 기준은 전달 경로(큐 구독 vs `ERROR` 프레임)다.

**STOMP `ERROR`에도 `code`를 실어 프론트가 기계적으로 구분할 수 있게 한다.** 경로 3에는 인증 실패와 destination 위반이 함께 들어오는데, 프론트의 재연결 규칙(§8)은 **인증 실패일 때만 refresh**해야 한다. 구분 값이 없으면 destination 위반에도 refresh를 돌게 된다. `ERROR` 프레임 body를 `CHAT_ERROR`와 같은 `{code, message}` 형태로 싣고, `code`는 기존 `ErrorCode`를 그대로 쓴다. 새 오류 코드를 만들지 않는다 — 필요한 값이 이미 있다.

| 사유 | `code` |
|---|---|
| CONNECT 인증 실패, 토큰 만료·서명 오류 | `UNAUTHORIZED` |
| 허용 목록 밖 destination의 SEND·SUBSCRIBE | `FORBIDDEN` |
| 인증되지 않은 프레임, 허용하지 않는 프레임 종류 | `FORBIDDEN` |
| STOMP 프레임 자체가 잘못됨 | `VALIDATION_FAILED` |
| 인터셉터·전송 계층의 예기치 않은 예외 | `INTERNAL_ERROR` |

`FORBIDDEN`은 범용 코드(`"현재 계정에 권한이 없습니다."`)라 destination 위반에 그대로 맞는다.

**두 경로가 같은 body를 내야 한다.** 인가 거부는 `ChannelInterceptor`에서 잡히지만, **STOMP 프레임 자체가 파싱되지 않는 경우는 인터셉터까지 오지도 않는다** — 그건 `StompSubProtocolErrorHandler`(또는 동등한 protocol error handler)가 처리하는 영역이고, 파싱 단계 오류에서는 원본 메시지가 `null`일 수도 있다. 두 곳을 각각 구현하면 인터셉터 오류만 `{code, message}`가 되고 malformed 프레임은 Spring 기본 형식으로 나간다. **양쪽 모두 같은 body를 만들도록 error handler를 구성한다.**

경로 3~5를 큐의 `CHAT_ERROR`로 보낼 수 없는 이유는 두 가지다. 첫째, 그 거부는 `ChannelInterceptor`에서 일어나 `@MessageMapping` 컨트롤러에 도달하지 않으므로 `@MessageExceptionHandler`가 잡는 흐름을 타지 않는다. 둘째, `SUBSCRIBE /topic/...` 같은 프레임에는 **애초에 `clientMessageId`가 없어** 아래 payload 규칙을 만족시킬 수 없다.

아래 payload는 **경로 1~2(컨트롤러에서 발생해 큐로 가는 오류)의 형식**이다.

```json
{
  "eventType": "CHAT_ERROR",
  "code": "CHAT_DUPLICATE_MESSAGE",
  "message": "동일한 clientMessageId의 메시지가 이미 존재합니다.",
  "roomId": 123,
  "clientMessageId": "client-generated-id"
}
```

- **`clientMessageId`를 반드시 싣는다.** 없으면 프론트가 여러 건을 동시에 보냈을 때 어느 전송이 실패했는지 알 수 없어 낙관적 렌더링을 되돌리지 못한다. **payload를 읽기 전에 실패한 경우에만 `null`이다** — payload 파싱 실패, 그리고 인터셉터 단계의 인증·세션 검사 실패(§3 항목 6, 경로 3)가 여기 해당한다. 그 검사는 컨트롤러 바인딩보다 앞이라 아직 `ChatMessageCreateRequest`가 없다. **이 값을 얻으려고 인터셉터에서 raw JSON을 다시 파싱하지 않는다.**
- `CHAT_ERROR`는 이 문서가 새로 정하는 값이다. `/user/queue/errors`에는 이 한 종류만 흐르지만, ACK·메시지 이벤트와 처리 코드를 공유할 수 있도록 `eventType`을 동일하게 싣는다.
- `message`는 `ErrorCode`에 정의된 문구를 그대로 쓴다. 위 예시의 문장도 `ErrorCode.CHAT_DUPLICATE_MESSAGE`의 실제 값이다. WebSocket 전용 문구를 새로 만들지 않는다.
- `code`·`message`는 REST 오류 응답의 `ApiResponse.ErrorBody`와 같은 의미다. 다만 **WebSocket은 `ApiResponse` 봉투를 쓰지 않는다** — §6.1·§6.2와 마찬가지로 평면 payload이며 `success`·`data` 필드가 없다.
- 오류는 `/user/queue/errors`로 보낸다.
- **`CHAT_ERROR`는 그 SEND를 일으킨 세션에만 보낸다.** ACK와 같은 규칙이며(§6.1) `@SendToUser("/queue/errors", broadcast = false)` 또는 동등한 세션 한정 방식을 쓴다. `/user` destination은 기본이 같은 User의 **모든** 세션이라 그대로 두면 broadcast된다.
  - 특히 위험한 경우는 `UNAUTHORIZED`다. 탭 A의 토큰만 만료됐는데 User 전체로 퍼지면 **멀쩡한 탭 B까지 refresh·재연결**에 들어간다(§8).
  - 인터셉터에서 발생한 인증·세션 오류도 그 세션에만 전달한다. 다만 그쪽은 큐가 아니라 STOMP `ERROR` 프레임이므로(경로 3) 애초에 broadcast 대상이 아니다.
- 오류 코드는 REST와 동일한 `ErrorCode`를 사용한다. WebSocket 전용 오류 코드를 새로 만들지 않는다.
- 일반적인 검증·권한·도메인 오류는 오류 이벤트 후 연결을 유지한다.
- CONNECT 실패는 경로 3이므로 STOMP `ERROR` 프레임이다. 연결 후의 토큰 만료·정지 계정 SEND도 같은 경로이며, 구독이 성립해 있더라도 큐의 `CHAT_ERROR`가 아니라 STOMP `ERROR`(`code=UNAUTHORIZED`)를 보내고 종료한다 — 근거와 `ACTIVE_PET_REQUIRED`(세션 유지)와의 구분은 §3 항목 6에 있다.
- WebSocket payload 검증 실패는 `VALIDATION_FAILED`로 매핑한다. `@MessageExceptionHandler`에서 raw validation exception을 노출하지 않는다.

SEND에서 나올 수 있는 오류 집합은 REST `POST /chat/rooms/{roomId}/messages`와 같다 — `VALIDATION_FAILED`(400), `ACTIVE_PET_REQUIRED`(403), `CHAT_ROOM_NOT_FOUND`(404), `GREETING_REPLY_REQUIRED`·`CHAT_DUPLICATE_MESSAGE`(409), 그리고 인증 축의 `UNAUTHORIZED`(401). 이 목록은 코드 기준이다.

여기에 **`CHAT_CLIENT_MESSAGE_ID_REQUIRED`(400)** 가 하나 더 있다. REST는 `@Valid @RequestBody`가 먼저 걸러 `VALIDATION_FAILED`가 나가므로 이 코드가 표면에 드러나지 않지만, `ChatMessageService.sendText`가 `clientMessageId`가 비면 직접 던진다. **WebSocket 컨트롤러에 payload 검증을 붙이지 않으면 REST와 다른 코드가 나간다.** 붙여서 `VALIDATION_FAILED`로 맞춘다. `04_M1_OpenAPI.yaml`의 대표 오류 서술에는 `ACTIVE_PET_REQUIRED`가 빠져 있다.

> **`04_M1_OpenAPI.yaml`의 대표 오류 목록에 `BLOCKED_USER`(403)가 적혀 있으나 채팅 전송 경로는 이 코드를 던지지 않는다.** 저장소 전체에서 `BLOCKED_USER`는 friend·greeting·setlog에서만 발생하고, 채팅에서 차단은 `ChatQueryService.requireParticipant`가 `404 CHAT_ROOM_NOT_FOUND`로 숨긴다. **WebSocket에 `BLOCKED_USER`를 넣으면 차단 사실이 노출된다.** OpenAPI 쪽이 틀렸으며 별도로 정정해야 한다.

### 7.1 `canSend`를 전송 가능 판정으로 쓰지 않는다

`ChatRoomResponse`에 `canSend`와 `sendBlockedReason`이 있으나 **현재 구현은 SEND 실패 조건을 전부 반영하지 않는다.**

- `canSend`는 방 상태만 본다 — `ACTIVE` 또는 `ARCHIVED`면 `true`다. 인사 답변 대기 방도 `true`가 되지만 실제 SEND는 `GREETING_REPLY_REQUIRED`(409)로 막힌다.
- `sendBlockedReason`은 항상 `null`을 반환하는 스텁이다. `04_M1_OpenAPI.yaml`은 이 필드를 `[GREETING_REPLY_REQUIRED, BLOCKED_USER, ACCOUNT_NOT_ACTIVE, null]` enum으로 정의해 두었으므로 **명세와 구현이 어긋나 있다.**

따라서 프론트는 이 두 필드로 입력창을 여닫는 판단을 하지 않는다. 전송 가능 여부의 정본은 SEND 결과이며, 오류가 오면 §7의 `code`로 입력을 제한한다. 두 필드를 실제로 채우려면 M1 구현과 OpenAPI를 함께 고쳐야 하고, 이 문서의 범위가 아니다.

### 7.2 방 조회 헬퍼를 게이트 없이 부르지 않는다

`ChatRoomRepository.findRoomById`에는 차단 필터가 없다. 지금 안전한 이유는 `ChatQueryService.getRoom`이 앞에서 `requireParticipant`를 부르기 때문이며, **방어가 호출 순서에만 의존한다.** WebSocket 구현에서 방 정보가 필요해 이 헬퍼를 직접 쓰면 차단된 방이 새어나간다. 방 접근은 반드시 `requireParticipant`를 거친 뒤에 한다.

## 8. 프론트 재연결·복구

- 연결 실패·heartbeat timeout(§3 항목 10)·세션 종료 시 지수 백오프로 재연결한다.
- **재연결 전에 refresh가 필요한지 먼저 판정한다.** 만료 토큰을 들고 백오프만 하면 무한 루프이고, 반대로 네트워크가 한 번 끊길 때마다 refresh하면 낭비다. 기준은 넷이다.

| 상황 | 처리 |
|---|---|
| `CHAT_ERROR(UNAUTHORIZED)` 수신 | refresh 1회 → 새 토큰으로 재연결 |
| STOMP `ERROR` 수신이고 **`code=UNAUTHORIZED`** | refresh 1회 → 새 토큰으로 재연결 |
| STOMP `ERROR` 수신이고 `code`가 `FORBIDDEN`·`VALIDATION_FAILED` | refresh하지 않는다. destination 위반·프로토콜 오류이므로 클라이언트 버그로 다루고 재연결 루프에 넣지 않는다 |
| 오류 없이 닫혔는데 **현재 시각 ≥ access token `exp`** | refresh 1회 → 새 토큰으로 재연결 (서버의 §3 항목 7 예약 종료가 이 경우다) |
| 그 밖의 close·heartbeat timeout·네트워크 실패 | **같은 토큰으로** 지수 백오프 재연결 |

재연결 시도가 다시 인증 실패로 끊기면 그때 refresh한다. refresh가 실패하면 로그아웃 처리한다. 정지 계정도 같은 코드로 끊기므로 refresh가 실패하고 자연히 로그아웃으로 수렴한다.
- **재연결 성공 시 방 목록(`GET /chat/rooms`)을 먼저 다시 읽는다.** 끊겨 있는 동안 상대가 인사를 보내 새 DIRECT 방이 생겼을 수 있는데, 그 방의 `CHAT_MESSAGE_CREATED`는 이미 놓쳤고 로컬에 `roomId`조차 없어 `afterMessageId` 복구가 불가능하다. 목록은 특정 방의 *소멸* 판정에는 쓸 수 없지만(§4) **신규 방 발견에는 이것이 유일한 경로다.**
- 그 다음 각 방의 누락 메시지를 `GET /chat/rooms/{roomId}/messages?afterMessageId={lastMessageId}`로 보충한다. **응답의 `hasMore`가 `true`면 `nextAfterMessageId`로 반복 호출해 끝까지 가져온다.** 응답은 `{items, nextAfterMessageId, hasMore}`이고 `limit`은 기본 50·최대 100이므로, 끊긴 동안 100건을 넘겼다면 한 번으로 부족하다.
- WebSocket 전송이 실패하면 기존 REST `POST /chat/rooms/{roomId}/messages`로 fallback한다.

## 9. BE-2 / BE-4 책임 경계

**이 표는 WebSocket 채팅 범위 안의 경계만 정한다.** M2 전체 담당은 `07_M2_마일스톤_WBS.md` §3이 정본이며, 거기에는 이 표에 없는 항목(공유 약속 제안, Redis·Kafka 공통 인프라, `participants[]` 확장 등)이 함께 있다. 두 문서가 어긋나면 M2 담당은 07을 따른다.

| 담당 | 책임 |
|---|---|
| BE-2 | 공통 WebSocket 설정, CONNECT 인증, Principal, ErrorCode 매핑, 개인 큐 발행, DIRECT controller/adapter, immutable DTO 이벤트 |
| BE-4 | GROUP 전송 경로, 그룹 멤버십·권한, Kafka partition key를 `roomId`로 하는 그룹 이벤트 연계 |
| 공통 | 기존 도메인 서비스 경유, 동일한 오류·이벤트 계약, 프론트 destination 공유 |

BE-4는 `SecurityConfig`, JWT interceptor, `Principal` 규칙, 공통 publisher, `/user/queue/errors`를 복제하지 않는다. GROUP의 저장·Kafka consumer 이후에도 최종 사용자 전달 형식은 이 문서의 개인 큐·오류 계약과 맞춘다.

`itda.common.config.KafkaProducerConfig`의 `KafkaTemplate<String, Object>` bean은 현재 저장소에 이미 있다(PR #55 — BE-4의 M2 공통 인프라 작업, `07_M2_마일스톤_WBS.md` `M2-008`). GROUP 메시지 발행에 이 bean을 재사용할지, 아니면 GROUP 전용 producer 설정을 새로 만들지는 BE-4가 위 표의 `roomId` partition key 전제와 함께 결정한다. 이 문서는 재사용을 강제하지 않는다 — 다만 `KafkaProducerConfig` 자체를 복제해 두 번째 `ProducerFactory`를 만들지 않는 것을 원칙으로 한다.

**결정 전에 확인할 사실:** 현재 `KafkaProducerConfig`는 타입이 `KafkaTemplate<String, Object>`인데 `VALUE_SERIALIZER_CLASS_CONFIG`가 `StringSerializer`로 잡혀 있다. 즉 value 타입만 `Object`일 뿐 String이 아닌 페이로드를 보내면 런타임에 실패한다. 그대로 재사용하려면 발행 측이 직렬화를 직접 해서 String을 넘겨야 하고, 이벤트 객체를 그대로 넘기려면 `JsonSerializer` 설정이 필요하다. 이 선택은 GROUP 이벤트 스키마 결정과 묶여 있으므로 BE-4가 M2-004에서 한 번에 정한다.

## 10. 수용 기준

> **검증 환경 주의.** 멱등성과 `replayed` 판정은 PostgreSQL 전용 구문(`ON CONFLICT ... RETURNING (xmax = 0)`)에 의존한다. 기본 `test` 태스크는 H2이고 `excludeTags 'postgres'`로 통합 테스트를 제외하므로, **아래 기준 중 멱등성·동시성·수신자 집합에 관한 것은 `@Tag("postgres")` 테스트로 작성하고 `./gradlew postgresTest`로 돌려야 한다.** 기존 채팅 통합 테스트 6종이 모두 그 태그를 쓴다.
>
> **그런데 그 테스트는 PR에서 돌지 않는다.** `dev`로 가는 PR을 검사하는 `.github/workflows/ci-test.yml`은 `./gradlew test`만 실행하고, `postgresTest`를 함께 돌리는 `ci.yml`은 `main` push에서만 동작한다. 즉 위 기준을 테스트로 옮겨도 **머지 전에 자동으로 검증되지 않는다.** 로컬에서 `./gradlew postgresTest`를 수동으로 돌리거나, 워크플로를 먼저 고쳐야 한다.

- CONNECT에 유효한 Bearer token이 없으면 연결이 거부된다.
- `/topic/**`·`/queue/**`·`/user/**`로의 직접 SEND가 거부된다.
- 허용 목록 밖 destination의 SUBSCRIBE가 거부되고, `/user/queue/chat/messages`·`/user/queue/errors` 구독은 성공한다.
- `HEARTBEAT`·`DISCONNECT`·`UNSUBSCRIBE`는 인가 인터셉터가 막지 않는다.
- destination 인가 거부는 STOMP `ERROR`로 오고 `/user/queue/errors`로 오지 않는다.
- STOMP `ERROR`에 `code`가 실려 인증 실패와 destination 위반을 프론트가 구분할 수 있다.
- 파싱되지 않는 STOMP 프레임도 인터셉터 거부와 **같은 `{code, message}` 형식**의 `ERROR`로 나간다.
- SEND를 한 번도 하지 않고 수신만 하는 세션도 access token `exp`에 종료된다.
- `exp` 전에 끝난 세션의 예약 종료 작업이 정리되고, `DISCONNECT`가 두 번 와도 문제가 없다.
- 정지 사용자와 만료 토큰은 SEND마다 차단되고 세션이 종료된다.
- 비참여자·차단 관계·Active Pet 없음은 REST와 같은 오류 의미로 처리된다. 특히 활성 계정의 Active Pet 없음은 `ACTIVE_PET_REQUIRED`이며 세션을 끊지 않는다(정지 계정의 `UNAUTHORIZED`와 구분 — §3 항목 6).
- 방을 나간 참여자와 차단 관계 User는 실시간 이벤트를 받지 않는다. 수신자 집합이 `existsAccessibleDirectRoomForPet`의 허용 집합과 일치한다(§6.3).
- 이벤트 봉투의 `roomType`이 저장 트랜잭션 안에서 채워진다(커밋 후 `getRoom()` 접근 없음).
- 새 메시지는 ACK 1건과 신규 메시지 이벤트 1건으로 분리된다.
- ACK는 요청을 보낸 세션에만 도달한다. 같은 User의 다른 탭은 ACK를 받지 않고 `CHAT_MESSAGE_CREATED`만 받는다.
- 같은 User의 두 세션 중 A에서 발생한 `CHAT_ERROR`가 B에 도달하지 않는다. 특히 A만 토큰이 만료됐을 때 B가 재연결에 들어가지 않는다.
- Principal이 HTTP `CurrentUser`가 아니며 `Principal.getName()`이 email이 아닌 userId 문자열이다.
- 같은 메시지의 `createdAt`이 REST 응답과 WebSocket 이벤트에서 동일한 형식으로 직렬화된다.
- 같은 `clientMessageId`에 다른 본문을 보내면 ACK가 아니라 `CHAT_DUPLICATE_MESSAGE` 오류가 온다.
- 오류 이벤트에 `clientMessageId`가 실려 어느 전송이 실패했는지 프론트가 특정할 수 있다.
- 차단 관계에서 SEND하면 `BLOCKED_USER`가 아니라 `CHAT_ROOM_NOT_FOUND`가 온다.
- 멱등 재전송은 ACK만 보내고 신규 메시지 이벤트는 보내지 않는다.
- REST에서 저장된 TEXT/CARD/SYSTEM도 동일한 신규 메시지 이벤트를 발행한다.
- `AFTER_COMMIT` 이벤트에 JPA 엔티티가 아닌 DTO가 들어간다.
- WebSocket이 끊겨도 REST 폴링으로 누락 메시지를 복구할 수 있다.
- 단일 인스턴스 전제와 다중 replica 전환 조건이 배포 문서에 전달된다. **다만 현재 저장소에는 그 "배포 문서"에 해당하는 정의가 없다** — compose 2개와 `Dockerfile`뿐이고 k8s·helm 같은 배포 정의가 없으며 `ci.yml`도 GHCR 이미지 푸시까지만 한다. 전달할 위치를 `M2-006`에서 먼저 정해야 이 기준이 검증 가능해진다.
- 이미 존재하는 `RedisConfig`·`KafkaProducerConfig`가 WebSocket 다중 replica 문제를 자동으로 해결하지 않는다는 점이 배포 문서·BE-4 인계에 명시된다.
- 물리 삭제된 방에 대한 SEND는 세션을 끊지 않고 REST와 동일한 `404 CHAT_ROOM_NOT_FOUND`로 응답한다.
- 채팅방 수명주기 배치(만료 삭제·`ARCHIVED` 전환)는 실시간 이벤트를 발행하지 않는다.

## 11. 구현 제외

- STOMP 외 별도 transport. **SockJS도 쓰지 않는다**(§1과 동일) — 순수 WebSocket + STOMP만 사용한다
- DIRECT 메시지의 Kafka 경유
- Outbox 도입
- Read receipt, typing indicator, online presence
- 메시지 수정·삭제
- 이미지·파일 메시지
- 다중 replica WebSocket 라우팅
- **방 생성.** DIRECT 방은 인사(`GreetingService`, setlog 기반)와 친구 요청 수락(`FriendRequestAcceptanceService`) 두 경로에서만 `ChatRoomService.ensureDirectRoom`으로 생긴다. WebSocket으로 방을 만들 수 없고, 이 계약은 **이미 존재하는 방에 대한 전송·수신만** 다룬다.
- 채팅방 수명주기 배치(삭제·보관) 결과의 실시간 push — 방 목록 폴링으로만 확인
- 채팅방 응답의 `participants[]` 인라인 확장. `04_M1_API_명세.md`가 이를 M2 계약으로 예고하고 있으나 REST 응답 스키마 변경이라 이 문서의 범위가 아니다. GROUP 채팅 설계와 함께 별도로 다룬다(`07_M2_마일스톤_WBS.md` M2-007).