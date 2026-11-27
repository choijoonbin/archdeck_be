import { Topic, ComparisonMatrix, GlossaryTerm, ChecklistItem, UserLearningState } from './types/index.js';

export const initialTopics: Topic[] = [
  {
    id: 'keycloak-iam-overview',
    slug: 'keycloak-iam-overview',
    title: 'Keycloak & Open Source IAM Overview',
    koreanTitle: 'Keycloak & 오픈소스 IAM 아키텍처 개요',
    category: 'IAM & Auth',
    summary: '엔터프라이즈 환경에서 통합 인증·인가(SSO, OIDC, RBAC)를 비즈니스 로직과 물리적으로 분리하는 이유와 글로벌 오픈소스 IAM 생태계를 조망합니다.',
    overview: `현대적인 웹/모바일 아키텍처에서 인증(Authentication)과 인가(Authorization)를 각 비즈니스 백엔드나 프론트엔드에 직접 구현하는 것은 심각한 안티패턴입니다.
비밀번호 해싱, 다중 인증(MFA), 소셜 로그인, 토큰 발행, 세션 관리, 보안 감사 로그 등의 복잡한 인증 로직을 중앙 집중화된 독립 IAM(Identity and Access Management) 솔루션에 위임함으로써 '관심사의 분리(Separation of Concerns)'와 '제로 트러스트(Zero Trust)' 보안을 달성할 수 있습니다.
Keycloak은 Red Hat 주도의 CNCF 생태계 표준 오픈소스 IAM으로서 OIDC/OAuth2/SAML 2.0 지원, 세밀한 역할 기반 접근 제어(RBAC), LDAP/Active Directory 연동, 내장 Infinispan 분산 세션 클러스터링을 기본 제공하여 글로벌 엔터프라이즈의 표준으로 자리잡았습니다.`,
    badges: ['Enterprise IAM', 'OIDC & OAuth 2.0', 'RBAC & ABAC', 'SSO', 'Keycloak 25+'],
    readingTimeMinutes: 7,
    diagramNodes: [
      { id: 'user', label: 'End User / Browser', subLabel: 'Client Agent', type: 'client', icon: 'User' },
      { id: 'app', label: 'Frontend (SPA/Web)', subLabel: 'React / Next.js', type: 'client', icon: 'Layout' },
      { id: 'iam', label: 'Keycloak IAM Cluster', subLabel: 'OIDC / Infinispan', type: 'auth', icon: 'ShieldCheck' },
      { id: 'gateway', label: 'API Gateway / BFF', subLabel: 'Envoy / Spring Cloud', type: 'gateway', icon: 'Network' },
      { id: 'services', label: 'Core Microservices', subLabel: 'Stateless APIs', type: 'server', icon: 'Server' },
      { id: 'user_db', label: 'IAM Database / LDAP', subLabel: 'PostgreSQL / AD', type: 'database', icon: 'Database' }
    ],
    flowSteps: [
      {
        step: 1,
        title: '인증 요청 및 SSO 리다이렉트',
        actor: 'User/Browser',
        target: 'Keycloak (IAM)',
        protocol: 'HTTPS GET (Front-Channel)',
        action: '사용자가 로그인을 시도하면 앱은 브라우저를 Keycloak 인증 엔드포인트(/auth)로 리다이렉트합니다.',
        description: '클라이언트 애플리케이션은 사용자의 비밀번호를 절대 직접 수집하지 않습니다. 브라우저가 직접 Keycloak의 보안 로그인 페이지로 이동하여 안전한 환경에서 인증이 수행됩니다.',
        activeNodes: ['user', 'app', 'iam']
      },
      {
        step: 2,
        title: '사용자 자격 증명 검증 및 MFA',
        actor: 'Keycloak (IAM)',
        target: 'IAM Database / LDAP',
        protocol: 'Internal DB Query / LDAP Bind',
        action: 'Keycloak이 패스워드 해시(Argon2/PBKDF2) 및 TOTP MFA를 검증하고 중앙 SSO 세션을 생성합니다.',
        description: '성공 시 Infinispan 분산 인메모리 캐시에 SSO 세션을 등록하고, 일회용 Authorization Code(인가 코드)를 발행합니다.',
        activeNodes: ['iam', 'user_db']
      },
      {
        step: 3,
        title: '인가 코드 반환 및 토큰 발급',
        actor: 'Keycloak (IAM)',
        target: 'Frontend (SPA/Web)',
        protocol: 'HTTPS 302 Redirect → POST /token',
        action: '사용자 브라우저를 앱의 Callback URL로 리다이렉트하고, 인가 코드를 토큰으로 교환합니다.',
        description: '인증 서버는 서명된 JWT(Access Token, ID Token) 및 Refresh Token을 발행하여 클라이언트에 안전하게 전달합니다.',
        activeNodes: ['iam', 'app']
      },
      {
        step: 4,
        title: 'JWKS 기반 무상태(Stateless) API 인가',
        actor: 'Frontend (SPA/Web)',
        target: 'Core Microservices',
        protocol: 'HTTPS REST (Bearer JWT)',
        action: '클라이언트가 Authorization: Bearer <JWT> 헤더를 첨부하여 마이크로서비스를 호출합니다.',
        description: '마이크로서비스들은 Keycloak을 매번 호출하지 않고, 사전에 캐시된 Keycloak의 JWKS 공개키로 JWT의 유효성과 Role을 로컬에서 즉시 검증합니다.',
        activeNodes: ['app', 'gateway', 'services']
      }
    ],
    keyTakeaways: [
      '관심사의 분리: 비즈니스 도메인 서비스는 비즈니스 로직에만 집중하고, 인증/계정 관리는 전담 IAM에 완전 위임',
      '표준 프로토콜 수용: OIDC(인증) 및 OAuth 2.0(인가) 표준 채택으로 멀티 플랫폼(Web, Mobile, B2B API) 확장 용이',
      '단일 장애점(SPOF) 극복: 백엔드는 JWKS 기반 무상태 JWT 검증을 수행하고, Keycloak은 Infinispan 클러스터로 HA 구성',
      '글로벌 대안 비교: 전통의 Keycloak(Java/엔터프라이즈), 경량의 authentik(Python/모던 UI), 클라우드 네이티브 ZITADEL(Go), 모듈형 Ory(Go/Kratos)'
    ],
    tradeOffs: {
      pros: [
        'SSO(Single Sign-On)로 전사 서비스 간 매끄러운 단일 로그인 경험 제공',
        '엔터프라이즈급 보안 표준(MFA, Passkey, WebAuthn, OAuth 2.1) 즉시 도입 가능',
        '백엔드 마이크로서비스 간 일관된 JWT Claims 기반 인가 모델 수립'
      ],
      cons: [
        '별도의 고가용성 인프라(JVM 튜닝, Infinispan 클러스터, DB 이중화) 운영 복잡도 수반',
        '초기 러닝 커브: Realm, Client, Scope, Mapper, Flow 등 Keycloak 개념 습득 필요'
      ]
    },
    codeSnippets: [
      {
        id: 'keycloak-docker',
        title: 'Keycloak 25+ 프로덕션 최적화 Docker Compose',
        filename: 'docker-compose.iam.yml',
        language: 'yaml',
        description: 'PostgreSQL을 스토리지로 사용하고 헬스체크와 최적화 옵션이 적용된 독립 Keycloak 구성입니다.',
        code: `version: '3.8'\nservices:\n  keycloak:\n    image: quay.io/keycloak/keycloak:25.0\n    command: start --optimized`
      }
    ],
    securityChecklistIds: ['chk-iam-1'],
    relatedTermIds: ['jwks', 'stateless-infinispan', 'back-channel', 'client-types'],
    relatedTopicIds: ['spa-pkce-direct', 'bff-token-relay']
  },
  {
    id: 'spa-pkce-direct',
    slug: 'spa-pkce-direct',
    title: 'Frontend Direct OIDC Integration (SPA + PKCE)',
    koreanTitle: '프론트엔드 직접 OIDC 연동 (SPA + PKCE 아키텍처)',
    category: 'Frontend Architecture',
    summary: 'Public Client 환경의 브라우저에서 RFC 7636 PKCE를 적용하여 인가 코드를 안전하게 교환하고 메모리 격리로 토큰을 수명주기 관리하는 실무 패턴.',
    overview: `React, Vue 등 순수 브라우저 SPA(Single Page Application)는 백엔드 시크릿을 안전하게 저장할 수 없는 'Public Client'입니다.
브라우저는 암호학적 랜덤 난수인 code_verifier를 생성하고 이를 SHA-256 해시한 code_challenge를 인증 서버에 보냅니다.
무엇보다 가장 중요한 실무 원칙은 발급받은 Access Token과 Refresh Token을 절대로 localStorage나 sessionStorage에 저장하지 않고 '자바스크립트 인메모리'에만 보관하는 것입니다.`,
    badges: ['RFC 7636', 'S256 Hash', 'In-Memory Tokens', 'Public Client', 'Zero Storage'],
    readingTimeMinutes: 9,
    diagramNodes: [
      { id: 'spa', label: 'SPA App (React)', subLabel: 'In-Memory Storage', type: 'client', icon: 'Code' },
      { id: 'browser', label: 'Web Browser Context', subLabel: 'User-Agent / DOM', type: 'client', icon: 'Globe' },
      { id: 'iam', label: 'Keycloak OIDC', subLabel: 'Authorization Server', type: 'auth', icon: 'ShieldCheck' },
      { id: 'api', label: 'Resource Server', subLabel: 'Backend APIs', type: 'server', icon: 'Server' }
    ],
    flowSteps: [
      {
        step: 1,
        title: 'PKCE Key Pair 생성',
        actor: 'SPA App (React)',
        target: 'Web Browser Context',
        protocol: 'Web Crypto API',
        action: 'code_verifier 난수 및 code_challenge(S256) 생성',
        description: '브라우저 내장 암호화 API로 일회용 증명 키 쌍을 계산합니다.',
        activeNodes: ['spa', 'browser']
      },
      {
        step: 2,
        title: 'Authorization Code 요청',
        actor: 'Web Browser Context',
        target: 'Keycloak OIDC',
        protocol: 'HTTPS GET /auth',
        action: 'code_challenge와 함께 인가 요청 리다이렉트',
        description: '인증 성공 시 일회용 인가 코드가 redirect_uri로 전달됩니다.',
        activeNodes: ['browser', 'iam']
      },
      {
        step: 3,
        title: '토큰 교환 (Code + Verifier)',
        actor: 'SPA App (React)',
        target: 'Keycloak OIDC',
        protocol: 'HTTPS POST /token',
        action: '원본 code_verifier와 코드를 전송하여 토큰 획득',
        description: 'Keycloak이 해시 대조 후 JWT Access/Refresh Token을 발행합니다.',
        activeNodes: ['spa', 'iam']
      },
      {
        step: 4,
        title: '인메모리 저장 및 Bearer 호출',
        actor: 'SPA App (React)',
        target: 'Resource Server',
        protocol: 'HTTPS REST',
        action: '메모리 변수에 보관된 토큰을 Bearer 헤더에 첨부',
        description: 'localStorage에 영구 저장하지 않아 XSS 토큰 덤프를 원천 차단합니다.',
        activeNodes: ['spa', 'api']
      }
    ],
    keyTakeaways: [
      'PKCE(S256) 필수화: 인가 코드 가로채기 완벽 차단',
      'No localStorage 원칙: 인메모리 격리 저장소 구축',
      'Refresh Token Rotation(RTR): 일회용 토큰 갱신 및 재사용 감지'
    ],
    tradeOffs: {
      pros: ['별도 BFF 백엔드 없이 정적 웹 호스팅만으로 신속 배포 가능'],
      cons: ['새로고침 시 메모리 초기화로 인한 백그라운드 재인증 필요']
    },
    codeSnippets: [],
    securityChecklistIds: ['chk-xss-1'],
    relatedTermIds: ['pkce', 'client-types', 'token-rotation', 'xss'],
    relatedTopicIds: ['bff-token-relay']
  },
  {
    id: 'bff-token-relay',
    slug: 'bff-token-relay',
    title: 'BFF Pattern (Backend for Frontend) & Token Relay',
    koreanTitle: 'BFF 패턴 & Token Relay 아키텍처',
    category: 'Backend & BFF',
    summary: '브라우저에는 HttpOnly 암호화 세션 쿠키만 허용하고, 백채널에서 JWT를 관리하여 마이크로서비스로 주입(Relay)하는 엔터프라이즈 최상위 보안 패턴.',
    overview: `BFF 패턴은 프론트엔드와 마이크로서비스 사이에 전담 백엔드 계층을 두어 브라우저를 'Public Client'의 위험에서 격리하고, BFF 서버 자체가 'Confidential Client'로서 인증 서버와 백채널 통신을 전담하는 아키텍처입니다.
브라우저는 오직 안전한 HttpOnly, Secure, SameSite=Lax 쿠키만을 주고받으며, Access Token은 Redis 세션 스토어에만 격리 보관됩니다.
BFF는 프록시 시 Authorization: Bearer <JWT>를 자동 주입(Token Relay)하며, 비상 시 Redis 세션 키를 즉시 삭제하여 모든 디바이스의 세션을 동결하는 'Kill Switch'를 제공합니다.`,
    badges: ['Confidential Client', 'HttpOnly Cookie', 'Token Relay', 'Redis HA', 'Kill Switch'],
    readingTimeMinutes: 11,
    diagramNodes: [
      { id: 'browser', label: 'Client (SPA)', subLabel: 'No Tokens Held', type: 'client', icon: 'Layout' },
      { id: 'bff', label: 'BFF Server (Node/Go/Java)', subLabel: 'Confidential Client', type: 'gateway', icon: 'Cpu' },
      { id: 'redis', label: 'Session Store (Redis HA)', subLabel: 'Cluster / Sentinel', type: 'database', icon: 'Database' },
      { id: 'iam', label: 'Keycloak (IAM)', subLabel: 'Back-Channel Auth', type: 'auth', icon: 'ShieldCheck' },
      { id: 'services', label: 'Internal Microservices', subLabel: 'Stateless Bearer JWT', type: 'server', icon: 'Server' }
    ],
    flowSteps: [
      {
        step: 1,
        title: 'BFF 백채널 인증 및 인가 코드 교환',
        actor: 'BFF Server (Node/Go/Java)',
        target: 'Keycloak (IAM)',
        protocol: 'HTTPS POST /token (Back-Channel)',
        action: 'Client Secret과 PKCE verifier로 백채널 상호 인증 토큰 교환',
        description: '브라우저는 Client Secret을 절대 볼 수 없습니다.',
        activeNodes: ['bff', 'iam']
      },
      {
        step: 2,
        title: 'Redis 세션 저장 및 암호화 쿠키 발행',
        actor: 'BFF Server (Node/Go/Java)',
        target: 'Redis Session Store',
        protocol: 'TCP RESP → Set-Cookie',
        action: '토큰을 Redis에 저장하고 HttpOnly 세션 쿠키 발급',
        description: '자바스크립트의 쿠키 접근이 100% 차단됩니다.',
        activeNodes: ['bff', 'redis', 'browser']
      },
      {
        step: 3,
        title: '세션 기반 API 호출 및 CSRF 검증',
        actor: 'Client (SPA)',
        target: 'BFF Server (Node/Go/Java)',
        protocol: 'HTTPS (Session Cookie + X-XSRF-TOKEN)',
        action: '세션 쿠키와 CSRF 커스텀 헤더를 동봉하여 호출',
        description: 'BFF가 CSRF 토큰을 검증하고 세션을 복원합니다.',
        activeNodes: ['browser', 'bff', 'redis']
      },
      {
        step: 4,
        title: '자동 토큰 릴레이 (Token Relay)',
        actor: 'BFF Server (Node/Go/Java)',
        target: 'Internal Microservices',
        protocol: 'Internal HTTPS (Bearer JWT)',
        action: 'Authorization 헤더에 원본 JWT 주입 후 프록시',
        description: '마이크로서비스는 세션을 모른 채 순수 무상태 JWT 검증만 수행합니다.',
        activeNodes: ['bff', 'services']
      },
      {
        step: 5,
        title: '킬 스위치 (Kill Switch) 즉시 세션 차단',
        actor: 'BFF Server (Node/Go/Java)',
        target: 'Redis Session Store',
        protocol: 'Redis DEL key',
        action: '이상 징후 발생 시 Redis 세션을 즉각 파기',
        description: '모든 후속 토큰 릴레이가 1초 내에 차단됩니다.',
        activeNodes: ['bff', 'redis']
      }
    ],
    keyTakeaways: [
      'Zero-Token-in-Browser: 브라우저에 토큰을 절대 주지 않음',
      'XSS 방어 극대화: 토큰 직접 유출 원천 차단',
      '실시간 제어권(Kill Switch): Redis 세션으로 실시간 취소 지원'
    ],
    tradeOffs: {
      pros: ['최고 수준의 클라이언트 보안', '원클릭 세션 만료 및 디바이스 제어'],
      cons: ['BFF 전담 서버 및 Redis HA 인프라 운영 필요']
    },
    codeSnippets: [],
    securityChecklistIds: ['chk-csrf-1'],
    relatedTermIds: ['token-relay', 'kill-switch', 'back-channel'],
    relatedTopicIds: ['spa-pkce-direct']
  },
  {
    id: 'spa-vs-bff-comparison',
    slug: 'spa-vs-bff-comparison',
    title: 'Architecture Deep-Dive: SPA + PKCE vs BFF Pattern',
    koreanTitle: 'SPA + PKCE vs BFF 패턴 심층 비교 & 의사결정 가이드',
    category: 'Frontend Architecture',
    summary: '클라이언트 유형, 토큰 보관 위치, 브라우저 인증 수단, XSS/CSRF 취약점, 인프라 운영 복잡도까지 실무 아키텍처 선택의 모든 기준을 나란히 비교합니다.',
    overview: `프론트엔드 보안 아키텍처 수립 시 가장 빈번하게 격돌하는 주제는 '순수 프론트엔드 직접 OIDC(SPA + PKCE)'인가, 아니면 '전담 백엔드 프록시 계층(BFF + Token Relay)'인가입니다.
시스템의 보안 등급(금융/개인정보 처리 vs 일반 B2C/사내 도구), 인프라 예산, 개발팀의 백엔드 운영 역량에 따른 합리적인 아키텍처 선택 기준을 확립해야 합니다.`,
    badges: ['Architecture Matrix', 'Trade-off Analysis', 'Decision Framework'],
    readingTimeMinutes: 8,
    diagramNodes: [],
    flowSteps: [],
    keyTakeaways: [
      '보안 최우선 환경(금융, 의료): BFF 패턴 권장',
      '경량/고속 개발 환경: SPA + PKCE 권장'
    ],
    tradeOffs: { pros: [], cons: [] },
    codeSnippets: [],
    securityChecklistIds: ['chk-xss-1', 'chk-csrf-1'],
    relatedTermIds: ['client-types', 'pkce', 'token-relay'],
    relatedTopicIds: ['spa-pkce-direct', 'bff-token-relay']
  },
  {
    id: 'xss-vs-csrf-defense',
    slug: 'xss-vs-csrf-defense',
    title: 'XSS vs CSRF: Core Vulnerabilities & Enterprise Defense',
    koreanTitle: 'XSS vs CSRF 차이 및 엔터프라이즈 실무 방어 전략',
    category: 'Security & Defense',
    summary: '공격 원리와 목적의 근본적인 차이를 이해하고, 현대 프론트엔드 및 백엔드 계층에서 구축해야 할 계층적 심층 방어(Defense in Depth) 수칙을 정리합니다.',
    overview: `XSS는 클라이언트 자바스크립트 실행 권한을 탈취하여 데이터를 훔치는 공격이고, CSRF는 브라우저의 쿠키 자동 첨부 특성을 이용해 위조된 요청을 보내는 공격입니다.
XSS는 이스케이프/CSP/토큰 격리로 막고, CSRF는 SameSite 쿠키와 CSRF 토큰(X-XSRF-TOKEN) 검증으로 방어합니다.`,
    badges: ['OWASP Top 10', 'CSP Level 3', 'SameSite Cookie', 'Double Submit CSRF'],
    readingTimeMinutes: 10,
    diagramNodes: [],
    flowSteps: [],
    keyTakeaways: [
      'XSS는 코드 실행 문제, CSRF는 요청 출처 검증 문제',
      'XSS가 뚫리면 CSRF 방어도 무력화되므로 XSS 방어가 최우선'
    ],
    tradeOffs: { pros: [], cons: [] },
    codeSnippets: [],
    securityChecklistIds: ['chk-xss-1', 'chk-csrf-1'],
    relatedTermIds: ['xss', 'csrf'],
    relatedTopicIds: ['bff-token-relay']
  }
];

