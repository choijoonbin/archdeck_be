# ArchDeck 저장소와 폴더 관리

기준일: 2026-11-27 · 개발 브랜치: 세 저장소 모두 dev

archdeck_be·archdeck_fe·archdeck_agent는 독립 저장소다. ArchDeck 루트는 세 저장소의 로컬 작업 공간이다. 이력이 필요한 설계·SQL·Docker 파일을 백엔드 저장소로 모았다.

## 최종 위치

| 항목 | 위치 | 이유 |
|---|---|---|
| Spring Boot·이전 Node | ArchDeck_BE | 현재 소스 기준선과 이전 구현 보존 |
| 리서치 Agent·하네스·Azure adapter·자체 Dockerfile | ArchDeck_Agent | 별도 실행·의존성·검증·이력 |
| React·빌드 설정·잠금 파일 | ArchDeck_FE | 독립 빌드와 이력 |
| 공통 기획·벤치마킹·UX·인증·작업 | ArchDeck_BE/docs | 원본 한 곳, FE는 링크 참조 |
| 테이블·분류 seed·검증 SQL | ArchDeck_BE/db | Docker와 향후 마이그레이션 소스 |
| Docker·환경 예시 | ArchDeck_BE/compose.yaml, .env.example | DB와 같은 저장소에서 재현 |
| 저장소 작업 지침 | 각 저장소 AGENTS.md | 단독 체크아웃에서도 적용 |
| 루트 README·AGENTS | 로컬 안내 | 각 저장소로 이동하는 링크와 공통 진입 기준 |

## Git 추적 기준

.env·실제 자격증명, H2·JSON 로컬 DB, db/backups, node_modules, target, dist, tsbuildinfo, 로그·OS 파일은 삭제하지 않고 .gitignore로 제외한다. 소스·lock 파일·환경 예시·문서·SQL·Compose는 추적한다.

## DB와 Docker

이전 루트 db는 DDL·seed·검증 SQL이므로 삭제 대신 ArchDeck_BE/db로 이동했다. 실제 데이터는 archdeck_pgdata 볼륨에 있다. Compose 프로젝트 archdeck과 포트 127.0.0.1:55433은 유지한다.

Docker는 ArchDeck_BE에서 실행한다. SQL bind mount 경로를 새 위치로 갱신하되 기존 볼륨과 H2·다른 프로젝트 컨테이너는 보존한다.

## 기준선

각 저장소의 첫 커밋은 기존 앱 소스다. 이후 커밋에서 문서·DB 기반·파일 구조를 정리했다. 이 작업에서 앱 기능 소스는 수정하지 않는다. 로그인·레이아웃·새 DB 연동은 후속 구현이다.

dev는 origin/dev를 추적한다. 이 작업에서 임의의 main 브랜치나 배포 자동화는 추가하지 않는다. 배포 브랜치·보호 정책은 운영 단계에서 정한다.

## 이동 후 확인

- Docker 컨테이너의 작업 디렉터리와 SQL bind mount를 ArchDeck_BE 위치로 갱신했다. archdeck_pgdata 볼륨은 그대로 유지했다.
- 18개 테이블·분류 4개·태그 11개를 보존했고 db/verify.sql의 9개 검사 그룹이 통과했다.
- 프론트 npm run build와 백엔드 Maven 오프라인 검사가 통과했다. Maven에는 실행할 자동 테스트가 없음을 구분한다.
- 앱 기능 소스는 각 저장소의 최초 기준선 커밋과 차이가 없다. 문서·설정 소속 정리는 후속 커밋으로 분리한다.

## Agent 저장소 추가

새 원격은 https://github.com/choijoonbin/archdeck_agent.git 이다. 공통 기획은 BE/docs/09~12, Agent 실행·의존성·테스트는 Agent 저장소에서 관리한다. 공통 DB compose와 기존 18개 테이블은 이 설계 작업에서 변경하지 않는다.
