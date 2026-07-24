# Dogether Backend

## Local setup

1. `.env.example`을 복사해 `.env`를 만들고 로컬 값을 채운다.
2. Java 25와 PostgreSQL을 준비하고 DB를 실행한다.

```powershell
docker compose up -d postgres
```

3. `local` 프로필로 애플리케이션을 시작한다. `.env.example`에는
   `SPRING_PROFILES_ACTIVE=local`이 포함되어 있다. local 프로필은 Flyway 실행 후
   시흥동·금토동·사송동 개발 seed를 자동 적용한다.

개발 seed는 운영 migration 경로에 포함되지 않으며 `prod` 프로필에서는 실행되지 않는다.

4. 빠른 단위 테스트를 실행한다.

```powershell
.\gradlew.bat test
```

5. Docker를 실행한 상태에서 PostgreSQL 통합 테스트를 실행한다.

```powershell
.\gradlew.bat postgresTest
```

`postgresTest`는 Docker가 없으면 실패한다. PR CI는 `test`와
`postgresTest`를 모두 실행하므로 PostgreSQL/Flyway 검증이 SKIP될 수 없다.

실제 `.env`는 Git에 포함하지 않는다. Spring은 프로젝트 루트의 `.env`를
properties 형식으로 선택적으로 읽는다.

## API

- Swagger UI: `/swagger-ui/index.html` (로컬·개발 환경)
- Health: `/actuator/health`
- Auth: `/auth/signup`, `/auth/login`, `/auth/refresh`, `/auth/logout`
- Media: `/media/uploads`, `/media/{id}/complete`, `/media/{id}`

`prod` 프로필에서는 Swagger UI와 OpenAPI JSON을 비활성화한다.
Google OAuth2 의존성은 M2 소셜 로그인 작업에서 추가한다.

## Product contract

- 최신 M1/M2 정책: `docs/spec/00_최신_제품정책.md`
- [기본설정 구조와 설계 이유](docs/프로젝트_기본설정_온보딩.md)
- 기존 v13 문서와 충돌하면 위 정책 문서를 우선한다.
- M1의 `SETLOG` 업로드는 시드 콘텐츠를 적재하는 관리자만 가능하다.
- 일반 사용자의 셋로그 업로드는 M2 범위다.

## Database migration

- 이미 공유된 Flyway migration은 수정하지 않는다.
- 스키마 변경은 다음 버전의 migration 파일로 추가한다.
- PostgreSQL을 정본 DB로 사용하고 통합 테스트에서 Flyway 전체 실행을 확인한다.

운영 AWS에서는 정적 키를 코드에 넣지 않고 IAM Role 또는 표준 AWS 자격증명
체인을 사용한다.
