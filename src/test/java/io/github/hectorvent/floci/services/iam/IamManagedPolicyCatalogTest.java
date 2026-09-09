package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The managed-policy catalog carries the full published AWS list rather than a curated
 * subset, so that attaching a real policy succeeds and attaching an invented one still
 * fails the way it does on AWS.
 *
 * <p>A subset produced false negatives — {@code AttachRolePolicy} returned
 * {@code NoSuchEntity} for policies AWS genuinely publishes, breaking valid Terraform and
 * CloudFormation configurations. Resolving every well-formed ARN instead would produce
 * false positives, silently accepting typos that real AWS rejects and defeating stack
 * rollback. Both directions matter, so both are asserted here.
 */
class IamManagedPolicyCatalogTest {

    private IamService iamService;

    @BeforeEach
    void setUp() {
        iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
    }

    @Test
    void catalogCarriesTheFullPublishedListNotAHandfulOfEntries() {
        assertTrue(AwsManagedPolicies.POLICIES.size() > 1000,
                "expected the full AWS catalog, found " + AwsManagedPolicies.POLICIES.size());
    }

    /** Policies a real ECS/EMR estate attaches; every one of these used to 404. */
    @ParameterizedTest
    @ValueSource(strings = {
        "arn:aws:iam::aws:policy/AmazonEC2ReadOnlyAccess",
        "arn:aws:iam::aws:policy/AmazonSSMFullAccess",
        "arn:aws:iam::aws:policy/service-role/AmazonEC2RoleforSSM",
        "arn:aws:iam::aws:policy/service-role/AmazonElasticMapReduceRole",
        "arn:aws:iam::aws:policy/service-role/AmazonElasticMapReduceforEC2Role",
        "arn:aws:iam::aws:policy/service-role/AmazonElasticMapReduceforAutoScalingRole",
        "arn:aws:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2",
    })
    void realWorldPoliciesResolveAndAttach(String policyArn) {
        IamPolicy policy = assertDoesNotThrow(() -> iamService.getPolicy(policyArn));
        assertEquals(policyArn, policy.getArn());

        iamService.createRole("attach-target", "/", "{}", null, 3600, null);
        assertDoesNotThrow(() -> iamService.attachRolePolicy("attach-target", policyArn));
    }

    @Test
    void everyPathPrefixAwsUsesIsRepresented() {
        for (String path : new String[] {"/", "/service-role/", "/aws-service-role/", "/job-function/"}) {
            assertTrue(AwsManagedPolicies.POLICIES.stream().anyMatch(p -> path.equals(p.path())),
                    "no policy carries the " + path + " path");
        }
    }

    @Test
    void arnsAreBuiltFromNameAndPath() {
        AwsManagedPolicies.ManagedPolicyDef def = AwsManagedPolicies.POLICIES.stream()
                .filter(p -> "AmazonEC2RoleforSSM".equals(p.name()))
                .findFirst().orElseThrow();

        assertEquals("/service-role/", def.path());
        assertEquals("arn:aws:iam::aws:policy/service-role/AmazonEC2RoleforSSM", def.arn());
    }

    @Test
    void curatedDescriptionsSurviveTheBulkCatalog() {
        IamPolicy policy = iamService.getPolicy("arn:aws:iam::aws:policy/AdministratorAccess");

        assertEquals("Provides full access to AWS services and resources.", policy.getDescription());
    }

    /**
     * A policy AWS has since retired but which Floci previously resolved: keeping it means
     * upgrading the catalog never takes away something that already worked.
     */
    @Test
    void policiesRetiredByAwsButPreviouslyResolvableAreRetained() {
        assertDoesNotThrow(() -> iamService.getPolicy("arn:aws:iam::aws:policy/AWSLambdaFullAccess"));
    }

    @Test
    void inventedManagedPolicyIsRejectedTheWayAwsRejectsIt() {
        AwsException e = assertThrows(AwsException.class,
                () -> iamService.getPolicy("arn:aws:iam::aws:policy/DefinitelyNotARealPolicy"));

        assertEquals("NoSuchEntity", e.getErrorCode());
        assertEquals(404, e.getHttpStatus(),
                "AWS answers GetPolicy for a nonexistent policy with NoSuchEntity/404");
    }

    @Test
    void attachingAnInventedManagedPolicyFails() {
        iamService.createRole("typo-role", "/", "{}", null, 3600, null);

        AwsException e = assertThrows(AwsException.class, () -> iamService.attachRolePolicy(
                "typo-role", "arn:aws:iam::aws:policy/AmazonS3FullAcess"));

        assertEquals("NoSuchEntity", e.getErrorCode(),
                "a typo must still fail, otherwise CloudFormation never rolls back");
    }

    @Test
    void unknownCustomerPolicyStillReturnsNoSuchEntity() {
        AwsException e = assertThrows(AwsException.class,
                () -> iamService.getPolicy("arn:aws:iam::000000000000:policy/nope"));

        assertEquals("NoSuchEntity", e.getErrorCode());
    }
}
