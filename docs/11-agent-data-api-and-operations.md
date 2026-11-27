# ArchDeck 리서치 에이전트 데이터와 API 운영 설계

검토일: 2026-11-27 · 상태: 추가 기능의 구현 전 설계 제안 · 기존 DB와 앱에는 미적용

정한 분야의 공개 정보를 수집하고 근거를 확인해 지식 초안을 만드는 기능을 독립 ArchDeck_Agent에 둔다. 정책·스케줄·실행 원장·검토·정규 지식 게시는 Spring Boot 백엔드가 책임지고, Python 에이전트는 제한된 수집·추출·검증·합성을 수행한다. 기존 인증·권한·DWP형 화면 요구를 유지한다.

현재 PostgreSQL은 기존 설계의 18개 테이블이며 앱은 아직 H2 기반이다. 아래 추가 테이블·API·worker·정책은 모두 설계안이다. 기존 init SQL 편집, 실제 볼륨 변경, 외부 모델 호출, 운영 스케줄 생성은 이번 문서 작업에 포함하지 않는다.

## 확정된 방향과 제안값

| 구분 | 내용 |
|---|---|
| 사용자 확인 | 인증·보안과 웹 아키텍처에 AI 에이전트 분야를 더한다. 분야는 앞으로 확장할 수 있어야 한다. 기존 DWP_agent의 Azure OpenAI 키와 연결 방식을 재사용한다. |
| 제품 합의 | 기존 학습 6개 메뉴에 트렌드·인사이트를 추가하고, ADMIN에게 리서치 에이전트 관리 도구를 제공한다. 게시 초안은 기존 콘텐츠 스튜디오와 연결한다. |
| 경계 | Agent가 앱 계정·세션·정규 콘텐츠 DB를 직접 읽거나 게시하지 않는다. FE는 BE만 호출한다. |
| 제안 | 최초 worker 동시 실행 1개, 제한된 공식·허용 출처, 관리자 게시 승인, provider-free fixture 검증부터 시작한다. |
| 후속 확정 | 수집 출처와 이용 조건, 갱신 주기·보존 기간·검색 기간, 모델 ID·가격표·예산·근거 기준과 평가 통과값 |

분야는 코드 enum으로 고정하지 않고 안정적인 domain ID와 버전 있는 정책으로 표현한다. 모델명도 코드 기본값으로 최신이라고 단정하지 않는다. 기본 provider는 azure_openai이며 기존 DWP_agent와 같은 Azure OpenAI v1 Responses 연결 방식을 우선한다. 정책의 공통 부분은 특정 모델 API 필드에 묶지 않는다.

## 서비스 책임과 권한

| 경계 | 소유하는 것 | 하지 않는 것 |
|---|---|---|
| ArchDeck_FE | 분야·기간·게시 인사이트 탐색, ADMIN 규칙·실행·검토·diff 화면 | Agent 직접 호출, 모델 API 키 보유, 브라우저 타이머로 수집 스케줄 실행 |
| ArchDeck_BE | 사용자 인증·권한, 정책 버전, 스케줄, 실행·예산 원장, 문서·후보 검증, 승인·반려·게시, 검색 | 모델에게 게시 결정 위임, 미검증 초안을 READER에게 반환 |
| ArchDeck_Agent | 허용 도구로 수집·추출·근거 확인·합성, 그래프 checkpoint, 실행 결과·usage 보고 | 계정·세션 조회, 임의 SQL·shell, 무제한 탐색, 외부 본문 지시에 따른 도구·정책 변경 |
| 외부 모델·정보 제공자 | 허용된 공개 정보의 추론·검색·API 응답 | 사내 개인 메모·비밀번호·세션·DB 비밀·관리자 초안을 기본 입력으로 받음 |

READER는 게시된 인사이트만 읽는다. 정책·실행·문서 원본·후보·평가 결과는 ADMIN만 조회한다. Agent의 기계 인증은 앱 ADMIN 계정과 분리한다. Agent가 ADMIN 로그인 쿠키를 받아 사용하는 방식을 쓰지 않는다.

## 최소 운영 구성

