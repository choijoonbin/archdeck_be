-- Idempotent initial taxonomy only. Do not maintain a second hand-written copy
-- of the frontend's 5 topics / 3 comparisons / 10 terms / 9 checkpoints here.

SET search_path TO archdeck, public;

INSERT INTO categories (slug, label, description, position) VALUES
  ('identity-and-access', '인증과 접근 제어', 'IAM, OIDC, OAuth, 세션과 권한의 개념 및 아키텍처', 10),
  ('frontend-architecture', '프론트엔드 아키텍처', 'SPA, 브라우저와 클라이언트 아키텍처', 20),
  ('backend-and-integration', '백엔드와 연동', 'BFF, 게이트웨이, 서버 간 통신과 데이터 경계', 30),
  ('security-and-operations', '보안과 운영', '위협 모델, 방어, 검증과 고가용성', 40)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO tags (slug, label) VALUES
  ('keycloak', 'Keycloak'),
  ('oidc', 'OIDC'),
  ('oauth', 'OAuth'),
  ('pkce', 'PKCE'),
  ('bff', 'BFF'),
  ('token-relay', 'Token Relay'),
  ('jwks', 'JWKS'),
  ('xss', 'XSS'),
  ('csrf', 'CSRF'),
  ('session', '세션'),
  ('high-availability', '고가용성')
ON CONFLICT (slug) DO NOTHING;
