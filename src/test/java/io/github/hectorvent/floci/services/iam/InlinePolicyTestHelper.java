package io.github.hectorvent.floci.services.iam;

final class InlinePolicyTestHelper {

    private InlinePolicyTestHelper() {}

    static String policyWithNonWhitespaceLength(int targetLength) {
        String prefix = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"";
        String suffix = "\",\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
        return prefix + "x".repeat(targetLength - prefix.length() - suffix.length()) + suffix;
    }
}
