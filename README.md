# Liquibase Sudarshan

Validation tooling for Liquibase SQL repositories — catch schema, datatype, and data errors
**before** they reach the database.

| Folder | What it is |
|---|---|
| [liquibase-sudarshan/](liquibase-sudarshan/) | IntelliJ IDEA plugin (2023.2+ Community/Ultimate, no upper bound): editor inspections with quick fixes, pre-commit/pre-push validation, read-only database dry run with INSERT/UPDATE data preview (PostgreSQL + Oracle), right-stripe tool window with datasource browser, headless CLI for CI/VS Code |
| [liquibase-sudarshan/sample-repository/](liquibase-sudarshan/sample-repository/) | PostgreSQL sample Liquibase repository (valid + intentionally broken files) |
| [oracle-liquibase-testrepo/](oracle-liquibase-testrepo/) | Oracle test repository: global DDL + country datasets, documented expected findings, VS Code tasks, free Oracle 23ai Docker datasource instructions |
| [vscode-liquibase-sudarshan/](vscode-liquibase-sudarshan/) | VS Code extension (1.60+ — years of versions): inline squiggles + Problems panel via the standalone CLI jar (`gradlew cliJar`), runs on save, dry-run capable; ships as a 6 KB `.vsix` |
| [specs/0.0.1/](specs/0.0.1/) | Spec-kit documentation for v0.0.1: [spec.md](specs/0.0.1/spec.md) (all features + acceptance criteria), [plan.md](specs/0.0.1/plan.md) (architecture), [tasks.md](specs/0.0.1/tasks.md) (delivery checklist + multi-IDE roadmap), [publishing.md](specs/0.0.1/publishing.md) (Marketplace release steps) |
| [specs/0.1.0/](specs/0.1.0/) | v0.1.0 **release execution simulation**: [requirements.md](specs/0.1.0/requirements.md) (DBA-view requirements — a release that passes validation must not fail when Jenkins runs it in SIT/UAT/PROD) and [tasks.md](specs/0.1.0/tasks.md) (delivery status) |
| [jenkins/](jenkins/) | `Jenkinsfile.validate` — pipeline template running `--simulate --country=CC --env=ENV` as a gate **before** `liquibase update` |
| [.github/workflows/](.github/workflows/) | CI: build + tests + repository validation with **inline PR annotations** (`--github`); combine with `--patch=pr.diff` for changed-lines-only PR review; release simulation jobs for both sample repos |

Quick start:

```bash
cd liquibase-sudarshan
./gradlew runIde          # sandbox IDE with the plugin installed
./gradlew buildPlugin     # build/distributions/liquibase-sudarshan-<version>.zip
./gradlew validateRepo -Prepo="../oracle-liquibase-testrepo" -PrepoArgs="--oracle"   # headless CLI
```

See each folder's README for full documentation.
