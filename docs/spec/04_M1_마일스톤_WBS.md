# 같이놀개 M1 마일스톤·WBS

> 기준일: 2026-07-24
> 목표일: 2026-07-30
> 범위: M1만 상세 관리한다. M2·M3는 후속 문서에서 추가한다.
> 상태 기준: 현재 Git 저장소의 구현·테스트 파일을 기준으로 판정한다.
> 상태 정정: 2026-08-10, `dev` `ec16ecd` 기준으로 41개 항목 전부를 저장소와 대조했다. 기존 `미착수` 24건을 전수 재검토해 22건을 정정하고 2건(M1-013A·M1-026)은 `미착수`로 유지했다. 2026-08-12에는 이 문서 PR이 rebase된 `dev` `a54122f`에서 `test`(617)와 추가 필터 없는 `postgresTest`(363)를 강제 재실행해 M1-014·015·028·030·038·039의 완료 근거를 갱신했다. 판정 기준은 §1의 상태 표기 정의를 따른다.

## 1. 정본과 적용 원칙

문서가 충돌하면 아래 순서로 적용한다.

1. 제품 범위·정책: `docs/spec/00_최신_제품정책.md`
2. REST 계약: 정적 `docs/spec/04_M1_OpenAPI.yaml`
3. 사람이 읽는 API 설명: `docs/spec/04_M1_API_명세.md`, `docs/spec/02_M1_API_계약.md`
4. 저장 계약: `docs/spec/01_M1_통합_ERD.md`, `docs/spec/01_M1_통합_ERD.sql`
5. 상태 계약: `docs/spec/03_M1_상태전이.md`
6. 최신 기획서·핵심기능·와이어프레임
7. v13 산출물은 작업 ID와 변경 이력 확인에만 사용

런타임 `/v3/api-docs`는 현재 구현 관찰용이며 M1 계약 정본이나 필수 CI 대조 Gate가 아니다.

작업 ID는 v13의 번호를 보존한다. 폐기·이동된 작업도 삭제하거나 번호를 재사용하지 않는다. 신규 작업은 `M1-035`부터 부여한다.

`완료` 판정에 담당자는 기준이 아니다. 아래 네 가지를 저장소에서 객관적으로 확인할 수 있으면 담당이 누구든 `완료`로 표기한다.

1. 구현 코드가 있다.
2. 해당 항목의 핵심 테스트가 있다.
3. 그 테스트가 이 항목의 수용 조건을 실제로 검증한다.
4. 그 테스트가 통과한다.

네 가지 중 하나라도 확인하지 못하면 `완료`가 아니라 `구현 확인 / 추가 검증 필요`다. **기능에 결함이 있다는 뜻이 아니라 완료를 증명할 근거가 아직 없다는 뜻이다.** 담당이 누구인지는 여기서도 기준이 아니다. 미확인 항목이 무엇인지는 비고에 적는다.

**PR CI만으로 4를 닫을 수는 없다.** `ci-test.yml`은 `./gradlew test`만 실행하고, `build.gradle`의 `tasks.named('test')`는 `postgres`·`rustfs`·`redis` 태그를 제외한다. 따라서 `*PostgreSqlIntegrationTest`와 `BlockIntegrationTest`는 PR CI에 포함되지 않는다.

다만 2026-08-12에 이 문서 PR이 rebase된 `dev` `a54122f`에서 `./gradlew.bat test --no-daemon --rerun-tasks`와 필터 없는 `./gradlew.bat postgresTest --no-daemon --rerun-tasks`를 직접 실행했다. 두 task 모두 `5 actionable tasks: 5 executed`로 수행됐고 XML 집계는 각각 617/0/0/0, 363/0/0/0이었다. M1-014·015·030·038·039의 비고에 적은 PostgreSQL 클래스가 모두 결과 XML에 포함된 것을 확인했으므로, 이 다섯 항목은 완료로 승격한다. M1-028도 요청·수락·거절·취소·목록·삭제·역방향 자동 수락·7일 만료의 핵심 테스트와 해당 PostgreSQL XML을 대조해 완료로 승격한다.

`ci.yml`의 main push 실행은 PR CI와 다른 보조 근거다. 2026-08-11 main CI run #30도 `./gradlew test postgresTest`를 실제 실행해 성공했지만, 이 문서의 현재 완료 판정은 위 최신 `a54122f` 재실행 결과를 정본 근거로 삼는다.

