package io.github.hectorvent.floci.testing;

import java.nio.file.Path;

/**
 * Paths into {@code src/main/java} for tests that read Floci's own sources. They are derived from
 * classes rather than written out, so they keep pointing at the right files when the package moves.
 */
public final class MainSources {

    /** Floci's root package: the package this class sits in, minus {@code .testing}. */
    public static final String BASE_PACKAGE =
            MainSources.class.getPackageName().substring(0, MainSources.class.getPackageName().lastIndexOf('.'));

    /** The source directory of {@link #BASE_PACKAGE}. */
    public static final Path BASE_DIR = packageDir(BASE_PACKAGE);

    private MainSources() {
    }

    /** The source directory of the given class's package. */
    public static Path packageDir(Class<?> type) {
        return packageDir(type.getPackageName());
    }

    /** The source file of a top-level class. */
    public static Path sourceFile(Class<?> type) {
        if (type.getEnclosingClass() != null) {
            throw new IllegalArgumentException(type.getName() + " is nested; pass its top-level class");
        }
        return packageDir(type).resolve(type.getSimpleName() + ".java");
    }

    private static Path packageDir(String packageName) {
        return Path.of("src/main/java", packageName.replace('.', '/'));
    }
}
