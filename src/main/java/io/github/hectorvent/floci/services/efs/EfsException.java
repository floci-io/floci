package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.Response.Status;

public class EfsException extends AwsException {

    public EfsException(Status status, String errorCode, String message) {
        super(errorCode, message, status.getStatusCode());
    }

    public EfsException(Status status, String errorCode, String message, java.util.Map<String, Object> extendedData) {
        super(errorCode, message, status.getStatusCode(), extendedData);
    }

    public static EfsException fileSystemAlreadyExists(String token, String fileSystemId) {
        return new EfsException(Status.CONFLICT, "FileSystemAlreadyExists", "File system with creation token " + token + " already exists.", java.util.Map.of("FileSystemId", fileSystemId));
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
    
    public static EfsException mountTargetConflict(String message) {
        return new EfsException(Status.CONFLICT, "MountTargetConflict", message);
    }

    public static EfsException accessPointAlreadyExists(String token, String accessPointId) {
        return new EfsException(Status.CONFLICT, "AccessPointAlreadyExists", "Access point with client token " + token + " already exists.", java.util.Map.of("AccessPointId", accessPointId));
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
}
