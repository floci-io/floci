package io.floci.aws.services.codeartifact;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomDependenciesTest {

    @Test
    void parseReadsTheParentAndEveryDirectDependencyWithTheirOwnDependencyType() {
        String pom = "<project>"
                + "<parent><groupId>com.example</groupId><artifactId>parent-pom</artifactId><version>1.0.0</version>"
                + "</parent>"
                + "<dependencies>"
                + "<dependency><groupId>com.example</groupId><artifactId>no-scope</artifactId>"
                + "<version>1.2.3</version></dependency>"
                + "<dependency><groupId>com.example</groupId><artifactId>test-scoped</artifactId>"
                + "<version>2.0.0</version><scope>test</scope></dependency>"
                + "<dependency><groupId>com.example</groupId><artifactId>optional-and-scoped</artifactId>"
                + "<version>3.0.0</version><scope>provided</scope><optional>true</optional></dependency>"
                + "</dependencies>"
                + "</project>";

        List<CodeArtifactService.PackageDependency> dependencies =
                PomDependencies.parse(pom.getBytes(StandardCharsets.UTF_8));

        assertEquals(4, dependencies.size());
        assertEquals(new CodeArtifactService.PackageDependency("com.example", "parent-pom", "parent", "1.0.0"),
                dependencies.get(0));
        assertEquals(new CodeArtifactService.PackageDependency("com.example", "no-scope", "compile", "1.2.3"),
                dependencies.get(1));
        assertEquals(new CodeArtifactService.PackageDependency("com.example", "test-scoped", "test", "2.0.0"),
                dependencies.get(2));
        assertEquals(new CodeArtifactService.PackageDependency("com.example", "optional-and-scoped", "optional",
                "3.0.0"), dependencies.get(3));
    }

    @Test
    void parseIgnoresDependencyManagementAndProfileDependencies() {
        String pom = "<project>"
                + "<dependencyManagement><dependencies>"
                + "<dependency><groupId>com.example</groupId><artifactId>managed-only</artifactId>"
                + "<version>9.9.9</version></dependency>"
                + "</dependencies></dependencyManagement>"
                + "<profiles><profile><id>extra</id><dependencies>"
                + "<dependency><groupId>com.example</groupId><artifactId>profile-only</artifactId>"
                + "<version>8.8.8</version></dependency>"
                + "</dependencies></profile></profiles>"
                + "<dependencies>"
                + "<dependency><groupId>com.example</groupId><artifactId>real-dependency</artifactId>"
                + "<version>1.0.0</version></dependency>"
                + "</dependencies>"
                + "</project>";

        List<CodeArtifactService.PackageDependency> dependencies =
                PomDependencies.parse(pom.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(new CodeArtifactService.PackageDependency("com.example", "real-dependency", "compile",
                "1.0.0")), dependencies);
    }

    @Test
    void parseReturnsNoRawPropertyResolution() {
        String pom = "<project><dependencies>"
                + "<dependency><groupId>com.example</groupId><artifactId>versioned-by-property</artifactId>"
                + "<version>${some.property}</version></dependency>"
                + "</dependencies></project>";

        List<CodeArtifactService.PackageDependency> dependencies =
                PomDependencies.parse(pom.getBytes(StandardCharsets.UTF_8));

        assertEquals("${some.property}", dependencies.get(0).versionRequirement());
    }

    @Test
    void parseReturnsNoDependenciesForAPomWithNeitherAParentNorADependenciesBlock() {
        String pom = "<project><groupId>com.example</groupId><artifactId>leaf</artifactId><version>1.0.0</version>"
                + "</project>";

        List<CodeArtifactService.PackageDependency> dependencies =
                PomDependencies.parse(pom.getBytes(StandardCharsets.UTF_8));

        assertTrue(dependencies.isEmpty());
    }
}
