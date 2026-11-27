# ArchDeck 구현과 통합 검증

기준일: 2026-11-27. 기존 P0, PostgreSQL·로그인, DWP형 화면, Azure 호환 리서치 실행·검토·게시를 세 저장소에 구현했다. Azure 실제 호출은 회사망 전용이라는 사용자 확인에 따라 회사에서 수행할 검증으로 남긴다.

이 문서는 최초 통합 시점의 기록이다. 이후 흐름도·기존 토픽 개정·분산 실행의 구현과 해당 잔여 항목의 해소 근거는 [15번 보고서](15-flow-revision-distributed-verification.md)가 우선한다.

## 실제 구현

| 영역 | 구현 내용 |
|---|---|
| P0 | 빈 flowSteps 정상 표시, 콘텐츠 API 전용 로딩, 초기 학습 저장 경합 제거, default_user 동기화 폐기 |
| PostgreSQL | Spring JDBC·Flyway V1~V5, 기존 18개 기반과 연구 업무 3개 테이블, 서버 시드 5/3/10/9 |
| 로그인 | Argon2id, Spring Session JDBC, CSRF, ADMIN·READER, 최초 비밀번호 변경, 계정 중지·변경 세션 회수 |
| 콘텐츠 | 목록·상세·생성·수정·보관, revision 충돌, 참조·그래프 검증, 저장 실패 구분 |
| DWP 화면 | 헤더·사이드바·좌/우/상단·축소, 프리셋·주야간/시스템·밀도·고대비·모션·초기화 |
| 개인 기록 | 계정별 브라우저 저장, 메모·북마크·진도·JSON 백업·복원, 로그아웃 시 현재 내용 정리 |
| 관리 도구 | 계정 관리, JSON 콘텐츠 스튜디오·미리보기, 리서치 정책·수동 실행·검토·명시적 게시 |
| Agent | RSS/Atom·robots·허용 출처·공인 DNS와 TLS IP 고정·크기/시간 제한, Azure v1 Responses, 인용 검증 |
| 지속 실행 | 단일 worker, SQLite 또는 PostgreSQL 체크포인트, 실행 재전송·취소·모델 시도 제한, BE 실행 snapshot·예약 슬롯 중복 방지 |
| 게시 | BE만 게시 권한, 근거 재검사·revision·출처 연결·후보별 게시 멱등성, 독자 인사이트 화면 |

기존 H2와 브라우저 기록은 삭제하거나 새 계정에 임의 병합하지 않았다. 서버 원본 초기 콘텐츠는 FE의 소스 데이터를 백엔드 seed로 이관했다. H2에서 사용자가 추가한 별도 데이터가 있다면 백업·내보내기 후 검증된 가져오기 절차를 사용해야 한다.

## 실행한 검사

- Maven 테스트 4개: Unicode 장문 비밀번호의 무음 절단 방지, 세션 principal 직렬화에 해시 미포함, legacy ID 안정성, 잘못된 그래프의 DB 접근 전 거절.
- 실제 PostgreSQL: 기존 18개 구조에서 버전 마이그레이션, 별도 빈 검증 DB에서 V1~V5 적용과 bootstrap·최초 비밀번호 변경 검사.
- 실제 HTTP: 미로그인 401, 로그인 CSRF 403, 변경 전 콘텐츠 403, 변경 후 재로그인, 전체 콘텐츠 건수, READER의 관리·쓰기 차단, 계정 중지 후 기존 세션 차단, 오래된 콘텐츠 버전 409, 로그아웃 후 접근 차단.
- 프론트 빌드·Prettier와 Playwright 모의 API 5개 통과. 실제 PostgreSQL 백엔드에서 로그인·메모 저장·새로고침 복원·로그아웃 1개 시나리오 통과.
- 1440·1280·390·320px·200% 확대, 주간/야간·우측/상단 배치의 스크린샷을 FE/artifacts에 로컬 보존했다. 실제 화면을 시각 확인했다.
- Agent 테스트 60개·ruff·Docker 빌드 통과. 실제 공식 RSS 수집, PostgreSQL agent_runtime의 체크포인트 생성·새 runtime 복구·추가 모델 호출 없음 확인.
- 제한된 Agent DB 역할에서 계정·콘텐츠·세션 테이블 접근 거부를 확인했다.
- 별도 검증 DB의 BE→내부 토큰 인증 Agent→합성 RSS/모의 Azure 모델→후보→관리자 게시→인사이트 조회→동일 게시 재전송을 확인했다. 합성 자료는 운영 DB에 게시하지 않았다.
- Caddy HTTPS 진입점에서 프론트 200·상태 200·미인증 콘텐츠 401, secure 프로필 로그인·콘텐츠 조회와 __Host 쿠키의 Secure·HttpOnly·Path=/를 확인했다. 이 검사는 내부 CA를 curl 검증 제외 옵션으로 연결한 로컬 검사이며 사내 단말의 인증서 신뢰 완료를 의미하지 않는다.

