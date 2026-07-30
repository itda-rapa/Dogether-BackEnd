# 홈 → 인사 → 채팅 데모 시드

로컬 프로필에서 Flyway를 활성화하면 데모 계정, Pet 4마리, 홈 셋로그
3개가 생성된다. 인사와 채팅방은 미리 만들지 않는다. 홈에서 `인사하기`를
누를 때 실제 API가 DIRECT 채팅방과 첫 인사 메시지를 생성한다.

## 영상 배치

설정된 S3/RustFS 버킷에 아래 object key로 MP4 파일을 미리 넣는다.

| 홈 순서 | Pet | object key |
|---:|---|---|
| 1 | 보리 | `demo/setlogs/bori.mp4` |
| 2 | 마루 | `demo/setlogs/maru.mp4` |
| 3 | 꼬마 | `demo/setlogs/kkoma.mp4` |

## 로그인

- 이메일: `demo@dogether.local`
- 비밀번호: `demo1234!`
- Active Pet: `두부#D001`

상대방 답장 시연에는 선택한 셋로그 작성자의 계정을 사용한다.

| 셋로그 Pet | 로그인 이메일 | Active Pet |
|---|---|---|
| 보리 | `bori@dogether.local` | `보리#D002` |
| 마루 | `maru@dogether.local` | `마루#D003` |
| 꼬마 | `kkoma@dogether.local` | `꼬마#D004` |

시드에 포함된 네 계정은 모두 `demo1234!` 비밀번호를 사용한다.

## 실행

`.env`에서 `SPRING_PROFILES_ACTIVE=local`, `FLYWAY_ENABLED=true`로 실행한다.
local 프로필은 migration, 공통 개발 seed, 데모 seed를 순서대로 적용한다.

데이터를 확인하는 기본 흐름은 다음과 같다.

1. `POST /auth/login`으로 데모 계정 로그인
2. `GET /setlogs`로 영상 셋로그 3개 조회
3. 원하는 셋로그에 `POST /setlogs/{setlogId}/greetings`
4. 선택한 셋로그 작성자 계정으로 로그인해 응답의 `roomId`에 답장
5. 양쪽에서 `POST /chat/rooms/{roomId}/messages`로 대화
6. `POST /chat/rooms/{roomId}/card-drafts`로 카드 초안 생성
7. 초안 내용을 확인·보완해 `POST /meeting-cards`로 카드 확정
8. 채팅 polling의 `CARD` 메시지 또는 `GET /meeting-cards/{cardId}`로
   카드 내용 표시

카드 초안 생성은 최근 24시간의 사용자 `TEXT` 메시지가 2개 이상일 때 AI
추출을 시도한다. AI 연결이 없거나 실패해도 빈 폼 fallback을 반환하므로,
`cardType`, `placeText`, `meetAt`을 채워 카드 확정 흐름을 계속 시연할 수
있다.
