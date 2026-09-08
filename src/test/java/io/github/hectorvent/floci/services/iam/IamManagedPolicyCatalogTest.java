package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.PolicyVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The managed-policy catalog carries the full published AWS list rather than a curated
 * subset, so that attaching a real policy succeeds and attaching an invented one still
 * fails the way it does on AWS.
 *
 * <p>A subset produced false negatives: {@code AttachRolePolicy} returned
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

    /**
     * AWS revises managed policies in place, so their default version reflects that history
     * rather than being {@code v1} for everything: {@code AmazonS3ReadOnlyAccess} is on
     * {@code v3} (last revised August 2023) while {@code AdministratorAccess} genuinely never
     * left {@code v1}. Reporting {@code v1} across the board only matched real AWS by accident.
     */
    @Test
    void defaultVersionReflectsAwsRevisionHistory() {
        IamPolicy s3ReadOnly = iamService.getPolicy("arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess");
        IamPolicy administrator = iamService.getPolicy("arn:aws:iam::aws:policy/AdministratorAccess");

        assertEquals("v3", s3ReadOnly.getDefaultVersionId());
        // A revised policy keeps its original creation date; only UpdateDate moves with v3.
        assertEquals(Instant.parse("2015-02-06T18:40:00Z"), s3ReadOnly.getCreateDate());
        assertEquals(Instant.parse("2023-08-10T21:31:39Z"), s3ReadOnly.getUpdateDate());
        assertEquals("v1", administrator.getDefaultVersionId());
        assertEquals(Instant.parse("2015-02-06T18:39:46Z"), administrator.getCreateDate());
        assertEquals(administrator.getCreateDate(), administrator.getUpdateDate());
    }

    @Test
    void listedVersionsCarryTheDefaultVersionIdAndItsDocument() {
        String arn = "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess";
        IamPolicy policy = iamService.getPolicy(arn);

        List<PolicyVersion> versions = iamService.listPolicyVersions(arn);
        assertEquals(1, versions.size(), "only the default version's document is bundled");
        PolicyVersion listed = versions.get(0);
        assertEquals("v3", listed.getVersionId());
        assertTrue(listed.isDefaultVersion());
        assertEquals(policy.getDefaultDocument(), listed.getDocument());
        assertEquals(policy.getUpdateDate(), listed.getCreateDate());

        PolicyVersion fetched = iamService.getPolicyVersion(arn, "v3");
        assertEquals(listed.getDocument(), fetched.getDocument());
        assertTrue(fetched.isDefaultVersion());
    }

    /**
     * The dataset only carries each policy's current document, so a superseded version cannot
     * be served. Answering {@code NoSuchEntity} matches what AWS returns for a version it has
     * pruned, and is preferable to serving the current document under an old id.
     */
    @Test
    void supersededVersionOfARevisedPolicyIsNotServedUnderTheWrongId() {
        AwsException e = assertThrows(AwsException.class, () -> iamService.getPolicyVersion(
                "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess", "v1"));

        assertEquals("NoSuchEntity", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void everyCatalogEntryHasAWellFormedVersionIdAndDates() {
        for (AwsManagedPolicies.ManagedPolicyDef def : AwsManagedPolicies.POLICIES) {
            assertTrue(def.defaultVersionId().matches("v[1-9][0-9]*"),
                    def.name() + " has version id " + def.defaultVersionId());
            assertNotNull(def.updateDate(), def.name() + " has no version date");
            if (def.createDate() != null) {
                assertFalse(def.createDate().isAfter(def.updateDate()),
                        def.name() + " was created after its current version");
            }
        }
        for (IamPolicy policy : iamService.listPolicies("AWS", null)) {
            assertFalse(policy.getCreateDate().isAfter(policy.getUpdateDate()),
                    policy.getPolicyName() + " reports CreateDate after UpdateDate");
        }
        assertTrue(AwsManagedPolicies.POLICIES.stream().anyMatch(p -> !"v1".equals(p.defaultVersionId())),
                "the catalog should carry revised policies, not v1 everywhere");
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
