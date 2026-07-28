# quarkus-jdbc-edb 0.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `quarkus.datasource.db-kind=edb` a fully supported Quarkus datasource in JVM and native mode, with XA transactions, Hibernate ORM via `PostgresPlusDialect`, Kubernetes service binding, and published Quarkiverse documentation.

**Architecture:** A standard two-module Quarkus extension. The `runtime` module declares the `com.enterprisedb:edb-jdbc` dependency and hosts two small runtime classes (an Agroal connection configurer and a service-binding converter). The `deployment` module contains a single `JdbcEdbProcessor` producing build items that register the driver with Agroal, declare the db-kind, map the Hibernate dialect, and configure native-image support. Verification is layered: build-time `QuarkusUnitTest`s that need no database, integration tests against a community PostgreSQL Testcontainer in public CI, and an opt-in profile targeting real EPAS.

**Tech Stack:** Java 17, Quarkus 3.37.2, Maven, `quarkiverse-parent:20`, `com.enterprisedb:edb-jdbc:42.7.3.5`, Agroal, Hibernate ORM, Testcontainers 2.0.5, JUnit 5, REST Assured, AssertJ.

**Spec:** `docs/superpowers/specs/2026-07-28-quarkus-jdbc-edb-design.md`

## Global Constraints

- **Do NOT run `git add`, `git commit`, `git push`, or any other mutating git command.** The user has explicitly instructed this. Every task therefore ends with a verification step instead of a commit step. Leave all changes in the working tree.
- Java release level: `17`. Quarkus version: `3.37.2`. Parent: `io.quarkiverse:quarkiverse-parent:20`.
- Extension coordinates: `io.quarkiverse.edb:quarkus-jdbc-edb` (runtime), `io.quarkiverse.edb:quarkus-jdbc-edb-deployment` (deployment).
- db-kind identifier: exactly `edb` (lowercase).
- Driver class: `com.edb.Driver`. XA DataSource class: `com.edb.xa.PGXADataSource`.
- Driver version is declared **once**, as the property `edb-jdbc.version` in the root `pom.xml`, value `42.7.3.5`.
- Hibernate dialect: `org.hibernate.dialect.PostgresPlusDialect`, selected via database product name `EnterpriseDB`.
- **Import order** (enforced by `impsort-maven-plugin`, groups `java.,javax.,jakarta.,org.,com.`): `java.*`, then `javax.*`, then `jakarta.*`, then `org.*`, then `com.*`, then everything else (`io.*`) last. One blank line between groups. Static imports first.
- Every file ends with a trailing newline.
- **Formatting:** run `mvn -B process-sources` locally to auto-format. CI runs `mvn -B clean install -Dno-format`, which activates the parent's `validate` profile — this runs `formatter:validate` and `impsort:check` and **fails the build** on any violation. `-Dno-format` does not skip formatting; it switches it from fix-mode to check-mode.
- Testcontainers coordinates: `org.testcontainers:testcontainers-postgresql`, version managed by `quarkus-bom` (2.0.5). Container class is `org.testcontainers.postgresql.PostgreSQLContainer` (the `org.testcontainers.containers.PostgreSQLContainer` variant is the deprecated legacy one — do not use it).

## Deviation From The Approved Spec — Read Before Starting

The spec lists a `graal/SQLXMLFeature` among the runtime classes. **This plan drops it from 0.1.**

Reason: in the PostgreSQL extension, `SQLXMLFeature` is not a correctness feature. It is a native-image *size* optimization built from three cooperating classes — a `Feature`, a `DomHelper`, and a full `@Substitute` reimplementation of the driver's `PgSQLXML` (~400 lines total) — whose only purpose is to keep the JDK XML parsers out of the image unless `SQLXML#setResult` is actually reachable. Porting it means maintaining a hand-written substitution of a large driver-internal class against every `edb-jdbc` upgrade. Native image works correctly without it; it is just larger when an application touches `SQLXML`.

Consequently this plan also omits the `org.graalvm.sdk:nativeimage` provided-scope dependency, which existed only to compile that feature class.

If the reviewer wants it in 0.1 after all, it is a self-contained addition that depends on no other task.

## File Structure

**Created:**

| Path | Responsibility |
| --- | --- |
| `runtime/src/main/java/io/quarkiverse/jdbc/edb/runtime/EdbAgroalConnectionConfigurer.java` | Agroal connection-level settings for db-kind `edb` |
| `runtime/src/main/java/io/quarkiverse/jdbc/edb/runtime/EdbServiceBindingConverter.java` | Maps Service Binding Operator secrets to datasource config |
| `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbXaDataSourceTest.java` | Proves the XA DataSource class resolves and loads |
| `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbImplicitDbKindTest.java` | Proves db-kind can be omitted |
| `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbDialectTest.java` | Proves Hibernate resolves `PostgresPlusDialect` |
| `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/EdbDatabaseTestResource.java` | Starts the PostgreSQL Testcontainer and injects `jdbc:edb://` config |
| `integration-tests/src/main/java/io/quarkiverse/jdbc/edb/it/Product.java` | JPA entity exercising the dialect |
| `integration-tests/src/main/java/io/quarkiverse/jdbc/edb/it/ProductResource.java` | REST endpoint for entity CRUD |
| `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/ProductResourceTest.java` | Hibernate CRUD + sequence assertions |
| `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/ProductResourceIT.java` | Native-mode variant |

**Modified:**

| Path | Change |
| --- | --- |
| `pom.xml` | Add `edb-jdbc.version` property |
| `runtime/pom.xml` | Use the version property; add `quarkus-agroal` and optional `quarkus-kubernetes-service-binding`; add `parentFirstArtifacts` |
| `deployment/pom.xml` | Add datasource/hibernate deployment SPI deps and test-scoped deployment deps |
| `deployment/src/main/java/io/quarkiverse/jdbc/edb/deployment/JdbcEdbProcessor.java` | All build steps |
| `integration-tests/pom.xml` | Testcontainers + Hibernate deps; `epas` profile |
| `integration-tests/src/main/java/io/quarkiverse/jdbc/edb/it/JdbcEdbResource.java` | Formatting; keep the metadata endpoint |
| `integration-tests/src/main/resources/application.properties` | Remove hardcoded localhost URL |
| `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/JdbcEdbResourceTest.java` | Assert against the container |
| `runtime/src/main/resources/META-INF/quarkus-extension.yaml` | Real metadata |
| `docs/antora.yml` | Real title |
| `docs/modules/ROOT/pages/index.adoc` | Full guide |

---

### Task 1: Dependency and formatting baseline

Establishes a clean, correctly-formatted build before any behavioural change, so later tasks are not fighting the formatter.

**Files:**
- Modify: `pom.xml:27-33` (properties block)
- Modify: `runtime/pom.xml:13-23` (dependencies), `runtime/pom.xml:25-42` (extension plugin)
- Modify: `deployment/pom.xml:13-32` (dependencies)
- Modify: `deployment/src/main/java/io/quarkiverse/jdbc/edb/deployment/JdbcEdbProcessor.java` (trailing newline)
- Modify: `integration-tests/src/main/resources/application.properties` (trailing newline)

