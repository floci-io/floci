package io.floci.aws.testing;

/** Policies shared by native and real-sidecar HTTP data-source tests. */
public final class AppSyncHttpTestPolicies {

    private AppSyncHttpTestPolicies() { }

    public static String trust(String apiArn, String account) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                "Principal":{"Service":"appsync.amazonaws.com"},
                "Condition":{"ArnEquals":{"aws:SourceArn":"%s"},"StringEquals":{"aws:SourceAccount":"%s"}}}]}
                """.formatted(apiArn, account);
    }

    public static String getSchedule(String effect, String region, String account) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Action":"scheduler:GetSchedule",
                "Resource":"arn:aws:scheduler:%s:%s:schedule/default/missing"}]}
                """.formatted(effect, region, account);
    }
}
