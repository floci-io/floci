package io.github.hectorvent.floci.services.rds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The engine versions RDS reports through DescribeDBEngineVersions and
 * DescribeOrderableDBInstanceOptions. It is a curated subset, one representative version per major
 * version that Floci knows through its managed parameter and option groups, not AWS's full list.
 * Each engine's default is the version Floci uses when a create request names none, and each
 * family is the one the create path computes for that version.
 */
final class RdsEngineCatalog {

    /** One engine version; {@code instanceClasses} are its orderable classes. */
    record EngineVersion(String engine, String version, String majorVersion, String family,
                         String engineDescription, boolean isDefault, List<String> instanceClasses,
                         List<String> engineModes) {

        String versionDescription() {
            return engineDescription + " " + version;
        }
    }

    private static final List<String> PROVISIONED = List.of("provisioned");

    static final List<EngineVersion> VERSIONS = List.of(
            postgres("13.20", "13", false, List.of()),
            postgres("14.17", "14", false, List.of()),
            postgres("15.12", "15", false, List.of()),
            postgres("16.3", "16", true, List.of("db.t3.micro", "db.t4g.micro", "db.t4g.small", "db.t4g.medium")),
            postgres("16.14", "16", false, List.of("db.t3.micro", "db.t4g.small")),
            postgres("17.4", "17", false, List.of()),
            postgres("18.1", "18", false, List.of("db.t3.micro", "db.m8g.large")),
            postgres("18.4", "18", false, List.of("db.m8g.large")),
            new EngineVersion("mysql", "8.0.36", "8.0", "mysql8.0", "MySQL Community Edition", true,
                    List.of("db.t3.micro"), PROVISIONED),
            new EngineVersion("mysql", "8.4.4", "8.4", "mysql8.4", "MySQL Community Edition", false,
                    List.of(), PROVISIONED),
            new EngineVersion("mariadb", "10.11.11", "10.11", "mariadb10.11", "MariaDb Community Edition", false,
                    List.of(), PROVISIONED),
            new EngineVersion("mariadb", "11.2", "11.2", "mariadb11.2", "MariaDb Community Edition", true,
                    List.of("db.t3.micro"), PROVISIONED),
            new EngineVersion("mariadb", "11.4.5", "11.4", "mariadb11.4", "MariaDb Community Edition", false,
                    List.of(), PROVISIONED),
            new EngineVersion("sqlserver-se", "15.00", "15.00", "sqlserver15", "SQL Server Standard Edition", true,
                    List.of("db.t3.micro"), PROVISIONED),
            new EngineVersion("aurora-postgresql", "15.12", "15", "aurora-postgresql15",
                    "Aurora (PostgreSQL)", false, List.of(), PROVISIONED),
            new EngineVersion("aurora-postgresql", "16.3", "16", "aurora-postgresql16",
                    "Aurora (PostgreSQL)", true, List.of(), PROVISIONED),
            new EngineVersion("aurora-postgresql", "17.4", "17", "aurora-postgresql17",
                    "Aurora (PostgreSQL)", false, List.of(), PROVISIONED),
            new EngineVersion("aurora-mysql", "8.0.mysql_aurora.3.05.2", "8.0", "aurora-mysql8.0",
                    "Aurora MySQL", true, List.of(), PROVISIONED),
            new EngineVersion("aurora-mysql", "8.0.mysql_aurora.3.08.0", "8.0", "aurora-mysql8.0",
                    "Aurora MySQL", false, List.of(), PROVISIONED));

    private RdsEngineCatalog() {
    }

    private static EngineVersion postgres(String version, String major, boolean isDefault, List<String> classes) {
        return new EngineVersion("postgres", version, major, "postgres" + major, "PostgreSQL", isDefault,
                classes, PROVISIONED);
    }

    /**
     * Whether {@code requested} names {@code version}: the exact version, or a prefix of it ending
     * at a dot, so {@code 16} and {@code 16.3} both name {@code 16.3}.
     */
    static boolean versionMatches(String version, String requested) {
        String wanted = requested.toLowerCase(Locale.ROOT);
        String actual = version.toLowerCase(Locale.ROOT);
        return actual.equals(wanted) || actual.startsWith(wanted + ".");
    }

    /** The later versions of the same engine, oldest first, as ValidUpgradeTarget lists them. */
    static List<EngineVersion> upgradeTargets(EngineVersion from) {
        List<EngineVersion> targets = new ArrayList<>();
        for (EngineVersion candidate : VERSIONS) {
            if (candidate.engine().equals(from.engine()) && compareVersions(candidate.version(), from.version()) > 0) {
                targets.add(candidate);
            }
        }
        targets.sort(Comparator.comparing(EngineVersion::version, RdsEngineCatalog::compareVersions));
        return targets;
    }

    /** Numeric parts compare as numbers and the rest as text, so 16.14 sorts after 16.3. */
    static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "";
            String y = i < b.length ? b[i] : "";
            int cmp = x.matches("\\d+") && y.matches("\\d+")
                    ? Long.compare(Long.parseLong(x), Long.parseLong(y))
                    : x.compareTo(y);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** Whether a parameter group family is one the catalogue's versions use. */
    static boolean knowsFamily(String family) {
        return family != null && VERSIONS.stream().anyMatch(v -> v.family().equalsIgnoreCase(family));
    }
}
