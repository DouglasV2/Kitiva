package ai.budgetspace.product;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C2b — the beauty columns on {@code products}, and the guard that keeps V6 in step with the entity.
 *
 * <p><strong>Why a drift guard matters here specifically.</strong> Dev runs {@code ddl-auto=create} with
 * Flyway OFF, so Hibernate builds the schema from the entity and the migration is never executed locally.
 * Production is the opposite: Flyway owns the schema and {@code ddl-auto=validate} checks the entity against
 * it. A column added to the entity but forgotten in V6 therefore works perfectly on every developer machine
 * and fails at production boot. {@code ProdSchemaBootIT} catches it, but only when someone has a Postgres
 * and the {@code BUDGETSPACE_BOOTTEST_DB_URL} env var set — it silently skips otherwise. This test needs
 * neither, so the mistake is caught on any machine.</p>
 */
class BeautyProductColumnsTest {

    /** Columns V6 introduces. Kept explicit so adding one to the entity forces a decision about the migration. */
    private static final List<String> BEAUTY_COLUMNS = List.of(
            "brand", "product_line", "shade_name", "shade_code", "size_ml",
            "coverage", "finish_tag", "formula_format", "shade_depth", "undertone", "required_applicator",
            "eye_area_safe_claim", "fragrance_free_claim",
            "nail_system", "application_role", "curing_required", "recommended_lamp", "cure_time_seconds",
            "removal_method", "effect_type", "magnet_required", "beginner_suitability",
            "hema_status", "di_hema_status", "tpo_status", "professional_only",
            "inci_source", "inci_verified_at",
            "safety_verdict", "safety_verdict_at", "safety_ruleset_version");

    /** Full file, comments included — for checks that only need to see a column name mentioned. */
    private String migrationSql() throws Exception {
        try (var in = BeautyProductColumnsTest.class.getResourceAsStream("/db/migration/V6__beauty_product_columns.sql")) {
            assertThat(in).as("V6 migration must exist").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Executable DDL only, with {@code --} comments stripped.
     *
     * <p>Needed because this migration explains itself at length, and the prose legitimately contains the
     * very phrases the DDL must not: it says a NOT NULL column would rewrite the table, and that a DEFAULT
     * would itself be an assertion about the product. Scanning the raw file for those strings flags the
     * commentary rather than the statements.</p>
     */
    private String migrationDdl() throws Exception {
        return migrationSql().lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment >= 0 ? line.substring(0, comment) : line;
                })
                .filter(line -> !line.isBlank())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private Field field(String javaName) {
        try {
            return Product.class.getDeclaredField(javaName);
        } catch (NoSuchFieldException ex) {
            throw new AssertionError("Product is missing the field " + javaName, ex);
        }
    }

    @Test
    void everyBeautyColumnOnTheEntityIsAlsoInTheV6Migration() throws Exception {
        String sql = migrationSql();
        List<String> missing = new ArrayList<>();
        for (String column : BEAUTY_COLUMNS) {
            if (!sql.contains(column)) missing.add(column);
        }
        assertThat(missing)
                .as("columns declared on Product but absent from V6 — these boot fine in dev and fail in prod")
                .isEmpty();
    }

    @Test
    void everyBeautyColumnIsDeclaredOnTheEntity() {
        List<String> declared = new ArrayList<>();
        for (Field f : Product.class.getDeclaredFields()) {
            Column column = f.getAnnotation(Column.class);
            if (column != null && !column.name().isBlank()) declared.add(column.name());
        }
        assertThat(declared).containsAll(BEAUTY_COLUMNS);
    }

    @Test
    void everyBeautyColumnIsNullableSoTheFurnitureCatalogMigratesUntouched() throws Exception {
        // 21,129 existing rows know nothing about nails. A NOT NULL column here would rewrite the whole
        // table and force a meaningless value onto every sofa.
        for (String columnName : BEAUTY_COLUMNS) {
            Field f = fieldForColumn(columnName);
            Column column = f.getAnnotation(Column.class);
            assertThat(column.nullable()).as("%s must be nullable", columnName).isTrue();
        }
        // Scoped to ADD COLUMN lines: the partial index legitimately ends "WHERE nail_system IS NOT NULL",
        // which is a predicate, not a column constraint.
        List<String> notNullColumns = migrationDdl().lines()
                .filter(l -> l.contains("add column"))
                .filter(l -> l.contains("not null"))
                .toList();
        assertThat(notNullColumns).as("V6 must not introduce a NOT NULL column").isEmpty();
    }

    @Test
    void unrecordedFlagsAreBoxedBooleansSoNullDoesNotCollapseIntoFalse() {
        // null means "we never recorded this", which is a different fact from false. A primitive would have
        // silently made every unrecorded product "not professional-only" — the exact shape of mistake the
        // whole tri-state design exists to prevent.
        for (String name : List.of("professionalOnly", "curingRequired", "magnetRequired")) {
            assertThat(field(name).getType())
                    .as("%s must be java.lang.Boolean, never primitive boolean", name)
                    .isEqualTo(Boolean.class);
        }
    }

    @Test
    void substanceStatusColumnsAreStringsNotBooleans() {
        // A boolean forces every unknown to false, and false reads as "does not contain" — which is how
        // incomplete retailer data turns into a safety claim. These hold a SubstancePresence name; NULL
        // parses to UNKNOWN, which blocks.
        for (String name : List.of("hemaStatus", "diHemaStatus", "tpoStatus")) {
            assertThat(field(name).getType())
                    .as("%s must hold a SubstancePresence name, not a boolean", name)
                    .isEqualTo(String.class);
        }
        assertThat(ai.budgetspace.beauty.safety.SubstancePresence.parse(null))
                .isEqualTo(ai.budgetspace.beauty.safety.SubstancePresence.UNKNOWN);
    }

    @Test
    void theMigrationIsRerunnableAndAddsNoDefaults() throws Exception {
        String ddl = migrationDdl();
        long addColumnCount = ddl.lines().filter(l -> l.contains("add column")).count();
        long ifNotExistsCount = ddl.lines().filter(l -> l.contains("add column if not exists")).count();

        assertThat(ifNotExistsCount)
                .as("every ADD COLUMN must be IF NOT EXISTS so a hand-patched database migrates cleanly")
                .isEqualTo(addColumnCount);
        assertThat(addColumnCount).isEqualTo(BEAUTY_COLUMNS.size());
        assertThat(ddl)
                .as("no DEFAULT on a safety column — a default would itself be an assertion about the product")
                .doesNotContain("default");
    }

    private Field fieldForColumn(String columnName) {
        for (Field f : Product.class.getDeclaredFields()) {
            Column column = f.getAnnotation(Column.class);
            if (column != null && columnName.equals(column.name())) return f;
        }
        throw new AssertionError("no @Column named " + columnName + " on Product");
    }
}