상태 표기는 다음과 같다.

| 상태 | 의미 |
|---|---|
| 완료 | 현재 저장소에서 구현과 핵심 테스트를 확인함 |
| 부분완료 | 기반 또는 일부만 구현됨 |
| 미착수 | 현재 저장소에서 도메인 구현을 확인하지 못함 |
| 구현 확인 / 추가 검증 필요 | 위 1·2는 확인했으나 3 또는 4를 확인하지 못함. 무엇이 남았는지는 비고에 적는다 |
| 부분 구현 / 추가 검증 필요 | 일부만 구현됨. 남은 범위는 비고에 적는다 |
| 확인 불가 | 프론트·배포처럼 이 저장소만으로 판정할 수 없음 |
| CANCELED | M1에서 폐기되었으며 번호를 보존함 |
| MOVED | M1 이후로 이동했으며 번호를 보존함 |

## 2. M1 완료 범위

### 포함

- 이메일 회원가입·로그인·Refresh·Logout
- 회원가입 시 동네 직접 선택
- Pet CRUD·Active Pet·최대 5마리
- 동물등록번호 또는 RFID 조회, 인증 정보 확인, 인증 배지
- S3 시드 셋로그 영상 3개 조회
- 귀여워요·좋아요 반응
- 셋로그 인사에서 DIRECT 방과 최초 메시지 생성
- 답변 전 추가 전송 제한, DIRECT 채팅과 polling
- 친구 요청·수락·거절·취소·삭제
- 약속 카드 초안·확정·조회·취소
- User 단위 차단
- DIRECT 방 신고, 관리자 신고 큐·전체 대화 조회·처리
- React 반응형 웹
- 개발·시연 환경 배포

### M1 제외

- 우리 동네 이웃 목록
- Google 로그인, GPS 동네 확인
- 이메일 인증, 비밀번호 찾기·재설정
- Pet 프로필 사진 업로드
- 사용자 셋로그 촬영·편집·업로드
- React Native 앱
- WebSocket, Push, 읽음 표시
- 메시지 수정·삭제, 이미지·파일 첨부
- 회원탈퇴·`DELETE /me`·탈퇴 Cleanup
- 인사 재시도와 차단 해제
- 욕설 금칙어 자동 차단, 일일 AI 검열, 대화 맥락 검열
- 지도·장소 검색, 만남 확인, 후기·발자국, 그룹 채팅

## 3. 담당 경계

| 담당 | M1 책임 |
|---|---|
| BE-1 | 전체 인프라·배포, S3 셋로그 기반, 시드 영상·계정·Pet·Setlog 데이터, 셋로그 조회·반응 API와 화면 |
| BE-2 | DIRECT 채팅·polling, 인사 수명주기, 카드 전체, 차단 전체, 신고 접수·관리자 신고 기능, 관련 React 화면, 프런트 공통 통합 |
| BE-3 | 인증, 동네 직접 선택, Pet·Active Pet, 동물등록 인증, 친구 API, DB 제약·Flyway 검토, 최소 관리자 RBAC/Core, 일반 관리자 화면 |
| AI-1 | 카드 초안 추출 계약·fixture 제공. 세부 모델 일정은 AI-1이 별도 관리 |
| 전원 | API 계약 검토, 통합 QA, 시연 리허설 |

### DB 작업 원칙

- 각 도메인 담당자가 자기 도메인의 migration을 작성한다.
- BE-3가 migration 번호, FK·Unique·Check·Partial Index, Flyway 적용 순서를 검토한다.
- BE-1은 배포 환경에서 migration 실행과 복구 절차를 책임지고, BE-3는 DB 정합성을 검증한다.

### 관리자 경계

- M1 Gate 필수: ADMIN 로그인·인가, 신고 큐, 신고 사유와 해당 DIRECT 방 전체 대화 조회, `DISMISSED`·`WARNING` 처리.
- BE-3는 공통 로그인과 최소 RBAC/Core를 먼저 제공한다.
- BE-2는 신고 관리자 API와 신고 큐·대화 열람 UI를 담당한다.
- 일반 사용자·Pet·등록결과 조회·정지 관리 화면은 BE-3 담당이지만 M1 후순위(P2)다.

## 4. M1 전체 WBS

우선순위는 `P0 = Gate 필수`, `P1 = M1 범위`, `P2 = 일정 초과 시 후순위`다.

