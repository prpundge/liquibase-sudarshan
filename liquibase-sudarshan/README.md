# Liquibase Sudarshan – SQL & Data Validator (IntelliJ IDEA plugin)

Catch Liquibase, **PostgreSQL and Oracle** data errors **inside the IntelliJ editor, before
any SQL is executed** — plus pre-commit/pre-push validation, a read-only database dry run,
and a headless CLI for CI and VS Code. Liquibase Sudarshan understands database repositories that combine global DDL,
global static datasets, country-specific static datasets and Liquibase formatted SQL with
temporary staging tables and `MERGE` upserts.

```text
database/
├── global/
│   ├── ddl/                    <- table definitions (the schema source of truth)
│   └── staticdatasetup/        <- global static DML
└── countries/
    ├── IN/staticdatasetup/     <- country-specific DML, validated against the global schema
    ├── SG/staticdatasetup/
    └── US/staticdatasetup/
```

## What it detects

| Check | Example message |
|-------|-----------------|
| Staging column wider than target | `temporary column 'code' in 'tmp_account_type' is VARCHAR(50) but target column 'account_type.code' is VARCHAR(15)` |
| Oversized INSERT value | `value length 27 exceeds VARCHAR(15) — column 'account_type.code' allows at most 15 characters` |
| Numeric range overflow | `value 999999 is out of range for SMALLINT (-32768..32767)` |
| DECIMAL precision/scale | `decimal scale exceeded for column 'account.balance' DECIMAL(10,2)` |
| Invalid BOOLEAN / DATE / TIMESTAMP / UUID literals | `invalid UUID '12345' — expected format xxxxxxxx-xxxx-…` |
| NULL into NOT NULL | `NULL is not allowed for column 'account_type.code' (NOT NULL)` |
| Missing mandatory column | `NOT NULL column 'account_type.name' has no default and is missing from the INSERT column list` |
| Unknown table / column | `column 'invalid_column' does not exist in table 'tmp_account_type'` |
| MERGE mapping problems | `column 'x' does not exist in source table…`, `MERGE source/target datatype mismatch…`, value/column count mismatches |
| Duplicate static data | `duplicate value for unique key of 'account_type' (code='SAVINGS'); already used in row 1, duplicated in row 3` |
| Unused staging column | `column 'extra_column' is defined in staging table … but is not used by the MERGE statement` (configurable) |
| Liquibase structure | duplicate changesets, invalid headers, SQL before the first changeset, rollbacks referencing unknown tables |
| Delimiters | missing `;` between statements (the next statement still validates), `splitStatements:false` with a multi-statement body, custom `endDelimiter` that will not split `;`-terminated statements |

Errors are highlighted on the **exact offending token** (the datatype, the literal value, the
column name). Quick fixes are offered where safe:

- **Change VARCHAR(50) to VARCHAR(15)** — rewrites the staging datatype to match the target.
- **Select value for editing** — jumps to and selects an oversized value; business data is
  never modified automatically.

## How validation works

1. **Offline mode (default).** The repository's DDL directory is parsed into a schema model
   (tables, columns, datatypes, lengths, precision/scale, nullability, PK/unique/FK
   constraints — including `ALTER TABLE ADD CONSTRAINT` and `CREATE UNIQUE INDEX`).
   Every `.sql` file is then validated against that model as you type. The data flow
   `CREATE TEMP TABLE → INSERT → MERGE → target table` is resolved from the MERGE's `ON`
   clause, `UPDATE SET` assignments and `INSERT` column/value pairs — by name, never by
   blind position. When a staging table has no MERGE in the file, a configurable
   `tmp_`/`temp_`/`stg_` naming heuristic maps it to its target.
2. **Database mode (optional).** With a PostgreSQL or Oracle connection configured, live
   metadata (`information_schema` / `ALL_*` dictionary views) overlays the repository
   schema, and validation additionally runs the read-only dry run described above.
   **Only read-only, parameterized `SELECT` statements are ever issued.** The plugin never
   executes INSERT/UPDATE/DELETE/MERGE/DROP/ALTER/TRUNCATE.

If the schema cannot be resolved, validation is skipped with a single notice — valid SQL is
never flagged just because metadata is unavailable.

## Configuration

**Settings | Tools | Liquibase Sudarshan**

- Global DDL directory, global static dataset directory, country root directory (absolute or
  project-relative; use *Auto-detect repository layout*), country code (empty = all countries)
- Database validation: JDBC URL, user, password (stored in the IDE PasswordSafe), schema,
  *Test connection*
- Per-check toggles: VARCHAR length, numeric ranges, NULL constraints, duplicates, MERGE
  mappings, foreign keys, unused staging columns, naming heuristic, Liquibase structure

## Usage

- **As you type** — the inspection `Liquibase | Liquibase SQL validation` runs automatically
  on every `.sql` file; problems appear like native IntelliJ inspections with tooltips and
  quick fixes (Alt+Enter).
- **Right-click a file or directory → Validate Liquibase SQL** — validates the selection in
  the background.
