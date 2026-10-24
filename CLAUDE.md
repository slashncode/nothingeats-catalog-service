# CLAUDE.md

Catalog service of NothingEats (restaurants, dishes, menus, DE/EN texts). Created from the Kotlin service template. The cross-repo plan and session rules live in `../CLAUDE.md` (workspace root), if present — read it first.

## Commands

- `./gradlew build` — compile (warnings are errors), ktlint, tests. Needs Docker for Testcontainers.
- `./gradlew ktlintFormat` — auto-format
- `./gradlew :app:run --args=migrate` / `./gradlew :app:run` — needs `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`
- `./gradlew :app:run --args="cron load-seed"` — imports `adapters/src/main/resources/seed/catalog.json`
- `./gradlew :app:jibDockerBuild -Djib.from.platforms=linux/arm64` — local image

## Rules

- Respect the module direction: `domain` ← `application` ← `adapters` ← `app`. Never add a framework dependency to `domain`.
- Wiring happens only in `app/.../ServiceComponent.kt`. Lifecycle components, health checks, routes and cron tasks are contributed with `@IntoSet`.
- Expected failures are `AppError` subtypes with an `UPPER_SNAKE` code, thrown from use cases. Domain code throws `InvalidCatalogData`, which use cases turn into `CATALOG_INVALID` with the field name.
- Schema changes are new Flyway files in `adapters/src/main/resources/db/migration`; never edit an applied migration (`V1__create_items.sql` is the template's and stays). Serving pods refuse to start with pending migrations.
- Customer-facing text is always `LocalizedText` with German and English; never add a single-language text field.
- Money is `Money` in euro cents, never floating point.
- The event contract is `docs/events.md`; change it together with the payload classes in `adapters/.../messaging`. Events are snapshots in all languages.
- The seed is test data for the demo; `LoadSeedTaskTest` checks that it stays valid (8 restaurants × 6 dishes).
