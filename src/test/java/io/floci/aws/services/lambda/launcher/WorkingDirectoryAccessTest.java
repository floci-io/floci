package io.floci.aws.services.lambda.launcher;

import io.floci.aws.services.lambda.launcher.WorkingDirectoryAccess.PathEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkingDirectoryAccessTest {

    @Test
    void aDirectoryOnlyItsOwnerCanEnterStopsLambdasUser() {
        Map<String, PathEntry> paths = Map.of(
                "/home", PathEntry.directory(0755),
                "/home/nonroot", PathEntry.directory(0700));

        assertEquals(Optional.of("/home/nonroot"), firstClosed("/home/nonroot", paths));
    }

    @Test
    void theFirstClosedDirectoryOnThePathIsTheOneReported() {
        Map<String, PathEntry> paths = Map.of(
                "/srv", PathEntry.directory(0750),
                "/srv/app", PathEntry.directory(0755));

        assertEquals(Optional.of("/srv"), firstClosed("/srv/app", paths));
    }

    @Test
    void directoriesEveryoneCanEnterLetLambdasUserThrough() {
        Map<String, PathEntry> paths = Map.of(
                "/var", PathEntry.directory(0755),
                "/var/task", PathEntry.directory(0711));

        assertEquals(Optional.empty(), firstClosed("/var/task/", paths));
    }

    @Test
    void theRootAndAnUnsetWorkingDirectoryAreNotChecked() {
        Map<String, PathEntry> paths = Map.of("/", PathEntry.directory(0700));

        assertEquals(Optional.empty(), firstClosed("/", paths));
        assertEquals(Optional.empty(), firstClosed("", paths));
        assertEquals(Optional.empty(), firstClosed(null, paths));
    }

    @Test
    void aDirectoryWhoseModeIsUnknownIsNotHeldAgainstTheFunction() {
        assertEquals(Optional.empty(), firstClosed("/missing", Map.of()));
    }

    @Test
    void aSymlinkToADirectoryOnlyItsOwnerCanEnterStopsLambdasUser() {
        Map<String, PathEntry> paths = Map.of(
                "/app", PathEntry.symlink("/home/nonroot"),
                "/home", PathEntry.directory(0755),
                "/home/nonroot", PathEntry.directory(0700));

        assertEquals(Optional.of("/home/nonroot"), firstClosed("/app", paths));
    }

    @Test
    void aRelativeSymlinkIsFollowedFromItsOwnDirectory() {
        Map<String, PathEntry> paths = Map.of(
                "/srv", PathEntry.directory(0755),
                "/srv/current", PathEntry.symlink("releases/1"),
                "/srv/releases", PathEntry.directory(0700),
                "/srv/releases/1", PathEntry.directory(0755));

        assertEquals(Optional.of("/srv/releases"), firstClosed("/srv/current", paths));
    }

    @Test
    void aSymlinkToAnOpenDirectoryUnderAClosedOneStopsLambdasUser() {
        Map<String, PathEntry> paths = Map.of(
                "/app", PathEntry.symlink("../opt/closed/open"),
                "/opt", PathEntry.directory(0755),
                "/opt/closed", PathEntry.directory(0700),
                "/opt/closed/open", PathEntry.directory(0755));

        assertEquals(Optional.of("/opt/closed"), firstClosed("/app", paths));
    }

    @Test
    void aSymlinkLoopEndsTheCheck() {
        Map<String, PathEntry> paths = Map.of(
                "/a", PathEntry.symlink("/b"),
                "/b", PathEntry.symlink("/a"));

        assertEquals(Optional.empty(), firstClosed("/a", paths));
    }

    @Test
    void aFileSystemMountedForTheFunctionIsNotChecked() {
        Map<String, PathEntry> paths = Map.of(
                "/mnt", PathEntry.directory(0755),
                "/mnt/efs", PathEntry.directory(0700),
                "/mnt/efs/app", PathEntry.directory(0700));

        assertEquals(Optional.empty(), WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter(
                "/mnt/efs/app", List.of("/mnt/efs/"), paths::get));
    }

    @Test
    void theDirectoriesAboveAFileSystemMountAreStillChecked() {
        Map<String, PathEntry> paths = Map.of(
                "/mnt", PathEntry.directory(0700),
                "/mnt/efs", PathEntry.directory(0755));

        assertEquals(Optional.of("/mnt"), WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter(
                "/mnt/efs", List.of("/mnt/efs"), paths::get));
    }

    private static Optional<String> firstClosed(String workingDir, Map<String, PathEntry> paths) {
        return WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter(workingDir, List.of(), paths::get);
    }
}