- **Tools → Liquibase Sudarshan → Validate Liquibase Repository** (also in the project view
  context menu) — scans global DDL, global staticdatasetup and the configured country's
  staticdatasetup, then opens the **Liquibase Validation** tool window on the **right
  stripe** (chakra icon). After each run the stripe icon carries a status dot — **red**
  (errors), **amber** (warnings) or **green** (clean) — like the IDE's own datasource icons.
  - **Validation tab**: errors (red) and warnings (amber) each on their own line;
    double-click to jump to the exact offset, or right-click for **Jump to Source /
    Copy Message / Validate Repository Again / Configure**.
  - **Datasource tab** — an SQL Developer-style, strictly **read-only** navigator:
    connection status lamp (grey/amber/green/red), **Connect / Test**, **Refresh
    Metadata**, **Configure…**, `DATABASECHANGELOG` execution entries, and the full
    schema tree — **Tables** (Columns with types/NOT NULL/PK/DEFAULT, Constraints with
    FK targets, Indexes with uniqueness, and a **Data** node: double-click for a
    first-50-rows grid preview), **Sequences** (increment/last number) and **Views**.
    Every query is a SELECT on a server-enforced READ ONLY transaction; the grid is
    non-editable — there is no write path anywhere.

## Commit, push and database dry run

- **Before commit** — every `.sql` file in the commit is validated; on errors a dialog lets
  you cancel the commit or proceed deliberately (toggle: *Validate SQL files before commit*).
- **Before push** — every `.sql` file touched by the outgoing commits is validated again;
  when a datasource is configured the **dry run** below is included. On errors you can
  cancel the push (toggle: *Validate SQL files before push*). Requires the bundled Git
  plugin; both hooks are optional dependencies, so the plugin still loads without VCS.
- **Database dry run** (needs *Enable database validation* + a JDBC URL — PostgreSQL or
  Oracle): strictly read-only, it reports
  - which changesets are **pending** (not in `DATABASECHANGELOG`, or `runAlways`),
  - `--precondition-sql-check` SELECTs evaluated live: mismatches that would make Liquibase
    **HALT** are reported as errors before you ever run `liquibase update`,
  - foreign-key values with no matching row in the live database,
  - direct INSERTs whose primary key already exists (would fail — use MERGE),
  - a **data preview** (like SQL Developer): every statically-known row is classified as
    *INSERT*, *UPDATE* or *CONFLICT* against the live data, grouped by changeset in
    execution order, in the **Liquibase Validation** tool window — double-click a row to
    jump to its value.
  Checks and the preview cover only changesets that would actually run (pending,
  `runAlways`, `runOnChange`). The dry run executes during *Validate Liquibase Repository*,
  the file action, and the pre-push hook — SELECTs only, on a server-enforced read-only
  transaction that is rolled back on close.

## Database drivers (not bundled — keeps the plugin ~20x smaller)

The plugin download is only ~0.5 MB because the JDBC drivers are resolved on demand,
in this order:

1. **One-click download** — when you first connect, the plugin offers to download the
   driver for your JDBC URL (PostgreSQL ~1 MB / Oracle ~7 MB) from Maven Central,
   verifies it against a **pinned SHA-256 checksum**, and caches it in
   `~/.liquibase-sudarshan/drivers`. Also available as the *Download driver* button in
   settings.
2. **Custom driver JAR** — air-gapped environments can point *Driver JAR (optional)* in
   settings at their own jar.
3. The CLI (`validateRepo`) ships its drivers on its own Gradle classpath — no setup.

## Dialects

The parser and datatype model understand **PostgreSQL** and **Oracle** syntax:

- Oracle types: `VARCHAR2(n [CHAR|BYTE])`, `NVARCHAR2`, `NUMBER(p[,s])` (validated as
  DECIMAL precision/scale), `CHAR(1)` flags, `CLOB`/`NCLOB`, `BINARY_FLOAT/DOUBLE`
- `CREATE GLOBAL TEMPORARY TABLE … ON COMMIT DELETE ROWS` staging tables
- Oracle `MERGE … USING … ON (t.code = s.code)` with the parenthesized ON clause
- `SYSDATE`, `SYSTIMESTAMP` and sequence `my_seq.NEXTVAL/CURRVAL` values are recognized and
  never produce false positives
- Optional Oracle semantics: **treat empty string '' as NULL** (settings checkbox /
  `--oracle` CLI flag) flags `''` inserted into NOT NULL columns

A ready-made Oracle test repository lives in
[../oracle-liquibase-testrepo](../oracle-liquibase-testrepo/).

## Command-line validation (CI / VS Code)

The same validation engine runs headless — no IDE, no database:

```bash
./gradlew validateRepo -Prepo="C:\path\to\repo" -PrepoArgs="--oracle --country=SG"
```

