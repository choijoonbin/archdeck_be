# ArchDeck 데이터 모델과 Docker 개발 환경

기준일은 2026년 11월 27일이며, 개인 PC를 호스트로 운영하고 사내 구성원이 로그인하여 사용하는 하나의 same-origin 웹·API 서비스를 대상으로 한다. 제품 계정은 아이디·비밀번호와 `ADMIN`·`READER` 두 역할로 제한한다. `ADMIN`은 콘텐츠·계정을 관리하고 `READER`는 게시 콘텐츠를 열람한다. 학습 기록과 외관 설정은 이번 MVP에서 서버에 동기화하지 않고 브라우저 안에 보관한다.

이 문서와 SQL은 새 PostgreSQL 모델의 설계·실행 기반이다. 기존 Spring Boot 애플리케이션은 아직 H2와 기존 API를 사용한다. Docker를 시작해도 애플리케이션의 DB·인증·권한·화면이 자동으로 전환되지는 않는다.

## 구성과 적용 상태

| 항목 | 결정 | 현재 산출물의 범위 |
|---|---|---|
| 프론트엔드 | React·TypeScript·Tailwind 유지 | 기존 소스 분석을 바탕으로 기획하며 이 작업에서 화면을 교체하지 않음 |
| 백엔드 | Spring Boot·JPA 기반을 유지하고 검증·보안·마이그레이션을 추가 | 기존 H2 프로필을 건드리지 않음 |
| 새 DB | PostgreSQL 17.12 Bookworm | 공식 이미지의 지원 태그를 확인한 선택이며 최신 메이저라는 의미는 아님 |
| Docker 서비스 | `db` 한 개 | 기존 프로젝트와 다른 Compose 프로젝트·볼륨·포트 사용 |
| 연결 | `127.0.0.1:55433` | 다른 프로젝트가 사용하는 `55432`에 접근하지 않음 |
| 계정·세션 | 앱 계정과 Spring Session JDBC | 테이블만 준비하며 로그인·보안 필터는 후속 구현 |
| 개인 학습·설정 | 브라우저별·계정별 저장 | 서버 `default_user` 공유 저장은 이관하지 않음 |
| 사내 공유 | 단일 same-origin 서비스의 ADMIN 관리·READER 열람 | 앱·게이트웨이·TLS는 현재 Compose에 포함되지 않음 |

