# 관리자 Dashboard 통계 API

## 목적

`GET /admin/dashboard`는 활성 `ADMIN`, `SUPER_ADMIN`에게 서비스 핵심 통계와 운영 backlog를 제공하는 읽기 전용 API다. Spring Security의 Role 검사 뒤 DB의 현재 계정 상태와 Role을 다시 검증한다.

## 기간

- `from`, `to`는 KST 날짜이며 두 날짜를 모두 포함한다.
- 둘 다 생략하면 KST 오늘을 포함한 최근 7일이다.
- 한쪽만 입력하거나 시작일이 종료일보다 늦으면 `INVALID_DATE_RANGE`다.
- 90일까지 허용하고 91일부터 `DATE_RANGE_TOO_LARGE`다.
- SQL에는 KST 날짜를 `[시작일 00:00, 종료일 다음 날 00:00)` UTC `Instant`로 변환해 전달한다.

## D-08 집계 기준

| 지표 | 포함 기준 | 기간 기준 |
|---|---|---|
| User | 일반 사용자, 탈퇴 제외, 정지 포함 | `created_at` |
| Pet | 논리 삭제 제외, 정지 포함 | `created_at` |
| Setlog | `VISIBLE`, 비 Seed | `created_at` |
| BoardPost | `PUBLISHED`, 미삭제 | `created_at` |
| Report 생성 | 상태 무관 | `created_at` |
| Report open | 현재 `OPEN` | 기간 무관 |
| Risk | 기간 내 Signal 및 고유 actor(Safety의 subject) | `occurred_at` |
| Safety open | 현재 `OPEN`, `REVIEWING` | 기간 무관 |
| Storage | 현재 `PENDING`, `RETRY`, `FAILED` | 기간 무관 |

최근 항목은 요청 기간과 무관한 Report·SafetyCase 전체 최신 10건이다. 정렬은 `created_at DESC, source ASC, id DESC`이며 Report 원문이나 Risk metadata는 조회하지 않는다.

## 쿼리와 운영 확인

Dashboard 호출은 데이터 건수와 무관하게 관리자 DB 재검증 1개와 집계 3개, 총 4개 SQL을 실행한다.

1. scalar subquery를 사용한 핵심 count 1회
2. RiskSignal 유형별 group 1회
3. Report·SafetyCase 최근 목록 union 1회

PostgreSQL 16 통합 fixture에서 대표적인 User 기간, Risk 기간·유형, Storage backlog 쿼리에 `EXPLAIN (ANALYZE, BUFFERS)`를 실행해 계획과 실행이 정상 완료되는 것을 검증했다. 작은 fixture의 결과만으로는 새 인덱스 근거가 충분하지 않다. 운영 규모와 유사한 데이터에서 Pet/BoardPost/Report와 recent union을 포함해 scan row·buffer를 다시 측정하고, 비용이 확인될 때 별도 migration으로 인덱스를 추가한다.
