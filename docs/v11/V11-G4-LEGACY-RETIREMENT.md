# V11-G4 WP11-022 Legacy Retirement

Status: implemented, executable verification pending.

After accepted WP11-021 production cutover, the rollback boundary is the previous immutable release rather than a Thymeleaf toggle inside the current release.

Retired:
- Spring Boot Thymeleaf dependency;
- all `src/main/resources/templates` files;
- legacy browser MVC/form controllers for pages and mutations;
- legacy form-login CAPTCHA filter and server-side form login processing;
- `APP_SPA_ENABLED` / `app.spa.enabled` transition toggle;
- Thymeleaf-specific source/UI audit assertions.

Preserved:
- React SPA routes and embedded Vite assets;
- `/api/v1` session authentication, CSRF and CAPTCHA login/registration;
- `/healthz`, CAPTCHA and `/avatar/{id}` compatibility endpoint;
- server-side authorization/scopes and audit logging;
- immutable release rollback pointers and pre-deploy backups;
- Flyway V1-V4 bytes unchanged.

No schema cleanup migration is introduced by WP11-022.