| 작업 ID | 권장 기간 | 분류 | 주담당 | 협업·검토 | 선행 | 산출물·완료 기준 | 우선순위 | 현재 상태 |
|---|---|---|---|---|---|---|---|---|
| M1-001 | 7/24 | 설계 | BE-3 | 전원 | 없음 | 최신 제품정책·API·ERD·상태전이와 본 WBS 승인 | P0 | 완료 |
| M1-002 | 7/24~7/27 | 설계 | BE-3 | BE-1, BE-2 | M1-001 | Flyway 정본 관계·M1 ERD·참고 SQL·핵심 제약 검토 | P0 | 부분완료 |
| M1-003 | 7/24~7/27 | 설계 | BE-2 | BE-1, BE-3 | M1-001 | M1 API·OpenAPI·Example·오류 매트릭스 소비자 검토 | P0 | 부분완료 |
| M1-004 | 7/24~7/25 | Web | BE-2 | BE-1, BE-3 | M1-001 | React 반응형 웹 셸, 라우팅, 인증 상태, 공통 API client | P0 | 확인 불가(프론트) |
| M1-005 | 7/24~7/25 | 인프라 | BE-1 | BE-3 | M1-001 | 로컬 PostgreSQL·API 실행, profile·환경변수 기준 확정 | P0 | 부분완료 |
| M1-006 | 7/24~7/29 | DB | 도메인별 담당 | BE-3 검토 | M1-002 | 전체 도메인 migration, Flyway clean-migrate 및 PostgreSQL 제약 테스트 | P0 | 부분완료 |
| M1-007 | 7/24 | 인증 | BE-3 | BE-2 | M1-006 | signup·login·refresh·logout, 동네 직접 선택, 토큰 회전·폐기 테스트 | P0 | 완료 |
| M1-008 | — | 사진 AI | AI-1 | — | — | 기존 photo-check 작업 | — | CANCELED |
| M1-009 | — | 검증 사진 S3 | BE-1 | — | — | 기존 펫 검증 사진 업로드 작업 | — | CANCELED |
| M1-010 | — | 촬영 UI | BE-2 | — | — | 기존 정면·측면 촬영 UI | — | CANCELED |
| M1-011 | — | 사진 판별 | AI-1 | — | — | 기존 사진 판별 모델 | — | CANCELED |
| M1-012 | — | 검증 사진 처리 | BE-1 | — | — | 기존 검증 사진 승격·삭제 | — | CANCELED |
| M1-013 | 7/27~7/28 | Pet Core | BE-3 | BE-2 | M1-006 | 미삭제 Pet 최대 5마리·owner 동시성 제어, 삭제 상태 CHECK, 생성 전 후보 확정, 생성·Active 지정 독립 Commit, L1 수동 복구 | P0 병목 | 구현 확인 / 추가 검증 필요(pet 30파일·테스트 23. 그 테스트가 수용 조건을 덮는지 미확인) |
| M1-013A | 7/28~7/29 | 등록 인증 | BE-3 | BE-2 | M1-013 | 동기 Provider 최종 상태 저장, canonical 부재 REJECTED, consume·배지·스냅샷·fingerprint·PII 미저장 | P1 | 미착수(pet 30파일 어디에도 등록 인증·canonical·fingerprint 구현이 없다) |
| M1-014 | 7/27~7/28 | 채팅 Core | BE-2 | BE-3 검토 | M1-002, M1-003, M1-013 인터페이스 | DIRECT room·participant·message schema/service, Pet pair 정합. Fixture 병렬 개발 가능, 최종 통합은 M1-013 병합 후 | P0 | 완료(`ChatPostgreSqlIntegrationTest` 13, `ChatServicePostgreSqlIntegrationTest` 12, `ChatConcurrencyPostgreSqlIntegrationTest` 3이 pair 정규화·중복 방 거부·동시 생성 단일성을 검증. 2026-08-12 최신 dev `postgresTest` XML 모두 0 실패·0 오류) |
| M1-015 | 7/25~7/27 | 채팅 API | BE-2 | — | M1-014 | 방 목록·메시지 이력·TEXT 전송 polling API | P0 | 완료(`ChatRestPollingApiPostgreSqlIntegrationTest` 35, `ChatApiContractPostgreSqlIntegrationTest` 10이 방 목록 정렬·커서·이력 폴링·TEXT 전송 멱등을 검증. 2026-08-12 최신 dev `postgresTest` XML 모두 0 실패·0 오류) |
| M1-017 | 7/26~7/28 | 채팅 Web | BE-2 | — | M1-004, M1-015 | 방 목록·대화·전송 UI, polling, 오류별 입력 제한 | P0 | 확인 불가(프론트) |
| M1-019 | — | Mobile | BE-2 | — | — | React Native 앱 | — | MOVED(POST-M1) |
| M1-020 | 7/25~7/29 | 배포 | BE-1 | BE-3 | M1-005 | 서버·DB·S3·환경변수·TLS·로그·health가 연결된 dev URL | P0 | 확인 불가(배포) |
| M1-021 | 7/26~7/29 | 보안 | BE-3 | BE-1, BE-2 | M1-007 | JWT 인가, L1/L2, ADMIN 재검증, PII·secret 로그 차단 | P0 | 부분완료 |
| M1-022 | 7/28~7/29 | 복구 | BE-1 | BE-3 | M1-020 | DB 백업·복구 리허설과 증빙 | P1 | 확인 불가(복구) |
| M1-023 | 7/29 | QA | 전원 | 전원 | P0 전체 | Gate 시나리오 3회 연속 성공, 치명 결함 0 | P0 | 확인 불가(QA) |
| M1-024 | 7/30 | 릴리스 | BE-1 | 전원 | M1-023 | M1 배포·시연, rollback 절차 확인 | P0 | 확인 불가(릴리스) |
| M1-025 | 7/24~7/26 | 관리자 인증 | BE-3 | BE-2 | M1-007 | `users.role`, 공통 로그인, ADMIN 접근 검사와 DB 재검증 | P0 | 부분완료 |
| M1-026 | 7/28~7/30 | 일반 관리자 | BE-3 | BE-2 | M1-013, M1-025 | 사용자·Pet·등록결과 조회·정지 API | P2 | 미착수(`/admin` 컨트롤러가 하나도 없다. AdminBootstrapRunner는 초기 계정 생성용이라 별개다) |
| M1-027 | 7/28~7/30 | 일반 관리자 Web | BE-3 | BE-2 | M1-004, M1-026 | 사용자·Pet·등록결과 조회·정지 화면 | P2 | 확인 불가(프론트. 선행 M1-026 관리자 API가 이 저장소에 없다) |
| M1-028 | 7/25~7/27 | 친구 API | BE-3 | BE-2 | M1-006, M1-013 | 요청·수락·거절·취소·목록·삭제, 상호요청 자동수락, 7일 만료 | P1 | 완료(`FriendRequestApiContractPostgreSqlIntegrationTest` 19가 요청·역방향 자동 수락·명시 수락·거절·취소·목록을, `FriendshipDeletionApiContractPostgreSqlIntegrationTest` 7이 삭제를 검증. `FriendRequestCommandTransactionServiceTest`가 7일 만료와 만료 요청 교체를 검증. 2026-08-12 최신 dev XML 모두 0 실패·0 오류) |
| M1-029 | 7/26~7/28 | Pet·친구 Web | BE-2 | BE-3 | M1-004, M1-013, M1-028 | Pet 공개 태그 검색, 친구 요청·받은·보낸·목록 화면 | P1 | 확인 불가(프론트) |
| M1-030 | 7/26~7/28 | 약속 카드 | BE-2 | AI-1 | M1-015, M1-033 | 2개 이상 메시지에서 버튼 활성, 초안·빈 폼 fallback·확정·조회·취소, CARD·SYSTEM polling 반영 | P0 | 구현 확인 / 추가 검증 필요(`MeetingCardPostgreSqlIntegrationTest`, `CardDraftPostgreSqlIntegrationTest`, `MeetingCardPolicyTest`가 백엔드 초안·fallback·확정·조회·취소·CARD/SYSTEM 반영을 검증. 수용조건의 프론트 버튼 활성 UI는 이 저장소에서 검증하지 못함) |
| M1-031 | — | 일일 검열 | BE-2 | AI-1 | — | 일일 AI 검열 배치 | — | CANCELED |
| M1-032 | — | 검열 UI | BE-2 | — | — | 위험 메시지·case 검열 화면 | — | CANCELED |
| M1-033 | 7/24~7/26 | AI 계약 | AI-1 | BE-2 | M1-003 | 최근 30개·24시간 이내 입력, 5초 제한, EMPTY_FORM fixture | P0 의존 | 구현 확인 / 추가 검증 필요(meetingcard/ai 8파일·HttpMeetingDraftAiClientContractTest·MeetingCardAiAdapterTest. 그 테스트가 30개·24시간·5초 제한을 덮는지 미확인) |
| M1-034 | — | 검열 AI | AI-1 | — | — | moderate-batch 계약 | — | CANCELED |
| M1-035 | 7/27~7/28 | 시드 셋로그 | BE-1 | BE-3 검토 | M1-005, M1-006, M1-013 또는 Pet Fixture | S3 시드 영상 3개, Presigned GET, L1 홈 조회 API와 화면 | P0 | 구현 확인 / 추가 검증 필요(`SetlogControllerTest`, `SetlogReadServiceTest`, `SetlogQueryServiceTest`, `SetlogMigrationPostgreSqlIntegrationTest`에서 셋로그 조회·Presigned URL·migration 계약을 확인. S3 운영 구성과 L1 홈 화면은 이 저장소에서 검증하지 못함) |
| M1-036 | 7/26~7/28 | 셋로그 반응 | BE-1 | BE-2 | M1-035, M1-013 | CUTE·LIKE 독립 추가/취소, 자기 Pet 금지, 카운트 API와 화면 | P1 | 구현 확인 / 추가 검증 필요(`SetlogReactionServiceTest`, `SetlogControllerTest`, `SetlogQueryServiceTest`에서 CUTE·LIKE 추가/취소·소유권·조회 계약을 확인. 반응 화면과 전체 수용조건 충족 여부는 이 저장소만으로 확정하지 않음) |
| M1-037 | 7/25~7/27 | 인사 | BE-2 | BE-3 검토 | M1-014, M1-035 | 인사→DIRECT·최초 고정 메시지, Pet당 일 10명, 재인사 영구 금지 | P0 | 완료(GreetingServiceTest — 인사 방·고정 메시지 단일 흐름, 일 10명 초과 거부, 재인사 영구 금지 검증. 태그가 없어 PR CI가 실행하므로 4까지 확인됨) |
| M1-038 | 7/26~7/28 | 방 수명주기 | BE-2 | BE-3 검토 | M1-037 | 답변 전 추가 전송 금지, 24시간 무답 정리, 신고 방 보존, 30일 보관·복구 | P0 | 완료(`ChatRoomLifecyclePostgreSqlIntegrationTest` 10이 24시간 무답 정리·신고 방 보존·30일 ARCHIVED 보관을, `ChatMessageServiceTest` 14가 답변 전 전송 금지를, `ChatServicePostgreSqlIntegrationTest` 12가 ARCHIVED 방 전송 복구를 검증. 2026-08-12 최신 dev XML 모두 0 실패·0 오류) |
| M1-039 | 7/26~7/28 | 차단 | BE-2 | BE-3 검토 | M1-006, M1-028 | User 단위 차단 API·검사·UI, 관계 정리, 콘텐츠 숨김, 해제 없음 | P0 | 구현 확인 / 추가 검증 필요(`BlockApiContractPostgreSqlIntegrationTest`, `BlockIntegrationTest`, `BlockServiceTest`, `ChatRestPollingApiPostgreSqlIntegrationTest`가 백엔드 차단·관계 정리·콘텐츠 차단을 검증하고 OpenAPI/`BlockController`에 해제 endpoint가 없음을 확인. 수용조건의 차단 UI는 이 저장소에서 검증하지 못함) |
| M1-040 | 7/27~7/29 | 신고·관리자 신고 | BE-2 | BE-3(RBAC) | M1-015, M1-025 | DIRECT 신고, 관리자 큐·전체 방 이력·처리 API와 화면 | P0 | 부분 구현 / 추가 검증 필요(POST /reports는 구현. /admin/reports 큐·처리 API 없음) |
| M1-041 | 7/27~7/28 | 시드 데이터 | BE-1 | BE-3 검토 | M1-006, M1-013 또는 Pet Fixture, M1-035 | 시드 User·Pet·Setlog와 영상 3개, local·dev·demo 재현 절차 | P0 | 부분 구현 / 추가 검증 필요(db/demo/R__zz_home_setlogs.sql 1개 확인. 시드 User·Pet과 재현 절차는 미확인) |
| M1-042 | 7/27~7/29 | Web 통합 | BE-2 | BE-1, BE-3 | M1-004, 각 Web 작업 | 공통 라우팅·인증 상태·API client·화면 merge, 반응형 확인 | P0 | 확인 불가(프론트) |

