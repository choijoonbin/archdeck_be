# ArchDeck DB 설계 파일

이 폴더는 PostgreSQL 환경을 재현하기 위한 소스다. 삭제 대상 임시 폴더나 실제 DB 파일을 담은 폴더가 아니다.

| 경로 | 용도 | Git |
|---|---|---|
| schema/001-initial.sql | 계정·세션·콘텐츠·흐름·경로의 18개 테이블과 제약·검색 뷰 | 추적 |
| seed/010-taxonomy.sql | 분류 4개·태그 11개, 계정·학습 본문 없음 | 추적 |
| verify.sql | fixture를 rollback하는 9개 무결성 검사 그룹 | 추적 |
| backups/ | 로컬 덤프와 복원 점검 파일 | 제외·로컬 보존 |

Docker는 백엔드 저장소 루트에서 실행한다. 실제 데이터는 archdeck_pgdata 명명 볼륨에 있다. init SQL은 빈 DB에만 적용하고 이후에는 버전 마이그레이션을 사용한다.

현재 앱의 data/archdeckdb.mv.db는 별도의 H2 데이터이며 Git에서 제외한다. 기존 기록을 임의로 삭제하지 않는다.

[데이터 설계](../docs/03-data-and-docker.md)와 [검증 결과](../docs/06-validation.md)를 참고한다.