PostgreSQL 이미지, 명명 볼륨과 초기화 SQL의 동작은 [공식 이미지 문서](https://hub.docker.com/_/postgres)를 기준으로 한다. 초기화 SQL은 빈 데이터 디렉터리에서만 실행되므로 기존 볼륨의 변경에는 별도의 버전 마이그레이션이 필요하다.

## 테이블 설계

DB 스키마 이름은 `archdeck`이며 업무 테이블은 총 18개다. `content_search_documents`는 검색용 뷰로 테이블 수에 포함하지 않는다. 애플리케이션 로그인 계정과 PostgreSQL 접속 계정은 서로 다른 대상이다.

| 테이블 | 주요 필드와 키 | 책임 |
|---|---|---|
| `app_users` | UUID PK, 유일한 `username`, `password_hash`, `display_name`, `role`, `enabled`, `must_change_password`, `auth_version`, `lock_version` | 최소 앱 계정과 강제 비밀번호 변경·세션 취소 판단 |
| `spring_session` | 표준 `primary_id`, 유일한 `session_id`, 생성·접근·만료 시각, `principal_name` | Spring Session JDBC 서버 세션 |
| `spring_session_attributes` | 세션·속성명 복합 PK, `attribute_bytes` | 표준 JDBC 세션 속성 |
| `categories` | UUID PK, 유일한 `slug`, 표시명·설명·순서 | 메뉴와 별도로 확장 가능한 공통 콘텐츠 분류 |
| `tags` | UUID PK, 유일한 `slug`, 표시명 | 다중 필터·연관 탐색용 태그 |
| `content_items` | UUID PK, 유일한 `slug`, `kind`, 제목·요약·본문, 분류 FK, 상태, `schema_version`, `payload`, 검토일, `lock_version` | 모든 콘텐츠의 공통 메타데이터와 종류별 본문 |
| `content_tags` | 콘텐츠·태그 복합 PK와 FK | 콘텐츠의 다중 태그 |
| `content_relations` | 출발·대상·관계 종류 복합 PK, 순서 | 관련 주제·선행 지식·용어 설명·검증 항목 연결 |
| `sources` | UUID PK, 유일한 정규 URL, 제목·발행처·종류·확인일 | 공식 문서와 표준 등 근거 목록 |
| `content_sources` | 콘텐츠·출처 복합 PK, 구체 위치·설명·순서 | 콘텐츠별 인용 근거와 연결 이유 |
| `content_revisions` | 콘텐츠·리비전 복합 PK, 전체 스냅샷, 변경 사유, 작성 계정 FK | 저장 시점의 전체 콘텐츠 이력 |
| `flow_nodes` | 콘텐츠·노드 키 복합 PK, 종류·이름·아이콘·선택 좌표 | 토픽의 다이어그램 노드 |
| `flow_edges` | 콘텐츠·연결 키 복합 PK, 출발·도착 노드 복합 FK, 프로토콜 | 방향과 통신 종류를 가진 연결선 |
| `flow_steps` | 콘텐츠·단계 키 복합 PK, 콘텐츠별 유일한 순서, 설명·페이로드·주의사항 | 순서대로 재생·선택할 학습 단계 |
| `flow_step_edges` | 콘텐츠·단계·연결 복합 PK, 단계·연결 복합 FK | 단계에서 강조할 연결선과 그 순서 |
| `flow_step_nodes` | 콘텐츠·단계·노드 복합 PK와 FK | 단계에서 별도로 강조할 노드 |
| `learning_paths` | 공통 콘텐츠 FK/PK, 난이도, 완료 기준 | 학습 경로의 종류별 설정 |
| `learning_path_items` | 경로·토픽 복합 PK, 경로별 유일한 순서, 필수 여부·학습 목표 | 안내 학습의 구성과 순서 |

로그인 계정에는 서버 북마크·메모·진도 테이블을 연결하지 않는다. 피드백·토론·웹훅·멀티테넌트·별도 알림 테이블도 이번 모델에 포함하지 않는다.

## 공통 콘텐츠와 JSONB의 경계

`content_items.kind`는 `topic`, `comparison`, `glossary`, `checklist`, `learning_path` 중 하나다. 같은 콘텐츠를 종류별 메타데이터 테이블과 전체 JSON에 반복 저장하지 않는다. ID·slug·제목·요약·본문·상태·검토일은 공통 컬럼에만 존재하며, `payload`에는 이 값을 다시 넣을 수 없도록 CHECK 제약을 둔다.

| 종류 | 공통 필드에 저장 | 종류별 `payload` 예시 |
|---|---|---|
| 토픽 | 제목·한글 부제·요약·상세 개요·예상 학습 시간 | `sections`, `keyTakeaways`, `tradeOffs`, `codeSnippets` |
| 비교 | 제목·부제·요약·설명 | `subjects`, `dimensions`, `decisionGuide` |
| 용어 | `title`에 용어, `subtitle`에 한글명, `summary`에 짧은 정의, 본문에 상세 정의 | `aliases`, `keyPoints`, `specRef`, `securityRelevance` |
| 체크 항목 | 제목·요약·상세 설명 | `priority`, `verificationMethod`, `codeExample` |
| 학습 경로 | 제목·요약·목적·검토일 | `outcomes`, `audience`; 난이도는 경로 테이블, 순서는 경로 항목 테이블 |

비교의 행·열·셀, 코드 스니펫, 설명 섹션은 대개 한 콘텐츠 단위로 함께 편집·조회하므로 JSONB에 둔다. 흐름의 노드·연결선·단계는 잘못된 참조가 곧 화면 오류로 이어지므로 관계형 테이블과 복합 FK로 보장한다. JSONB는 JSON 문법과 객체 여부를 보장하지만 종류별 JSON Schema 전체를 검사하는 기능은 이 DDL에 포함하지 않는다. API와 가져오기 검증기가 `schema_version`에 맞는 깊은 구조를 검증해야 한다. [PostgreSQL JSON 타입](https://www.postgresql.org/docs/17/datatype-json.html)

토픽이 설명형 문서일 때 흐름 단계가 0개인 것은 유효하다. 화면은 이 경우 흐름도 영역을 생략하거나 설명형 콘텐츠 상태를 보여야 하며 `flowSteps[0]`의 존재를 가정하지 않는다.

## 데이터 무결성과 저장 규칙

- 콘텐츠 slug는 소문자 영문·숫자·하이픈으로 전역 유일하게 한다. 한글 제목에서 자동으로 빈 slug를 만들지 않으며 직접 입력 또는 안전한 대체 생성 후 중복을 검사한다.
- UUID는 내부의 안정적인 식별자, slug는 URL의 읽기 쉬운 식별자로 사용한다. 기존 프론트의 `kind + 문자열 ID`에서 UUID로 가는 매핑은 버전 산출물로 보존하고 재이관에도 동일한 UUID를 사용한다. slug 변경이나 재실행으로 개인 메모의 대상 ID를 다시 생성하지 않는다.
- 흐름의 노드·연결·단계 FK에는 `content_id`가 함께 들어간다. 다른 토픽에 속한 노드를 참조하는 연결이나 강조 단계를 저장할 수 없다.
- 학습 경로 항목은 실제 `topic` 종류만 참조한다. 순서는 경로 안에서 유일하며 완료율은 각 브라우저의 필수 토픽 완료 상태로 계산한다.
- 일반 삭제 동작은 `archived`로 변경한다. 대상 콘텐츠를 가리키는 관계·학습 경로·이력이 있으면 하드 삭제를 막는다. 콘텐츠의 소유 태그·출처 연결·흐름 자식은 소유 콘텐츠를 삭제할 때 함께 삭제한다.
- 노드 연결 FK는 지연 검사하여 하나의 트랜잭션에서 전체 흐름을 교체할 수 있게 한다. 최종 상태는 커밋 또는 `SET CONSTRAINTS ALL IMMEDIATE`에서 반드시 유효해야 한다.
- `updated_at`은 DB 트리거로 갱신한다. `lock_version`은 애플리케이션의 낙관 잠금 토큰이며 DB가 자동 증가시키지 않는다.
- API는 전달받은 버전과 현재 버전이 같은 콘텐츠만 갱신하고, 자식·관계·출처 변경을 포함한 한 번의 저장 트랜잭션에서 버전을 한 번 증가시킨다. 충돌 시 자동 덮어쓰기하지 않고 사용자에게 비교·재적용을 제공한다.
- 최초 저장의 `lock_version=0`과 이력 `revision_no=1`을 대응시킨다. 다음 저장은 각각 1과 2다. 복원도 이전 행을 덮어쓰지 않고 새 리비전을 만든다.
- `content_revisions.snapshot`에는 공통 필드뿐 아니라 태그·관계·흐름·학습 경로 항목·출처 정보까지 포함한 전체 문서를 담는다. 단순히 ID 목록만 담아 이후 외부 행 수정으로 과거 내용이 바뀌지 않게 한다.
- 이력의 불변성은 서비스 계층과 DB 역할 권한으로 구현한다. 현재 스캐폴드의 DB 소유자는 이력을 수정할 수 있으므로 감사 변조 방지까지 구현되었다고 간주하지 않는다.

FK·유일성·CHECK는 DB가 지키며, 관계 종류별 대상 타입·선행 경로 순환·비교 셀의 완전성·출처 존재·게시 요건은 API의 집합 검증으로 다룬다. 다른 행을 읽는 규칙을 일반 CHECK로 흉내 내지 않는다. [PostgreSQL 제약 조건](https://www.postgresql.org/docs/17/ddl-constraints.html)

## 검색과 한글 처리

검색 입력과 문서는 NFC 정규화, 소문자화, 공백 제거를 동일하게 적용한다. `무상태 검증`과 `무상태검증`, 합성형과 분해형 한글을 같은 검색 키로 다룬다. 공백 제거가 의미를 넓힐 수 있으므로 제목 일치·접두어·본문 부분 일치 순으로 표시 점수를 분리한다. [문자열 정규화 함수](https://www.postgresql.org/docs/17/functions-string.html)

`content_search_documents` 뷰는 공통 본문·payload, 노드명, 연결 프로토콜, 단계 설명, 태그를 동일한 `content_id`로 검색할 수 있게 합친다. 결과에서는 콘텐츠 ID를 중복 제거하고 종류·분류·다중 태그 필터를 적용한다. 일반 열람 API에는 로그인·계정 활성·역할 검사 후 `published` 상태 필터도 적용한다. 초안은 작성 API의 권한으로만 조회한다.

`pg_trgm` GIN 인덱스를 두어 부분 문자열 검색을 지원한다. 짧은 1~2글자나 데이터량이 적은 초기 상태에서 항상 인덱스를 쓰거나 빠르다고 보장하지 않는다. 입력 길이·결과 수·쿼리 시간 제한을 두고 실제 한글 데이터로 실행 계획과 응답 시간을 확인한다. 형태소 검색과 오타 교정, 한글 초성 검색은 MVP 범위에 포함하지 않는다. [pg_trgm 인덱스 지원](https://www.postgresql.org/docs/17/pgtrgm.html)

API 쿼리는 바인딩 파라미터를 사용하고 `%`, `_`, 역슬래시를 LIKE 와일드카드로 해석하지 않도록 이스케이프한다. 생성된 `search_text`는 검색용 파생 값이며 원본 콘텐츠를 편집하는 저장소로 쓰지 않는다.

## 계정과 세션 설계

계정 식별자는 변경되지 않는 UUID다. `username`은 3~32자의 소문자 ASCII 영문·숫자·점·밑줄·하이픈을 허용하고 영문·숫자로 시작한다. 입력은 trim·소문자 변환 후 유일성을 검사한다. 표시 이름은 한글을 허용한다. 공개 회원가입·메일 인증·이메일 비밀번호 복구·SSO·Keycloak 도입은 하지 않는다.

신규 비밀번호는 Argon2id와 Spring Security의 인코더 접두사 형식으로 저장한다. 초기 기준은 최소 19MiB 메모리·반복 2·병렬도 1이며 대상 PC에서 성능을 측정해 확정한다. `password_hash`의 DB CHECK는 인코더 접두사 유무만 검사하므로 실제 알고리즘·강도 검증은 비밀번호 인코더가 담당한다. 비밀번호 평문·초기 관리자 자격증명을 SQL seed나 `.env.example`에 넣지 않는다.

관리자는 별도 로컬 초기화 절차로 최초 한 번 생성한다. 관리자 수동 계정 생성·활성 해제·임시 비밀번호 발급을 지원하며 첫 로그인에는 비밀번호 변경을 요구한다. 마지막 활성 관리자를 제거하는 작업은 서비스 트랜잭션에서 막는다. 비밀번호 변경·권한 변경·계정 비활성화 시 `auth_version`을 증가시키고 해당 계정의 세션을 파기한다. 매 요청에서 활성 상태와 세션의 `auth_version`을 확인하여 이미 진행 중인 요청의 세션 재저장으로 취소가 무효화되지 않게 한다.

Spring Session JDBC는 공식 PostgreSQL 컬럼·바이너리 타입·인덱스 구조를 사용한다. `principal_name`에는 username이나 표시 이름 대신 사용자 UUID 문자열을 넣는다. 세션 속성에는 해시·비밀번호를 포함한 전체 계정 엔티티를 직렬화하지 않고 필요한 ID·인증 버전 등 최소 정보만 넣는다. DB의 표준 `session_id` 컬럼을 임의 해시화하면 라이브러리 조회 계약이 달라지므로, 세션 쿠키·DB 접근·로그·만료·폐기 정책으로 보호한다. [Spring Session 3.4.1 PostgreSQL DDL](https://raw.githubusercontent.com/spring-projects/spring-session/3.4.1/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql), [JDBC 세션 구성](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)

세션 만료·쿠키·CSRF·로그인 시도 제한·TLS·관리자 화면 정책은 보안 계획과 함께 후속 구현에서 검증한다. 특히 기존 프론트의 내장 초기 데이터는 JS 번들만 내려받아도 열람할 수 있다. 사내 로그인 배포에서는 비공개 본문·초안·검색 메타데이터를 번들에서 제거하고 인증 API에서만 제공해야 한다. API에 보안 필터만 추가하는 것으로 콘텐츠 보호가 완성되지 않는다.

## 개인 학습과 외관 설정

제품 계정이 생겨도 이번 MVP는 서버 개인 기록을 만들지 않는다. 동기화가 필요해지면 별도 후속 스키마와 충돌 정책을 설계한다. 익명 UUID나 임의 브라우저 키는 인증 수단으로 쓰지 않는다.

| 계약 | 키 예시 | 보관 내용 |
|---|---|---|
| 개인 학습 v2 | `archdeck:learning:v2:<serviceId>:<userId>` | 북마크, 완료 콘텐츠, 메모, 체크 항목 실행 상태, 최근 본 토픽·최근 학습 경로, 저장 버전·갱신 시각 |
| 외관 설정 v1 | `archdeck:appearance:v1:<serviceId>:<userId>` | 색상 프리셋, 메뉴 left/right/top, expanded/compact, light/dark/system, 밀도, 고대비, 모션 축소 |
| 로그인 전 외관 | `archdeck:appearance:v1:<serviceId>:guest` | 로그인 화면을 표시하기 위한 개인 정보 없는 외관 값 |
| 인증 상태 | 서버 세션과 HttpOnly 쿠키 | localStorage에 토큰·비밀번호·세션 ID를 저장하지 않음 |

`serviceId`는 서비스 인스턴스의 안정적인 식별자이며 `userId`는 변경되지 않는 `app_users.id` UUID다. 표시 이름·username·포트 번호를 사용자 UUID의 대체 값으로 쓰지 않는다. 서비스 이름 공간은 복원·이전 때 명시적으로 연결하며 서로 다른 사내 서비스의 기록을 자동 병합하지 않는다.

브라우저 초기 로딩이 끝나기 전 빈 학습 상태를 저장하지 않는다. 계정 전환 시 React 상태·검색 결과·작성 초안·콘텐츠 캐시를 비우고 같은 서비스의 새 사용자 UUID 저장 영역을 다시 읽는다. localStorage 영역 분리는 같은 브라우저에서의 혼동 방지이며, 공용 PC에서 사용자가 브라우저 개발 도구로 다른 저장 값을 읽는 것까지 막는 보안 경계는 아니다. 공용 PC 운영에는 로그아웃 시 로컬 기록 제거 옵션을 제공한다.

학습 체크는 실제 시스템의 보안 준수를 인증하는 결과가 아니라 이 브라우저에서 기록한 학습·검토 상태로 표시한다. JSON 백업은 계정 자격증명·세션·다른 사용자의 기록을 포함하지 않는다.

## 가져오기와 기존 데이터 이관

1. 프론트 내장 데이터의 기존 5개 토픽·3개 비교·10개 용어·9개 체크 항목을 공통 콘텐츠 패키지 한 곳으로 추출한다. DB용 콘텐츠를 손으로 또 작성하지 않는다.
2. 누락된 `chk-iam-2`, `chk-iam-3` 등의 참조를 실제 항목 추가 또는 연결 수정으로 해결한 뒤 이관한다. 기존 H2의 축약된 데이터로 더 풍부한 원본을 덮어쓰지 않는다.
3. 원본 패키지의 schemaVersion·고정 식별자 매핑·slug를 검증한다. 최초 이관 때 `kind + legacyId → UUID`를 `content/migrations/legacy-id-map.v1.json` 같은 버전 산출물로 작성·보존하며, 재이관 때 이 매핑을 재사용하고 새 UUID를 다시 생성하지 않는다. 매핑 충돌·누락은 저장 전에 검출한다. 이 파일은 후속 이관 구현의 산출물이며 현재 생성되어 있지는 않다. 공백·유니코드·카테고리·관계·코드·비교 셀·흐름 참조도 정규화한다.
4. 미리보기에서 신규·변경·충돌·연결 불가 항목을 보여준다. 알 수 없는 종류·지원하지 않는 버전·중복 키·임의 HTML·너무 큰 코드/본문·원격 실행 지시는 거부한다.
5. 명시적인 승인 대상 항목만 하나의 트랜잭션으로 반영하고 이력을 남긴다. 검증 실패 시 부분 저장하지 않는다. URL은 데이터로만 받아들이며 가져오기 중 원격 URL을 자동 요청하거나 코드를 실행하지 않는다.
6. 같은 패키지 재실행은 중복 생성하지 않도록 패키지 식별자와 보존된 legacy ID·UUID 매핑으로 판단한다. 이미 편집된 항목은 버전 비교 없이 시드로 덮어쓰지 않는다. slug는 탐색용 보조 정보이며 UUID 매핑을 재할당하는 근거로 쓰지 않는다.
7. 브라우저 백업의 북마크·완료 목록·메모의 key·체크 항목 실행 상태·최근 열람 대상에도 같은 매핑을 적용해 새 UUID 기준 문서로 변환한다. 변환된 기록은 확인된 `serviceId + userId` 영역에 저장하고 원본 백업을 보존한다. 알 수 없는 ID는 조용히 버리지 않고 복원 보고서에 남긴다.
8. H2의 공용 `default_user` 기록은 특정 계정 소유로 자동 배정하지 않는다. 필요한 사용자가 확인한 JSON을 자신의 브라우저 영역에 가져오게 한다.

현재 `010-taxonomy.sql`에는 분류 4개와 태그 11개만 들어 있다. 계정·비밀번호·실제 학습 콘텐츠 seed는 0개이며, 완전한 콘텐츠 이관 기능은 후속 작업이다.

## Docker 실행과 관리

관련 파일은 백엔드 저장소 루트의 `compose.yaml`, `.env.example`, `db/schema/001-initial.sql`, `db/seed/010-taxonomy.sql`이다. `.env`와 DB 백업은 Git 추적에서 제외한다.

```bash
cd /Users/a10697/Work/ArchDeck/ArchDeck_BE
cp -n .env.example .env
# .env의 ARCHDECK_DB_PASSWORD를 로컬 임의 비밀번호로 교체한다.
docker compose config --quiet
docker compose up -d db
docker compose ps
```

DB 소유자 기본 이름을 바꾸지 않았다면 다음과 같이 스키마를 확인한다. 비밀번호를 명령행에 쓰지 않는다.

```bash
docker compose exec -T db psql -U archdeck_owner -d archdeck -v ON_ERROR_STOP=1 \
  -c "SELECT table_name FROM information_schema.tables WHERE table_schema='archdeck' AND table_type='BASE TABLE' ORDER BY table_name;"
```

서버를 멈출 때는 볼륨을 보존한다.

```bash
docker compose stop db
```

최초 실행 뒤 SQL 파일을 편집해도 기존 볼륨에 자동 반영되지 않는다. 통합 단계에서 이 초기 스키마를 Flyway 첫 마이그레이션으로 편입하고 이후 변경을 새 마이그레이션으로 추가한다. 기존 H2에 `ddl-auto=update`를 실행해 PostgreSQL 모델을 흉내 내거나 DB 초기화를 통해 변경을 반영하지 않는다.

JPA의 기본 schema는 `archdeck`, JDBC 세션 테이블은 `archdeck.SPRING_SESSION`, DB 초기화는 마이그레이션 전담으로 맞춘다. PostgreSQL 드라이버·Flyway·Spring Security·Spring Session JDBC·Argon2 의존성 및 새 프로필은 애플리케이션 통합 단계에서 추가한다. 이 문서는 설정값을 실제 소스에 적용했다는 뜻이 아니다.

이 Compose의 `ARCHDECK_DB_USER`는 로컬 초기화용 소유자다. 통합 단계에서는 마이그레이션 소유자와 단일 앱 서비스의 런타임 DB 역할을 분리한다. 런타임 역할은 콘텐츠 CRUD, 본인 비밀번호 변경과 계정 관리에 필요한 DML, JDBC 세션 DML을 수행한다. API 서버가 각 요청의 `ADMIN`·`READER` 역할, 계정 활성, 본인성을 검사하여 허용된 동작만 실행한다. 이는 프론트 사용자에게 PostgreSQL 권한이나 접속 자격증명을 직접 부여하는 구성이 아니다. 이력의 UPDATE·DELETE와 DDL 등 앱이 필요로 하지 않는 권한은 런타임 역할에서 제외한다.

사내 공유는 하나의 same-origin 웹·API 서비스로 제공한다. 호스트에서 주로 관리하되 인증된 `ADMIN`의 LAN 접속과 관리 작업도 허용하며, 별도 reader 서비스나 localhost 전용 작성 배포를 MVP의 필수 구성으로 두지 않는다. `READER`는 게시 콘텐츠를 읽고 자기 비밀번호를 변경할 수 있으며 콘텐츠·다른 계정을 변경할 수 없다. 백엔드 자체 포트와 DB 포트는 loopback에 유지하고 HTTPS 웹 진입점만 LAN에 연다. UI에서 편집 버튼을 숨기거나 CORS·Origin 검사만 하는 것을 서버 권한 통제로 취급하지 않는다.

백업은 접근을 제한한 `db/backups/`에 보관하고 세션 데이터는 제외한다. 마이그레이션 전 백업과 별도 임시 DB 복원 검증을 수용 조건으로 둔다. 이미지 digest는 실제 pull·실행 검증 시 기록하여 다시 실행할 대상을 특정한다.

## 검증과 후속 통합 수용 조건

2026년 11월 27일 별도 Docker 검증 환경에서 PostgreSQL 17.12가 healthy 상태로 실행되었고, 기본 연결은 `127.0.0.1:55433`으로 구성되었다. 업무 테이블 18개와 분류 4개·태그 11개를 확인했으며, 계정·콘텐츠 seed는 0개다. `db/verify.sql`의 9개 스모크 검증 묶음이 통과했고 검증용 레코드는 모두 롤백했다. 이 결과는 스키마·제약·검색 SQL의 검증이며 실제 앱 인증·권한·화면을 검증한 결과는 아니다.

- 깨끗한 별도 Compose 프로젝트의 임시 볼륨에서 초기 스키마·taxonomy가 적용되어야 한다.
- 업무 테이블 18개, 분류 4개, 태그 11개이며 계정과 콘텐츠는 임의로 생성되어 있지 않아야 한다.
- 중복 slug·잘못된 계정명·인코더 접두사 없는 비밀번호·잘못된 역할·잘못된 종류·다른 토픽의 노드 참조를 DB가 거부해야 한다.
- 단계 없는 토픽, 2열·4열 비교, 용어 추가, 경로 순서, 한글 공백·NFC/NFD 검색을 실제 화면과 API에서 확인해야 한다.
- 동시 편집의 버전 충돌, 전체 스냅샷 복원, 가져오기 실패의 원자성, archive된 콘텐츠의 공개 검색 제외를 확인해야 한다.
- `ADMIN`·`READER`·비로그인·강제 비밀번호 변경·비활성 계정·권한 변경 후 기존 세션을 API 수준에서 검증해야 한다.
- 브라우저 계정 전환과 로그아웃에서 개인 학습·메모·콘텐츠 캐시가 다른 사용자에게 이어지지 않아야 한다.
- 테스트용 내용은 트랜잭션 롤백으로 제거하고 기존 H2·다른 프로젝트 컨테이너·볼륨을 변경하지 않아야 한다.

실제 실행 결과와 임시 프로젝트·이미지 digest는 저장소의 검증 기록에 남긴다. 현재 SQL만으로 API JSON Schema 검증, 낙관 잠금의 서비스 구현, 세션 보안, 권한 분리, 이관·백업 복원까지 완료되었다고 간주하지 않는다.
