# 같이놀개 M3 계약 문서 안내

> 기준: 2026-08-20 백엔드 회의 반영 업무분담  
> 범위: M3 신규 기능과 M2 보완 중 API·DB 계약이 바뀌는 항목  
> 상태: 2026-08-20 노동훈 피드백 반영 v2. `결정 대기`는 G1 합의 후 확정

## 문서 기준과 우선순위

업무 범위·담당자·완료 조건은 먼저 작성된 다음 두 문서를 정본으로 고정한다.

1. [M3 백엔드 업무분담 산출물](M3_백엔드_업무분담_산출물.md)
2. [M3 기준 마일스톤·WBS](M3기준_마일스톤_WBS.md)

이 명세 세트는 위 정본의 업무와 일정을 변경하지 않고 API·DB 구현 계약을 보충한다. 기술 계약끼리 충돌할 때는 다음 순서를 따른다.

1. 팀이 승인한 `05_M3_결정사항_보완과제.md`
2. `04_M3_API_상세명세.md`
3. `03_M3_상태전이.md`
4. `02_M3_API_계약.md`
5. `01_M3_통합_ERD.md`
6. `00_M3_제품정책.md`

현재 코드와 이 문서가 다르면 이 문서는 M3 목표 계약이며, 구현 PR에서 코드·테스트·문서를 함께 맞춘다.

## 문서별 용도

| 문서 | 용도 |
|---|---|
| `00_M3_제품정책.md` | 사용자 정책, 권한, 범위, 불변조건 |
| `01_M3_통합_ERD.md` | 신규·변경 테이블, 키, 제약, 보존 정책 |
| `02_M3_API_계약.md` | 전체 Endpoint·WebSocket·Kafka 요약 |
| `03_M3_상태전이.md` | Meeting, Media, Chat, Safety, Walk 상태 머신 |
| `04_M3_API_상세명세.md` | JSON Request/Response, 권한, 오류, 멱등성 |
| `05_M3_결정사항_보완과제.md` | 확정사항, 결정 대기, 오류 코드, 체크리스트 |

세부 업무와 일정은 같은 폴더의 다음 문서를 최종 기준으로 한다.

- [M3 백엔드 업무분담 산출물](M3_백엔드_업무분담_산출물.md)
- [M3 기준 마일스톤·WBS](M3기준_마일스톤_WBS.md)
- [M3 백엔드 연동 계약 및 의존성](M3_백엔드_연동계약_및_의존성.md)

## v2 주요 변경

- `05`를 D-번호 유일 기준으로 삼고 체크리스트를 D-01~D-17로 동기화
- 비로그인 공개 미도입 확정
- OAuth callback·신규 signup·동일 이메일 연결 후보 계약 보완
- Board/Comment 응답을 현재 DTO와 하위 호환되게 수정
- 대댓글 조회·Place upsert·PATCH 생략/null 의미 명시
- Pet 이미지 동시성·StorageDeleteJob 조건 보완
- FriendRequest RiskSignal, Redis 완료 기준, Media batch WBS 보완

## 노동훈 피드백 반영 위치

| 피드백 | 반영 문서 |
|---|---|
| 1 D-번호 통일 | `05`, `M3_백엔드_결정사항_리스크_체크리스트` |
| 2~5 HELPFUL·BoardPost·Comment 하위 호환 | `00`, `03`, `04` |
| 6~10 OAuth callback·signup·동일 이메일·Token·code 오류 | `00`~`05`, WBS |
| 11~13 대댓글 조회·Place ID·PATCH 의미 | `00`~`05`, WBS |
| 14~17 Pet 삭제·이미지 범위·동시성·Storage 정리 | `00`, `03`~`05`, 업무분담·연동 계약 |
| 18~20 FriendRequest event·Redis 완료선·Media batch 선행 | 업무분담·WBS·연동 계약 |
| 21 비로그인 미도입 | `00`, `05`, 체크리스트·WBS |
| 22 목록 API 크기 예외 | `02`, `04` |

## 구현 원칙

- 애플리케이션 경로는 Controller 기준으로 기술한다. 외부 Gateway가 `/api/v1`을 붙이는 경우 중복 prefix를 만들지 않는다.
- REST 응답은 현재 코드의 `ApiResponse<T>`를 사용한다.
- 모든 시각은 ISO 8601 UTC, 날짜 통계 경계만 `Asia/Seoul`로 계산한다.
- 생성·전송 요청은 UUID 멱등키를 사용한다.
- Media와 Setlog는 ID를 정본으로 저장하고 Presigned URL·원문 snapshot을 영구 저장하지 않는다.
- 위치·대화 원문·JWT·OAuth code를 로그에 남기지 않는다.
- REST, DIRECT WebSocket, Open Chat Kafka/Consumer의 typed message 의미를 일치시킨다.
