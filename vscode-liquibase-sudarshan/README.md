# Liquibase Sudarshan for VS Code

Validates Liquibase SQL repositories **before execution** — the same engine as the
[IntelliJ plugin](https://github.com/prpundge/liquibase-sudarshan): datatype/length checks,
NULL and MERGE-mapping validation, duplicate keys, changeset header typos, delimiter
problems, and an optional strictly read-only database **dry run** (PostgreSQL & Oracle)
with execution plan and INSERT/UPDATE preview. Findings appear inline as squiggles and in
the Problems panel.

Compatible with VS Code **1.60 and every newer version** (uses only long-stable APIs).

## Setup (2 minutes)

1. Build the CLI jar once (needs JDK 17+):
   ```bash
   cd liquibase-sudarshan && ./gradlew cliJar
   # -> build/libs/liquibase-sudarshan-cli-<version>.jar
   ```
2. Install the extension: `code --install-extension liquibase-sudarshan-<version>.vsix`
3. In VS Code settings, set **`liquibaseSudarshan.cliJar`** to the jar path.
4. Open your Liquibase repository folder. Every save of a `.sql` file re-validates
   (toggle: `liquibaseSudarshan.runOnSave`), or run
   **Liquibase Sudarshan: Validate Repository** from the command palette.

Optional dry run against a datasource — add to `liquibaseSudarshan.extraArgs`:

```json
["--oracle", "--db-url=jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
 "--db-user=app_user", "--db-password=app_pass"]
```

PR-review mode (only findings on changed lines): `["--patch=my-change.diff"]`.
