# Liquibase Sudarshan 0.1.0 — Task Breakdown

> Each phase leaves the build green and shippable. Acceptance criteria are testable.
> Effort: S < half day, M ~ a day, L = multi-day (relative, single developer).
> Status legend: [x] delivered · [~] partially delivered (note inline) · [ ] open.

## Phase A — Configuration & layout model (S/M)
- [x] A1. `ReleaseConfig` model (`release/ReleaseConfig.kt`): stages 1–6 with directories,
      environment names, PROD name, policy severities. Defaults match requirements §1.
- [~] A2. `.liquibase-sudarshan.yml` loader (flat key:value parser, zero deps; unknown
      keys/values warn). *Open: IDE settings page does not yet show a "configured by
      repository file" hint.*
- [x] A3. File-ordering comparator `SqlFileOrder`: numeric prefix numerically, then
      case-insensitive name. Unit-tested against plain alphabetical.
- **Accept**: ✅ same manifest order from CLI and IDE (same `ReleaseAssembler`); yml
  overrides respected; no yml = defaults (`ReleaseSimulatorTest`).

## Phase B — Release manifest & sequential schema accumulation (L)
- [x] B1. `ReleaseAssembler`: (country, env, config) → ordered manifest, stage 6 skipped
      for PROD, optional `environments/<ENV>/countries/<CC>/` subfolder included.
- [x] B2. Accumulating schema: starts empty (or seeded READ-ONLY from `--db-url`),
      applies each file's CREATE/ALTER/UNIQUE INDEX/DROP in manifest order; every file
      validates against a point-in-time `MapSchemaProvider` snapshot.
- [x] B3. "Used before created" (DML **and FOREIGN KEY targets**), "defined twice",
      "ALTER before create" errors, anchored to the statement and naming the step/file
      that defines the table later.
- [~] B4. Manifest rendering: CLI text (archivable) and tool-window tree node. *Open:
      per-changeset RUN/SKIP/HALT/BLOCKED merge with the datasource dry run — today the
      dry run remains a separate per-file action.*
- **Accept**: ✅ re-ordered DDL produces "used before its DDL runs" naming the later
  step; correct order is clean (`ReleaseSimulatorTest`, sample repos renamed to
  `001_…/002_…/003_…` so the FK order is actually correct).

## Phase C — Whole-release cross-file checks (M)
- [x] C1. Duplicate changeset ids across the release unit = error naming the earlier step.
- [x] C2. Cross-file duplicate keys: direct-INSERT collision = error; MERGE-upsert
      overlap global→country = info ("will UPDATE that row").
- [~] C3. Country/environment leakage: prevented **by construction** (the assembler only
      selects the chosen country's and environment's directories); no separate diagnostic
      for stray files.
- **Accept**: ✅ same `author:id` in two files fails naming both; MERGE overlap is info
  (`ReleaseSimulatorTest`).

## Phase D — Environment policy guardrails (M)
- [x] D1. Destructive-statement detector on the parsed AST: new `DROP`/`TRUNCATE`/
      `UPDATE` statements + `DELETE`/`UPDATE` without top-level `WHERE`
      (`scanForWhere`, subquery-safe); staging-named tables (`tmp_/temp_/stg_/staging_`)
      exempt.
- [x] D2. Policy engine keyed by environment, severities from the yml;
      `--approved-destructive <ticket>` changeset marker downgrades to INFO (kept in
      the report), with typo detection like every other directive.
- [x] D3. PROD rules: stage-6 excluded by construction; rollback-required = ERROR;
      `failOnError:false` on DML and `runAlways:true` on DML warnings per R4.
- **Accept**: ✅ same release warns for SIT and errors for PROD on an unapproved
  destructive statement; the marker turns it into INFO (`ReleaseSimulatorTest` +
  oracle repo E2E: SIT 1 warning → PROD 2 errors).

## Phase E — CLI `--simulate` + Jenkins template (M)
- [x] E1. `--simulate --country=CC --env=ENV`: R1–R4 + all existing per-file checks,
      manifest to stdout, exit 1 on errors; composes with `--db-url…` (read-only base
      schema), `--github`, `--patch`, `--fail-on-warnings`, `--oracle`.
- [x] E2. `jenkins/Jenkinsfile.validate` stage template: simulate gate **before**
      `liquibase update`, credentials from the Jenkins store, exit-code contract
      documented.
- [x] E3. GitHub Actions: strict simulate job on the PostgreSQL sample (must stay green)
      + annotation-only simulate on the Oracle repo.
- **Accept**: ✅ sample repo IN/SIT: 7 files, 0 errors, "release would EXECUTE";
  oracle IN/PROD blocks on the unapproved destructive fixture.

## Phase F — IDE "Simulate Release…" action (M)
- [~] F1. Dialog with country + environment pickers (countries discovered from the repo,
      environments from the yml; country preselected from settings). *Open: the last
      environment choice is not yet persisted per project.*
- [x] F2. Manifest + findings rendered in the tool window ("Release manifest" node),
      double-click navigation, stripe status dot reflects the result, notification with
      the EXECUTE/FAIL verdict.
- **Accept**: [~] covered by unit tests of the shared engine; *open: a headless platform
  test driving the action itself.*

## Phase G — Samples, tests, docs (M)
- [x] G1. Both sample repos gained `update/` and `environments/SIT|UAT/` stages; the
      oracle repo carries documented policy fixtures (unapproved UPDATE-without-WHERE,
      `--approved-destructive DB-1234` DROP) and a commented sample
      `.liquibase-sudarshan.yml`; DDL files renamed `001_/002_/003_` to demonstrate the
      ordering convention (FK-correct order).
- [x] G2. Unit tests: ordering comparator, yml loader, assembler order + PROD exclusion,
      used-before-created (DML + FK), defined-twice, ALTER-before-create,
      DROP-then-recreate, cross-file duplicate id, direct-INSERT collision, policy
      matrix SIT/UAT/PROD, approved marker, new DML statement parsing
      (193 tests total, all green).
- [x] G3. Docs: README release-simulation + Jenkins section, requirements assumptions
      resolved (A1/A2 user-confirmed, A3/A4 defaults, all configurable), this file.
- **Accept**: ✅ full suite green; simulate output deterministic (manifest ordering is
  pure `SqlFileOrder`; nothing depends on wall-clock or hash order).

## Confirmations — resolved 2026-08-03
1. ✅ **User confirmed**: folder names `update/` and `environments/SIT|UAT`.
2. ✅ **User confirmed**: alphabetical ordering with numeric prefixes (Jenkins-matching).
3. ⚙ Default accepted: flat environment folders, optional `environments/<ENV>/countries/<CC>/`.
4. ⚙ Default accepted: PROD never receives environment-specific SQL.
5. ⚙ Default accepted: one Liquibase formatted-SQL changelog per file.
   Everything above is configurable via `.liquibase-sudarshan.yml`.