## 회사망에서 수행할 Azure 확인

DWP의 AZURE_OPENAI_ENDPOINT·AZURE_OPENAI_API_KEY·배포명 호환을 구현했다. 이 환경의 Azure 주소는 회사망 밖에서 DNS 해석이 실패했으며 호출 성공으로 보고하지 않는다. 사용자가 회사에서 연결 확인하기로 결정했다.

1. 회사망에서 Agent 실행 프로세스에 DWP Azure 변수만 주입한다. scripts/run-local.py agent --dwp-env 경로 옵션은 지정된 파일에서 모델 관련 변수만 읽고 비밀을 출력하지 않는다.
2. ARCHDECK_AGENT_SERVICE_TOKEN을 BE·Agent에 동일하게 주입하고 LIVE_ENABLED를 명시적으로 켠다.
3. 승인한 출처 하나·문서 1~2개·출력 토큰 제한으로 수동 실행한다. 배포별 Responses/Structured Outputs 지원·사용량·거절/실패를 확인한다.
4. 후보의 주장·날짜·인용을 사람이 검토하고 게시한다. 실행만으로 자동 게시하지 않는다.

## 개발 실행

BE 루트 .env는 DB 설정과 로컬 비밀만 저장하며 추적하지 않는다. 초기 ADMIN은 README의 bootstrap 환경변수로 한 번 생성한다. 정해진 기본 비밀번호는 없다. 검증용 계정의 비밀번호는 로그나 문서에 표시하지 않는다.

```sh
# ArchDeck_BE
python3 scripts/run-local.py backend
# 별도 터미널, Agent 가상환경 설치 후
python3 scripts/run-local.py agent --dwp-env /path/to/dwp_agent/.env.local
# 별도 터미널
python3 scripts/run-local.py frontend
```

기본 서비스 포트는 BE 4000·FE 5173·Agent 4010이다. 이번 작업은 기존 실행 중인 H2 4000·Vite 5173을 보존하고 새 구현을 BE 4400·FE 5175에서 검증했다. 기존 프로세스를 종료·교체하는 것은 호스트 운영 절차에 따른다. 테스트용 Agent 모의 서버는 실제 운영에 사용하지 않는다.

## 사내 HTTPS 배포

```sh
# FE에서 npm run build 후 BE에서 실행
ARCHDECK_BACKEND_PORT=4000 docker compose -f deploy/compose.web.yaml up -d
```

기본 바인딩은 127.0.0.1:8443이다. 사내에 제공할 때는 ARCHDECK_HOST를 회사에서 승인한 이름으로, ARCHDECK_WEB_BIND를 필요한 인터페이스로 설정한다. BE의 secure cookie를 켜고 회사 인증서 또는 Caddy 내부 CA를 승인된 방식으로 신뢰시킨다. 테스트의 인증서 검사 생략 옵션을 운영 브라우저의 신뢰 정책으로 대체하지 않는다. DB·Agent·개발 서버는 외부에 직접 노출하지 않는다.

## 구현 범위와 후속 작업

현재는 단일 호스트·단일 BE/worker를 대상으로 한다. 연구 DB는 설계의 12개 정규 테이블 대신 3개 업무 테이블과 불변 snapshot·후보 payload로 구현했다. 분산 lease·outbox/inbox·개별 문서 버전 원장·기존 토픽 개정 diff·범용 JSON Schema 전체 검증은 완성했다고 표현하지 않는다. 재시작 때 실행 중이던 BE 작업은 실패 사유를 기록하며 대기 작업은 복구한다. 재시작으로 외부 호출 비용이 정확히 한 번이라는 보장도 하지 않는다.

P2인 정교한 그래프 연결선·본문 용어 자동 연결·독립 학습 경로 화면은 남아 있다. 스튜디오는 JSON 구조 편집·미리보기 방식이며 시각 그래프 편집기는 아니다. 개인 기록의 기기 간 자동 동기화는 원래 범위에서 제외한 대로 유지한다.
