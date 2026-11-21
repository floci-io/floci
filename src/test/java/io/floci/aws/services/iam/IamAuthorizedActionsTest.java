package io.floci.aws.services.iam;

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