export const initialGlossaryTerms: GlossaryTerm[] = [
  {
    id: 'pkce',
    term: 'PKCE',
    koreanName: '작업 코드 교환용 증명 키 (Proof Key for Code Exchange)',
    category: 'Protocol & Spec',
    shortDef: '모바일 앱과 SPA 같은 Public Client에서 인가 코드 가로채기 공격을 방지하기 위한 OAuth 2.0 보안 확장 규격(RFC 7636).',
    fullDef: '클라이언트가 code_verifier 난수를 만들고 code_challenge(S256)를 인증 서버에 보낸 뒤 토큰 교환 시 검증하는 표준입니다.',
    keyPoints: ['code_verifier: 43~128자 난수', 'code_challenge: SHA-256 해시', 'S256 필수'],
    specRef: 'RFC 7636',
    securityRelevance: 'Plain 방식은 공격자가 가로챌 수 있으므로 S256을 강제해야 합니다.',
    tags: ['OAuth2', 'OIDC', 'RFC7636']
  },
  {
    id: 'csrf',
    term: 'CSRF',
    koreanName: '사이트 간 요청 위조 (Cross-Site Request Forgery)',
    category: 'Security Attack & Defense',
    shortDef: '사용자가 인증된 세션을 보유한 상태에서 공격자가 준비한 사이트를 통해 사용자의 권한으로 서버에 원치 않는 위조 요청을 전송하게 만드는 취약점.',
    fullDef: '브라우저의 자동 쿠키 첨부 특성을 악용하는 공격입니다.',
    keyPoints: ['원인: Ambient Credential', '방어: SameSite, Double Submit CSRF 헤더'],
    specRef: 'OWASP Top 10',
    securityRelevance: 'BFF 도입 시 SameSite와 X-XSRF-TOKEN 검증이 필수입니다.',
    tags: ['OWASP', 'Cookie', 'SameSite']
  },
  {
    id: 'xss',
    term: 'XSS',
    koreanName: '크로스 사이트 스크립팅 (Cross-Site Scripting)',
    category: 'Security Attack & Defense',
    shortDef: '검증되지 않은 사용자 입력값이 브라우저 내에서 악성 자바스크립트로 실행되어 세션 탈취, DOM 변조를 유발하는 취약점.',
    fullDef: '희생자의 브라우저 컨텍스트 내에서 스크립트 실행 권한을 획득하는 공격입니다.',
    keyPoints: ['위협: localStorage 토큰 탈취', '방어: 자동 이스케이프, Strict CSP, 토큰 격리'],
    specRef: 'OWASP Top 10',
    securityRelevance: 'localStorage에 토큰을 두지 않고 인메모리 또는 HttpOnly 쿠키로 격리해야 합니다.',
    tags: ['OWASP', 'XSS', 'CSP']
  },
  {
    id: 'jwks',
    term: 'JWKS',
    koreanName: 'JSON 웹 키 집합 (JSON Web Key Set)',
    category: 'Identity & Cryptography',
    shortDef: '인증 서버가 JWT 서명에 사용하는 공개키(Public Key)들을 표준화된 JSON 형식으로 발행하는 엔드포인트 규격(RFC 7517).',
    fullDef: '백엔드 리소스 서버들이 인증 서버와 통신 없이 로컬 캐시된 공개키로 무상태 고속 서명 검증을 수행합니다.',
    keyPoints: ['kid 대조', '무상태 고속 검증', '키 로테이션 지원'],
    specRef: 'RFC 7517',
    securityRelevance: '미인식 kid 재조회 시 Rate Limit 및 백오프를 두어 DoS 공격을 방어합니다.',
    tags: ['JWT', 'Crypto', 'RS256']
  },
  {
    id: 'token-relay',
    term: 'Token Relay',
    koreanName: '토큰 릴레이 (Token Relay Pattern)',
    category: 'Architecture Pattern',
    shortDef: 'BFF나 게이트웨이가 클라이언트의 세션 쿠키를 받아 검증한 뒤 마이크로서비스 호출 시 실제 JWT Access Token을 헤더에 자동 주입하는 패턴.',
    fullDef: '브라우저에는 쿠키만 허용하고 내부 서비스로는 표준 Bearer JWT를 전달하여 양측의 장점을 결합합니다.',
    keyPoints: ['클라이언트 토큰 노출 제로', '백채널 자동 갱신', '마이크로서비스 호환성'],
    specRef: 'RFC 8693',
    securityRelevance: '토큰 유출 가능성을 서버 내부 VPC 안으로 완벽히 통제합니다.',
    tags: ['BFF', 'Microservices', 'Bearer']
  },
  {
    id: 'back-channel',
    term: 'Back-Channel',
    koreanName: '백채널 통신 (Back-Channel Communication)',
    category: 'Architecture Pattern',
    shortDef: '브라우저를 거치지 않고 서버 대 서버 간에 직접 암호화된 채널을 통해 민감한 인증 정보를 교환하는 통신 방식.',
    fullDef: '클라이언트 시크릿, 토큰 교환, 백채널 로그아웃 웹훅이 이 채널로 수행됩니다.',
    keyPoints: ['Server-to-Server 직접 통신', 'Client Secret 보호'],
    specRef: 'RFC 6749',
    securityRelevance: 'BFF는 백채널을 보유하여 완벽한 기밀 클라이언트 보안을 구현합니다.',
    tags: ['Security', 'TLS']
  },
  {
    id: 'client-types',
    term: 'Public vs Confidential Client',
    koreanName: '퍼블릭 클라이언트 vs 컨피덴셜 클라이언트',
    category: 'Protocol & Spec',
    shortDef: 'OAuth 2.0에서 클라이언트가 자격 증명(Client Secret)의 기밀성을 안전하게 보관할 수 있는 능력에 따른 분류.',
    fullDef: 'SPA/모바일은 Public Client이며, 백엔드 서버/BFF는 Confidential Client입니다.',
    keyPoints: ['Public: 시크릿 금지, PKCE 필수', 'Confidential: 시크릿 은닉 가능'],
    specRef: 'RFC 6749',
    securityRelevance: '프론트엔드 환경 변수에 시크릿을 하드코딩해서는 안 됩니다.',
    tags: ['OAuth2', 'Security']
  },
  {
    id: 'token-rotation',
    term: 'Refresh Token Rotation',
    koreanName: '리프레시 토큰 로테이션 (RTR)',
    category: 'Protocol & Spec',
    shortDef: 'Refresh Token 사용 시마다 매번 새 Refresh Token을 발급하고 구버전 토큰 재사용 시 모든 연계 세션을 파기하는 일회용 갱신 메커니즘.',
    fullDef: '토큰 도난 시 공격자의 지속적 세션 유지를 감지하고 차단합니다.',
    keyPoints: ['일회용 갱신', '토큰 재사용 침해 감지', '가족 세션 무효화'],
    specRef: 'OAuth 2.0 BCP',
    securityRelevance: '정적 토큰 대비 침해 확산을 획기적으로 줄입니다.',
    tags: ['OAuth2', 'Security']
  },
  {
    id: 'stateless-infinispan',
    term: 'Stateless & Infinispan',
    koreanName: '무상태성 및 인피니스팬 클러스터링',
    category: 'Architecture Pattern',
    shortDef: '백엔드는 JWT로 무상태 수평 확장하고, Keycloak은 Infinispan 분산 인메모리 그리드로 세션 동기화와 HA를 보장하는 아키텍처.',
    fullDef: 'Resource Server의 무상태 검증과 중앙 IAM의 상태 보존 분산 캐시가 조화를 이룹니다.',
    keyPoints: ['Stateless API 확장', 'Infinispan 분산 캐시 클러스터링'],
    specRef: 'Keycloak HA Guide',
    securityRelevance: '인증 서버 단일 장애점 방지를 위해 멀티 노드 HA가 필수입니다.',
    tags: ['Keycloak', 'Infinispan', 'Clustering']
  },
  {
    id: 'kill-switch',
    term: 'Kill Switch',
    koreanName: '즉시 세션 만료 킬 스위치 (Instant Session Invalidation)',
    category: 'Security Attack & Defense',
    shortDef: '보안 침해 시 분산된 모든 디바이스의 활성 로그인 세션을 1초 이내에 즉각 강제 만료시키는 비상 제어 기능.',
    fullDef: 'BFF 세션 스토어(Redis)의 세션 키를 즉시 삭제하여 무상태 JWT의 즉각 취소 불가능 문제를 완벽 해결합니다.',
    keyPoints: ['Redis O(1) 원격 파기', '긴급 보안 통제', 'Back-Channel Logout 연동'],
    specRef: 'NIST SP 800-63B',
    securityRelevance: '금융 및 엔터프라이즈 보안 컴플라이언스 필수 요건입니다.',
    tags: ['Security', 'Redis', 'Session']
  }
];

