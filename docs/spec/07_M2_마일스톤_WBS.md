# 같이놀개 M2 마일스톤·WBS

> 상태: **계획 갱신본 — M2-002는 PR #67 리뷰·병합 대기이며, 나머지 항목은 담당·일정 미배정 또는 계획 상태**
> 기준일: 2026-08-07
> **과거 조사 이력:** 이 문서의 초기 저장소 대조는 `dev` `ec16ecd`(PR #62 머지 시점)와 그 이전 `d27f3ac`에서 수행됐다. 아래에 남은 SHA·파일 수·테스트 수치는 당시 조사 근거일 뿐 현재 구현·PR 상태 판정에는 쓰지 않는다. 현재 상태는 각 작업 행과 해당 PR을 기준으로 재확인한다.
> 목표일: 미정(팀 합의 필요)
> 범위: `00_최신_제품정책.md`의 M2 8개 항목과, 정책 문서 반영 대기 중인 아침 리마인드(UC-07)를 다룬다. 실제 진행 상태가 있는 채팅/실시간 인프라와 UC-07만 상세 WBS로 관리하고, 나머지는 뼈대만 세우고 담당자 배정 이후 갱신한다.
> 상태 기준: 현재 Git 저장소의 구현·PR 상태를 기준으로 판정한다.

## 1. 정본과 적용 원칙

문서가 충돌하면 아래 순서로 적용한다.

1. 제품 범위·정책: `docs/spec/00_최신_제품정책.md` **1. 마일스톤 / M2**
2. 실시간 채팅 계약: `docs/spec/06_M2_WebSocket_계약.md`
3. 본 문서: 작업 ID·담당·일정
4. M1 문서 세트(`01~05`)는 M2에서도 저장·상태·오류 계약의 기반으로 계속 유효하다. M2 변경이 있는 부분만 이 문서에서 갱신 대상으로 표시한다.

작업 ID는 `M2-001`부터 새로 채번한다. **이전 v13 산출물의 M2 번호 체계는 통째로 재사용하지 않는다** — 전체 WBS의 `M2-024~027`(미디어·차단·알림·신고이의)뿐 아니라 담당자별 문서의 `M2-006A/006B/006/021/025` 같은 번호도 포함한다. 그 결정 시점 이후 차단·신고가 M1로 편입되고 BE-4가 합류하면서 M2 범위와 담당이 모두 바뀌었기 때문이다. 과거 번호가 필요해지면 새 번호를 붙이고 이 문서에 매핑을 남긴다.

상태 표기는 `04_M1_마일스톤_WBS.md`와 같다.

| 상태 | 의미 |
|---|---|
| 완료 | 현재 저장소에서 구현과 핵심 테스트를 확인함 |
| 부분완료 | 기반 또는 일부만 구현됨 |
| 미착수 | 현재 저장소에서 도메인 구현을 확인하지 못함 |
| 초안 | 계약 문서만 있고 리뷰·머지가 끝나지 않음 |
| 후보 | M2 필수 범위가 아니며, 제품 결정이 나야 착수 여부가 정해짐 |

## 2. M2 목표 범위

`00_최신_제품정책.md`가 정본이다. 이 문서는 그 8개 항목을 §2.1·§2.2 두 갈래로 나누고, 정책 문서에 아직 없는 확정 기능을 §2.3에 따로 둔다.

### 2.1 실시간 채팅 인프라 (BE-2·BE-4 중심, 일부 항목 미배정)

제품 정책 문서의 "그룹 채팅" 항목과, M1이 "폴링 방식"이었기 때문에 자연히 M2로 넘어오는 실시간 전환 작업이다. 사용자 기능 목록에는 없지만 그룹 채팅을 지탱하는 전제 인프라라서 별도로 관리한다.

- DIRECT 채팅의 WebSocket/STOMP 전환 (REST 폴링과 병행)
- GROUP 채팅 도메인과 Kafka 연계

**채팅방 수명주기 배치(무응답 인사 방 정리·30일 보관)는 M2 신규 작업이 아니다.** 이 기능은 `04_M1_마일스톤_WBS.md`의 `M1-038`이며, PR #50(2026-08-06 머지)이 M1 종료 기한을 넘겨 뒤늦게 완료했다. 최신 M1 WBS에도 `M1-038`은 완료와 PostgreSQL·단위 테스트 근거로 정정되어 있다. 다만 이 배치가 WebSocket SEND·이벤트 동작과 직접 상호작용하므로(`06_M2_WebSocket_계약.md` §4·§5·§10 참고) §4 각주와 §7 위험 항목에서 참고로만 언급한다.

### 2.2 담당·일정 미배정 항목

이 문서 작성 시점 기준으로 담당자·기간이 배정되지 않았다.

- Google 로그인
- GPS 동네 확인
- 사용자 셋로그 업로드
- 지도·장소
- 만남 확인
- 후기·발자국
- 대화 맥락 검열 — GROUP 채팅 포함 여부는 §4 `M2-005`에서 따로 다룬다

**React Native는 M2에 포함하지 않는다(2026-08-07 확정).** 최신 `04_M1_마일스톤_WBS.md`는 React Native를 M1 제외 항목에 두고 `M1-019`를 `MOVED(POST-M1)`로 정리했다. `00_최신_제품정책.md` §1의 8개 항목이 M2 범위의 정본이며, 이 WBS에서 별도 후속 작업으로 다시 잡지 않는다.

Push, 읽음 표시, 메시지 수정·삭제, 이미지·파일 첨부, 회원탈퇴, 이메일 인증, 비밀번호 찾기·재설정은 `00_최신_제품정책.md`가 M1 미제공으로 명시했고 M2 목록에도 없다. M2로 당겨오려면 제품 정책 문서를 먼저 갱신해야 하며, 이 WBS가 임의로 포함하지 않는다.

다만 **이메일 인증과 비밀번호 재설정은 이미 구현돼 dev에 머지됐다**(PR #62, `ec16ecd`). Redis Streams는 이메일 delivery queue로 사용하며, 인증 challenge 발급·코드 확인·verification token consume은 `EmailVerificationRedisStore`의 Redis Lua script로 원자 처리한다. 회원가입 연동과 비밀번호 재설정 API도 들어갔고 `SecurityConfig`의 `permitAll`에도 세 경로가 추가됐다. **제품 정책 문서의 M1 미제공 표기는 최신 정책 문서에서 구현 사실과 정합화됐다.**

### 2.3 공유 약속 제안(UC-07) — 구현 확정, 정책 문서 반영 대기

`00_최신_제품정책.md` M2 목록에는 없지만 **구현하기로 확정된 기능**이다(2026-08-07). 출처는 기획 단계 산출물 `같이놀개_유즈케이스.html`의 **UC-07 「아침 리마인드 — 채팅방에서 함께 다듬기」**이며, 그 문서에서 이미 M2로 표시돼 있었다. v13 산출물과 현재 `docs/spec/`에는 승격된 적이 없다.

**그 출처 파일은 저장소 밖에 있다.** 아래에 원문 규격을 그대로 옮겨 두었으므로 이 절만으로 판단할 수 있으나, `G-M2-A`로 정책 문서에 반영할 때 원본을 저장소에 함께 올릴지 결정한다.

기획 원문의 규격은 다음과 같다.

- 사전 조건: 전날 대화에 약속 표현이 있었는데 **카드를 만들지 않음**
- 새벽 배치가 전날 대화를 검사해 해당 방을 찾는다
- 아침에 **채팅방 안에** 제안이 나타난다 — "아직 약속이 아니에요" 표시
- 양쪽 모두 종류·날짜·시각·장소를 수정할 수 있다
- 양쪽이 모두 👍를 누르면 정식 카드로 확정된다
- 동의하지 않으면 **당일 자정에 자동 소멸**한다
- 하루 1건 제한 · 알림 1회

#### 이것은 버튼식 초안의 자동화판이 아니다

**별도 도메인으로 만든다.** 버튼식(`POST /chat/rooms/{roomId}/card-drafts`, M1 구현 완료)은 특정 Pet이 요청하는 개인 편집용 초안이고, 즉시 카드로 확정할 수 있으며 **AI가 실패하거나 문맥이 부족해도 요청을 거절하지 않고 `200`과 빈 폼(`fallbackReason=INSUFFICIENT_CONTEXT`)을 돌려준다**(`05_M1_결정사항_보완과제.md` §7.4, `02_M1_API_계약.md`). UC-07은 시스템이 생성하는 방 공유 리소스이고, 참여자가 공동 편집하며 양쪽 동의가 있어야 확정되고, 정해진 시각에 공개·만료되며 AI가 실패하면 아예 만들지 않는다. 성질이 전부 반대다.

기존 `card_drafts`를 확장해 두 성격을 겸하게 만들면 M1에서 확정한 카드·채팅 계약이 함께 흔들린다. 신규 테이블 `meeting_card_suggestions`로 분리한다.

```
meeting_card_suggestions
  id, room_id, source_date, status,
  card_type, place_text, meet_at,
  revision              -- 내용 편집의 낙관적 동시성
  event_version         -- BIGINT NOT NULL DEFAULT 0, 클라이언트 관찰 가능한 변경마다 +1
  generation_started_at -- lease. stale 회수와 fencing에 사용
  skip_reason           -- SKIPPED 사유 (약속없음/AI타임아웃/AI오류/자격변동/공개시각놓침)
  canceled_by_pet_id    -- CANCELED 흔적
  canceled_at
  publish_at, expires_at,
  created_at, updated_at
  UNIQUE (room_id, source_date)
```

위 목록은 이 절에서 확정된 것을 모두 반영한 것이다. 각 컬럼의 근거는 아래 소절에 있다.

상태는 최소 집합으로 둔다.

```
GENERATING → READY → ACTIVE → CONFIRMED
     │         │        ├→ EXPIRED
     │         │        └→ CANCELED
     │         └→ SKIPPED        (공개 직전 재검사 실패 / 공개 시각 놓침)
     └→ SKIPPED                  (AI 실패 / 약속 없음)
```

**`READY`에서 나가는 길이 `ACTIVE` 하나뿐이면 안 된다.** 공개 직전에 대상 조건을 다시 검사하기로 했으므로(아래) 새벽에는 통과했지만 06:30에 상대가 차단하는 식으로 **재검사가 실패하는 경우가 실제로 생긴다.** 그때 갈 곳이 없으면 그 행은 `READY`로 영원히 남는다. `READY → SKIPPED`를 둔다.

**공개 시각을 놓친 `READY`도 같은 길로 보낸다.** 서버 장애로 07:00 작업이 아예 돌지 않고 다음 날 발견되면, 이미 지난 날짜의 `READY`를 되살려 공개하지 않는다 — `expires_at`이 지났으므로 `SKIPPED`로 닫는다.

**`GENERATING`에 갇힌 행을 되살릴 규칙이 필요하다.** `UNIQUE (room_id, source_date)`가 배치 멱등성의 근거인데, 바로 그 때문에 `GENERATING` 행을 만든 직후 프로세스가 죽으면 **다음 배치가 같은 키로 다시 만들 수 없어 그 방은 영원히 `GENERATING`으로 남는다.** 위 상태도에는 그 복구 경로가 없다. `generation_started_at`(또는 `updated_at`) 기준으로 일정 시간이 지난 `GENERATING`을 다음 배치가 회수해 재시도할 수 있게 한다. 회수 기준 시간은 설정값으로 두고 **`M2-009`가 `generation_started_at` 전용 컬럼을**(`updated_at` 재사용보다 의미가 명확하다), **`M2-010`이 회수 알고리즘을** 맡는다.

**회수는 두 워커가 동시에 집어가지 않아야 한다.** Redis 락은 정합성 수단이 아니므로(아래) 회수권도 DB로 가져간다 — 행을 `FOR UPDATE`로 잠그고, 잠근 상태에서 `generation_started_at`이 아직 stale인지 **재확인**한 뒤 그 값을 `now`로 갱신하고 커밋한다. **AI 호출은 그 커밋 이후에 한다.** 잠금 없이 조회→호출 순서로 짜면 replica 둘이 같은 방에 대해 AI를 두 번 부른다.

**최초 점유도 같은 원칙이다.** 후보를 찾으면 AI를 부르기 전에 먼저 `status=GENERATING`·`generation_started_at=now`로 **INSERT하고 `ON CONFLICT (room_id, source_date) DO NOTHING`**, 삽입에 성공한 워커만 AI를 호출한다. `chat_messages`의 멱등 upsert와 같은 구조다.

**회수도 claim cutoff를 지킨다.** 06:40에 점유한 워커가 죽고 06:58에 다른 워커가 stale을 발견해도, 그 시각이 이미 claim cutoff를 지났다면 **AI를 새로 부르지 않는다** — 어차피 07:00 전에 끝낼 수 없다. 그 경우 회수한 lease 아래에서 `GENERATING → SKIPPED`(공개 시각 놓침)로 닫는다. 이 전이도 행 잠금 안에서 한다.

**결과를 저장할 때 자기 lease인지 확인한다.** stale 기준을 길게 잡아도 프로세스가 멈췄다 늦게 살아나는 경우까지는 막지 못한다 — 옛 워커와 회수한 워커가 둘 다 결과를 쓰면 서로를 덮는다. 완료 UPDATE에 **자기가 점유할 때 쓴 `generation_started_at` 값을 조건으로 건다.**

```sql
UPDATE meeting_card_suggestions
   SET status = 'READY', ...
 WHERE id = :id
   AND status = 'GENERATING'
   AND generation_started_at = :myClaimedAt
```

영향 행이 0이면 **이미 lease를 잃은 워커이므로 결과를 버린다.** 증가형 `lease_version` 컬럼을 두는 방식도 같은 목적이며 `M2-009`에서 택한다.

**stale 기준 시간은 AI 호출 타임아웃보다 넉넉히 길어야 한다.** 짧으면 정상 처리 중인 워커의 행을 다른 replica가 빼앗아 AI를 두 번 부른다. `stale 기준 > AI 요청 하드 타임아웃 + 여유`를 조건으로 두고 설정값으로 노출한다.

`SKIPPED`는 **공개 전에 그날 그 방에는 제안을 노출하지 않기로 종결한 상태**이며 클라이언트에 노출하지 않는다. 사유로 AI 실패·약속 없음·공개 전 자격 변동·공개 시각 놓침을 구분한다. 이 상태가 없으면 자정 배치가 재시도될 때 "이미 시도했는지 / 실패했으니 그날은 끝인지 / 다시 AI를 불러도 되는지"를 구분할 수 없다. `SKIPPED` 행도 `UNIQUE (room_id, source_date)`를 점유하므로 이것이 곧 배치 멱등성의 근거다.

확정 카드에는 nullable UNIQUE `source_suggestion_id`를 별도로 추가한다. 기존 `source_draft_id`의 의미를 건드리지 않는다. **방이 살아 있는 동안에는 `meeting_card_suggestions` 행을 물리 삭제하지 않는다.** 만료·취소·`SKIPPED`를 이유로 지우는 retention cleanup을 두지 않고 상태만 정리한다. **방 자체가 사라질 때는 다르다** — `M1-038`이 `chat_rooms`를 물리 삭제하는 경우에는 종속 데이터로 함께 지운다(아래 삭제 순서). 방·날짜당 최대 한 행이라 양이 문제되지 않고, 지우면 두 가지가 함께 깨진다.

- **재접속 복구의 기준점.** `CANCELED`·`EXPIRED` 행을 지우면 `latestSuggestionId`·`latestSourceDate`·`latestEventVersion`을 만들 수 없어, 지연된 옛 이벤트가 제안을 되살리는 것을 막지 못한다.
- **배치 멱등성.** `UNIQUE (room_id, source_date)`가 곧 "그날 그 방은 이미 처리했다"는 근거다. `SKIPPED` 행을 지우면 다음 배치가 같은 방을 다시 집는다.

여기서 말하는 것은 **retention cleanup**이며 방 삭제와는 범위가 다르다. `CONFIRMED`는 카드가 provenance로 참조하므로 FK를 `RESTRICT`로 둔다. 나중에 삭제가 필요해지면 **클라이언트 재접속 지평과 배치 재시도 기간보다 오래 보존**한다는 조건을 먼저 정한다. `M2-009`에 이 정책을 명시한다.

#### 허용 상태값을 DB 제약으로 묶는다 — 카드 테이블 선례를 따른다

CHECK가 막아 주는 것은 **허용되지 않는 `status` 값**이지 잘못된 전이가 아니다. `ACTIVE → READY` 같은 역행은 CHECK로 못 막으므로 **전이의 적법성은 서비스 로직과 행 잠금이 정본**이다. 그 전제 위에서, 위 상태 목록은 문서상의 약속이 아니라 **CHECK 제약**이어야 한다. `meeting_cards`가 이미 그렇게 하고 있고, 같은 수준을 맞춘다.

| 선례 | 제안 테이블에 필요한 것 |
|---|---|
| `ck_meeting_card_status CHECK (status IN ('OPEN','CANCELED'))` | 제안의 7개 상태도 CHECK로 강제한다. 애플리케이션 enum만 믿지 않는다 |
| `ck_meeting_card_cancel` — 취소 상태와 취소 흔적(`canceled_by_pet_id`·`canceled_at`)이 **항상 함께** 성립하도록 강제. 주석: "상태만 바꾸고 `canceled_by_pet_id`를 비워두는 반쪽 취소를 DB가 막는다" | `CANCELED`에도 **취소 주체와 시각**이 필요하다. 차단으로 인한 취소는 차단을 실행한 Pet을 주체로 기록한다(카드와 동일). 상태와 흔적을 묶는 CHECK를 함께 건다 |
| `ck_meeting_card_cancel`과 같은 방식으로 **`SKIPPED`와 `skip_reason`도 CHECK로 묶는다** — `status = 'SKIPPED'`면 `skip_reason IS NOT NULL`, 아니면 `IS NULL`. 없으면 사유 없는 `SKIPPED`나 `READY`인데 사유가 붙은 행이 들어갈 수 있다 | 아래 사유 값과 함께 `M2-009`에서 제약까지 만든다 |
| `card_drafts.fallback_reason` + `CardDraftFallbackReason{TIMEOUT, MODEL_ERROR, INSUFFICIENT_CONTEXT}` | **`SKIPPED`에도 사유 컬럼이 필요하다.** 사유가 없으면 "AI가 죽어서 안 만든 날"과 "약속 얘기가 없어서 안 만든 날"을 구분할 수 없어 배치를 운영할 수 없다. 사유 값은 최소 다섯 갈래가 필요하다 — **약속 없음**(AI 정상·추출 없음), **AI 타임아웃**, **AI 오류**, **자격 변동**(공개 직전 재검사 실패), **공개 시각 놓침**(AI가 늦게 끝났거나 공개 작업이 돌지 않음). 뒤의 둘은 서로 다른 사건이므로 한 값으로 묶지 않는다. **기존 enum 재사용만으로는 부족하다.** `CardDraftFallbackReason`은 `TIMEOUT`·`MODEL_ERROR`·`INSUFFICIENT_CONTEXT` 셋뿐인데, "AI가 정상 응답했으나 약속이 없었다"는 경우는 `AiDraftResult.empty()`이며 `fallbackReason`이 `null`이다. 배치에서 가장 흔할 이 경우를 담을 값이 없으므로 `M2-009`에서 값을 추가하거나 별도 enum을 만든다 |

위 세 가지는 `M2-009`의 산출물에 포함한다.

#### 방 물리 삭제 경로에 새 테이블을 반드시 등록한다

**이걸 빠뜨리면 기존 수명주기 배치가 런타임에 깨진다.** `chat_rooms`를 참조하는 테이블 중 `ON DELETE CASCADE`가 걸린 것은 `chat_room_participants`와 `chat_messages` 둘뿐이다. 나머지는 `ChatRoomLifecycleTransactionService`가 순서대로 직접 지운다.

```java
chatMessageRepository.deleteByRoomId(...)
meetingParticipantRepository.deleteByRoomId(...)
meetingCardRepository.deleteByRoomId(...)
cardDraftRepository.deleteByRoomId(...)
participantRepository.deleteByRoomId(...)
chatRoomRepository.delete(room)
```

`meeting_card_suggestions.room_id`에 FK를 걸면서 이 목록에 넣지 않으면, 무응답 인사 방을 지우는 순간 **FK 위반으로 배치 트랜잭션이 실패한다.** `M2-009`에서 **삭제 순서에 명시적으로 추가한다.** `ON DELETE CASCADE`보다 기존 lifecycle 코드에 줄을 넣는 쪽이 낫다 — 순서가 눈에 보여야 하기 때문이다.

```
chatMessage → meetingParticipant → meetingCard
→ suggestionApproval → meetingCardSuggestion      ← 여기
→ cardDraft → participant → chatRoom
```

**`meeting_cards.source_suggestion_id`가 `RESTRICT`이므로 카드를 먼저 지워야 한다.** 현재 lifecycle이 이미 `meetingCard`를 앞에서 지우므로 그 뒤에 두 줄을 넣으면 된다. 동의 테이블은 제안에 종속되므로 제안보다 먼저다.

> 참고로 **신고된 방을 삭제 대상에서 빼는 규칙은 정책이자 구조적 필연**이다. `reports.room_id`는 CASCADE도 아니고 위 삭제 목록에도 없으므로, 신고 이력이 있는 방을 지우려 하면 어차피 FK 위반이 난다.

이 분리로 `card_drafts.requested_by_pet_id`를 nullable로 바꿀 필요가 없어지고, 하루 1건 제한이 `UNIQUE (room_id, source_date)`로 DB에서 보장되며, 공개 전·공개 후·만료를 상태로 표현할 수 있다.

#### 공동 편집과 동의는 revision 기반

```
meeting_card_suggestion_approvals
  suggestion_id, pet_id, approved_revision, approved_at
  UNIQUE (suggestion_id, pet_id)
```

**A가 👍한 뒤 B가 시각이나 장소를 고치면 A의 동의는 무효다.** 수정 시 `revision`을 올리고, 양쪽의 `approved_revision`이 현재 `revision`과 같을 때만 확정한다. 수정 요청에도 클라이언트가 현재 `revision`을 실어 보내게 해서, 오래된 화면에서의 수정은 `409`로 막는다.

**`PATCH`도 `approve`와 같은 방식으로 잠근다.** revision을 "읽고 비교한 뒤 UPDATE"하는 순진한 구현은 두 사용자가 같은 revision을 보고 동시에 수정하면 둘 다 통과해 한쪽 수정이 덮인다. 더 나쁜 경우는 `PATCH`가 `ACTIVE`/rev4를 읽은 사이 `approve`가 `CONFIRMED`로 확정하고, 뒤늦은 `PATCH` UPDATE가 **이미 확정된 제안을 수정**하는 것이다. 따라서 `PATCH`도 **`InteractionPairLock` → 참여·차단 재검사 → 제안 행 `FOR UPDATE` → `status`·`expires_at`·`revision` 재검사 → 수정과 `revision + 1`**을 한 트랜잭션에서 끝낸다. `approve`와 같은 순서이며, 여기서도 pair lock이 먼저다.

**확정 카드의 `creator_pet_id`를 정해야 한다.** `MeetingCard.creator_pet_id`는 `nullable = false`이고 `MeetingCardResponse.creatorPetId`로 노출되며, 방에 게시되는 CARD 메시지도 PET 발신이어야 한다. 공유 제안은 양쪽 동의로 확정되므로 "만든 사람"이 없다. 규칙을 이렇게 고정한다.

> **두 번째로 현재 revision에 동의한 Pet**을 `creatorPetId`이자 카드 메시지 발신자로 기록한다. UI는 `creatorPetId`를 "제안자"로 해석하지 않고, `participantPetIds` 기준으로 "공동 확정"으로 표시한다.

이 문장이 없으면 `M2-011` 구현자가 임의로 한쪽을 고르게 된다.

#### 확정은 `approve` 트랜잭션 하나로 끝낸다

**잠금 순서를 하나로 고정한다.** `approve`는 제안 행과 interaction pair 두 개를 잠근다. 차단 처리는 pair를 먼저 잠근 뒤 제안을 `CANCELED`로 바꾸므로, `approve`가 반대 순서로 잡으면 데드락이다. **pair lock이 항상 먼저다.**

```
requireParticipant 사전 검사
→ InteractionPairLockService.lockInteractionPair      ← 먼저
→ requireLockedActor · 참여자 · 차단 재검사
→ 제안 행 FOR UPDATE                                  ← 나중
→ roomId · ACTIVE · expiresAt · revision 재검사
→ (확정으로 이어질 차례면) 양쪽 자격 재검사
→ 동의 upsert
→ (양쪽 일치 시) 카드 생성 + CONFIRMED
→ commit
```

`approve` 요청은 위 순서를 지키는 **하나의 트랜잭션**에서 아래를 모두 검증하고, 동의를 upsert하며,

카드 확정이 초안에 대해 하는 검사(`MeetingCardService.resolveDraftId`)를 제안에 맞게 옮긴 것이다. 그쪽 주석이 이유를 적어 두었다 — "남의 초안이나 다른 방 초안으로 카드를 만들 수 있으면 초안에 담긴 대화 추출 결과가 엉뚱한 방으로 새어 나간다."

1. **제안의 `room_id`가 경로의 `roomId`와 일치**할 것. 초안 쪽과 같은 이유다.
2. **상태가 `ACTIVE`일 것.** `READY`(공개 전)·`EXPIRED`·`CANCELED`·이미 `CONFIRMED`인 제안에는 동의가 들어갈 수 없다. `revision`만 보면 만료·취소된 제안에도 동의가 성립한다.
3. **`expires_at > now`일 것.** 상태 검사만으로는 부족하다 — 만료를 조회 시점으로 판정하기로 했으므로 **자정이 지나도 DB의 `status`는 `ACTIVE`로 남는다.** 그 상태에서 API를 직접 호출하면 만료된 제안을 수정하고 확정까지 할 수 있다. **`PATCH`에도 같은 검사를 건다.**
   - **여기서 `EXPIRED`로 바꾸지 않는다.** 상태를 바꾼 뒤 거부 예외를 던지면 `@Transactional` 기본 규칙상 `RuntimeException`에서 트랜잭션 전체가 롤백되어 **그 전이도 함께 사라지고 행은 다시 `ACTIVE`로 남는다.** 공개 경로에서 자격 검사 실패를 예외로 처리하면 안 되는 것과 같은 이유다.
   - 요청 경로는 **거부만** 한다. `ACTIVE`인데 `expires_at`이 지난 행을 `EXPIRED`로 정리하는 것은 느슨한 정리 배치의 몫이다. 만료의 정본은 이미 `expires_at`과 REST 조회이므로 이 분담으로 충분하다.
4. **아직 카드로 쓰이지 않았을 것.** `source_suggestion_id UNIQUE`가 최종 방어선이지만, 사용자에게 제약 위반 대신 명시적 오류를 주려면 애플리케이션에서 먼저 검사한다(초안 쪽 `existsBySourceDraftId`와 같은 구조).
5. **현재 `revision`과 일치**할 것.

"남의 제안" 검사는 초안과 다르다 — 제안은 방 공유 리소스라 요청자 개념이 없으므로, `requestedByPetId` 대응 검사 대신 **호출자가 그 방의 참여자인지**(`requireParticipant`)로 대체한다.

이어서 두 참여자의 `approved_revision`이 현재 `revision`과 일치하면 **같은 트랜잭션에서** 카드 생성과 `CONFIRMED` 전이까지 완료한다.

**이 검사는 동의를 저장하기 전에 한다.** 저장한 뒤 검사해서 실패하면 "양쪽이 승인했는데 확정되지 않은" 행이 남고, no-op 규칙(이미 동의한 Pet의 재호출은 아무것도 하지 않음) 때문에 **다시 확정을 촉발할 방법이 없어진다.** 그래서 이번 `approve`가 확정으로 이어질 차례라면 — 상대의 동의가 이미 현재 `revision`에 유효하다면 — **자격 검사를 먼저 하고 통과했을 때만 upsert와 확정을 진행한다.** 검사 실패는 이 요청 전체를 실패시키고, 이번 동의도 저장하지 않는다. 그래야 상대가 자격을 회복한 뒤 다시 눌러 확정할 수 있다.

**확정 전에 양쪽 자격을 본다.** 호출자만 검사하면 아래가 통과한다 — A가 rev3에 동의한 뒤 **Pet을 바꿔 그 방 자격을 잃었는데**, B가 동의하면 A의 승인 행은 그대로 있으니 양쪽 `approved_revision`이 일치해 확정된다. 이는 이 문서가 §2.3에서 선언한 "한쪽이 Pet을 바꾸면 확정될 수 없다"와 정면으로 어긋난다. 카드 생성 직전에 **두 참여자 모두**에 대해 확인한다.

- 양쪽 User가 활성일 것
- 양쪽 참여 Pet이 활성이고 삭제되지 않았을 것
- **각 User의 `active_pet_id`가 그 방의 참여 Pet과 일치할 것**
- 차단 관계가 없을 것
- 인사 답변 게이트 통과

하나라도 어긋나면 확정하지 않는다. 이 검사는 pair lock과 제안 행 잠금 안에서 수행한다. **별도의 후속 confirm 트랜잭션을 두지 않는다.** 나누면 두 번째 동의와 후속 확정 사이에 수정 요청이 끼어들 틈이 생긴다. "두 번째로 동의한 Pet"의 판정도 이 잠금 안에서 이뤄지므로 결정론적이다.

**재사용하는 것은 확정 경로의 "순서"이지 `confirm()` 진입점이 아니다.** `MeetingCardService.confirm()`은 `requireParticipant` 사전 검사 → `InteractionPairLockService.lockInteractionPair` → `requireLockedActor` → 잠금 이후 `requireParticipant`·`requireGreetingReplyCompleted` 재검사 순서로 동작한다. 공유 제안의 확정도 **같은 잠금과 차단·참여자 재검사를 같은 순서로 수행한다.** 이 잠금을 건너뛰면 "양쪽 동의로 확정 시작 ↔ 상대가 차단" 경합에서 차단 이후에 카드가 생길 수 있다.

**그러나 `confirm()`을 그대로 호출할 수는 없다.** 확인해 보면 자리가 없다.

- `confirm()`은 `new MeetingCard(roomId, actor.petId(), sourceDraftId, cardType, placeText, meetAt)`로 저장한다. **엔티티 생성자에 `source_suggestion_id` 자리가 없다.**
- 진입점도 `MeetingCardCreateRequest`(`roomId, draftId, cardType, placeText, meetAt`)를 받는 공개 REST용이라 제안 확정 흐름과 맞지 않는다.

**고치는 것은 엔티티까지이고 기존 요청 DTO는 건드리지 않는다.** `MeetingCardCreateRequest`에 `suggestionId`를 넣으면 `{"draftId": 10, "suggestionId": 20}` 같은 무의미한 조합을 외부에 새로 열게 된다. UC-07은 이미 자기 엔드포인트(`POST .../card-suggestions/{suggestionId}/approve`)를 가지므로 **`suggestionId`는 경로에서 오고 서버 내부에서 카드를 만든다.**

- `MeetingCard` 엔티티·생성자(또는 팩토리)에 `sourceSuggestionId` 추가 — `M2-009`
- 제안 확정 전용 내부 경로(예: `confirmSuggestion(...)`) 추가 — `M2-011`
- `MeetingCardCreateRequest`는 **무변경**

이쪽이 "M1 계약을 최소로만 건드린다"는 이 문서의 원칙과도 맞는다.

두 가지는 그대로 맞아떨어진다.

- `confirm()`이 `actor.petId()`를 creator이자 CARD 메시지 발신자로 쓴다. 제안 확정은 **두 번째 동의자가 호출자**이므로 §2.3의 `creatorPetId` 규칙이 추가 작업 없이 성립한다.
- CARD 메시지의 멱등 키가 `"meeting-card:" + card.getId() + ":created"`로 결정적이다. 제안 확정도 같은 규칙을 그대로 쓴다.

#### 제안을 `chat_messages`로 저장하지 않는다

"채팅방 안에 보인다"는 UI 요구이지 `chat_messages` 행이어야 한다는 뜻이 아니다. 별도 리소스를 채팅 화면 안에서 카드 형태로 렌더링한다. `MessageType`에 값을 추가하지 않는다.

```
GET   /chat/rooms/{roomId}/card-suggestions/active
PATCH /chat/rooms/{roomId}/card-suggestions/{suggestionId}
POST  /chat/rooms/{roomId}/card-suggestions/{suggestionId}/approve
```

실시간 이벤트도 채팅 메시지 이벤트와 분리한다 — `CARD_SUGGESTION_PUBLISHED`, `UPDATED`, `APPROVED`, `CONFIRMED`, **`CANCELED`**, 그리고 `EXPIRED`*(예약 — M2에서는 발행을 보장하지 않는다)*. `CANCELED`가 필요한 이유는 공개 이후 차단이 `ACTIVE → CANCELED`를 일으키기 때문이다 — 이벤트가 없으면 접속 중인 상대 화면에 제안이 그대로 남아 있다가 다음 REST 조회에서야 사라진다. 반면 `SKIPPED`는 공개 전 내부 상태라 사용자가 본 적이 없으므로 이벤트를 보내지 않는다.

**일반 이벤트의 수신자는 REST 접근 가능 집합과 같다.** `PUBLISHED`·`UPDATED`·`APPROVED`·`CONFIRMED`는 **그 DIRECT 방을 지금 REST로 조회할 수 있는 양쪽 User의 모든 활성 세션**에 보낸다(`06_M2_WebSocket_계약.md` §6.3과 같은 기준). Pet을 바꿔 그 방을 못 보게 된 사용자에게 계속 보내면 **REST로는 안 보이는 리소스가 WebSocket으로는 오는** 상태가 된다.

**`CANCELED`는 수신자 규칙이 다르다.** DIRECT 메시지의 수신자 정본(`06_M2_WebSocket_계약.md` §6.3)은 **차단 관계인 User를 제외**한다. 차단 때문에 `CANCELED`가 된 제안에 그 규칙을 그대로 쓰면 **수신자가 0명이 되어 이벤트를 만든 의미가 사라진다** — 화면에 뜬 제안이 그대로 남는다.

그래서 이 이벤트는 **이미 화면에 노출된 리소스를 지우라는 tombstone**으로 다루되, **차단을 실행한 User에게만 보낸다.**

양쪽에 보내면 기존 차단 은폐 정책과 정면으로 어긋난다. 이 프로젝트는 차단을 `BLOCKED_USER`가 아니라 `404 CHAT_ROOM_NOT_FOUND`로 숨긴다. 그런데 `CANCELED`의 사실상 유일한 발생 원인이 차단이므로, payload에 이유를 빼더라도 **차단 직후에 제안이 사라지는 타이밍 자체가 차단을 알려준다.** "차단 사실을 싣지 않는다"만으로는 은폐가 되지 않는다.

| 대상 | 처리 |
|---|---|
| 차단을 실행한 User | tombstone 즉시 수신 → 화면에서 제거 |
| 차단당한 User | **실시간 이벤트를 보내지 않는다.** 이후 REST 조회·`PATCH`·`approve`에서 기존 차단 정책대로 `404`를 받는다 |

차단당한 쪽 화면에 잠시 낡은 제안이 남지만, **차단 이후의 정본을 REST에 두는 것은 DIRECT 채팅이 이미 쓰는 방식**이라 일관된다.

payload에서 **차단 사실이나 상대 정보는 빼되, 신선도 값은 뺄 수 없다.** tombstone이야말로 역순 도착의 대표 사례라 — `CANCELED`를 먼저 받아 지운 뒤 늦은 `PUBLISHED`가 오면 다시 그린다 — `eventVersion` 없이는 판정이 안 된다. 버전 값은 차단과 무관하므로 실어도 아무것도 새지 않는다.

```json
{
  "eventType": "CARD_SUGGESTION_CANCELED",
  "suggestionId": 123,
  "roomId": 456,
  "sourceDate": "2026-08-08",
  "status": "CANCELED",
  "revision": 3,
  "eventVersion": 5
}
``` WebSocket이 끊긴 구간은 `GET .../card-suggestions/active`로 복구한다.

**destination도 분리한다.** `06_M2_WebSocket_계약.md` §2는 `/user/queue/chat/messages`에 `CHAT_SEND_ACK`와 `CHAT_MESSAGE_CREATED` 두 종류만 흐른다고 정했다. 제안은 채팅 메시지가 아니므로 그 큐에 세 번째 타입으로 얹지 않고 `/user/queue/card-suggestions`를 따로 둔다. **이 destination과 payload, REST 복구 경로는 `M2-012` 계약에서 확정하며 PR #54(`06` 문서)에는 넣지 않는다** — DIRECT WebSocket 계약이 아직 리뷰 중이라 UC-07 설계에 묶이면 안 된다.

이 결정으로 `MessageType`·`ck_chat_message_payload`·`ChatMessageResponse`·`06_M2_WebSocket_계약.md` §6.2가 모두 무변경이고, `activateAndTouchLastMessageAt()`을 호출하지 않으므로 ARCHIVED 방이 되살아나는 문제도 사라진다. 만료 때 채팅 메시지를 물리 삭제하는 이상한 동작도 없다.

#### 스케줄은 2단계

```
대화 기준일 D:      D 00:00 ~ D+1 00:00 (Asia/Seoul)
후보 수집·AI 처리:  D+1 00:05 ~ 06:59   → READY
공개:               D+1 07:00           → ACTIVE
만료:               D+2 00:00           → EXPIRED
```

**07:00을 두 가지로 나눠서 본다 — 생성 마감과 공개 실행 시작이다.** 둘을 하나로 다루면 공개가 통째로 무너진다.

**① 생성 마감 (07:00, 절대)**

- AI 결과를 저장하는 시점에 이미 07:00이 지났으면 `READY`로 만들지 않고 **`SKIPPED`(공개 시각 놓침)** 으로 종결한다. 06:59:50에 호출해 07:00:15에 응답한 건은 그날 공개하지 않는다.
- 애초에 **`07:00 − AI 하드 타임아웃 − 여유`**(이하 *claim cutoff*) 이후에는 새 후보를 claim하지 않는다. 끝내지 못할 일을 시작하지 않는다.

**② 공개 실행 (07:00에 시작, 완료까지 시간이 걸린다)**

- 대상은 **07:00 이전에 `READY`가 된 행**이다. 공개 작업은 07:00에 시작하지만 수백 건을 한순간에 커밋할 수는 없다.
- **개별 행을 처리하는 시점이 07:00을 넘겼다는 이유로 `SKIPPED` 처리하지 않는다.** 그렇게 짜면 07:00:00.000 이후 처리되는 거의 모든 행이 탈락한다. 판정 기준은 행 처리 시각이 아니라 **그 실행(run)이 제때 시작했는가**다.
- 07:00 실행 자체가 돌지 않아 장애 복구 후 뒤늦게 시작된 실행이라면, 그 실행이 집는 `READY`는 **`SKIPPED`(공개 시각 놓침)** 으로 닫는다. 지난 아침의 제안을 오후에 띄우지 않는다.
- **공개 처리에도 상한(`publishDeadline`)을 둔다.** "제때 시작했는가"만 보면, 07:00에 정상 시작한 실행이 중간에 멈췄다 11:00에 되살아나 남은 제안을 그때 공개할 수 있다. 그러면 "지난 아침 제안을 나중에 띄우지 않는다"는 원칙이 다른 경로로 무너진다. 전이 허용 구간을 **`07:00 ≤ now < publishDeadline`** 으로 닫고, 그 뒤에 남은 `READY`는 `SKIPPED`(공개 시각 놓침)로 정리한다.
- **이것은 AI grace window가 아니다.** 07:00 이후에 끝난 AI 결과는 여전히 무조건 `SKIPPED`다. `publishDeadline`은 **07:00 전에 이미 완성된 `READY`들을 서버가 처리하는 데 필요한 운영상의 시간창**일 뿐이다.
- `publishDeadline`의 실제 폭은 설정값으로 두고 `M2-012`에서 확정한다.

자정에 바로 사용자에게 보이는 게 아니라 `READY`로 만들어 두고 07:00에 `ACTIVE`로 전환한다. AI 호출량 때문에 새벽 처리 시간이 길어져도 공개 시각을 지키기 위해서다. 기획 산출물의 와이어프레임에는 `배치 08:00` 표기가 있으나 **공개 시각은 07:00으로 확정**한다. 시각·경계는 코드 상수가 아니라 설정값으로 둔다.

**메시지는 시간순으로 넘긴다.** `CardDraftService.loadSourceMessages`는 **최신순**으로 가져오고, `toCommand`가 `Collections.reverse`로 뒤집어 시간순으로 AI에 넘긴다. 주석이 이유를 적어 두었다 — "AI는 '내일 저녁'처럼 앞선 발화를 참조하는 표현을 읽으므로 순서가 뒤집히면 추출이 틀어진다." 배치가 조회 메서드만 재사용하고 뒤집기를 빠뜨리면 **예외 없이 잘못된 약속이 추출된다.** 수집 방식이 달라져도 AI에 들어가는 순서는 시간순임을 보장한다.

**장소 문자열은 잘라서 넣는다.** `place_text`는 `VARCHAR(500)`이고, 버튼식은 `truncatePlace`로 초과분을 자른다(예외 대신). 배치도 같은 처리를 해야 AI가 긴 장소를 반환했을 때 저장이 실패하지 않는다.

**AI 기준 날짜는 반드시 `source_date`를 넘긴다.** 현재 `CardDraftService`는 `LocalDate.ofInstant(clock.instant(), SEOUL)`을 `AiDraftCommand.referenceDate`로 전달하고, 그 필드의 정의 자체가 "Asia/Seoul 기준 오늘"이다. 이 경로를 그대로 배치에 쓰면 D+1 새벽에 D 대화를 처리할 때 "내일 6시"가 하루 밀린다.

#### 만료는 조회 시점 기준

`expires_at <= now`이면 API가 반환하지 않고 프론트도 `expiresAt`에 맞춰 감춘다. **정확히 자정에 지우는 두 번째 스케줄러는 필수가 아니다.** DB 정리는 느슨한 주기 배치로 충분하고, **`CARD_SUGGESTION_EXPIRED`는 M2에서 보장하지 않는다.** 만료의 정본은 `expires_at`과 REST 조회이며, 프론트는 그 값으로 스스로 감춘다. 이벤트는 이름만 예약해 두고, 정확한 만료 push가 필요해지면 그때 활성화한다. 보장하지 않는 이벤트를 프론트가 기다리게 만들지 않는다.

#### "알림 1회"는 인앱으로 한정

두 가지를 구분한다 — ① 채팅방 안 제안 1건 표시 + 접속 중 WebSocket 이벤트, ② OS Push. **M2는 ①로 한정한다.** 오프라인 사용자는 방에 들어올 때 REST로 확인한다. OS Push는 토큰·권한·FCM/APNs·실패 재시도가 딸린 별도 기능이라 필요해지면 별도 작업으로 뺀다(`M2-013` 후보).

WebSocket은 정확히 한 번 전달을 보장하지 못한다. 서버가 제안 리소스를 한 번만 생성하고 클라이언트가 `suggestionId`로 중복을 제거하는 방식이어야 한다.

**중복 제거만으로는 부족하다 — 순서가 뒤집힐 수 있다.** DB 트랜잭션은 잠금으로 직렬화되지만, `AFTER_COMMIT`은 각 트랜잭션이 커밋된 뒤 리스너를 돌린다는 뜻이지 **서로 다른 요청·스레드에서 나간 이벤트의 도착 순서까지 보장하지 않는다.** 최악의 경우 클라이언트가 `CANCELED`를 먼저 받아 제안을 지우고, 늦게 도착한 `PUBLISHED`를 보고 **다시 그린다.** `UPDATED ↔ APPROVED`, 확정 시점의 `CARD_SUGGESTION_CONFIRMED ↔ CHAT_MESSAGE_CREATED(type=CARD)`에도 같은 문제가 있다.

**순서의 정본은 `revision`이 아니라 별도의 `event_version`이다.** `revision`은 사용자가 내용을 고칠 때만 오르므로 `PUBLISHED`·`APPROVED`·`CONFIRMED`·`CANCELED`에서는 값이 그대로다 — 같은 `revision`을 단 이벤트가 여럿 생겨 순서를 가릴 수 없다. 셋의 역할을 나눈다.

| 값 | 역할 |
|---|---|
| `revision` | 사용자가 고친 **내용**의 낙관적 동시성 |
| `event_version` | 그 제안에서 일어난 **클라이언트 관찰 가능 변경의 순서** |
| `updated_at` | 표시·감사용 시각 |

`event_version`은 **상태가 바뀌었는지와 무관하게, 클라이언트가 관찰할 수 있는 변경마다** 1 올린다. "상태 변화마다"로 정의하면 첫 번째 `approve`가 빠진다 — 그때는 동의 행만 저장되고 `status`는 `ACTIVE` 그대로여서, 버전이 안 오르면 `PUBLISHED`와 같은 값이 되고 프론트 규칙에 걸려 **`APPROVED`가 통째로 버려진다.** 증가와 도메인 변경은 같은 트랜잭션에서 하고, 증가된 값을 `AFTER_COMMIT` 이벤트에 싣는다. `UPDATED`만 `revision`도 함께 올린다.

**확정시키는 마지막 `approve`는 `CONFIRMED`만 발행한다.** `APPROVED`와 `CONFIRMED`를 같은 트랜잭션에서 둘 다 보내면 버전이 같아 하나가 버려지고, 버전을 둘로 나누면 한 번의 동작이 두 이벤트가 된다. `CONFIRMED`가 더 강한 종결 상태이므로 그것만 보낸다.

| 동작 | `event_version` | 발행 |
|---|---|---|
| `READY → ACTIVE` 공개 | +1 | `PUBLISHED` |
| `PATCH` 수정 | +1 (`revision`도 +1) | `UPDATED` |
| 첫 번째 `approve` | +1 | `APPROVED` |
| 두 번째 `approve`(확정) | +1 | **`CONFIRMED`만** |
| 차단으로 취소 | +1 | `CANCELED` |
| `GENERATING`·`READY` 내부 변화 | 올리지 않음 | 없음 (사용자가 본 적 없음) |

**실질 변경이 없으면 아무것도 하지 않는다.** 이미 현재 `revision`에 동의한 Pet이 `approve`를 다시 눌러도 승인 행이 그대로이므로 버전을 올리지 않고 이벤트도 보내지 않는다. `PATCH`도 마찬가지다 — 보낸 값이 기존과 전부 같으면 **`revision`을 올리지 않는다.** 올려 버리면 사용자가 수정 화면에서 아무것도 바꾸지 않고 저장한 것만으로 **상대의 👍가 무효가 된다.**

**한 번의 제안 변경 = 한 번의 `event_version` 증가 = 최대 한 개의 `CARD_SUGGESTION_*` 이벤트**가 원칙이다. 확정 시 함께 나가는 `CHAT_MESSAGE_CREATED(type=CARD)`는 채팅 스트림이라 이 규칙의 대상이 아니다.

그래서 **모든 `CARD_SUGGESTION_*` payload는 `suggestionId`·`roomId`·`sourceDate`·`status`·`revision`·`eventVersion`을 싣는다.** `roomId`가 공통인 이유는 destination이 방별이 아니라 **User 단위 큐 하나**(`/user/queue/card-suggestions`)이기 때문이다. 방을 열 개 가진 사용자가 `PUBLISHED`를 받았을 때 `roomId`가 없으면 어느 방 제안인지 알 수 없고, 처음 보는 제안이라 `suggestionId → roomId` 매핑도 없다. 다만 공통 필드만으로는 화면을 갱신할 수 없는 이벤트가 있으므로, **이벤트는 무엇이 바뀌었는지도 함께 운반한다.** 매번 REST를 다시 부르게 하는 방식은 쓰지 않는다 — 👍 한 번에 왕복이 붙는다.

| 이벤트 | 공통 필드에 더해 싣는 것 |
|---|---|
| `PUBLISHED` | `cardType`·`placeText`·`meetAt`, 그리고 **`expiresAt`** — 만료를 프론트가 `expiresAt`으로 스스로 감추기로 했고 `EXPIRED` 이벤트는 보장하지 않으므로, 이 값이 없으면 **언제 숨겨야 할지 알 수 없다** |
| `UPDATED` | 수정 후의 `cardType`·`placeText`·`meetAt`. 무엇이 바뀌었는지 알 수 없으면 의미가 없다. **프론트는 이 이벤트를 받으면 로컬 승인 표시를 전부 초기화한다** — `revision`이 올랐다는 것은 이전 revision의 승인이 모두 무효라는 뜻이므로, 이벤트마다 `approvedPetIds`를 실을 필요가 없다 |
| `APPROVED` | `approvedByPetId`(또는 현재 동의한 Pet 목록) — `status`도 `revision`도 그대로라 이 값이 없으면 **누가 눌렀는지 알 수 없다** |
| `CONFIRMED` | `meetingCardId` — 확정된 카드를 가리켜야 화면을 넘길 수 있다 |
| `CANCELED` | 없음. tombstone이므로 공통 필드로 충분하다 |

구체적 필드명은 `M2-012`에서 확정하되, **"공통 필드만 보내고 나머지는 REST로 다시 읽어라"로 미루지 않는다.** 프론트 규칙은 셋이다.

- **순서는 두 층으로 본다 — 제안 사이는 `sourceDate`, 제안 안은 `eventVersion`이다.** `event_version`은 행마다 0에서 시작하므로 방 단위로 마지막 값만 비교하면 다음 날 새 제안(`eventVersion=1`)이 어제 값(5)보다 작다고 버려진다. 그렇다고 "처음 보는 `suggestionId`면 새 제안"으로만 두면 반대 구멍이 뚫린다 — 재접속으로 메모리가 비워진 뒤 **더 오래된 제안의 지연 이벤트**가 오면 처음 보는 ID라 그대로 되살아난다.
  - `incoming.sourceDate < local.latestSourceDate` → 무시 (지난 세대)
  - `incoming.sourceDate == local.latestSourceDate` 이고 같은 `suggestionId` → `eventVersion`으로 비교, `<=`면 무시
  - `incoming.sourceDate > local.latestSourceDate` → 새 제안으로 처리
  - 같은 `sourceDate`인데 `suggestionId`가 다름 → `UNIQUE (room_id, source_date)` 위반 상황이므로 REST로 다시 맞춘다

  그래서 **`sourceDate`도 모든 이벤트와 REST 표현의 공통 필드**다. 방·날짜당 한 건이라는 UNIQUE가 곧 세대 번호 역할을 한다. PK 증가 순서를 시간 의미로 쓰는 방식은 채택하지 않는다.
- `CANCELED`·`CONFIRMED` 같은 종결 이벤트 뒤에는 그보다 오래된 `PUBLISHED`·`UPDATED`·`APPROVED`를 적용하지 않는다.
- 판단이 서지 않으면 `GET .../card-suggestions/active`로 다시 맞춘다.

**`event_version BIGINT`는 스키마이므로 `M2-009`에 넣는다.** payload를 확정하는 `M2-012`가 아니라 컬럼을 만드는 쪽이다.

**REST 응답에도 같은 값이 있어야 복구가 성립한다.** "판단이 서지 않으면 `GET .../card-suggestions/active`로 맞춘다"는 규칙은 그 응답이 몇 번째 버전인지 알아야 쓸 수 있다. 버전 없이 상태만 받으면, 재접속 직후 늦게 도착한 낮은 버전 이벤트를 버릴 근거가 없다.

`PATCH`·`approve` 성공은 **`CardSuggestionResponse`**, `GET .../active`는 **이를 감싼 `CardSuggestionActiveResponse`**를 쓴다. 이건 REST 계약이므로 `M2-012`가 아니라 **`M2-011`**의 산출물이다.

| 필드 | 비고 |
|---|---|
| `suggestionId` | |
| `roomId` | 경로에도 있지만 응답에 함께 둔다(WebSocket 이벤트와 같은 모양) |
| `sourceDate` | 제안 세대 순서. 이벤트 신구 판정에 쓴다 |
| `status` | |
| `cardType`·`placeText`·`meetAt` | **오프라인 사용자는 `PUBLISHED`를 못 받으므로 REST만으로 화면을 그릴 수 있어야 한다** |
| `expiresAt` | 프론트가 스스로 감추는 기준 |
| `revision` | |
| `eventVersion` | 재접속 복구의 기준점 |
| `approvedPetIds` | **`approved_revision`이 현재 `revision`과 일치하는 승인만** |
| `meetingCardId` | `CONFIRMED`일 때 |

**`approvedPetIds`가 없으면 재접속 후 승인 상태를 복구할 수 없다.** 오프라인이던 B가 방에 들어와 REST로 제안을 받아도 *A가 이미 👍를 눌렀는지* 알 수 없다. 더 중요한 것은 **승인 행을 그대로 내려주면 안 된다**는 점이다 — A가 rev3에 동의한 뒤 B가 수정해 rev4가 되면 A의 행은 남아 있지만 그 동의는 무효다. 서버가 현재 `revision`과 대조해 **유효한 것만** 계산해 내려준다.

**모든 타임스탬프는 `Instant`(UTC, `Z`)로 직렬화한다.** 기존 `ChatMessageResponse.createdAt`과 같은 규약이다. 이 절의 시각 정책은 전부 Asia/Seoul 기준으로 서술했으므로 **직렬화할 때 9시간을 빼야 한다** — 예를 들어 만료인 `D+2 00:00 KST`는 `D+1 15:00Z`다. `2026-08-10T00:00:00Z`로 쓰면 실제로는 **KST 09:00**이라 만료가 아홉 시간 늦어진다.

아래는 `source_date = 2026-08-08`, 공개 `2026-08-09 07:00 KST`, 만료 `2026-08-10 00:00 KST`인 경우다.

```json
{
  "suggestionId": 123, "roomId": 456, "sourceDate": "2026-08-08", "status": "ACTIVE",
  "cardType": "WALK", "placeText": "한강공원 입구",
  "meetAt": "2026-08-09T09:00:00Z",
  "expiresAt": "2026-08-09T15:00:00Z",
  "revision": 3, "eventVersion": 8, "approvedPetIds": [11]
}
```

`meetAt`은 `2026-08-09 18:00 KST`, `expiresAt`은 `2026-08-10 00:00 KST`다.

그러면 재접속 후 REST로 받은 `eventVersion=8`을 기준으로 이후 WebSocket 이벤트 중 8 이하를 버릴 수 있다.

**"활성 제안 없음"일 때도 기준점을 줘야 한다.** 서버가 `PUBLISHED v1 → UPDATED v2 → CANCELED v3`까지 갔고 클라이언트가 재접속해 `GET .../active`를 불렀다고 하자. 지금 계약대로면 "없음"만 돌아오므로 **클라이언트는 서버가 v3까지 갔다는 사실을 모른다.** 그 직후 지연됐던 `PUBLISHED v1`이 도착하면 비교할 기준이 없어 제안을 다시 그린다. 복구 규칙에 구멍이 남는 것이다.

그래서 이 응답은 활성 제안의 유무와 무관하게 **가장 최근에 클라이언트에 노출된 제안(`event_version > 0`)의 `suggestionId`·`sourceDate`·`eventVersion`**을 함께 실어야 한다.

따라서 **`GET`만 wrapper를 쓴다.** 활성 제안이 있을 때와 없을 때 응답 타입이 갈리면 OpenAPI에서 표현할 수 없다.

```
CardSuggestionActiveResponse
  suggestion         : CardSuggestionResponse | null
  latestSuggestionId : Long | null
  latestSourceDate   : LocalDate | null
  latestEventVersion : Long | null
```

```json
{ "suggestion": null, "latestSuggestionId": 123, "latestSourceDate": "2026-08-08", "latestEventVersion": 9 }
```

`PATCH`와 `approve`는 wrapper 없이 `CardSuggestionResponse`를 그대로 반환한다.

**`latestSuggestionId`·`latestEventVersion`은 `event_version > 0`인 최신 제안에서 고른다.** 단순히 `source_date` 최신 행을 집으면 barrier가 사라진다 — 자정 직후에는 그날치 `#100`이 만료된 상태에서 다음 날용 `#200`이 `GENERATING`(`event_version = 0`)으로 이미 만들어져 있을 수 있고, 그걸 집으면 응답이 `latestSuggestionId=200, latestEventVersion=0`이 되어 **사용자가 실제로 봤던 `#100 v5`의 기준점이 없어진다.**

`GENERATING`·`READY`와 한 번도 노출되지 않은 `SKIPPED`는 `event_version`이 0이므로 자연히 후보에서 빠진다. `#200`이 07:00에 `PUBLISHED v1`이 되면 프론트에는 **처음 보는 `suggestionId`**라 새 제안으로 정상 처리된다(§2.3의 비교 규칙).

#### 대상 방 조건

자정 후보 선정과 07:00 공개 직전에 **모두 재검사**한다.

- DIRECT 방이고 상태가 `ACTIVE`일 것 (ARCHIVED 방을 되살리지 않는다)
- 인사 답변 완료
- 양쪽 사용자·Pet 활성이고, **각 User의 `active_pet_id`가 그 방의 참여 Pet과 일치할 것.** 다른 펫을 활성화한 사용자는 채팅 접근 규칙상 그 방을 조회할 수 없으므로, 이 조건이 없으면 제안 이벤트는 받는데 방에서는 볼 수 없는 상태가 생긴다(`06_M2_WebSocket_계약.md` §6.3의 수신자 규칙과 같은 기준이다)
- 차단 관계 없음
- 전날 양쪽 모두 **사용자 TEXT** 대화에 참여. 정책(`02_M1_API_계약.md`)이 버튼식 활성화 조건에서 `CARD`·`SYSTEM`을 개수에서 제외하고, 구현도 `loadSourceMessages`가 `SenderType.PET` + `MessageType.TEXT`로만 조회한다. 배치의 후보 판정도 같은 기준을 쓴다 — 시스템 공지와 카드 게시만 오간 방은 대화가 있었던 것이 아니다
- **그 제안으로** 아직 카드가 만들어지지 않음(`source_suggestion_id` 기준). **방에 다른 OPEN 카드가 있는 것은 제외 조건이 아니다** — §4.1의 중복 카드 결정 참고
- AI 결과가 제안으로 쓸 수 없으면 생성하지 않음. **판정은 `AiDraftResult`가 실제로 주는 것으로만 한다 — 이 계약에는 확신도(confidence) 필드가 없다.** `fallbackReason != null`(AI 실패)이거나 `combinedInstant == null`(날짜·시각을 조합하지 못함)이면 제안하지 않는다. 시각 없는 카드는 `meeting_cards.meet_at NOT NULL`을 채울 수 없다

**공개 이후 한쪽이 Pet을 바꾸거나 지우면 그 제안은 확정될 수 없다.** `pets`는 소프트 삭제(`deleted_at`)이므로 FK와 과거 동의 기록은 그대로 남지만, 위 대상 조건의 "`active_pet_id`가 방 참여 Pet과 일치"가 깨져 그쪽은 제안을 조회할 수도 동의할 수도 없다. 상대 화면에는 계속 떠 있어 "왜 답이 없지" 상태가 된다. **이를 차단처럼 `CANCELED`로 즉시 취소하지 않고 자정 만료에 맡긴다** — 사용자가 Pet을 도로 바꾸면 그날 안에 다시 이어갈 수 있어야 하기 때문이다. 허용된 한계로 두되, 모르고 만든 구멍이 아니라 정한 것임을 남긴다.

**`READY → ACTIVE` 공개도 `approve`와 같은 잠금 순서를 쓴다.** "공개 직전 재검사"만으로는 차단과 경합한다 — 차단 없음을 확인한 뒤 차단 트랜잭션이 커밋되고, 그때 제안은 아직 `READY`라 차단 정리 대상이 아니며, 그 다음 공개가 진행되면 **차단이 끝난 뒤에 제안이 공개된다.** 순서를 고정한다.

```
InteractionPairLock                     ← pair lock이 먼저 (M2-011과 동일)
→ 제안 행 FOR UPDATE                     ← 검사보다 먼저 잡는다
→ 아직 READY인지 확인
→ 참여자 · 차단 · 활성 상태 검사
→ publishAt ≤ now < publishDeadline 재검사
   ├─ 실패 → READY → SKIPPED(사유) → commit        (예외를 던지지 않는다)
   └─ 성공 → READY → ACTIVE      → commit
                                  → AFTER_COMMIT 에 CARD_SUGGESTION_PUBLISHED 발행
```

**검사 실패를 예외로 던져 빠져나오면 `SKIPPED`를 저장할 수 없다.** `requireParticipant` 같은 기존 게이트를 그대로 호출하면 제안 행을 잠그기도 전에 트랜잭션이 끝나 버려, 문서가 말하는 "재검사에서 걸려 `SKIPPED`"가 실제로는 일어나지 않고 그 행은 `READY`로 남는다. 그래서 **잠금을 먼저 잡고, 자격 검사는 예외가 아니라 상태 전이 분기로 처리한다.** `SKIPPED` 저장은 커밋되어야 하므로 이 경로에서 롤백을 유발하지 않는다.

이러면 경계가 분명해진다 — **차단이 먼저 이기면 공개되지 않고**(재검사에서 걸려 `SKIPPED`), **공개가 먼저 이기면** 이후 차단 정리가 `ACTIVE` 제안을 `CANCELED`로 닫는다.

공개 이후 차단이 발생하면 해당 제안을 `CANCELED`로 처리한다. **범위는 Pet pair가 아니라 User 쌍이다.** 카드 도메인이 이미 같은 함정을 겪었다 — `MeetingCardRepository.cancelOpenCardsBetweenUsers`는 `pets`를 조인해 `owner_user_id` 기준으로 훑는다. 주석이 이유를 적어 두었다: "차단은 User 단위인데 방은 Pet pair 단위라, UI에서 고른 두 Pet만 보면 나머지 Pet으로 만든 카드가 살아남는다." 제안 취소도 같은 기준을 쓴다. 그러지 않으면 차단한 상대가 다른 Pet으로 참여한 방의 제안이 살아남는다.

#### 배치 중복 실행 방어는 DB가 정본

정합성은 `UNIQUE (room_id, source_date)`, 상태 전이 시 행 잠금, `source_suggestion_id UNIQUE`, 멱등 재시도로 잡는다. **Redis 분산락을 최종 방어선으로 두지 않는다** — 여러 replica가 같은 AI 호출을 중복하는 비용을 줄이는 보조 수단으로만 쓴다.

#### REST 계약을 어느 문서에 둘지 정해야 한다

UC-07은 신규 REST 3종(`GET`/`PATCH`/`approve`)을 추가한다. 그런데 **M2용 OpenAPI 문서가 없다.** 현재 정본은 `04_M1_OpenAPI.yaml` 하나이고, `README.md`의 "구현할 때 주의할 경계"는 "`04_M1_OpenAPI.yaml`에 없는 endpoint를 M1 API로 임의 추가하지 않는다", PR 리뷰 첫 단계는 "변경 endpoint가 `04_M1_OpenAPI.yaml`과 일치하는지 확인한다"이다. M2 엔드포인트가 생기는 순간 이 절차가 성립하지 않는다.

선택지는 둘이다 — ① `04_M1_OpenAPI.yaml`에 M2 경로를 함께 넣고 파일명을 바꾼다, ② M2 OpenAPI를 새로 만든다. **이 결정은 UC-07만의 문제가 아니라 `M2-003`(GROUP REST)·`M2-007`(`participants[]`)에도 똑같이 걸리므로, `M2-009` 안이 아니라 별도 Gate `G-M2-I`로 빼서 세 작업의 공통 선행으로 둔다**(§6). `M2-003`은 다른 선행이 없어 이 Gate만 통과하면 바로 착수할 수 있다.

**선행 조건:** 이 문서 §1의 정본 순서상 제품 범위는 `00_최신_제품정책.md`가 정한다. UC-07을 M2 범위로 확정하려면 그 문서의 M2 목록 갱신이 먼저이며, 이는 §6의 Gate로 관리한다.

## 3. 담당 경계

> 출처: 2026-08-07 팀 확정. 저장소에 M2 담당을 적은 다른 문서가 없으므로 **이 표가 M2 담당의 정본이다.** 배정이 바뀌면 이 표를 먼저 고친다.

| 담당 | 상태 | M2 범위 |
|---|---|---|
| BE-2 | 진행 중 | DIRECT WebSocket 계약·구현(`M2-001`·`M2-002`), 공통 WebSocket 보안(CONNECT JWT·userId Principal·오류 매핑·개인 큐 publisher), UC-07 공유 약속 제안 백엔드 전체(`M2-009`~`M2-012`), 다중 replica 대응(`M2-006`, BE-4와 공동) |
| BE-4 | 진행 중 | **실시간·그룹 쪽 주류.** Redis·Kafka 공통 초기 설정(`M2-008`, 완료), GROUP REST 도메인·참여자·나가기(`M2-003`), GROUP WebSocket·Kafka consumer·partition key(`M2-004`), `participants[]` 확장 계약(`M2-007`), 다중 replica 대응(`M2-006`, BE-2와 공동) |
| 미배정 | 대기 | Google 로그인, GPS 동네 확인, 셋로그 업로드, 지도·장소, 만남 확인, 후기·발자국, 대화 맥락 검열(`M2-005`), OS Push(`M2-013`, 필요할 때만) |

**충돌 시 규칙:** BE-4가 Redis·Kafka 공통 인프라와 GROUP 설계의 정본이다. **GROUP 수신자 규칙과 Kafka serializer·producer 재사용 여부는 BE-4가 최종 설계한다.** BE-2의 DIRECT·UC-07 구현이 BE-4의 공통 설계와 충돌하면 **BE-2가 독자 구현하지 않고 맞춘다.**

**프론트·AI는 현재 WBS상 별도 배정이 없다.** 프론트는 BE-2의 DIRECT·UC-07 REST·WebSocket 계약을 소비하는 쪽이고, AI 서버를 새로 만드는 것이 아니라 BE-2가 기존 `MeetingDraftAiClient` 인터페이스를 공통 어댑터로 정리해 쓴다.

Redis/Kafka 공통 설정(PR #55, `RedisConfig`·`KafkaProducerConfig`)은 **BE-4의 M2 작업으로 확정한다**(2026-08-07). BE-4는 M2부터 합류했으므로 이 PR은 M1 백로그가 아니다. 다만 이메일 인증·캐싱·분산락·멱등성용 Redis 4개 DB는 채팅 전용이 아니며, 특히 DB 1번의 용도인 이메일 인증은 제품 정책상 M1 미제공이고 M2 목록에도 없다(§2.2). GROUP 채팅이 이 Kafka producer를 재사용할지는 M2-004에서 별도로 결정한다(§4).

BE-2/BE-4는 `06_M2_WebSocket_계약.md` **9. BE-2 / BE-4 책임 경계**의 세부 규칙을 따른다. 공통 `SecurityConfig`·JWT interceptor·Principal·오류 큐는 BE-2가 소유하며 BE-4가 복제하지 않는다.

## 4. 상세 WBS (채팅·실시간·카드 리마인드)

| 작업 ID | 분류 | 주담당 | 선행 | 산출물·완료 기준 | 상태 |
|---|---|---|---|---|---|
| M2-001 | 계약 | BE-2 | 없음 | DIRECT WebSocket 계약 문서(`06_M2_WebSocket_계약.md`), BE-4·프론트 리뷰 | 초안 — PR #54 OPEN |
| M2-002 | 채팅 | BE-2 | M2-001 | DIRECT WebSocket Controller·`WebSocketConfig`·CONNECT JWT 인터셉터·개인 큐 발행 Gateway 구현. **destination 인가 allowlist(`06` §2.1), access token `exp` 기반 세션 예약 종료(`06` §3 항목 7), 그리고 인터셉터 거부와 STOMP 파싱 오류가 같은 `{code, message}` ERROR body를 내도록 하는 protocol error handler 구성(`06` §7)을 포함한다.** `SecurityConfig`의 `permitAll`에 `/ws`를 등록하고 STOMP 엔드포인트 allowed origin은 `CorsProperties`에서 가져온다(`06_M2_WebSocket_계약.md` §3). | 구현 진행 — PR #67 리뷰·병합 대기 |
| M2-003 | 그룹채팅 | BE-4 | **G-M2-I**(§6) | GROUP REST 도메인(Entity·참여자·나가기, REST API). WebSocket 계약과 독립적으로 먼저 설계 가능 | 미착수 |
| M2-004 | 그룹채팅 | BE-4 | M2-002, M2-003 | GROUP WebSocket destination, Kafka consumer, `roomId` partition key 확정. `M2-008`의 공용 `KafkaTemplate` 재사용 여부를 여기서 결정한다 — 현재 value serializer가 `StringSerializer`라 String 외 페이로드는 그대로 못 보낸다(`06_M2_WebSocket_계약.md` §9) | 미착수 |
| M2-005 | 검열 | 미배정 | M2-003 | 대화 맥락 검열 대상에 GROUP 포함 여부 결정, 계약 초안. 검열 기능 전체는 §2.2의 미배정 항목이며 여기서는 GROUP 경계만 다룬다 | 미착수 |
| M2-006 | 배포 | BE-2·BE-4 | M2-002, M2-004 | WebSocket 다중 replica 대응(broker relay 또는 Kafka/Redis 방식 확정) — `M2-008` 인프라 재사용 여부 포함. `deployment/local/docker-compose.yml`이 확장 출발 파일이며, 현재 구성은 단일 노드라 그 자체가 해결책이 아니다. `06_M2_WebSocket_계약.md` §1이 "BE-2와 BE-4가 먼저 확정한다"고 정했으므로 미배정으로 두지 않는다 | 미착수 |
| M2-007 | 계약 | BE-4 | M2-003, **G-M2-I**(§6) | 채팅방 응답의 `participants[]` 인라인 확장. `04_M1_API_명세.md`가 이를 M2 계약으로 예고해 두었으나 지금까지 어느 M2 문서에도 없었다. REST 응답 스키마 변경이므로 **`G-M2-I`에서 확정한 OpenAPI 정본**과 `02_M1_API_계약.md` 동기화가 함께 필요하다 | 미착수 |
| M2-008 | 인프라 | BE-4 | 없음 | Redis 4개 DB(이메일 인증·캐시·분산락·멱등성) 및 Kafka producer 공통 **초기 설정**, `deployment/local/docker-compose.yml`의 로컬 단일 노드 Redis/Kafka 구성, 애플리케이션 컨텍스트 기동 및 기존 테스트 통과(PR #55 `5bb26cb`, Backend CI 성공). 실제 broker 연결·GROUP 이벤트 직렬화 검증은 M2-004·M2-006에서 수행한다 | 완료 |
| M2-009 | 제안 | BE-2 | **G-M2-A·G-M2-I·G-M2-K**(§6) | 공유 약속 제안 제품 계약 + `meeting_card_suggestions`·`meeting_card_suggestion_approvals` 신규 스키마, `meeting_cards.source_suggestion_id`(nullable UNIQUE), **실시간 이벤트 순서용 `event_version BIGINT`**, 마이그레이션. `01_M1_통합_ERD.md`·`.sql` 동기화 포함. **`card_drafts`는 건드리지 않는다.** 착수 전 ERD 문서의 기존 drift를 먼저 정리한다(§4.2) | 미착수 |
| M2-010 | 제안 | BE-2 | M2-009 | 전날 대화 후보 선정 + AI 생성 파이프라인 — 대상 방 조건(§2.3), 사용자 컨텍스트 없는 AI 호출 경로, `source_date`를 `referenceDate`로 넘기는 경로 분리, 배치 전용 fallback(실패 시 `SKIPPED`), `GENERATING → READY`, **lease 전 구간**(최초 `INSERT ... ON CONFLICT DO NOTHING` claim → stale 회수 시 `FOR UPDATE` 재확인·lease 갱신 → 커밋 → AI 호출 → 결과 저장 시 `generation_started_at` 일치 조건으로 fencing). **claim cutoff는 최초 claim과 stale 회수 양쪽에 적용**하며, cutoff를 지난 회수는 AI를 부르지 않고 `GENERATING → SKIPPED`로 닫는다. **"아직 카드를 만들지 않음"의 판정 규칙 정의 포함.** 후보 선정 쿼리는 `V16`의 부분 인덱스 `ix_chat_rooms_active_direct_last_message (last_message_at, id) WHERE type='DIRECT' AND status='ACTIVE'`를 재사용할 수 있는지 먼저 확인한다 | 미착수 |
| M2-011 | 제안 | BE-2 | M2-009 | 공동 편집·`revision` 기반 동의·카드 확정 REST — `GET`·`PATCH`·`approve` 3종을 **여기서 모두 구현한다**(`M2-012`는 이 GET을 재사용만 한다). **`PATCH`·`approve`는 `CardSuggestionResponse`(§2.3의 필드 표)를, `GET`은 이를 감싼 `CardSuggestionActiveResponse`를 반환하고, 현재 `revision`에 유효한 `approvedPetIds`를 계산해 내려준다.** 확정 직전 양쪽 자격 재검사(§2.3)도 포함한다. 활성 제안이 없을 때도 **가장 최근에 클라이언트에 노출된 제안(`event_version > 0`)의 `suggestionId`·`sourceDate`·`eventVersion`**을 함께 반환해 재접속 복구의 기준점이 되게 한다, 오래된 revision 수정 `409`, 양쪽 `approved_revision` 일치 시에만 `CONFIRMED`. **`approve`·`PATCH` 모두 단일 트랜잭션이며 잠금 순서는 `InteractionPairLock` → 참여·차단 재검사 → 제안 행 `FOR UPDATE` → 상태·만료·revision 재검사 순이다**(pair lock이 먼저 — 차단 경로와 반대로 잡으면 데드락), `creatorPetId` 규칙(§2.3), 공개 후 차단 시 `CANCELED` 경로 포함. **기존 OPEN 카드 존재를 확정 거부 조건으로 검사하지 않는다**(§4.1) | 미착수 |
| M2-012 | 제안 | BE-2 | M2-010, M2-011, M2-002 | 07:00 공개(§2.3의 `InteractionPairLock` → 제안 `FOR UPDATE` 순서 준수)·자정 만료. **on-time publish run 판정**(행 처리 시각이 아니라 실행 시작 시각 기준), **늦게 시작한 실행이 집은 `READY`는 `SKIPPED`(공개 시각 놓침)**, **`CARD_SUGGESTION_CANCELED`는 차단 실행자에게만 발행**, **`CARD_SUGGESTION_EXPIRED`는 M2 비보장**, **모든 `CARD_SUGGESTION_*` payload에 `suggestionId`·`roomId`·`sourceDate`·`status`·`revision`·`eventVersion`을 싣고 `PUBLISHED`에는 `expiresAt`까지 실어, 역순 도착을 무시하고 만료 시점을 프론트가 알 수 있게 한다**(`CANCELED` tombstone도 공통 필드는 예외가 아니다), **`publishDeadline` 이후 남은 `READY`는 `SKIPPED`**. **REST 복구 경로는 `M2-011`의 `GET .../card-suggestions/active`를 그대로 쓰며, 여기서는 끊긴 구간을 그 GET으로 메우는 동작만 검증한다**(새 엔드포인트를 만들지 않는다). 공개 직전 대상 조건 재검사, 만료는 조회 시점 기준. **`/user/queue/card-suggestions` destination과 `CARD_SUGGESTION_*` payload를 이 작업의 계약으로 확정**(`06` 문서에는 넣지 않음). **`M2-002`가 만든 inbound `SUBSCRIBE` allowlist에 이 destination을 추가하고 인가 테스트도 함께 넣는다** — 기본 거부라 추가하지 않으면 프론트 구독이 거절된다 | 미착수 |
| M2-013 | 알림 | 미배정 | M2-012 | OS Push (토큰·권한·FCM/APNs·실패 재시도). **M2 필수 아님** — 필요해지면 착수하며 제품 정책 갱신이 선행 | 후보 |

`M2-008`은 채번 순서상 뒤에 있을 뿐 실제로는 다른 M2 작업보다 먼저 머지됐다. 번호는 발견 순서이며 실행 순서를 뜻하지 않는다.

`M2-008`의 "완료"는 위 범위 한정이다. 신규 테스트 파일은 없지만 `DogetherApplicationTests.contextLoads()`가 `@SpringBootTest`로 컨텍스트를 올리고 PR에서 `application-test.yaml`을 Redis·Kafka에 맞춰 함께 고쳤으므로, bean 생성과 설정 바인딩은 실제 테스트 경로를 통과했다. 다만 test 프로파일은 Redisson autoconfiguration을 제외하므로 검증 범위는 거기까지이며, broker 실연결은 포함하지 않는다.

**`M2-008`에 남은 하자 두 가지.** 완료 판정은 유지하되 후속으로 처리한다(§8).

1. **기본값 없는 필수 env가 `.env.example`에도 prod 프로파일에도 없다.** `app.redis.host: ${REDIS_HOST}`, `app.redis.port: ${REDIS_PORT}`, `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVER}` 셋 다 기본값이 없다. PR #62에서 `REDIS_HOST`·`REDIS_PORT`는 `.env.example`에 추가됐으나 **`KAFKA_BOOTSTRAP_SERVER`는 아직 없다.** `application-prod.yaml`도 이 셋을 재정의하지 않아 base 설정을 그대로 상속하므로, prod에서 해당 환경변수 없이 뜨면 placeholder를 해결하지 못해 **애플리케이션이 기동하지 않는다.**
2. **compose 두 개가 서로를 보완하지 못한다.** 루트 `docker-compose.yml`에는 백엔드(`app`)가 있지만 Redis·Kafka 서비스도, 그 환경변수도 없다. Redis·Kafka가 있는 `deployment/local/docker-compose.yml`에는 백엔드가 없다. 둘을 따로 띄우면 네트워크가 갈린다.

**"Backend CI 성공"의 범위도 좁다.** `dev` PR을 검사한 것은 `ci-test.yml`의 `./gradlew test`이며, 이 태스크는 `excludeTags 'postgres', 'rustfs'`로 통합 테스트를 제외한다. `postgresTest`를 함께 돌리는 `ci.yml`은 `main` push 전용이라 이 PR에서는 동작하지 않았다(§7 위험).

참고(WBS 항목 아님): `M1-038`(채팅방 수명주기, PR #50 완료)은 M2 신규 작업이 아니라 M1 종료 후 뒤늦게 완료된 기존 M1 백로그다. §2.1에서 별도로 설명한다.

### 4.1 UC-07 설계 결정으로 해소된 충돌과 남은 과제

`d27f3ac` 기준 실측이다. 처음 16건을 뽑았고 설계·검토를 진행하며 3건이 더 드러나 **모두 19건**이며, §2.3의 "별도 도메인 분리" 결정으로 **11건이 해소됐다.** 남은 8건은 이후 검토와 결정을 거치며 **설계가 확정된 5건과 아직 결정이 필요한 3건으로 갈렸다** — 아래 대조표 다음에 나눠 적는다.

**해소됨 — 별도 테이블 분리(§2.3)**

| 원래 충돌 | 해소 방식 |
|---|---|
| `card_drafts.requested_by_pet_id`가 `NOT NULL REFERENCES pets(id)`라 배치가 넣을 값이 없음 | `card_drafts` 무변경. 신규 `meeting_card_suggestions`는 요청자 개념 자체가 없다 |
| 초안에 상태·만료 컬럼이 없음 | 신규 테이블에 `status`·`publish_at`·`expires_at` |
| `meeting_cards.source_draft_id` UNIQUE 하나뿐이라 "한쪽만 👍"를 표현 불가 | `meeting_card_suggestion_approvals` 분리, `source_suggestion_id` 별도 추가 |
| 하루 1건을 강제할 DB 제약 없음 | `UNIQUE (room_id, source_date)` |

**해소됨 — 채팅 메시지로 저장하지 않음(§2.3)**

| 원래 충돌 | 해소 방식 |
|---|---|
| `MessageType`에 `TEXT/CARD/SYSTEM`뿐이라 초안 표현 불가 | 값 추가하지 않음. `ck_chat_message_payload`·`ChatMessageResponse`도 무변경 |
| `06_M2_WebSocket_계약.md` §5의 "배치는 실시간 이벤트 미발행"과 충돌 | 제안은 `chat_messages`가 아니므로 그 규칙의 대상이 아니다. 계약 문서에는 규칙의 적용 범위만 한정해 두었다 |
| `insert()`의 `activateAndTouchLastMessageAt`이 ARCHIVED 방을 되살림 | 메시지를 쓰지 않으므로 호출 자체가 없다. 대상 조건에서도 `ACTIVE` 방만 고른다 |
| 만료 시 채팅 메시지를 지워야 하는 문제 | 만료는 조회 시점 기준. 메시지 삭제 없음 |

**해소됨 — 정책·운영**

| 원래 충돌 | 해소 방식 |
|---|---|
| "알림 1회"를 실현할 Push가 M1·M2 어디에도 없음 | M2는 인앱(방 안 표시 + WebSocket 이벤트)으로 한정. OS Push는 `M2-013` 후보로 분리 |
| Redis 분산락이 배치 중복 실행의 방어선이 됨 | 정합성 정본은 DB 제약. Redis 락은 AI 중복 호출 비용 절감용 보조 |
| 자정 소멸이 두 번째 스케줄러를 요구 | 조회 시점 만료로 대체. 정리 배치는 느슨해도 된다 |

**설계가 확정된 것 — 남은 일은 구현과 검증이다**

- **날짜 기준.** 자동 제안은 `source_date`를 `AiDraftCommand.referenceDate`로 넘긴다. 버튼식이 쓰는 `LocalDate.ofInstant(clock.instant(), SEOUL)` 경로를 그대로 재사용하면 D+1 새벽에 "내일"이 하루 밀린다. 검증은 `G-M2-H`(`M2-010`).
- **공동 편집 동시성.** `revision` 증가 시 기존 동의 무효화, 양쪽 `approved_revision` 일치 시에만 확정, 오래된 revision 수정은 `409`. 잠금 순서는 `InteractionPairLock` → 제안 `FOR UPDATE`로 고정했다. 검증은 `G-M2-F`·`G-M2-G`(`M2-011`).
- **공통 AI 호출 경로.** `MeetingDraftAiClient` 인터페이스를 공통 계약 seam으로 쓰고 구현(`MeetingCardAiAdapter`·`HttpMeetingDraftAiClient`)을 복제하지 않는다. 갈라지는 것은 입력 수집 규칙과 fallback 정책뿐이다(`M2-010`).

- **중복 카드 정책 — 허용한다(2026-08-10 확정).** M2에서는 **기존 OPEN 카드의 존재를 공유 제안의 생성·확정 거부 조건으로 쓰지 않는다.** M1이 한 DIRECT 방에 여러 OPEN 카드를 허용하고(산책과 병원 약속은 따로 잡는 것이다), 기존 카드가 지금 제안과 같은 약속인지 **서버가 결정적으로 판별할 계약이 없기** 때문이다. 방 단위 카드 존재를 조건으로 걸면 M1의 정책을 M2가 뒤에서 깨게 된다.
  - 중복 방어는 **`meeting_cards.source_suggestion_id UNIQUE` 하나**다. 같은 제안이 두 번 카드가 되는 것만 DB가 막고, 버튼식 카드나 다른 제안과 의미상 같은 카드가 함께 있는 것은 허용한다.
  - 덕분에 차단 전용으로 짜인 `CANCELED` 계약(취소 주체·CHECK·tombstone 수신자)을 **한 줄도 건드리지 않는다.** `DUPLICATE_CARD` 같은 취소 사유 축을 도입하면 취소 주체가 누구인지, 이벤트를 누구에게 보내는지가 전부 새 문제가 되는데, 얻는 것은 "가끔 같은 약속이 카드 두 장이 되는 것을 막음" 하나뿐이라 비용이 맞지 않는다.
  - 의미 기반 중복 제거가 필요해지면 방·종류·시각 범위·정규화한 장소·출처 메시지 구간 같은 **appointment identity/provenance 계약**으로 따로 다룬다. 기존 카드 목록을 AI에 넣어 판단시키는 방식은 정합성을 비결정적 판단에 맡기게 되므로 M2에서는 쓰지 않는다.

- **"아직 카드를 만들지 않음"의 판정 — 방 단위 카드 존재로 보지 않는다(2026-08-10 확정).** 위와 같은 이유다. 토요일 병원 카드가 하나 있다는 이유로 "내일 저녁에 한강 갈래?" 제안이 아예 생성되지 않으면 안 된다. 기획 원문의 *"약속 표현이 있었는데 카드를 만들지 않음"*은 제품 의도로 남기되, **현재 데이터 모델로는 "이 대화에서 말한 바로 그 약속이 이미 카드가 됐는지"를 결정적으로 판별할 수 없다**는 점을 명시한다. 후보 선정은 §2.3의 나머지 조건으로만 한다.

**아직 결정이 필요한 것**

중복 카드 정책과 "아직 카드를 만들지 않음" 판정이 위에서 닫히면서 셋으로 줄었다. 그 둘의 결론(방 단위 카드 존재를 조건으로 쓰지 않는다)이 **AI에 기존 카드 목록을 넣을 필요가 없다**는 뜻이므로, 남은 1번의 범위도 그만큼 좁아졌다.

1. **AI 입력 범위·비용.** 버튼식 상수는 `MAX_SOURCE_MESSAGES=30`, `SOURCE_WINDOW=24h`, `MIN_SOURCE_MESSAGES=2`다. "전날 00:00~24:00"은 24시간 롤링 윈도우와 다르므로 배치용 수집 규칙을 따로 정한다. 대상 방 전체를 한 번에 도는 호출량과 07:00 공개 시각의 관계도 여기서 잡는다(`M2-010`).
2. **알림 범위의 최종 확정.** 인앱 한정으로 출시할지, `M2-013`을 M2 안에 넣을지는 제품 결정이다. 인앱 한정이면 오프라인 사용자는 방 진입 시 REST로만 확인한다는 점을 제품이 수용해야 한다.
3. **차단 발생 시점별 처리.** 후보 선정·공개 직전 재검사로 대부분 걸러지지만, 공개 이후 차단은 `CANCELED` 전이가 필요하다. `MeetingCardBlockCleanupService`가 OPEN 카드만 다루므로 제안 취소 경로를 어디에 둘지 정한다(`M2-011`).

한편 동시 동의에서 "두 번째로 동의한 Pet"이 결정론적인지, 확정과 차단이 경합할 때 차단 이후 카드가 생기지 않는지는 §2.3의 단일 `approve` 트랜잭션과 `InteractionPairLockService` 재사용으로 결론이 났다. 남은 과제가 아니며 검증만 `G-M2-G`로 관리한다.

### 4.2 `01_M1_통합_ERD.sql`이 실제 스키마와 어긋나 있다

`M2-009`가 이 문서를 갱신해야 하는데, 착수 시점에 이미 벌어진 차이가 있다. `d27f3ac`의 마이그레이션(`V1`~`V17`)과 대조한 결과다.

| 구분 | 테이블 | 판단 |
|---|---|---|
| ERD 문서에만 있음 | `admin_actions`, `pet_registration_verification_attempts`, `pet_registration_verifications` | 관리자 조치 이력과 동물등록 인증. 해당 작업(`M1-013A`·`M1-026`)이 미착수라 **의도된 목표 구조**로 보인다. README도 이 파일을 "M1 목표 구조 참고 DDL, 직접 실행 금지"로 규정한다 |
| 마이그레이션에만 있음 | `media`(`V12`) | **실제 drift.** ERD 문서는 `media_assets`(`V4`)만 담고 있다. 코드에도 `itda.media`와 `itda.media.old`가 공존하므로 이행 중인 것으로 보이나, ERD 문서에 반영되지 않았다 |

`M2-009`에서 신규 테이블 두 개를 이 파일에 넣기 전에 **"목표 구조를 적는 파일인가, 현재 스키마를 반영하는 파일인가"를 먼저 정한다.** 그러지 않으면 새 테이블이 어느 쪽 규칙으로 들어가는지 알 수 없다. 미디어 담당과 함께 확인한다.

## 5. 담당자별 실행 순서 (채팅 범위)

### BE-2

1. `M2-001` 계약 리뷰 마무리(BE-4·프론트 승인) → PR #54 머지
2. `M2-002` DIRECT WebSocket 구현. 착수 전 이미 완료된 `M1-038`(PR #50)의 배치 주기·아카이브 기준(`app.chat-room.lifecycle.*`)이 WebSocket 동작과 충돌하지 않는지 재확인
3. **`G-M2-A`(제품 정책 갱신) + `G-M2-I`(M2 REST 계약 위치) + `G-M2-K`(정본 문서 구조) 통과** → `M2-009` 제안 계약·스키마. `card_drafts`는 건드리지 않는다
4. `M2-010` 후보 선정·AI 파이프라인과 `M2-011` 공동 편집·동의는 `M2-009` 이후 병행 가능하다
5. `M2-012` 공개·만료·실시간 이벤트. `M2-002`가 끝난 뒤여야 `CARD_SUGGESTION_*` 이벤트를 기존 개인 큐·오류 계약 위에 얹을 수 있다
6. `M2-006` 다중 replica 대응을 BE-4와 함께 확정. 방식의 정본은 BE-4다(§3)

### BE-4

1. **`G-M2-I`(M2 REST 계약 위치 확정) 통과** → `M2-003` GROUP REST 도메인. 착수 전 `M2-008`의 `KafkaProducerConfig`를 그대로 쓸지 재설계할지 확정(value serializer 포함 — §7)
2. `M2-004` GROUP WebSocket·Kafka consumer 연동

## 6. M2 Gate (채팅·제안 범위, 초안)

나머지 M2 기능(Google 로그인 등)은 담당자 배정 후 별도 Gate를 추가한다.

**DIRECT WebSocket(M2-001·M2-002)의 Gate는 `06_M2_WebSocket_계약.md` §10 수용 기준이 정본이며, 여기에 옮겨 적지 않는다.** 같은 목록을 두 문서에 두면 한쪽만 고쳐져 반드시 갈라진다. 계약이 바뀌면 §10만 고친다.

이 문서에서만 관리하는 Gate는 계약 문서가 다루지 않는 범위다.

| Gate | 내용 | 관련 |
|---|---|---|
| **G-M2-I** | **M2 REST 계약을 어느 문서에 둘지 확정한다** — `04_M1_OpenAPI.yaml`에 M2 경로를 합칠지 M2 OpenAPI를 새로 만들지. `README.md`의 "구현할 때 주의할 경계"와 PR 리뷰 1단계가 `04_M1_OpenAPI.yaml` 기준이므로 함께 갱신한다. **`M2-003`·`M2-007`·`M2-009`의 공통 선행이다** | M2-003·M2-007·M2-009 |
| **G-M2-K** | **M2 정본 문서 구조를 확정한다** — ① 제안 상태 전이를 `03_M1_상태전이.md`에 넣을지(문서를 `03_상태전이.md`로 승격) 별도 M2 상태전이 문서로 뺄지, ② 이번에 내린 아키텍처 결정을 `05_M1_결정사항_보완과제.md`에 넣을지 M2 결정 문서를 새로 만들지, ③ 그 결과에 맞춰 `README.md`의 정본 순서와 PR 리뷰 절차를 갱신. **`M2-009` 착수의 선행이다** — 상태전이·결정 정본을 정하지 않은 채 스키마부터 만들면 기록이 갈라진다 | M2-009 |
| **G-M2-A** | `00_최신_제품정책.md` M2 목록에 공유 약속 제안(UC-07)이 추가된다. **`M2-009` 착수의 선행 조건이며, 통과 전에는 스키마·마이그레이션을 만들지 않는다.** | M2-009 |
| G-M2-B | GROUP 메시지가 Kafka를 거쳐 최종적으로 DIRECT와 동일한 개인 큐·오류 계약으로 도달한다 | M2-004 |
| G-M2-C | 다중 replica 전환 방식이 결정되어 배포 문서에 기록된다. 미결 상태로 M2를 종료하지 않는다 | M2-006 |
| G-M2-D | `participants[]` 확장이 **`G-M2-I`에서 확정한 OpenAPI 정본**·`02_M1_API_계약.md`·구현·테스트에 같은 PR로 반영된다(`02_M1_API_계약.md`의 동기화 원칙) | M2-007 |
| G-M2-E | 제안이 `chat_messages`에 한 행도 쓰지 않는다. `MessageType`·`ck_chat_message_payload`·`ChatMessageResponse`가 무변경이고, 제안 생성·만료로 방의 `last_message_at`과 `status`가 바뀌지 않는다 | M2-012 |
| G-M2-F | A가 동의한 뒤 B가 수정하면 A의 동의가 무효화되고, 그 상태로는 확정되지 않는다. 오래된 `revision`으로 보낸 수정은 `409`다 | M2-011 |
| G-M2-J | 공개(`READY → ACTIVE`)와 차단이 경합해도 **차단 이후에 제안이 공개되지 않는다.** 공개 직전 재검사에 실패한 `READY`는 `SKIPPED`로 종결되며 `READY`로 남지 않는다. **차단당한 User에게는 `CARD_SUGGESTION_CANCELED`가 가지 않는다** | M2-012 |
| G-M2-G | 두 번째 `approve`와 카드 생성·`CONFIRMED`가 한 트랜잭션에서 끝난다. 동시 동의에서 `creatorPetId`가 결정론적으로 하나로 정해지고, 확정과 차단이 경합해도 차단 이후에 카드가 생기지 않는다 | M2-011 |
| G-M2-H | 배치가 AI에 넘기는 `referenceDate`가 실행일이 아니라 `source_date`다(전날 대화의 "내일"이 하루 밀리지 않는다). `fallbackReason != null`이거나 `combinedInstant == null`이면 제안을 만들지 않고 `SKIPPED`로 종결하며, 그 사유가 구분되어 기록된다. 같은 `(room_id, source_date)`로 두 번 생성되지 않고, **lease를 잃은 워커의 결과가 저장되지 않는다** | M2-010 |

**`G-M2-K`의 우선 후보**(결정이 아니라 출발점이다).

- `03_M1_상태전이.md` → **`03_상태전이.md`로 승격.** 상태 전이는 "M1 때 정한 것"이 아니라 현재 시스템이 허용하는 상태 머신이다. M2용을 따로 만들면 M3에서 또 만들게 된다.
- `05_M1_결정사항_보완과제.md`는 **M1 기록으로 두고 `08_M2_결정사항.md`를 새로 만든다.** 여기서 이름만 떼면 과거 M1 결정·현재 M2 결정·미완 보완과제가 한 문서에 쌓인다. 03과 성격이 다르다.
- `docs/decisions/ADR-xxx` 같은 구조는 지금 규모에 과하다.
- **파일명을 바꾸는 쪽으로 정하면 참조를 같은 PR에서 전부 고친다.** `03`을 승격하면 `README.md`·`06`·`07`과 다른 spec 문서의 파일명 참조가 함께 바뀌어야 한다. 이름만 먼저 바꾸면 정본 링크가 끊긴다.

**Gate를 새로 만드는 기준**은 하나다 — *통과하지 않고 다음 작업을 하면 잘못된 구현이나 계약이 만들어지는가*. 문서 문장 하나를 고치는 수준이면 §8의 후속 작업으로 충분하다. 지금 11개는 각각 실패 조건이 있어 유지하되, 문서를 고칠 때마다 Gate를 늘리지 않는다.

Gate가 없는 작업은 `M2-001`·`M2-002`(정본이 `06_M2_WebSocket_계약.md` §10), `M2-008`(완료), `M2-005`·`M2-013`(미배정)이다. `M2-003`은 `G-M2-I`를 선행으로 가지되 **완료 Gate는 두지 않는다** — GROUP REST 도메인의 완료 기준은 BE-4 소관이라 이 문서가 임의로 세우지 않으며, 필요하면 BE-4가 추가한다.

## 7. 위험

| 위험 | 영향 | 대응 |
|---|---|---|
| WebSocket 다중 replica 방안 미확정(M2-006) | `replicas=1` 전제가 배포 규모 확장을 막음 | `M2-008`의 `RedisConfig`·`KafkaProducerConfig`를 그대로 재사용할 수 없다는 점을 배포 문서에 명시하고, 필요 시점 이전에 BE-2·BE-4가 별도 설계 착수 |
| GROUP 도메인 미착수 상태에서 Kafka producer bean만 먼저 존재(`M2-008`) | `KafkaTemplate<String, Object>`인데 value serializer가 `StringSerializer`라 String 아닌 페이로드는 런타임에 실패. 다른 도메인이 이를 모르고 재사용하면 배포 후에야 드러남 | M2-004에서 serializer 방식을 확정하고, GROUP 전용 설정이 필요하면 그때 분리. 확정 전까지 다른 도메인의 재사용 금지 |

| `M1-038` 채팅방 수명주기 배치(물리 삭제)와 WebSocket 세션 생존 구간이 겹침 | 구독 중인 방이 배치로 사라져도 클라이언트가 즉시 알 수 없음 | `06_M2_WebSocket_계약.md` §4·§10에 명시한 대로 **단건 조회(`GET /chat/rooms/{roomId}`)가 `404`인지로 판정**하고, 실시간 알림을 추가로 구현하지 않음. 방 목록은 커서 페이지네이션이라 특정 방의 소멸 판정에 쓸 수 없다 |
| 7개 항목(Google 로그인·GPS·셋로그 업로드·지도/장소·만남 확인·후기/발자국·대화 맥락 검열) 담당·일정 미배정 | M2 전체 일정 추정 불가 | 팀 회의에서 담당자·우선순위 배정 후 이 문서의 §2.2·§4를 갱신 |
| BE-2가 DIRECT WebSocket과 UC-07 백엔드를 동시에 안고 있음 | `M2-001`·`M2-002`·`M2-006`에 `M2-009`~`M2-012`까지 7건. DIRECT가 늦어지면 `M2-012`(제안 실시간)까지 연쇄로 밀림 | `M2-002`를 먼저 끝내고 `M2-009`~`M2-011`은 그와 독립이므로 병행. `M2-012`만 `M2-002`에 의존한다(§5) |
| UC-07이 `00_최신_제품정책.md` M2 목록에 없는 채로 WBS에만 존재 | 정본과 실행 계획이 어긋난 상태이며, 이 문서 §1의 정본 순서상 정책 문서가 이긴다 | 착수 전 정책 문서 갱신을 선행 조건으로 §2.3·§5에 명시 |
| 자동 제안의 AI 기준 날짜가 실행일로 밀림 | 전날 대화의 "내일 6시"가 하루 뒤로 해석되어 잘못된 약속이 제안됨 | `M2-010`에서 `source_date`를 `referenceDate`로 넘기는 경로를 분리하고, 이를 수용 기준에 넣음(§4.1) |
| 공동 편집 동시성 — 동의 후 상대가 수정 | 합의하지 않은 시각·장소로 약속이 확정될 수 있음 | `revision` 증가 시 기존 동의 무효화, 양쪽 `approved_revision` 일치 시에만 확정, 오래된 revision 수정은 `409`(`G-M2-F`) |
| 배치 AI 호출량이 07:00 공개 시각을 못 맞출 수 있음 | 제안이 늦게 뜨거나 일부 방만 생성됨 | 자정 처리와 07:00 공개를 `READY`/`ACTIVE` 2단계로 분리(§2.3). 공개 시각은 설정값 |
| **PR 체크가 `postgresTest`를 돌리지 않음** | `dev` PR을 검사하는 `ci-test.yml`은 `./gradlew test`만 실행하고, `postgresTest`를 포함한 `ci.yml`은 `main` push 전용이다. 멱등성·동시성·수신자 집합처럼 PostgreSQL 전용 구문(`xmax = 0`)에 의존하는 검증이 **머지 전에 한 번도 돌지 않는다.** 채팅 통합 테스트 6종이 이미 그 상태다 | `M2-002` 착수 전 `ci-test.yml`에 `postgresTest` 추가를 검토. 그때까지는 로컬 수동 실행을 PR 체크리스트에 넣는다 |
| 워크플로 두 개가 같은 `name: Backend CI`를 씀 | PR 체크 이름만으로 어느 워크플로가 돌았는지 구분되지 않아, "CI 성공"의 범위를 오해하기 쉽다(`M2-008` 판정도 이에 해당) | 워크플로 이름을 분리 |
| **다중 replica 결정을 반영할 배포 정의가 저장소에 없다** | `M2-006`의 산출물이 "배포 문서에 기록"인데, 저장소에는 compose 2개와 `Dockerfile`뿐이고 k8s·helm 같은 배포 정의가 없다. `ci.yml`도 GHCR 이미지 푸시까지만 한다. `06_M2_WebSocket_계약.md` §10의 "단일 인스턴스 전제와 다중 replica 전환 조건이 배포 문서에 전달된다"는 수용 기준이 현재 검증 불가다 | `M2-006`에서 배포 정의의 위치부터 정한다. 저장소 밖(인프라 레포 등)이라면 그 위치를 이 문서에 기록 |

## 8. 후속 작업

### 8.1 M2로 갱신해야 하는 문서

**이 문서와 `06_M2_WebSocket_계약.md`만으로 M2 문서 체계가 끝난 것이 아니다.** 지금 두 문서가 한 일은 "M2를 시작하기 위한 WBS와 WebSocket 계약"까지다. 나머지는 스키마·API·제품 결정이 나기 전에 미리 쓰면 틀린 정본을 만들게 되므로 시점을 걸어 미뤄 두었다. 아래가 남은 전부다.

| 문서 | 필요한 일 | 시점 |
|---|---|---|
| `00_최신_제품정책.md` | M2 목록에 공유 약속 제안(UC-07) 추가 | **`G-M2-A`** |
| `03_M1_상태전이.md` | **제안 상태 7개와 전이 규칙 반영.** 지금 이 문서 §2.3에만 있다 — 상태 전이의 정본이 03이고 `README.md`의 PR 리뷰 2단계가 "상태 변경이 03과 DB CHECK를 위반하지 않는지 확인"이므로, 빠진 채로는 그 절차가 성립하지 않는다 | **`G-M2-K`** 결정 후 `M2-009` |
| `05_M1_결정사항_보완과제.md` 또는 신규 | 이번에 내린 아키텍처 결정 기록 — 제안을 별도 도메인으로 분리, `chat_messages` 미사용, `event_version`·`sourceDate`, 잠금 순서, 07:00 생성·공개 분리, 보존 정책. **WBS 작업 내용이 아니라 결정이므로 이 문서에만 두지 않는다** | **`G-M2-K`** |
| `01_M1_통합_ERD.md`·`.sql` | 제안·동의 테이블, `source_suggestion_id` 반영 | `M2-009` |
| `02_M1_API_계약.md`·`04_M1_OpenAPI.yaml` | M2 REST 경로 | **`G-M2-I`** 결정 후 |
| `04_M1_API_명세.md` | `participants[]` 확장, 오류 정정 2건 | `M2-007` / 후속 |
| `04_M1_마일스톤_WBS.md` | `M1-038` 완료 및 React Native 후속 문구 정리 | PR #65 최신 문서 변경에 반영됨; 해당 PR의 정본 반영 후 확인 |
| `README.md` | 정본 순서·PR 리뷰 절차 갱신 | **`G-M2-K`** 결과 반영 |
| `docs/인프라_역할분담.md` | 다중 replica 결론 반영. PR #54가 `replicas=1` 전제를 한 줄 넣어 두었고, 저장소에서 배포에 가장 가까운 문서다 | `M2-006` |

### 8.2 개별 항목

8.1이 목록이라면 아래는 그중 배경 설명이 필요한 것들이다.

- `00_최신_제품정책.md`의 M2 목록에 공유 약속 제안(UC-07)을 추가한다. `G-M2-A`이며 `M2-009` 착수의 선행 조건이다(§2.3).
- 담당자 배정 회의 이후 §2.2 항목들을 §4와 같은 형식의 WBS 표로 승격한다.
- `M2-008`의 하자 두 건을 처리한다(§4). ① `.env.example`과 `application-prod.yaml`에 `REDIS_HOST`·`REDIS_PORT`·`KAFKA_BOOTSTRAP_SERVER`를 반영한다 — 기본값이 없어 지금은 예시를 복사하면 기동에 실패한다. ② 루트 `docker-compose.yml`(백엔드 있음)과 `deployment/local/docker-compose.yml`(Redis·Kafka 있음)이 서로를 보완하지 못하는 구성을 정리한다. `origin/chore/infra-server/59`에서 진행 중일 수 있으므로 담당자 확인이 먼저다.
- `04_M1_마일스톤_WBS.md`의 `M1-038` 완료 상태와 React Native 후속 문구 정리는 PR #65 최신 문서 변경에 반영되어 있다. 이 PR에서 중복 수정하지 않는다.
- `04_M1_OpenAPI.yaml` 정정 두 건. ① `POST /chat/rooms/{roomId}/messages`의 대표 오류에 `BLOCKED_USER`(403)가 적혀 있으나 채팅 전송 경로는 이 코드를 던지지 않는다(차단은 `404 CHAT_ROOM_NOT_FOUND`로 은폐). 문서를 믿고 구현하면 차단 사실이 노출된다. ② `sendBlockedReason`이 `[GREETING_REPLY_REQUIRED, BLOCKED_USER, ACCOUNT_NOT_ACTIVE, null]` enum으로 정의돼 있으나 구현은 항상 `null`을 반환하는 스텁이다. 둘 다 `06_M2_WebSocket_계약.md` §7·§7.1 참고.
- `canSend` 구현 보완 검토. 현재 방 상태만 보므로 인사 답변 대기 방도 `true`가 되어, 프론트가 이 값을 믿으면 전송이 `GREETING_REPLY_REQUIRED`로 튕긴다. M1 범위 보완이라 이 문서의 WBS 항목으로 잡지 않고 후속으로 남긴다.
- `06_M2_WebSocket_계약.md`가 리뷰를 마치고 머지되면 이 문서의 M2-001 상태를 "완료"로 갱신한다.
- GROUP 도메인 설계가 시작되면 `01_M1_통합_ERD.md`에 대응하는 M2 ERD 문서 필요 여부를 판단한다.
