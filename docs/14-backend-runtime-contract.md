# PostgreSQL 및 인증 백엔드 실행 계약

2026-11-27 실제 구현 기준이다. 계획 전체의 구현 완료를 뜻하지 않으며 통합 검증 결과는 13번 보고서를 따른다.

## 실행과 데이터

기본 DB는 Docker PostgreSQL의 archdeck 스키마다. Spring JDBC로 공통 content_items와 graph tables를 조회·저장한다. 기존 H2 파일은 변경하지 않는다. 원본 FE 데이터를 서버의 content-seed.json으로 이동해 빈 DB에 5개 토픽·3개 비교·10개 용어·9개 체크항목을 적재한다. 기존 문자열 ID는 응답 호환용 legacyId로 유지하고 UUID는 이름 기반의 결정적 변환을 사용한다.

Flyway V1은 빈 DB의 기반 테이블, V2는 taxonomy, V3는 insight와 compact research 테이블·흐름 actor/target/protocol, V4는 정책 snapshot·회수·게시 키, V5는 스케줄 슬롯, V6는 분산 lease·명령 outbox/inbox 및 그래프 신뢰 경계, V7은 미편집 기본 토픽의 명시적 edge·예외 경로, V8은 신뢰 영역 배치·응답 의미를 담당한다. 기존 18테이블 DB는 baseline 1 이후 마이그레이션을 수행한다. 운영 credential과 기존 기록을 SQL seed에 넣지 않는다.

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
| POST /api/research/runs | policyId를 받아 정책·대상 토픽 snapshot과 queued 실행·outbox 명령을 같은 트랜잭션으로 저장 |
| GET /api/research/runs | 최근 100개 실행과 결과·일반화된 실패 사유 |
| POST /api/research/runs/{id}/cancel | queued/running을 취소하고 Agent 취소 요청. 후속 결과 게시 후보 반영을 차단 |
| GET /api/research/candidates | 관리자 검토 후보·문서 근거·candidateVersion·proposalKind·targetContentId·baseLockVersion·proposedTopic |
| POST /api/research/candidates/{id}/review | reject/request_changes/comment와 reason, candidateVersion 필수. stale 409 |
| POST /api/research/candidates/{id}/publish | candidateVersion·reason, 토픽 개정은 baseLockVersion도 필수, 선택 Idempotency-Key. 후보당 한 게시를 기본 멱등 키로 처리 |
| GET /api/insights | 인증된 사용자가 승인·게시된 인사이트만 조회 |

게시 시 row lock으로 후보 버전과 상태를 검사하고 주장 인용이 문서에 실제 존재하며 재사용 허용된 자료인지 다시 검증한다. 후보·콘텐츠·출처·revision 기록을 한 트랜잭션으로 저장한다. 이는 인용 연결 검증이며 의미적 사실성이나 저작권 허용의 자동 판정을 보장하지 않는다. Agent에 게시 API 자격증명과 계정 DB 권한을 주지 않는다.

scheduleEnabled는 기본 false다. 활성 정책만 60초 tick에서 intervalMinutes 15~10080 간격으로 생성하며 정책+시간 슬롯 unique key로 중복을 막는다. 수동 대기·실행 최대 20건 검사는 enqueue 트랜잭션의 advisory lock 아래 수행한다. asOf와 policy/targetTopic 전체 요청은 outbox에 고정하며 재시도에도 동일하다. 소유권을 나타내는 leaseFence만 증가한다.

## 기존 토픽 개정

정책의 targetContentId를 지정하면 insight 대신 topic_revision 후보를 생성한다. 선택 targetBaseLockVersion이 현재 버전과 다르면 실행 생성부터 409다. Agent는 title·summary·overview·keyTakeaways만 포함하는 revisionPatch를 제안한다. BE는 원본 전체 토픽에 해당 patch를 적용하며 그래프·코드·연관 용어 등 다른 필드를 보존한다. 임의 full proposedTopic 입력을 게시 데이터로 신뢰하지 않는다.

