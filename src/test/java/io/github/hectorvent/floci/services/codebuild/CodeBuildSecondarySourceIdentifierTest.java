package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A secondary source's identifier is resolved directly into a host filesystem path
 * ({@code root.resolve(identifier)}) and later interpolated unquoted into an in-container shell
 * command and a {@code CODEBUILD_SRC_DIR_<identifier>} env var name. botocore documents it as
 * alphanumeric-and-underscore only; an unvalidated value like {@code ../../etc} would otherwise
 * escape the source-staging directory, and one containing shell metacharacters would inject a
 * command.
 */
class CodeBuildSecondarySourceIdentifierTest {

    private CodeBuildRunner runner() {
        return new CodeBuildRunner(null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ProjectSource secondary(String identifier) {
        ProjectSource s = new ProjectSource();
        s.setSourceIdentifier(identifier);
        s.setType("S3");
        s.setLocation("bucket/key.zip");
        return s;
    }

    @Test
    void pathTraversalIdentifierIsRejectedNotResolvedIntoAPath(@TempDir Path tempDir) throws Exception {
        Build build = new Build();
        build.setId("b-1");
        build.setSecondarySources(List.of(secondary("../../escaped")));

        var dirs = runner().acquireSecondarySources(build, tempDir);

        assertTrue(dirs.isEmpty(), "an unsafe identifier must not be resolved into any path");
        assertFalse(Files.exists(tempDir.resolve("../../escaped").normalize()),
                "the traversal target must never be created on disk");
    }

    @Test
    void shellMetacharacterIdentifierIsRejected(@TempDir Path tempDir) throws Exception {
        Build build = new Build();
        build.setId("b-1");
        build.setSecondarySources(List.of(secondary("a; rm -rf /")));

        var dirs = runner().acquireSecondarySources(build, tempDir);

        assertTrue(dirs.isEmpty(), "an identifier with shell metacharacters must not be accepted");
    }

    @Test
    void alphanumericUnderscoreIdentifierIsAccepted(@TempDir Path tempDir) throws Exception {
        Build build = new Build();
        build.setId("b-1");
        ProjectSource s = secondary("Config_1");
        s.setLocation(null);
        build.setSecondarySources(List.of(s));

        var dirs = runner().acquireSecondarySources(build, tempDir);

        assertTrue(dirs.containsKey("Config_1"));
        assertTrue(Files.isDirectory(dirs.get("Config_1")));
    }
}