Findings are printed as `file:line:col: severity: message` (gcc style), with a non-zero exit
code when errors are found — ready for CI pipelines and editor problem matchers. Flags:
`--oracle`, `--country=XX`, `--fail-on-warnings`, `--ddl=<dir>`, `--data=<dir>` (defaults
auto-detect `*/global/ddl`, `*/staticdatasetup`, `countries/*`). The Oracle test repository
ships a `.vscode/tasks.json` that wires this into VS Code's Problems panel.

The read-only database dry run also runs headless when a datasource is passed:
`--db-url=<jdbc url> --db-user=<user> --db-password=<pass>` (or env
`LIQUIBASE_SUDARSHAN_DB_PASSWORD`) `[--db-schema=<schema>]` — pending changesets,
live precondition checks, FK/PK probes and `preview: INSERT|UPDATE|CONFLICT …` lines.
See the Oracle test repo's README for a one-command free Oracle 23ai Docker datasource.

## Building and running

Requirements: JDK 21 (Gradle toolchain), internet access for the first build.

```bash
./gradlew build      # compile + all unit and platform tests + plugin ZIP
./gradlew runIde     # launch a sandbox IntelliJ IDEA with the plugin installed
./gradlew test       # tests only
./gradlew buildPlugin  # produces build/distributions/liquibase-sudarshan-0.0.1.zip
```

### Installing the built plugin

1. `./gradlew buildPlugin`
2. In IntelliJ IDEA: **Settings | Plugins | ⚙ | Install Plugin from Disk…**
3. Choose `build/distributions/liquibase-sudarshan-0.0.1.zip`, restart the IDE.
4. Open your database repository, configure **Settings | Tools | Liquibase Sudarshan**.

**IDE compatibility:** IntelliJ IDEA (Community or Ultimate) **2023.2 and every newer
version** — the plugin declares no upper build bound, uses only long-stable platform APIs
and ships JVM 17 bytecode, so new IDE releases (2024.x, 2025.x, 2026.x, …) install it
without an update. Compatibility is checked with the JetBrains Plugin Verifier
(`./gradlew verifyPlugin`).

## Publishing to JetBrains Marketplace

One-time setup: create a JetBrains account, open <https://plugins.jetbrains.com>, and add a
vendor profile. Then either upload manually (first release must be manual):
**Upload plugin** → select `build/distributions/liquibase-sudarshan-0.0.1.zip`, choose
license and category (*Tools integration*), submit for review (~2 business days).
Subsequent releases can be automated: generate a **Personal Access Token** on the
Marketplace (profile → My Tokens), then

```bash
set JETBRAINS_MARKETPLACE_TOKEN=perm:xxxxxxxx   # PowerShell: $env:JETBRAINS_MARKETPLACE_TOKEN="perm:..."
./gradlew publishPlugin
```

Optional plugin signing (recommended): generate a key pair + certificate, point
`PLUGIN_CERTIFICATE_CHAIN_FILE`, `PLUGIN_PRIVATE_KEY_FILE`, `PLUGIN_PRIVATE_KEY_PASSWORD`
at them (see `signing {}` in `build.gradle.kts`), and `./gradlew signPlugin` runs
automatically before publishing.

### Trying the sample repository

Open the [sample-repository](sample-repository/) folder as a project (or point the settings
at it). It contains:

- `database/global/ddl/` — `account_type`, `customer_type`, `account` definitions
- `database/global/staticdatasetup/account_type.sql` — a fully **valid** staging+MERGE script
- `database/countries/IN/.../account_type.sql` — a valid country dataset
- `database/countries/SG/.../account_type.sql` — **intentionally broken**: oversized staging
  types, oversized value, NULL into NOT NULL, duplicate primary keys, unused staging column
- `database/countries/US/.../customer_type.sql` — **intentionally broken**: SMALLINT
  overflow, invalid UUID, INTEGER overflow, unknown column

With default settings (`database/global/ddl` etc.) the SG and US files light up with the
errors described in their comments; the global and IN files stay clean.

## Architecture

```text
IntelliJ layer          inspection/  quickfix/  settings/  plugin/  schema/SchemaIndexService
                              │ (reads snapshots, maps problems to editor ranges)
Pure-JVM core           sql/ (lexer+parser, offset-tracking AST)
                        liquibase/ (changeset metadata parser)
                        schema/ (TableSchema model, DdlSchemaBuilder, SchemaProvider)
                        validation/ (ValidationEngine + Datatype/TempTable/Insert/Merge/
                                     Duplicate validators, DataFlow mapping)
                        database/ (read-only JDBC metadata provider + FK prober)
```

The core has zero IntelliJ dependencies and is covered by plain JUnit 5 tests; the IntelliJ
layer is covered by `BasePlatformTestCase` integration tests (highlight ranges, messages,
quick fixes, no false positives). Schema snapshots are cached and invalidated by a VFS
listener plus a settings modification counter — nothing is re-parsed on every keystroke, and
no blocking work runs on the EDT.

## Safety

Static validation never touches a database. Database mode issues only read-only metadata
queries and parameterized `SELECT … LIMIT 1` existence probes, on a connection opened with
`readOnly=true`. Nothing is ever written, and no destructive statement is ever executed.
