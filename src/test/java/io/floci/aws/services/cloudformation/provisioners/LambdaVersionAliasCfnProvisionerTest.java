package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.lambda.LambdaAliasStore;
import io.floci.aws.services.lambda.LambdaConcurrencyLimiter;
import io.floci.aws.services.lambda.LambdaFunctionStore;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.LambdaTargetResolver;
import io.floci.aws.services.lambda.WarmPool;
import io.floci.aws.services.lambda.model.LambdaAlias;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.zip.CodeStore;
import io.floci.aws.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Lambda Version/Alias CFN provisioner in isolation. Covers the two paths the SAM
 * integration test cannot reach: the already-exists fallback, and delete.
 */
class LambdaVersionAliasCfnProvisionerTest {

    private static final String ALIAS_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:my-fn:production";

    private final LambdaService lambda = mock(LambdaService.class);
    private final LambdaVersionAliasCfnProvisioner provisioner = new LambdaVersionAliasCfnProvisioner(lambda);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return new ProvisionContext(ProvisionContexts.passthroughEngine(), "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private LambdaFunction publishedVersion() {
        LambdaFunction version = new LambdaFunction();
        version.setFunctionName("my-fn");
        version.setVersion("3");
        version.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:my-fn:3");
        return version;
    }

    private LambdaAlias alias(String functionVersion) {
        LambdaAlias alias = new LambdaAlias();
        alias.setName("production");
        alias.setFunctionName("my-fn");
        alias.setFunctionVersion(functionVersion);
        alias.setAliasArn(ALIAS_ARN);
        return alias;
    }

