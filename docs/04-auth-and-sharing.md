# ArchDeck 사내 로그인과 공유 접근 설계

검토일: 2026-11-27 · 상태: 구현 전 설계와 수용 조건

최신 요구는 **사내 사용자가 아이디·비밀번호로 로그인해 열람하고, ADMIN이 콘텐츠와 계정을 관리하는 단일 same-origin 서비스**다. 이전의 로그인 없는 공유 계획은 폐기한다. 로그인 경험은 단순하게 유지하되 세션·권한·전송 보호는 서버에서 적용한다. 이 문서는 설계이며 현재 애플리케이션에 인증이 구현되었다는 뜻은 아니다.

## 기술과 범위

현재 프로젝트는 Java 21·Spring Boot 3.4.2다. 로컬 Maven BOM에서 Spring Security 6.4.2·Spring Session 3.4.1 관리를 확인했다. 앱 구현 전 의존성 지원 상태와 보안 패치를 확인하고, 적용할 버전에서 아래 동작을 다시 검증한다.

| 항목 | 결정 |
|---|---|
| 인증 | Spring Security의 아이디·비밀번호 로그인과 서버 세션 |
| 계정 | PostgreSQL `app_users`, ADMIN·READER 두 역할 |
| 세션 | Spring Session JDBC와 PostgreSQL 표준 `spring_session`, `spring_session_attributes` |
| 비밀번호 | 신규 계정은 Argon2id와 `DelegatingPasswordEncoder`의 알고리즘 접두사 |
| 프론트 연동 | 같은 출처의 `/api` 사용, 로그인 상태는 서버의 `/api/auth/me`로 확인 |
| 개인 기록 | 브라우저 + 안정적인 `serviceId` + 불변 사용자 UUID `userId`별로 학습 기록·초안·설정을 분리 |
| 제외 | 공개 회원가입, 소셜 로그인, SSO, 앱의 Keycloak 연동, JWT, Redis, 별도 프로필 제품, 알림 |

Keycloak·OIDC·PKCE·BFF는 학습 콘텐츠로 계속 제공한다. 앱의 사내 로그인 구현 방식과 학습 주제의 인증 아키텍처를 혼동하지 않는다.

## 역할과 공개 범위

| 기능·자원 | 미로그인 | READER | ADMIN |
|---|---|---|---|
| 로그인 화면·일반 UI 정적 자산 | 허용 | 허용 | 허용 |
| CSRF 초기 토큰 발급 | 허용, 콘텐츠 없음 | 허용 | 허용 |
| 게시 콘텐츠·용어·비교·검색·학습 경로·조회용 내보내기 | 차단 | 허용 | 허용 |
| 초안·미게시 콘텐츠·수정 이력 | 차단 | 차단 | 허용 |
| 콘텐츠 생성·수정·게시·삭제·가져오기 | 차단 | 차단 | 허용 |
| 계정 생성·목록·역할 변경·사용 중지·비밀번호 재설정 | 차단 | 차단 | 허용 |
| 본인 비밀번호 변경·로그아웃 | 차단 또는 세션 정리만 | 허용 | 허용 |
| 본인 브라우저의 학습 기록·설정 | 내용 표시 차단 | 본인 영역 | 본인 영역 |

READER의 읽기 전용은 콘텐츠와 관리 데이터에 대한 제한이다. 본인 비밀번호 변경과 로그아웃은 허용한다. 서버 요청 경로의 역할 검사와 서비스 계층의 게시 상태 필터를 함께 적용한다. 화면에서 버튼을 숨기는 것만으로 권한을 통제하지 않는다. ADMIN은 보통 호스트 PC에서 관리하지만 사내 HTTPS로 로그인한 ADMIN의 관리 작업도 허용한다. 별도의 reader/writer 배포와 ADMIN의 LAN 접근 금지는 MVP에 강제하지 않는다.

미로그인 API는 콘텐츠 존재 여부·제목·태그·개수·검색 결과·수정 시각을 반환하지 않는다. READER 검색과 관련 항목 조회는 게시 데이터만 대상으로 한다. 인가 검사 전 대상 존재 여부를 조회해 404 차이로 관리 데이터를 노출하지 않도록 순서를 정한다.

## 계정과 최초 관리자

