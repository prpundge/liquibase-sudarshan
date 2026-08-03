# Marketplace Listing Content — paste-ready

Everything below fills the empty sections of the plugin page at
plugins.jetbrains.com (plugin id 33326). Fields marked *(web UI only)* must be
entered on the page via **Edit Section** — they cannot come from plugin.xml.

## Contacts & Resources *(web UI only)*

| Field | Value |
|---|---|
| Documentation URL | `https://github.com/prpundge/liquibase-sudarshan#readme` |
| Bugtracker | `https://github.com/prpundge/liquibase-sudarshan/issues` |
| Forum | `https://github.com/prpundge/liquibase-sudarshan/discussions` |
| Source Code | already set ✔ |

(Enable Discussions once in the GitHub repo: Settings → Features → Discussions.)

## General Information *(web UI only)*

- **Copyright**: `© 2026 Pravin Pundge — MIT License`
- **Tags**: keep **Database**; replace *Education / Plugin Development / Viewer* with
  **Tools integration** and **Inspections** (better search match — this is not an
  education or plugin-dev tool, and wrong tags hurt ranking).

## Getting Started *(web UI only — paste as is)*

```markdown
**1. Point the plugin at your repository.** Open your Liquibase repository as a project.
If it follows the common layout (database/global/ddl, database/global/staticdatasetup,
database/countries/<CC>/staticdatasetup) it works immediately; otherwise set the paths
under Settings | Tools | Liquibase Sudarshan (there is an Auto-detect button).

**2. Open any Liquibase SQL file.** Errors and warnings appear inline on the exact
offending token — oversized values, wrong datatypes, NULL violations, MERGE mapping
problems, duplicate keys, changeset header typos. Press Alt+Enter for quick fixes
(e.g. "Change VARCHAR(50) to VARCHAR(15)").

**3. Validate the whole repository.** Tools | Liquibase Sudarshan | Validate Liquibase
Repository opens the report tool window (right stripe, chakra icon) — double-click any
finding to jump to it. The icon shows red/amber/green after each run.

**4. Optional — connect a database (PostgreSQL or Oracle).** Enable database validation
in the settings, then use the tool window's Datasource tab: Connect / Test, browse
DATABASECHANGELOG and all tables, and run a read-only DRY RUN — execution plan
(RUN/SKIP/HALT), live precondition checks, and a per-row INSERT/UPDATE data preview.
Right-click a single file → "Dry Run Against Database" for a focused simulation.
No SQL is ever executed: SELECTs only, on a read-only transaction.

**5. Commit/push safely.** SQL files are validated before every commit and push
(toggleable) — broken changesets never leave your machine unnoticed.

Try it in 2 minutes: the repository's `oracle-liquibase-testrepo` folder contains
intentionally broken files with documented findings, plus one-command instructions for
a free local Oracle 23ai datasource (Docker).
```

## Screenshots *(web UI only — capture these 3, ≥1200×760)*

1. **Inline errors**: open
   `oracle-liquibase-testrepo/database/countries/SG/staticdatasetup/account_type.sql`
   in the IDE — red highlights on `VARCHAR2(50 CHAR)`, the oversized literal, NULL and
   the duplicate `'SAVINGS'`, with the Alt+Enter quick-fix popup open.
2. **Tool window**: after *Validate Liquibase Repository* with the datasource connected —
   Errors/Warnings tree + Execution plan + Data preview (INSERT/UPDATE rows) visible.
3. **Datasource tab**: green lamp, DATABASECHANGELOG node expanded, tables with row
   counts.

## Technical Information → Plugin Features *(web UI only)*

Suggested feature list (one per line): Liquibase formatted SQL validation ·
PostgreSQL & Oracle dialects · staging-table/MERGE data-flow checks · quick fixes ·
pre-commit & pre-push gates · read-only database dry run with execution plan and data
preview · DATABASECHANGELOG browser · headless CLI for CI/VS Code.

## Token handling (IMPORTANT)

The publish token must NEVER be written into build.gradle.kts (public repo!). It now
loads from `JETBRAINS_MARKETPLACE_TOKEN` env var or `marketplaceToken=` in
`%USERPROFILE%\.gradle\gradle.properties` (outside the repository). Because a token was
briefly pasted into a working file, **revoke it and generate a fresh one**
(Marketplace → profile → My Tokens), then update the user-level gradle.properties.
Marketplace tokens have the form `perm:xxxx.yyyy.zzzz` (colon after `perm`).
