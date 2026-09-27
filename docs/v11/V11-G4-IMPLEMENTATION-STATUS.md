# V11-G4 Implementation Status

Current local objective: WP11-017..020 pre-cutover production integration.

Implemented source changes:

- Vite production output embedded in the Spring Boot JAR under classpath `static/`.
- Explicit allow-listed SPA routes with no `/api/**` fallback.
- WP11-022 retires legacy MVC/form controllers and Thymeleaf after accepted production cutover; SPA is unconditional.
- `/healthz`, `/forbidden`, CAPTCHA and legacy avatar compatibility remain independent from SPA routing.
- React registration route added on top of the existing registration API.
- CSP changed to self-hosted compiled scripts only; no `unsafe-inline` or `unsafe-eval`.
- Multi-stage Node/Maven/Java production Docker build with frontend and backend verification gates.
- Deploy source hash includes frontend bytes and production smoke/audit validates SPA/API/security behavior.
- G4 Playwright runs against Spring-served production assets rather than the Vite development server.

Gate semantics:

- `V11-G4 PRE-CUTOVER PASS` closes WP11-017..020 only.
- WP11-021 and overall `V11-G4 PASS` require a real transitional production deployment, HTTPS smoke, production audit, external health and retained rollback evidence.
- WP11-022 legacy retirement remains a separate later package and is not combined with first v11 public cutover.
