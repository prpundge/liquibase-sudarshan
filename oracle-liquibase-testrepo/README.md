# Oracle Liquibase Test Repository

A realistic **Oracle** Liquibase repository for testing the
[Liquibase Sudarshan](../liquibase-sudarshan/) IntelliJ plugin — and its command-line validator from
**VS Code**. It uses Oracle syntax throughout: `VARCHAR2(n CHAR)`, `NUMBER(p,s)`, `CHAR(1)`
Y/N flags, `CREATE GLOBAL TEMPORARY TABLE … ON COMMIT DELETE ROWS`, parenthesized
`MERGE … ON (…)`, `SYSDATE`/`SYSTIMESTAMP` and sequence `NEXTVAL` values.

```text
changelog/changelog-master.xml       Liquibase master changelog (includes all files below)
liquibase.properties                 Oracle JDBC settings (only needed for real `liquibase update`)
database/
├── global/
│   ├── ddl/                         account_type, customer_type (+sequence), account
│   └── staticdatasetup/             VALID upserts (GTT + MERGE, SYSDATE, seq.NEXTVAL)
└── countries/
    ├── IN/staticdatasetup/          VALID country dataset
    ├── SG/staticdatasetup/          INTENTIONALLY BROKEN (lengths, NULL, duplicates — E1–E7 + W1)
    └── US/staticdatasetup/          INTENTIONALLY BROKEN (NUMBER precision/scale, MERGE mapping,
                                     missing NOT NULL column, bad TIMESTAMP, duplicate changeset — E1–E10 + W2)
```

The valid files must produce **zero findings**; every expected finding in the broken files is
documented at the top of the file (`E1…`, `W…`).

> **Prerequisite:** this folder must sit **next to the `liquibase-sudarshan` checkout** — the VS
> Code tasks and the commands below call `../liquibase-sudarshan/gradlew`. If it lives elsewhere,
> adjust the paths in `.vscode/tasks.json` accordingly.

## Testing in IntelliJ IDEA

1. Build/launch the plugin: `cd ../liquibase-sudarshan` and either `gradlew runIde` (sandbox IDE)
   or `gradlew buildPlugin` + *Settings | Plugins | Install Plugin from Disk*.
2. Open **this folder** (`oracle-liquibase-testrepo`) as a project.
3. The default plugin settings already match this layout (`database/global/ddl`,
   `database/global/staticdatasetup`, `database/countries`) — verify under
   **Settings | Tools | Liquibase Sudarshan** and tick
   **“Oracle: treat empty string '' as NULL (NOT NULL violations)”** to also get finding *E6*
   in the SG file.
4. Open `database/countries/SG/staticdatasetup/account_type.sql`:
   - `VARCHAR2(50 CHAR)` is highlighted with *“…exceeds VARCHAR(15)…”* — press **Alt+Enter**
     for the **Change VARCHAR(50) to VARCHAR(15)** quick fix.
   - The oversized literal, the `NULL`, the duplicate `'SAVINGS'` and `'YES'` are highlighted
     on the exact values.
5. Open the US file for NUMBER precision/scale and MERGE mapping errors.
6. Right-click the `database` folder → **Validate Liquibase SQL**, or run
   **Tools | Liquibase Sudarshan | Validate Liquibase Repository** for the report tool window
   (double-click a finding to navigate).

The global and IN files must show **no** errors or warnings.

## Testing in VS Code

The IntelliJ plugin itself cannot run inside VS Code, but its validation engine ships with a
CLI that this repo wires up as a VS Code task (requires JDK 21 on the PATH):

1. Open **this folder** in VS Code.
2. **Terminal → Run Task… → “Liquibase Sudarshan: validate repository”**
   (or “validate SG only” for a single country).
3. Findings appear in the terminal as `file:line:col: severity: message` and are picked up by
   the task's problem matcher, so they also show in the **Problems** panel and as squiggles —
   click any entry to jump to the exact line.

The task essentially runs (`gradlew.bat` on Windows; `--console=plain --quiet` added for
clean output):

```bash
../liquibase-sudarshan/gradlew -p ../liquibase-sudarshan validateRepo \
    -Prepo=<this folder> -PrepoArgs=--oracle --console=plain --quiet
```

