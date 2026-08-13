# DIRECT WebSocket 시연 Smoke Runbook

내일 시연에서 DIRECT 채팅의 실시간 송수신을 확인하기 위한 실행 가이드다. 이
문서는 시연 환경의 설정과 확인 절차만 다루며, 코드 설정의 기본값은 바꾸지 않는다.

## 공통 사전조건

- WebSocket을 활성화한 애플리케이션 인스턴스는 **정확히 1개**여야 한다.
- 애플리케이션이 PostgreSQL과 Redis에 연결할 수 있어야 한다.
- 브라우저에서 접근할 실제 프론트엔드 Origin을 미리 확정한다.
- 프론트엔드와 백엔드가 HTTPS를 사용하는 시연이면 WebSocket도 그에 맞는 `wss`
  endpoint를 사용한다.
- 애플리케이션의 WebSocket endpoint는 `/ws`이며, Origin은 HTTP CORS와 같은
  `CORS_ALLOWED_ORIGINS` 목록으로 검사된다. `*`는 사용하지 않는다.

## 시연 환경변수

실제 값은 각 실행 환경의 `.env` 또는 서버 환경변수에만 넣는다. 아래는 변수 이름과
필요한 형태만 적은 것이며, 비밀번호·secret·Service Key·access token은 문서나
commit에 기록하지 않는다.

```text
WEBSOCKET_ENABLED=true
CORS_ALLOWED_ORIGINS=<demo frontend origin>
```

`FLYWAY_ENABLED=true`는 V23~V27 migration이 아직 적용되지 않은 빈 DB 또는
시연 전용 DB를 처음 구성할 때만 일시적으로 사용한다. 기동 로그와 DB의 Flyway
적용 상태로 migration 적용을 확인한 뒤 `FLYWAY_ENABLED=false`로 되돌릴 수 있다.
이미 사용 중인 DB에 이 값을 임의로 켜거나, migration 적용을 확인하지 않은 채
시연을 시작하지 않는다.

시연이 끝나면 `WEBSOCKET_ENABLED=false`로 되돌린다. `.env`는 Git에 포함하지
않으며 실제 secret은 출력·화면 공유·commit하지 않는다.

## A. IntelliJ에서 백엔드 직접 실행

시연 대상이 로컬 PC의 IntelliJ 실행인 경우 다음 조건을 준비한다.

1. PostgreSQL과 Redis가 실행 중이고, 애플리케이션이 해당 주소로 연결되는지 확인한다.
2. IntelliJ 실행 구성의 환경변수에 `WEBSOCKET_ENABLED=true`와
   `CORS_ALLOWED_ORIGINS=<demo frontend origin>`을 넣는다.
3. 빈 시연 DB에 migration이 필요한 경우에만 `FLYWAY_ENABLED=true`로 한 번 기동해
   V23~V27 적용을 확인한다. 확인 후에는 `FLYWAY_ENABLED=false`로 되돌린다.
4. IntelliJ에서 애플리케이션을 실행하고 health endpoint가 정상인지 확인한다.

기본 설정 파일의 `WEBSOCKET_ENABLED` 기본값 false는 유지한다. 시연용 값은
실행 구성 또는 로컬 `.env`에서만 주입한다.

## B. 서버 `docker-compose` 실행

시연 대상이 `deployment/server/docker-compose.yml` 기반 서버 실행인 경우 다음을
확인한다.

1. 서버의 `.env`가 PostgreSQL·Redis 연결과 실제 프론트엔드 Origin을 가리키는지
   확인한다. `.env` 내용과 secret은 출력하거나 commit하지 않는다.
2. 서버 환경변수에 `WEBSOCKET_ENABLED=true`와
   `CORS_ALLOWED_ORIGINS=<demo frontend origin>`을 설정한다.
3. `deployment/server/docker-compose.yml`의 `app` 컨테이너는 한 개만 실행한다.
   PostgreSQL과 Redis는 compose 외부의 준비된 서비스일 수 있으므로 연결 상태를
   먼저 확인한다.
4. 빈 시연 DB에 V23~V27 적용이 필요한 경우에만 `FLYWAY_ENABLED=true`로 기동하고,
   적용 확인 뒤 `FLYWAY_ENABLED=false`로 되돌린다.
