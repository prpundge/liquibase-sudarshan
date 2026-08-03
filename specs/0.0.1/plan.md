# Liquibase Sudarshan — Architecture Plan — v0.0.1

```text
IntelliJ layer (depends on core, never the reverse)
  ├── inspection/   LiquibaseSqlInspection — whole-file LocalInspectionTool, exact-range
  │                 highlighting, severity mapping, quick-fix wiring
  ├── quickfix/     ReplaceTextQuickFix, SelectValueQuickFix
  ├── settings/     LiquibaseSettings (PersistentStateComponent, PasswordSafe for the DB
  │                 password) + Configurable UI; ProjectPaths (resolution + auto-detect)
  ├── schema/       SchemaIndexService — cached schema snapshot (settings counter + VFS
  │                 listener invalidation), optional live-DB overlay, executed changesets,
  │                 row counts; all fetches on background threads
  └── plugin/       Validate/DryRun actions, ValidationRunner, report ToolWindow
                    (Validation + Datasource tabs), status-dot stripe icons,
                    vcs/ pre-commit CheckinHandler + pre-push PrePushHandler (optional deps)

Pure-JVM core (zero IntelliJ imports — plain JUnit-testable)
  ├── sql/          Offset-tracking lexer + tolerant recursive-descent parser
  │                 (PostgreSQL + Oracle subset: CREATE [GLOBAL TEMP] TABLE, INSERT,
  │                 MERGE with parenthesized ON, ALTER ADD CONSTRAINT, CREATE INDEX),
  │                 SqlDataType model, missing-';' detection, TextDistance
  ├── liquibase/    Formatted-SQL metadata parser: changesets, contexts, labels,
  │                 preconditions, rollbacks, directive/attribute/value typo detection
  ├── schema/       TableSchema/ColumnSchema/ConstraintSchema, DdlSchemaBuilder,
  │                 SchemaProvider abstraction
  ├── validation/   ValidationEngine orchestrating: TempTable/Insert/Merge/Duplicate/Ddl
  │                 validators, TypeCompatibility, LiteralValidators, DataFlow
  │                 (tmp → INSERT → MERGE → target mapping by name)
  ├── database/     DatabaseConnector/DatabaseSession (read-only contract, SqlGuards),
  │                 JdbcConnector (PostgreSQL information_schema / Oracle ALL_* views,
  │                 schema-qualified lookups, server-enforced READ ONLY transactions),
  │                 LiquibaseDryRun (plan, pending, preconditions, FK/PK probes, preview)
  └── cli/          ValidatorCli — headless validation + dry run, gcc-style output
```

Key decisions (unchanged rationale from the original implementation plan):

1. **Hand-written offset-tracking parser** instead of a library: exact-token highlighting
   is the core UX; Liquibase comments must never break SQL parsing; tolerant recovery
   means one bad statement never hides the rest.
2. **Whole-file inspection** (`checkFile`): works identically in Community (plain-text
   SQL) and Ultimate (SQL PSI), plugs into native severities/suppression/batch inspect.
3. **Schema snapshot caching** keyed by settings modification counter + a VFS `.sql`
   listener; inspections only read snapshots — nothing re-parses per keystroke, nothing
   blocks the EDT (PasswordSafe and JDBC access are pooled-thread only).
4. **Read-only database contract** enforced in one place (`JdbcConnector`): autocommit
   off + `READ ONLY` transaction + single-SELECT guard + identifier validation +
   rollback-on-close.
5. **Compatibility floor 2023.2** with JVM 17 bytecode and Kotlin 1.8 API; no upper
   bound; only long-stable platform APIs (verified with Plugin Verifier; the one
   API that forced the floor is PrePushHandler's 3-arg `handle`, absent in 2023.1).

Build: Gradle + IntelliJ Platform Gradle Plugin 2.1.0, compiled against IC 2024.2.4,
`runIde` / `buildPlugin` / `verifyPlugin` / `validateRepo` / `publishPlugin` tasks.
