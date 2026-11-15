package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.model.StackSet;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class StackSetArnTest {

    /**
     * A stack set's ARN is in the Region of the request naming it, given to the service, whatever context runs it: a
     * Step Functions task calls the handler with no HTTP request, in its own Region, which is not the deployment's.
     */
    @Test
    void aStackSetsArnIsInTheRegionOfTheRequestNamingIt() {
        StackSetService service = new StackSetService(mock(CloudFormationService.class),
                new InMemoryStorageFactory("000000000000"));
        StackSet set = service.createStackSet("regional", "{\"Resources\":{}}", null, null, Map.of(), null);
        String arn = "arn:aws:cloudformation:eu-west-1:000000000000:stackset/" + set.getStackSetId();

        assertEquals(arn, service.stackSetArn(set, "eu-west-1"));
        assertEquals(Optional.of(arn), service.stackSetArn("regional", "eu-west-1"));
        assertEquals(Optional.empty(), service.stackSetArn("missing", "eu-west-1"));
    }
}
