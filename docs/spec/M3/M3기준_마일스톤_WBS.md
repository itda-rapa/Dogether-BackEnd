# M3 기준 마일스톤·WBS

> 기준: 2026-08-20 백엔드 업무분담 회의  
> 코드·통합 종료: 2026-08-26  
> 발표: 2026-08-28

## 1. 마일스톤

| 날짜 | Gate | 목표 | 통과 조건 |
|---|---|---|---|
| 8/20 | G0 회의 반영 | 담당·중복 영역·결정 대기 식별 | 업무분담·WBS 공유 |
| 8/21 | G1 계약 동결 | API·DTO·Event·DB·권한·오류 계약 | 공용 계약 리뷰 완료, breaking change 통제 |
| 8/22 | G2 기반 구현 | 각 담당의 DB와 happy path | 신규 Migration 적용, 단위 테스트 |
| 8/23 | G3 기능 연결 | REST/WS/Kafka/Media/Map 연결 | 담당별 수직 슬라이스 통과 |
| 8/24 | G4 교차 통합 | Front·AI·도메인 간 E2E | 핵심 경로 1회 성공, blocker 해소 |
| 8/25 | G5 Release Candidate | 권한·동시성·삭제·장애·성능 검증 | Sev-1/2 0건, 배포 E2E·seed·Runbook |
| 8/26 | G6 Freeze | 오전 회귀, 오후 기능 동결 | 전체 회귀, 시연 2회, 인계 완료 |
| 8/27 | 리허설 | 치명 결함만 대응 | 신규 기능·계약 변경 없음 |
| 8/28 | 발표 | 안정적 시연 | 합의된 시나리오 사용 |

## 2. 공통 선행 WBS

| ID | 담당 | 작업 | 완료 조건 | 목표일 |
|---|---|---|---|---|
| M3-000 | 전원 | 업무분담·리뷰어·PR 경계 확정 | 중복 구현 영역 없음 | 8/20 |
| M3-001 | 전원 | 공용 Flyway 번호표와 dirty worktree 정리 | 신규 Migration 충돌 없음 | 8/21 |
| M3-002 | 김승균+박선+이정수 | Chat typed message 계약 | TEXT/CARD/IMAGE/VIDEO/SETLOG_SHARE/SYSTEM 의미 합의 | 8/21 |
| M3-003 | 김승균+이정수 | Location·Meeting 계약 | 좌표·accuracy·capturedAt·거리 책임 합의 | 8/21 |
| M3-004 | 박선+각 도메인 | RiskSignal event 계약 | sourceType/sourceId/actor/target/time/idempotency 합의 | 8/21 |
| M3-005 | 노동훈+이정수 | Board Place 계약 | Provider upsert·내부 placeId·요약·지도 이동 계약 | 8/21 |
| M3-006 | 전원 | 비로그인 공개 범위 결정 | 로그인 기반 유지 확정·Security 변경 없음 | 완료 |

## 3. 김승균 WBS

| ID | 작업 | 선행 | 완료 증거 | 목표일 |
|---|---|---|---|---|
| M3-BE2-101 | DIRECT/인사/약속/차단/신고 공개 계약 정리 | M3-000 | API·Event·권한표 | 8/21 |
| M3-BE2-102 | DIRECT IMAGE/VIDEO/SETLOG_SHARE 처리 | M3-002 | REST/WS 계약·권한 테스트 | 8/23 |
| M3-BE2-103 | 만남 위치 제출·양쪽 확인 | M3-003 | 거리·시간·중복 PG 테스트 | 8/23 |
| M3-BE2-104 | 확인 코드 fallback | M3-BE2-103 | TTL·시도·1회용 테스트 | 8/24 |
| M3-BE2-105 | 만남 후기·발자국 | M3-BE2-103 | skip·적립·하루 중복 테스트 | 8/24 |
| M3-BE2-106 | DIRECT/Greeting/Block/leave 위험 event | M3-004 | source별 event 계약 테스트 | 8/23 |
| M3-BE2-107 | 게시판 답변자→DIRECT 연결 | M3-BE2-101 | 기존 방 재사용·차단 테스트 | 8/24 |
| M2-BE2-201 | 기존 Chat·신고 예외/권한 회귀 | M3-BE2-101 | 회귀 테스트 | 8/25 |

## 4. 노동훈 WBS

