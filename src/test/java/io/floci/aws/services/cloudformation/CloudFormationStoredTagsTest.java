package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.model.ChangeSet;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.cloudformation.provisioners.CfnResourceDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class CloudFormationStoredTagsTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String TEMPLATE =
            "{\"Resources\":{\"Handle\":{\"Type\":\"AWS::CloudFormation::WaitConditionHandle\"}}}";

    /**
     * The tags IAM gives a resource-tag condition: the stack's or the change set's, read on its whole ARN in the
     * account and Region it names; none for anything else.
     */
    @Test
    void storedTagsReadsTheStackOrTheChangeSetItsArnNames() {
        CloudFormationService cfn = CloudFormationServiceFixture.create(mock(CfnResourceDispatcher.class));
        ChangeSet changeSet = cfn.createChangeSet("tagged", "first", "CREATE", TEMPLATE, null, Map.of(), List.of(),
                Map.of("team", "data"), REGION, ACCOUNT);
        Stack stack = cfn.describeStacks("tagged", REGION, ACCOUNT).getFirst();

        assertEquals(Map.of("team", "data"), cfn.storedTags(changeSet.getChangeSetId()));
        assertEquals(stack.tagsSnapshot(), cfn.storedTags(stack.getStackId()));
        assertEquals(Map.of(), cfn.storedTags(changeSet.getChangeSetId().replace("/first/", "/other/")));
        assertEquals(Map.of(), cfn.storedTags(stack.getStackId().replace(":" + ACCOUNT + ":", ":111122223333:")));
        assertEquals(Map.of(), cfn.storedTags(changeSet.getChangeSetId().replace(REGION, "eu-west-1")));
        assertEquals(Map.of(), cfn.storedTags("arn:aws:s3:::tagged"));
    }
}
