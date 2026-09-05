package io.github.ivarm1984.banksim;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need a real Postgres, not H2: schema-per-module DDL,
 * pessimistic locking, and jOOQ-generated types all need real Postgres
 * semantics to be meaningful.
 *
 * <p>Deliberately NOT using {@code @Testcontainers}/{@code @Container} here:
 * that extension stops the container in {@code afterAll} of whichever test
 * class triggers it, which breaks a container meant to be shared (via this
 * static field) across every subclass. Starting it in a static initializer
 * instead makes it a true JVM-wide singleton - one container for the whole
 * test run, reaped by Ryuk at JVM exit. See Testcontainers' "singleton
 * container" pattern.
 */
@Tag("integration")
@SpringBootTest
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withDatabaseName("banksim")
                .withUsername("banksim")
                .withPassword("banksim");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