| ID | 작업 | 선행 | 완료 증거 | 목표일 |
|---|---|---|---|---|
| M3-BE3-101 | Pet 삭제·Active Pet 정합성 | M3-000 | 상태·참조 PG 테스트 | 8/23 |
| M3-BE3-102 | 게시글 `한 수 배웠어요` | M3-000 | 멱등·누계·조회 테스트 | 8/22 |
| M3-BE3-103 | Google OAuth | M3-000 | callback·loginCode·신규 signup·기존 Token 계약 테스트 | 8/23 |
| M3-BE3-104 | Naver OAuth | M3-BE3-103 | callback·loginCode·신규 signup·동일 이메일 정책 테스트 | 8/24 |
| M3-BE3-105 | Pet 프로필·Board Post 이미지 수정·삭제 | M3-000 | 소유권·동시성·Storage 정리 테스트 | 8/24 |
| M3-BE3-106 | FriendRequest reject RiskSignal event | M3-004 | eventId 멱등·재처리 계약 테스트 | 8/23 |
| M2-BE3-201 | 대댓글·계층형 댓글 조회 | M3-000 | V32 hierarchy FK/CHECK·depth 0~3·strict reply endpoint·Root cursor nested tree·tombstone/Block subtree·Parent/Post DELETE 경합 테스트 | 8/24 |
| M2-BE3-202 | 게시판 Place 연결 | M3-005 | 장소 첨부·지도 이동 테스트 | 8/24 |
| M2-BE3-203 | Redis Cache/Lock 적용 판단 | 측정 결과 | 측정 결과와 적용/미적용 근거; 적용 시 TTL·무효화/Lock 실패 테스트 | 8/25 |
| M2-BE3-204 | Pet/Board 이미지 N+1 제거 | M3-BE-MIA-102 | 공용 batch 계약과 쿼리 수 회귀 테스트 | 8/25 |

## 5. 박선 WBS

| ID | 작업 | 선행 | 완료 증거 | 목표일 |
|---|---|---|---|---|
| M3-BE-MIA-101 | 관리자 Dashboard 통계 | M3-000 | 권한·기간·KST·DB 대조 테스트 | 8/22 |
| M3-BE-MIA-102 | Chat Media 참조·검증·batch URL | M3-002 | 소유권·MIME·상태·N+1 테스트 | 8/22 |
| M3-BE-MIA-103 | 이미지·영상 Front 업로드 연동 | M3-BE-MIA-102 | upload→SEND→수신 E2E | 8/23 |
| M3-BE-MIA-104 | Setlog 공유 summary·접근 정책 | M3-002 | 삭제·차단·batch 조회 테스트 | 8/23 |
| M3-BE-MIA-105 | Setlog 공유 버튼·상세 route 연동 | M3-BE-MIA-104 | 공유→카드→상세 E2E | 8/24 |
| M3-BE-MIA-106 | RiskSignal 저장·집계 | M3-004 | 멱등·기간·임계값 PG 테스트 | 8/23 |
| M3-BE-MIA-107 | 관리자 안전 Queue·감사 | M3-BE-MIA-106 | 권한·동시 처리·감사 테스트 | 8/24 |
| M3-BE-MIA-108 | 배포·seed·Runbook | 각 필수 기능 | 배포 E2E·시연 2회 | 8/25 |
| M2-BE-MIA-201 | Admin 기준선·테스트 정리 | M3-000 | 기존 관리자 회귀 0 failure | 8/21 |
| M2-BE-MIA-202 | Media N+1 개선 지원 | M3-BE-MIA-102 | Feed/Chat SQL 수 테스트 | 8/25 |
| M2-BE-MIA-203 | Setlog 반응 Lock 경합 측정 | 테스트 데이터 | lock wait·정합성 결과 | 8/25 |
| M2-BE-MIA-204 | StorageDeleteWorker 실제 S3 보완 | 측정 결과 | retry·backlog·lease 테스트 | 8/25 |
| M2-BE-MIA-205 | 배포 설정 점검 | M3-BE-MIA-108 | Flyway/Kafka/WS/S3 smoke | 8/25 |

## 6. 이정수 WBS

