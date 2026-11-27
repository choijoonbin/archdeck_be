# 흐름도·토픽 개정·분산 실행

2026-11-27 추가 구현 기록이다. Azure 실연결은 사용자 요청에 따라 회사망에서 확인한다. 이 문서는 이번 범위의 최신 계약과 검증 근거를 기록하며, 이전 전체 구현은 [13번 보고서](13-implementation-and-verification.md)를 따른다.

## 범위

- 단계별 노드·방향 연결선과 신뢰 경계를 읽을 수 있는 SVG 흐름도, 확대·축소·이동·화면 맞춤, 키보드·좁은 화면 대응.
- 신규 인사이트와 기존 토픽 개정 제안을 구분하고, 기존 내용·제안 내용·근거·고정한 원본 버전을 비교한 뒤 관리자 게시.
- PostgreSQL을 공유하는 복수 BE와 복수 Agent 프로세스의 선점·lease 갱신·만료 회수·fencing·중복 결과 방지·취소·모델 시도 예산.

## 토픽 개정 경계

정책에서 대상 토픽을 선택하면 실행 시 토픽과 `baseLockVersion`을 고정한다. Agent는 근거가 연결된 초안과 `revisionPatch`를 제안한다. 수정 허용 필드는 `title`, `summary`, `overview`, `keyTakeaways`다. 기존 노드·흐름·코드·연관 항목·ID를 모델 출력으로 통째로 덮어쓰지 않는다. 신규 인사이트 후보도 관리자가 대상 토픽을 선택해 개정 제안으로 전환할 수 있다.

관리자 검토 화면은 필드별 기존 값과 제안 값, 수집 근거, 기준·현재 버전을 보여준다. 게시에는 후보 버전, 원본 토픽 버전, 검토 사유가 필요하다. 다른 관리자가 후보나 원본을 변경한 경우 409로 중단하며, 최신 토픽을 기준으로 제안을 다시 작성하고 확인한다. 콘텐츠 변경·revision·출처 연결·후보 게시 상태는 하나의 트랜잭션이다. 열람자에게 검토·게시 API 권한을 부여하지 않는다.

## 운영 구분

BE의 연구 업무 원장은 `archdeck` 스키마, Agent 실행 원장과 LangGraph checkpoint는 별도 `agent_runtime` 스키마에 둔다. Agent 실행 계정은 사용자·세션·콘텐츠를 읽거나 게시할 권한을 받지 않는다. 설치 시 테이블을 준비하는 계정과 실행 시 DML만 수행하는 계정을 구분한다.