    @Test
    void versionSetsPhysicalIdAndGetAttAttributes() {
        when(lambda.publishVersion(eq("us-east-1"), eq("my-fn"), isNull())).thenReturn(publishedVersion());

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-fn");
        StackResource r = resource("AWS::Lambda::Version", "MyFuncVersion");

        provisioner.provision(r, props, ctx());

        // Ref -> qualified ARN, GetAtt Version -> the number the Alias resource references.
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-fn:3", r.getPhysicalId());
        assertEquals("3", r.getAttributes().get("Version"));
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-fn:3",
                r.getAttributes().get("FunctionArn"));
    }

    @Test
    void versionWithoutFunctionNameFails() {
        StackResource r = resource("AWS::Lambda::Version", "MyFuncVersion");
        assertThrows(IllegalArgumentException.class,
                () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
        verifyNoInteractions(lambda);
    }

    @Test
    void aliasSetsPhysicalIdFromAliasArn() {
        when(lambda.createAlias(eq("us-east-1"), eq("my-fn"), eq("production"), eq("3"), isNull(), isNull()))
                .thenReturn(alias("3"));

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-fn");
        props.put("Name", "production");
        props.put("FunctionVersion", "3");
        StackResource r = resource("AWS::Lambda::Alias", "MyFuncAliasproduction");

        provisioner.provision(r, props, ctx());

        assertEquals(ALIAS_ARN, r.getPhysicalId());
        assertEquals(ALIAS_ARN, r.getAttributes().get("AliasArn"));
    }

    @Test
    void aliasSurvivingAPriorDeployIsUpdatedRatherThanFailingTheStack() {
        // Floci deletes and recreates a stack on redeploy; an alias that outlived the delete makes
        // createAlias conflict. Converging on it keeps a redeploy idempotent instead of leaving the
        // stack in ROLLBACK.
        when(lambda.createAlias(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AwsException("ResourceConflictException", "Alias already exists: production", 409));
        // A template that declares no RoutingConfig means the alias carries no weights, so the
        // update clears them with an empty map rather than keeping whatever was there.
        when(lambda.updateAlias(eq("us-east-1"), eq("my-fn"), eq("production"), eq("4"), isNull(), eq(Map.of())))
                .thenReturn(alias("4"));

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-fn");
        props.put("Name", "production");
        props.put("FunctionVersion", "4");
        StackResource r = resource("AWS::Lambda::Alias", "MyFuncAliasproduction");

        provisioner.provision(r, props, ctx());

        verify(lambda).updateAlias(eq("us-east-1"), eq("my-fn"), eq("production"), eq("4"), isNull(), eq(Map.of()));
        assertEquals(ALIAS_ARN, r.getPhysicalId());
    }

    @Test
    void aliasFailureOtherThanConflictPropagates() {
        // Only the already-exists case is convergeable. A missing function (for example) must fail
        // the stack rather than be silently swallowed by the fallback.
        when(lambda.createAlias(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found: my-fn", 404));

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-fn");
        props.put("Name", "production");
        StackResource r = resource("AWS::Lambda::Alias", "MyFuncAliasproduction");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void deleteAliasParsesFunctionAndAliasFromArn() {
        provisioner.delete("AWS::Lambda::Alias", ALIAS_ARN, "us-east-1");
        verify(lambda).deleteAlias("us-east-1", "my-fn", "production");
    }

    @Test
    void deleteAliasAlreadyGoneIsIdempotent() {
        // The function may have been deleted first, taking its aliases with it; a stack delete has
        // to stay idempotent rather than transition to DELETE_FAILED.
        Mockito.doThrow(new AwsException("ResourceNotFoundException", "Alias not found: production", 404))
                .when(lambda).deleteAlias(any(), any(), any());

        provisioner.delete("AWS::Lambda::Alias", ALIAS_ARN, "us-east-1");
    }

    @Test
    void deleteVersionIsANoOp() {
        // Lambda exposes no DeleteVersion; deleting the function drops its versions with it.
        provisioner.delete("AWS::Lambda::Version",
                "arn:aws:lambda:us-east-1:000000000000:function:my-fn:3", "us-east-1");
        verifyNoInteractions(lambda);
    }

    @Test
    void deleteToleratesAPhysicalIdThatIsNotAnAliasArn() {
        // Stacks created before this provisioner existed got the generic fallback's physical id
        // (logicalId + "-" + short uuid) for an explicitly declared Lambda::Alias. Deleting such a
        // stack after upgrading must not fail on an unparseable id.
        provisioner.delete("AWS::Lambda::Alias", "MyFuncAliasproduction-1a2b3c4d", "us-east-1");
        verifyNoInteractions(lambda);
    }

    // --- dropped Description, against a real in-memory LambdaService ---

    private static final String WITH_DESCRIPTION = """
            {"FunctionName":"my-fn","Name":"production","FunctionVersion":"1","Description":"orig",
             "RoutingConfig":{"AdditionalVersionWeights":[{"FunctionVersion":"2","FunctionWeight":0.1}]}}
            """;
    private static final String WITHOUT_DESCRIPTION = """
            {"FunctionName":"my-fn","Name":"production","FunctionVersion":"1",
             "RoutingConfig":{"AdditionalVersionWeights":[{"FunctionVersion":"2","FunctionWeight":0.1}]}}
            """;

    private LambdaService realLambda() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        LambdaFunctionStore functionStore = new LambdaFunctionStore(storageFactory);
        LambdaAliasStore aliasStore = new LambdaAliasStore(storageFactory);
        LambdaService real = new LambdaService(functionStore, null, mock(LambdaConcurrencyLimiter.class),
                mock(WarmPool.class), new CodeStore(Path.of("target/test-data/lambda-code")), new ZipExtractor(),
                null, new RegionResolver("us-east-1", "000000000000"), null, aliasStore,
                new LambdaTargetResolver(functionStore, aliasStore), null, null, null, null, null,
                storageFactory, null, null, null, null, new ObjectMapper(), null);
        real.createFunction("us-east-1", new HashMap<>(Map.of("FunctionName", "my-fn", "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role", "Handler", "index.handler",
                "Timeout", 10, "MemorySize", 256)));
        return real;
    }

    private StackResource deployAlias(LambdaService real, String properties) throws Exception {
        StackResource r = resource("AWS::Lambda::Alias", "MyFuncAliasproduction");
        new LambdaVersionAliasCfnProvisioner(real).provision(r, mapper.readTree(properties), ctx());
        return r;
    }

    private void updateAlias(LambdaService real, StackResource r, String previous, String current) throws Exception {
        new LambdaVersionAliasCfnProvisioner(real).provision(r, mapper.readTree(current),
                ProvisionContexts.update("us-east-1", "000000000000", "my-stack",
                        r.getPhysicalId(), previous));
    }

    @Test
    void aliasDescriptionDroppedFromTheTemplateIsClearedOnUpdate() throws Exception {
        LambdaService real = realLambda();
        StackResource r = deployAlias(real, WITH_DESCRIPTION);
        assertEquals("orig", real.getAlias("us-east-1", "my-fn", "production").getDescription());

        updateAlias(real, r, WITH_DESCRIPTION, WITHOUT_DESCRIPTION);

        assertNull(real.getAlias("us-east-1", "my-fn", "production").getDescription());
    }

    @Test
    void changedAliasDescriptionStillApplies() throws Exception {
        LambdaService real = realLambda();
        StackResource r = deployAlias(real, WITH_DESCRIPTION);

        updateAlias(real, r, WITH_DESCRIPTION, WITH_DESCRIPTION.replace("orig", "changed"));

        assertEquals("changed", real.getAlias("us-east-1", "my-fn", "production").getDescription());
    }

    @Test
    void emptyAliasDescriptionIsStoredAsGivenAndALaterDropClearsIt() throws Exception {
        LambdaService real = realLambda();
        StackResource r = deployAlias(real, WITH_DESCRIPTION);
        String empty = WITH_DESCRIPTION.replace("\"orig\"", "\"\"");

        updateAlias(real, r, WITH_DESCRIPTION, empty);
        assertEquals("", real.getAlias("us-east-1", "my-fn", "production").getDescription());

        updateAlias(real, r, empty, WITHOUT_DESCRIPTION);
        assertNull(real.getAlias("us-east-1", "my-fn", "production").getDescription());
    }

    @Test
    void aliasDescriptionSetOutsideTheStackSurvivesAnUpdateThatNeverDeclaredIt() throws Exception {
        LambdaService real = realLambda();
        StackResource r = deployAlias(real, WITHOUT_DESCRIPTION);
        real.updateAlias("us-east-1", "my-fn", "production", null, "outside", null);

        updateAlias(real, r, WITHOUT_DESCRIPTION, WITHOUT_DESCRIPTION.replace("\"1\"", "\"3\""));

        LambdaAlias stored = real.getAlias("us-east-1", "my-fn", "production");
        assertEquals("outside", stored.getDescription());
        assertEquals("3", stored.getFunctionVersion());
    }

    @Test
    void aliasDescriptionDroppedThenSetOutsideTheStackSurvivesALaterUpdateThatStillDoesNotDeclareIt()
            throws Exception {
        LambdaService real = realLambda();
        StackResource r = deployAlias(real, WITH_DESCRIPTION);
        updateAlias(real, r, WITH_DESCRIPTION, WITHOUT_DESCRIPTION);
        real.updateAlias("us-east-1", "my-fn", "production", null, "outside", null);

        updateAlias(real, r, WITHOUT_DESCRIPTION, WITHOUT_DESCRIPTION.replace("\"1\"", "\"3\""));

        LambdaAlias stored = real.getAlias("us-east-1", "my-fn", "production");
        assertEquals("outside", stored.getDescription());
        assertEquals("3", stored.getFunctionVersion());
    }

    @Test
    void droppingTheAliasDescriptionKeepsTheVersionAndTheRoutingConfig() throws Exception {
        LambdaService real = realLambda();
        StackResource r = deployAlias(real, WITH_DESCRIPTION);

        updateAlias(real, r, WITH_DESCRIPTION, WITHOUT_DESCRIPTION);

        LambdaAlias stored = real.getAlias("us-east-1", "my-fn", "production");
        assertEquals("1", stored.getFunctionVersion());
        assertEquals(Map.of("2", 0.1), stored.getRoutingConfig());
    }
}
