package io.archdeck.backend.init;

import io.archdeck.backend.dto.*;
import io.archdeck.backend.service.ArchDeckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final ArchDeckService archDeckService;

    @Override
    public void run(ApplicationArguments args) {
        if (!archDeckService.getAllTopics(null, null).isEmpty()) {
            log.info("ArchDeck DB에 이미 데이터가 존재하여 시딩을 건너뜁니다.");
            return;
        }

        log.info("🌱 [ArchDeck Spring Boot] 초기 엔터프라이즈 아키텍처 및 보안 데이터 시딩을 시작합니다...");

        seedTopics();
        seedGlossary();
        seedComparisons();
        seedChecklists();
        seedUserLearning();

        log.info("✅ [ArchDeck Spring Boot] 데이터 시딩 완료!");
    }

    private void seedTopics() {
        // 1. Keycloak & IAM
        TopicDto keycloak = TopicDto.builder()
                .id("keycloak-iam-overview")
                .slug("keycloak-iam-overview")
                .title("Keycloak & Open Source IAM Overview")
                .koreanTitle("Keycloak & 오픈소스 IAM 아키텍처 개요")
                .category("IAM & Auth")
                .summary("엔터프라이즈 환경에서 통합 인증·인가(SSO, OIDC, RBAC)를 비즈니스 로직과 물리적으로 분리하는 이유와 글로벌 오픈소스 IAM 생태계를 조망합니다.")
                .overview("현대적인 웹/모바일 아키텍처에서 인증과 인가를 각 비즈니스 백엔드나 프론트엔드에 직접 구현하는 것은 심각한 안티패턴입니다.\nKeycloak은 Red Hat 주도의 CNCF 생태계 표준 오픈소스 IAM으로서 OIDC/OAuth2/SAML 2.0 지원, 세밀한 RBAC, Infinispan 분산 세션 클러스터링을 기본 제공합니다.")
                .badges(List.of("Enterprise IAM", "OIDC & OAuth 2.0", "RBAC & ABAC", "SSO", "Keycloak 25+"))
                .readingTimeMinutes(7)
                .diagramNodes(List.of(
                        TopicDto.DiagramNodeDto.builder().id("user").label("End User / Browser").subLabel("Client Agent").type("client").icon("User").build(),
                        TopicDto.DiagramNodeDto.builder().id("app").label("Frontend (SPA/Web)").subLabel("React / Next.js").type("client").icon("Layout").build(),
                        TopicDto.DiagramNodeDto.builder().id("iam").label("Keycloak IAM Cluster").subLabel("OIDC / Infinispan").type("auth").icon("ShieldCheck").build(),
                        TopicDto.DiagramNodeDto.builder().id("gateway").label("API Gateway / BFF").subLabel("Envoy / Spring Cloud").type("gateway").icon("Network").build(),
                        TopicDto.DiagramNodeDto.builder().id("services").label("Core Microservices").subLabel("Stateless APIs").type("server").icon("Server").build(),
                        TopicDto.DiagramNodeDto.builder().id("user_db").label("IAM Database / LDAP").subLabel("PostgreSQL / AD").type("database").icon("Database").build()
                ))
                .flowSteps(List.of(
                        TopicDto.FlowStepDto.builder().step(1).title("인증 요청 및 SSO 리다이렉트").actor("User/Browser").target("Keycloak (IAM)").protocol("HTTPS GET (Front-Channel)").action("Keycloak 인증 엔드포인트(/auth)로 리다이렉트").description("클라이언트는 비밀번호를 보지 않고 Keycloak 로그인 페이지에서 안전하게 인증을 진행합니다.").activeNodes(List.of("user", "app", "iam")).build(),
                        TopicDto.FlowStepDto.builder().step(2).title("사용자 자격 증명 검증 및 MFA").actor("Keycloak (IAM)").target("IAM Database / LDAP").protocol("Internal DB Query / LDAP Bind").action("패스워드 해시 및 TOTP MFA 검증 후 세션 생성").description("Infinispan 분산 인메모리 캐시에 SSO 세션을 등록하고 일회용 인가 코드를 발행합니다.").activeNodes(List.of("iam", "user_db")).build(),
                        TopicDto.FlowStepDto.builder().step(3).title("인가 코드 반환 및 토큰 발급").actor("Keycloak (IAM)").target("Frontend (SPA/Web)").protocol("HTTPS 302 Redirect → POST /token").action("앱의 Callback URL로 리다이렉트 후 토큰 교환").description("서명된 JWT(Access Token, ID Token) 및 Refresh Token을 발행합니다.").activeNodes(List.of("iam", "app")).build(),
                        TopicDto.FlowStepDto.builder().step(4).title("JWKS 기반 무상태(Stateless) API 인가").actor("Frontend (SPA/Web)").target("Core Microservices").protocol("HTTPS REST (Bearer JWT)").action("Authorization: Bearer <JWT> 헤더 첨부 호출").description("마이크로서비스들은 Keycloak의 JWKS 공개키로 JWT 서명을 로컬에서 무상태 검증합니다.").activeNodes(List.of("app", "gateway", "services")).build()
                ))
                .keyTakeaways(List.of(
                        "관심사의 분리: 비즈니스 서비스는 도메인 로직에만 집중하고 인증/계정 관리는 전담 IAM에 위임",
                        "표준 프로토콜 수용: OIDC 및 OAuth 2.0 표준 채택으로 멀티 플랫폼 확장 용이",
                        "단일 장애점 극복: 백엔드는 JWKS 기반 무상태 검증, Keycloak은 Infinispan 클러스터로 HA 구성"
                ))
                .tradeOffs(TopicDto.TradeOffsDto.builder()
                        .pros(List.of("SSO로 전사 서비스 간 매끄러운 단일 로그인 경험", "MFA, Passkey, WebAuthn 표준 즉시 도입", "일관된 JWT Claims 기반 인가 모델"))
                        .cons(List.of("고가용성 인프라 운영 복잡도 수반", "초기 러닝 커브 (Realm, Client, Mapper 등)"))
                        .build())
                .codeSnippets(List.of(
                        TopicDto.CodeSnippetDto.builder().id("kc-compose").title("Keycloak 25+ 프로덕션 Compose").filename("docker-compose.iam.yml").language("yaml").code("services:\n  keycloak:\n    image: quay.io/keycloak/keycloak:25.0\n    command: start --optimized").description("최적화 옵션이 적용된 독립 Keycloak 구성입니다.").build()
                ))
                .securityChecklistIds(List.of("chk-iam-1"))
                .relatedTermIds(List.of("jwks", "stateless-infinispan", "back-channel", "client-types"))
                .relatedTopicIds(List.of("spa-pkce-direct", "bff-token-relay"))
                .build();
        archDeckService.saveTopic(keycloak);

        // 2. SPA + PKCE
        TopicDto spa = TopicDto.builder()
                .id("spa-pkce-direct")
                .slug("spa-pkce-direct")
                .title("Frontend Direct OIDC Integration (SPA + PKCE)")
                .koreanTitle("프론트엔드 직접 OIDC 연동 (SPA + PKCE 아키텍처)")
                .category("Frontend Architecture")
                .summary("Public Client 환경의 브라우저에서 RFC 7636 PKCE를 적용하여 인가 코드를 안전하게 교환하고 메모리 격리로 토큰을 수명주기 관리하는 실무 패턴.")
                .overview("React, Vue 등 순수 브라우저 SPA는 Client Secret을 은닉할 수 없는 Public Client입니다.\nAuthorization Code Grant with PKCE(S256)를 적용하고, 토큰을 localStorage가 아닌 인메모리 클로저에만 보관해야 합니다.")
                .badges(List.of("RFC 7636", "S256 Hash", "In-Memory Tokens", "Public Client", "Zero Storage"))
                .readingTimeMinutes(9)
                .diagramNodes(List.of(
                        TopicDto.DiagramNodeDto.builder().id("spa").label("SPA App (React)").subLabel("In-Memory Storage").type("client").icon("Code").build(),
                        TopicDto.DiagramNodeDto.builder().id("browser").label("Web Browser Context").subLabel("User-Agent / DOM").type("client").icon("Globe").build(),
                        TopicDto.DiagramNodeDto.builder().id("iam").label("Keycloak OIDC").subLabel("Authorization Server").type("auth").icon("ShieldCheck").build(),
                        TopicDto.DiagramNodeDto.builder().id("api").label("Resource Server").subLabel("Backend APIs").type("server").icon("Server").build()
                ))
                .flowSteps(List.of(
                        TopicDto.FlowStepDto.builder().step(1).title("PKCE Key Pair(Verifier & Challenge) 생성").actor("SPA App (React)").target("Web Browser Context").protocol("Web Crypto API").action("난수 verifier 및 S256 challenge 생성").description("브라우저 내장 암호화 API로 일회용 증명 키 쌍을 계산합니다.").activeNodes(List.of("spa", "browser")).build(),
                        TopicDto.FlowStepDto.builder().step(2).title("Authorization Code 요청 (Redirect)").actor("Web Browser Context").target("Keycloak OIDC").protocol("HTTPS GET /auth").action("code_challenge 동봉 인가 요청").description("인증 성공 시 인가 코드가 redirect_uri로 전달됩니다.").activeNodes(List.of("browser", "iam")).build(),
                        TopicDto.FlowStepDto.builder().step(3).title("인가 코드 및 Verifier 토큰 교환").actor("SPA App (React)").target("Keycloak OIDC").protocol("HTTPS POST /token").action("원본 code_verifier와 코드 전송").description("Keycloak이 S256 해시를 검증하고 토큰을 발행합니다.").activeNodes(List.of("spa", "iam")).build(),
                        TopicDto.FlowStepDto.builder().step(4).title("인메모리 저장 및 Bearer API 호출").actor("SPA App (React)").target("Resource Server").protocol("HTTPS REST").action("메모리 변수의 토큰으로 Bearer 헤더 첨부").description("localStorage에 저장하지 않아 XSS 토큰 덤프를 원천 방어합니다.").activeNodes(List.of("spa", "api")).build()
                ))
                .keyTakeaways(List.of(
                        "PKCE(S256) 필수화: Public Client의 인가 코드 가로채기 완벽 차단",
                        "No localStorage 원칙: XSS 취약 지점인 웹 스토리지를 비우고 인메모리 격리",
                        "Refresh Token Rotation(RTR): 일회용 토큰 갱신 및 재사용 침해 감지"
                ))
                .tradeOffs(TopicDto.TradeOffsDto.builder()
                        .pros(List.of("별도 BFF 백엔드 없이 정적 웹 호스팅만으로 신속 배포 가능"))
                        .cons(List.of("새로고침 시 메모리 초기화로 인한 백그라운드 재인증 필요"))
                        .build())
                .codeSnippets(List.of(
                        TopicDto.CodeSnippetDto.builder().id("pkce-gen").title("Web Crypto S256 PKCE Generator").filename("pkceUtils.ts").language("typescript").code("// SubtleCrypto S256 Generator\nconst array = new Uint8Array(64);\nwindow.crypto.getRandomValues(array);").description("브라우저 표준 암호화 API를 사용한 PKCE 생성 코드입니다.").build()
                ))
                .securityChecklistIds(List.of("chk-xss-1", "chk-pkce-1"))
                .relatedTermIds(List.of("pkce", "client-types", "token-rotation", "xss"))
                .relatedTopicIds(List.of("bff-token-relay", "spa-vs-bff-comparison"))
                .build();
        archDeckService.saveTopic(spa);

        // 3. BFF Pattern
        TopicDto bff = TopicDto.builder()
                .id("bff-token-relay")
                .slug("bff-token-relay")
                .title("BFF Pattern (Backend for Frontend) & Token Relay")
                .koreanTitle("BFF 패턴 & Token Relay 아키텍처")
                .category("Backend & BFF")
                .summary("브라우저에는 HttpOnly 암호화 세션 쿠키만 허용하고, 백채널에서 JWT를 관리하여 마이크로서비스로 주입(Relay)하는 엔터프라이즈 최상위 보안 패턴.")
                .overview("BFF는 프론트엔드 전담 백엔드 계층으로서 브라우저를 Public Client의 위험에서 격리하고, BFF 서버 자체가 Confidential Client로서 인증 서버와 백채널 통신을 전담합니다.\n브라우저는 HttpOnly 쿠키만 보유하고, Access Token은 Redis 세션 스토어에 격리되어 Token Relay로 마이크로서비스에 주입됩니다.\n비상 시 Redis 세션 삭제로 즉시 세션을 만료시키는 Kill Switch를 제공합니다.")
                .badges(List.of("Confidential Client", "HttpOnly Cookie", "Token Relay", "Redis HA", "Kill Switch"))
                .readingTimeMinutes(11)
                .diagramNodes(List.of(
                        TopicDto.DiagramNodeDto.builder().id("browser").label("Client (SPA)").subLabel("No Tokens Held").type("client").icon("Layout").build(),
                        TopicDto.DiagramNodeDto.builder().id("bff").label("BFF Server (Spring/Node)").subLabel("Confidential Client").type("gateway").icon("Cpu").build(),
                        TopicDto.DiagramNodeDto.builder().id("redis").label("Session Store (Redis HA)").subLabel("Cluster / Sentinel").type("database").icon("Database").build(),
                        TopicDto.DiagramNodeDto.builder().id("iam").label("Keycloak (IAM)").subLabel("Back-Channel Auth").type("auth").icon("ShieldCheck").build(),
                        TopicDto.DiagramNodeDto.builder().id("services").label("Internal Microservices").subLabel("Stateless Bearer JWT").type("server").icon("Server").build()
                ))
                .flowSteps(List.of(
                        TopicDto.FlowStepDto.builder().step(1).title("BFF 백채널 인증 및 인가 코드 교환").actor("BFF Server (Spring/Node)").target("Keycloak (IAM)").protocol("HTTPS POST (Back-Channel)").action("Client Secret과 PKCE verifier로 백채널 교환").description("브라우저는 Client Secret을 절대 볼 수 없습니다.").activeNodes(List.of("bff", "iam")).build(),
                        TopicDto.FlowStepDto.builder().step(2).title("Redis 세션 저장 및 암호화 쿠키 발행").actor("BFF Server (Spring/Node)").target("Redis Session Store").protocol("RESP → Set-Cookie").action("토큰을 Redis에 저장하고 HttpOnly 세션 쿠키 발급").description("자바스크립트의 쿠키 접근이 100% 차단됩니다.").activeNodes(List.of("bff", "redis", "browser")).build(),
                        TopicDto.FlowStepDto.builder().step(3).title("세션 기반 API 호출 및 CSRF 검증").actor("Client (SPA)").target("BFF Server (Spring/Node)").protocol("HTTPS (Cookie + X-XSRF-TOKEN)").action("세션 쿠키와 CSRF 헤더를 동봉하여 호출").description("BFF가 CSRF 토큰을 검증하고 세션을 복원합니다.").activeNodes(List.of("browser", "bff", "redis")).build(),
                        TopicDto.FlowStepDto.builder().step(4).title("자동 토큰 릴레이 (Token Relay)").actor("BFF Server (Spring/Node)").target("Internal Microservices").protocol("Internal HTTPS (Bearer JWT)").action("Authorization 헤더에 원본 JWT 주입 후 프록시").description("마이크로서비스는 세션을 모른 채 순수 무상태 JWT 검증만 수행합니다.").activeNodes(List.of("bff", "services")).build(),
                        TopicDto.FlowStepDto.builder().step(5).title("킬 스위치 (Kill Switch) 즉시 세션 차단").actor("BFF Server (Spring/Node)").target("Redis Session Store").protocol("Redis DEL key").action("이상 징후 발생 시 Redis 세션을 즉각 파기").description("모든 후속 토큰 릴레이가 1초 내에 차단됩니다.").activeNodes(List.of("bff", "redis")).build()
                ))
                .keyTakeaways(List.of(
                        "Zero-Token-in-Browser: 브라우저에 토큰을 절대 주지 않음",
                        "XSS 방어 극대화: 자바스크립트가 토큰을 읽을 수 없음",
                        "Token Relay: 마이크로서비스는 표준 Bearer JWT 무상태성 유지",
                        "실시간 제어권 (Kill Switch): Redis 세션으로 실시간 취소 지원"
                ))
                .tradeOffs(TopicDto.TradeOffsDto.builder()
                        .pros(List.of("최고 수준의 클라이언트 보안", "원클릭 세션 만료 및 디바이스 제어", "ITP 이슈 원천 차단"))
                        .cons(List.of("BFF 전담 서버 및 Redis HA 인프라 운영 필요", "CSRF 방어책 추가 구현 필수"))
                        .build())
                .codeSnippets(List.of(
                        TopicDto.CodeSnippetDto.builder().id("spring-relay").title("Spring Cloud Gateway Token Relay").filename("application.yml").language("yaml").code("spring:\n  cloud:\n    gateway:\n      default-filters:\n        - TokenRelay=").description("Spring Cloud Gateway의 자동 Token Relay 필터 구성입니다.").build()
                ))
                .securityChecklistIds(List.of("chk-csrf-1", "chk-bff-1", "chk-bff-2"))
                .relatedTermIds(List.of("token-relay", "kill-switch", "back-channel", "client-types", "csrf"))
                .relatedTopicIds(List.of("spa-pkce-direct", "spa-vs-bff-comparison"))
                .build();
        archDeckService.saveTopic(bff);

        // 4. SPA vs BFF
        TopicDto comparison = TopicDto.builder()
                .id("spa-vs-bff-comparison")
                .slug("spa-vs-bff-comparison")
                .title("Architecture Deep-Dive: SPA + PKCE vs BFF Pattern")
                .koreanTitle("SPA + PKCE vs BFF 패턴 심층 비교 & 의사결정 가이드")
                .category("Frontend Architecture")
                .summary("클라이언트 유형, 토큰 보관 위치, 브라우저 인증 수단, XSS/CSRF 취약점, 인프라 운영 복잡도까지 실무 아키텍처 선택의 모든 기준을 나란히 비교합니다.")
                .overview("보안 등급(금융/개인정보 처리 vs 일반 B2C/사내 도구), 인프라 예산, 개발팀의 백엔드 운영 역량에 따른 합리적인 아키텍처 선택 기준을 확립해야 합니다.")
                .badges(List.of("Architecture Matrix", "Trade-off Analysis", "Decision Framework"))
                .readingTimeMinutes(8)
                .diagramNodes(List.of())
                .flowSteps(List.of())
                .keyTakeaways(List.of(
                        "보안 최우선 환경(금융, 핀테크, 의료): BFF 패턴 권장",
                        "경량/고속 개발 환경(스타트업 MVP): SPA + PKCE 권장"
                ))
                .tradeOffs(TopicDto.TradeOffsDto.builder().pros(List.of()).cons(List.of()).build())
                .codeSnippets(List.of())
                .securityChecklistIds(List.of("chk-xss-1", "chk-csrf-1"))
                .relatedTermIds(List.of("client-types", "pkce", "token-relay"))
                .relatedTopicIds(List.of("spa-pkce-direct", "bff-token-relay"))
                .build();
        archDeckService.saveTopic(comparison);

        // 5. XSS vs CSRF
        TopicDto xssCsrf = TopicDto.builder()
                .id("xss-vs-csrf-defense")
                .slug("xss-vs-csrf-defense")
                .title("XSS vs CSRF: Core Vulnerabilities & Enterprise Defense")
                .koreanTitle("XSS vs CSRF 차이 및 엔터프라이즈 실무 방어 전략")
                .category("Security & Defense")
                .summary("공격 원리와 목적의 근본적인 차이를 이해하고, 현대 프론트엔드 및 백엔드 계층에서 구축해야 할 계층적 심층 방어(Defense in Depth) 수칙을 정리합니다.")
                .overview("XSS는 클라이언트 자바스크립트 실행 권한을 탈취하여 데이터를 훔치는 공격이고, CSRF는 브라우저의 쿠키 자동 첨부 특성을 이용해 위조된 요청을 보내는 공격입니다.\nXSS는 이스케이프/CSP/토큰 격리로 막고, CSRF는 SameSite 쿠키와 CSRF 토큰 검증으로 방어합니다.")
                .badges(List.of("OWASP Top 10", "CSP Level 3", "SameSite Cookie", "Double Submit CSRF"))
                .readingTimeMinutes(10)
                .diagramNodes(List.of())
                .flowSteps(List.of())
                .keyTakeaways(List.of(
                        "XSS는 코드 실행 문제, CSRF는 요청 출처 검증 문제",
                        "XSS가 뚫리면 CSRF 방어도 무력화되므로 XSS 방어가 최우선"
                ))
                .tradeOffs(TopicDto.TradeOffsDto.builder().pros(List.of()).cons(List.of()).build())
                .codeSnippets(List.of())
                .securityChecklistIds(List.of("chk-xss-1", "chk-csrf-1"))
                .relatedTermIds(List.of("xss", "csrf"))
                .relatedTopicIds(List.of("bff-token-relay"))
                .build();
        archDeckService.saveTopic(xssCsrf);
    }

    private void seedGlossary() {
        List<GlossaryDto> list = List.of(
                GlossaryDto.builder().id("pkce").term("PKCE").koreanName("작업 코드 교환용 증명 키 (Proof Key for Code Exchange)").category("Protocol & Spec").shortDef("모바일 앱과 SPA 같은 Public Client에서 인가 코드 가로채기 공격을 방지하기 위한 OAuth 2.0 보안 확장 규격(RFC 7636).").fullDef("클라이언트가 code_verifier 난수를 만들고 code_challenge(S256)를 인증 서버에 보낸 뒤 토큰 교환 시 검증하는 표준입니다.").keyPoints(List.of("code_verifier: 43~128자 난수", "code_challenge: SHA-256 해시", "S256 필수")).specRef("RFC 7636").securityRelevance("Plain 방식은 공격자가 가로챌 수 있으므로 S256을 강제해야 합니다.").tags(List.of("OAuth2", "OIDC", "RFC7636")).build(),
                GlossaryDto.builder().id("csrf").term("CSRF").koreanName("사이트 간 요청 위조 (Cross-Site Request Forgery)").category("Security Attack & Defense").shortDef("사용자가 인증된 세션을 보유한 상태에서 공격자가 준비한 사이트를 통해 사용자의 권한으로 서버에 원치 않는 위조 요청을 전송하게 만드는 취약점.").fullDef("브라우저의 자동 쿠키 첨부 특성을 악용하는 공격입니다.").keyPoints(List.of("원인: Ambient Credential", "방어: SameSite, Double Submit CSRF 헤더")).specRef("OWASP Top 10").securityRelevance("BFF 도입 시 SameSite와 X-XSRF-TOKEN 검증이 필수입니다.").tags(List.of("OWASP", "Cookie", "SameSite")).build(),
                GlossaryDto.builder().id("xss").term("XSS").koreanName("크로스 사이트 스크립팅 (Cross-Site Scripting)").category("Security Attack & Defense").shortDef("검증되지 않은 사용자 입력값이 브라우저 내에서 악성 자바스크립트로 실행되어 세션 탈취, DOM 변조를 유발하는 취약점.").fullDef("희생자의 브라우저 컨텍스트 내에서 스크립트 실행 권한을 획득하는 공격입니다.").keyPoints(List.of("위협: localStorage 토큰 탈취", "방어: 자동 이스케이프, Strict CSP, 토큰 격리")).specRef("OWASP Top 10").securityRelevance("localStorage에 토큰을 두지 않고 인메모리 또는 HttpOnly 쿠키로 격리해야 합니다.").tags(List.of("OWASP", "XSS", "CSP")).build(),
                GlossaryDto.builder().id("jwks").term("JWKS").koreanName("JSON 웹 키 집합 (JSON Web Key Set)").category("Identity & Cryptography").shortDef("인증 서버가 JWT 서명에 사용하는 공개키(Public Key)들을 표준화된 JSON 형식으로 발행하는 엔드포인트 규격(RFC 7517).").fullDef("백엔드 리소스 서버들이 인증 서버와 통신 없이 로컬 캐시된 공개키로 무상태 고속 서명 검증을 수행합니다.").keyPoints(List.of("kid 대조", "무상태 고속 검증", "키 로테이션 지원")).specRef("RFC 7517").securityRelevance("미인식 kid 재조회 시 Rate Limit 및 백오프를 두어 DoS 공격을 방어합니다.").tags(List.of("JWT", "Crypto", "RS256")).build(),
                GlossaryDto.builder().id("token-relay").term("Token Relay").koreanName("토큰 릴레이 (Token Relay Pattern)").category("Architecture Pattern").shortDef("BFF나 게이트웨이가 클라이언트의 세션 쿠키를 받아 검증한 뒤 마이크로서비스 호출 시 실제 JWT Access Token을 헤더에 자동 주입하는 패턴.").fullDef("브라우저에는 쿠키만 허용하고 내부 서비스로는 표준 Bearer JWT를 전달하여 양측의 장점을 결합합니다.").keyPoints(List.of("클라이언트 토큰 노출 제로", "백채널 자동 갱신", "마이크로서비스 호환성")).specRef("RFC 8693").securityRelevance("토큰 유출 가능성을 서버 내부 VPC 안으로 완벽히 통제합니다.").tags(List.of("BFF", "Microservices", "Bearer")).build(),
                GlossaryDto.builder().id("back-channel").term("Back-Channel").koreanName("백채널 통신 (Back-Channel Communication)").category("Architecture Pattern").shortDef("브라우저를 거치지 않고 서버 대 서버 간에 직접 암호화된 채널을 통해 민감한 인증 정보를 교환하는 통신 방식.").fullDef("클라이언트 시크릿, 토큰 교환, 백채널 로그아웃 웹훅이 이 채널로 수행됩니다.").keyPoints(List.of("Server-to-Server 직접 통신", "Client Secret 보호")).specRef("RFC 6749").securityRelevance("BFF는 백채널을 보유하여 완벽한 기밀 클라이언트 보안을 구현합니다.").tags(List.of("Security", "TLS")).build(),
                GlossaryDto.builder().id("client-types").term("Public vs Confidential Client").koreanName("퍼블릭 클라이언트 vs 컨피덴셜 클라이언트").category("Protocol & Spec").shortDef("OAuth 2.0에서 클라이언트가 자격 증명(Client Secret)의 기밀성을 안전하게 보관할 수 있는 능력에 따른 분류.").fullDef("SPA/모바일은 Public Client이며, 백엔드 서버/BFF는 Confidential Client입니다.").keyPoints(List.of("Public: 시크릿 금지, PKCE 필수", "Confidential: 시크릿 은닉 가능")).specRef("RFC 6749").securityRelevance("프론트엔드 환경 변수에 시크릿을 하드코딩해서는 안 됩니다.").tags(List.of("OAuth2", "Security")).build(),
                GlossaryDto.builder().id("token-rotation").term("Refresh Token Rotation").koreanName("리프레시 토큰 로테이션 (RTR)").category("Protocol & Spec").shortDef("Refresh Token 사용 시마다 매번 새 Refresh Token을 발급하고 구버전 토큰 재사용 시 모든 연계 세션을 파기하는 일회용 갱신 메커니즘.").fullDef("토큰 도난 시 공격자의 지속적 세션 유지를 감지하고 차단합니다.").keyPoints(List.of("일회용 갱신", "토큰 재사용 침해 감지", "가족 세션 무효화")).specRef("OAuth 2.0 BCP").securityRelevance("정적 토큰 대비 침해 확산을 획기적으로 줄입니다.").tags(List.of("OAuth2", "Security")).build(),
                GlossaryDto.builder().id("stateless-infinispan").term("Stateless & Infinispan").koreanName("무상태성 및 인피니스팬 클러스터링").category("Architecture Pattern").shortDef("백엔드는 JWT로 무상태 수평 확장하고, Keycloak은 Infinispan 분산 인메모리 그리드로 세션 동기화와 HA를 보장하는 아키텍처.").fullDef("Resource Server의 무상태 검증과 중앙 IAM의 상태 보존 분산 캐시가 조화를 이룹니다.").keyPoints(List.of("Stateless API 확장", "Infinispan 분산 캐시 클러스터링")).specRef("Keycloak HA Guide").securityRelevance("인증 서버 단일 장애점 방지를 위해 멀티 노드 HA가 필수입니다.").tags(List.of("Keycloak", "Infinispan", "Clustering")).build(),
                GlossaryDto.builder().id("kill-switch").term("Kill Switch").koreanName("즉시 세션 만료 킬 스위치 (Instant Session Invalidation)").category("Security Attack & Defense").shortDef("보안 침해 시 분산된 모든 디바이스의 활성 로그인 세션을 1초 이내에 즉각 강제 만료시키는 비상 제어 기능.").fullDef("BFF 세션 스토어(Redis)의 세션 키를 즉시 삭제하여 무상태 JWT의 즉각 취소 불가능 문제를 완벽 해결합니다.").keyPoints(List.of("Redis O(1) 원격 파기", "긴급 보안 통제", "Back-Channel Logout 연동")).specRef("NIST SP 800-63B").securityRelevance("금융 및 엔터프라이즈 보안 컴플라이언스 필수 요건입니다.").tags(List.of("Security", "Redis", "Session")).build()
        );
        list.forEach(archDeckService::saveGlossary);
    }

    private void seedComparisons() {
        ComparisonDto spaVsBff = ComparisonDto.builder()
                .id("spa-vs-bff")
                .title("SPA + PKCE 직접 연동 vs BFF (Backend for Frontend) 패턴")
                .subtitle("보안 등급, 토큰 노출도, 인프라 비용에 따른 아키텍처 의사결정 매트릭스")
                .category("Architecture Comparison")
                .subjects(List.of(
                        ComparisonDto.SubjectDto.builder().id("spa-pkce").name("SPA + PKCE 직접 연동").tag("Public Client").iconName("Layout").recommendedFor("신속한 MVP 개발, 정적 호스팅").description("브라우저에서 직접 PKCE로 토큰 교환").build(),
                        ComparisonDto.SubjectDto.builder().id("bff-pattern").name("BFF 패턴 + Token Relay").tag("Confidential Client (Recommended)").iconName("ShieldCheck").recommendedFor("금융/핀테크, 개인정보 취급 시스템").description("BFF가 백채널 토큰 교환 및 주입").build()
                ))
                .dimensions(List.of(
                        ComparisonDto.DimensionDto.builder().key("token-location").name("토큰 보관 위치").values(Map.of(
                                "spa-pkce", ComparisonDto.DimensionValueDto.builder().text("브라우저 JS 메모리").status("warning").detail("JS 변수/클로저에 보관").build(),
                                "bff-pattern", ComparisonDto.DimensionValueDto.builder().text("서버 측 Redis 세션").status("positive").detail("서버의 고속 인메모리 DB에만 격리").build()
                        )).build(),
                        ComparisonDto.DimensionDto.builder().key("xss-vulnerability").name("XSS 토큰 탈취 위험").values(Map.of(
                                "spa-pkce", ComparisonDto.DimensionValueDto.builder().text("위험 (메모리 덤프 가능)").status("negative").detail("악성 스크립트가 토큰을 외부로 전송 가능").build(),
                                "bff-pattern", ComparisonDto.DimensionValueDto.builder().text("안전 (토큰 탈취 불가)").status("positive").detail("토큰 자체가 브라우저에 도달하지 않음").build()
                        )).build()
                ))
                .decisionGuide(List.of(
                        ComparisonDto.DecisionGuideRuleDto.builder().condition("금융/보안 규제 및 즉시 세션 취소 필수").recommendation("BFF 패턴 필수 채택").reason("브라우저 토큰 노출 금지 규격 충족 및 Redis O(1) Kill Switch 가능").build()
                ))
                .build();
        archDeckService.saveComparison(spaVsBff);
    }

    private void seedChecklists() {
        List<ChecklistDto> list = List.of(
                ChecklistDto.builder().id("chk-xss-1").category("XSS Defense").title("Access/Refresh Token의 localStorage 저장 완전 금지").description("브라우저 로컬 스토리지에 토큰을 저장하면 XSS 발생 시 즉각 전체 탈취됩니다.").priority("CRITICAL").verificationMethod("Application > Local Storage 확인").codeExample("// 권장 (인메모리 또는 HttpOnly 쿠키)\nauthStore.setTokenInMemory(token);").build(),
                ChecklistDto.builder().id("chk-csrf-1").category("CSRF Defense").title("모든 인증/세션 쿠키에 SameSite 속성 및 __Host- 접두사 적용").description("SameSite=Lax/Strict와 Secure, HttpOnly를 강제합니다.").priority("CRITICAL").verificationMethod("Set-Cookie 응답 헤더 점검").codeExample("res.cookie('__Host-ArchSession', sessionId, { httpOnly: true, secure: true, sameSite: 'lax' });").build(),
                ChecklistDto.builder().id("chk-iam-1").category("Infra & IAM").title("백엔드 마이크로서비스의 JWKS 공개키 로컬 캐싱 및 서명 무상태 검증").description("인증 서버 호출 없이 로컬 JWKS 캐시로 RS256 무상태 검증을 수행합니다.").priority("HIGH").verificationMethod("네트워크 분리 테스트").codeExample("jwksClient({ jwksUri: '.../certs', cache: true });").build()
        );
        list.forEach(archDeckService::saveChecklist);
    }

    private void seedUserLearning() {
        UserLearningDto initial = UserLearningDto.builder()
                .bookmarks(List.of("bff-token-relay"))
                .completedTopics(List.of("keycloak-iam-overview"))
                .notes(Map.of("bff-token-relay", "Spring Boot + Spring Cloud Gateway 환경에서 Token Relay 필터와 Redis 세션 관리를 우선 검토할 것."))
                .checklistProgress(Map.of("chk-xss-1", true))
                .build();
        archDeckService.saveUserLearning(initial);
    }
}
