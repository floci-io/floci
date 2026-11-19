package io.floci.aws.services.iam;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a statement sits in its document, against the positions AWS publishes for the same text.
 *
 * <p>The first case is AWS's own example for {@code SimulatePrincipalPolicy}: that request supplies
 * the document spelled here, and the response it publishes reports the statement at
 * {@code StartPosition} line 3, column 16 and {@code EndPosition} line 7, column 4. So the example
 * fixes the convention, which is the character just past each of the statement's braces: the
 * opening brace of that document sits in column 15 of line 3, and the closing one in column 3 of
 * line 7.
 */
class PolicyStatementPositionsTest {

    /** AWS's own example document, spelled as its published request supplies it. */
    private static final String PUBLISHED_EXAMPLE = """
            {
              "Version":"2012-10-17",
              "Statement":{
                "Effect":"Allow",
                "Action":"s3:PutObject",
                "Resource":"arn:aws:s3:::my-test-bucket"
              }
            }""";

    @Test
    void theOneStatementSitsWhereAwsPublishesItForTheSameDocument() {
        assertEquals(List.of(new PolicyStatementPositions.Span(3, 16, 7, 4)),
                PolicyStatementPositions.of(PUBLISHED_EXAMPLE));
    }

    @Test
    void eachStatementOfAListIsLocatedInTheOrderTheDocumentWritesThem() {
        String document = """
                {
                  "Version":"2012-10-17",
                  "Statement":[
                    {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"},
                    {"Effect":"Deny","Action":"s3:PutObject","Resource":"*"}
                  ]
                }""";

        assertEquals(List.of(new PolicyStatementPositions.Span(4, 6, 4, 62),
                        new PolicyStatementPositions.Span(5, 6, 5, 61)),
                PolicyStatementPositions.of(document));
    }

    /**
     * A one-line document, which is how a stored policy is usually held, puts both positions on
     * line 1, as AWS's same-account example does for the policy a user holds.
     */
    @Test
    void aDocumentOnOneLineIsLocatedOnThatLine() {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"iam:GetUser\",\"Resource\":\"*\"}]}";

        // The statement's brace sits in column 38 and its close in column 93, so the positions are
        // the characters just past them, as the published example fixes the convention.
        assertEquals(List.of(new PolicyStatementPositions.Span(1, 39, 1, 94)),
                PolicyStatementPositions.of(document));
    }

    /**
     * A document the reader cannot get through carries no positions, which is what a document that
     * fails to parse contributes to a decision anyway.
     */
    @Test
    void aDocumentThatIsNoPolicyCarriesNoPositions() {
        assertEquals(List.of(), PolicyStatementPositions.of("{not-a-policy"));
        assertEquals(List.of(), PolicyStatementPositions.of("{\"Version\":\"2012-10-17\"}"));
        assertEquals(List.of(), PolicyStatementPositions.of("[]"));
        assertEquals(List.of(), PolicyStatementPositions.of(null));
        assertEquals(List.of(), PolicyStatementPositions.of("   "));
    }

    /**
     * A {@code Statement} list carrying something that is no statement still answers for it: the
     * parse keeps one entry per element, so an index into the statements is an index into these.
     */
    @Test
    void anElementThatIsNoStatementIsStillCounted() {
        assertEquals(3, PolicyStatementPositions.of(
                "{\"Statement\":[{\"Effect\":\"Allow\"},\"nonsense\",{\"Effect\":\"Deny\"}]}").size());
    }
}
