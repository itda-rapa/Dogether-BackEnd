# Dogether Backend

## Local setup

### 실행에 필요한 파일

- `.env`: 각 개발 PC에서 작성하는 로컬 실행값이다. Git에는 포함하지 않는다.
- `.env.example`: `.env`에 필요한 환경 변수 목록과 안전한 예시값이다.
- `Dockerfile`, `docker-compose.yml`: 애플리케이션 이미지와 로컬 PostgreSQL·RustFS 실행 구성을 정의한다.
- `build.gradle`, `settings.gradle`: Java 버전, 의존성, 테스트 및 프로젝트 이름을 정의한다.
- `gradlew`, `gradlew.bat`, `gradle/wrapper/*`: 별도 Gradle 설치 없이 프로젝트를 빌드하고 실행한다.
- `src/main/resources/application*.yaml`: 공통·로컬·운영 Spring 설정을 정의한다.

일반적인 로컬 실행에서는 `.env`만 개발 환경에 맞게 작성하면 된다. 나머지 파일은
프로젝트에 포함된 설정 파일이므로 실행 환경이 바뀔 때만 수정한다.

### 실행 순서

1. `.env.example`을 복사해 `.env`를 만들고 로컬 값을 채운다.
2. Docker Desktop을 준비하고 Docker 엔진이 실행 중인지 확인한 뒤 PostgreSQL과
   RustFS를 먼저 실행한다.

```powershell
docker compose up -d postgres rustfs
docker compose ps
```

3. RustFS 콘솔에서 로컬 버킷을 수동으로 생성한다.

- 콘솔: `http://localhost:9001/rustfs/console/index.html`
- 계정: `.env`의 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
- 버킷 이름: `.env`의 `S3_BUCKET` (기본값 `dogether-local`)

RustFS 콘솔에 로그인해 `S3_BUCKET`과 동일한 이름의 버킷을 만든다. 이미 버킷이
존재하면 이 단계는 생략한다. 버킷 데이터는 `dogether-rustfs-data` 볼륨에
저장되므로 일반적인 `docker compose down` 이후에도 유지된다.

4. 버킷 생성 후 애플리케이션을 빌드하고 실행한다.

```powershell
docker compose up -d --build app
docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health
```

PostgreSQL, RustFS, 애플리케이션 세 컨테이너가 `healthy`이고 Health 응답의
`status`가 `UP`이면 실행이 완료된 것이다.
PostgreSQL의 기본 호스트 포트는 `5432`를 사용한다.
RustFS의 기본 API 포트는 `9000`, 콘솔 포트는 `9001`을 사용한다.
애플리케이션 기본 호스트 포트는 `8080`이며, 충돌하면 `.env`의 `APP_PORT`를
변경하고 Health URL에도 같은 포트를 사용한다.

애플리케이션을 IDE 또는 Gradle로 직접 실행할 때는 Java 25를 준비하고
PostgreSQL과 RustFS를 시작한다. 최초 실행이면 위와 동일하게 RustFS 콘솔에서
버킷을 생성한다.

```powershell
docker compose up -d postgres rustfs
.\gradlew.bat bootRun
```

`.env.example`에는 `SPRING_PROFILES_ACTIVE=local`과 `FLYWAY_ENABLED=false`가
포함되어 있다. 현재 로컬 기본값에서는 Flyway를 자동 실행하지 않는다. 빈 DB를
처음 구성하거나 migration 적용이 필요할 때만 `.env`의 값을 일시적으로 `true`로
변경해 실행한 뒤 다시 `false`로 복원한다. Flyway가 활성화된 local 프로필은
`db/migration`과 `db/seed`를 적용하며, 개발 seed는 `prod` 프로필에서 실행되지
않는다.

5. 빠른 단위 테스트를 실행한다.

```powershell
.\gradlew.bat test
```

6. Docker를 실행한 상태에서 PostgreSQL 통합 테스트를 실행한다.

```powershell
.\gradlew.bat postgresTest
```

`postgresTest`는 Docker가 없으면 실패한다. PR CI는 `test`와
`postgresTest`를 모두 실행하므로 PostgreSQL/Flyway 검증이 SKIP될 수 없다.

7. RustFS 호환성을 다시 확인해야 할 때 전용 통합 테스트를 수동 실행한다.

```powershell
.\gradlew.bat rustfsTest
```

`rustfsTest`는 별도의 임시 RustFS 컨테이너와 테스트 버킷을 생성해 Presigned
PUT, HEAD, GET, DELETE를 검증한다. 로컬 Compose의 `dogether-local` 버킷을
사용하지 않으며 일반 `test`와 CI에서는 자동 실행하지 않는다.

