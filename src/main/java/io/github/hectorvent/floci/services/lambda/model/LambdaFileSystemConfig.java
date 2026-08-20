package io.github.hectorvent.floci.services.lambda.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class LambdaFileSystemConfig {

    private String arn;
    private String localMountPath;

    public LambdaFileSystemConfig() {
    }

    public LambdaFileSystemConfig(String arn, String localMountPath) {
        this.arn = arn;
        this.localMountPath = localMountPath;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getLocalMountPath() {
        return localMountPath;
    }

    public void setLocalMountPath(String localMountPath) {
        this.localMountPath = localMountPath;
    }
}