## 5. 담당자별 실행 순서

### BE-1

1. `M1-005` 로컬·환경 기반 확정
2. `M1-035`, `M1-041` 시드 영상·데이터·조회 수직 흐름
3. `M1-036` 반응 API·화면
4. `M1-020` dev 배포
5. `M1-022`, `M1-024` 복구 리허설·최종 배포

BE-1은 전체 배포의 최종 책임자다. 도메인 담당자는 배포 가능한 이미지·환경변수·migration 정보를 BE-1에 제공한다.

### BE-2

1. `M1-004`, `M1-014`, `M1-015` 웹·채팅 기반
2. `M1-037`, `M1-038` 인사와 방 수명주기
3. `M1-030` 카드 fallback-first
4. `M1-039`, `M1-040` 차단·신고·관리자 신고
5. `M1-017`, `M1-029`, `M1-042` 관련 화면과 전체 프런트 통합

BE-2의 병목은 카드가 아니라 채팅 Core다. `M1-014`의 테이블·서비스 계약을 먼저 고정하고 인사·카드·신고가 이를 재사용해야 한다.

### BE-3

1. `M1-001~003`, `M1-006` 정본·DB 검토
2. `M1-007`, `M1-013` 인증·Pet 수직 흐름
3. `M1-025` 최소 ADMIN RBAC/Core를 BE-2에 우선 제공
4. `M1-028`, `M1-013A` 친구·등록 인증
5. `M1-026`, `M1-027` 일반 관리자 기능은 P0·P1 완료 후 진행