export const initialChecklists: ChecklistItem[] = [
  {
    id: 'chk-xss-1',
    category: 'XSS Defense',
    title: 'Access/Refresh Token의 localStorage 저장 완전 금지',
    description: '브라우저 로컬 스토리지에 토큰을 저장하면 XSS 발생 시 즉각 전체 탈취됩니다.',
    priority: 'CRITICAL',
    verificationMethod: 'Application > Local Storage 확인',
    codeExample: `// 권장 (인메모리 또는 HttpOnly 쿠키)\nauthStore.setTokenInMemory(token);`
  },
  {
    id: 'chk-csrf-1',
    category: 'CSRF Defense',
    title: '모든 인증/세션 쿠키에 SameSite 속성 및 __Host- 접두사 적용',
    description: 'SameSite=Lax/Strict와 Secure, HttpOnly를 강제합니다.',
    priority: 'CRITICAL',
    verificationMethod: 'Set-Cookie 응답 헤더 점검'
  },
  {
    id: 'chk-iam-1',
    category: 'Infra & IAM',
    title: '백엔드 마이크로서비스의 JWKS 공개키 로컬 캐싱 및 서명 무상태 검증',
    description: '인증 서버 호출 없이 로컬 JWKS 캐시로 RS256 무상태 검증을 수행합니다.',
    priority: 'HIGH',
    verificationMethod: '네트워크 분리 테스트'
  }
];

