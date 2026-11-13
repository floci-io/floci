package io.floci.aws.services.redshiftserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.RequestCallerResolver;
import io.floci.aws.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftServerlessSharedSnapshotsTest {

    private static final String OWNER = "111111111111";
    private static final String CALLER = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String SNAPSHOT_ARN = "arn:aws:redshift-serverless:us-east-1:" + OWNER + ":snapshot/shared";

    private RedshiftServerlessResourcePolicyService policies;
    private RedshiftServerlessSharedSnapshots shared;
    private RedshiftServerlessSnapshot snapshot;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        policies = mock(RedshiftServerlessResourcePolicyService.class);
        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerArns(any())).thenReturn(Optional.empty());
        Instance<RequestContext> requestContext = mock(Instance.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RegionResolver regionResolver = new RegionResolver(REGION, CALLER);
        RedshiftServerlessResourcePolicyProvider resourcePolicyProvider = new RedshiftServerlessResourcePolicyProvider(
                mock(RedshiftServerlessService.class), policies, objectMapper);
        shared = new RedshiftServerlessSharedSnapshots(policies,
                new IamPolicyEvaluator(objectMapper), regionResolver,
                new RequestCallerResolver(iamService, regionResolver, requestContext), resourcePolicyProvider);

        snapshot = new RedshiftServerlessSnapshot();
        snapshot.setSnapshotName("shared");
        snapshot.setSnapshotArn(SNAPSHOT_ARN);
        snapshot.setSnapshotCreateTime(Instant.parse("2030-01-01T00:00:00Z"));
    }

    private void policy(String document) {
        when(policies.policyOfSnapshot(eq(OWNER), eq(snapshot), eq(REGION))).thenReturn(Optional.of(document));
    }

    private static String statement(String effect, String principal) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"" + effect + "\",\"Principal\":{\"AWS\":\""
                + principal + "\"},\"Action\":\"redshift-serverless:GetSnapshot\",\"Resource\":\"*\"}]}";
    }

    @Test
    void aResourceLessAllowSharesTheSnapshot() {
        policy(statement("Allow", CALLER).replace(",\"Resource\":\"*\"", ""));

        assertEquals(List.of(snapshot), shared.readable(OWNER, List.of(snapshot), REGION));
    }

    @Test
    void aResourceLessDenyOverridesAnExplicitAllow() {
        policy("""
                {"Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"222222222222"},
                   "Action":"redshift-serverless:GetSnapshot","Resource":"*"},
                  {"Effect":"Deny","Principal":{"AWS":"222222222222"},
                   "Action":"redshift-serverless:GetSnapshot"}
                ]}
                """);

        assertEquals(List.of(), shared.readable(OWNER, List.of(snapshot), REGION));
    }

    @Test
    void aSingleResourceLessStatementSharesTheSnapshot() {
        policy("""
                {"Statement":{"Effect":"Allow","Principal":{"AWS":"222222222222"},
                 "Action":"redshift-serverless:GetSnapshot"}}
                """);

        assertEquals(List.of(snapshot), shared.readable(OWNER, List.of(snapshot), REGION));
    }

    @Test
    void readableKeepsOnlyTheSnapshotsSharedWithTheCaller() {
        RedshiftServerlessSnapshot hidden = new RedshiftServerlessSnapshot(snapshot);
        hidden.setSnapshotName("hidden");
        hidden.setSnapshotArn("arn:aws:redshift-serverless:us-east-1:" + OWNER + ":snapshot/hidden");
        policy(statement("Allow", "arn:aws:iam::" + CALLER + ":root"));
        when(policies.policyOfSnapshot(eq(OWNER), eq(hidden), eq(REGION))).thenReturn(Optional.empty());

        assertEquals(List.of(snapshot), shared.readable(OWNER, List.of(snapshot, hidden), REGION));
    }

    @Test
    void aSnapshotOfAnotherPartitionIsNotSharedWhateverThePolicyNames() {
        snapshot.setSnapshotArn("arn:aws-cn:redshift-serverless:cn-north-1:" + OWNER + ":snapshot/shared");
        policy(statement("Allow", CALLER));

        assertEquals(List.of(), shared.readable(OWNER, List.of(snapshot), REGION));
    }
}
