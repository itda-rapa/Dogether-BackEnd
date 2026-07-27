# Dogether Git·브랜치·PR 규칙

이 문서는 Dogether 백엔드 저장소의 Git 협업 정본이다. Asset-Box의 협업 규칙을
Dogether의 현재 브랜치, 도메인, CI 구성에 맞게 보정했다.

## 1. 기본 원칙

- 한 브랜치는 한 가지 목적만 다룬다.
- 모든 작업 브랜치는 최신 `dev`에서 시작한다.
- `dev`와 `main`에는 직접 push하지 않고 PR로만 반영한다.
- 공유 브랜치에 올라간 커밋은 rebase하거나 force push하지 않는다.
- 공유 설정과 계약을 수정하기 전 담당자에게 변경 범위를 알린다.
- 실제 시크릿, 토큰, 개인정보, `.env`는 커밋하지 않는다.

## 2. 브랜치 전략

현재 Dogether는 `dev`와 `main`의 2단계 전략을 사용한다.

```text
작업 브랜치
    │ PR
    ▼
   dev        개발 통합·테스트
    │ 릴리스 PR
    ▼
   main       배포 가능한 안정 버전
```

| 브랜치 | 역할 | 머지 가능한 출처 |
|---|---|---|
| `feature/*` | 신규 기능 | `dev`에서 분기 |
| `fix/*` | 일반 버그 수정 | `dev`에서 분기 |
| `refactor/*` | 동작을 바꾸지 않는 구조 개선 | `dev`에서 분기 |
| `test/*` | 테스트 전용 변경 | `dev`에서 분기 |
| `chore/*` | 빌드·CI·환경설정·의존성 | `dev`에서 분기 |
| `docs/*` | 정책·설계·온보딩·컨벤션 문서 | `dev`에서 분기 |
| `hotfix/*` | 배포본 긴급 수정 | `main`에서 분기 후 `main`, `dev`에 모두 반영 |
| `dev` | 개발 통합 브랜치 | 작업 브랜치 |
| `main` | 배포 기준 브랜치 | `dev` 또는 `hotfix/*` |

`prod` 브랜치는 현재 사용하지 않는다. 별도 QA 배포 브랜치가 실제로 필요해질 때
팀 합의 후 문서와 CI를 함께 수정한다.

## 3. 브랜치 이름

형식:

```text
<type>/<domain>-<topic>-<issue-number>
```

규칙:

- 영문 소문자와 숫자만 사용한다.
- 단어는 하이픈(`-`)으로 구분한다.
- 끝에 GitHub Issue 번호를 붙인다.
- `#` 문자는 브랜치 이름에 넣지 않는다.
- topic은 두세 단어 이내로 짧게 작성한다.

예:

```text
feature/auth-login-12
feature/pet-registration-21
fix/chat-duplicate-message-34
refactor/media-upload-policy-42
test/friend-request-concurrency-51
chore/postgres-ci-8
docs/project-bootstrap-onboarding-63
```

### Dogether 도메인

```text
auth
user
neighborhood
pet
verification
friend
block
chat
setlog
meeting
report
moderation
admin
media
infra
common
```

둘 이상의 도메인이 필요한 작업은 중심 도메인 하나를 선택한다. 여러 도메인을
동등하게 변경한다면 작업을 나눌 수 없는지 먼저 검토한다.

## 4. 작업 시작

```powershell
git switch dev
git pull origin dev
git switch -c feature/auth-login-12
```

기존 작업 브랜치에서 `dev` 최신 내용을 가져올 때:

```powershell
git fetch origin
git merge origin/dev
```

공유한 브랜치에서는 rebase 대신 merge로 최신화한다. 충돌을 해결한 뒤 테스트하고
일반 push한다.

## 5. 커밋 메시지

PR 제목과 개별 커밋 메시지는 같은 형식을 사용한다.

```text
[TYPE] 한국어 한 줄 요약
```

| TYPE | 용도 |
|---|---|
| `FEAT` | 사용자에게 보이는 기능 추가 |
| `FIX` | 잘못된 동작 수정 |
| `CHORE` | 빌드, CI, 환경설정, 의존성 |
| `DOCS` | 정책, 설계, 온보딩, 컨벤션 |
| `REFACTOR` | 기능 변화 없는 구조 개선 |
| `TEST` | 테스트 추가·수정 |
| `PERF` | 성능 개선 |
| `WIP` | 원격 Push 전 로컬에서만 사용하는 임시 작업 |

예:

```text
[FEAT] 펫 등록 API 추가
[FIX] 만료 미디어 상태 롤백 문제 수정
[CHORE] PostgreSQL 통합 테스트 CI 추가
[DOCS] 프로젝트 기본설정 온보딩 문서 추가
[REFACTOR] 친구 요청 상태 검증 로직 분리
[TEST] 동시 친구 요청 자동수락 테스트 추가
```