export const initialComparisons: ComparisonMatrix[] = [
  {
    id: 'spa-vs-bff',
    title: 'SPA + PKCE 직접 연동 vs BFF (Backend for Frontend) 패턴',
    subtitle: '보안 등급, 토큰 노출도, 인프라 비용에 따른 아키텍처 의사결정 매트릭스',
    category: 'Architecture Comparison',
    subjects: [
      {
        id: 'spa-pkce',
        name: 'SPA + PKCE 직접 연동',
        tag: 'Public Client',
        iconName: 'Layout',
        recommendedFor: '신속한 MVP 개발, 정적 호스팅',
        description: '브라우저에서 직접 PKCE로 토큰 교환'
      },
      {
        id: 'bff-pattern',
        name: 'BFF 패턴 + Token Relay',
        tag: 'Confidential Client (Recommended)',
        iconName: 'ShieldCheck',
        recommendedFor: '금융/핀테크, 개인정보 취급 시스템',
        description: 'BFF가 백채널 토큰 교환 및 주입'
      }
    ],
    dimensions: [
      {
        key: 'token-location',
        name: '토큰 보관 위치',
        values: {
          'spa-pkce': { text: '브라우저 JS 메모리', status: 'warning' },
          'bff-pattern': { text: '서버 측 Redis 세션', status: 'positive' }
        }
      },
      {
        key: 'xss-vulnerability',
        name: 'XSS 토큰 탈취 위험',
        values: {
          'spa-pkce': { text: '위험 (메모리 덤프 가능)', status: 'negative' },
          'bff-pattern': { text: '안전 (토큰 탈취 불가)', status: 'positive' }
        }
      }
    ],
    decisionGuide: [
      {
        condition: '금융/보안 규제 필수',
        recommendation: 'BFF 패턴 필수 채택',
        reason: '브라우저 토큰 노출 금지 규격 충족'
      }
    ]
  }
];

export const initialUserLearning: UserLearningState = {
  bookmarks: ['bff-token-relay'],
  completedTopics: ['keycloak-iam-overview'],
  notes: {
    'bff-token-relay': '사내 핀테크 결제 서비스에 BFF 계층을 도입하여 HttpOnly 쿠키와 Token Relay를 우선 적용할 예정.'
  },
  checklistProgress: {
    'chk-xss-1': true
  }
};
