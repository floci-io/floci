package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;

import java.util.UUID;

/** Keeps an existing image reachable while a fixture replaces its tag. */
final class PreservedImageTag implements AutoCloseable {
    private final DockerClient docker;
    private final String repository;
    private final String tag;
    private final String backup;

    PreservedImageTag(DockerClient docker, String repository, String tag) {
        this.docker = docker;
        this.repository = repository;
        this.tag = tag;
        String originalId;
        try {
            originalId = docker.inspectImageCmd(repository + ":" + tag).exec().getId();
        } catch (NotFoundException expected) {
            // An initially absent tag must be absent again after the fixture.
            originalId = null;
        }
        if (originalId == null) {
            backup = null;
        } else {
            String backupTag = UUID.randomUUID().toString();
            docker.tagImageCmd(originalId, "floci/helper-base-backup", backupTag).exec();
            backup = "floci/helper-base-backup:" + backupTag;
        }
    }

    @Override
    public void close() {
        if (backup != null) {
            docker.tagImageCmd(backup, repository, tag).exec();
            // Retain the recovery tag if restoration throws.
            docker.removeImageCmd(backup).exec();
        } else {
            try {
                docker.removeImageCmd(repository + ":" + tag).exec();
            } catch (NotFoundException expected) {
                // A failed pull may never have created the initially absent tag.
            }
        }
    }
}
