package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.Response.Status;

public class EfsException extends AwsException {

    public EfsException(Status status, String errorCode, String message) {
        super(errorCode, message, status.getStatusCode());
    }

    public static EfsException fileSystemNotFound(String fileSystemId) {
        return new EfsException(Status.NOT_FOUND, "FileSystemNotFound", "File system " + fileSystemId + " does not exist.");
    }

    public static EfsException fileSystemInUse(String fileSystemId) {
        return new EfsException(Status.CONFLICT, "FileSystemInUse", "File system " + fileSystemId + " is in use.");
    }

    public static EfsException mountTargetNotFound(String mountTargetId) {
        return new EfsException(Status.NOT_FOUND, "MountTargetNotFound", "Mount target " + mountTargetId + " does not exist.");
    }

    public static EfsException accessPointNotFound(String accessPointId) {
        return new EfsException(Status.NOT_FOUND, "AccessPointNotFound", "Access point " + accessPointId + " does not exist.");
    }
    
    public static EfsException policyNotFound(String fileSystemId) {
        return new EfsException(Status.NOT_FOUND, "PolicyNotFound", "Policy for file system " + fileSystemId + " does not exist.");
    }
    
    public static EfsException badRequest(String message) {
        return new EfsException(Status.BAD_REQUEST, "BadRequest", message);
    }
    
    public static EfsException idempotentParameterMismatch() {
        return new EfsException(Status.BAD_REQUEST, "IdempotentParameterMismatch", "A resource with this token already exists with different parameters.");
    }
}
