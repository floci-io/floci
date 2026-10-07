package io.github.hectorvent.floci.services.apigatewayv2;

import org.junit.jupiter.params.provider.Arguments;

import java.util.Locale;
import java.util.stream.Stream;

public final class AuthorizerPolicyFixtures {

    private AuthorizerPolicyFixtures() {}

    public static String renderStatements(String statements, String arn) {
        return statements.replace("${arn}", arn).replace("${upperArn}", arn.toUpperCase(Locale.ROOT))
                .replace("${prefix}", arn.substring(0, arn.indexOf("/")));
    }

    public static Stream<Arguments> policies() {
        String allow = statement("Allow", "execute-api:Invoke", "${arn}");
        String deny = statement("Deny", "execute-api:Invoke", "${arn}");
        String unrelatedAllow = statement("Allow", "execute-api:Invoke", "${arn}/other");
        String unrelatedDeny = statement("Deny", "execute-api:Invoke", "${arn}/other");
        String localAddresses = "[\"127.0.0.0/8\",\"::1/128\"]";
        String otherAddress = "\"198.51.100.0/24\"";
        return Stream.of(
                Arguments.of("exact allow", "[" + allow + "]", 200),
                Arguments.of("implicit deny", "[" + unrelatedAllow + "]", 403),
                Arguments.of("wrong action", "[" + statement("Allow", "s3:GetObject", "*") + "]", 403),
                Arguments.of("later deny", "[" + allow + "," + deny + "]", 403),
                Arguments.of("earlier deny", "[" + deny + "," + allow + "]", 403),
                Arguments.of("unrelated deny", "[" + unrelatedDeny + "," + allow + "]", 200),
                Arguments.of("later allow", "[" + unrelatedAllow + "," + allow + "]", 200),
                Arguments.of("singleton statement", allow, 200),
                Arguments.of("wildcard", "[" + statement("Allow", "execute-api:*", "${prefix}/*") + "]", 200),
                Arguments.of("action and resource lists", "[{\"Effect\":\"Allow\","
                        + "\"Action\":[\"s3:GetObject\",\"EXECUTE-API:Invoke\"],"
                        + "\"Resource\":[\"${arn}/other\",\"${arn}\"]}]", 200),
                Arguments.of("case sensitive resource", "[" + statement("Allow", "execute-api:Invoke", "${upperArn}") + "]", 403),
                Arguments.of("missing action", "[{\"Effect\":\"Allow\",\"Resource\":\"*\"}]", 500),
                Arguments.of("missing resource", "[{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\"}]", 500),
                Arguments.of("malformed later statement", "[" + allow + ",{}]", 500),
                Arguments.of("invalid effect", "[{\"Effect\":\"Other\",\"Action\":\"*\",\"Resource\":\"*\"}]", 500),
                Arguments.of("empty statements", "[]", 500),
                Arguments.of("missing effect", "[{\"Action\":\"*\",\"Resource\":\"*\"}]", 500),
                Arguments.of("both action fields", "[{\"Effect\":\"Allow\",\"Action\":\"*\","
                        + "\"NotAction\":\"s3:*\",\"Resource\":\"*\"}]", 500),
                Arguments.of("negated action", "[{\"Effect\":\"Allow\",\"NotAction\":\"s3:*\",\"Resource\":\"${arn}\"}]", 200),
                Arguments.of("negated resource excludes request", "[{\"Effect\":\"Allow\","
                        + "\"Action\":\"execute-api:Invoke\",\"NotResource\":\"${arn}\"}]", 403),
                Arguments.of("invalid action list", "[{\"Effect\":\"Allow\",\"Action\":[\"*\",1],\"Resource\":\"*\"}]", 500),
                Arguments.of("matching source IP deny", "[" + allow + ","
                        + ipStatement("Deny", "IpAddress", localAddresses) + "]", 403),
                Arguments.of("nonmatching source IP deny", "[" + allow + ","
                        + ipStatement("Deny", "IpAddress", otherAddress) + "]", 200),
                Arguments.of("matching source IP allow", "[" + ipStatement("Allow", "IpAddress", localAddresses) + "]", 200),
                Arguments.of("nonmatching source IP allow", "[" + ipStatement("Allow", "IpAddress", otherAddress) + "]", 403),
                Arguments.of("negated source IP deny", "[" + allow + ","
                        + ipStatement("Deny", "NotIpAddress", otherAddress) + "]", 403),
                Arguments.of("empty condition is unconditional", "[{\"Effect\":\"Allow\",\"Action\":\"*\","
                        + "\"Resource\":\"*\",\"Condition\":{}}]", 200),
                Arguments.of("empty later condition denies", "[" + allow + ",{\"Effect\":\"Deny\",\"Action\":\"*\","
                        + "\"Resource\":\"*\",\"Condition\":{}}]", 403),
                Arguments.of("insecure transport deny", "[" + allow + ","
                        + conditionStatement("Deny", "Bool", "aws:SecureTransport", "\"false\"") + "]", 403),
                Arguments.of("secure transport allow", "["
                        + conditionStatement("Allow", "Bool", "aws:SecureTransport", "\"true\"") + "]", 403),
                Arguments.of("current time allow", "["
                        + conditionStatement("Allow", "DateGreaterThan", "aws:CurrentTime", "\"2000-01-01T00:00:00Z\"") + "]", 200),
                Arguments.of("invalid condition", "[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\",\"Condition\":true}]", 500),
                Arguments.of("condition operator without keys", "[{\"Effect\":\"Allow\",\"Action\":\"*\","
                        + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":\"invalid\"}}]", 500),
                Arguments.of("condition operator with empty keys", "[{\"Effect\":\"Allow\",\"Action\":\"*\","
                        + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{}}}]", 500),
                Arguments.of("unknown condition operator deny", "[" + allow + ","
                        + conditionStatement("Deny", "IpAddres", "aws:SourceIp", localAddresses) + "]", 500),
                Arguments.of("empty condition values deny", "[" + allow + ","
                        + ipStatement("Deny", "NotIpAddress", "[]") + "]", 500),
                Arguments.of("object condition value", "["
                        + ipStatement("Allow", "IpAddress", "{\"cidr\":\"127.0.0.0/8\"}") + "]", 500),
                Arguments.of("if exists operator", "["
                        + conditionStatement("Allow", "StringEqualsIfExists", "aws:Unset", "\"x\"") + "]", 200),
                Arguments.of("boolean condition value", "[" + allow + ","
                        + conditionStatement("Deny", "Bool", "aws:SecureTransport", "false") + "]", 403));
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String ipStatement(String effect, String operator, String addresses) {
        return conditionStatement(effect, operator, "aws:SourceIp", addresses);
    }

    private static String conditionStatement(String effect, String operator, String key, String values) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"${arn}\","
                + "\"Condition\":{\"" + operator + "\":{\"" + key + "\":" + values + "}}}";
    }
}
