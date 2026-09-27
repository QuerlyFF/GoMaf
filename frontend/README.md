# GoMaf v11 frontend foundation

G1 development-only React + TypeScript + Vite application.

Production serves the embedded React SPA from the Spring Boot artifact. Legacy Thymeleaf UI was retired in WP11-022 after production cutover acceptance.

Commands after dependencies are resolved:

```bash
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

The first online bootstrap may create `package-lock.json`; after that every gate uses `npm ci` only.