**Interfaces:**
- Consumes: nothing.
- Produces: the property `${edb-jdbc.version}`; the `quarkus-agroal` and `quarkus-kubernetes-service-binding` dependencies that Tasks 5 and 6 compile against; the `quarkus-datasource-deployment-spi`, `quarkus-hibernate-orm-deployment-spi`, `quarkus-agroal-deployment` and `quarkus-hibernate-orm-deployment` dependencies that Tasks 2, 3 and 4 compile against.

- [ ] **Step 1: Add the driver version property to the root pom**

In `pom.xml`, inside the existing `<properties>` block, add the property alongside `quarkus.version`:

```xml
        <quarkus.version>3.37.2</quarkus.version>
        <edb-jdbc.version>42.7.3.5</edb-jdbc.version>
```

- [ ] **Step 2: Replace the runtime module dependencies**

In `runtime/pom.xml`, replace the entire `<dependencies>` block with:

```xml
    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-agroal</artifactId>
        </dependency>
        <dependency>
            <groupId>com.enterprisedb</groupId>
            <artifactId>edb-jdbc</artifactId>
            <version>${edb-jdbc.version}</version>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-kubernetes-service-binding</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
```

- [ ] **Step 3: Add parentFirstArtifacts to the extension plugin**

The JDBC driver must be loaded by the parent classloader, exactly as the PostgreSQL extension does for pgjdbc. In `runtime/pom.xml`, in the `quarkus-extension-maven-plugin` block, add a `<configuration>` inside the `<execution>`'s existing configuration — replace the existing `<configuration>` element with:

```xml
                        <configuration>
                            <deployment>${project.groupId}:${project.artifactId}-deployment:${project.version}</deployment>
                            <parentFirstArtifacts>
                                <parentFirstArtifact>com.enterprisedb:edb-jdbc</parentFirstArtifact>
                            </parentFirstArtifacts>
                        </configuration>
```

- [ ] **Step 4: Add the deployment module dependencies**

In `deployment/pom.xml`, replace the entire `<dependencies>` block with:

```xml
    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc-deployment</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkiverse.edb</groupId>
            <artifactId>quarkus-jdbc-edb</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-agroal-spi</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-datasource-deployment-spi</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm-deployment-spi</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit-internal</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-agroal-deployment</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm-deployment</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

- [ ] **Step 5: Auto-format everything and add trailing newlines**

Run:

```bash
mvn -B process-sources
```

This rewrites sources through `formatter-maven-plugin` and `impsort-maven-plugin`, fixing the import ordering in `JdbcEdbProcessor.java` and `JdbcEdbResource.java`. It does **not** fix missing trailing newlines in non-Java files, so also ensure `integration-tests/src/main/resources/application.properties` and `deployment/.../JdbcEdbProcessor.java` end with a newline character.

- [ ] **Step 6: Verify the build passes format validation**

Run:

```bash
mvn -B clean install -Dno-format -DskipTests
```

Expected: `BUILD SUCCESS`. If `formatter:validate` or `impsort:check` reports a violation, re-run Step 5 and re-check. A failure here means CI would have failed on push.

- [ ] **Step 7: Verify no git state was modified**

Run:

```bash
git status --short
```

Expected: modified files listed, **nothing staged**. Do not stage or commit.

---

### Task 2: Fix the XA DataSource class

The current processor registers `com.edb.postgresql.XADataSource`, which does not exist in `edb-jdbc-42.7.3.5.jar`. This task writes a test that reproduces the failure, then fixes it.

**Files:**
- Create: `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbXaDataSourceTest.java`
- Modify: `deployment/src/main/java/io/quarkiverse/jdbc/edb/deployment/JdbcEdbProcessor.java`

**Interfaces:**
- Consumes: `${edb-jdbc.version}` and the test-scoped `quarkus-agroal-deployment` dependency from Task 1.
- Produces: `JdbcEdbProcessor.registerDriver(BuildProducer<JdbcDriverBuildItem>)`, producing `JdbcDriverBuildItem("edb", "com.edb.Driver", "com.edb.xa.PGXADataSource")`.

**Why this test catches it:** Quarkus resolves the driver class at build time — `AgroalProcessor` selects `JdbcDriverBuildItem.getDriverXAClass()` when `transactions=xa` — and then `DataSources` calls `Class.forName(resolvedDriverClass)` when the `AgroalDataSource` bean is created. A wrong class name throws `RuntimeException: Unable to load the datasource driver <class> for datasource <default>`. Agroal does not open a connection at bean creation (`min-size` defaults to 0), so no live database is needed.

- [ ] **Step 1: Write the failing test**

Create `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbXaDataSourceTest.java`:

```java
package io.quarkiverse.jdbc.edb.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusUnitTest;

public class JdbcEdbXaDataSourceTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(jar -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "edb")
            .overrideConfigKey("quarkus.datasource.jdbc.transactions", "xa")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:edb://localhost:5444/edb")
            .overrideConfigKey("quarkus.datasource.username", "edb")
            .overrideConfigKey("quarkus.datasource.password", "edb")
            .overrideConfigKey("quarkus.datasource.jdbc.min-size", "0")
            .overrideConfigKey("quarkus.datasource.health.enabled", "false");

    @Inject
    AgroalDataSource dataSource;

    @Test
    public void xaDataSourceClassIsLoadable() {
        // Creating the Agroal datasource forces Class.forName on the configured XA
        // DataSource class, so this fails if the registered class name is wrong.
        assertThat(dataSource).isNotNull();
        assertThat(dataSource.getConfiguration()
                .connectionPoolConfiguration()
                .connectionFactoryConfiguration()
                .connectionProviderClass())
                .isEqualTo(com.edb.xa.PGXADataSource.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -B -pl deployment test -Dtest=JdbcEdbXaDataSourceTest -Dno-format
```

Expected: FAIL. The failure message contains `Unable to load the datasource driver com.edb.postgresql.XADataSource for datasource <default>`.

If instead the test fails to *compile* on `com.edb.xa.PGXADataSource`, the driver dependency from Task 1 is not on the test classpath — fix Task 1 before continuing.

If it fails to compile because `connectionProviderClass()` is not a readable getter on this Agroal
version (only the setter was verified against Quarkus's `DataSources`), drop that second assertion
and keep only `assertThat(dataSource).isNotNull();`. That alone still reproduces the defect, because
the failure happens during `Class.forName` inside datasource creation — the assertion on the class
is a nicety, not the mechanism. Use `javap -cp <agroal-api.jar> io.agroal.api.configuration.ConnectionFactoryConfiguration`
to check the real method name if you want to keep it.

- [ ] **Step 3: Fix the registered XA class**

In `deployment/src/main/java/io/quarkiverse/jdbc/edb/deployment/JdbcEdbProcessor.java`, replace the existing `createJdbcDriver()` method with:

```java
    @BuildStep
    void registerDriver(BuildProducer<JdbcDriverBuildItem> jdbcDriver) {
        jdbcDriver.produce(new JdbcDriverBuildItem("edb", "com.edb.Driver", "com.edb.xa.PGXADataSource"));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
mvn -B -pl deployment test -Dtest=JdbcEdbXaDataSourceTest -Dno-format
```

Expected: PASS, `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Verify formatting still validates**

Run:

```bash
mvn -B -pl deployment process-sources && mvn -B -pl deployment test -Dno-format
```

Expected: `BUILD SUCCESS`. Do not stage or commit.

---

### Task 3: Implicit db-kind resolution

Lets users omit `quarkus.datasource.db-kind` entirely when the extension is on the classpath, and supplies the build item the Dev Services processor will need in 0.2.

**Files:**
- Create: `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbImplicitDbKindTest.java`
- Modify: `deployment/src/main/java/io/quarkiverse/jdbc/edb/deployment/JdbcEdbProcessor.java`

**Interfaces:**
- Consumes: `registerDriver` from Task 2; `quarkus-datasource-deployment-spi` from Task 1.
- Produces: `JdbcEdbProcessor.registerDefaultDbKind(BuildProducer<DefaultDataSourceDbKindBuildItem>)`.

**Note on semantics:** `DefaultDataSourceDbKindBuildItem.resolve()` returns an explicitly configured `db-kind` immediately without consulting the registered defaults. So this build item changes behaviour *only* when `db-kind` is absent. That is exactly what the test below exercises.

- [ ] **Step 1: Write the failing test**

Create `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbImplicitDbKindTest.java`:

```java
package io.quarkiverse.jdbc.edb.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusUnitTest;

public class JdbcEdbImplicitDbKindTest {

    // Deliberately does NOT set quarkus.datasource.db-kind: having the extension on the
    // classpath must be enough to resolve the kind to "edb".
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(jar -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:edb://localhost:5444/edb")
            .overrideConfigKey("quarkus.datasource.username", "edb")
            .overrideConfigKey("quarkus.datasource.password", "edb")
            .overrideConfigKey("quarkus.datasource.jdbc.min-size", "0")
            .overrideConfigKey("quarkus.datasource.health.enabled", "false");

    @Inject
    AgroalDataSource dataSource;

    @Test
    public void dbKindIsResolvedImplicitly() {
        assertThat(dataSource).isNotNull();
        assertThat(dataSource.getConfiguration()
                .connectionPoolConfiguration()
                .connectionFactoryConfiguration()
                .connectionProviderClass())
                .isEqualTo(com.edb.Driver.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -B -pl deployment test -Dtest=JdbcEdbImplicitDbKindTest -Dno-format
```

Expected: FAIL. With no `db-kind` configured and no `DefaultDataSourceDbKindBuildItem` registered, `resolve()` returns empty and the JDBC datasource is never configured, so the `AgroalDataSource` bean is unsatisfied or the build reports the datasource as unconfigured. Record the exact message you observe — you will confirm it changes in Step 4.

If `connectionProviderClass()` did not compile in Task 2, apply the same reduction here: keep only
`assertThat(dataSource).isNotNull();`. Injecting the bean at all is what proves implicit resolution
worked.

- [ ] **Step 3: Register the default db-kind**

In `JdbcEdbProcessor.java`, add:

```java
    @BuildStep
    void registerDefaultDbKind(BuildProducer<DefaultDataSourceDbKindBuildItem> dbKind) {
        dbKind.produce(new DefaultDataSourceDbKindBuildItem("edb"));
    }
```

Add the import `io.quarkus.datasource.deployment.spi.DefaultDataSourceDbKindBuildItem` in the `io.*` group.

- [ ] **Step 4: Run both tests to verify they pass**

Run:

```bash
mvn -B -pl deployment test -Dtest='JdbcEdbXaDataSourceTest+JdbcEdbImplicitDbKindTest' -Dno-format
```

Expected: PASS, `Tests run: 2, Failures: 0, Errors: 0`. Task 2's test must still pass — the explicit-`db-kind` path is unaffected by this change.

- [ ] **Step 5: Verify formatting**

Run:

```bash
mvn -B -pl deployment process-sources && mvn -B -pl deployment test -Dno-format
```

Expected: `BUILD SUCCESS`. Do not stage or commit.

---

### Task 4: Hibernate ORM dialect mapping

Without a matching `DatabaseKindDialectBuildItem`, Quarkus cannot pick a dialect for an unknown db-kind and fails the build.

**Files:**
- Create: `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbDialectTest.java`
- Modify: `deployment/src/main/java/io/quarkiverse/jdbc/edb/deployment/JdbcEdbProcessor.java`

**Interfaces:**
- Consumes: `registerDriver` from Task 2; `quarkus-hibernate-orm-deployment-spi` and test-scoped `quarkus-hibernate-orm-deployment` from Task 1.
- Produces: `JdbcEdbProcessor.registerHibernateDialect(BuildProducer<DatabaseKindDialectBuildItem>)`.

**Background:** Hibernate ORM already ships an `ENTERPRISEDB` entry in `org.hibernate.dialect.Database` that matches product name `EnterpriseDB`, declares driver `com.edb.Driver` and URL prefix `jdbc:edb:`, and resolves to `org.hibernate.dialect.PostgresPlusDialect`. We therefore use `forCoreDialect` with the product name rather than naming a third-party dialect class.

- [ ] **Step 1: Write the failing test**

Create `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbDialectTest.java`:

```java
package io.quarkiverse.jdbc.edb.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import org.hibernate.SessionFactory;
import org.hibernate.dialect.PostgresPlusDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

public class JdbcEdbDialectTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(jar -> ShrinkWrap.create(JavaArchive.class)
                    .addClass(DialectProbeEntity.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "edb")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:edb://localhost:5444/edb")
            .overrideConfigKey("quarkus.datasource.username", "edb")
            .overrideConfigKey("quarkus.datasource.password", "edb")
            .overrideConfigKey("quarkus.datasource.jdbc.min-size", "0")
            .overrideConfigKey("quarkus.datasource.health.enabled", "false")
            .overrideConfigKey("quarkus.hibernate-orm.schema-management.strategy", "none")
            .overrideConfigKey("quarkus.hibernate-orm.database.version-check.enabled", "false");

    @Inject
    SessionFactory sessionFactory;

    @Test
    public void dialectIsPostgresPlus() {
        assertThat(((SessionFactoryImplementor) sessionFactory).getJdbcServices().getDialect())
                .isInstanceOf(PostgresPlusDialect.class);
    }

    @Entity
    public static class DialectProbeEntity {
        @Id
        @GeneratedValue
        public Long id;
        public String name;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -B -pl deployment test -Dtest=JdbcEdbDialectTest -Dno-format
```

Expected: FAIL at augmentation with a configuration error stating that the dialect for database kind `edb` cannot be determined.

**If the failure is instead a connection attempt to `localhost:5444`,** Hibernate is trying to reach the database during startup in this Quarkus version. In that case delete this test file and move the dialect assertion into Task 9's integration test (`ProductResourceTest`), where a live container exists, adding there:

```java
    @Test
    public void dialectIsPostgresPlus() {
        given().when().get("/product/dialect").then().statusCode(200)
                .body(is("PostgresPlusDialect"));
    }
```

backed by a `/product/dialect` endpoint returning
`((SessionFactoryImplementor) sessionFactory).getJdbcServices().getDialect().getClass().getSimpleName()`.
Then proceed to Step 3 and verify via Task 9 instead.

- [ ] **Step 3: Register the dialect mapping**

In `JdbcEdbProcessor.java`, add:

```java
    @BuildStep
    void registerHibernateDialect(BuildProducer<DatabaseKindDialectBuildItem> dialect) {
        dialect.produce(DatabaseKindDialectBuildItem.forCoreDialect("edb", "EnterpriseDB",
                Set.of("org.hibernate.dialect.PostgresPlusDialect")));
    }
```

Add imports `java.util.Set` (in the `java.` group) and
`io.quarkus.hibernate.orm.deployment.spi.DatabaseKindDialectBuildItem` (in the `io.*` group).

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
mvn -B -pl deployment test -Dtest=JdbcEdbDialectTest -Dno-format
```

Expected: PASS, `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Run the whole deployment test suite**

Run:

```bash
mvn -B -pl deployment test -Dno-format
```

Expected: all tests pass. Do not stage or commit.

---

### Task 5: Agroal connection configurer

Gives db-kind `edb` correct SSL-disable, exception-sorting, keep-alive and read-timeout behaviour instead of Agroal's unknown-database fallback.

**Files:**
- Create: `runtime/src/main/java/io/quarkiverse/jdbc/edb/runtime/EdbAgroalConnectionConfigurer.java`
- Modify: `deployment/src/main/java/io/quarkiverse/jdbc/edb/deployment/JdbcEdbProcessor.java`

**Interfaces:**
- Consumes: `quarkus-agroal` runtime dependency from Task 1.
- Produces: class `io.quarkiverse.jdbc.edb.runtime.EdbAgroalConnectionConfigurer`, registered as an application-scoped unremovable bean by `JdbcEdbProcessor.configureAgroalConnection`.

The EDB driver is a pgjdbc fork and accepts the same connection properties (`sslmode`, `tcpKeepAlive`, `socketTimeout`), so Agroal's `PostgreSQLExceptionSorter` and these property names apply unchanged.

- [ ] **Step 1: Write the configurer**

Create `runtime/src/main/java/io/quarkiverse/jdbc/edb/runtime/EdbAgroalConnectionConfigurer.java`:

```java
package io.quarkiverse.jdbc.edb.runtime;

import java.time.Duration;
import java.util.Map;

import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.exceptionsorter.PostgreSQLExceptionSorter;
import io.quarkus.agroal.runtime.AgroalConnectionConfigurer;
import io.quarkus.agroal.runtime.JdbcDriver;

@JdbcDriver("edb")
public class EdbAgroalConnectionConfigurer implements AgroalConnectionConfigurer {

    @Override
    public void disableSslSupport(String databaseKind, AgroalDataSourceConfigurationSupplier dataSourceConfiguration,
            Map<String, String> additionalProperties) {
        dataSourceConfiguration.connectionPoolConfiguration().connectionFactoryConfiguration().jdbcProperty("sslmode",
                "disable");
    }

    @Override
    public void setExceptionSorter(String databaseKind, AgroalDataSourceConfigurationSupplier dataSourceConfiguration) {
        // The EDB driver is a fork of pgjdbc and reports the same SQL states.
        dataSourceConfiguration.connectionPoolConfiguration().exceptionSorter(new PostgreSQLExceptionSorter());
    }

    @Override
    public void setKeepAlive(String databaseKind, AgroalDataSourceConfigurationSupplier dataSourceConfiguration,
            Map<String, String> additionalJdbcProperties, boolean keepAlive) {
        dataSourceConfiguration.connectionPoolConfiguration().connectionFactoryConfiguration().jdbcProperty("tcpKeepAlive",
                Boolean.toString(keepAlive));
    }

    @Override
    public void setReadTimeout(String databaseKind, AgroalDataSourceConfigurationSupplier dataSourceConfiguration,
            Map<String, String> additionalJdbcProperties, Duration timeout) {
        // socketTimeout is expressed in seconds.
        dataSourceConfiguration.connectionPoolConfiguration().connectionFactoryConfiguration().jdbcProperty("socketTimeout",
                Long.toString(timeout.getSeconds()));
    }
}
```

- [ ] **Step 2: Register it as a bean**

In `JdbcEdbProcessor.java`, add:

```java
    @BuildStep
    void configureAgroalConnection(BuildProducer<AdditionalBeanBuildItem> additionalBeans, Capabilities capabilities) {
        if (capabilities.isPresent(Capability.AGROAL)) {
            additionalBeans.produce(new AdditionalBeanBuildItem.Builder()
                    .addBeanClass(EdbAgroalConnectionConfigurer.class)
                    .setDefaultScope(BuiltinScope.APPLICATION.getName())
                    .setUnremovable()
                    .build());
        }
    }
```

Add imports, in the `io.*` group: `io.quarkiverse.jdbc.edb.runtime.EdbAgroalConnectionConfigurer`,
`io.quarkus.arc.deployment.AdditionalBeanBuildItem`, `io.quarkus.arc.processor.BuiltinScope`,
`io.quarkus.deployment.Capabilities`, `io.quarkus.deployment.Capability`.

- [ ] **Step 3: Add a test asserting the configurer is used**

Append this test method to `deployment/src/test/java/io/quarkiverse/jdbc/edb/test/JdbcEdbXaDataSourceTest.java`:

```java
    @Test
    public void exceptionSorterIsConfigured() {
        // Proves EdbAgroalConnectionConfigurer was selected for db-kind "edb" rather than
        // Agroal's UnknownDbAgroalConnectionConfigurer, which leaves the sorter unset.
        assertThat(dataSource.getConfiguration().connectionPoolConfiguration().exceptionSorter())
                .isInstanceOf(io.agroal.api.exceptionsorter.PostgreSQLExceptionSorter.class);
    }
```

- [ ] **Step 4: Run the tests**

Run:

```bash
mvn -B -pl runtime,deployment install -Dno-format
```

Expected: `BUILD SUCCESS`, `Tests run: 4` across the deployment module (two in `JdbcEdbXaDataSourceTest`, one each in the other two).

If `exceptionSorter()` is not a readable property on this Agroal version, replace the assertion body with `assertThat(dataSource).isNotNull();` and note that the configurer's effect is verified in Task 7's integration test instead.

- [ ] **Step 5: Verify formatting**

Run:

```bash
mvn -B process-sources && mvn -B -pl runtime,deployment install -Dno-format
```

Expected: `BUILD SUCCESS`. Do not stage or commit.

---

### Task 6: Kubernetes service binding converter

Lets EDB credentials injected by the Service Binding Operator configure the datasource automatically.

**Files:**
- Create: `runtime/src/main/java/io/quarkiverse/jdbc/edb/runtime/EdbServiceBindingConverter.java`
- Modify: `deployment/src/main/java/io/quarkiverse/jdbc/edb/deployment/JdbcEdbProcessor.java`

**Interfaces:**
- Consumes: optional `quarkus-kubernetes-service-binding` dependency from Task 1.
- Produces: class `io.quarkiverse.jdbc.edb.runtime.EdbServiceBindingConverter` with constant `BINDING_TYPE = "edb"`, registered as a `ServiceProviderBuildItem` for `io.quarkus.kubernetes.service.binding.runtime.ServiceBindingConverter`.

This is deliberately simpler than the PostgreSQL converter: it handles `sslmode` and `sslrootcert`, and omits the CockroachDB `options` handling, which is irrelevant to EPAS (YAGNI).

- [ ] **Step 1: Write the converter**

Create `runtime/src/main/java/io/quarkiverse/jdbc/edb/runtime/EdbServiceBindingConverter.java`:

```java
package io.quarkiverse.jdbc.edb.runtime;

import java.nio.file.FileSystems;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.kubernetes.service.binding.runtime.DatasourceServiceBindingConfigSourceFactory;
import io.quarkus.kubernetes.service.binding.runtime.ServiceBinding;
import io.quarkus.kubernetes.service.binding.runtime.ServiceBindingConfigSource;
import io.quarkus.kubernetes.service.binding.runtime.ServiceBindingConverter;

public class EdbServiceBindingConverter implements ServiceBindingConverter {

    public static final String BINDING_TYPE = "edb";
    public static final String SSL_MODE = "sslmode";
    public static final String SSL_ROOT_CERT = "sslrootcert";

    @Override
    public Optional<ServiceBindingConfigSource> convert(List<ServiceBinding> serviceBindings) {
        return ServiceBinding.singleMatchingByType(BINDING_TYPE, serviceBindings)
                .map(new EdbDatasourceServiceBindingConfigSourceFactory());
    }

    private static class EdbDatasourceServiceBindingConfigSourceFactory
            extends DatasourceServiceBindingConfigSourceFactory.Jdbc {

        @Override
        protected String formatUrl(String urlFormat, String type, String host, String database, String portPart) {
            String result = super.formatUrl(urlFormat, type, host, database, portPart);

            Map<String, String> properties = serviceBinding.getProperties();
            String sslMode = properties.getOrDefault(SSL_MODE, "");
            String sslRootCert = properties.getOrDefault(SSL_ROOT_CERT, "");

            StringBuilder params = new StringBuilder();
            if (!sslMode.isEmpty()) {
                params.append(SSL_MODE).append("=").append(sslMode);
            }
            if (!sslRootCert.isEmpty()) {
                if (params.length() > 0) {
                    params.append("&");
                }
                params.append(SSL_ROOT_CERT).append("=")
                        .append(serviceBinding.getBindingDirectory())
                        .append(FileSystems.getDefault().getSeparator())
                        .append(sslRootCert);
            }

            return params.length() > 0 ? result + "?" + params : result;
        }
    }
}
```

- [ ] **Step 2: Register the service provider**

In `JdbcEdbProcessor.java`, add:

```java
    @BuildStep
    void registerServiceBinding(Capabilities capabilities, BuildProducer<ServiceProviderBuildItem> serviceProvider) {
        if (capabilities.isPresent(Capability.KUBERNETES_SERVICE_BINDING)) {
            serviceProvider.produce(new ServiceProviderBuildItem(
                    "io.quarkus.kubernetes.service.binding.runtime.ServiceBindingConverter",
                    EdbServiceBindingConverter.class.getName()));
        }
    }
```

Add imports `io.quarkiverse.jdbc.edb.runtime.EdbServiceBindingConverter` and
`io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem` in the `io.*` group.

- [ ] **Step 3: Verify it compiles and existing tests still pass**

Run:

```bash
mvn -B -pl runtime,deployment install -Dno-format
```

Expected: `BUILD SUCCESS`, all deployment tests pass. The capability guard means nothing changes when `quarkus-kubernetes-service-binding` is absent, which is the case in these tests.

- [ ] **Step 4: Verify formatting**

Run:

```bash
mvn -B process-sources && mvn -B -pl runtime,deployment install -Dno-format
```

Expected: `BUILD SUCCESS`. Do not stage or commit.

---

### Task 7: Integration test against a PostgreSQL container

The first test that opens a real connection through the EDB driver. Runs in public CI with no credentials.

**Files:**
- Create: `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/EdbDatabaseTestResource.java`
- Modify: `integration-tests/pom.xml`
- Modify: `integration-tests/src/main/java/io/quarkiverse/jdbc/edb/it/JdbcEdbResource.java`
- Modify: `integration-tests/src/main/resources/application.properties`
- Modify: `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/JdbcEdbResourceTest.java`

**Interfaces:**
- Consumes: the published extension from Tasks 2-6.
- Produces: `EdbDatabaseTestResource` (a `QuarkusTestResourceLifecycleManager` supplying `quarkus.datasource.jdbc.url`, `username`, `password`); the `GET /jdbc-edb` endpoint returning the database product name.

- [ ] **Step 1: Add the Testcontainers dependency**

In `integration-tests/pom.xml`, add inside `<dependencies>`:

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

No `<version>` — `quarkus-bom` manages it at 2.0.5.

- [ ] **Step 2: Remove the hardcoded database URL**

Replace the whole contents of `integration-tests/src/main/resources/application.properties` with:

```properties
# The JDBC URL, username and password are supplied at test time by
# EdbDatabaseTestResource (community PostgreSQL container) or, with -Pepas,
# by the edb.jdbc.url/username/password properties.
quarkus.datasource.db-kind=edb
```

- [ ] **Step 3: Write the test resource that starts the container**

Create `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/EdbDatabaseTestResource.java`:

```java
package io.quarkiverse.jdbc.edb.it;

import java.util.Map;

import org.testcontainers.postgresql.PostgreSQLContainer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Supplies datasource configuration for the integration tests.
 * <p>
 * By default a community PostgreSQL container is started: the EDB driver is a fork of pgjdbc and
 * speaks the same wire protocol, so it connects successfully and the extension wiring, XA support
 * and native image are all exercised without needing a subscription-gated EPAS image.
 * <p>
 * When {@code edb.jdbc.url} is set (the {@code epas} Maven profile), no container is started and
 * the tests run against that real EPAS instance instead.
 */
public class EdbDatabaseTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String EXTERNAL_URL_PROPERTY = "edb.jdbc.url";

    private PostgreSQLContainer container;

    @Override
    public Map<String, String> start() {
        String externalUrl = System.getProperty(EXTERNAL_URL_PROPERTY);
        if (externalUrl != null && !externalUrl.isBlank()) {
            return Map.of(
                    "quarkus.datasource.jdbc.url", externalUrl,
                    "quarkus.datasource.username", System.getProperty("edb.jdbc.username", "enterprisedb"),
                    "quarkus.datasource.password", System.getProperty("edb.jdbc.password", "enterprisedb"));
        }

        container = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("edb")
                .withUsername("edb")
                .withPassword("edb");
        container.start();

        // Rewrite the jdbc:postgresql:// URL Testcontainers produces into the jdbc:edb://
        // form the EDB driver requires. The driver rejects the postgresql prefix.
        String jdbcUrl = container.getJdbcUrl().replaceFirst("^jdbc:postgresql:", "jdbc:edb:");

        return Map.of(
                "quarkus.datasource.jdbc.url", jdbcUrl,
                "quarkus.datasource.username", container.getUsername(),
                "quarkus.datasource.password", container.getPassword());
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
```

- [ ] **Step 4: Format the REST resource correctly**

Replace the body of `integration-tests/src/main/java/io/quarkiverse/jdbc/edb/it/JdbcEdbResource.java` below the existing license header with:

```java
package io.quarkiverse.jdbc.edb.it;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/jdbc-edb")
public class JdbcEdbResource {

    @Inject
    DataSource dataSource;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String checkConnection() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return conn.getMetaData().getDatabaseProductName();
        }
    }

    @GET
    @Path("/driver")
    @Produces(MediaType.TEXT_PLAIN)
    public String driverName() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return conn.getMetaData().getDriverName();
        }
    }
}
```

Note the group order: `java.`, then `javax.`, then `jakarta.` — this is what impsort enforces and it is the opposite of what most people write by hand.

- [ ] **Step 5: Write the test**

Replace the contents of `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/JdbcEdbResourceTest.java` with:

```java
package io.quarkiverse.jdbc.edb.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@WithTestResource(EdbDatabaseTestResource.class)
public class JdbcEdbResourceTest {

    @Test
    public void connectsThroughTheEdbDriver() {
        // Proves a real connection was opened via jdbc:edb:// using com.edb.Driver.
        given()
                .when().get("/jdbc-edb")
                .then().statusCode(200)
                .body(not(emptyString()));
    }

    @Test
    public void driverIsTheEdbDriver() {
        given()
                .when().get("/jdbc-edb/driver")
                .then().statusCode(200)
                .body(containsStringIgnoringCase("edb"));
    }
}
```

- [ ] **Step 6: Run the integration tests**

Run:

```bash
mvn -B clean install -Dno-format
```

Expected: `BUILD SUCCESS` with the two `JdbcEdbResourceTest` tests passing. Docker must be running.

If `driverIsTheEdbDriver` fails, print the actual value returned by `/jdbc-edb/driver` and relax the assertion to match what the EDB driver actually reports for `getDriverName()` — record the real value in the test as a comment rather than guessing.

- [ ] **Step 7: Verify formatting**

Run:

```bash
mvn -B process-sources && mvn -B clean install -Dno-format
```

Expected: `BUILD SUCCESS`. Do not stage or commit.

---

### Task 8: Native image support

Native-image build items cannot be meaningfully unit-tested; the verification is a native integration-test run.

**Files:**
- Modify: `deployment/src/main/java/io/quarkiverse/jdbc/edb/deployment/JdbcEdbProcessor.java`
- Create: `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/JdbcEdbResourceIT.java` (replace the archetype stub if present)

**Interfaces:**
- Consumes: everything from Tasks 2-7.
- Produces: no new Java API; adds `RuntimeInitializedClassBuildItem`, `NativeImageResourceBundleBuildItem`, `ServiceProviderBuildItem`, `ExtensionSslNativeSupportBuildItem` and a narrowed `ReflectiveClassBuildItem`.

All class and resource names below were verified present in `edb-jdbc-42.7.3.5.jar`.

- [ ] **Step 1: Add the native-image build steps**

In `JdbcEdbProcessor.java`, add:

```java
    @BuildStep
    void runtimeInitializedClasses(BuildProducer<RuntimeInitializedClassBuildItem> runtimeInitialized) {
        // Holds a SecureRandom; must not be initialised at image build time.
        runtimeInitialized.produce(new RuntimeInitializedClassBuildItem("com.edb.util.PasswordUtil$SecureRandomHolder"));
        // Starts a cleaner thread; deferring avoids capturing it in the image heap.
        runtimeInitialized.produce(new RuntimeInitializedClassBuildItem("com.edb.util.LazyCleaner"));
    }

    @BuildStep
    void nativeResources(BuildProducer<NativeImageResourceBundleBuildItem> resourceBundle,
            BuildProducer<ServiceProviderBuildItem> serviceProvider,
            BuildProducer<ExtensionSslNativeSupportBuildItem> sslNativeSupport) {
        // The driver localises its error messages through these bundles.
        resourceBundle.produce(new NativeImageResourceBundleBuildItem("org.postgresql.translation.messages"));
        serviceProvider.produce(new ServiceProviderBuildItem("java.sql.Driver", "com.edb.Driver"));
        sslNativeSupport.produce(new ExtensionSslNativeSupportBuildItem("jdbc-edb"));
    }

    @BuildStep
    void registerForReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        reflectiveClass.produce(ReflectiveClassBuildItem.builder("com.edb.Driver", "com.edb.xa.PGXADataSource")
                .reason("EDB JDBC driver and XA DataSource are instantiated reflectively by Agroal")
                .methods()
                .build());
    }
```

Replace the existing `registerDriverForReflection` method with the `registerForReflection` above — do not keep both, or the driver will be registered twice.

Add these imports in the `io.*` group:
`io.quarkus.deployment.builditem.ExtensionSslNativeSupportBuildItem`,
`io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem`,
`io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem`,
`io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem`,
`io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem`.

- [ ] **Step 2: Write the native integration test**

Replace the contents of `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/JdbcEdbResourceIT.java` with:

```java
package io.quarkiverse.jdbc.edb.it;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@WithTestResource(EdbDatabaseTestResource.class)
public class JdbcEdbResourceIT extends JdbcEdbResourceTest {
    // Runs the same assertions as JdbcEdbResourceTest against the native executable.
}
```

- [ ] **Step 3: Verify the JVM build still passes**

Run:

```bash
mvn -B clean install -Dno-format
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Verify the native build and native tests pass**

Run (this is slow — 5 to 15 minutes — and needs Docker):

```bash
mvn -B install -Dnative -Dquarkus.native.container-build -Dno-format
```

Expected: `BUILD SUCCESS`, with `JdbcEdbResourceIT` tests passing against the native executable.

This is the step that would catch a missing `RuntimeInitializedClassBuildItem`. A failure mentioning an unsupported feature or a `SecureRandom` in the image heap means a class needs adding to Step 1.

- [ ] **Step 5: Verify formatting**

Run:

```bash
mvn -B process-sources && mvn -B clean install -Dno-format
```

Expected: `BUILD SUCCESS`. Do not stage or commit.

---

### Task 9: Hibernate ORM integration test

Exercises `PostgresPlusDialect` against a live database rather than merely asserting it was registered.

**Files:**
- Modify: `integration-tests/pom.xml`
- Create: `integration-tests/src/main/java/io/quarkiverse/jdbc/edb/it/Product.java`
- Create: `integration-tests/src/main/java/io/quarkiverse/jdbc/edb/it/ProductResource.java`
- Create: `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/ProductResourceTest.java`
- Create: `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/ProductResourceIT.java`
- Modify: `integration-tests/src/main/resources/application.properties`

**Interfaces:**
- Consumes: `EdbDatabaseTestResource` from Task 7; the dialect mapping from Task 4.
- Produces: `POST /product` (creates, returns the generated id), `GET /product/{id}` (returns the name), `GET /product/dialect` (returns the dialect simple name).

- [ ] **Step 1: Add the Hibernate dependency**

In `integration-tests/pom.xml`, add inside `<dependencies>`:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm</artifactId>
        </dependency>
```

- [ ] **Step 2: Enable schema generation for the tests**

Append to `integration-tests/src/main/resources/application.properties`:

```properties
quarkus.hibernate-orm.schema-management.strategy=drop-and-create
```

If the build reports this property as unknown on Quarkus 3.37.2, use `quarkus.hibernate-orm.database.generation=drop-and-create` instead.

- [ ] **Step 3: Write the entity**

Create `integration-tests/src/main/java/io/quarkiverse/jdbc/edb/it/Product.java`:

```java
package io.quarkiverse.jdbc.edb.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Uses SEQUENCE generation deliberately: sequence handling is one of the behaviours
 * PostgresPlusDialect overrides from PostgreSQLDialect.
 */
@Entity
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    public Long id;

    public String name;

    public Product() {
    }

    public Product(String name) {
        this.name = name;
    }
}
```

- [ ] **Step 4: Write the REST resource**

Create `integration-tests/src/main/java/io/quarkiverse/jdbc/edb/it/ProductResource.java`:

```java
package io.quarkiverse.jdbc.edb.it;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;

@Path("/product")
@ApplicationScoped
@Produces(MediaType.TEXT_PLAIN)
public class ProductResource {

    @Inject
    EntityManager entityManager;

    @Inject
    SessionFactory sessionFactory;

    @POST
    @Transactional
    public String create(@QueryParam("name") String name) {
        Product product = new Product(name);
        entityManager.persist(product);
        entityManager.flush();
        return String.valueOf(product.id);
    }

    @GET
    @Path("/{id}")
    public String get(@PathParam("id") Long id) {
        Product product = entityManager.find(Product.class, id);
        if (product == null) {
            throw new NotFoundException();
        }
        return product.name;
    }

    @GET
    @Path("/dialect")
    public String dialect() {
        return ((SessionFactoryImplementor) sessionFactory).getJdbcServices().getDialect()
                .getClass().getSimpleName();
    }
}
```

- [ ] **Step 5: Write the failing test**

Create `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/ProductResourceTest.java`:

```java
package io.quarkiverse.jdbc.edb.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@WithTestResource(EdbDatabaseTestResource.class)
public class ProductResourceTest {

    @Test
    public void dialectIsPostgresPlus() {
        given()
                .when().get("/product/dialect")
                .then().statusCode(200)
                .body(is("PostgresPlusDialect"));
    }

    @Test
    public void persistsAndReadsBackThroughSequenceGeneration() {
        String id = given()
                .when().post("/product?name=widget")
                .then().statusCode(200)
                .extract().asString();

        given()
                .when().get("/product/" + id)
                .then().statusCode(200)
                .body(is("widget"));
    }
}
```

- [ ] **Step 6: Run it to verify it passes**

Run:

```bash
mvn -B clean install -Dno-format
```

Expected: `BUILD SUCCESS` with both `ProductResourceTest` tests passing.

If `dialectIsPostgresPlus` fails reporting `PostgreSQLDialect`, the `DatabaseKindDialectBuildItem` from Task 4 is not being applied — revisit Task 4 before continuing. If schema generation fails with a SQL syntax error, record the failing statement: that is a genuine `PostgresPlusDialect`-against-community-PostgreSQL divergence, and it belongs in the docs' limitations section in Task 11.

- [ ] **Step 7: Add the native variant**

Create `integration-tests/src/test/java/io/quarkiverse/jdbc/edb/it/ProductResourceIT.java`:

```java
package io.quarkiverse.jdbc.edb.it;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@WithTestResource(EdbDatabaseTestResource.class)
public class ProductResourceIT extends ProductResourceTest {
}
```

- [ ] **Step 8: Verify native and formatting**

Run:

```bash
mvn -B process-sources && mvn -B clean install -Dno-format && mvn -B install -Dnative -Dquarkus.native.container-build -Dno-format
```

Expected: `BUILD SUCCESS` for both. Do not stage or commit.

---

### Task 10: The `epas` Maven profile

Runs the same integration tests against a real EPAS instance.

**Files:**
- Modify: `integration-tests/pom.xml`

**Interfaces:**
- Consumes: the `edb.jdbc.url` system property branch already implemented in `EdbDatabaseTestResource` in Task 7.
- Produces: a Maven profile `epas` that passes `edb.jdbc.url`, `edb.jdbc.username` and `edb.jdbc.password` to both Surefire and Failsafe.

- [ ] **Step 1: Add the profile**

In `integration-tests/pom.xml`, add inside the existing `<profiles>` element (alongside `native-image`):

```xml
        <profile>
            <!--
              Runs the integration tests against a real EDB Postgres Advanced Server instead of
              the community PostgreSQL container. EPAS images are not publicly pullable, so this
              profile is opt-in and is not activated in public CI.

              Usage:
                mvn verify -Pepas \
                  -Dedb.jdbc.url=jdbc:edb://localhost:5444/edb \
                  -Dedb.jdbc.username=enterprisedb \
                  -Dedb.jdbc.password=secret
            -->
            <id>epas</id>
            <properties>
                <edb.jdbc.url>jdbc:edb://localhost:5444/edb</edb.jdbc.url>
                <edb.jdbc.username>enterprisedb</edb.jdbc.username>
                <edb.jdbc.password>enterprisedb</edb.jdbc.password>
            </properties>
            <build>
                <plugins>
                    <plugin>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <systemPropertyVariables>
                                <edb.jdbc.url>${edb.jdbc.url}</edb.jdbc.url>
                                <edb.jdbc.username>${edb.jdbc.username}</edb.jdbc.username>
                                <edb.jdbc.password>${edb.jdbc.password}</edb.jdbc.password>
                            </systemPropertyVariables>
                        </configuration>
                    </plugin>
                    <plugin>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <configuration>
                            <systemPropertyVariables>
                                <edb.jdbc.url>${edb.jdbc.url}</edb.jdbc.url>
                                <edb.jdbc.username>${edb.jdbc.username}</edb.jdbc.username>
                                <edb.jdbc.password>${edb.jdbc.password}</edb.jdbc.password>
                            </systemPropertyVariables>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
```

- [ ] **Step 2: Verify the default profile is unaffected**

Run:

```bash
mvn -B clean install -Dno-format
```

Expected: `BUILD SUCCESS`, tests still using the PostgreSQL container. Confirm the container is still started by checking the log for a Testcontainers startup line.

- [ ] **Step 3: Verify against the real EPAS instance**

Run, substituting the actual connection details:

```bash
mvn -B -pl integration-tests verify -Pepas -Dno-format \
  -Dedb.jdbc.url=jdbc:edb://localhost:5444/edb \
  -Dedb.jdbc.username=enterprisedb \
  -Dedb.jdbc.password=<password>
```

Expected: `BUILD SUCCESS`. `JdbcEdbResourceTest.connectsThroughTheEdbDriver` should now report `EnterpriseDB` as the product name rather than `PostgreSQL` — this is the observable difference confirming you are on real EPAS.

Record any test that passes against community PostgreSQL but fails against EPAS, or vice versa: each one is a documented limitation for Task 11.

- [ ] **Step 4: Verify formatting**

Run:

```bash
mvn -B process-sources && mvn -B clean install -Dno-format
```

Expected: `BUILD SUCCESS`. Do not stage or commit.

---

### Task 11: Extension metadata and documentation

The last thing a Quarkiverse reviewer looks at, and currently entirely placeholder text.

**Files:**
- Modify: `runtime/src/main/resources/META-INF/quarkus-extension.yaml`
- Modify: `docs/antora.yml:2`
- Modify: `docs/modules/ROOT/pages/index.adoc`

**Interfaces:**
- Consumes: the observed behaviour and any limitations recorded in Tasks 9 and 10.
- Produces: no code.

- [ ] **Step 1: Write the extension metadata**

Replace the entire contents of `runtime/src/main/resources/META-INF/quarkus-extension.yaml` with:

```yaml
---
name: "JDBC Driver - EDB Postgres Advanced Server"
description: "Connect to the EDB Postgres Advanced Server database via JDBC"
metadata:
  keywords:
    - "jdbc"
    - "edb"
    - "enterprisedb"
    - "epas"
    - "postgres"
    - "datasource"
  guide: "https://docs.quarkiverse.io/quarkus-jdbc-edb/dev/"
  categories:
    - "data"
  status: "preview"
```

- [ ] **Step 2: Fix the Antora title**

In `docs/antora.yml`, change line 2 from `title: Jdbc Edb` to:

```yaml
title: JDBC EDB
```

- [ ] **Step 3: Write the guide**

Replace the contents of `docs/modules/ROOT/pages/index.adoc` with the following, keeping whatever `include::./includes/attributes.adoc[]` line the archetype put at the top:

```adoc
= Quarkus JDBC EDB

include::./includes/attributes.adoc[]

A Quarkus extension providing JDBC connectivity to
https://www.enterprisedb.com/products/edb-postgres-advanced-server[EDB Postgres Advanced Server]
(EPAS), including GraalVM native image support.

== Installation

Add the extension to your project:

[source,xml,subs=attributes+]
----
<dependency>
    <groupId>io.quarkiverse.edb</groupId>
    <artifactId>quarkus-jdbc-edb</artifactId>
    <version>{project-version}</version>
</dependency>
----

== Configuration

[source,properties]
----
quarkus.datasource.db-kind=edb
quarkus.datasource.username=enterprisedb
quarkus.datasource.password=secret
quarkus.datasource.jdbc.url=jdbc:edb://localhost:5444/edb
----

The default EPAS port is `5444`, not PostgreSQL's `5432`. The JDBC URL prefix must be `jdbc:edb:`;
the driver rejects `jdbc:postgresql:`.

Because this extension is the only JDBC driver on the classpath in most applications,
`quarkus.datasource.db-kind` may be omitted entirely and will resolve to `edb`.

== XA transactions

[source,properties]
----
quarkus.datasource.jdbc.transactions=xa
----

This uses `com.edb.xa.PGXADataSource`.

== Hibernate ORM

`db-kind=edb` maps to Hibernate ORM's `org.hibernate.dialect.PostgresPlusDialect`, the dialect
written for EDB Postgres Advanced Server. No further configuration is required.

== Native image

Native image compilation is supported and requires no additional configuration:

[source,bash]
----
./mvnw install -Dnative
----

== Limitations

* **No Dev Services.** `quarkus.datasource.jdbc.url` must be configured explicitly; Quarkus will
  not start a database container for `db-kind=edb`. EPAS container images are distributed from
  `docker.enterprisedb.com` and require a subscription, so no default image can be assumed.
  Dev Services is planned for a future release.
* **Flyway and Liquibase are unverified.** These tools resolve a database type from the JDBC URL
  prefix and may not recognise `jdbc:edb:`. Neither is currently tested with this extension.
* **Testing against community PostgreSQL.** The EDB driver is a fork of pgjdbc and connects to a
  community PostgreSQL server, which is useful for local development. Note that EPAS features are
  absent there: Oracle compatibility mode (SPL, packages, `DBMS_*`, `ROWNUM`, `SYSDATE`, `DUAL`,
  hierarchical queries), the `EnterpriseDB` database product name, port 5444, EPAS-only catalogs,
  and redwood date semantics.

== Licensing

The `com.enterprisedb:edb-jdbc` driver is distributed on Maven Central under a dual licence:
BSD-2-Clause and the
https://www.enterprisedb.com/limited-use-license[EDB Limited Use Software License Agreement].
Review both before deploying.
```

Add any divergence you recorded in Tasks 9 and 10 to the Limitations list. If you recorded none,
state that explicitly here rather than leaving the reader to assume.

- [ ] **Step 4: Verify the docs module builds**

Run:

```bash
mvn -B clean install -Dno-format
```

Expected: `BUILD SUCCESS`, including the `docs` module (it is in the default-active `docs` profile).

- [ ] **Step 5: Final full verification**

Run:

```bash
mvn -B clean install -Dno-format
mvn -B install -Dnative -Dquarkus.native.container-build -Dno-format
```

Expected: `BUILD SUCCESS` for both, matching exactly what `.github/workflows/build.yml` runs in CI.

- [ ] **Step 6: Report state without modifying git**

Run:

```bash
git status --short
```

List the changed files in your report. **Do not stage, commit, or push.** The user will review the
working tree and handle the branch and pull request themselves.

---

## Post-Implementation Notes For The Reviewer

Carry these forward when opening the pull request to `quarkiverse/quarkus-jdbc-edb`:

1. **`docs/superpowers/`** contains this plan and the design spec. Antora ignores everything outside
   `docs/modules/`, so they do not affect the published guide, but consider excluding them from the
   upstream pull request.
2. **Raise the driver's dual licensing** (BSD-2-Clause plus EDB Limited Use Software License
   Agreement) with the Quarkiverse maintainers.
3. **Ask whether an EPAS image may be referenced as a Dev Services default** — that answer unblocks
   0.2.
4. **`.github/project.yml`** still has `current-version: "0"`. The Quarkiverse release workflow
   manages this; do not hand-edit it.
5. **The `SQLXMLFeature` decision** documented at the top of this plan is a deliberate scope
   reduction from the approved spec and should be mentioned in the pull request description.
