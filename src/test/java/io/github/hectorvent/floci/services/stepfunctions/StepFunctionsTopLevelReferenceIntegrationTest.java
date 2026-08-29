package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * A JSONata expression that reads the top-level context is refused when the state machine is
 * created, as real AWS refuses it. Every message and location asserted here was measured with
 * {@code aws stepfunctions validate-state-machine-definition --region us-east-1} on the same
 * definition, and the CreateStateMachine wire shape with
 * {@code aws stepfunctions create-state-machine}.
 */
@QuarkusTest
class StepFunctionsTopLevelReferenceIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String VALIDATE_TARGET = "AWSStepFunctions.ValidateStateMachineDefinition";
    private static final String CREATE_TARGET = "AWSStepFunctions.CreateStateMachine";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/service-role/sfn";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response validate(String definition) {
        return given().contentType(CONTENT_TYPE).header("X-Amz-Target", VALIDATE_TARGET)
                .body(OBJECT_MAPPER.createObjectNode().put("definition", definition).toString())
                .when().post("/");
    }

    private static Response create(String name, String definition) {
        return given().contentType(CONTENT_TYPE).header("X-Amz-Target", CREATE_TARGET)
                .body(OBJECT_MAPPER.createObjectNode()
                        .put("name", name)
                        .put("definition", definition)
                        .put("roleArn", ROLE_ARN).toString())
                .when().post("/");
    }

    private static void expectTopLevelReference(String definition, String reference, String location) {
        validate(definition).then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("UNSUPPORTED_JSONATA_EXPRESSION"))
                .body("diagnostics[0].message", equalTo(
                        "Reference to '" + reference + "' at the top level is not supported."))
                .body("diagnostics[0].location", equalTo(location));
    }

    private static void expectAccepted(String definition) {
        validate(definition).then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    private static String passWithOutput(String output) {
        return """
                {"QueryLanguage":"JSONata","StartAt":"E",
                 "States":{"E":{"Type":"Pass","Output":__OUTPUT__,"End":true}}}
                """.replace("__OUTPUT__", output);
    }

    @Test
    @DisplayName("CreateStateMachine refuses the definition with AWS's InvalidDefinition message")
    void createStateMachineRefusesATopLevelReference() {
        create("top-level-reference-" + System.nanoTime(),
                passWithOutput("{\"v\":\"{% phone[0] %}\"}"))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidDefinition"))
                .body("message", equalTo("Invalid State Machine Definition: "
                        + "'UNSUPPORTED_JSONATA_EXPRESSION: Reference to 'phone' at the top level "
                        + "is not supported. at /States/E/Output/v'"));
    }

    @Test
    @DisplayName("CreateStateMachine still accepts the anchored form of the same expression")
    void createStateMachineAcceptsTheAnchoredForm() {
        create("anchored-reference-" + System.nanoTime(),
                passWithOutput("{\"v\":\"{% $states.input.phone[0] %}\"}"))
                .then().statusCode(200);
    }

    @Test
    @DisplayName("Output names the field that holds the expression")
    void outputField() {
        expectTopLevelReference(passWithOutput("{\"v\":\"{% phone %}\"}"),
                "phone", "/States/E/Output/v");
    }

    @Test
    @DisplayName("a nested Output object names the whole path down to the array element")
    void nestedOutputField() {
        expectTopLevelReference(passWithOutput("{\"a\":{\"b\":[\"{% phone %}\"]}}"),
                "phone", "/States/E/Output/a/b[0]");
    }

    @Test
    @DisplayName("Assign is parsed like Output")
    void assignField() {
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"E",
                 "States":{"E":{"Type":"Pass","Assign":{"v":"{% phone %}"},"End":true}}}
                """, "phone", "/States/E/Assign/v");
    }

    @Test
    @DisplayName("a Choice condition names its rule by index")
    void choiceCondition() {
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"C","States":{
                  "C":{"Type":"Choice","Choices":[{"Condition":"{% phone = 1 %}","Next":"E"}],
                       "Default":"E"},
                  "E":{"Type":"Pass","End":true}}}
                """, "phone", "/States/C/Choices[0]/Condition");
    }

    @Test
    @DisplayName("Wait Seconds is parsed")
    void waitSeconds() {
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"E",
                 "States":{"E":{"Type":"Wait","Seconds":"{% phone %}","End":true}}}
                """, "phone", "/States/E/Seconds");
    }

    @Test
    @DisplayName("Fail Cause is parsed")
    void failCause() {
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"E",
                 "States":{"E":{"Type":"Fail","Cause":"{% phone %}"}}}
                """, "phone", "/States/E/Cause");
    }

    @Test
    @DisplayName("Map Items is parsed and so is every state of its ItemProcessor")
    void mapItemsAndItemProcessor() {
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"M","States":{
                  "M":{"Type":"Map","Items":"{% phone %}","ItemProcessor":{
                        "StartAt":"I","States":{"I":{"Type":"Pass","End":true}}},"End":true}}}
                """, "phone", "/States/M/Items");
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"M","States":{
                  "M":{"Type":"Map","Items":"{% $states.input.a %}","ItemProcessor":{
                        "StartAt":"I","States":{"I":{"Type":"Pass",
                          "Output":{"v":"{% phone %}"},"End":true}}},"End":true}}}
                """, "phone", "/States/M/ItemProcessor/States/I/Output/v");
    }

    @Test
    @DisplayName("a Parallel branch state names its branch by index")
    void parallelBranchState() {
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"P","States":{
                  "P":{"Type":"Parallel","Branches":[{"StartAt":"B","States":{
                        "B":{"Type":"Pass","Output":{"v":"{% phone %}"},"End":true}}}],
                       "End":true}}}
                """, "phone", "/States/P/Branches[0]/States/B/Output/v");
    }

    @Test
    @DisplayName("a Catch Output names its catcher by index")
    void catchOutput() {
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"E","States":{
                  "E":{"Type":"Task","Resource":"arn:aws:states:::aws-sdk:s3:listBuckets",
                       "Arguments":{},"Catch":[{"ErrorEquals":["States.ALL"],
                        "Output":{"v":"{% phone %}"},"Next":"F"}],"End":true},
                  "F":{"Type":"Pass","End":true}}}
                """, "phone", "/States/E/Catch[0]/Output/v");
    }

    @Test
    @DisplayName("an earlier Assign does not make the bare name legal; only $phone is")
    void anEarlierAssignDoesNotBindTheBareName() {
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"A","States":{
                  "A":{"Type":"Pass","Assign":{"phone":"{% 1 %}"},"Next":"E"},
                  "E":{"Type":"Pass","Output":{"v":"{% phone %}"},"End":true}}}
                """, "phone", "/States/E/Output/v");
        expectAccepted("""
                {"QueryLanguage":"JSONata","StartAt":"A","States":{
                  "A":{"Type":"Pass","Assign":{"phone":"{% 1 %}"},"Next":"E"},
                  "E":{"Type":"Pass","Output":{"v":"{% $phone %}"},"End":true}}}
                """);
    }

    @Test
    @DisplayName("each distinct name in one field gets its own diagnostic")
    void everyDistinctNameInOneField() {
        validate(passWithOutput("{\"v\":\"{% aaa + bbb %}\"}"))
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                .body("diagnostics[0].message", equalTo(
                        "Reference to 'aaa' at the top level is not supported."))
                .body("diagnostics[1].message", equalTo(
                        "Reference to 'bbb' at the top level is not supported."))
                .body("diagnostics[1].location", equalTo("/States/E/Output/v"));
    }

    @Test
    @DisplayName("the context item itself is named '$'")
    void contextItem() {
        expectTopLevelReference(passWithOutput("{\"v\":\"{% $.phone %}\"}"),
                "$", "/States/E/Output/v");
    }

    @Test
    @DisplayName("a name reached through $states, a predicate or a variable is accepted")
    void anchoredExpressionsAreAccepted() {
        expectAccepted(passWithOutput("{\"v\":\"{% $states.input.phone %}\"}"));
        expectAccepted(passWithOutput("{\"v\":\"{% $states.input.items[phone > 3] %}\"}"));
        expectAccepted(passWithOutput("{\"v\":\"{% $map($states.input.a, function($x){ $x.phone }) %}\"}"));
        expectAccepted(passWithOutput("{\"v\":\"{% $phone %}\"}"));
    }

    @Test
    @DisplayName("a JSONPath state machine is not parsed as JSONata")
    void jsonPathMachineIsUntouched() {
        expectAccepted("""
                {"StartAt":"E",
                 "States":{"E":{"Type":"Pass","Parameters":{"v":"{% phone %}"},"End":true}}}
                """);
    }

    @Test
    @DisplayName("Comment, Next and ErrorEquals keep a {% %} string as text, as AWS does")
    void fieldsAwsDoesNotParse() {
        expectAccepted("""
                {"QueryLanguage":"JSONata","StartAt":"E",
                 "States":{"E":{"Type":"Pass","Comment":"{% phone %}","End":true}}}
                """);
        expectAccepted("""
                {"QueryLanguage":"JSONata","StartAt":"E","States":{
                  "E":{"Type":"Task","Resource":"arn:aws:states:::aws-sdk:s3:listBuckets",
                       "Arguments":{},"Retry":[{"ErrorEquals":["{% phone %}"]}],"End":true}}}
                """);
    }

    @Test
    @DisplayName("ReaderConfig.CSVHeaders holds column names, not expressions, as AWS does")
    void readerConfigCsvHeadersAreNotParsed() {
        expectAccepted("""
                {"QueryLanguage":"JSONata","StartAt":"M","States":{
                  "M":{"Type":"Map",
                       "ItemReader":{"Resource":"arn:aws:states:::s3:getObject",
                                     "Arguments":{"Bucket":"b","Key":"k"},
                                     "ReaderConfig":{"InputType":"CSV","CSVHeaderLocation":"GIVEN",
                                                     "CSVHeaders":["{% phone %}"]}},
                       "ItemProcessor":{"StartAt":"I","States":{"I":{"Type":"Pass","End":true}}},
                       "End":true}}}
                """);
    }

    @Test
    @DisplayName("a payload key named after an ASL field is still an expression, as AWS parses it")
    void aPayloadKeyNamedAfterAnAslFieldIsParsed() {
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"E",
                 "States":{"E":{"Type":"Pass","Assign":{"Next":"{% phone %}"},"End":true}}}
                """, "phone", "/States/E/Assign/Next");
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"E","States":{
                  "E":{"Type":"Task","Resource":"arn:aws:states:::lambda:invoke",
                       "Arguments":{"FunctionName":"f","Payload":{"Comment":"{% phone %}"}},
                       "End":true}}}
                """, "phone", "/States/E/Arguments/Payload/Comment");
        expectTopLevelReference(passWithOutput("{\"ItemProcessor\":{\"Iterator\":\"{% phone %}\"}}"),
                "phone", "/States/E/Output/ItemProcessor/Iterator");
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"M","States":{
                  "M":{"Type":"Map","ItemSelector":{"Next":"{% phone %}"},
                       "ItemProcessor":{"StartAt":"I","States":{"I":{"Type":"Pass","End":true}}},
                       "End":true}}}
                """, "phone", "/States/M/ItemSelector/Next");
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"M","States":{
                  "M":{"Type":"Map","ItemBatcher":{"BatchInput":{"Comment":"{% phone %}"}},
                       "ItemProcessor":{"StartAt":"I","States":{"I":{"Type":"Pass","End":true}}},
                       "End":true}}}
                """, "phone", "/States/M/ItemBatcher/BatchInput/Comment");
        expectTopLevelReference("""
                {"QueryLanguage":"JSONata","StartAt":"E","States":{
                  "E":{"Type":"Task","Resource":"arn:aws:states:::lambda:invoke",
                       "Arguments":{"FunctionName":"f"},
                       "Catch":[{"ErrorEquals":["States.ALL"],"Assign":{"Retry":"{% phone %}"},
                                 "Next":"D"}],"End":true},
                  "D":{"Type":"Pass","End":true}}}
                """, "phone", "/States/E/Catch[0]/Assign/Retry");
    }

    @Test
    @DisplayName("a syntax error is left to the execution, as it is today")
    void syntaxErrorsAreOutOfScope() {
        expectAccepted(passWithOutput("{\"v\":\"{% phone[1,2) %}\"}"));
    }
}
