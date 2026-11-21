package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aPolicyThatCarriesAListOfStatementsHasThem() {
        JsonNode policy = policy("{\"Statement\":[{\"Effect\":\"Allow\"},{\"Effect\":\"Deny\"}]}");

        assertEquals(2, PolicyDocument.statementsOf(policy).size());
        assertEquals("Allow", PolicyDocument.statementsOf(policy).get(0).path("Effect").asText());
    }

    @Test
    void aPolicyThatCarriesOneStatementHasIt() {
        JsonNode policy = policy("{\"Statement\":{\"Effect\":\"Allow\"}}");

        assertEquals(1, PolicyDocument.statementsOf(policy).size());
    }

    @Test
    void aPolicyThatCarriesNoStatementHasNone() {
        assertTrue(PolicyDocument.statementsOf(policy("{\"Version\":\"2012-10-17\"}")).isEmpty());
        assertTrue(PolicyDocument.statementsOf(policy("{\"Statement\":[]}")).isEmpty());
        assertTrue(PolicyDocument.statementsOf(policy("{\"Statement\":\"Allow\"}")).isEmpty());
    }

    @Test
    void aWildcardPrincipalNamesEveryone() {
        assertTrue(PolicyDocument.namesEveryone(principal("\"*\"")));
        assertTrue(PolicyDocument.namesEveryone(principal("{\"AWS\":\"*\"}")));
        assertTrue(PolicyDocument.namesEveryone(principal("{\"AWS\":[\"*\"]}")));
        assertTrue(PolicyDocument.namesEveryone(principal("[\"*\"]")));
        assertTrue(PolicyDocument.namesEveryone(principal("{\"AWS\":[\"arn:aws:iam::123456789012:role/app\",\"*\"]}")));
    }

    @Test
    void aPrincipalThatNamesOnePrincipalNamesNotEveryone() {
        assertFalse(PolicyDocument.namesEveryone(principal("\"arn:aws:iam::123456789012:role/app\"")));
        assertFalse(PolicyDocument.namesEveryone(principal("{\"AWS\":\"arn:aws:iam::123456789012:role/app\"}")));
        assertFalse(PolicyDocument.namesEveryone(principal("{\"AWS\":[\"arn:aws:iam::123456789012:role/app\"]}")));
    }

    @Test
    void aPrincipalThatIsThereNamesNotEveryone() {
        assertFalse(PolicyDocument.namesEveryone(null));
        assertFalse(PolicyDocument.namesEveryone(principal("null")));
    }

    @Test
    void aGrantThatNamesEveryoneReachesEveryone() {
        assertTrue(PolicyDocument.grantsToEveryone(statement("\"Principal\":\"*\"")));
        assertTrue(PolicyDocument.grantsToEveryone(statement("\"Principal\":{\"AWS\":\"*\"}")));
    }

    @Test
    void aGrantThatNamesOnePrincipalReachesNotEveryone() {
        assertFalse(PolicyDocument.grantsToEveryone(
                statement("\"Principal\":{\"AWS\":\"arn:aws:iam::123456789012:role/app\"}")));
    }

    @Test
    void aGrantThatExcludesOnePrincipalReachesEveryoneElse() {
        assertTrue(PolicyDocument.grantsToEveryone(
                statement("\"NotPrincipal\":{\"AWS\":\"arn:aws:iam::123456789012:role/app\"}")));
    }

    @Test
    void aGrantThatExcludesEveryoneReachesNobody() {
        assertFalse(PolicyDocument.grantsToEveryone(statement("\"NotPrincipal\":\"*\"")));
    }

    @Test
    void aGrantThatNamesNoPrincipalReachesNobody() {
        assertFalse(PolicyDocument.grantsToEveryone(statement("\"Action\":\"s3:GetObject\"")));
    }

    private static JsonNode policy(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not JSON: " + json, e);
        }
    }

    private static JsonNode principal(String json) {
        return policy(json);
    }

    private static JsonNode statement(String member) {
        return policy("{\"Effect\":\"Allow\"," + member + "}");
    }
}
