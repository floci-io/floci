package io.floci.aws.services.iam;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamAuthorizedActionsTest {

    @Test
    void batchOperationsAreAuthorizedAsTheirSingleItemAction() {
        assertEquals("sqs:SendMessage", IamAuthorizedActions.actionFor("sqs", "SendMessageBatch"));
        assertEquals("sqs:DeleteMessage", IamAuthorizedActions.actionFor("sqs", "DeleteMessageBatch"));
        assertEquals("sqs:ChangeMessageVisibility",
                IamAuthorizedActions.actionFor("sqs", "ChangeMessageVisibilityBatch"));
        assertEquals("sns:Publish", IamAuthorizedActions.actionFor("sns", "PublishBatch"));
    }

    @Test
    void budgetsOperationsAreAuthorizedAsModifyBudgetOrViewBudget() {
        assertEquals("budgets:ModifyBudget", IamAuthorizedActions.actionFor("budgets", "CreateBudget"));
        assertEquals("budgets:ModifyBudget", IamAuthorizedActions.actionFor("budgets", "UpdateSubscriber"));
        assertEquals("budgets:ViewBudget", IamAuthorizedActions.actionFor("budgets", "DescribeBudgets"));
        // A budget action is authorized by an action of its own.
        assertEquals("budgets:CreateBudgetAction", IamAuthorizedActions.actionFor("budgets", "CreateBudgetAction"));
    }

    @Test
    void anOperationTheFileDoesNotNameKeepsItsOwnName() {
        assertEquals("sqs:SendMessage", IamAuthorizedActions.actionFor("sqs", "SendMessage"));
    }

    @Test
    void transactGetItemsKeepsItsOwnNameForTheItemByItemCheck() {
        assertEquals("dynamodb:TransactGetItems", IamAuthorizedActions.actionFor("dynamodb", "TransactGetItems"));
    }

    @Test
    void entriesAreKeyedByNamespaceAndOperation() throws IOException {
        IamAuthorizedActions.Table parsed = IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:SendMessage"}},
                 "actionsWithoutResourceTypes": {"es": ["ListDomainNames"], "dsql": []},
                 "authorizedByMethod": {"apigateway": ["GetStage", "TagResource"]}}
                """));
        assertEquals(Map.of("sqs:SendMessageBatch", "sqs:SendMessage"), parsed.actions());
        assertEquals(Set.of("es:listdomainnames"), parsed.withoutResourceTypes());
        assertEquals(Set.of("apigateway:GetStage", "apigateway:TagResource"), parsed.byMethod());
    }

    @Test
    void theActionsOfAModelsRequestsAVersionChoiceAndSpellingsAreKeyedByOperation() throws IOException {
        IamAuthorizedActions.Table parsed = IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:SendMessage"}},
                 "actionsWithoutResourceTypes": {}, "authorizedByMethod": {},
                 "authorizedByModel": {"s3": {"s3control": {"GetPublicAccessBlock": "s3:GetAccountPublicAccessBlock"}}},
                 "versioned": {"s3": {"GetObject": {"parameter": "versionId", "without": "s3:GetObject",
                                                    "with": "s3:GetObjectVersion"}}},
                 "spellings": {"memorydb": {"CreateACL": "memorydb:CreateAcl"}}}
                """));
        assertEquals(Map.of("s3:GetPublicAccessBlock@s3control", "s3:GetAccountPublicAccessBlock"), parsed.byModel());
        assertEquals(Map.of("s3:GetObject", new IamAuthorizedActions.Versioned("versionId", "s3:GetObject",
                "s3:GetObjectVersion")), parsed.versioned());
        assertEquals(Map.of("memorydb:CreateACL", "memorydb:CreateAcl"), parsed.spellings());
    }

    @Test
    void aMalformedModelVersionOrSpellingEntryIsRefused() {
        String base = "\"authorizedActions\": {\"sqs\": {\"A\": \"sqs:B\"}}, \"actionsWithoutResourceTypes\": {}, "
                + "\"authorizedByMethod\": {}, ";
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("{" + base
                + "\"authorizedByModel\": {\"s3\": {\"s3control\": {\"GetPublicAccessBlock\": \"sqs:SendMessage\"}}}}")));
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("{" + base
                + "\"versioned\": {\"s3\": {\"GetObject\": {\"without\": \"s3:GetObject\", \"with\": \"s3:X\"}}}}")));
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("{" + base
                + "\"spellings\": {\"memorydb\": {\"CreateACL\": \"memorydb:CreateCluster\"}}}")));
    }

    /**
     * A request of a route's model and query is checked as the action the file gives that model's requests, or the
     * one its query chooses: S3 Control's account-level GetPublicAccessBlock and S3's bucket-level one, a GetObject
     * naming a version, and S3's GetBucketLifecycleConfiguration, the action the reference gives its namesake client.
     */
    @Test
    void aRouteOfAModelOrARequestNamingAVersionIsCheckedAsTheActionTheFileGivesIt() {
        MultivaluedMap<String, String> none = new MultivaluedHashMap<>();
        MultivaluedMap<String, String> version = new MultivaluedHashMap<>();
        version.add("versionId", "null");
        assertEquals("s3:GetAccountPublicAccessBlock",
                IamAuthorizedActions.actionFor("s3", "GetPublicAccessBlock", "GET", "s3control", none));
        assertEquals("s3:GetBucketPublicAccessBlock",
                IamAuthorizedActions.actionFor("s3", "GetPublicAccessBlock", "GET", "s3", none));
        assertEquals("s3:GetObject", IamAuthorizedActions.actionFor("s3", "GetObject", "GET", "s3", none));
        assertEquals("s3:GetObjectVersion", IamAuthorizedActions.actionFor("s3", "GetObject", "GET", "s3", version));
        assertEquals("s3:GetObjectVersionAnnotation",
                IamAuthorizedActions.actionFor("s3", "GetObjectAnnotation", "GET", "s3", version));
        assertEquals("s3:GetLifecycleConfiguration",
                IamAuthorizedActions.actionFor("s3", "GetBucketLifecycleConfiguration", "GET", "s3", none));
        assertEquals("s3:ListBucket", IamAuthorizedActions.actionFor("s3", "ListObjectsV2", "GET", "s3", none));
        assertEquals("s3:PutObject", IamAuthorizedActions.actionFor("s3", "CopyObject", "PUT", "s3", none));
        // A model the file gives nothing for keeps the answer the operation gets otherwise.
        assertEquals("apigateway:PUT", IamAuthorizedActions.actionFor("apigateway", "TagResource", "PUT", null, none));
    }

    /** An action the reference spells otherwise than its operation, in case alone, is named with its spelling. */
    @Test
    void anActionIsNamedAsTheReferenceSpellsIt() {
        assertEquals("memorydb:CreateAcl", IamAuthorizedActions.actionFor("memorydb", "CreateACL"));
        assertEquals("s3:PutBucketCORS", IamAuthorizedActions.actionFor("s3", "PutBucketCors", "PUT"));
        assertEquals("sqs:SendMessage", IamAuthorizedActions.actionFor("sqs", "SendMessageBatch"));
    }

    /**
     * A request on the route of an operation the file authorizes by method is checked as the action its method
     * names: API Gateway's {@code TagResource} is a PUT in v1 and a POST in v2. An operation the file renames keeps
     * that action, and one it names nowhere its own name, whatever the method.
     */
    @Test
    void anOperationAuthorizedByMethodIsTheActionItsRequestsMethodNames() {
        assertEquals("apigateway:PUT", IamAuthorizedActions.actionFor("apigateway", "TagResource", "PUT"));
        assertEquals("apigateway:POST", IamAuthorizedActions.actionFor("apigateway", "TagResource", "post"));
        assertEquals("apigateway:DELETE", IamAuthorizedActions.actionFor("apigateway", "DeleteStage", "DELETE"));
        assertEquals("apigateway:GET", IamAuthorizedActions.actionFor("apigateway", "GetRestApis", "GET"));
        assertEquals("apigateway:RejectAccessAssociation",
                IamAuthorizedActions.actionFor("apigateway", "RejectDomainNameAccessAssociation", "POST"));
        assertEquals("apigateway:CreateRoutingRule",
                IamAuthorizedActions.actionFor("apigateway", "CreateRoutingRule", "POST"));
        assertEquals("lambda:InvokeFunction", IamAuthorizedActions.actionFor("lambda", "Invoke", "POST"));
        // An operation named in a request rather than reached by its route keeps the two-argument answer.
        assertEquals("apigateway:TagResource", IamAuthorizedActions.actionFor("apigateway", "TagResource"));
    }

    @Test
    void aFileWithoutTheOperationsAuthorizedByMethodIsRefused() {
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:SendMessage"}},
                 "actionsWithoutResourceTypes": {}}
                """)));
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:SendMessage"}},
                 "actionsWithoutResourceTypes": {}, "authorizedByMethod": {"apigateway": "TagResource"}}
                """)));
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:SendMessage"}},
                 "actionsWithoutResourceTypes": {}, "authorizedByMethod": {"apigateway": [""]}}
                """)));
    }

    /**
     * The actions of a routed REST scope that AWS authorizes on no resource type are known, matched as IAM matches
     * action names; one acting on a resource, or of a scope whose routes are not read, is not one of them.
     */
    @Test
    void anActionOfNoResourceTypeIsKnownWhateverTheCaseOfItsName() {
        assertTrue(IamAuthorizedActions.actsOnNoResourceType("es:ListDomainNames"));
        assertTrue(IamAuthorizedActions.actsOnNoResourceType("ES:listdomainnames"));
        assertTrue(IamAuthorizedActions.actsOnNoResourceType("es:CreateVpcEndpoint"));
        assertTrue(IamAuthorizedActions.actsOnNoResourceType("access-analyzer:ValidatePolicy"));
        assertFalse(IamAuthorizedActions.actsOnNoResourceType("es:DescribeDomain"));
        assertFalse(IamAuthorizedActions.actsOnNoResourceType("access-analyzer:CreateAnalyzer"));
        assertFalse(IamAuthorizedActions.actsOnNoResourceType("sqs:ListQueues"));
        assertFalse(IamAuthorizedActions.actsOnNoResourceType(null));
    }

    @Test
    void aFileWithoutTheActionsOfNoResourceTypeIsRefused() {
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:SendMessage"}}}
                """)));
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:SendMessage"}},
                 "actionsWithoutResourceTypes": {"es": "ListDomainNames"}}
                """)));
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:SendMessage"}},
                 "actionsWithoutResourceTypes": {"es": [""]}}
                """)));
    }

    @Test
    void anActionOfAnotherNamespaceIsRefused() {
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sns:Publish"}}}
                """)));
    }

    @Test
    void aNamespaceWithNoActionNameIsRefused() {
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:"}}}
                """)));
    }

    @Test
    void aFileWithoutEntriesIsRefused() {
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("{}")));
        assertThrows(IllegalStateException.class, () -> IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": ["SendMessageBatch"]}}
                """)));
    }

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
