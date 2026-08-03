# Liquibase Sudarshan — Feature Specification — v0.0.1

> Spec-kit style specification of everything shipped in the initial public release.
> Companion documents: [plan.md](plan.md) (architecture), [tasks.md](tasks.md)
> (delivery checklist + roadmap), [publishing.md](publishing.md) (Marketplace release).

## Overview

Liquibase Sudarshan is an IntelliJ IDEA plugin (plus a headless CLI) that validates
Liquibase formatted SQL repositories **before any SQL executes**: static schema/data
validation in the editor, commit/push gates, and a strictly read-only database dry run
that simulates what `liquibase update` would do against a configured PostgreSQL or
Oracle datasource.

- **Plugin id**: `com.sudarshan.liquibase-validator`
- **Version**: 0.0.1
- **IDE compatibility**: IntelliJ IDEA 2023.2+ (build 232+), Community and Ultimate,
  no upper bound. Verified with JetBrains Plugin Verifier against 2023.2.7, 2024.2.4,
  2025.1.3. Installable in other IntelliJ-based IDEs (see roadmap).
- **Dialects**: PostgreSQL and Oracle.
- **Safety guarantee**: validation never executes INSERT/UPDATE/DELETE/MERGE/DDL.
  Database mode issues only SELECT/metadata queries on a server-enforced READ ONLY
  transaction that is rolled back on close.

## Repository model

The plugin understands repositories shaped like:

```text
database/
├── global/
│   ├── ddl/                 <- CREATE TABLE / ALTER TABLE / CREATE [UNIQUE] INDEX (schema source of truth)
│   └── staticdatasetup/     <- global static DML (temp/GTT staging + MERGE upserts)
└── countries/<CC>/staticdatasetup/   <- country DML validated against the GLOBAL schema
```

Paths are configurable (absolute or project-relative) with auto-detection; a country code
setting scopes repository-wide validation. Nothing is hardcoded.

## F1 — Editor inspection (inline errors and warnings)

Whole-file inspection `Liquibase | Liquibase SQL validation` runs on every `.sql` file as
you type; every finding is anchored to the exact offending token with native IntelliJ
severity highlighting (red error / amber warning / weak warning) and tooltips.

Checks (each toggleable in Settings | Tools | Liquibase Sudarshan):

| # | Check | Severity |
|---|-------|----------|
| F1.1 | Staging (temp/GTT) column datatype/length vs mapped target column (via MERGE mapping or `tmp_`-name heuristic) | error/warning |
| F1.2 | INSERT value vs column type: VARCHAR/CHAR length (code points), SMALLINT/INTEGER/BIGINT ranges, DECIMAL/NUMBER precision+scale, BOOLEAN, DATE, TIMESTAMP, UUID literals | error |
| F1.3 | NULL into NOT NULL; NOT NULL column (no default) missing from the INSERT column list; optional Oracle `'' = NULL` semantics | error |
| F1.4 | Unknown table / unknown column (INSERT, MERGE source/target, rollback statements) | error/warning |
| F1.5 | MERGE validation: table existence, ON/SET/INSERT column existence, source→target datatype compatibility, value/column arity | error |
| F1.6 | Duplicate PK/unique values across all VALUES rows and INSERT statements of a file (mapped through staging when applicable) | error |
| F1.7 | Unused staging column (defined but never used by the MERGE) | warning |
| F1.8 | Column-count vs value-count mismatches; mapping by name, never blind position | error |
| F1.9 | DDL self-validation: duplicate columns, constraints on missing columns, FK to unknown table/column, unparseable CREATE TABLE | error/warning |
| F1.10 | Unknown SQL statement keyword (e.g. `INSEffRT`) with did-you-mean suggestion; following statements still validate | error |
| F1.11 | Delimiters: missing `;` between statements, `splitStatements:false` with multi-statement body, custom `endDelimiter` vs `;`-terminated body | warning |
| F1.12 | Liquibase structure: missing/malformed `--liquibase formatted sql` header, duplicate changeset ids, invalid `--changeset author:id` headers, SQL before the first changeset, rollbacks referencing unknown tables | error/warning |
| F1.13 | Header typo detection with did-you-mean: directives (`--changeasset`→`--changeset`, `--coasmment:`→`--comment`), changeset attributes (`failOnErasdror`→`failOnError`), precondition attributes (`onFaisl`→`onFail`), and attribute values (`runOnChange:ture`→`true`, `onFail:HALTT`→`HALT`) | error/warning |

