package io.github.hectorvent.floci.services.iot.rules;

/**
 * What a rule can ask about the message beyond its payload: the topic it was published on,
 * the MQTT client that sent it ({@code null} when it did not arrive over MQTT, which
 * {@code clientid()} reports as {@code n/a} as AWS does), and the account that owns the rule.
 */
public record RuleSqlContext(String topic, String clientId, String accountId) {
}
