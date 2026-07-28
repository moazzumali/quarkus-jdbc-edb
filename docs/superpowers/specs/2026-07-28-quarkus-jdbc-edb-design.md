# quarkus-jdbc-edb 0.1 — Design

**Date:** 2026-07-28
**Status:** Approved, pending implementation plan

## Context

Quarkus issue [#55327](https://github.com/quarkusio/quarkus/issues/55327) approved an EDB JDBC
extension. The Quarkiverse team created
[quarkiverse/quarkus-jdbc-edb](https://github.com/quarkiverse/quarkus-jdbc-edb) from the
`create-extension` archetype. Work happens on a fork
([moazzumali/quarkus-jdbc-edb](https://github.com/moazzumali/quarkus-jdbc-edb)) and lands upstream
by pull request.

The repo currently holds the archetype skeleton plus an initial spike: the driver dependency, a
`JdbcDriverBuildItem`, a `ReflectiveClassBuildItem`, and an integration-test resource that injects
a `DataSource`.

## Verified facts

These were checked against the published artifacts, not assumed.

**Driver distribution.** `com.enterprisedb:edb-jdbc` is on Maven Central, versions `42.5.4.2`
through `42.7.3.5` (latest published June 2026). No private repository is required, so Quarkiverse
release automation works unmodified. Its POM declares dual licensing: BSD-2-Clause *and* EDB's
"Limited Use Software License Agreement". The extension only depends on the artifact and does not
redistribute it, but this should be raised with the Quarkiverse team.

**Class names, from inspecting `edb-jdbc-42.7.3.5.jar`:**

| Purpose | Class |
| --- | --- |
| Driver | `com.edb.Driver` |
| XA DataSource | `com.edb.xa.PGXADataSource` |
| Connection interface | `com.edb.PGConnection` |
| SQLXML implementation | `com.edb.jdbc.PgSQLXML` |
| Cleaner | `com.edb.util.LazyCleaner` |
| RNG holder | `com.edb.util.PasswordUtil$SecureRandomHolder` |

The jar ships `META-INF/services/java.sql.Driver` and `org/postgresql/translation/*.properties`
resource bundles. It does **not** ship a `driverconfig.properties` (upstream pgjdbc does), so no
native-image resource registration is needed for that file.

**The driver is a pgjdbc fork.** It speaks the same wire protocol, so it connects to a community
PostgreSQL server. This underpins the test strategy below. It requires a `jdbc:edb:` URL prefix —
it will not accept `jdbc:postgresql:`.

**Hibernate ORM already knows EDB.** `org.hibernate.dialect.Database` has an `ENTERPRISEDB`
constant declaring driver class `com.edb.Driver`, URL prefix `jdbc:edb:`, product name
`EnterpriseDB`, resolving to `org.hibernate.dialect.PostgresPlusDialect`. `PostgresPlusDialect`
extends `PostgreSQLDialect`, overriding sequence handling, `dual`, some casts, and
callable-statement details.

**`db-kind` resolution.** `DefaultDataSourceDbKindBuildItem.resolve()` returns an explicitly
configured `db-kind` immediately, without validating it against the registered default kinds. So
`quarkus.datasource.db-kind=edb` resolves from `JdbcDriverBuildItem` alone; the
`DefaultDataSourceDbKindBuildItem` is what allows the property to be omitted entirely.
`DatabaseKind.normalize()` lowercases and applies aliases only — `edb` passes through unchanged and
collides with no core database kind.

**Defect in the current spike.** `JdbcEdbProcessor` registers the XA DataSource as
`com.edb.postgresql.XADataSource`, which does not exist in the jar. XA transactions would fail at
runtime. The correct class is `com.edb.xa.PGXADataSource`.

## Decisions

| Decision | Choice | Rationale |
| --- | --- | --- |
| 0.1 scope | Feature parity with `quarkus-jdbc-postgresql` | Credibility for a vendor-backed extension; the upstream extension is a direct blueprint, so cost is mostly mechanical |
| `db-kind` identifier | `edb` | Matches artifact name, `jdbc:edb:` URL prefix, `com.edb` package, and the approved extension name |
| Hibernate dialect | `PostgresPlusDialect` via `forCoreDialect("edb", "EnterpriseDB", …)` | Hibernate's EDB-specific dialect; upstream already maps this path |
| Test databases | Layered: community PostgreSQL by default, real EPAS via opt-in profile | EPAS images sit behind `docker.enterprisedb.com` and are unavailable to public CI or outside contributors |
| Dev Services | Deferred to 0.2 | Affects no build, test, or release step — it only saves downstream users one line of config. Largest single work item, and its default-image question needs an EDB-side answer |
| Kubernetes service binding | In 0.1 | Small, direct parity |
| Agroal connection configurer | In 0.1 | Effectively core wiring |
| Flyway / Liquibase | Investigate only, not committed | Flyway resolves database type from JDBC URL prefix and may not recognise `jdbc:edb:`. If unsupported, this is upstream work or a documented limitation |
| Dev UI console command | Deferred to 0.2 | Depends on Dev Services |
| Workflow | Feature branch on fork → PR to `quarkiverse/quarkus-jdbc-edb` | Standard flow; gives maintainers a reviewable diff with CI |

## Architecture

The existing four-module layout is unchanged: `runtime`, `deployment`, `docs`,
`integration-tests`. Deferring Dev Services is what keeps the module count flat — a Dev Services
provider would warrant its own module, as it does upstream.

### Runtime module

Responsibilities: declare the driver dependency, and host the small set of classes that must exist
on the application classpath at runtime.

Dependency changes:

- `com.enterprisedb:edb-jdbc`, version via an `edb-jdbc.version` property rather than inline, so
  Dependabot can manage it
- add `io.quarkus:quarkus-agroal` (today the module declares only `quarkus-arc`)
- add `io.quarkus:quarkus-kubernetes-service-binding`, optional
- add `org.graalvm.sdk:nativeimage`, provided scope, for the native-image feature class

Classes:

- `EdbAgroalConnectionConfigurer` — transaction-isolation and connection-level settings, modelled
  on `PostgreSQLAgroalConnectionConfigurer`
- `EdbServiceBindingConverter` — maps Service Binding Operator secrets to datasource config
- `graal/SQLXMLFeature` — native-image feature covering `com.edb.jdbc.PgSQLXML`

Build configuration: `parentFirstArtifacts` on `quarkus-extension-maven-plugin` listing
`com.enterprisedb:edb-jdbc`, matching what the PostgreSQL extension requires of pgjdbc.

### Deployment module

A single `JdbcEdbProcessor` holds the build steps. Each is independently meaningful and testable
through the build-item graph.

| Build item produced | Purpose |
| --- | --- |
| `FeatureBuildItem("jdbc-edb")` | feature name in startup banner |
| `JdbcDriverBuildItem("edb", "com.edb.Driver", "com.edb.xa.PGXADataSource")` | driver + XA registration; corrects the defect noted above |
| `DefaultDataSourceDbKindBuildItem("edb")` | enables *implicit* db-kind resolution, letting users omit `quarkus.datasource.db-kind` entirely when the extension is on the classpath; also consumed by the Dev Services processor |
| `DatabaseKindDialectBuildItem.forCoreDialect("edb", "EnterpriseDB", Set.of("org.hibernate.dialect.PostgresPlusDialect"))` | Hibernate ORM dialect selection |
| `RuntimeInitializedClassBuildItem` for `com.edb.util.LazyCleaner` and `com.edb.util.PasswordUtil$SecureRandomHolder` | defer initialisation past native-image build |
| `NativeImageFeatureBuildItem(SQLXMLFeature.class)` | SQLXML support in native mode |
| `NativeImageResourceBundleBuildItem` for `org.postgresql.translation.messages` | driver error messages in native mode |
| `ServiceProviderBuildItem` for `java.sql.Driver` | driver discovery in native mode |
| `ExtensionSslNativeSupportBuildItem` | TLS connections in native mode |
| `ReflectiveClassBuildItem` | narrowed version of the existing registration |
| `AdditionalBeanBuildItem(EdbAgroalConnectionConfigurer)` | guarded by `Capability.AGROAL` |
| `ServiceProviderBuildItem` for the service-binding converter | guarded by `Capability.KUBERNETES_SERVICE_BINDING` |

Dialect selection happens at build time from `db-kind`, not by runtime product-name detection.
This makes dialect choice deterministic and independent of which container the tests run against.

## Testing

Three layers, each with a distinct job.

**Build-time tests (`deployment/src/test`).** `QuarkusUnitTest`-based, no database. Assert that
`db-kind=edb` resolves to a datasource configuration, that the driver and XA classes are
registered, and that the Hibernate dialect mapping is produced. These catch the class of defect
already found in the spike — a wrong class name in a build item — without needing any server.

**Integration tests, default profile (`integration-tests`).** A Testcontainers community
PostgreSQL container, addressed with a `jdbc:edb://` URL through the EDB driver. Runs in public CI
in both JVM and native mode, and is available to outside contributors with no credentials. Proves
extension wiring, connection handling, XA, native-image correctness, and Hibernate mapping.

**Integration tests, `epas` profile.** The same tests against real EPAS via a configurable JDBC
URL, run against a live EPAS instance during development and by maintainers. This is where
EPAS-specific behaviour — Oracle compatibility mode, `EnterpriseDB` product name, port 5444,
`edb_` GUCs — is actually exercised.

**Hibernate smoke test.** Entity CRUD plus sequence generation, so `PostgresPlusDialect` is
exercised rather than merely registered. Because `PostgresPlusDialect` extends `PostgreSQLDialect`
and overrides a narrow set of behaviours, this is expected to pass against community PostgreSQL;
any divergence found there is itself a useful finding and gets documented.

### What the default profile does not cover

Recorded so the gap is not mistaken for coverage: against community PostgreSQL there is no Oracle
compatibility mode (SPL, packages, `DBMS_*`, `ROWNUM`, `SYSDATE`, `DUAL`, hierarchical queries),
`getDatabaseProductName()` returns `PostgreSQL` rather than `EnterpriseDB`, the port is 5432 rather
than 5444, and EPAS-only catalogs and redwood date semantics are absent. The `epas` profile exists
precisely to cover this, and the docs state the distinction.

## Error handling

Failure modes should be loud and actionable at the earliest possible stage:

- **Unknown `db-kind`** — a build-time failure from Agroal's driver lookup when no
  `JdbcDriverBuildItem` matches. Note that explicitly setting `quarkus.datasource.db-kind=edb`
  resolves from `JdbcDriverBuildItem` alone; `DefaultDataSourceDbKindBuildItem` is only consulted
  when `db-kind` is omitted.
- **Missing JDBC URL with no Dev Services** — startup failure directing the user to set
  `quarkus.datasource.jdbc.url`. This is the accepted consequence of deferring Dev Services and is
  documented explicitly.
- **Wrong class names in build items** — caught by the build-time test layer, since these fail only
  at runtime otherwise.
- **Native-image initialisation errors** — caught by running the integration tests in native mode
  in CI, not by inspection.

## Metadata and documentation

`runtime/src/main/resources/META-INF/quarkus-extension.yaml` is entirely placeholder today. It
needs a real name ("JDBC Driver - EDB Postgres Advanced Server"), description, keywords,
`categories: [data]`, `status: preview`, and the guide URL. `docs/antora.yml` title needs the same
treatment.

`docs/modules/ROOT/pages/index.adoc` covers: installation, datasource configuration, the
`jdbc:edb://host:5444/db` URL format, XA transactions, native-image usage, Hibernate ORM with
`PostgresPlusDialect`, the EPAS-versus-community-PostgreSQL distinction, and stated limitations
(no Dev Services in 0.1, Flyway/Liquibase status).

## Housekeeping

The two Java files modified in the spike have import ordering that violates the
`quarkiverse-parent` formatter and impsort configuration, and two files lack trailing newlines.
This fails CI on first push and should be fixed as part of the work rather than discovered.

## Deferred to 0.2

- **Dev Services provider.** Requires its own module, a `DevServicesDatasourceProvider`, and a
  container subclass emitting `jdbc:edb://` URLs — Quarkus's `QuarkusPostgreSQLContainer` is a
  private inner class whose `getEffectiveJdbcUrl()` hardcodes `jdbc:postgresql://`, so it cannot be
  reused directly. The open question is the default image: a public PostgreSQL image works
  everywhere but is not EPAS, while an EPAS default requires a `docker.enterprisedb.com` login and
  breaks dev mode without a subscription. This needs an EDB-side answer.
- **Dev UI console command**, the equivalent of Postgres's `psql` command.
- **Flyway / Liquibase verification** for `jdbc:edb:` URLs.

## Open items to raise with Quarkiverse

- The driver's dual licensing (BSD-2-Clause plus EDB Limited Use Software License Agreement).
- Whether an EPAS image can be referenced as a Dev Services default, which unblocks 0.2.
