# Agent instructions

## Interaction Style
- Be extremely concise. Sacrifice grammar for the sake of concision.

## Publications
- `publicationId` semantics and consumer rules: see `docs/publications.md`. Only valid when `status` is `published`.

## Database Schema Changes
- When changing Flyway migrations, persisted enums, JPA converters, views, or DB-facing entities, use the `refresh-database-schema-docs` skill and regenerate `docs/database-schema.md`.
