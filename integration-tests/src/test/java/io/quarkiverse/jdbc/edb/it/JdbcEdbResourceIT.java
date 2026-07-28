package io.quarkiverse.jdbc.edb.it;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs the same assertions as {@link JdbcEdbResourceTest} against the native executable.
 */
@QuarkusIntegrationTest
@WithTestResource(EdbDatabaseTestResource.class)
public class JdbcEdbResourceIT extends JdbcEdbResourceTest {
}
