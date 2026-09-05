# Agent notes — bank-sim

Conventions and tooling decisions for this repo, so they don't get
relitigated or drift from what's actually in the code. See `TODO.md` for the
build roadmap/milestones; this file is about *how* to build, not *what*.

## Backend stack
- **Gradle, Groovy DSL** (not Maven) — `backend/build.gradle`
- **Java 25** (matches the locally installed Corretto 25 toolchain)
- **Spring Boot 4.1.1**
- **jOOQ**, not Spring Data JPA/Hibernate, for all data access. Chosen because
  the ledger core (double-entry postings, `SUM(debits) == SUM(credits)`
  invariants, pessimistic row locking, trial-balance aggregation) is exactly
  the kind of complex-query/locking code where an ORM's session cache, lazy
  loading, and flush ordering get in the way. Write explicit SQL via jOOQ's
  DSL (`DSLContext`) — no `@Entity`, no Hibernate session.
- **Liquibase**, not Flyway, for schema/data migrations.

## Schema-per-module (Postgres)
One Postgres **schema** per top-level domain package, 1:1, no exceptions.
Current schemas: `customer`, `account`, `ledger`, `transaction`. Later
milestones (`interest`/`statement` in M4, `treasury`/`loan` in M6) each add
their own schema the same way when they land — not created speculatively
ahead of the code that needs them.

Rule: **no cross-schema foreign keys.** A module reaches another module's
data only through that module's own service/repository, never a raw join.
This is still a single deployable Spring Boot app (modular monolith, per
`TODO.md`) — the schema boundary just makes a later real service split
mechanical instead of a rewrite.

## Liquibase changelog layout
One folder per module/schema, each fully self-contained — so a module's
changelog folder can be lifted straight into its own service/repo later with
no restructuring:
```
backend/src/main/resources/db/changelog/
  db.changelog-master.yaml         <- lists one `include` per module
  customer/
    db.changelog-customer.yaml     <- includeAll over this module's own structure/+data/
    structure/                     <- DDL: tables, indexes, constraints
    data/                          <- DML: seed/reference data
  account/    (same shape)
  ledger/     (same shape)
  transaction/(same shape)
```
Rules:
- **Adding a changeset to an existing module** → drop a new file into that
  module's `structure/` or `data/`. Its own `db.changelog-<module>.yaml`
  picks it up via `includeAll` automatically — **never edit that file or the
  master file for this.**
- **Adding a brand-new module/schema** (rare — once per milestone) → create
  its folder (mirroring the shape above) and add one `include` line for it in
  `db.changelog-master.yaml`.
- Filename convention: `NNNN-description.yaml`, numeric prefix per subfolder
  controls execution order (a module's `structure/` always runs before its
  own `data/`)
- One `databaseChangeLog:` root per file, containing one or more `changeSet`s;
  changeSet `id`s are prefixed with the module name (e.g.
  `customer-0001-create-schema`)

## jOOQ codegen
jOOQ codegen reads table definitions from a live, already-migrated dev
Postgres — it does not read the changelog files directly. After adding new
Liquibase structural changesets, regenerate with:
```
cd backend && ./gradlew update generateJooq
```
(`update` applies the changelog via the Liquibase Gradle plugin;
`generateJooq` — from the `nu.studer.jooq` plugin — then reads the resulting
schema. `generateJooq.dependsOn update` is already wired in `build.gradle`.)
Codegen is configured for the `customer`/`account`/`ledger`/`transaction`
schemas; add new schemas to the `jooq { … database { schemata { … } } }`
block in `build.gradle` alongside their first Liquibase changeset.

## Liquibase changelog path must match between the Gradle task and Spring Boot
The `liquibase` Gradle plugin's `update` task (used for `generateJooq`, see
above) and Spring Boot's own runtime migration (on `bootRun`/app startup) both
apply `db.changelog-master.yaml` against the same dev DB, and both record
applied changesets in `DATABASECHANGELOG` keyed partly on `FILENAME`. Spring
resolves its changelog via `classpath:db/changelog/...`, so `FILENAME` is
recorded relative to the classpath root (`src/main/resources`). The Gradle
activity must resolve to the *same* relative path or the two bookkeeping
trails diverge silently — each thinks the other's changesets were never run,
and a non-idempotent changeset (e.g. `CREATE TABLE`, unlike the idempotent
`CREATE SCHEMA IF NOT EXISTS` M0 shipped with) fails with "already exists" the
second time it's applied through the other path. Fixed via `searchPath
'src/main/resources'` + `changelogFile 'db/changelog/db.changelog-master.yaml'`
in the `liquibase { activities { main { ... } } }` block — never point
`changelogFile` at a path prefixed with `src/main/resources/`.

## Testing
- **Real Postgres via Testcontainers, not H2**, for any test that touches the
  DB — decided in M1 because H2's locking/MVCC semantics diverge from
  Postgres, which matters for the ledger's pessimistic row locking, and
  because avoiding a second DB dialect fits the project's no-abstraction-leak
  stance on data access (see jOOQ rationale above).
- Base DB-touching tests on `PostgresIntegrationTest`
  (`backend/src/test/java/.../banksim/PostgresIntegrationTest.java`). It
  starts **one Postgres container for the whole test run** via a static
  initializer, deliberately **without** `@Testcontainers`/`@Container` —
  that JUnit extension stops the container in `afterAll` of whichever test
  class triggers it, which breaks the container for every other subclass
  sharing it. This is the Testcontainers "singleton container" pattern; the
  container is only reaped by Ryuk at JVM exit.
- Migrations run the normal way (Spring Boot's own Liquibase autoconfig on
  context startup via `@DynamicPropertySource`-overridden datasource
  properties) — no separate migration step needed in tests.

## Local dev prerequisites
- Docker Desktop must be running before `docker compose up -d` (Postgres),
  any Gradle task that touches the dev DB (`update`, `generateJooq`, tests —
  tests need it for Testcontainers, not for the dev DB itself), or `bootRun`.