BE-3는 모든 migration을 대신 작성하지 않는다. 도메인 담당자가 낸 migration의 번호와 물리 제약을 최종 검토한다.

## 6. 핵심 의존성과 인계 계약

| 제공자 → 소비자 | 제공물 | 필요 시점 | 차단되는 작업 |
|---|---|---|---|
| BE-3 → 전원 | User·Pet ID·ActivePetContext·Pet Summary·권한 인터페이스 | 즉시 | 채팅·반응·인사·친구·차단·카드·시드 셋로그 |
| BE-3 → BE-2 | ADMIN 접근 검사와 DB 재검증 | 7/26 | 관리자 신고 큐·전체 대화 조회 |
| BE-1 → BE-2 | 시드 Setlog·Pet 식별자와 조회 계약 | 7/26 | 셋로그 인사 화면 |
| BE-2 → AI-1 | 카드 입력·출력 계약 | 7/24 | 카드 fixture |
| AI-1 → BE-2 | 카드 fixture·timeout/실패 응답 | 7/26 | 카드 AI 연동 |
| 각 도메인 → BE-3 | migration과 제약 목록 | merge 전 | Flyway 통합 |
| 각 도메인 → BE-1 | 실행 환경변수·health·migration 정보 | 7/28 | dev·최종 배포 |

