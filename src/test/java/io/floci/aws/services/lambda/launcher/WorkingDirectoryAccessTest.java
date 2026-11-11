package io.floci.aws.services.lambda.launcher;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkingDirectoryAccessTest {

    @Test
    void aDirectoryOnlyItsOwnerCanEnterStopsLambdasUser() {
        Map<String, Integer> modes = Map.of("/home", 0755, "/home/nonroot", 0700);

        assertEquals(Optional.of("/home/nonroot"),
                WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter("/home/nonroot", modes::get));
    }

    @Test
    void theFirstClosedDirectoryOnThePathIsTheOneReported() {
        Map<String, Integer> modes = Map.of("/srv", 0750, "/srv/app", 0755);

        assertEquals(Optional.of("/srv"),
                WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter("/srv/app", modes::get));
    }

    @Test
    void directoriesEveryoneCanEnterLetLambdasUserThrough() {
        Map<String, Integer> modes = Map.of("/var", 0755, "/var/task", 0711);

        assertEquals(Optional.empty(),
                WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter("/var/task/", modes::get));
    }

    @Test
    void theRootAndAnUnsetWorkingDirectoryAreNotChecked() {
        assertEquals(Optional.empty(), WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter("/", path -> 0700));
        assertEquals(Optional.empty(), WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter("", path -> 0700));
        assertEquals(Optional.empty(), WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter(null, path -> 0700));
    }

    @Test
    void aDirectoryWhoseModeIsUnknownIsNotHeldAgainstTheFunction() {
        assertEquals(Optional.empty(),
                WorkingDirectoryAccess.firstDirectoryLambdaCannotEnter("/missing", path -> null));
    }
}
