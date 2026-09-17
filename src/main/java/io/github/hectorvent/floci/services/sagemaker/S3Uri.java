package io.github.hectorvent.floci.services.sagemaker;

record S3Uri(String bucket, String key) {
    static S3Uri parse(String uri) {
        if (uri == null || !uri.startsWith("s3://")) {
            throw new IllegalArgumentException("Expected s3:// URI");
        }
        String rest = uri.substring(5);
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("Expected s3://<bucket>/<key>, got: " + uri);
        }
        if (slash < 0) {
            return new S3Uri(bucket, "");
        }
        return new S3Uri(bucket, rest.substring(slash + 1));
    }
}
