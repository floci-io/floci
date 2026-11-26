package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.SmithyValidationErrors;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What AWS answered to each request below, as measured, read through the vendored request shapes. Orders,
 * wording and the cut at ten violations are the measured ones.
 */
class EventBridgeV2RequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BUS_ARN =
            "arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnopqrstuvwxy";

    private static final String REQUEST = "{\"Name\":\"n\",\"EventBusArn\":"
            + "\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnopqrstuvwxy\","
            + "\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":"
            + "\"arn:aws:iam::123456789012:role/probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}}}";

    @Test
    void validRequestsHaveNoViolations() {
        assertDoesNotThrow(() -> EventBridgeV2RequestShapes.check("CreateEventSource", json(REQUEST)));
        assertDoesNotThrow(() -> EventBridgeV2RequestShapes.check("ListEventSources", json("{}")));
    }

    @Test
    void unknownMembersAreIgnoredAndNullMembersAreAbsent() {
        ObjectNode request = json(REQUEST);
        request.put("Bogus", "x");
        request.putNull("Description");

        assertDoesNotThrow(() -> EventBridgeV2RequestShapes.check("CreateEventSource", request));
    }

    @Test
    void otherConfigurationVariantsPassTheShapes() {
        assertDoesNotThrow(() -> EventBridgeV2RequestShapes.check("CreateEventSource", json(
                "{\"Name\":\"n\",\"EventBusArn\":"
                        + "\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnopqrstuvwxy\","
                        + "\"Configuration\":{\"AwsServiceEventsConfiguration\":{\"AwsService\":\"aws.s3\"}}}")));
    }

    @Test
    void twoHundredAndOneTagsQuoteTheWholeMapInHashMapOrder() {
        ObjectNode request = json("{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}}}");
        ObjectNode tags = request.putObject("Tags");
        for (int i = 0; i < 201; i++) {
            tags.put("k" + i, "v");
        }

        assertAnswers("CreateEventSource", request,
                "Value '{k31=v, k30=v, k33=v, k32=v, k35=v, k34=v, k37=v, k36=v, k39=v, k38=v, k141=v, k140" +
                "=v, k143=v, k142=v, k145=v, k144=v, k147=v, k146=v, k149=v, k148=v, k40=v, k42=v, k41=v, k" +
                "44=v, k43=v, k46=v, k45=v, k48=v, k47=v, k49=v, k130=v, k132=v, k131=v, k134=v, k133=v, k1" +
                "36=v, k135=v, k138=v, k137=v, k139=v, k51=v, k50=v, k53=v, k52=v, k55=v, k54=v, k57=v, k56" +
                "=v, k59=v, k170=v, k58=v, k161=v, k160=v, k163=v, k162=v, k165=v, k164=v, k167=v, k166=v, " +
                "k169=v, k168=v, k60=v, k62=v, k61=v, k64=v, k63=v, k66=v, k65=v, k68=v, k67=v, k69=v, k150" +
                "=v, k152=v, k151=v, k154=v, k153=v, k156=v, k155=v, k158=v, k157=v, k159=v, k71=v, k70=v, " +
                "k73=v, k72=v, k75=v, k74=v, k77=v, k76=v, k79=v, k190=v, k78=v, k192=v, k191=v, k183=v, k1" +
                "82=v, k185=v, k184=v, k187=v, k186=v, k189=v, k188=v, k80=v, k82=v, k81=v, k84=v, k83=v, k" +
                "86=v, k85=v, k88=v, k87=v, k89=v, k181=v, k180=v, k172=v, k171=v, k174=v, k173=v, k176=v, " +
                "k175=v, k178=v, k177=v, k179=v, k91=v, k90=v, k93=v, k92=v, k95=v, k94=v, k97=v, k96=v, k9" +
                "9=v, k98=v, k194=v, k193=v, k196=v, k195=v, k198=v, k197=v, k199=v, k0=v, k1=v, k2=v, k3=v" +
                ", k4=v, k5=v, k6=v, k7=v, k8=v, k9=v, k200=v, k101=v, k100=v, k103=v, k102=v, k105=v, k104" +
                "=v, k107=v, k106=v, k109=v, k108=v, k11=v, k10=v, k13=v, k12=v, k15=v, k14=v, k17=v, k16=v" +
                ", k19=v, k18=v, k121=v, k120=v, k123=v, k122=v, k125=v, k124=v, k127=v, k126=v, k129=v, k1" +
                "28=v, k20=v, k22=v, k21=v, k24=v, k23=v, k26=v, k25=v, k28=v, k27=v, k29=v, k110=v, k112=v" +
                ", k111=v, k114=v, k113=v, k116=v, k115=v, k118=v, k117=v, k119=v}' at 'tags' failed to sat" +
                "isfy constraint: Member must have length less than or equal to 200");
    }

    @Test
    void tagKeyWithALeadingSpaceQuotesTheMapInHashMapOrder() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\" lead\":\"v\",\"ok\":\"v2\"}}",
                "Value '{ok=v2,  lead=v}' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]");
    }

    @Test
    void tagKeyOverTheLengthLimitIsAKeyViolation() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\"kkkkkkkkkkkkkkkkkkkkkkkkkkkk" +
                "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk" +
                "k\":\"v\"}}",
                "Value '{kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk=v}' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]");
    }

    @Test
    void tagValueWithALeadingSpaceIsAValueViolation() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\"k\":\" lead\",\"ok\":\"v2\"}}",
                "Value '{ok=v2, k= lead}' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: [Member must have length less than or equal to 256, Member must have length greater than or equal to 0, Member must satisfy regular expression pattern: (\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*, Member must not be null]");
    }

    @Test
    void tagValueOverTheLengthLimitIsAValueViolation() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\"k\":\"vvvvvvvvvvvvvvvvvvvvvvvv" +
                "vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv" +
                "vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv" +
                "vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv\"}}",
                "Value '{k=vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv}' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: [Member must have length less than or equal to 256, Member must have length greater than or equal to 0, Member must satisfy regular expression pattern: (\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*, Member must not be null]");
    }

    @Test
    void tagKeyAndValueBothBrokenAreTwoViolationsKeysFirst() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\" k\":\" v\"}}",
                "Value '{ k= v}' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]",
                "Value '{ k= v}' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: [Member must have length less than or equal to 256, Member must have length greater than or equal to 0, Member must satisfy regular expression pattern: (\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*, Member must not be null]");
    }

    @Test
    void descriptionComesBeforeTags() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\" k\":\" v\"},\"Description\":\"a\\u" +
                "0001b\"}",
                "Value 'a\u0001b' at 'description' failed to satisfy constraint: Member must satisfy regular expression pattern: [^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]*(?![\\s\\S]).*",
                "Value '{ k= v}' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]",
                "Value '{ k= v}' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: [Member must have length less than or equal to 256, Member must have length greater than or equal to 0, Member must satisfy regular expression pattern: (\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*, Member must not be null]");
    }

    @Test
    void everythingBrokenAnswersTheFirstTenOfTheWalk() {
        assertAnswers("CreateEventSource", "{\"Name\":\"bad name\",\"EventBusArn\":\"x\",\"Description\":\"a\\u0001b\",\"ClientToken\":\"a b\",\"Tags\":{\" k\":\" v\"}" +
                ",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"r\",\"KmsKeyIdentifier\":\"a b\",\"MetadataHttp" +
                "Headers\":[\"bad header\"],\"DeduplicationConfiguration\":{\"DeduplicationType\":\"X\"},\"LogConfiguration\":{\"" +
                "Level\":\"X\",\"IncludePayload\":\"X\"},\"AuthConfiguration\":{\"HmacAuthConfiguration\":{\"Algorithm\":\"X\",\"Enco" +
                "ding\":\"X\",\"SignatureHttpHeader\":\"bad header\",\"SignaturePrefix\":\"\",\"Delimiter\":\"\",\"TimestampHttpHeade" +
                "r\":\"bad header\",\"TimestampPrefix\":\"\",\"TimestampToleranceInSeconds\":0,\"MessageIdHttpHeader\":\"bad head" +
                "er\",\"SignedMessageComposition\":\"X\",\"SecretValue\":\"abc\",\"SecretEncoding\":\"X\"}}}}}",
                "Value 'X' at 'configuration.webhookEventsConfiguration.deduplicationConfiguration.deduplicationType' failed to satisfy constraint: Member must satisfy enum value set: [CONTENT_BASED]",
                "Value 'a b' at 'configuration.webhookEventsConfiguration.kmsKeyIdentifier' failed to satisfy constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_\\-/:]*",
                "Value 'r' at 'configuration.webhookEventsConfiguration.roleArn' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:aws(-[a-z0-9]+)*:iam::\\d{12}:role\\/[\\w+=,.@/-]+",
                "Value 'X' at 'configuration.webhookEventsConfiguration.logConfiguration.level' failed to satisfy constraint: Member must satisfy enum value set: [ERROR, INFO, OFF]",
                "Value 'X' at 'configuration.webhookEventsConfiguration.logConfiguration.includePayload' failed to satisfy constraint: Member must satisfy enum value set: [ON_ERROR_ONLY, FULL]",
                "Value '[bad header]' at 'configuration.webhookEventsConfiguration.metadataHttpHeaders' failed to satisfy constraint: Member must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+, Member must not be null]",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.secretEncoding' failed to satisfy constraint: Member must satisfy enum value set: [RAW, BASE64]",
                "Value '' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.timestampPrefix' failed to satisfy constraint: Member must have length greater than or equal to 1",
                "Value '' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.timestampPrefix' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\u0020-\\u007E]+",
                "Value 'bad header' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.messageIdHttpHeader' failed to satisfy constraint: Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+");
    }

    @Test
    void emptyRequestNamesTheThreeRequiredMembersInWalkOrder() {
        assertAnswers("CreateEventSource", "{}",
                "Value null at 'configuration' failed to satisfy constraint: Member must not be null",
                "Value null at 'name' failed to satisfy constraint: Member must not be null",
                "Value null at 'eventBusArn' failed to satisfy constraint: Member must not be null");
    }

    @Test
    void emptyWebhookNamesRoleArnThenAuthConfiguration() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{}}}",
                "Value null at 'configuration.webhookEventsConfiguration.roleArn' failed to satisfy constraint: Member must not be null",
                "Value null at 'configuration.webhookEventsConfiguration.authConfiguration' failed to satisfy constraint: Member must not be null");
    }

    @Test
    void emptyHmacNamesItsFourRequiredMembersInWalkOrder() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"HmacAuthConfiguration\":{}}}}}",
                "Value null at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.signedMessageComposition' failed to satisfy constraint: Member must not be null",
                "Value null at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.signatureHttpHeader' failed to satisfy constraint: Member must not be null",
                "Value null at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.encoding' failed to satisfy constraint: Member must not be null",
                "Value null at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.algorithm' failed to satisfy constraint: Member must not be null");
    }

    @Test
    void staticTokenAndBasicBrokenAreWalkedInThatOrder() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"bad header\",\"SecretVa" +
                "lue\":\"abc\"},\"BasicHttpAuthConfiguration\":{\"Username\":\"a:b\",\"Password\":\"abc\"}}}}}",
                "Value at 'configuration.webhookEventsConfiguration.authConfiguration.staticTokenAuthConfiguration.secretValue' failed to satisfy constraint: Member must have length greater than or equal to 6",
                "Value 'bad header' at 'configuration.webhookEventsConfiguration.authConfiguration.staticTokenAuthConfiguration.tokenHttpHeader' failed to satisfy constraint: Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+",
                "Value at 'configuration.webhookEventsConfiguration.authConfiguration.basicHttpAuthConfiguration.password' failed to satisfy constraint: Member must have length greater than or equal to 6",
                "Value 'a:b' at 'configuration.webhookEventsConfiguration.authConfiguration.basicHttpAuthConfiguration.username' failed to satisfy constraint: Member must satisfy regular expression pattern: [^:\\s]+");
    }

    @Test
    void updateEverythingBrokenIsWalkedConfigurationFirst() {
        assertAnswers("UpdateEventSource", "{\"EventSourceArn\":\"x\",\"Description\":\"a\\u0001b\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleA" +
                "rn\":\"r\"}}}",
                "Value 'r' at 'configuration.webhookEventsConfiguration.roleArn' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:aws(-[a-z0-9]+)*:iam::\\d{12}:role\\/[\\w+=,.@/-]+",
                "Value null at 'configuration.webhookEventsConfiguration.authConfiguration' failed to satisfy constraint: Member must not be null",
                "Value 'x' at 'eventSourceArn' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):event-sourcev2\\/(aws\\.service|aws\\.partner|webhook)\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}",
                "Value 'a\u0001b' at 'description' failed to satisfy constraint: Member must satisfy regular expression pattern: [^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]*(?![\\s\\S]).*");
    }

    @Test
    void tagsOfSixEntriesQuoteTheMapInHashMapOrder() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\" zeta\":\"v\",\"alpha\":\" v\",\"Mid" +
                "\":\"v\",\"b\":\"v\",\"aa\":\"v\",\"BB\":\"v\"}}",
                "Value '{aa=v, BB=v, Mid=v, b=v,  zeta=v, alpha= v}' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]",
                "Value '{aa=v, BB=v, Mid=v, b=v,  zeta=v, alpha= v}' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: [Member must have length less than or equal to 256, Member must have length greater than or equal to 0, Member must satisfy regular expression pattern: (\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*, Member must not be null]");
    }

    @Test
    void listNamePrefixBreakingItsPatternIsOneViolation() {
        assertAnswers("ListEventSources", "{\"NamePrefix\":\"bad name\"}",
                "Value 'bad name' at 'namePrefix' failed to satisfy constraint: Member must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*");
    }

    @Test
    void createEmptyNameBreaksLengthThenPattern() {
        assertAnswers("CreateEventSource", "{\"Name\":\"\",\"EventBusArn\":\"" + BUS_ARN + "\",\"Configuration\":{}}",
                "Value '' at 'name' failed to satisfy constraint: Member must have length greater than or equal to 1",
                "Value '' at 'name' failed to satisfy constraint: Member must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*");
    }

    @Test
    void createOverlongClientTokenWithABadPatternBreaksLengthThenPattern() {
        String token = " ".repeat(65);
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"" + BUS_ARN + "\",\"ClientToken\":\"" + token
                + "\",\"Configuration\":{}}",
                "Value '" + token + "' at 'clientToken' failed to satisfy constraint: Member must have length less than or equal to 64",
                "Value '" + token + "' at 'clientToken' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\u0021-\\u007E]+");
    }

    @Test
    void describeEmptyEventSourceArnBreaksLengthThenPattern() {
        assertAnswers("DescribeEventSource", "{\"EventSourceArn\":\"\"}",
                "Value '' at 'eventSourceArn' failed to satisfy constraint: Member must have length greater than or equal to 1",
                "Value '' at 'eventSourceArn' failed to satisfy constraint: Member must satisfy regular expression pattern: " + DESCRIBE_ARN_PATTERN);
    }

    @Test
    void emptyTimestampPrefixBreaksLengthThenPattern() {
        ObjectNode request = json("{\"Name\":\"n\",\"EventBusArn\":\"" + BUS_ARN + "\",\"Configuration\":{"
                + "\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/probe\","
                + "\"AuthConfiguration\":{\"HmacAuthConfiguration\":{\"TimestampPrefix\":\"\"}}}}}");
        List<String> found = violationsOf("CreateEventSource", request).stream()
                .filter(v -> v.contains("hmacAuthConfiguration.timestampPrefix'"))
                .toList();
        assertEquals(List.of(
                "Value '' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.timestampPrefix' failed to satisfy constraint: Member must have length greater than or equal to 1",
                "Value '' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.timestampPrefix' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\u0020-\\u007E]+"),
                found);
    }

    @Test
    void listEmptyNamePrefixBreaksLengthThenPattern() {
        assertAnswers("ListEventSources", "{\"NamePrefix\":\"\"}",
                "Value '' at 'namePrefix' failed to satisfy constraint: Member must have length greater than or equal to 1",
                "Value '' at 'namePrefix' failed to satisfy constraint: Member must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*");
    }

    @Test
    void listMaxResultsBelowOne() {
        assertAnswers("ListEventSources", "{\"MaxResults\":0}",
                "Value '0' at 'maxResults' failed to satisfy constraint: Member must have value greater than or equal to 1");
    }

    @Test
    void listMaxResultsAboveOneHundred() {
        assertAnswers("ListEventSources", "{\"MaxResults\":101}",
                "Value '101' at 'maxResults' failed to satisfy constraint: Member must have value less than or equal to 100");
    }

    @Test
    void listEmptyNextTokenIsTooShort() {
        assertAnswers("ListEventSources", "{\"NextToken\":\"\"}",
                "Value '' at 'nextToken' failed to satisfy constraint: Member must have length greater than or equal to 1");
    }

    @Test
    void listEventBusArnBreakingItsPattern() {
        assertAnswers("ListEventSources", "{\"EventBusArn\":\"x\"}",
                "Value 'x' at 'eventBusArn' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):event-busv2\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}");
    }

    @Test
    void listEverythingBrokenIsWalkedInTheOrderAwsMeasured() {
        assertAnswers("ListEventSources", "{\"EventBusArn\":\"x\",\"NamePrefix\":\"bad name\",\"MaxResults\":0,\"NextToken\":\"\"}",
                "Value '0' at 'maxResults' failed to satisfy constraint: Member must have value greater than or equal to 1",
                "Value '' at 'nextToken' failed to satisfy constraint: Member must have length greater than or equal to 1",
                "Value 'bad name' at 'namePrefix' failed to satisfy constraint: Member must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*",
                "Value 'x' at 'eventBusArn' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):event-busv2\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}");
    }

    @Test
    void describeArnBreakingItsPattern() {
        assertAnswers("DescribeEventSource", "{\"EventSourceArn\":\"x\"}",
                "Value 'x' at 'eventSourceArn' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):event-sourcev2\\/(aws\\.service|aws\\.partner|webhook)\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}");
    }

    @Test
    void deleteWithoutAnArnNamesTheMissingMember() {
        assertAnswers("DeleteEventSource", "{}",
                "Value null at 'eventSourceArn' failed to satisfy constraint: Member must not be null");
    }

    @Test
    void nullTagValueIsQuotedAsNull() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\"k\":null}}",
                "Value '{k=null}' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: [Member must have length less than or equal to 256, Member must have length greater than or equal to 0, Member must satisfy regular expression pattern: (\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*, Member must not be null]");
    }

    @Test
    void nullMetadataHeaderIsQuotedAsNull() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}},\"MetadataHttpHeaders\":[\"ok\",null]}}}",
                "Value '[ok, null]' at 'configuration.webhookEventsConfiguration.metadataHttpHeaders' failed to satisfy constraint: Member must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+, Member must not be null]");
    }

    @Test
    void nullNameIsAnAbsentMember() {
        assertAnswers("CreateEventSource", "{\"Name\":null,\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmno" +
                "pqrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role" +
                "/probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}}}",
                "Value null at 'name' failed to satisfy constraint: Member must not be null");
    }

    @Test
    void emptyBodyAnswersTheSameAsAnEmptyRequest() {
        assertAnswers("CreateEventSource", "{}",
                "Value null at 'configuration' failed to satisfy constraint: Member must not be null",
                "Value null at 'name' failed to satisfy constraint: Member must not be null",
                "Value null at 'eventBusArn' failed to satisfy constraint: Member must not be null");
    }

    @Test
    void tagsWithALeadingSpaceKeyAndAnOkKey() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\" lead\":\"v\",\"ok\":\"v2\"}}",
                "Value '{ok=v2,  lead=v}' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]");
    }

    @Test
    void badCompositionListsItsValuesInTheMeasuredOrder() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"Signatur" +
                "eHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"X\"}}}}}",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.signedMessageComposition' failed to satisfy constraint: Member must satisfy enum value set: [TIMESTAMP_DOT_BODY, BODY, MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY]");
    }

    @Test
    void elevenViolationsAreCutToTheFirstTen() {
        assertAnswers("CreateEventSource", "{\"Name\":\"bad name\",\"EventBusArn\":\"x\",\"Description\":\"a\\u0001b\",\"ClientToken\":\"a b\",\"Tags\":{\" k\":\" v\"}" +
                ",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"r\",\"AuthConfiguration\":{\"BasicHttpAuthCon" +
                "figuration\":{}},\"KmsKeyIdentifier\":\"a b\",\"MetadataHttpHeaders\":[\"bad header\"],\"DeduplicationConfigur" +
                "ation\":{\"DeduplicationType\":\"X\"},\"LogConfiguration\":{\"Level\":\"X\",\"IncludePayload\":\"X\"}}}}",
                "Value 'X' at 'configuration.webhookEventsConfiguration.deduplicationConfiguration.deduplicationType' failed to satisfy constraint: Member must satisfy enum value set: [CONTENT_BASED]",
                "Value 'a b' at 'configuration.webhookEventsConfiguration.kmsKeyIdentifier' failed to satisfy constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_\\-/:]*",
                "Value 'r' at 'configuration.webhookEventsConfiguration.roleArn' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:aws(-[a-z0-9]+)*:iam::\\d{12}:role\\/[\\w+=,.@/-]+",
                "Value 'X' at 'configuration.webhookEventsConfiguration.logConfiguration.level' failed to satisfy constraint: Member must satisfy enum value set: [ERROR, INFO, OFF]",
                "Value 'X' at 'configuration.webhookEventsConfiguration.logConfiguration.includePayload' failed to satisfy constraint: Member must satisfy enum value set: [ON_ERROR_ONLY, FULL]",
                "Value '[bad header]' at 'configuration.webhookEventsConfiguration.metadataHttpHeaders' failed to satisfy constraint: Member must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+, Member must not be null]",
                "Value 'a b' at 'clientToken' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\u0021-\\u007E]+",
                "Value 'bad name' at 'name' failed to satisfy constraint: Member must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*",
                "Value 'a\u0001b' at 'description' failed to satisfy constraint: Member must satisfy regular expression pattern: [^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]*(?![\\s\\S]).*",
                "Value 'x' at 'eventBusArn' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):event-busv2\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}");
    }

    @Test
    void nameAsANumberIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":5,\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnopqr" +
                "stuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/pr" +
                "obe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}}}");
    }

    @Test
    void nameAsABooleanIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":true,\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmno" +
                "pqrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role" +
                "/probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}}}");
    }

    @Test
    void nameAsAListIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":[\"a\"],\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmn" +
                "opqrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:rol" +
                "e/probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}}}");
    }

    @Test
    void configurationAsAStringIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":\"x\"}");
    }

    @Test
    void webhookConfigurationAsAStringIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":\"x\"}}");
    }

    @Test
    void tagsAsAListIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":[\"a\"]}");
    }

    @Test
    void tagValueAsANumberIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"Tags\":{\"k\":5}}");
    }

    @Test
    void metadataHeadersAsAStringIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}},\"MetadataHttpHeaders\":\"x\"}}}");
    }

    @Test
    void metadataHeaderAsANumberIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}},\"MetadataHttpHeaders\":[5]}}}");
    }

    @Test
    void toleranceAsAStringIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"Signatur" +
                "eHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\",\"TimestampToleranceInSeconds\":\"5\"}}}}}");
    }

    @Test
    void toleranceAsAFloatIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"Signatur" +
                "eHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\",\"TimestampToleranceInSeconds\":5.5}}}}}");
    }

    @Test
    void toleranceBeyond32BitsIsASerializationExceptionWithNoMessage() {
        assertSerializationException("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"Signatur" +
                "eHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\",\"TimestampToleranceInSeconds\":999999999999999" +
                "99999}}}}}");
    }

    @Test
    void listMaxResultsAsAStringIsASerializationExceptionWithNoMessage() {
        assertSerializationException("ListEventSources", "{\"MaxResults\":\"5\"}");
    }

    @Test
    void hmacEnumsListTheirValuesInTheMeasuredOrder() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"HmacAuthConfiguration\":{\"Algorithm\":\"X\",\"Encoding\":\"X\",\"SignatureHttpHe" +
                "ader\":\"X-Sig\",\"SignedMessageComposition\":\"X\",\"SecretEncoding\":\"X\"}}}}}",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.secretEncoding' failed to satisfy constraint: Member must satisfy enum value set: [RAW, BASE64]",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.signedMessageComposition' failed to satisfy constraint: Member must satisfy enum value set: [TIMESTAMP_DOT_BODY, BODY, MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY]",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.encoding' failed to satisfy constraint: Member must satisfy enum value set: [BASE64, HEX]",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.algorithm' failed to satisfy constraint: Member must satisfy enum value set: [SHA1, SHA256]");
    }

    @Test
    void hmacMembersBrokenInTheFirstHalfAreWalkedInHashOrder() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"Signatur" +
                "eHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\",\"SignaturePrefix\":\"\\u00e9\",\"Delimiter\":\"\\u00e" +
                "9\",\"TimestampHttpHeader\":\"bad header\",\"TimestampPrefix\":\"\\u00e9\",\"TimestampToleranceInSeconds\":0,\"Me" +
                "ssageIdHttpHeader\":\"bad header\",\"SecretValue\":\"abc\"}}}}}",
                "Value '\u00e9' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.timestampPrefix' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\u0020-\\u007E]+",
                "Value 'bad header' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.messageIdHttpHeader' failed to satisfy constraint: Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+",
                "Value at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.secretValue' failed to satisfy constraint: Member must have length greater than or equal to 6",
                "Value '\u00e9' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.signaturePrefix' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\u0020-\\u007E]+",
                "Value '\u00e9' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.delimiter' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\u0020-\\u007E]+",
                "Value '0' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.timestampToleranceInSeconds' failed to satisfy constraint: Member must have value greater than or equal to 1",
                "Value 'bad header' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.timestampHttpHeader' failed to satisfy constraint: Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+");
    }

    @Test
    void hmacMembersBrokenInTheSecondHalfAreWalkedInHashOrder() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"HmacAuthConfiguration\":{\"Algorithm\":\"X\",\"Encoding\":\"X\",\"SignatureHttpHe" +
                "ader\":\"bad header\",\"SignedMessageComposition\":\"X\",\"SecretEncoding\":\"X\",\"SecretValue\":\"abc\",\"Timestam" +
                "pToleranceInSeconds\":901}}}}}",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.secretEncoding' failed to satisfy constraint: Member must satisfy enum value set: [RAW, BASE64]",
                "Value at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.secretValue' failed to satisfy constraint: Member must have length greater than or equal to 6",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.signedMessageComposition' failed to satisfy constraint: Member must satisfy enum value set: [TIMESTAMP_DOT_BODY, BODY, MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY]",
                "Value '901' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.timestampToleranceInSeconds' failed to satisfy constraint: Member must have value less than or equal to 900",
                "Value 'bad header' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.signatureHttpHeader' failed to satisfy constraint: Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.encoding' failed to satisfy constraint: Member must satisfy enum value set: [BASE64, HEX]",
                "Value 'X' at 'configuration.webhookEventsConfiguration.authConfiguration.hmacAuthConfiguration.algorithm' failed to satisfy constraint: Member must satisfy enum value set: [SHA1, SHA256]");
    }

    @Test
    void sixTopLevelMembersBrokenAreWalkedInHashOrder() {
        assertAnswers("CreateEventSource", "{\"Name\":\"bad name\",\"EventBusArn\":\"x\",\"Description\":\"a\\u0001b\",\"ClientToken\":\"a b\",\"Tags\":{\" k\":\"v\"}}",
                "Value null at 'configuration' failed to satisfy constraint: Member must not be null",
                "Value 'a b' at 'clientToken' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\u0021-\\u007E]+",
                "Value 'bad name' at 'name' failed to satisfy constraint: Member must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*",
                "Value 'a\u0001b' at 'description' failed to satisfy constraint: Member must satisfy regular expression pattern: [^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]*(?![\\s\\S]).*",
                "Value 'x' at 'eventBusArn' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):event-busv2\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}",
                "Value '{ k=v}' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]");
    }

    @Test
    void nameBreakingPatternAndLengthAnswersTheLengthFirst() {
        assertAnswers("CreateEventSource", "{\"Name\":\" aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"EventBusArn\":\"arn:aws:events:u" +
                "s-east-1:123456789012:event-busv2/orders/abcdefghijklmnopqrstuvwxy\",\"Configuration\":{\"WebhookEventsC" +
                "onfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/probe\",\"AuthConfiguration\":{\"BasicHttpAuthC" +
                "onfiguration\":{}}}}}",
                "Value ' aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' at 'name' failed to satisfy constraint: Member must have length less than or equal to 256",
                "Value ' aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' at 'name' failed to satisfy constraint: Member must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*");
    }

    @Test
    void clientTokenBreakingPatternAndLengthAnswersTheLengthFirst() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}},\"ClientToken\":\"                      " +
                "                                           \"}",
                "Value '                                                                 ' at 'clientToken' failed to satisfy constraint: Member must have length less than or equal to 64",
                "Value '                                                                 ' at 'clientToken' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\u0021-\\u007E]+");
    }

    @Test
    void tokenHeaderBreakingPatternAndLengthAnswersTheLengthFirst() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"                     " +
                "                                                                                                    " +
                "                                                                                                    " +
                "                                                                                                    " +
                "                                                                                                    " +
                "                                                                                            \"}}}}}",
                "Value '                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 ' at 'configuration.webhookEventsConfiguration.authConfiguration.staticTokenAuthConfiguration.tokenHttpHeader' failed to satisfy constraint: Member must have length less than or equal to 512",
                "Value '                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 ' at 'configuration.webhookEventsConfiguration.authConfiguration.staticTokenAuthConfiguration.tokenHttpHeader' failed to satisfy constraint: Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+");
    }

    @Test
    void metadataListBreakingItsSizeAndAMemberAnswersTheSizeFirst() {
        assertAnswers("CreateEventSource", "{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}},\"MetadataHttpHeaders\":[\"bad header\",\"h0" +
                "\",\"h1\",\"h2\",\"h3\",\"h4\",\"h5\",\"h6\",\"h7\",\"h8\",\"h9\",\"h10\",\"h11\",\"h12\",\"h13\",\"h14\",\"h15\",\"h16\",\"h17\",\"h18\"" +
                ",\"h19\"]}}}",
                "Value '[bad header, h0, h1, h2, h3, h4, h5, h6, h7, h8, h9, h10, h11, h12, h13, h14, h15, h16, h17, h18, h19]' at 'configuration.webhookEventsConfiguration.metadataHttpHeaders' failed to satisfy constraint: Member must have length less than or equal to 20",
                "Value '[bad header, h0, h1, h2, h3, h4, h5, h6, h7, h8, h9, h10, h11, h12, h13, h14, h15, h16, h17, h18, h19]' at 'configuration.webhookEventsConfiguration.metadataHttpHeaders' failed to satisfy constraint: Member must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: [!#$%&'*+\\-.^_`|~0-9A-Za-z]+, Member must not be null]");
    }

    @Test
    void tagsBreakingSizeKeysAndValuesAreThreeViolationsInThatOrder() {
        ObjectNode request = json("{\"Name\":\"n\",\"EventBusArn\":\"arn:aws:events:us-east-1:123456789012:event-busv2/orders/abcdefghijklmnop" +
                "qrstuvwxy\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"RoleArn\":\"arn:aws:iam::123456789012:role/" +
                "probe\",\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}}}}}");
        ObjectNode tags = request.putObject("Tags");
        for (int i = 0; i < 199; i++) {
            tags.put("k" + i, "v");
        }
        tags.put(" a", "v");
        tags.put("b", " v");

        List<String> found = violationsOf("CreateEventSource", request);

        assertEquals(3, found.size());
        assertTrue(found.get(0).endsWith("' at 'tags' failed to satisfy constraint: "
                + "Member must have length less than or equal to 200"), found.get(0));
        assertTrue(found.get(1).endsWith("' at 'tags' failed to satisfy constraint: " + "Map keys must satisfy constraint: [Member must have length less than or equal to 128, Member must have length greater than or equal to 1, Member must satisfy regular expression pattern: \\S([\\s\\S]*\\S)?(?![\\s\\S]).*]"), found.get(1));
        assertTrue(found.get(2).endsWith("' at 'tags' failed to satisfy constraint: " + "Map value must satisfy constraint: [Member must have length less than or equal to 256, Member must have length greater than or equal to 0, Member must satisfy regular expression pattern: (\\S([\\s\\S]*\\S)?)?(?![\\s\\S]).*, Member must not be null]"), found.get(2));
    }

    private static final String DESCRIBE_ARN_PATTERN = "arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):"
            + "event-sourcev2\\/(aws\\.service|aws\\.partner|webhook)\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}";

    private static final String ROTATE_ARN_PATTERN = "arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:"
            + "([0-9]{12}):event-sourcev2\\/webhook\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}";

    @Test
    void rotateWebhookCredentialsWalksCredentialThenArnThenClientToken() {
        assertAnswers("RotateWebhookCredentials",
                "{\"EventSourceArn\":\"bad\",\"ClientToken\":\"a b\",\"Credential\":{\"StaticTokenSecretValue\":\"bad\"}}",
                "Value at 'credential.staticTokenSecretValue' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 6",
                "Value 'bad' at 'eventSourceArn' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: " + ROTATE_ARN_PATTERN,
                "Value 'a b' at 'clientToken' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [\\u0021-\\u007E]+");
    }

    @Test
    void rotateWebhookCredentialsPrintsLengthBeforePatternForAnEmptyClientToken() {
        assertAnswers("RotateWebhookCredentials",
                "{\"EventSourceArn\":\"bad\",\"ClientToken\":\"\",\"Credential\":{\"StaticTokenSecretValue\":\"bad\","
                        + "\"BasicHttpPassword\":\"bad\",\"HmacSecretValue\":\"bad\"}}",
                "Value at 'credential.staticTokenSecretValue' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 6",
                "Value at 'credential.basicHttpPassword' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 6",
                "Value at 'credential.hmacSecretValue' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 6",
                "Value 'bad' at 'eventSourceArn' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: " + ROTATE_ARN_PATTERN,
                "Value '' at 'clientToken' failed to satisfy constraint: Member must have length greater than or equal to 1",
                "Value '' at 'clientToken' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [\\u0021-\\u007E]+");
    }

    @Test
    void rotateWebhookCredentialsMissingArnAndOversizeMembers() {
        assertAnswers("RotateWebhookCredentials", "{}",
                "Value null at 'eventSourceArn' failed to satisfy constraint: Member must not be null");
        assertAnswers("RotateWebhookCredentials",
                "{\"EventSourceArn\":\"arn:aws:events:us-east-1:123456789012:event-sourcev2/webhook/x/abcdefghijklmnopqrstuvwxy\","
                        + "\"Credential\":{\"StaticTokenSecretValue\":\"" + "a".repeat(513) + "\"}}",
                "Value at 'credential.staticTokenSecretValue' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 512");
        assertAnswers("RotateWebhookCredentials",
                "{\"EventSourceArn\":\"arn:aws:events:us-east-1:123456789012:event-sourcev2/webhook/x/abcdefghijklmnopqrstuvwxy\","
                        + "\"ClientToken\":\"" + "c".repeat(65) + "\"}",
                "Value '" + "c".repeat(65) + "' at 'clientToken' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 64");
    }

    @Test
    void rotateWebhookCredentialsAnswersSerializationExceptionForACredentialOfTheWrongType() {
        assertSerializationException("RotateWebhookCredentials",
                "{\"EventSourceArn\":\"arn:aws:events:us-east-1:123456789012:event-sourcev2/webhook/x/abcdefghijklmnopqrstuvwxy\","
                        + "\"Credential\":\"secret\"}");
    }

    @Test
    void rotateWebhookCredentialsAcceptsANullCredentialAndANullMember() {
        assertDoesNotThrow(() -> EventBridgeV2RequestShapes.check("RotateWebhookCredentials", json(
                "{\"EventSourceArn\":\"arn:aws:events:us-east-1:123456789012:event-sourcev2/webhook/x/abcdefghijklmnopqrstuvwxy\","
                        + "\"Credential\":null}")));
        assertDoesNotThrow(() -> EventBridgeV2RequestShapes.check("RotateWebhookCredentials", json(
                "{\"EventSourceArn\":\"arn:aws:events:us-east-1:123456789012:event-sourcev2/webhook/x/abcdefghijklmnopqrstuvwxy\","
                        + "\"Credential\":{\"StaticTokenSecretValue\":\"abcdef\",\"HmacSecretValue\":null}}")));
    }

    private static void assertAnswers(String operation, String request, String... violations) {
        assertAnswers(operation, json(request), violations);
    }

    private static void assertAnswers(String operation, ObjectNode request, String... violations) {
        AwsException e = assertThrows(AwsException.class, () -> EventBridgeV2RequestShapes.check(operation, request));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals(SmithyValidationErrors.message(Arrays.asList(violations)), e.getMessage());
    }

    private static List<String> violationsOf(String operation, ObjectNode request) {
        AwsException e = assertThrows(AwsException.class, () -> EventBridgeV2RequestShapes.check(operation, request));
        return List.of(e.getMessage().replaceFirst("^\\d+ validation errors? detected: ", "").split("; (?=Value )"));
    }

    private static void assertSerializationException(String operation, String request) {
        AwsException e = assertThrows(AwsException.class,
                () -> EventBridgeV2RequestShapes.check(operation, json(request)));
        assertEquals("SerializationException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertNull(e.getMessage());
    }

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }
}
