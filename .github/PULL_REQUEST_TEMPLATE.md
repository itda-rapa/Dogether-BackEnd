## 🔗 관련 이슈
- close #

## 왜
- (어떤 기능·버그·기획 결정을 반영하는지 한 줄로 작성)

## 어떻게
- 핵심 변경 1
- 핵심 변경 2

## 영향받는 도메인
- [ ] Auth / Security
- [ ] User / Neighborhood
- [ ] Pet / Verification
- [ ] Friend / Block
- [ ] Chat
- [ ] Setlog
- [ ] Meeting Card
- [ ] Moderation / Report
- [ ] Admin
- [ ] Media / S3
- [ ] Infra / DB / CI

## 테스트
- 실행 명령:
- 검증한 성공·실패·권한 시나리오:
- PostgreSQL/Flyway 또는 외부 연동 검증:

## 체크리스트
- [ ] 대상 브랜치가 `dev`인지 확인
- [ ] `./gradlew test` 통과
- [ ] API 응답을 `ApiResponse<T>` 형식으로 통일
- [ ] 인증·인가 및 소유권 검사를 적용
- [ ] 토큰·비밀번호·AWS 키 등 민감정보를 커밋하지 않음
- [ ] DB 변경 시 새 Flyway migration을 추가하고 기존 migration을 수정하지 않음
- [ ] API·enum·오류 코드 변경 시 OpenAPI와 관련 v13 문서를 동기화
- [ ] 설정값 추가 시 `.env.example`과 README를 동기화

## ERD / API / 상태 전이 변경
- [ ] 변경 없음
- [ ] 있음 → 관련 OpenAPI, ERD, 정책·상태 전이 문서를 같은 PR에서 수정

## 기타 사항
> 리뷰어가 특별히 확인해야 할 내용, 후속 작업 또는 미해결 사항을 작성해주세요.
