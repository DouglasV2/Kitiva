package hr.kitiva;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Sprint 10.95 — the production cold-boot guard. Against a CLEAN Postgres (the same engine prod uses),
 * <strong>Flyway</strong> builds the schema from {@code V1__baseline.sql} + every later {@code V{n}} migration and
 * Hibernate <strong>{@code ddl-auto=validate}</strong> confirms each {@code @Entity} matches it. The assertion IS
 * that the Spring context starts.
 *
 * <p>Why this exists: the rest of the suite is plain JUnit/Mockito and never starts Spring or touches a DB, so the
 * moment an entity gains/renames a column without a matching migration the build stays green and the app crashes
 * only at <em>prod boot</em> on a {@code SchemaManagementException}. This test turns that latent, deploy-time
 * failure into a red build instead.</p>
 *
 * <p><strong>How it runs.</strong> Named {@code *IT}, so it runs under {@code mvn verify}, not the fast DB-free
 * {@code mvn test}. It only activates when pointed at a dedicated CLEAN database via
 * {@code BUDGETSPACE_BOOTTEST_DB_URL} (so it never runs against — and corrupts/false-fails on — a developer's
 * polluted dev DB). CI provisions a fresh Postgres service container and sets that env var; locally, start a
 * throwaway Postgres and export the var. When the var is unset the test is SKIPPED (reported, not failed).</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "BUDGETSPACE_BOOTTEST_DB_URL", matches = ".+",
        disabledReason = "Set BUDGETSPACE_BOOTTEST_DB_URL to a CLEAN Postgres to run the prod-schema boot guard")
class ProdSchemaBootIT {

    @DynamicPropertySource
    static void mirrorProdSchemaStrategy(DynamicPropertyRegistry registry) {
        // Point Spring at the dedicated clean DB supplied by the environment (CI service container / local throwaway).
        registry.add("spring.datasource.url", () -> System.getenv("BUDGETSPACE_BOOTTEST_DB_URL"));
        registry.add("spring.datasource.username", () -> envOr("BUDGETSPACE_BOOTTEST_DB_USER", "budgetspace"));
        registry.add("spring.datasource.password", () -> envOr("BUDGETSPACE_BOOTTEST_DB_PASSWORD", "budgetspace"));
        // Exactly the prod profile's schema contract: Flyway owns DDL, validate checks it, nothing drops/defers.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        // Keep the boot focused on the schema check — skip the catalog seeder's import (irrelevant to migrations).
        registry.add("budgetspace.real-catalog.seed-enabled", () -> "false");
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void bootsWithFlywaySchemaAndValidatesEntities() {
        // The assertion IS that the context started: Flyway migrated a fresh DB and ddl-auto=validate passed.
        // A missing/renamed column without a migration makes context startup throw, failing this test.
    }
}