목표는 웹·API의 같은 출처 진입점 → BE → PostgreSQL이며 Agent worker가 내부 BE API에서 일을 가져간다. 기존 PostgreSQL 한 인스턴스에 스키마와 역할을 분리한다.

- archdeck: 기존 앱 계정·정규 콘텐츠, BE가 소유한다.
- research: 아래 리서치 업무 테이블, BE가 소유한다. 필요한 app_users·content_items FK는 스키마를 명시한다.
- agent_runtime: LangGraph checkpoint 등 라이브러리 실행 상태만 저장한다. 별도 Agent DB 역할을 사용하며 archdeck·research에는 권한이 없다.
- 마이그레이션 소유자와 각 런타임 역할을 분리한다. Agent 런타임에 CREATE/ALTER를 허용하지 않고 checkpoint 초기화·업그레이드는 전담 배포 절차로 실행한다.

업무 DB가 사용자에게 보일 실행 상태의 원본이다. checkpoint는 모델 실행을 이어가는 내부 상태이며 장기 지식 저장소나 승인 기록이 아니다. LangGraph는 checkpoint와 애플리케이션 장기 저장을 별개로 설명한다. [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)

MVP에는 Kafka·Redis·Celery·Kubernetes·별도 벡터 DB를 필수로 넣지 않는다. PostgreSQL 작업 선점과 outbox/inbox, 제한된 worker, 영속 checkpoint로 시작한다. 다중 서비스의 긴 대기·보상·대규모 스케줄 운영이 생기면 Temporal을 대안으로 평가하며 LangGraph와 중복된 전체 오케스트레이터를 함께 운영하지 않는다. Temporal은 장애 뒤 실행 상태를 이어가는 workflow 실행 모델을 제공한다. 이는 제품 규모에 따른 대안이지 현재 구조보다 시간상 더 최신이라는 의미가 아니다. [Temporal Workflow Execution](https://docs.temporal.io/workflow-execution)

## 추가 업무 테이블 제안

추가 업무 테이블은 12개다. 기존 18개와 합하면 업무 테이블 30개가 되지만 이는 미래 마이그레이션 적용 후의 수다. 라이브러리가 관리하는 agent_runtime checkpoint 테이블은 별도이며 버전에 따라 수가 달라질 수 있다.

| 테이블 | 최소 주요 필드와 키 | 책임·검증 |
|---|---|---|
| research_policies | id UUID PK, domain_key unique, title, enabled, active_version_id, next_due_at, lock_version | 분야와 운영 상태. 활성 버전 FK는 같은 정책 안의 버전만 허용한다. |
| research_policy_versions | id UUID PK, policy_id FK, version_no, schema_version, rules JSONB, rules_sha256, created_by FK, created_at; unique(policy_id,version_no) | 출처·필터·기간·도구·스케줄·예산·검증 기준의 불변 버전. rules 안에 계정·키를 넣지 않는다. |
| research_runs | id UUID PK, policy_version_id FK, trigger_kind, schedule_slot, idempotency_key, state, cutoff_at, window_start/end, lease_owner/until, lease_fence, cancel_requested_at, budget/usage JSONB, last_accepted_cursor, timestamps | 실행 권위와 선점·중지·예산 상태. 수동 실행 키와 예약 slot에 유일 제약을 둔다. |
| research_run_steps | run_id FK, step_key, attempt_no, stage, state, request_id, reservation/usage, input/output SHA, artifact/cursor, error_code; unique(run_id,step_key,attempt_no) | 단계·도구·모델 호출의 예약·결과·비용·재시도 기록. 원문·비밀을 일반 로그에 반복 저장하지 않는다. |
| collected_documents | id UUID PK, normalized_url unique, canonical_hash unique, original/final/declared_canonical_url, publisher_key, first_seen_at | 수집 문서의 정체성과 출처. 모델이 만든 URL을 검증 없이 canonical로 승격하지 않는다. |
| document_versions | id UUID PK, document_id FK, content_sha256, extractor_version, title, published/updated/event_at, fetched_at, date_confidence, permitted_excerpt/artifact_ref, retention_until, fetch metadata; unique(document_id,content_sha256,extractor_version) | 같은 기사의 변경과 수집 시점을 보존. 본문 영구 저장 권한과 보존 정책을 별도로 확인한다. |
| run_documents | run_id FK, document_version_id FK, source_config_key, decision, reason_codes, relevance, cluster_key; PK(run_id,document_version_id) | 이번 실행에 어떤 버전이 포함·제외됐는지와 중복 묶음 근거. |
| knowledge_candidates | id UUID PK, run_id FK, candidate_key, proposal_kind, proposed_kind, target_content_id/base_lock_version, payload JSONB, validation_result, status, candidate_version, created_at; unique(run_id,candidate_key) | 인사이트 신규·토픽 신규·기존 지식 개정 제안. 외부 본문과 승인 전 초안은 정규 게시 콘텐츠와 분리한다. |
| candidate_evidence | candidate_id FK, claim_key, document_version_id FK, locator, permitted_quote, quote_sha256, verdict, verifier_version; composite unique | 핵심 주장과 실제 확인한 문서 버전·위치·판정 연결. JSON claims와의 완전성은 집합 검증기로 확인한다. |
| candidate_reviews | id UUID PK, candidate_id FK, candidate_version, reviewer_id FK, action, reason, decision_at, publication_key unique | 불변 승인·반려·수정 요청 기록과 게시 멱등성. 수정된 후보는 이전 승인을 재사용하지 않는다. |
| agent_outbox | event_id UUID PK, aggregate_id, event_type, schema_version, payload, available_at, delivery_state, attempt_count | 실행·취소 같은 명령을 run 변경과 같은 트랜잭션으로 기록한다. worker pull의 대기 원장으로 사용한다. |
| agent_inbox | event_id UUID PK, run_id FK, lease_fence, payload_sha256, event_type, accepted_at, result_ref | worker 재전송을 중복 처리하지 않는다. 같은 event_id에 다른 payload면 충돌로 거절한다. |

고정 메타데이터는 컬럼, 버전별 도구 규칙·검증 상세·후보의 종류별 구조는 JSONB로 둔다. JSONB가 스키마·참조 전체 검증을 대신하지 않는다. 런타임 계정은 검토 이력과 수신 이벤트를 수정·삭제할 권한을 가지지 않으며 삭제·보존 정리는 전용 정책 작업으로 수행한다.

수집 문서를 기존 sources에 바로 넣지 않는다. 승인된 근거만 BE가 기존 sources·content_sources에 매핑한다. 이렇게 수집한 임의 URL과 검토된 지식 출처의 의미를 분리한다.

## 기존 콘텐츠 모델의 변경

트렌드·인사이트를 게시하려면 content_items.kind에 insight를 추가하는 미래 마이그레이션이 필요하다. 기존 001-initial.sql을 편집하거나 현재 볼륨을 재생성하지 않는다. insight payload는 변화 요약, 영향·적용 조건, 관찰 기간, 사건 시각, 핵심 주장, 한계와 후속 지식 연결을 담고, 승인된 sources와 content_revisions를 함께 기록한다.

기사는 사건의 근거이며 인사이트는 그 변화가 설계에 주는 의미다. 영구 지식 토픽과 인사이트를 구분하고 근거가 같은 기사를 여러 번 가져왔다고 독립 출처 수를 늘리지 않는다.

기존 지식 수정 후보는 target_content_id와 base_lock_version을 고정한다. ADMIN의 명시적인 “승인하고 게시”는 POST /api/research/candidates/{id}/publish 한 번의 동작으로 처리한다. candidateVersion, baseLockVersion, Idempotency-Key, 검토 사유와 결정을 입력에 고정하고 서버가 권한·근거·현재 버전을 다시 검증한다. approval record, 정규 content, sources·연결, revision과 publication key를 같은 DB 트랜잭션에서 원자적으로 커밋한다. 현재 revision이 다르면 409로 반환하고 diff와 재검토를 요구한다. 별도 승인 기록 저장 뒤 다시 게시 버튼을 누르는 두 단계 절차를 필수로 만들지 않는다. /review는 반려·수정 요청·의견만 처리하며 단순 approve 요청을 몰래 게시로 해석하지 않는다. Agent에 자동 게시 도구나 DB 쓰기 권한을 주지 않는다.

## 실행 상태와 내구성

업무 상태와 그래프 단계를 구분한다. run은 queued → running → awaiting_review 또는 succeeded로 이동하며, rejected/accepted는 후보의 검토 상태다. 실패·취소는 failed 또는 cancelled로 종료하고 대기 재시도에는 retry_wait를 쓴다. 한 run에 후보가 여러 개면 run 하나의 accepted로 후보별 결정을 덮지 않는다.

1. ADMIN 요청 또는 BE 스케줄 tick이 run과 run_requested outbox를 한 트랜잭션으로 만든다. 수집 기준 cutoff와 기간은 이때 고정한다.
2. worker가 내부 claim API로 일을 요청한다. BE가 대기 행을 짧은 트랜잭션의 FOR UPDATE SKIP LOCKED로 선점하고 lease_fence를 증가시켜 실행 envelope를 반환한다. 네트워크·모델 호출 중 DB row lock을 잡고 있지 않는다.
3. worker는 heartbeat와 남은 예산을 확인하며 단계 결과를 checkpoint와 불변 결과물에 보존한다. 모델·도구 호출 전 action별 예산 예약을 BE에 요청한다.
4. 단계·문서·후보 이벤트를 run_id, lease_fence, event_id, payload SHA와 함께 보고한다. BE는 현재 lease·상태·중지 요청·스키마·수신 중복을 확인하고 수신 기록과 반영을 함께 커밋한다.
5. worker가 죽거나 연결이 끊겨 lease가 끝나면 BE reconciler가 마지막으로 승인한 cursor부터 재개할 작업을 만든다. 재시도 횟수·backoff·최대 기간을 넘으면 실패로 종료한다.

SKIP LOCKED는 잠긴 행을 건너뛰는 작업 대기열에 쓸 수 있지만 일반 조회의 일관성을 대신하지 않는다. 선점·기한·fencing·재시도는 ArchDeck이 별도로 구현해야 한다. [PostgreSQL SELECT locking clause](https://www.postgresql.org/docs/17/sql-select.html)

checkpoint namespace는 run_id와 lease_fence로 분리한다. 만료된 worker가 늦게 checkpoint를 써도 새 소유자의 namespace를 덮을 수 없게 한다. 재개는 BE가 마지막으로 수락한 단계·artifact hash·cursor를 기준으로 새 fence에 복원하며, 과거 namespace의 최신 상태를 무조건 신뢰하지 않는다. LangGraph checkpoint 자체가 BE의 lease fencing을 제공한다고 가정하지 않는다.

분산 전달은 최소 한 번 일어날 수 있다. 같은 문서·후보·게시 결과의 중복 저장은 유일 키와 inbox로 막지만 외부 모델 호출의 정확히 한 번 실행을 보장하지 않는다. 모델 응답 직후 checkpoint나 보고가 저장되기 전에 죽으면 재호출과 추가 비용이 발생할 수 있다. 애매한 호출은 미청구로 간주하지 않고 예산 예약을 보수적으로 유지해 UI에 불확실 비용을 표시한다.

취소는 요청 기록 후 다음 외부 동작 전에 적용한다. 진행 중 네트워크 호출은 가능한 범위에서 취소·timeout 처리하지만 이미 사용한 토큰·과금을 되돌린다고 표시하지 않는다. 취소 뒤 도착한 후보는 게시 대기로 자동 전환하지 않는다. 정책 중지는 새 run 생성을 막는 동작이고 진행 중 run 취소와는 별개다.

## 최소 API 계약

외부 FE는 세션·CSRF로 BE API를 사용한다. 내부 worker API는 별도 서비스 자격증명·scope로 인증하고 사용자 세션을 허용하지 않는다. 경로 이름은 구현 계약 초안이며 OpenAPI 확정 때 버전을 고정한다.

| 사용자 API | 권한·행동 |
|---|---|
| GET /api/insights 및 /api/insights/{slug} | 인증된 READER·ADMIN, 게시 상태만, 날짜·분야·태그 필터와 상세 |
| GET/POST /api/admin/research/policies | ADMIN, 분야·출처·규칙·주기·예산 관리. 변경은 새 policy_version |
| POST /api/admin/research/policies/{id}/validate | ADMIN, 규칙 스키마·도메인·예산·모델 capability 검사. 수집을 시작하지 않음 |
| POST /api/admin/research/runs | ADMIN, idempotency key와 정책 버전으로 실행 요청, 202와 run ID |
| GET /api/admin/research/runs/{id} | ADMIN, 단계·수집/제외 수·근거·usage·보수적 비용·실패·재시도 표시 |
| POST /api/admin/research/runs/{id}/cancel | ADMIN·CSRF, 취소 요청을 멱등하게 기록 |
| GET /api/research/candidates/{id} | ADMIN, 후보·claim-evidence·기존 지식 diff·검증 결과 |
| POST /api/research/candidates/{id}/review | ADMIN·CSRF, candidateVersion과 반려·수정 요청·의견만. 게시나 단순 approve는 처리하지 않음 |
| POST /api/research/candidates/{id}/publish | ADMIN·CSRF, “승인하고 게시”. candidateVersion·baseLockVersion·Idempotency-Key·검토 사유/결정 고정, 검증·승인 기록·정규 콘텐츠·출처·revision·게시 키를 한 트랜잭션으로 반영 |
| GET /api/admin/research/runs/{id}/events | ADMIN, 원장 기반 진행 스트림 또는 polling; 연결이 끊겨도 작업은 지속 |

| 내부 API | 검증·행동 |
|---|---|
| POST /internal/agent/claims | 제한된 worker만, 원자 선점. run ID·fence·정책 snapshot·cutoff·budget 반환 |
| POST /internal/agent/runs/{id}/heartbeat | 현재 owner/fence만, 짧은 lease 연장, 중지·취소 상태 반환 |
| POST /internal/agent/runs/{id}/reservations | 현재 owner/fence, action ID별 호출·토큰·비용 상한 예약 |
| POST /internal/agent/runs/{id}/events | event ID·payload hash·fence·허용 event type·스키마·문서 참조 검증 후 원자 반영 |
| GET /internal/agent/runs/{id}/resume-manifest | 현재 owner/fence, 마지막으로 수락한 결과물·cursor만 제공 |

publish는 같은 Idempotency-Key와 같은 입력이면 기존 게시 결과를 반환하고, 같은 키에 다른 입력·변경된 후보 버전·base revision이면 409로 거절한다. /review의 의견 저장을 게시 요청으로 재해석하지 않는다.

동일 event ID의 동일 본문은 기존 결과를 반환한다. 같은 ID에 다른 hash는 409, 오래된 fence는 409, schema/크기/참조 오류는 422로 거부한다. error code와 재시도 가능 여부를 구분하고 입력 원문·키를 오류 응답에 넣지 않는다.

provider 설정은 ARCHDECK_MODEL_PROVIDER·ARCHDECK_OPENAI_MODEL을 우선하고 모델 이름에는 DWP_OPENAI_MODEL 호환 alias를 제공한다. ARCHDECK_OPENAI_MODEL 값은 Azure의 실제 배포 이름이다. Azure 연결은 AZURE_OPENAI_API_KEY, AZURE_OPENAI_ENDPOINT와 배포 이름을 서버 설정으로 읽고 endpoint의 /openai/v1 Responses 방식으로 맞춘다. DWP의 v1 경로에는 api-version 쿼리를 덧붙이지 않는다. 키와 api-key 헤더는 원장·정책 snapshot·trace·FE로 전달하지 않는다. 원장에는 provider·배포 이름·검증한 모델/프롬프트 버전과 필요한 리전만 남긴다. [Azure OpenAI API lifecycle](https://learn.microsoft.com/en-us/azure/ai-foundry/openai/api-version-lifecycle), [Azure Responses](https://learn.microsoft.com/en-us/azure/ai-foundry/openai/how-to/responses)

Azure 배포·리전별 Responses와 도구 지원은 후속 capability probe에서 확인한다. 공개 OpenAI에서 제공하는 web search가 같은 Azure 배포에서도 동작한다고 가정하지 않고 외부 수집 client와 모델 합성을 분리한다. 이번 단계는 provider-free fixture이므로 실제 키를 읽거나 유료 요청을 보내지 않는다.

worker envelope의 공통 필드는 schemaVersion, runId, leaseFence, policyVersionId, policyHash, windowStart/End, cutoffAt, domainId, allowedTools/Sources, budgets, modelProfileRef, promptBundleVersion이다. modelProfileRef는 비밀값이 아닌 구성 참조다. 결과는 candidateKey, proposalKind, targetContentId/baseLockVersion, claims, evidence, source dates, provider/model/prompt/extractor/verifier version과 usage를 포함한다. 서버가 알지 못하는 URL·claim·artifact를 모델이 참조하면 저장 전에 거절한다.

## 최신성·출처·중복 기준

- published_at, 사건·발표 시각 event_at, 수집 fetched_at을 분리하고 원문 갱신 시각 updated_at도 보존한다. UTC로 저장하고 화면의 사용자 시간대에 맞춰 표시한다.
- 사건 시각을 알 수 없으면 unknown으로 표시하고 기사 발행일을 사건 날짜로 단정하지 않는다. 미래·모순 날짜는 검토 대상으로 보낸다. 발행일이 없는 원문도 document_versions에 보존할 수 있으나 날짜를 생성하지 않고 needs_review로 표시하며 최신 피드·시간창 판단과 게시는 보류한다. fixture 계약이 published_at을 필수로 요구하는 것은 초기 prototype의 제한이며 운영 문서 모델의 날짜 없음 처리 정책을 대체하지 않는다.
- 원래 URL, 요청 뒤 최종 URL, 문서가 주장한 canonical, 검증된 normalized URL을 구분한다. 알려진 추적 파라미터만 제거하고 의미 있는 쿼리는 유지한다.
- SHA-256은 정확한 문서 버전을 고정한다. 유사 제목·본문과 재전재 기사 묶음은 별도 cluster_key로 표시하되 출처를 삭제하거나 독립 근거로 과대 집계하지 않는다.
- 각 핵심 주장에는 확인 가능한 문서 버전·문단 또는 locator·허용 발췌와 판정이 필요하다. supported, partially_supported, unsupported, contradicted, needs_review를 구분하고 모델의 자신감 수치를 사실 검증으로 취급하지 않는다.
- 독립 근거 수, 공식 발표 예외, 최신 기간과 평가 통과 기준은 정책 버전에 둔다. 초기 제안은 공식 1차 출처의 단일 발표 사실과 복수 독립 근거가 필요한 종합 트렌드 판단을 구분하는 것이다.

## 수집·모델·저장 경계

전역 웹 수집을 임의 URL proxy로 제공하지 않는다. 초기에는 허용한 공식 RSS·릴리스·문서·공개 API를 우선하고 추가 출처는 ADMIN이 구성한다. 검색 제공자가 후보 URL을 주더라도 수집 정책을 다시 통과시킨다.

HTTPS와 허용 host·port·path를 확인하고 DNS 결과의 loopback·사설·링크로컬·멀티캐스트·메타데이터 주소를 차단한다. redirect 자동 추적은 기본 끄며 필요하면 매 hop에 같은 검사를 수행한다. DNS 검증과 실제 연결 대상의 불일치를 막고, 전용 fetch client의 egress 제한을 함께 둔다. HTTP 크기·압축 해제 크기·timeout·redirect 수·MIME·도메인별 요청 속도도 제한한다. 내부 BE 호출 client와 공개 문서 fetch client의 자격증명·허용 주소를 섞지 않는다. [OWASP SSRF Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)

외부 HTML·RSS·본문·PDF·검색 응답은 신뢰하지 않는 데이터다. MVP는 HTML을 정적으로 추출하고 JS·파일·shell·문서 안 지시를 실행하지 않는다. 모델 입력의 지시와 근거 본문을 분리하고, 본문이 도구 추가·키 출력·게시 승인을 요구해도 실행 권한으로 바뀌지 않는다. 검사 모델을 추가했다는 사실만으로 prompt injection이 해결됐다고 보지 않는다. [OWASP LLM Prompt Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)

robots.txt·제공 API·이용 조건을 확인하고 paywall·로그인·차단을 우회하지 않는다. robots 규칙은 접근 권한이나 재배포 허가가 아니다. [RFC 9309](https://www.rfc-editor.org/rfc/rfc9309)

원문 전체의 영구 저장·재배포는 기본으로 하지 않는다. 기본 보존은 URL·메타데이터·hash·허용된 짧은 발췌와 독자 작성 요약이며, 전체 본문 저장이 필요한 출처는 이용 조건과 보존 기간을 별도로 정한다. 실험용 fixture는 직접 작성하거나 재사용 권한이 분명한 자료로 만든다. checkpoint·trace에도 본문이 들어갈 수 있으므로 같은 보존·삭제 정책을 적용한다.

## 예산·스케줄·관측

분야마다 최대 문서·요청·토큰·모델 호출·재시도·동시성·소요 시간·추정 금액을 정한다. 외부 동작 전 BE가 원자적으로 예산을 예약하고 불명확한 응답은 보수적으로 사용량에 반영한다. 금액은 versioned 가격표와 usage에 따른 추정치이며 공급자의 실제 청구액을 보장하는 수치로 쓰지 않는다.

스케줄은 BE가 UTC 실행 slot과 정책 시간대를 저장해 계산한다. 동일 policy version과 slot은 한 번의 run만 생성한다. 호스트가 꺼져 놓친 실행은 전부 재생하지 않고 기본 coalesce 1회를 제안한다. 스케줄 UI 저장은 제품 서버 작업의 설정이며 Codex 자동화나 OS cron을 실제 생성하는 행동이 아니다.

UI 진행 정보는 BE 원장에서 복원한다. trace에는 run·step·model·prompt·policy version, latency·usage·제외 이유·검증 통과율을 남기되 비밀번호·모델 키·세션·사내 개인 메모는 제외한다. 외부 trace SaaS 전송은 기본 끄고 민감 정보 제거와 운영 선택 뒤에 켠다.

## 마이그레이션과 구현 수용 조건

1. 기존 18개 스키마의 기준선과 백업·복원 검사를 유지하고 별도 버전 마이그레이션으로 research 스키마와 insight kind를 추가한다.
2. 기존 content_items·sources·content_revisions와의 FK·게시 원자성·기존 검색 필터를 먼저 검증한다. 현재 SQL을 수정하거나 볼륨을 삭제해 적용하지 않는다.
3. 작업 선점·lease·fence·outbox/inbox를 fixture worker로 시험하고 재시작·중복 이벤트·지연 응답·취소·예산 소진·부분 실패를 재현한다.
4. source 변경·문서 중복·재전재·날짜 불명·서로 모순된 근거·prompt injection·SSRF redirect·부정 MIME을 평가 fixture에 포함한다.
5. 같은 publish 입력·Idempotency-Key 재전송에서 정규 콘텐츠가 한 번만 만들어지며 후보·base revision 충돌은 덮어쓰기 없이 409로 처리한다. /review만 호출해서 게시되지 않아야 하고 “승인하고 게시”는 검증·승인 기록·콘텐츠·출처·revision·게시 키를 한 트랜잭션으로 완결한다.
6. Agent DB 역할로 앱 계정·세션·정규 콘텐츠·research 원장에 접근하거나 게시할 수 없음을 실제 권한 검사로 확인한다.
7. READER가 draft·run·원문·검증 메타데이터 API에 접근하지 못하고 게시 인사이트만 검색·홈·상세에서 볼 수 있어야 한다.
8. 운영 collector와 유료 LLM 연결 전 provider-free harness fixture·스키마·평가 기준을 통과시킨다. 현재 단계는 실시간 수집·운영 스케줄·모델 검증 완료가 아니다.