- `id`는 불변 UUID이며 로그인 아이디와 분리한다. 세션 principal의 이름에는 사용자 UUID 문자열을 사용한다. 개인 기록은 서비스 인스턴스 식별자 `serviceId`와 사용자 UUID `userId`를 함께 사용해 구분한다.
- 로그인 아이디는 소문자 ASCII 3~32자이며 첫 글자는 영문·숫자, 나머지는 영문·숫자·`.`·`_`·`-`를 허용한다. 입력은 로그인·생성 양쪽에서 같은 규칙으로 정규화한다. 표시 이름은 별도 필드다.
- 관리자가 READER 또는 ADMIN 계정을 생성한다. 자동 회원가입과 이메일 기반 자가 비밀번호 복구는 MVP에 추가하지 않는다.
- 신규·재설정 비밀번호는 `must_change_password=true`로 처리한다. 이 상태에서는 본인 정보·CSRF·비밀번호 변경·로그아웃만 허용하고 콘텐츠를 열지 않는다.
- 초기 스키마와 시드에는 계정을 0건 넣는다. 정해진 관리자 아이디·평문 비밀번호·샘플 비밀번호 해시는 배포 파일에 포함하지 않는다.
- 최초 ADMIN은 호스트 PC에서 명시적으로 실행하는 일회성 bootstrap CLI로 만든다. 가능하면 숨김 입력을 사용하고, 자동화 시 비밀 파일·환경변수는 소스 관리·명령행 인수·로그에서 제외한다. 이미 ADMIN이 있으면 bootstrap은 덮어쓰지 않고 실패한다.
- 마지막 활성 ADMIN을 사용 중지·강등할 수 없도록 동시 요청까지 포함해 서비스 트랜잭션에서 검사한다. 복구는 호스트의 관리 CLI를 사용한다.

## 비밀번호 정책과 저장