| ID | 작업 | 선행 | 완료 증거 | 목표일 |
|---|---|---|---|---|
| M3-BE4-101 | AI 검열 Request/Response 계약 | AI 담당 협의 | 정상·위험 fixture 계약 | 8/21 |
| M3-BE4-102 | Open Chat 비동기 검열 연동 | M3-BE4-101 | AI 장애 격리·retry/DLQ 테스트 | 8/23 |
| M3-BE4-103 | 공통 Place·Provider adapter | M3-000 | provider mock·좌표 테스트 | 8/22 |
| M3-BE4-104 | 지도 Front·검색 | M3-BE4-103 | 검색·표시·오류 E2E | 8/23 |
| M3-BE4-105 | 약속 카드·게시판 Place 연동 | M3-003,M3-005 | 양쪽 화면 이동 테스트 | 8/24 |
| M3-BE4-106 | 현재 계정 GPS 계약 | M3-003 | 권한·accuracy·stale 테스트 | 8/22 |
| M3-BE4-107 | 산책 경로 저장·조회 | M3-BE4-103 | 경로 저장·권한 테스트 | 8/24 |
| M3-BE4-108 | 산책 안내·가중치 정책 | AI 담당 협의 | 합의 완료선 시연 | 8/25 |
| M3-BE4-109 | 로깅 범위 결정·최소 적용 | M3-000 | 민감정보 미기록 검증 | 8/25 |
| M2-BE4-201 | Open Chat IMAGE/VIDEO/SETLOG_SHARE | M3-002,박선 Media | Kafka→Consumer→STOMP 테스트 | 8/24 |

## 7. AI 협업 WBS

| ID | 담당 | 작업 | 완료 증거 | 목표일 |
|---|---|---|---|---|
| M3-AI-101 | AI 담당+이정수 | 대화 위험 판정 계약 | type/score/reason/timeout schema | 8/21 |
| M3-AI-102 | AI 담당 | 정상·위험·오탐 fixture와 평가 | precision 중심 결과 | 8/23 |
| M3-AI-103 | AI 담당+이정수 | 산책 경로 가중치·추천 범위 | 입력·출력·fallback 합의 | 8/23 |
| M3-AI-104 | AI 담당+박선 | AI 결과→RiskSignal 매핑 | 중복·장애 시 처리 계약 | 8/24 |

## 8. 통합 Gate 체크리스트

### G1 — 계약

- [ ] Chat typed message와 Media/Setlog payload
- [ ] Location·Meeting 거리 판정 책임
- [ ] RiskSignal event와 멱등키
- [ ] Place·Board·Map DTO
- [ ] OAuth callback·신규 signup·동일 이메일 연결 정책
- [x] 대댓글 depth 0~3·별도 Reply CREATE·Root cursor nested tree·tombstone·Block subtree·`COMMENT_DEPTH_EXCEEDED` 계약
- [ ] Board Place upsert와 PATCH 생략/null 의미
- [x] 비로그인 공개 미도입·로그인 기반 유지

### G3 — 기능

- [ ] 각 신규 Migration이 clean DB에 적용
- [ ] 담당별 happy path와 권한 테스트
- [ ] REST/WS/Kafka payload 일치
- [ ] Media·Setlog batch 조회

### G4 — 통합

- [ ] Chat 이미지·영상·Setlog 공유
- [ ] 약속→GPS→만남→후기→발자국
- [ ] Board Place→지도, 게시판 답변자→DIRECT
- [ ] AI 결과→RiskSignal→관리자 Queue
- [ ] Pet 삭제 후 Chat/Open Chat 참조 정합성

### G5 — Release Candidate

- [ ] 전체 Unit/PG/Redis/Storage/WS/Consumer 테스트
- [ ] 배포 환경 Flyway·Kafka·WS·S3 검증
- [ ] 개인정보·로그·Evidence 검토
- [ ] 발표 seed와 rollback/fallback
- [ ] Sev-1/2 0건

## 9. 범위 조절 원칙

회의에서 담당된 핵심 기능의 happy path는 유지한다. 일정이 부족하면 다음 표현·고도화부터 줄인다.

1. Dashboard 그래프와 상세 운영 카드
2. 채팅 다중 첨부, 영상 썸네일·고급 재생
3. Setlog 공유 카드의 부가 통계
4. Redis·ELK의 전면 도입
5. 산책 안내의 복잡한 AI 가중치

계약 변경은 8월 21일 이후 담당자 전원 공유 없이 진행하지 않는다.
