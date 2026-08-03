# Publishing v0.0.1 to JetBrains Marketplace

## One-time setup

1. Create/sign in to a JetBrains Account at <https://plugins.jetbrains.com>.
2. Profile menu → **Upload plugin** → create a **vendor profile**
   (name, contact email, optional website). Individual vendors are fine.

## First release (must be a manual upload)

1. Build: `cd liquibase-sudarshan && ./gradlew buildPlugin`
   → `build/distributions/liquibase-sudarshan-0.0.1.zip`.
2. On the Upload plugin page: select the ZIP, choose a **license** (e.g. Apache-2.0),
   **category**: *Tools integration*, add source-code URL
   `https://github.com/prpundge/liquibase-sudarshan`, submit.
3. JetBrains moderation takes ~2 business days; you get an email on approval and the
   plugin becomes searchable in every IDE's plugin browser.
4. After approval, polish the listing: screenshots (the SG file with inline errors and
   the dry-run execution plan/preview make the best ones) and tags
   (liquibase, sql, oracle, postgresql, database, validation).

## Subsequent releases (automated)

1. Marketplace profile → **My Tokens** → *Generate token* (permanent, `perm:` prefix).
2. Bump `version` and `changeNotes` in `build.gradle.kts` (each upload must be a higher
   version than the published one).
3. Publish — the build is already wired:

```powershell
$env:JETBRAINS_MARKETPLACE_TOKEN = "perm:xxxxxxxx"
.\gradlew.bat publishPlugin
```

## Optional plugin signing (recommended)

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

Set `PLUGIN_CERTIFICATE_CHAIN_FILE`, `PLUGIN_PRIVATE_KEY_FILE`,
`PLUGIN_PRIVATE_KEY_PASSWORD` (see `signing {}` in `build.gradle.kts`);
`signPlugin` then runs automatically before `publishPlugin`. Without author signing,
JetBrains signs Marketplace-distributed builds themselves.

## Pre-flight checklist

- [ ] `./gradlew clean test` — all tests green
- [ ] `./gradlew verifyPlugin` — Compatible on all pinned IDE versions
- [ ] `changeNotes` describe this version; plugin name/description contain no
      non-ASCII punctuation (Marketplace rejects it)
- [ ] README + specs updated for the version
