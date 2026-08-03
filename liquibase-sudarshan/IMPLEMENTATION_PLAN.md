# Liquibase Database Validator — IntelliJ IDEA Plugin — Implementation Plan

## 1. Current project structure

The repository (`c:\Users\User\Project\plugin`) is **empty**. This is a greenfield project.

## 2. Existing technology stack

Nothing exists in the project. The build machine provides:

| Tool   | Version | Notes                                        |
|--------|---------|----------------------------------------------|
| JDK    | 21.0.4  | Required by IntelliJ Platform 2024.2+ builds |
| Gradle | 8.10    | Used to generate the project wrapper         |

Chosen stack for the plugin:

| Component                        | Choice                                      |
|----------------------------------|---------------------------------------------|
| Language                         | Kotlin 2.0.x                                |
| Build                            | Gradle 8.10 + IntelliJ Platform Gradle Plugin 2.x |
| Target IDE                       | IntelliJ IDEA Community 2024.2 (since-build 242) |
| Unit tests                       | JUnit 5 (pure-JVM core)                     |
| Integration tests                | IntelliJ Platform Test Framework (`BasePlatformTestCase`, JUnit 4 via vintage engine) |
| Optional DB metadata             | PostgreSQL JDBC driver (read-only)          |

## 3. Existing IntelliJ plugin configuration

None. `plugin.xml`, Gradle config, and all sources must be created.

## 4. What can be reused

Nothing in-repo. From the platform we reuse (rather than reinvent):

- **Inspection framework** (`LocalInspectionTool` + `ProblemsHolder`) for editor highlighting, severities, tooltips, and quick fixes.
- **Kotlin UI DSL** for the settings page; `PersistentStateComponent` for persistence; `PasswordSafe` for the DB password.
- **`Task.Backgroundable`** for repository-wide validation; **ToolWindow API** for the report; **VFS listener** for cache invalidation.

## 5. Missing components

Everything: build scripts, plugin descriptor, Liquibase parser, SQL parser, schema model/resolver,
validation engine, inspections, quick fixes, settings UI, repository validation action + report tool
window, database metadata provider, tests, sample repository, README.

## 6. Proposed architecture

```text
IntelliJ layer (depends on core, never the reverse)
  ├── inspection/  LiquibaseSqlInspection (LocalInspectionTool, checkFile-based, whole-file)
  ├── quickfix/    ReplaceTextQuickFix (change VARCHAR(50) → VARCHAR(15)), SelectValueQuickFix
  ├── settings/    LiquibaseSettingsService (PersistentStateComponent) + Configurable (Kotlin UI DSL)
  ├── schema/      SchemaIndexService — caches parsed DDL, VFS-listener invalidation, DB overlay
  └── plugin/      Validate File / Validate Repository actions, report ToolWindow, notifications

Pure-JVM core (zero IntelliJ imports → unit-testable with plain JUnit 5)
  ├── liquibase/   LiquibaseParser — formatted-SQL header, changesets, contexts, labels,
  │                preconditions, rollback, comment; every element carries text offsets
  ├── sql/         SqlLexer (offset-tracking tokenizer, understands -- comments, /* */,
  │                dollar-quoted strings, '' escapes, ::casts) + SqlParser (recursive descent):
  │                CREATE [TEMP] TABLE, INSERT…VALUES, MERGE…USING…ON…WHEN, ALTER TABLE ADD
  │                CONSTRAINT, CREATE [UNIQUE] INDEX, DELETE (for rollback), tolerant skip of
  │                anything else. Dialect kept behind one entry point so more dialects can be added.
  ├── schema/      TableSchema / ColumnSchema / ConstraintSchema / SqlDataType,
  │                DdlSchemaBuilder (parsed DDL scripts → schema map), SchemaProvider interface
  ├── validation/  ValidationEngine orchestrating small validators:
  │                TypeCompatibility (decl vs decl), LiteralValidators (value vs type),
  │                TempTableValidator, InsertValidator, MergeValidator, DuplicateValidator,
  │                DataFlow (tmp table → INSERT → MERGE → target column mapping)
  └── database/    DatabaseMetadataProvider interface, JdbcDatabaseMetadataProvider
                   (information_schema, read-only), ReadOnlyDatabaseValidator (SELECT-only
                   FK / PK existence probes; never any write statement)
```

Key decisions and why:

