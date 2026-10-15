package com.floci.test;

final class IamInlinePolicyTestHelper {

    private IamInlinePolicyTestHelper() {
    }

    static String policyWithNonWhitespaceLength(int targetLength) {
        String prefix = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"";
        String suffix = "\",\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
        return prefix + "x".repeat(targetLength - prefix.length() - suffix.length()) + suffix;
    }

    static String removeCountedWhitespace(String policyDocument) {
        return policyDocument.replace(" ", "")
                .replace("\t", "")
                .replace("\n", "")
                .replace("\r", "");
    }
}
