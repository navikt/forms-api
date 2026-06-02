---
name: refresh-database-schema-docs
description: Refreshes docs/database-schema.md for this repository's PostgreSQL schema. Use when editing Flyway migrations, persisted enums, JPA converters, views, triggers, functions, or DB-facing entities that should be reflected in the schema documentation.
---

# Refresh database schema docs

## Quick start

1. Review the database sources:
   - `src/main/resources/db/migration/**/*.sql`
   - DB-facing entities and view entities under `src/main/kotlin/`
   - persisted enum/converter classes that affect stored DB values
2. Update `docs/database-schema.md` to reflect the current schema.
3. Refresh the **Flyway migration inputs** table at the bottom of `docs/database-schema.md` so it matches the current migration files.

## Workflow

- Treat Flyway migrations as the primary source of truth for the physical schema.
- Use Kotlin entities, converters, and enums to clarify names, relationships, and persisted values when they add detail not obvious from SQL alone.
- Keep Mermaid diagrams GitHub-renderable.
- Preserve the existing document structure unless the change clearly requires a new section.
- If a schema-related change does not affect the markdown content, still refresh the migration table if the migration set changed.

## Checklist

- `docs/database-schema.md` matches the current tables, views, and relationships
- persisted DB values from enums/converters are reflected where relevant
- the bottom migration table lists the current Flyway files in order