| 요청 | 계약 |
|---|---|
| GET /api/research/candidates/{id}/diff | candidateVersion, proposalKind, targetContentId, baseLockVersion, currentLockVersion, conflict, before, after, changes:[{path,before,after}] |
| POST /api/research/candidates/{id}/revision | candidateVersion,targetContentId,baseLockVersion,revisionPatch. 기준 재설정·후보 version 증가·needs_review 전환·기존 reviewer/reason 초기화 |
| POST /api/research/candidates/{id}/publish | candidateVersion,baseLockVersion,reason을 함께 검증. source 인용 검증 후 canonical topic+revision+후보 상태를 같은 트랜잭션으로 저장 |

fingerprint는 재귀 키 정렬 JSON을 사용해 JSONB 키 순서 변환과 무관하다. 후보를 잠근 뒤 대상 콘텐츠도 잠그고 현재 버전·fingerprint를 다시 검증한다. 후보 개정 또는 원문 변경 뒤 stale 검토·게시 요청은 409다. FE는 승인을 해제하고 diff와 최신 원문을 다시 조회한다. 이미 게시된 동일 후보의 재요청은 기존 게시 결과를 반환한다.

## 명시적 흐름도

Topic.diagramEdges는 id,source,target,label,protocol,type(request/response/event/internal/error),trustBoundary를 사용한다. DiagramNode는 선택 x/y와 trustZone을 지원한다. FlowStep.activeEdgeIds는 기존 activeNodes와 병행한다. 노드·edge 중복 ID, 알 수 없는 source/target/highlight, 유한하지 않은 좌표는 400이며 저장 전 검증한다. 정규 graph 테이블에 보관해 저장·재조회 후 metadata를 보존한다.

V7/V8 기본 graph 보강은 lockVersion=0인 초기 토픽만 적용하며 편집 자료·본문·본문 revision 번호를 덮어쓰지 않는다. 콘텐츠 없는 새 DB는 최신 content-seed.json으로 시작한다. 좌표는 신뢰 영역별로 분리하고 경계 통신과 내부 처리를 구분한다. PKCE invalid_grant 예외는 명시적인 error edge다.

## 분산 실행과 운영 범위

research_commands(outbox), research_inbox와 기존 연구 3개 테이블을 사용한다. 여러 BE가 SKIP LOCKED로 queued/만료 running을 선점하고 lease_owner·lease_until·lease_fence를 기록한다. 전용 heartbeat가 실행 중 lease를 갱신하며 만료된 lease는 되살리지 않는다. 실행 결과의 runId/fence와 현재 DB 소유자·만료·상태를 확인하고 행 잠금 뒤 시계를 다시 확인한다. 취소는 DB 상태와 cancel outbox를 같은 트랜잭션으로 저장한다. 취소 이후 늦은 성공 응답은 후보를 생성하지 않는다.

HTTP 전달은 at-least-once다. 전송 오류는 고정 명령을 새 fence로 최대 5번 전달하고 최종 실패 시 outbox를 종료한다. Agent도 공유 PostgreSQL registry에서 중복 실행·stale fence·취소 tombstone을 처리한다. 외부 모델 호출 자체의 exactly-once 보장은 아니다. 프로세스 재시작은 다른 인스턴스의 running을 실패시키지 않으며 만료 lease만 회수한다. 기본 lease 45초는 ARCHDECK_RUN_LEASE_SECONDS로 조절한다(최소6초).

정책 전체 편집 이력과 문서별 변경 원장, 웹훅·독립 스케줄러·자동 품질 점수는 추가 확장 범위다. 로그인 시도 제한은 여전히 프로세스 메모리이므로 여러 BE를 사내 공유할 때 프록시의 공유 rate limit이 필요하다. 실제 Azure 검증은 회사망에서 별도 수행한다. 실제 결과는 docs/15-flow-revision-distributed-verification.md에 기록한다.
