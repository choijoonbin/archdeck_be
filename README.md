# ArchDeck Backend

Spring Boot API와 공통 기획·DB·Docker 기반을 관리한다. 개발 브랜치는 dev이며 프론트는 [archdeck_fe](https://github.com/choijoonbin/archdeck_fe/tree/dev)에서 관리한다.

현재 앱은 Java 21 대상의 Spring Boot 3.4.2·JPA·H2다. 사내 아이디·비밀번호 로그인, PostgreSQL 전환과 DWP형 화면은 기획에 반영했으며 실제 앱 통합은 후속 구현이다.

## 기획과 작업 기준

| 문서 | 내용 |
|---|---|
| [제품 기획 기준](docs/00-project-baseline.md) | 목적·최신 결정·MVP |
| [글로벌 벤치마킹](docs/01-benchmark.md) | ByteByteGo·roadmap.sh·Refactoring.Guru |
| [메뉴와 화면](docs/02-product-and-navigation.md) | 상세 URL·DWP형 설정·수용 조건 |
| [테이블과 Docker](docs/03-data-and-docker.md) | PostgreSQL·실행·이관·복구 |
| [로그인과 공유](docs/04-auth-and-sharing.md) | 세션·관리자/열람자 |
| [누적 작업 계획](docs/05-implementation-plan.md) | 기존 문제·우선순위 |
| [검증 결과](docs/06-validation.md) | 실제 확인과 남은 작업 |
| [Agent 통합 계획](docs/12-agent-implementation-plan.md) | 최신 기술·Azure·수집·근거·UI·구현 경계 |
| [파일 구조와 Git](docs/08-repository-layout.md) | 두 저장소의 책임과 제외 기준 |

## 현재 백엔드 실행

이 저장소 루트에서 Java 21 이상과 Maven Wrapper로 실행한다.

```sh
./mvnw spring-boot:run
```

기본 포트는 4000, 상태 확인은 /api/health다. H2 파일은 data 아래에 생기며 Git에서 제외한다. 현재 앱은 새 PostgreSQL에 자동 연결되지 않는다.

```sh
./mvnw -o test
```

오프라인 검사는 의존성이 로컬 캐시에 있을 때 실행한다. 현재 자동 테스트는 없으며 빌드 성공은 기능 검증을 대신하지 않는다.

## PostgreSQL Docker

[db 폴더](db/README.md)는 테이블·분류 seed·검증 SQL이다. 실제 PostgreSQL 데이터는 archdeck_pgdata 명명 볼륨에 보관한다. 기본 연결은 127.0.0.1:55433, DB는 archdeck, 초기화 소유자는 archdeck_owner다. 이는 웹 로그인 계정이 아니다.

처음에만 .env.example을 .env로 복사하고 ARCHDECK_DB_PASSWORD를 임의 비밀번호로 설정한다. 기존 .env는 보존하며 Git에서 제외한다.

```sh
docker compose config --quiet
docker compose up -d --wait --wait-timeout 60 db
docker compose ps
docker compose exec -T db sh -c 'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < db/verify.sql
```

SQL은 빈 볼륨 최초 생성 때만 적용한다. 계정·학습 본문은 seed하지 않는다. 이후 스키마 변경은 버전 마이그레이션을 사용한다. 중지는 docker compose stop db로 수행하고 볼륨을 초기화 수단으로 삭제하지 않는다.

## 이전 Node와 Git 관리

[node_version_backup](node_version_backup/README.md)은 이전 Express 소스 보존용이다. 현재 기준은 Spring Boot다.

소스·잠금 파일·환경 예시·문서·Docker·SQL은 추적한다. 실제 .env·data·db/backups·target·node_modules·dist·로그는 로컬에 보존하고 제외한다.

## 리서치 Agent

[archdeck_agent](https://github.com/choijoonbin/archdeck_agent/tree/dev)는 별도 FastAPI·LangGraph 서버다. 지정 분야의 수집·지식화 후보를 만들고 BE가 검토·게시한다. 현재는 합성 fixture와 Azure DWP호환 어댑터 골격이며 실제 수집·모델·스케줄·게시 연동은 후속이다.
