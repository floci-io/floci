package io.github.hectorvent.floci.services.sagemaker;

record S3Uri(String bucket, String key) {
    static S3Uri parse(String uri) {
        if (uri == null || !uri.startsWith("s3://")) {
            throw new IllegalArgumentException("Expected s3:// URI");
        }
        String rest = uri.substring(5);
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return new S3Uri(rest, "");
        }
        return new S3Uri(rest.substring(0, slash), rest.substring(slash + 1));
    }
}