1. **Hand-written offset-tracking lexer + recursive-descent parser** instead of JSqlParser.
   The single most important integration requirement is highlighting the *exact* offending
   token (`VARCHAR(50)`, a literal value, a column name). JSqlParser has unreliable node
   positions, chokes on PostgreSQL `CREATE TEMP TABLE … ON COMMIT DROP`, and would treat
   Liquibase comment metadata as noise. A tolerant recursive-descent parser over a real token
   stream (not regex-only) gives precise ranges, never throws on unknown statements, and is
   trivially extensible per dialect. Parsing scope is a well-defined subset (DDL, INSERT,
   MERGE, DELETE) which is exactly what this repository style contains.
2. **`LocalInspectionTool.checkFile` on the whole file.** IntelliJ Community has no SQL PSI,
   so `.sql` files are plain text; a whole-file inspection parses once per highlighting pass
   (platform-debounced), registers problems with `TextRange`s, and plugs into native severity,
   tooltip, suppression and batch-inspect machinery. If the Ultimate SQL plugin is present the
   inspection still works because it never depends on SQL PSI.
3. **Schema is resolved once and cached.** `SchemaIndexService` parses all DDL under the
   configured directories into an immutable snapshot, invalidated by a VFS listener scoped to
   those directories and by settings changes. Inspections only ever read the snapshot.
4. **Country model:** global DDL defines the schema; country `staticdatasetup` files are DML
   validated against that same schema (never treated as separate schemas). The configured
   country code scopes repository-wide validation.
5. **Database mode is strictly read-only**: `information_schema` metadata queries (schema
   overlay, DB wins over files when enabled) and parameterized `SELECT … LIMIT 1` existence
   probes for FK/PK checks, executed only from the explicit repository-validation action,
   never on keystrokes. No INSERT/UPDATE/DELETE/MERGE/DROP/ALTER/TRUNCATE, ever.
6. **Mapping chain** (`tmp table → INSERT → MERGE → target`) is computed by `DataFlow` from
   the MERGE `ON` clause, `UPDATE SET` assignments and `WHEN NOT MATCHED INSERT` column/value
   pairing — by name, not position. A configurable `tmp_`/`temp_`/`stg_` prefix heuristic
   covers files whose MERGE lives in another file.

## 7. Implementation plan (phases)

| Phase | Deliverable | Done-when |
|-------|-------------|-----------|
| 1 | Gradle project, wrapper, `plugin.xml`, settings service + UI, empty inspection registered | `gradlew build` green, plugin loads in `runIde` |
| 2 | `LiquibaseParser` + unit tests | header/changeset/context/label/precondition/rollback parsing tested |
| 3 | `SqlLexer`, `SqlParser`, `SqlDataType`, `DdlSchemaBuilder`, `SchemaIndexService` + tests | DDL repo parsed into schema snapshot |
| 4 | `TempTableValidator` + `TypeCompatibility`, wired into the inspection | VARCHAR(50)-vs-VARCHAR(15) error highlights in editor |
| 5 | `InsertValidator` + `LiteralValidators` (varchar/int/decimal/bool/date/ts/uuid/null) | value-too-long, bad literal, NULL-into-NOT-NULL detected |
| 6 | `MergeValidator` + `DataFlow` | source/target existence, mapping, type-mismatch validation |
| 7 | Country resolution + `DuplicateValidator` | country DML validated against global schema; static dup detection |
| 8 | DB metadata provider + read-only validator + settings wiring | optional overlay + FK/PK probes from repo action |
| 9 | Quick fixes | change-datatype fix applies correctly in tests |
| 10 | Repository validation action + report tool window, samples, README | full deliverable list satisfied |

Each phase ends with a full `gradlew build` (compile + all tests). No placeholder
implementations for core validation.

## 8. Test strategy

- **Pure unit tests (JUnit 5)** for everything in the core: lexer/parser round-trips with
  offset assertions, Liquibase metadata extraction, every datatype rule from the spec
  (VARCHAR 15/16, INTEGER overflow, DECIMAL(10,2) precision/scale, BOOLEAN, NOT NULL + NULL,
  UUID good/bad), MERGE mapping (missing source/target column, type mismatch), duplicate
  PK/unique detection, and end-to-end `ValidationEngine` runs on realistic scripts including
  a fully valid script (no false positives).
- **Platform integration tests (`BasePlatformTestCase`)**: DDL fixture file + DML file opened
  in the editor; assert the inspection produces exactly the expected highlights at the
  expected offsets with the expected messages; quick fix availability and application
  (text after fix); valid file produces zero problems.
- **No DB in tests**: `DatabaseMetadataProvider` is an interface; DB-backed logic is tested
  against a fake provider.
