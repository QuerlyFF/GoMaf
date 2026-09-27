# GoMaf v11 worktree status

Current gate: V11-G2 Players vertical slice.

Certified baseline:

- V11-G0 Architecture: PASS.
- V11-G1 Foundation: PASS.

Current work package:

- WP11-006 Players read API: IMPLEMENTED, executable Maven gate pending.
- WP11-007 Player administration API: PENDING.
- WP11-008 Players React UI: PENDING.
- WP11-009 Browser E2E for Players: PENDING.

WP11-006 source includes:

- paged/searchable/sortable public players API;
- public player profile DTO;
- public avatar resource;
- player game-history DTO and pagination;
- hidden-game visibility delegated to the existing `GameService.canView` policy;
- ADMIN accounts hidden from all new public player endpoints;
- MODERATOR status not exposed by public player DTOs, preserving the legacy public presentation as players;
- explicit sort whitelist and pagination validation;
- integration tests covering anonymous reads, ADMIN hiding, hidden-game history, avatar behavior and invalid sort/page inputs.

Production deployment is not part of WP11-006 and has not been changed.