카드 AI가 지연되어도 M1을 막지 않는다. BE-2는 timeout·오류 시 빈 폼으로 카드 작성이 가능한 경로를 먼저 완성한다.

### 6.1 7/27 기준 Pet Core 긴급 실행 순서

1. `GET /me`
2. Pet Entity·다음 버전 Flyway Migration
3. `POST /pets`
4. `PUT /me/active-pet`
5. 채팅용 ActivePetQuery·Pet Summary 계약
6. `GET /pets/me`
7. `GET /pets/{petId}`
8. 등록정보 인증
9. Pet 수정·삭제

`M1-013A`는 v12·v13에서 보존한 기존 작업 ID이며 신규 번호가 아니다.

## 7. M1 Gate

아래 시나리오를 dev 환경에서 3회 연속 통과해야 M1 완료로 본다.

1. 이메일 회원가입에서 시흥동·금토동·사송동 중 동네를 직접 선택하고 로그인·Refresh·Logout한다.
2. Pet을 직접 등록해 첫 Pet 후보와 Active 지정 결과를 확인한다. 예상 가능한 일시적 Active 지정 실패 시 Pet은 유지되고 L1 사용자가 수동 선택한다. 예상 밖 오류가 `RETRY_REQUIRED`로 숨겨지지 않는지 확인한다. 미삭제 Pet 5마리 한도와 삭제 상태 CHECK를 PostgreSQL에서 검증한다.
3. L1 사용자는 시드 셋로그 3개를 보고, L2 사용자는 CUTE·LIKE를 독립적으로 누른다.
4. 셋로그에서 인사하면 DIRECT 방과 최초 메시지가 생성되고, 상대 답변 전 추가 전송은 거절된다.
5. 상대 답변 뒤 polling으로 양방향 TEXT 대화를 확인한다.
6. 최근 24시간 내 사용자 TEXT 2개 이상이면 약속 카드 버튼이 활성화된다. 0~1개 직접 호출과 AI 실패 상황에서도 `INSUFFICIENT_CONTEXT` 빈 폼으로 카드를 확정·조회·취소한다.
7. 친구요청·수락과 반대 방향 동시 요청 자동수락을 확인한다. 친구 삭제 뒤 기존 대화는 유지된다.
8. 차단하면 Friendship·PENDING 요청이 정리되고 인사·메시지·콘텐츠 노출이 차단된다.
9. DIRECT 방을 신고하고 ADMIN 계정으로 신고 큐·사유·방 전체 대화를 조회해 `DISMISSED` 또는 `WARNING`으로 종결한다.
10. BE-1 배포 환경에서 health, DB migration, S3 시드 영상 재생, rollback 절차를 확인한다.

