# Liquibase Sudarshan 0.1.0 — Release Execution Simulation — Requirements

> Written from the DBA's chair: I own these databases, and every failed Liquibase run in
> SIT/UAT/PROD is my incident, my rollback, and my audit finding. The tool's job is that
> **a release which passes validation does not fail when Jenkins executes it.**
> Open-source, lightweight (no new runtime dependencies, plugin ZIP stays under ~1 MB),
> everything works offline; the read-only datasource browser is feature-frozen as-is.

## 1. The execution model being protected

A **release unit** is: a branch checkout × one country × one environment (SIT / UAT / PROD).
Jenkins executes SQL files **one by one, in a fixed stage order, fail-fast** — the first
failing statement aborts the run and leaves the release half-applied. Stages:

| Stage | Directory (default, configurable) | Content | Runs in |
|---|---|---|---|
| 1 | `database/global/ddl/` | DDL — tables, constraints, indexes, sequences | all envs |
| 2 | `database/global/staticdatasetup/` | global reference DML (staging + MERGE) | all envs |
| 3 | `database/countries/<CC>/staticdatasetup/` | country reference DML | all envs |
| 4 | `database/global/update/` | one-off corrective DML (UPDATE/DELETE fixes) — **kept outside staticdatasetup** | all envs |
| 5 | `database/countries/<CC>/update/` | country one-off corrective DML | all envs |
| 6 | `database/environments/<ENV>/` (+ optional `…/<ENV>/countries/<CC>/`) | environment-specific SQL (test users, SIT/UAT data) | SIT/UAT only — **never PROD** |

Within a stage, files execute in **deterministic name order**: numeric prefix first
(`001_`, `010_`…), then case-insensitive alphabetical — this MUST match the Jenkins sort.

**ASSUMPTIONS — RESOLVED** (2026-08-03, all configurable via `.liquibase-sudarshan.yml`):
- A1: ✅ **CONFIRMED by user** — corrective-DML folders are named `update/` (global and per-country).
- A2: ✅ **CONFIRMED by user** — environment folders are `database/environments/SIT/`, `…/UAT/`; PROD has no folder.
- A3: ⚙ default accepted — files order alphabetically, numeric prefixes compared numerically (`SqlFileOrder`).
- A4: ⚙ default accepted — each file is a Liquibase formatted-SQL changelog; PROD never receives stage-6 SQL.

## 2. Functional requirements

### R1 — Release manifest (the exact run, before the run)
Given (country, environment), produce the ordered list of every file and changeset that
Jenkins would execute, stage by stage, with per-changeset status
(RUN / SKIP — already in DATABASECHANGELOG / HALT — failing precondition / BLOCKED —
after a HALT). The manifest is printable (CLI), viewable (IDE tool window), and archivable
as a plain-text artifact for the change ticket.

### R2 — Sequential schema accumulation
Each file is validated against the schema **as it exists at that point of the simulated
run**: stage-1 DDL is applied file-by-file to an in-memory schema; later files see tables,
columns, and constraints only if an *earlier* file created them. This catches, before any
environment does:
- a DML file referencing a table whose DDL comes later in the order or not at all;
- ALTER/INDEX statements targeting not-yet-created tables;
- two DDL files defining the same table (later silently wins today — becomes an error);
- update-stage scripts assuming reference rows that static datasetup only creates later.

### R3 — Whole-release cross-file checks
Today's validation is per-file. Per release unit, additionally enforce:
- changeset `author:id` unique across **all** files in the release (Liquibase aborts on
  duplicates at parse time — currently only caught within one file);
- duplicate PK/unique **data** across files: a country dataset re-inserting a key the
  global dataset already inserts (MERGE makes this an update — report as info; a direct
  INSERT collision is an error);
- environment/update files referencing countries other than the selected one (leakage).

### R4 — Environment policy guardrails (the DBA's veto)
Policies evaluated per target environment, severities configurable, defaults:
- **PROD**: stage-6 files excluded — any file under `environments/` selected for PROD is
  an ERROR; destructive statements (`DROP` of non-temp objects, `TRUNCATE`,
  `DELETE`/`UPDATE` without a `WHERE`) are ERRORS unless the changeset carries an explicit
  marker comment (`--approved-destructive <ticket>`); every changeset MUST have a rollback;
  `failOnError:false` on DML is a WARNING.
- **SIT/UAT**: destructive statements WARN; missing rollback WARNS.
- All environments: `runAlways:true` on DML WARNS (repeat execution risk).

### R5 — One shared configuration (config-as-code, lightweight)
A single optional file at the repository root — `.liquibase-sudarshan.yml` (flat
`key: value`, parsed without new dependencies) — defines: stage directories, environment
names, country list, ordering rule, and policy severities. IDE, CLI, VS Code and Jenkins
all read the same file; IDE settings become personal overrides. No file = current defaults.

### R6 — CLI simulation & Jenkins gate
`--simulate --country=SG --env=SIT` runs R1–R4 (plus all existing per-file validation) in
release order and exits non-zero on any ERROR — inserted as a Jenkins stage **before**
`liquibase update`, so a failing release never reaches the database. Ship a documented
`Jenkinsfile` stage template. Existing `--db-url` flags compose (dry run against the
target env's DB: pending/HALT/preview per manifest). `--github`/`--patch` compose for PRs.

### R7 — IDE parity
Action **"Simulate Release…"** (choose country + environment) renders the manifest and
findings in the existing tool window — same engine, zero drift between IDE and Jenkins.

## 3. Non-functional requirements
- Pure static analysis by default; DB access remains strictly read-only and optional.
- No new runtime dependencies; simulation of a 500-file release completes in seconds.
- Deterministic: identical inputs produce byte-identical manifests (diff-able artifacts).
- Every requirement covered by automated tests, including a sample repo exercising all
  six stages with intentional order violations and policy breaches.

## 4. Explicitly out of scope
Executing SQL (Liquibase/Jenkins do that); replacing Liquibase; scheduling; SQL Developer
feature parity (browser stays as-is); telemetry/licensing (open-source, lightweight).
