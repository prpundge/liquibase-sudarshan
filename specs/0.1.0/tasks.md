# Liquibase Sudarshan 0.1.0 — Task Breakdown

> Each phase leaves the build green and shippable. Acceptance criteria are testable.
> Effort: S < half day, M ~ a day, L = multi-day (relative, single developer).

## Phase A — Configuration & layout model (S/M)
- [ ] A1. `ReleaseLayout` model: stages 1–6 with directories, environment names, country
      list, ordering rule. Defaults match requirements §1.
- [ ] A2. `.liquibase-sudarshan.yml` loader (flat key:value parser, zero deps; unknown
      keys warn). IDE settings page shows "configured by repository file" when present.
- [ ] A3. File-ordering comparator: numeric prefix numerically, then case-insensitive
      name. Property-tested against plain alphabetical to document divergence.
- **Accept**: same manifest order from CLI and IDE for the sample repo; yml overrides
  respected; no yml = today's behavior.

## Phase B — Release manifest & sequential schema accumulation (L)
- [ ] B1. `ReleaseAssembler`: (country, env, layout) → ordered `[Stage → files]`, skipping
      stage 6 for PROD.
- [ ] B2. `AccumulatingSchema`: starts empty (or from live DB overlay when connected),
      applies each DDL file's CREATE/ALTER/INDEX in file order; exposes point-in-time
      SchemaProvider to validate each subsequent file.
- [ ] B3. "Used before created" / "defined twice" / "referenced never-created" findings
      anchored to the offending statement, naming the file that defines it later (if any).
- [ ] B4. Manifest rendering: text (CLI, archivable) and tool-window tree (IDE), with
      per-changeset RUN/SKIP/HALT/BLOCKED when a datasource is attached (reuses dry run).
- **Accept**: sample repo with a deliberately re-ordered DDL file produces the
  "used before created" error pointing at both statements; correct order is clean.

## Phase C — Whole-release cross-file checks (M)
- [ ] C1. Duplicate changeset ids across the full release unit (error, both locations).
- [ ] C2. Cross-file duplicate keys: direct-INSERT collisions = error; MERGE-upsert
      overlap global→country = info ("row updated by later stage").
- [ ] C3. Country/environment leakage: files under other countries' or other envs'
      directories selected into the unit = error.
- **Accept**: global+SG sample with the same `author:id` in two files fails with both
  file:line locations; MERGE overlap reported as info not error.

## Phase D — Environment policy guardrails (M)
- [ ] D1. Destructive-statement detector (DROP non-temp / TRUNCATE / DELETE·UPDATE
      without WHERE) on the parsed AST — no regex heuristics.
- [ ] D2. Policy engine keyed by environment with severity table from the yml;
      `--approved-destructive <ticket>` marker comment lifts the block (kept in report).
- [ ] D3. PROD rules: stage-6 exclusion error, rollback-required, failOnError:false and
      runAlways warnings per requirements R4.
- **Accept**: same release passes for SIT and fails for PROD on an unapproved TRUNCATE;
  adding the marker comment turns it into a reported-but-passing entry.

## Phase E — CLI `--simulate` + Jenkins template (M)
- [ ] E1. `--simulate --country=CC --env=ENV` wiring: R1–R4 + existing per-file checks,
      manifest to stdout, non-zero exit on errors; composes with `--db-url…`,
      `--github`, `--patch`, `--fail-on-warnings`.
- [ ] E2. `jenkins/Jenkinsfile.validate` stage template (checkout → cliJar → simulate →
      only then liquibase update), documented in README.
- [ ] E3. GitHub Actions workflow gains a simulate job for the sample repo (annotations).
- **Accept**: Jenkins template runs green on the sample repo for SIT and blocks the
  PROD-destructive fixture; manifest file archived as build artifact.

## Phase F — IDE "Simulate Release…" action (M)
- [ ] F1. Dialog: country + environment pickers (values from yml/layout), remembers last
      choice per project.
- [ ] F2. Manifest + findings rendered in the existing tool window (new "Release" node);
      double-click navigation; stripe status dot reflects the simulation result.
- **Accept**: platform test drives the action headlessly and asserts the manifest node
  content and a navigation target.

## Phase G — Samples, tests, docs (M)
- [ ] G1. Extend both sample repos with `update/` and `environments/SIT|UAT/` stages,
      including one intentional order violation, one cross-file duplicate id, one PROD
      policy breach — all documented in file headers (E-numbering convention).
- [ ] G2. Unit tests: ordering comparator, accumulating schema, cross-file checks,
      destructive detector, policy matrix, yml loader. Platform tests: simulate action.
- [ ] G3. Docs: README release-simulation section, requirements/tasks linked, Jenkins
      how-to, updated spec.md for 0.1.0.
- **Accept**: full suite green; CLI simulate output on the sample repo matches a
  committed golden manifest (deterministic).

## Open confirmations (block only Phase A defaults, not the design)
1. Folder names: `update/` and `environments/SIT|UAT` — correct? (A1/A2)
2. Ordering: alphabetical with numeric prefixes — matches the Jenkins job? (A3)
3. Are environment folders per-country underneath (`environments/SIT/countries/SG/`)?
4. Does PROD ever get environment-specific SQL, or is exclusion always correct?
5. How does Jenkins invoke files today — one Liquibase changelog per file, a generated
   master changelog, or raw sqlplus? (Affects checksum/ordering fidelity in R1.)