`WIP` 커밋은 로컬 작업 보호에만 사용한다. 공유 브랜치에 Push하기 전 의미 있는
타입과 단위로 정리하며, 이미 Push한 커밋을 고치기 위한 force push는 하지 않는다.

커밋에는 하나의 논리적 변경만 담는다. `수정`, `작업`, `최종`, `Initial Commit`
같이 내용을 알 수 없는 메시지는 사용하지 않는다.

## 6. PR 규칙

### 대상 브랜치

- 일반 작업 PR: 작업 브랜치 → `dev`
- 릴리스 PR: `dev` → `main`
- hotfix PR: `hotfix/*` → `main`, 이후 동일 변경을 `dev`에도 반영

### PR 크기

- 한 PR은 하나의 목적만 가진다.
- 변경량은 400줄 이내를 권장한다.
- 생성 코드, migration, 계약 문서 때문에 커졌다면 PR 본문에서 이유를 설명한다.
- 기능 추가와 무관한 대규모 포맷팅을 같은 PR에 섞지 않는다.

### 제목

커밋 메시지와 동일한 형식을 사용한다.

```text
[CHORE] 프로젝트 기본설정과 공통 인증 기반 구성
```

### 본문 필수 항목

- 왜 변경하는지
- 무엇을 변경했는지
- 영향받는 도메인
- 실행한 테스트와 실행하지 못한 테스트
- DB·환경변수·보안 영향
- API·ERD·상태 전이 변경 여부
- 리뷰어가 집중해서 볼 부분

PR 본문의 이슈 연결은 다음 형식을 사용한다.

```text
Closes #12
```

### 문서 분리

- API·ERD·enum·상태 전이처럼 구현 계약을 바꾸는 문서는 코드 PR과 함께 수정하거나
  코드보다 먼저 Docs PR로 반영한다.
- 온보딩, 컨벤션, 운영 가이드는 별도 `docs/*` 브랜치와 `[DOCS]` PR로 분리할 수 있다.
- README가 별도 문서를 링크한다면 링크와 대상 문서는 같은 PR에서 반영한다.

## 7. 필수 리뷰어

| 변경 영역 | 필수 검토 대상 |
|---|---|
| 도메인 내부 코드 | 해당 도메인 담당자 |
| `SecurityConfig`, JWT, 권한 | Auth·Admin 담당자 |
| 공통 응답·예외 | 영향받는 도메인 담당자 |
| Entity·Flyway·인덱스 | DB·Infra 담당자와 영향 도메인 |
| `build.gradle`, CI, Docker, YAML | Infra 담당자 |
| API·ERD·상태 전이 | 영향받는 Client·Backend 담당자 |

최소 승인 수와 필수 리뷰어는 GitHub 브랜치 보호 규칙을 정본으로 한다.

## 8. 머지 규칙

- CI가 통과하고 필수 리뷰가 끝난 뒤 머지한다.
- 기본 머지 방식은 GitHub의 `Create a merge commit`이다.
- 공유 브랜치의 커밋을 다시 쓰는 rebase merge와 force push는 사용하지 않는다.
- squash merge는 개별 커밋을 보존해야 하는 PR에서는 사용하지 않는다.
- 머지된 작업 브랜치는 원격에서 삭제한다. 커밋과 PR 기록은 GitHub에 남는다.

팀이 GitHub 저장소 설정에서 다른 머지 방식을 확정하면 설정과 이 문서를 함께
수정한다.

## 9. CI와 완료 조건

일반 단위·컨텍스트 테스트:

```powershell
.\gradlew.bat test
```

PostgreSQL·Flyway 통합 테스트:

```powershell
.\gradlew.bat postgresTest
```

PR 완료 조건:

- 컴파일과 `test` 통과
- PostgreSQL 기능을 변경했다면 `postgresTest` 통과
- 신규 설정값은 `.env.example`과 README에 반영
- DB 변경은 새 Flyway migration으로 추가
- 실제 시크릿과 개인정보가 diff에 없는지 확인
- API·ERD·enum·상태 변경 시 정본 문서 동기화

## 10. 충돌 방지

다음 파일은 공유 자원이므로 수정 전에 팀에 알린다.

```text
build.gradle
application*.yaml
SecurityConfig.java
ErrorCode.java
BaseEntity.java
Flyway migration
OpenAPI·ERD·상태 전이 문서
GitHub Actions
```

- 작업 시작과 PR 직전에 `dev`를 최신화한다.
- 동일 파일을 여러 명이 동시에 수정하지 않도록 담당자를 정한다.
- migration 버전 번호가 겹치면 먼저 머지된 번호를 유지하고 나머지가 새 번호를 쓴다.
- 해결이 30분 이상 막히면 혼자 진행하지 말고 담당자에게 공유한다.
