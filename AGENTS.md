# ArchDeck Backend 작업 기준

원격은 https://github.com/choijoonbin/archdeck_be.git, 개발 브랜치는 dev다.

작업 전에 docs/00-project-baseline.md와 docs/05-implementation-plan.md를 읽고 해당 분야의 상세 문서를 확인한다. 요구나 결정이 바뀌면 같은 작업에서 문서와 상태를 갱신한다. 공통 기획·SQL·Docker 원본은 이 저장소에서 관리한다.

## 최신 요구

- 개인 PC에서 관리하고 사내 사용자가 아이디·비밀번호로 로그인해 열람하는 기술 학습·설계 판단 도구다. 앞선 로그인 제외 결정은 폐기됐다.
- 권한은 ADMIN·READER다. 헤더 로그인 상태·로그아웃을 포함하고 알림은 제외한다. 회원가입·SSO·외부 IAM·결제는 현재 범위에 없다.
- 프론트는 DWP형 헤더·사이드바를 React·TypeScript·Tailwind로 구현한다. preset, 좌·우·상단, 펼침·축소, 주간·야간·시스템, 밀도·고대비·모션·초기화·설정 보존이 필수다.
- 개인 기록은 MVP에서 serviceId와 불변 로그인 계정 UUID별 브라우저 저장으로 분리한다. default_user 공용 동기화를 사용하지 않는다.

## 구현과 검증

- 기존 빈 흐름 오류·초기화 경합·저장 실패 처리를 해결하기 전 안정화됐다고 표현하지 않는다.
- 콘텐츠 스키마와 참조 무결성을 지키며 흐름이 없는 문서도 정상 렌더링한다.
- 서버 인증·권한·CSRF가 접근 경계다. 메뉴 숨김·Origin·익명 UUID를 인증으로 취급하지 않는다. 공유 번들에 보호 콘텐츠를 내장하지 않는다.
- 비밀번호·세션·DB 자격증명을 문서·seed·로그·Git에 넣지 않는다.
- Docker는 이 저장소 루트에서 실행한다. 기존 H2와 다른 프로젝트의 컨테이너·볼륨은 변경하지 않는다.
- init SQL은 빈 볼륨 최초 초기화용이며 이후 변경은 버전 마이그레이션으로 관리한다.
- 설계·DB 기반 준비와 실제 앱 통합 완료를 구분한다. DB 변경은 db/verify.sql과 필요 시 백업·복원을 확인한다.
- 화면 관련 변경은 FE 저장소 지침과 1440·1280·390·320px, 200% 확대·키보드·테마·모션·오류 상태를 확인한다.

## 문서

00-project-baseline은 제품 기준, 01-benchmark는 공식 출처 벤치마킹, 02-product-and-navigation은 UX, 03-data-and-docker는 DB, 04-auth-and-sharing은 인증, 05-implementation-plan은 작업 목록, 06-validation은 검증 근거, 08-repository-layout은 저장소 구조다. 모두 docs 아래에 있다.
