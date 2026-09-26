# AGENTS.md

- When changing app behavior or agent capabilities, update the relevant references: `AGENT_CAPABILITIES.md`, `backend-v1/docs/app-guide.md`, and `backend-v1/docs/app-overview.md`. The latter two supply context to the in-app assistant.
- Verify changes with `npm test`, `npm run benchmark:test`, and `npm run test:android`; Android tests require Java 21 or newer.
