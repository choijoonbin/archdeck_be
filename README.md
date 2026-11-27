# ArchDeck Backend

Spring Boot API와 공통 기획·DB·Docker 기반을 관리한다. 개발 브랜치는 dev이며 프론트는 [archdeck_fe](https://github.com/choijoonbin/archdeck_fe/tree/dev)에서 관리한다.

현재 앱은 Java 21 대상의 Spring Boot 3.4.2·JDBC·PostgreSQL이다. 아이디·비밀번호, Argon2id, 서버 JDBC 세션과 CSRF, ADMIN·READER 인가를 구현했다. 프론트와 Agent 통합 검증의 최신 결과는 docs/13 문서를 따른다.

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

기본 포트는 4000, 상태 확인은 /api/health다. 기존 H2 파일은 data 아래에 보존하고 접근하지 않는다. 현재 기본 연결은 Docker PostgreSQL이며 .env의 DB 자격증명을 프로세스에 주입한다. scripts/run-local.py를 사용하면 로컬 .env를 주입할 수 있다.

```sh
./mvnw -o test
```

오프라인 검사는 의존성이 로컬 캐시에 있을 때 실행한다. 4개 계약·암호·직렬화·검증 회귀 테스트를 포함한다. 실제 PostgreSQL과 HTTP 통합 검증은 별도로 수행한다.

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

## 최초 관리자와 현재 데이터

ARCHDECK_BOOTSTRAP_USERNAME과 ARCHDECK_BOOTSTRAP_PASSWORD를 프로세스 환경에 명시하면 ADMIN이 없는 DB에만 최초 관리자를 만든다. 비밀번호는 15~128 Unicode code point이며 최초 로그인에서 변경해야 콘텐츠에 접근할 수 있다. 기존 ADMIN이 있으면 환경변수로 덮어쓰지 않는다. 평문을 명령행 인수·Git·로그에 남기지 않는다.

content-seed.json은 FE에 있던 5개 토픽·3개 비교·10개 용어·9개 체크항목의 서버 원본이다. 콘텐츠가 없는 DB에서만 적재하며 안정적인 legacy ID→UUID를 적용한다. 이전 H2의 개인 기록이나 비계정 브라우저 기록은 자동으로 계정에 배정하지 않는다.

Flyway V1은 신규 DB를 초기화하고 기존 설계 DB는 baseline 1 후 V2~5를 적용한다. 기존 init SQL을 수정해 다시 실행하지 않는다. 공개 공유는 HTTPS 진입점과 ARCHDECK_SECURE_COOKIE=true, ARCHDECK_SESSION_COOKIE_NAME=__Host-ArchDeckSession을 사용한다. 현재 로컬은 loopback HTTP에만 바인딩한다.

## 리서치 실행

ARCHDECK_AGENT_SERVICE_TOKEN과 ARCHDECK_AGENT_URL이 설정된 내부 Agent를 연결한다. 정책은 관리자만 생성하며 scheduleEnabled의 기본값은 false다. true이면 intervalMinutes 15~10080 범위에서 분 단위 tick으로 예약하고 PostgreSQL의 정책·시간슬롯 유일 키로 중복을 방지한다. 수동 실행의 대기·실행은 최대 20건으로 제한한다.

실행 정책 snapshot과 결과·후보를 DB에 보존한다. 재시작 시 queued는 재개하고 실행 중이던 작업은 interrupted_restart로 실패 처리해 명시적으로 재실행한다. 이 단계는 단일 인스턴스용 compact 구현이며 설계 문서의 12개 정규 research 테이블·분산 lease/outbox 전달 전체를 구현한 것은 아니다. 현재 연구 업무 테이블은 3개다. 자동 게시 권한은 없으며 관리자 검토와 승인하고 게시만 콘텐츠를 만든다. 실제 Azure 검증은 회사망 접근이 가능한 환경에서 별도로 확인한다.

최신 구현·통합 검증·회사망 확인 절차는 [구현 결과](docs/13-implementation-and-verification.md)를 따른다.