5. 컨테이너 health endpoint와 애플리케이션 로그에서 기동·WebSocket 활성화를
   확인한 뒤 브라우저 smoke를 진행한다.

실제 시연 방식이 A인지 B인지 확정되지 않았다면 컨테이너를 새로 올리거나 배포를
변경하지 말고, 해당 방식에 맞는 외부 PostgreSQL·Redis와 프론트엔드 Origin을
확인한 뒤 진행한다.

## 두 사용자 브라우저 Smoke 시나리오

서로 다른 계정으로 브라우저 A와 B를 준비한다. 한 브라우저의 여러 탭을 같은
사용자로 재사용하지 않는다.

1. A와 B에서 각각 로그인한다.
2. 각 브라우저가 아래 endpoint로 WebSocket 연결을 연다.
   - HTTP 시연: `ws://<backend-host>/ws`
   - HTTPS 시연: `wss://<backend-host>/ws`
3. WebSocket 연결 위에서 STOMP `CONNECT`를 보내고 native header에 다음을 넣는다.

   ```text
   Authorization: Bearer <access-token>
   ```

   `Authorization`은 HTTP handshake header가 아니라 STOMP native header다. header가
   없거나 token이 만료되면 `UNAUTHORIZED` STOMP `ERROR` 후 연결이 종료될 수 있다.
4. `CONNECT` 성공 직후 두 user destination을 모두 구독한다.

   ```text
   /user/queue/chat/messages
   /user/queue/errors
   ```

   둘 다 user destination이다. 임의의 `/queue` 또는 `/topic` destination을 직접
   구독하지 않는다.
5. 두 사용자 사이의 기존 DIRECT 방에 진입한다. WebSocket으로 방을 생성하지
   않는다.
6. A가 아래 destination으로 TEXT 메시지를 전송한다.

   ```text
   /app/chat/direct/rooms/{roomId}/messages
   ```

   body는 다음 형태를 사용한다.

   ```json
   {
     "clientMessageId": "<UUID>",
     "body": "안녕하세요"
   }
   ```

   `{roomId}`는 이미 존재하는 DIRECT 방 ID다. `clientMessageId`는 REST fallback
   시에도 같은 값을 재사용한다. `senderPetId`, `type` 같은 필드는 보내지 않는다.
7. B 화면에서 새 메시지가 REST polling을 기다리지 않고 즉시 수신되는지 확인한다.
8. B가 같은 방식으로 응답을 전송한다.
9. A 화면에서 응답이 즉시 수신되는지 확인한다.
10. 두 브라우저의 연결이 유지되고, 메시지가 중복 저장되지 않았는지 REST 조회로
   필요한 범위만 확인한다.

실제 프론트가 위 STOMP `CONNECT`·구독·`SEND`를 아직 구현하지 않았다면, 브라우저
두 개 시연이 가능하다고 가정하지 않는다. 먼저 프로젝트 밖의 임시 STOMP 테스트
클라이언트 또는 프론트 담당자의 실제 클라이언트로 위 endpoint, 인증 header, 두
구독, SEND를 수동 검증한 뒤 시연한다. 테스트 클라이언트 구현·commit은 이 PR
범위 밖이다.

## 실패 시 대응

시연 중 CONNECT, 구독, 전송 또는 즉시 수신이 실패하면 다음 순서로 대응한다.

1. `WEBSOCKET_ENABLED=false`로 되돌린다.
2. 애플리케이션을 재기동해 WebSocket 비활성 상태를 확인한다.
3. DIRECT 채팅은 기존 REST polling 방식으로 시연한다.

SimpleBroker는 구독과 세션 전달 상태를 애플리케이션 JVM 내부에 보관한다. 따라서
인스턴스가 두 개 이상이면 A가 연결된 JVM에서 발생한 이벤트를 B가 연결된 다른
JVM이 자동으로 공유하지 않는다. sticky session만으로 이 broker 상태 공유가
해결되지 않으므로, broker relay 또는 Kafka/Redis 기반 다중 replica 설계가 확정되기
전까지는 WebSocket을 단일 replica에서만 활성화한다. 해당 확장은 이 시연 범위에
포함하지 않는다.