작업을 마치면 데이터 볼륨을 유지한 채 컨테이너를 종료한다.

```powershell
docker compose down
```

실제 `.env`는 Git에 포함하지 않는다. Spring은 프로젝트 루트의 `.env`를
`local` 프로필에서만 properties 형식으로 읽는다.

### RustFS와 AWS S3 전환

로컬 환경에서는 RustFS를 S3 호환 저장소로 사용한다.

| 설정 | 로컬 RustFS | 운영 AWS S3 |
|---|---|---|
| `S3_ENDPOINT` | `http://localhost:9000` | 빈 값 |
| `S3_PRESIGN_ENDPOINT` | `http://localhost:9000` | 빈 값 |
| `S3_PATH_STYLE` | `true` | `false` |
| 버킷 생성 | RustFS 콘솔에서 수동 생성 | AWS 인프라에서 별도 생성 |

Docker Compose로 애플리케이션을 실행할 때는 내부 통신을 위해
`S3_ENDPOINT=http://rustfs:9000`으로 자동 덮어쓴다. Presigned URL은
브라우저에서 접근할 수 있도록 `localhost:9000`을 사용한다.

AWS S3로 전환할 때는 RustFS 컨테이너와 로컬 버킷 생성 절차를 운영 환경에
사용하지 않는다. AWS에서 버킷, CORS, IAM 권한을 별도로 구성하고 endpoint를
비운 뒤 path-style을 비활성화한다. 운영 자격증명은 코드나 `.env`에 저장하지
않고 IAM Role 또는 표준 AWS 자격증명 체인을 사용한다.

## Spring Profiles

| Profile | 용도 | DB·Flyway |
|---|---|---|
| `local` | 개발 PC와 Docker Compose | PostgreSQL, Flyway 기본 비활성화, 활성화 시 migration과 seed 적용 |
| `test` | Gradle 단위·통합 테스트 | 일반 테스트는 H2/Flyway 비활성화, `postgresTest`와 `rustfsTest`는 Testcontainers 사용 |
| `prod` | 운영 배포 | 운영 PostgreSQL, Flyway 기본 비활성화, migration만 적용 가능 |

Gradle의 모든 `Test` 작업은 `test` 프로필을 자동 활성화한다. `prod` 프로필은
프로젝트의 `.env`를 읽지 않으며 다음 환경변수에 개발용 기본값을 사용하지 않는다.

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `JWT_ISSUER`, `JWT_SECRET`, `JWT_ACCESS_TTL`, `JWT_REFRESH_TTL`
- `CORS_ALLOWED_ORIGINS`
- `S3_BUCKET`, `AWS_REGION`

위 값이 없으면 운영 애플리케이션은 시작 단계에서 실패한다. `prod`에서는 Swagger가
항상 비활성화되고 개발 seed 경로가 Flyway locations에 포함되지 않는다.

## API

- Swagger UI: `/swagger-ui/index.html` (로컬·개발 환경)
- Health: `/actuator/health`
- Neighborhood: `GET /neighborhoods` (공개)
- Auth: `/auth/signup`, `/auth/login`, `/auth/refresh`, `/auth/logout`
- Media: `/media/uploads`, `/media/{id}/complete`, `/media/{id}`

`prod` 프로필에서는 Swagger UI와 OpenAPI JSON을 비활성화한다.
Google OAuth2 의존성은 M2 소셜 로그인 작업에서 추가한다.

## Product contract

- [M1 계약 문서 안내](docs/spec/README.md)
- [최신 제품 정책](docs/spec/00_최신_제품정책.md)
- [M1 정적 OpenAPI](docs/spec/04_M1_OpenAPI.yaml)
- [기본설정 구조와 설계 이유](docs/프로젝트_기본설정_온보딩.md)
- 기본설정 온보딩은 현재 코드 기반 설명이며 M1 제품 계약 정본이 아니다.
- M1의 `SETLOG` 업로드는 시드 콘텐츠를 적재하는 관리자만 가능하다.
- 일반 사용자의 셋로그 업로드는 M2 범위다.

## Collaboration

- [Git·브랜치·PR 규칙](docs/convention/01_Git_브랜치_PR_규칙.md)
- [백엔드 코딩 컨벤션](docs/convention/02_코딩_컨벤션.md)

## Database migration

- 이미 공유된 Flyway migration은 수정하지 않는다.
- 스키마 변경은 다음 버전의 migration 파일로 추가한다.
- PostgreSQL을 정본 DB로 사용하고 통합 테스트에서 Flyway 전체 실행을 확인한다.

운영 AWS에서는 정적 키를 코드에 넣지 않고 IAM Role 또는 표준 AWS 자격증명
체인을 사용한다.
