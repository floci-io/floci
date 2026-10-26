package io.github.hectorvent.floci.services.iam;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        Map<String, String> parsed = IamAuthorizedActions.parse(json("""
                {"authorizedActions": {"sqs": {"SendMessageBatch": "sqs:SendMessage"}}}
                """));
        assertEquals(Map.of("sqs:SendMessageBatch", "sqs:SendMessage"), parsed);
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
