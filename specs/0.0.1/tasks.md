# Liquibase Sudarshan — Delivery Checklist & Roadmap — v0.0.1

## Delivered in 0.0.1

- [x] SQL lexer/parser with exact offsets (PostgreSQL + Oracle dialects)
- [x] Liquibase formatted-SQL metadata parser (changesets, contexts, labels,
      preconditions, rollbacks)
- [x] Header hardening: missing/malformed `--liquibase formatted sql`, directive typos,
      attribute-name typos, attribute-value validation (booleans, onFail/onError) —
      all with did-you-mean quick fixes
- [x] Schema model from repository DDL (+ ALTER ADD CONSTRAINT, CREATE UNIQUE INDEX)
- [x] DDL self-validation (duplicate columns, constraint columns, FK targets,
      unparseable CREATE TABLE)
- [x] Staging-table validation via MERGE mapping and `tmp_` naming heuristic
- [x] INSERT/MERGE value validation: VARCHAR/CHAR length, integer ranges,
      DECIMAL/NUMBER precision+scale, BOOLEAN, DATE, TIMESTAMP, UUID, NULLability
- [x] Duplicate PK/unique detection across statements; column/value arity checks
- [x] Unknown statement keyword detection (`INSEffRT` → INSERT) with quick fix
- [x] Delimiter validation (missing `;`, splitStatements:false, endDelimiter)
- [x] Country-aware repository validation (global DDL + global/country datasets)
- [x] Editor inspection with exact-range highlighting + quick fixes
- [x] Right-stripe tool window (chakra icon, red/amber/green status dot):
      Validation tab (findings, execution plan, data preview, context menu) and
      Datasource tab (lamp, connect/test, DATABASECHANGELOG browser, tables + row counts)
- [x] Pre-commit and pre-push validation (committed-content based, optional VCS deps)
- [x] Read-only dry run for PostgreSQL + Oracle: execution plan (RUN/SKIP/HALT/BLOCKED),
      pending changesets, live preconditions, FK/PK probes, INSERT/UPDATE/CONFLICT preview
- [x] Single-file "Dry Run Against Database" action
- [x] Headless CLI incl. datasource flags; VS Code tasks integration; CI-ready exit codes
- [x] Free Oracle 23ai Docker datasource instructions + E2E verification against it
- [x] 155 automated tests; Plugin Verifier Compatible on 2023.2 / 2024.2 / 2025.1
- [x] Sample PostgreSQL repository + Oracle test repository with documented findings

## Roadmap

### 0.0.2 (quality)
- [ ] Fix the remaining deprecated-API note (CredentialAttributes constructor on 2025.1+)
- [ ] Dry-run support for composite (multi-column) unique keys in the data preview
- [ ] Oracle wallet / Autonomous Database (TNS_ADMIN) connection support
- [ ] Structured XML/JSON changelog awareness (include/includeAll traversal)

### 0.1.0 (multi-IDE)
- [ ] **Verified compatibility with other IntelliJ-based IDEs** — the plugin already
      depends only on the core platform (installable in PyCharm, WebStorm, DataGrip,
      GoLand, CLion, Rider, PhpStorm, Android Studio Iguana+); this milestone adds a
      Plugin Verifier matrix across those products, product-specific testing (DataGrip
      first — closest audience), and Marketplace listing metadata for each
- [ ] Optional integration with the IDE's own Database tool window datasources
      (reuse configured connections instead of a separate JDBC config)

### Later
- [ ] More dialects (SQL Server, MySQL/MariaDB)
- [ ] Changeset checksum (MD5SUM) comparison for exact runOnChange re-run prediction
- [ ] Batch quick-fix ("fix all staging lengths in file")