OWASP는 신규 비밀번호 저장에 Argon2id를 우선 제시하고 최소 19MiB 메모리·반복 2·병렬 1을 안내한다. Spring의 `Argon2PasswordEncoder`는 메모리·반복·병렬 값을 지정할 수 있으며 BouncyCastle을 사용한다. [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html), [Spring Security 6.4 Argon2 API](https://docs.spring.io/spring-security/site/docs/6.4.x/api/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.html)

ArchDeck의 신규 저장 기준은 다음과 같다.

| 항목 | 정책 |
|---|---|
| 알고리즘 | Argon2id, 저장 접두사 `{argon2}` |
| 시작 파라미터 | 메모리 19,456KiB, 반복 2, 병렬 1, salt 16바이트, hash 32바이트 |
| 설정 방식 | `Argon2PasswordEncoder(16, 32, 1, 19456, 2)`를 위임 인코더의 신규 저장 대상으로 등록 |
| 의존성 | `org.bouncycastle:bcprov-jdk18on`을 추가하고 적용 BOM·보안 패치와 호환성 확인 |
| 성능 조정 | 호스트 PC에서 정상·동시 로그인 시간을 측정해 상향 조정한다. 위 최소값보다 낮추지 않는다. |
| 허용 길이 | Unicode code point 15~128개, UTF-8 최대 512바이트 |
| 문자 처리 | 한글·공백·붙여넣기·비밀번호 관리자 사용 허용. 비밀번호를 trim·대소문자 변환·무음 절단하지 않는다. |
| 약한 값 | 아이디·서비스명과 동일한 값과 알려진 취약 비밀번호의 로컬 차단 목록을 검사한다. |
| 변경 | 본인 변경은 현재 비밀번호 확인 후 실행한다. 정기 변경을 강제하기보다 초기·재설정·침해 시 변경한다. |

Spring의 `defaultsForSpringSecurity_v5_8()`은 메모리 16MiB이므로 이 설계의 최소 19MiB와 같지 않다. 신규 저장에는 표의 명시적 파라미터를 사용하며 기본 팩토리의 bcrypt 저장 설정으로 대체하지 않는다.

15자는 MFA 없는 현재 범위를 위한 기준이다. 128자·512바이트는 ArchDeck의 길이·자원 상한이며 OWASP의 고정 요구값으로 표현하지 않는다. Java `codePointCount`와 UTF-8 바이트 길이를 서버 기준으로 확인하고 프론트도 같은 결과를 제공한다. 잘못된 Unicode 입력은 거절한다. [OWASP Authentication의 비밀번호 길이·문자 정책](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)

`DelegatingPasswordEncoder`는 `{id}`로 저장 형식을 구분한다. 신규 저장은 Argon2id만 사용한다. 향후 기존 bcrypt 해시를 이관할 때만 명시적으로 검증용 매핑을 추가하며, bcrypt의 72바이트 입력 한계를 지킨다. 평문·빠른 SHA 해시·`noop`과 알 수 없는 알고리즘 접두사를 허용하지 않는다. [Spring Password Storage](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html)

계정 API·로그·내보내기에는 `password_hash`를 포함하지 않는다. bootstrap과 로그인 실패 처리에도 평문·해시·세션 ID·CSRF 토큰을 기록하지 않는다.

## 로그인과 CSRF 계약

로그인은 Spring Security 표준 인증 필터로 처리하고 JSON 성공·실패 응답만 조정한다. `application/x-www-form-urlencoded`의 `username`, `password`를 받아 임의 로그인 컨트롤러에서 세션을 직접 조립하는 방식을 피한다. JSON 전용 필터로 변경한다면 세션 인증 전략과 `SecurityContextRepository` 저장까지 별도 검증해야 한다.

CSRF는 세션 저장 방식으로 유지한다. 프론트는 `/api/auth/csrf` 응답의 토큰을 메모리에 보관하고 모든 변경 요청의 `X-CSRF-TOKEN` 헤더로 전송한다. 앱 초기화와 로그인·로그아웃 성공 후 토큰을 새로 요청한다. 로그인·로그아웃도 예외로 두지 않는다. Spring 공식 6.4.2 문서는 CSRF 초기 발급 엔드포인트, 로그인 전 허용, 인증·로그아웃 후 재발급을 설명한다. [Spring Security 6.4.2 CSRF](https://github.com/spring-projects/spring-security/blob/6.4.2/docs/modules/ROOT/pages/servlet/exploits/csrf.adoc)

| 엔드포인트 | 계약 |
|---|---|
| `GET /api/auth/csrf` | 토큰·헤더명만 반환. 미로그인에서도 허용하며 `no-store` 적용 |
| `POST /api/auth/login` | CSRF 검사, 표준 인증 필터, 성공 시 최소 계정 정보와 `mustChangePassword`, 실패 시 동일한 401 |
| `GET /api/auth/me` | `serviceId`·사용자 UUID·표시 이름·역할·초기 변경 여부만 반환. 미인증은 401 |
| `POST /api/auth/password` | 인증·CSRF·현재 비밀번호·새 정책 검사, 변경 후 모든 세션 종료와 재로그인 |
| `POST /api/auth/logout` | CSRF 검사, 서버 세션·인증 컨텍스트 삭제, 쿠키 만료, 204 |
| `GET /api/admin/users`와 관리 변경 API | ADMIN만 허용, 변경은 CSRF 필수, 해시는 응답하지 않음 |

미존재 아이디·틀린 비밀번호·비활성 계정의 인증 실패는 동일한 401 문구로 응답하고 사용자 존재에 따른 빠른 반환을 피한다. 미로그인 콘텐츠 조회는 401, 로그인했지만 역할이 부족한 요청은 대상 세부정보 없는 403으로 처리한다. 세션 만료와 네트워크 장애는 프론트에서 구분한다. `returnTo`는 허용된 동일 출처 내부 경로만 허용한다.

## 세션의 수명과 회수

Spring Session JDBC 표준 두 테이블을 PostgreSQL 마이그레이션으로 관리한다. 세션 attributes에는 비밀번호·DB Entity 전체를 저장하지 않고, 직렬화 가능한 최소 principal인 사용자 UUID·현재 역할·로그인 시 `auth_version`을 저장한다. Spring Security principal의 이름과 Session principal 인덱스가 UUID를 사용하도록 구성하고 실제 저장값을 검사한다. principal UUID로 인덱스를 조회해 해당 사용자의 세션을 찾는다. [Spring Session 3.4 JDBC](https://docs.spring.io/spring-session/reference/3.4/configuration/jdbc.html)

| 항목 | ArchDeck 정책 |
|---|---|
| 세션 생성 | 인증에 필요할 때 생성. 로그인 전 CSRF 세션은 짧게 유지 |
| 로그인 성공 | 세션 ID 변경으로 고정 공격 방어, CSRF 재발급 |
| 만료 | 인증 세션 유휴 30분, 절대 수명 8시간을 초기 운영값으로 설정 |
| 저장 | PostgreSQL에 서버 세션 보존. 브라우저에는 불투명 세션 쿠키만 전달 |
| 지속 로그인 | remember-me는 도입하지 않음. 브라우저 종료 자체를 세션 종료 보장으로 취급하지 않음 |
| 로그아웃 | 서버 세션·SecurityContext 삭제와 쿠키 만료. 기존 쿠키 복사본 재사용도 401 |
| 계정 사용 중지·권한 변경·비밀번호 변경/재설정 | 같은 계정 변경 트랜잭션에서 `auth_version` 증가 후 해당 principal의 모든 세션 삭제 |

로그인 후 요청은 인가 검사 전에 `app_users`의 `enabled`와 `auth_version`을 확인한다. 삭제·비활성·버전 불일치이면 세션을 폐기하고 401을 반환한다. 세션에 남아 있는 과거 역할만 신뢰하지 않는다. 소규모 단일 인스턴스에서는 우선 매 요청 DB 조회로 정확성을 확보하며, 조회 캐시를 추가해 계정 중지 반영을 지연시키지 않는다.

이 검사와 principal별 세션 삭제를 함께 사용해 세션 삭제와 동시 요청의 저장 경합에도 후속 접근이 유지되지 않게 한다. ‘즉시 회수’는 **계정 변경 커밋 후 시작되는 다음 보호 요청부터 차단**하는 의미다. 이미 실행 중인 요청을 원격 취소한다고 보장하지 않으며, 계정·게시 같은 중요 변경은 서비스 트랜잭션에서도 현재 권한을 확인한다.

로그인 시 세션 ID 변경은 Spring의 `changeSessionId` 보호를 유지한다. 만료 행 정리는 저장 공간 관리이며 만료 세션을 허용하는 유예 시간이 아니다. 절대 수명은 idle timeout과 별도로 검사한다. [Spring Security 6.4.2 세션 관리](https://github.com/spring-projects/spring-security/blob/6.4.2/docs/modules/ROOT/pages/servlet/authentication/session-management.adoc)

## 사내 HTTPS와 호스트 개발 환경

| 항목 | 사내 공유 환경 | 호스트 로컬 개발 |
|---|---|---|
| 접속 | 신뢰 가능한 사내 이름·인증서의 HTTPS | `127.0.0.1`·`localhost` 한정 HTTP 허용 |
| 세션 쿠키 | `__Host-ArchDeckSession`, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, Domain 미설정 | 별도 이름 `ArchDeckSession`, `HttpOnly`, `SameSite=Lax`, `Secure=false` |
| 경로 | 같은 출처에서 프론트·`/api` 제공 | Vite `/api` 프록시로 같은 출처 유지 |
| 외부 노출 | TLS 프록시의 필요한 포트만 사내에 공개 | 개발 서버·API는 loopback 바인딩 |
| PostgreSQL | 컨테이너 내부 통신, 호스트 공개가 필요하면 loopback 바인딩만 | `127.0.0.1` 포트 사용 |

사내 HTTP에서 실계정 비밀번호를 주고받는 구성을 공유 완료 조건으로 삼지 않는다. HTTPS 프록시 뒤에서는 신뢰한 프록시의 전달 헤더만 수용하고, 애플리케이션 포트를 직접 사내에 열어 TLS·접근 정책을 우회할 수 없게 한다. CORS wildcard와 임의 Origin의 credential 요청을 허용하지 않는다.

HTTPS·쿠키의 `Secure`·`HttpOnly`·`SameSite`는 각각 다른 보호를 제공한다. `SameSite`를 CSRF 토큰의 대체 수단으로 보지 않는다. [OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)

## 미인증 콘텐츠 누출과 캐시 방지

현재 FE의 `src/data` 초기 콘텐츠는 정적 JavaScript 번들에 포함된다. API에 인증만 붙이고 이 데이터를 유지하면 보호한 본문·용어·비교 내용이 번들에서 노출된다. 따라서 공유 배포에서는 다음을 함께 변경한다.

- 콘텐츠는 인증 API에서만 로드한다. FE 번들·public JSON·지연 로드 청크·소스맵에 보호 콘텐츠와 초안을 포함하지 않는다.
- 인증 실패·세션 만료 때 내장 콘텐츠로 fallback하지 않는다. 이미 읽은 화면도 메모리에서 내리고 로그인 진입을 제공한다.
- 로그인·계정·콘텐츠·검색·내보내기 응답에 `Cache-Control: no-store`를 적용한다. 프록시·서비스워커가 보호 응답을 캐시하지 않도록 한다.
- 로그아웃·계정 전환 시 API 캐시·활성 상세·메모·초안·검색 결과를 메모리에서 제거하고 다른 탭에도 전달한다. 브라우저 뒤로 가기·BFCache 복귀 시 세션 확인 전 이전 화면을 보이지 않는다.
- 일반 UI 정적 자산은 캐시할 수 있으나 콘텐츠 데이터와 분리한다. 기존 개발용 `/api/health`의 Java·프레임워크 정보와 H2 console은 사내 공개 경로에서 차단한다.
- 보안 헤더는 기본 보호를 유지하고 CSP·프레임 삽입 제한·MIME 스니핑 방지를 콘텐츠 렌더링 방식과 함께 검증한다. [Spring Security 6.4.2 응답 헤더](https://github.com/spring-projects/spring-security/blob/6.4.2/docs/modules/ROOT/pages/servlet/exploits/headers.adoc)

개인 기록은 `archdeck:learning:v2:<serviceId>:<userId>`, 외관 설정은 `archdeck:appearance:v1:<serviceId>:<userId>`에 저장한다. `serviceId`는 서비스 인스턴스의 안정적인 식별자, `userId`는 `app_users.id` UUID다. 표시 이름·username·포트 번호로 대체하지 않고 서비스 이전·복원 시 이름 공간 연결을 명시적으로 확인한다. 이 구분은 화면 혼합을 막는 정책이며 로컬 저장소 암호화는 아니다. 공용 PC를 위해 ‘이 브라우저의 내 기록 지우기’를 제공한다. 계정·비밀번호·해시·세션을 학습 백업에 포함하지 않고, 기존 공용 `default_user` 기록을 새 계정에 자동 배정하지 않는다.

## DB 백업과 복원 후 세션 회수

개인 학습 JSON 백업과 운영 DB 백업을 구분한다. DB 백업은 계정 복구를 위해 `app_users.password_hash`를 포함할 수 있으므로 제한된 관리자·호스트 계정만 읽게 하고, 파일 권한·보관 위치·암호화로 보호한다. 프론트 계정 응답이나 개인 학습 JSON으로 DB 백업을 제공하지 않는다. 평문 비밀번호는 원 DB와 DB 백업 양쪽에 저장하지 않는다.

DB 백업에는 세션 테이블의 구조만 보존하고 `spring_session`, `spring_session_attributes`의 데이터는 제외하는 것을 기본으로 한다. 세션 데이터가 포함된 기존 백업을 복원하는 경우도 아래 전역 로그아웃 절차를 생략하지 않는다.

1. 애플리케이션을 중지하고 사내 진입을 닫은 상태에서 DB를 복원한다.
2. 복원한 모든 세션과 세션 attributes를 제거하고, 모든 계정의 `auth_version`을 증가시킨다.
3. 복원 시점에 따라 계정 활성·역할·비밀번호 상태도 되돌아갈 수 있으므로 관리자 계정과 접근 정책을 확인한다.
4. 애플리케이션의 인증 캐시를 비운 상태로 재시작하고 이전 쿠키가 401을 반환하는지 확인한 뒤 사내 진입을 연다. 모든 사용자는 다시 로그인한다.

세션 제거와 버전 증가는 서비스 재개 전에 완료한다. 복원 작업 중 활성 요청이 과거 세션을 다시 저장할 수 있는 상태로 애플리케이션을 함께 실행하지 않는다.

## 로그인 시도 제한과 운영

소규모 단일 인스턴스에 맞춰 크기 제한·만료가 있는 메모리 기반 로그인 제한을 먼저 둔다. 계정 키는 정규화한 아이디로 구분하고, 계정별 제한과 IP별 제한을 함께 적용한다. 사내 공유 IP에 대해서는 계정 단위 제한을 우선해 모든 사용자를 함께 잠그지 않는다. 미존재 아이디에도 동일한 제한 동작을 적용한다.

초기 조정값은 계정별 15분 내 5회 실패 후 짧은 재시도 대기, 반복 실패 시 최대 15분까지 지수 증가, IP별 분당 30회 로그인 요청 상한이다. 값은 운영 정책 제안이며 측정 후 조정한다. 해시 동시 실행 수·요청 본문 크기·메모리 키 수를 제한하고 `429`에는 일반 문구와 `Retry-After`만 제공한다. 영구 계정 잠금과 존재 여부를 드러내는 응답은 피한다. [OWASP Authentication의 시도 제한 지침](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)

프로세스 재시작 시 메모리 제한 기록은 초기화된다. 이는 단일 PC 운영 범위의 선택이며 다중 인스턴스로 확장할 때 공유 제한 저장소를 별도로 설계한다. 인증 성공·실패·관리자 계정 변경·세션 회수는 식별자와 결과 중심으로 로그에 남기되 자격 증명은 기록하지 않는다.

## 구현 완료 수용 조건

| ID | 검증해야 할 동작 |
|---|---|
| AUTH-01 | 관리자 수동 bootstrap 이전에는 계정이 0건이고 정해진 평문·샘플 계정이 없다. 두 번째 bootstrap이 기존 계정을 바꾸지 않는다. |
| AUTH-02 | 로그인 성공 후 새로고침과 API 호출에서 동일한 인증 상태가 유지된다. 로그인 전후 세션 ID가 다르다. |
| AUTH-03 | 미존재·오입력·비활성 계정이 같은 401·일반 문구를 반환하며 응답·로그에 비밀이 없다. 제한을 넘으면 조절된 429가 발생한다. |
| AUTH-04 | CSRF 없이 로그인·쓰기·비밀번호 변경·로그아웃 요청을 보내면 거절한다. 올바른 토큰으로 성공하고 인증·로그아웃 후 재발급된다. |
| AUTH-05 | 미인증의 API·검색·내보내기·정적 번들에서 보호 콘텐츠와 메타데이터를 읽을 수 없다. |
| AUTH-06 | READER가 관리 화면 경로·관리 API·초안 조회·직접 수정 요청을 시도해도 서버가 차단한다. ADMIN은 허용된 작업을 수행한다. |
| AUTH-07 | 신규·재설정 계정은 비밀번호를 바꾸기 전 콘텐츠를 읽지 못한다. 비밀번호 변경은 모든 기존 세션을 종료한다. |
| AUTH-08 | 계정 중지·권한 변경 커밋 뒤 기존 쿠키의 다음 보호 요청은 차단된다. 동시 세션 저장과 회수 경합을 포함한다. |
| AUTH-09 | 유휴 30분·절대 8시간 만료와 로그아웃 뒤 이전 쿠키를 재전송해도 인증되지 않는다. |
| AUTH-10 | 사내 HTTPS 쿠키의 Secure·HttpOnly·SameSite·Path·Domain을 확인한다. local HTTP 프로필이 사내 공유 바인딩으로 시작되지 않는다. |
| AUTH-11 | 로그아웃·만료·다른 계정 로그인·다른 탭 전환·뒤로 가기에서 이전 사용자 화면·메모·초안이 노출되지 않는다. |
| AUTH-12 | 한글·공백·15/128 code point·512바이트 경계와 초과 입력을 서버·프론트에서 같은 규칙으로 처리한다. 서로 다른 비밀번호가 무음 절단으로 같아지지 않는다. |
| AUTH-13 | 계정 응답·개인 학습 JSON 백업·오류·운영 로그에 비밀번호·해시·세션 ID·CSRF 토큰이 없다. 계정 해시를 포함할 수 있는 DB 백업은 제한 권한으로 보호하며 세션 데이터를 제외한다. 복원 후 세션 삭제·전 계정 auth_version 증가와 이전 쿠키 차단을 검증한다. DB 포트와 H2 console은 사내 공개되지 않는다. |
| AUTH-14 | 마지막 활성 ADMIN의 중지·강등을 동시 요청에서도 막고 호스트 복구 절차를 검증한다. |

위 수용 조건은 앱 인증·공유 통합 단계에서 실행한다. DB 테이블 생성과 Docker 기동 성공만으로 로그인·권한·HTTPS 구현이 완료되었다고 보고하지 않는다.