CLI flags: `--oracle` (empty string = NULL semantics), `--country=XX` (limit country
datasets), `--fail-on-warnings`, `--ddl=<dir>` / `--data=<dir>` to override auto-detection.
Exit code is non-zero when errors are found, so the same command works in CI.

## Free test datasource (Oracle 23ai Free in Docker)

Oracle's free edition runs locally in one command — this is the easiest way to try the
database dry run and data preview:

```powershell
docker run -d --name liquibase-sudarshan-oracle -p 1521:1521 `
    -e ORACLE_PASSWORD=admin123 -e APP_USER=app_user -e APP_USER_PASSWORD=app_pass `
    gvenzl/oracle-free:23-slim
docker logs -f liquibase-sudarshan-oracle   # wait for "DATABASE IS READY TO USE!"
```

Apply this repo's DDL and seed a row (so the preview shows both UPDATE and INSERT):

```powershell
Get-Content database\global\ddl\account_type.sql, database\global\ddl\customer_type.sql, `
    database\global\ddl\account.sql -Raw |
    docker exec -i liquibase-sudarshan-oracle sqlplus -s app_user/app_pass@localhost/FREEPDB1
"INSERT INTO account_type (code, name, description, active_flag) VALUES ('SAVINGS','Old','seed','Y');
COMMIT;
EXIT;" | docker exec -i liquibase-sudarshan-oracle sqlplus -s app_user/app_pass@localhost/FREEPDB1
```

Connection settings for the plugin (Settings | Tools | Liquibase Sudarshan) or CLI:

```text
JDBC URL:  jdbc:oracle:thin:@//localhost:1521/FREEPDB1
User:      app_user     Password: app_pass     Schema: (leave empty = app_user)
```

`docker stop`/`docker start liquibase-sudarshan-oracle` pauses/resumes it; `docker rm -f`
removes it. (Alternative without Docker: Oracle Cloud "Always Free" Autonomous Database —
free forever, but needs an Oracle account and wallet-based JDBC setup.)

## How the plugin compares SQL files with the datasource

1. **Baseline** is always the repository DDL (`database/global/ddl`) — works offline.
2. When database validation is enabled and connected, **live metadata overlays** the file
   baseline (Oracle `ALL_TAB_COLUMNS`/`ALL_CONSTRAINTS` dictionary views) — so drift between
   files and the real schema is validated against reality.
3. The **dry run** then compares content: changesets vs `DATABASECHANGELOG` (pending or
   not), `--precondition-sql-check` SELECTs evaluated live, FK values vs existing rows, and
   every statically-known row classified as **INSERT / UPDATE / CONFLICT** — visible in the
   Datasource/Validation tool window tabs, the pre-push dialog, or headless:

```powershell
..\liquibase-sudarshan\gradlew -p ..\liquibase-sudarshan validateRepo "-Prepo=$PWD" `
    "-PrepoArgs=--oracle --db-url=jdbc:oracle:thin:@//localhost:1521/FREEPDB1 --db-user=app_user --db-password=app_pass"
```

Everything is read-only — the dry run runs SELECTs in a server-enforced READ ONLY
transaction and never modifies the database.

## Running Liquibase for real (optional)

Static validation never needs a database. If you want to actually apply the changelog to an
Oracle instance, fill in `liquibase.properties` and run:

```bash
liquibase update --context-filter="GLOBAL,IN"
```

The context filter selects the country datasets (`GLOBAL`, `IN`, `SG`, `US`). Note the SG/US
files are intentionally broken — Liquibase Sudarshan exists precisely to stop you before running
them (the US file even contains a duplicate changeset id, which Liquibase rejects at parse
time).

Two Oracle notes baked into the valid files:

- Staging GTTs are dropped by a preceding `runAlways:true failOnError:false` changeset, so
  the `CREATE GLOBAL TEMPORARY TABLE` never collides (ORA-00955) on repeat deployments, and
  each country uses its own staging table name.
- The `INSERT` → `MERGE` flow through an `ON COMMIT DELETE ROWS` GTT relies on Liquibase's
  default one-transaction-per-changeset execution (autocommit off). Never replay these
  statements one by one with autocommit enabled — each commit would purge the GTT and the
  MERGE would silently merge zero rows.