Gate 예외:

- 등록정보 Provider 장애 시 직접 Pet 등록 경로로 통과할 수 있다. Provider 장애는 기록한다.
- canonical 등록번호가 없으면 Attempt를 REJECTED로 종결하고 배지는 발급하지 않으며 직접 등록은 정상 제공한다.
- 카드 AI 품질은 Gate 대상이 아니다. timeout·실패 시 빈 폼 fallback 동작은 Gate 대상이다.
- 일반 사용자·Pet·등록결과 관리자 화면(`M1-026`, `M1-027`)은 P2이며 신고 관리자 흐름을 막지 않는다.

## 8. 위험과 운영 기준

| 위험 | 영향 | 대응 |
|---|---|---|
| BE-2에 채팅·카드·차단·신고·프런트 통합 집중 | M1 최대 병목 | 채팅 Core 우선, 카드 fallback-first, 화면은 API fixture로 병렬 개발 |
| BE-3의 Pet·친구·RBAC 의존 지연 | BE-1·BE-2 작업 연쇄 차단 | 최소 인터페이스·fixture부터 제공하고 일반 관리자 기능은 P2 유지 |
| 시드 영상·DB row 불일치 | 홈·인사 전체 차단 | BE-1이 자산과 seed를 묶어 관리하고 BE-3가 FK를 검토 |
| Provider/RFID 계약 불확실 | 인증 배지 발급 실패 | canonical 등록번호가 없으면 배지 미발급, 직접 등록은 정상 제공 |
| 짧은 일정에서 DB 충돌 | Flyway 실패·merge 지연 | 도메인별 migration 작성, BE-3 번호·제약 검토, merge 전 PostgreSQL 검증 |
| 신고 방과 24시간 삭제 경합 | 신고 증거 소실 | 정리 배치는 신고 존재 여부를 잠금·재검사하고 신고 방을 삭제하지 않음 |

## 9. 현재 저장소 기준 요약

- 구현 확인: 인증·JWT·Refresh Token·동네 seed, 공통 예외·보안 설정, 범용 미디어 기반과 PostgreSQL/Flyway 테스트.
- 부분 구현: 로컬·운영 profile, S3 설정, ADMIN role 기반, DB migration 기반.
- 미구현: Pet, 등록 인증, 셋로그, 반응, 인사, 채팅, 친구, 카드, 차단, 신고, 관리자 신고, React 화면과 실제 배포.
- 현재 코드에 없는 기능을 완료로 표시하지 않는다. 작업 상태는 각 PR merge 시 이 표에서 갱신한다.

## 10. 후속 마일스톤 메모

M2·M3 상세 WBS는 본 문서에 넣지 않는다. M2는 `07_M2_마일스톤_WBS.md`가 담당한다. M2 범위의 정본은 `00_최신_제품정책.md`이며, 이 문단이 예고했던 React Native는 M2에 포함하지 않기로 확정했다(2026-08-07).

회원탈퇴는 기존 M1 작업 ID가 확인되지 않아 새 ID를 만들지 않는다.
과거 ID가 발견되면 번호를 재사용하지 않고 `MOVED(POST-M1)`로 복원한다.