Unresolvable schema never produces false positives: validation is skipped with a single
notice (F-safety: "valid SQL is never flagged because metadata is unavailable").

## F2 — Quick fixes

- Change staging datatype to the target's (e.g. `VARCHAR(50)` → `VARCHAR(15)`).
- Change a typo'd keyword/directive/attribute/value to the suggestion.
- Insert the missing `--liquibase formatted sql` header.
- Select an oversized value for editing (business data is never modified automatically).

## F3 — Commit and push gates

- **Pre-commit** (`Validate SQL files before commit`): validates every `.sql` file in the
  commit; a dialog lists errors and lets the developer cancel or proceed. Cancelling the
  progress cancels the commit (never commits unvalidated).
- **Pre-push** (`Validate SQL files before push`): validates the **committed content** of
  outgoing commits (not the working tree) and includes the database dry run when a
  datasource is configured; the push can be aborted. Both are optional dependencies
  (VCS / Git4Idea) — the plugin loads without them.

## F4 — Database dry run (read-only)

With a configured datasource (PostgreSQL `information_schema` / Oracle `ALL_*` views):

- **Schema overlay**: live metadata overrides the file baseline for validation.
- **Execution plan**: simulates `liquibase update` order — per changeset
  `RUN / SKIP (already in DATABASECHANGELOG) / HALT (failing precondition, onFail:HALT) /
  BLOCKED (after a HALT)` with statement counts, honoring `runAlways`/`runOnChange`.
- **Pending changesets** from `DATABASECHANGELOG` (fresh-database detection included).
- **Live preconditions**: `--precondition-sql-check` SELECTs are executed and compared to
  `expectedResult` (numeric equivalence, no-rows handling).
- **Data preview** (SQL Developer-style): every statically-known row classified as
  **INSERT / UPDATE / CONFLICT** against live data, grouped by changeset in execution order.
- **FK probes**: statically-known FK values checked for matching rows.
- **PK conflicts**: direct INSERTs whose key already exists.
- Checks and preview apply only to changesets that would actually run.

## F5 — Tool window (right stripe, chakra icon)

- Stripe icon carries a status dot after each run: red (errors) / amber (warnings) /
  green (clean) — INFO rows never dirty the stripe.
- **Validation tab**: Errors / Warnings / Info groups, execution plan, data preview;
  double-click navigates to the exact offset; right-click menu (Jump to Source, Copy
  Message, Validate Repository Again, Configure).
- **Datasource tab**: status lamp (grey/amber/green/red with failure precedence),
  connection summary, Connect/Test + Refresh Metadata + Configure buttons,
  `DATABASECHANGELOG: N executed changeset(s)` browser, and all schema tables with
  columns, types, NOT NULL/PK flags and row counts (schemas ≤ 50 tables).

## F6 — Actions

- Right-click file/directory → **Validate Liquibase SQL**.
- Right-click `.sql` file → **Dry Run Against Database** (single-file simulation).
- Tools → Liquibase Sudarshan → **Validate Liquibase Repository** (global DDL + global
  static + configured country) with report + notification summary.

## F7 — Headless CLI (CI / VS Code)

```bash
./gradlew validateRepo -Prepo=<dir> -PrepoArgs="--oracle --country=SG
    --db-url=<jdbc> --db-user=<u> --db-password=<p> [--db-schema=<s>]"
```

gcc-style `file:line:col: severity: message` output (plus `plan`/`preview`/`dry run` info
lines), non-zero exit on errors, `--fail-on-warnings`, auto-detected or explicit
`--ddl=`/`--data=` directories. The Oracle test repo ships `.vscode/tasks.json` wiring
this into VS Code's Problems panel.

## F8 — Settings

Repository paths (+ auto-detect), country code, datasource (URL/user/password-in-
PasswordSafe/schema, test connection), per-check toggles, Oracle empty-string mode,
commit/push gates.

## Test evidence

155 automated tests (unit: parser/lexer offsets, every datatype rule, Liquibase metadata,
MERGE mapping, duplicates, dry run with fake sessions, DDL validation, typo detection;
platform: inline highlight ranges/messages, quick-fix application, zero false positives
on valid files). End-to-end verified against a real Oracle 23ai Free container
(execution plan, UPDATE-vs-INSERT preview on seeded data, live preconditions).
