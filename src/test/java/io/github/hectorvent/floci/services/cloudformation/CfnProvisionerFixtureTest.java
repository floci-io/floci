package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ProvisionContext;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The fixture wires provisioners from the services a test names.
 *
 * <p>This is what keeps a test honest across a migration. Before it, a test that named a service
 * and provisioned one of its types silently kept passing when that type moved into a provisioner:
 * the empty registry sent it to the dispatcher's stub arm, which reports CREATE_COMPLETE with a
 * synthetic physical id, so every assertion ran against a resource nothing had provisioned.
 */
class CfnProvisionerFixtureTest {

    /**
     * Provisioners the fixture cannot build from a single service, so they must be passed to
     * {@code provisioners(...)} explicitly. Both take collaborators beyond one service
     * ({@code RegionResolver} and an {@code ObjectMapper}), which the Builder does not model.
     */
    private static final Set<String> NOT_INFERABLE =
            Set.of("CodeBuildCfnProvisioner", "CodePipelineCfnProvisioner");

    /**
     * Builder setters that replace the inferred registry outright rather than naming a service.
     * Driving them in the sweep below would discard everything inference produced.
     */
    private static final Set<String> REGISTRY_OVERRIDES = Set.of("registry", "provisioners");

    /** Reaches the registry the same way the dispatcher does. */
    private static boolean serves(CfnProvisionerFixture.Builder builder, String type) {
        return builder.buildRegistry().forType(type).isPresent();
    }

    @Test
    void namingAServiceWiresItsProvisioner() {
        CfnProvisionerFixture.Builder fixture = CfnProvisionerFixture.builder()
                .logs(mock(CloudWatchLogsService.class));

        assertTrue(serves(fixture, "AWS::Logs::LogGroup"));
    }

    @Test
    void anUnnamedServiceIsNotWired() {
        CfnProvisionerFixture.Builder fixture = CfnProvisionerFixture.builder()
                .logs(mock(CloudWatchLogsService.class));

        assertFalse(serves(fixture, "AWS::SNS::Topic"),
                "a test that never named SNS should not get its provisioner");
    }

    /** One service can back several provisioners, as it does under CDI. */
    @Test
    void oneServiceCanWireSeveralProvisioners() {
        CfnProvisionerFixture.Builder fixture = CfnProvisionerFixture.builder()
                .ec2(mock(Ec2Service.class));

        for (String type : Set.of("AWS::EC2::VPC", "AWS::EC2::VPCEndpoint", "AWS::EC2::NetworkAcl",
                "AWS::EC2::LaunchTemplate", "AWS::EC2::SecurityGroupIngress")) {
            assertTrue(serves(fixture, type), type + " should be wired from Ec2Service");
        }
    }

    /** CDK::Metadata backs no service, so it is always available. */
    @Test
    void theServicelessProvisionerIsAlwaysWired() {
        assertTrue(serves(CfnProvisionerFixture.builder(), "AWS::CDK::Metadata"));
    }

    /**
     * Every provisioner on disk is reachable by driving the Builder's own public setters, or is
     * explicitly exempt.
     *
     * <p>Deliberately behavioural rather than textual. An earlier version of this gate matched
     * {@code new XCfnProvisioner(} against the fixture's source, which scored a construction arm as
     * wired even when its field had no setter and could never be non-null. That is the same silent
     * gap this fixture exists to close, so the check has to run the wiring instead of reading it:
     * everything below reaches the registry only through the public API a test has, which means a
     * field no setter assigns cannot be satisfied and shows up here as unreachable.
     *
     * <p>Mirrors {@code CfnResourceInventoryTest}, which compares the CDI-resolved production
     * registry for the same reason.
     */
    @Test
    void everyProvisionerIsReachableThroughThePublicBuilderOrExplicitlyExempt() throws Exception {
        Path provisioners = Path.of(
                "src/main/java/io/github/hectorvent/floci/services/cloudformation/provisioners");
        Set<String> onDisk;
        try (var files = Files.list(provisioners)) {
            onDisk = files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith("CfnProvisioner.java"))
                    .map(n -> n.replace(".java", ""))
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        CfnProvisionerFixture.Builder builder = CfnProvisionerFixture.builder();
        for (Method setter : CfnProvisionerFixture.Builder.class.getDeclaredMethods()) {
            boolean namesOneCollaborator = Modifier.isPublic(setter.getModifiers())
                    && setter.getReturnType() == CfnProvisionerFixture.Builder.class
                    && setter.getParameterCount() == 1
                    && !setter.isVarArgs()
                    && !REGISTRY_OVERRIDES.contains(setter.getName());
            if (namesOneCollaborator) {
                setter.invoke(builder, mock(setter.getParameterTypes()[0]));
            }
        }

        CloudFormationResourceRegistry registry = builder.buildRegistry();
        Set<String> reachable = registry.registeredTypes().stream()
                .map(registry::ownerOf)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> unreachable = new TreeSet<>(onDisk);
        unreachable.removeAll(reachable);
        unreachable.removeAll(NOT_INFERABLE);

        assertTrue(unreachable.isEmpty(),
                "These provisioners cannot be reached through any public Builder setter, so a "
                        + "fixture test naming their service would silently hit the stub arm. Give "
                        + "them a service field AND a setter in CfnProvisionerFixture, or add them "
                        + "to NOT_INFERABLE with a reason: " + unreachable);

        Set<String> staleExemptions = new TreeSet<>(NOT_INFERABLE);
        staleExemptions.removeAll(onDisk);
        assertTrue(staleExemptions.isEmpty(),
                "NOT_INFERABLE names provisioners that no longer exist: " + staleExemptions);
    }

    @Test
    void anExplicitProvisionerSetReplacesTheInferredOne() {
        CfnResourceProvisioner only = new CfnResourceProvisioner() {
            @Override
            public Set<String> resourceTypes() {
                return Set.of("AWS::Test::Only");
            }

            @Override
            public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
            }
        };

        CfnProvisionerFixture.Builder fixture = CfnProvisionerFixture.builder()
                .sns(mock(SnsService.class))
                .provisioners(only);

        assertTrue(serves(fixture, "AWS::Test::Only"));
        assertFalse(serves(fixture, "AWS::SNS::Topic"),
                "an explicit set replaces inference rather than adding to it");
    }
}