Agent 다중 프로세스에는 `ARCHDECK_AGENT_DISTRIBUTED=true`와 `ARCHDECK_AGENT_CHECKPOINT_DSN`이 필요하다. PostgreSQL 연결이나 준비된 테이블이 없으면 readiness가 실패해야 하며 SQLite로 우회하지 않는다. 개발용 단일 프로세스 SQLite 실행은 별도로 유지한다. 자세한 설치·최소 권한 절차는 [Agent README](https://github.com/choijoonbin/archdeck_agent/blob/dev/README.md)를 따른다.

로컬 실행 도구는 포트를 지정할 수 있다. 예를 들어 두 BE 프로세스는 각각 `python3 scripts/run-local.py backend --port 4500`, `--port 4501`로 실행한다. 동일 DB와 서비스 토큰, 동일 Agent URL을 비밀 환경변수로 전달한다. Agent는 위 분산 환경을 준비한 뒤 `python3 scripts/run-local.py agent --port 4010 --workers 2`로 시작한다. 실제 사내 공유는 기존 HTTPS 프록시·Secure 세션 설정을 유지한다.

작업 전달은 재전송될 수 있다. DB 반영 멱등성과 fence로 오래된 작업의 확정을 차단하지만, 외부 모델 호출 직후 장애가 발생한 경우 호출 비용까지 정확히 한 번을 보장하지 않는다. 모델 시도 예약은 실행 전체에서 제한하며 fence 교체로 예산을 초기화하지 않는다. 원장·checkpoint는 보존 정책과 정기 백업 대상이다.

이 구조는 PostgreSQL의 [행 잠금·SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html)와 LangGraph의 [checkpoint 지속성](https://docs.langchain.com/oss/python/langgraph/persistence)을 사용한다. checkpoint 저장만으로 분산 작업 소유권이나 외부 부작용의 멱등성이 해결되는 것은 아니므로 별도의 원장·lease·fence가 필요하다.

## 검증 기록

합성 RSS·모델 응답 테스트와 Azure 실연결을 구분한다. 이번 검사에서 유료 모델 호출은 수행하지 않았다.

- 변경 전 운영 DB를 비공개 `db/backups/pre-distributed-*.dump`로 백업했다. 별도 `archdeck_pre_dist_restore` DB에 단일 트랜잭션 복원을 수행해 콘텐츠 27개와 Flyway V5를 확인했다. 백업·복원 검사는 운영 원본을 변경하지 않았다.

| 검사 | 결과 |
|---|---|
| BE `./mvnw package` | 단위 검사 6개, 패키징 통과 |
| `db/verify.sql` + `db/verify-research.sql` | 기본 무결성 9그룹과 연구 명령·receipt·후보·fence·취소 3그룹 통과, 모두 rollback |
| `scripts/check-distributed.py` | 실제 두 JVM + 별도 PostgreSQL에서 아래 4개 시나리오 통과. 최종 V8 jar로도 재검증 |
| `scripts/check-revisions.py` | 실제 BE→합성 Agent→필드 diff→409→재작성→게시·멱등 재전송 통과. 그래프 정규 저장·비대상 필드 보존 확인 |
| Agent `pytest` | 76개 통과, 실제 PostgreSQL 프로세스/권한 검사 9개 포함 |
| Agent 정적·컨테이너 검사 | Ruff 검사·format·lock 및 Docker build 통과. UID10001, health200, 미설정 readiness503, Docker 네트워크에서 최소 역할 PG 저장 확인 |
| FE build·format·Playwright | 타입·번들·format 통과. 기존 5개와 신규 6개 모의 시나리오, 실제 PostgreSQL BE 연동 3개 시나리오 통과 |

분산 HTTP 검사는 실제 BE 프로세스를 소유하고 종료·재기동하며 다음을 확인한다. BE4500의 토픽 검증 DB와 다른 `archdeck_distributed_http_verify` DB를 사용한다.

1. 두 JVM이 서로 다른 작업을 선점한다. 6초 lease를 넘겨 8초 기다려도 heartbeat가 갱신되고 후보는 실행당 1개다.
2. 첫 HTTP 연결이 결과 없이 끊긴 경우 같은 `asOf`·정책·토픽 명령을 더 높은 fence로 재전달하며 후보는 1개다.
3. 다른 JVM에서 취소한 뒤 이전 실행의 성공 응답이 도착해도 상태는 cancelled이고 후보는 0개다.
4. 실행 중 두 JVM을 강제 종료하고 다시 시작하면 만료된 작업을 더 높은 fence로 회수하고 후보는 1개다.

재현 시 `ARCHDECK_JDBC_URL`은 별도 `*_verify` DB여야 한다. 빈 DB는 임의 초기 계정을 만들며, 같은 DB에 재실행하려면 비밀 환경변수 `ARCHDECK_TEST_ADMIN_USERNAME`, `ARCHDECK_TEST_INITIAL_PASSWORD`, `ARCHDECK_TEST_ADMIN_PASSWORD`로 해당 검증 계정을 지정한다. 비밀번호·원본 credential·실제 endpoint는 로그나 저장소에 기록하지 않는다. 검증 도구가 생성한 합성 정책·후보는 검증 DB에만 남긴다.

그래프 시각 검사는 1440·1280·390·320px, 200% 확대, 주야간·키보드·축소 모션을 포함한다. 긴 연결 설명은 번호 배지와 텍스트 목록으로 분리하고, 신뢰 영역이 겹치지 않도록 배치했다. 모바일에서는 최대 8배 확대·이동·맞춤과 텍스트 대체를 제공한다. 잘못된 edge 참조는 스튜디오와 서버 모두 거부한다.

실제 브라우저 검사 3개는 세션 로그인·개인 메모·로그아웃, 서버 graph metadata·PKCE 거부·4개 화면폭·200% 및 800% 확대/이동, 미게시 개정 후보의 기존/제안 필드 비교·승인 초기 비활성을 확인했다. 합성 게시·개정 검사는 별도 검증 DB에서만 수행했다. 실제 Agent 2worker의 동일 PostgreSQL registry와 BE 원장에서도 같은 runId/fence 및 receipt를 대조했다.

## 기존 로컬 데이터와 적용

기존 V1~V5는 수정하지 않았다. V6는 분산 연구 원장과 graph 신뢰 영역, V7/V8은 편집하지 않은 기본 토픽의 명시적 graph 및 경계·좌표 보강이다. 본문과 기존 사용자 편집을 자동으로 덮어쓰지 않는다. 기존 로컬 메인 DB도 V8로 올리고 로그인·API를 확인했다. 과거 검증 저장으로 버전만 증가한 SPA 토픽은 변경 전 seed와 본문·기존 graph가 동일함을 대조한 뒤, 관리자 API의 새 revision으로 graph metadata만 추가했다. H2와 다른 프로젝트의 컨테이너·볼륨은 변경하지 않았다.

## 남은 확인과 범위

Azure 리소스의 실제 네트워크·배포명·Responses 지원·권한·quota·usage 응답은 회사망에서 확인한다. 이번 합성 검증을 실제 Azure 성공으로 표현하지 않는다. 독립 스케줄러, 정책 전체 편집 이력, 협업·문서 웹훅, 학습 경로 전용 화면과 본문 전체 자동 용어 연결은 이번 세 기능의 범위에 포함하지 않는다.
