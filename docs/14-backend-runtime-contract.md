# PostgreSQL 및 인증 백엔드 실행 계약

2026-11-27 실제 구현 기준이다. 계획 전체의 구현 완료를 뜻하지 않으며 통합 검증 결과는 13번 보고서를 따른다.

## 실행과 데이터

기본 DB는 Docker PostgreSQL의 archdeck 스키마다. Spring JDBC로 공통 content_items와 graph tables를 조회·저장한다. 기존 H2 파일은 변경하지 않는다. 원본 FE 데이터를 서버의 content-seed.json으로 이동해 빈 DB에 5개 토픽·3개 비교·10개 용어·9개 체크항목을 적재한다. 기존 문자열 ID는 응답 호환용 legacyId로 유지하고 UUID는 이름 기반의 결정적 변환을 사용한다.

Flyway V1은 빈 DB의 기반 테이블, V2는 taxonomy, V3는 insight와 compact research 테이블·흐름 actor/target/protocol, V4는 정책 snapshot·회수·게시 키, V5는 스케줄 슬롯을 담당한다. 기존 18테이블 DB는 baseline 1 이후 마이그레이션을 수행한다. 운영 credential과 기존 기록을 SQL seed에 넣지 않는다.

## 인증 및 사용자 API

| 요청 | 응답·행동 |
|---|---|
| GET /api/auth/csrf | token, headerName. 세션 저장 토큰을 읽으며 공개 콘텐츠를 반환하지 않음 |
| POST /api/auth/login | form username/password + X-CSRF-TOKEN. 성공 계정 view, 실패 일반 401 |
| GET /api/auth/me | id, username, displayName, role, mustChangePassword |
| POST /api/auth/password | currentPassword/newPassword. 정책 검사 뒤 모든 기존 세션을 회수하고 현재 컨텍스트 종료 |
| POST /api/auth/logout | CSRF 필수. 세션·쿠키 삭제, 204 |
| GET/POST /api/admin/users | ADMIN 목록/생성. 생성은 username, password, displayName, role |
| PUT /api/admin/users/{id} | ADMIN이 표시 이름·역할·활성 상태·비밀번호를 변경. 기존 세션 회수 |

신규 계정은 강제 비밀번호 변경 상태다. 변경 전에는 me/csrf/password/logout만 사용할 수 있다. Argon2id 19MiB·t2·p1, 15~128 code point·UTF-8 512바이트를 적용한다. 세션 principal은 불변 사용자 UUID이며 직렬화된 세션에 비밀번호 해시를 넣지 않는다. 로그인 시도는 사용자·IP 별도 버킷과 최대 키 수로 제한한다. 이 제한은 단일 프로세스 메모리이며 재시작 후 누적 시도는 초기화된다.

공유 환경은 HTTPS 프록시와 Secure 쿠키를 사용한다. 백엔드를 non-loopback에 직접 바인딩하면서 secure cookie=false로 시작하는 설정은 거부한다. 쿠키 이름은 ARCHDECK_SESSION_COOKIE_NAME으로 설정하고 공유 시 __Host-ArchDeckSession을 사용한다.

## 콘텐츠 API

GET /api/topics, /api/comparisons, /api/glossary, /api/checklists는 기존 DTO 배열 형태를 유지한다. 검색과 category 필터를 지원한다. 개별 콘텐츠는 /{id 또는 slug}로 조회한다. 목록과 상세는 게시 상태만 반환한다. 인증이 없으면 401이며, 변경 요청은 ADMIN·CSRF가 필요하다.

POST는 새 콘텐츠를 만들고 같은 ID가 있으면 409를 반환한다. PUT /{id}는 응답의 lockVersion을 제출해야 한다. 행 잠금·현재 버전·kind를 검사하고 stale update는 409다. DELETE는 실제 제거 대신 archived 상태와 revision을 기록하며 선택적으로 lockVersion을 검사한다. 잘못된 배열·흐름 노드·연관 콘텐츠 참조는 저장 전에 거부한다. 필드별 세부 스키마와 완전한 출처·관련 항목 정규화는 추가 개선 범위다.

공용 default_user API는 제거했다. 개인 학습 기록은 브라우저와 로그인 계정별로 관리하며 이전 데이터 소유자를 자동 추정하지 않는다.

## 리서치와 게시

| 요청 | 계약 |
|---|---|
| GET/POST /api/research/policies | 관리자 정책 조회·저장. name/domainId/sources/lookbackDays/maxDocuments/maxInputCharacters |
| POST /api/research/runs | policyId를 받아 정책 snapshot과 queued 원장을 저장하고 단일 worker에서 내부 Agent 요청 |
| GET /api/research/runs | 최근 100개 실행과 결과·일반화된 실패 사유 |
| POST /api/research/runs/{id}/cancel | queued/running을 취소하고 Agent 취소 요청. 후속 결과 게시 후보 반영을 차단 |
| GET /api/research/candidates | 관리자 검토 후보·문서 근거·현재 version |
| POST /api/research/candidates/{id}/review | reject/request_changes/comment와 reason |
| POST /api/research/candidates/{id}/publish | candidateVersion·reason, 선택 Idempotency-Key. 후보당 한 게시를 기본 멱등 키로 처리 |
| GET /api/insights | 인증된 사용자가 승인·게시된 인사이트만 조회 |

게시 시 row lock으로 후보 버전과 상태를 검사하고 주장 인용이 문서에 실제 존재하며 재사용 허용된 자료인지 다시 검증한다. 후보·콘텐츠·출처·revision 기록을 한 트랜잭션으로 저장한다. 이는 인용 연결 검증이며 의미적 사실성이나 저작권 허용의 자동 판정을 보장하지 않는다. Agent에 게시 API 자격증명과 계정 DB 권한을 주지 않는다.

scheduleEnabled는 기본 false다. 활성화된 정책만 60초 tick에서 intervalMinutes 15~10080 간격으로 처리하며 정책+시간 슬롯의 PostgreSQL unique key로 중복을 막는다. 실행 시작 기준 asOf는 DB created_at으로 고정해 같은 요청 재전송에서도 변하지 않는다. 수동 대기·실행은 최대 20건이며 프로세스 재시작 시 queued는 재개하고 running은 interrupted_restart 실패로 명시한다.

## 현재 구현의 경계

설계의 연구 업무 12개 테이블을 모두 분해하지 않고 research_policies, research_runs, knowledge_candidates의 3개 compact 테이블과 JSONB snapshot으로 시작했다. 분산 lease·fencing·outbox/inbox 전달, 정책 전체 버전 이력, 별도 문서 변경 원장, 기존 토픽 변경 제안·diff 승인은 아직 완전 구현하지 않았다. 현재는 단일 BE 인스턴스와 신규 insight 게시 흐름이다. 다중 인스턴스 운영 전에 해당 제약을 확장해야 한다.

실제 Azure 회사 배포 연결은 회사망 환경에서 수행해야 한다. 현재 서비스 간 계약은 모의 Azure 응답을 통한 통합 검증과 구분해서 기록한다.
