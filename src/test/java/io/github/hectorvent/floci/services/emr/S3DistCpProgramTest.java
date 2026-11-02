package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.launcher.BootstrapProcess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Floci's S3DistCp program, its options and what it plans to write where, run by the host's {@code python3}: the
 * planning needs no Spark, which only the copy itself runs under in a step's container.
 */
class S3DistCpProgramTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Loads the program, the file named first, as a module; each driver below goes on to print its answers as JSON.
    private static final String LOAD_PROGRAM = """
            import importlib.util, json, sys
            spec = importlib.util.spec_from_file_location("s3distcp", sys.argv[1])
            program = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(program)
            """;
    // Prints what each case gives.
    private static final String DRIVER = LOAD_PROGRAM + """
            cases = json.loads(sys.stdin.read())
            answers = []
            for case in cases:
                try:
                    options, settings = program.parse(case["argv"])
                    if "files" not in case:
                        answers.append({"options": options, "settings": settings})
                        continue
                    files = [tuple(f) for f in case["files"]]
                    outputs = program.plan(files, options, case.get("extensions", {}), case.get("outputExtension"))
                    program.check_targets(outputs, options["dest"], options.get("outputManifest"), lambda l: l)
                    answers.append({"outputs": [[target, [f[1] for f in sources]] for target, sources in outputs]})
                except program.UsageError as e:
                    answers.append({"error": str(e)})
            print(json.dumps(answers))
            """;
    // Runs replace against a file system of names whose listed renames fail, and prints what each case leaves.
    private static final String REPLACE_DRIVER = LOAD_PROGRAM + """
            class Files:
                def __init__(self, files, failing):
                    self.files, self.failing = dict(files), {tuple(f) for f in failing}

                def rename(self, src, dst):
                    if (src, dst) in self.failing or src not in self.files or dst in self.files:
                        return False
                    self.files[dst] = self.files.pop(src)
                    return True

                def delete(self, path, recursive):
                    return self.files.pop(path, None) is not None

            answers = []
            for failing in json.loads(sys.stdin.read()):
                files = Files({"t": "old", "t.new": "new"}, failing)
                try:
                    program.replace(files, "t.new", "t", "t.old")
                    answers.append({"files": files.files})
                except IOError as e:
                    answers.append({"files": files.files, "error": str(e)})
            print(json.dumps(answers))
            """;
    // Prints what a file's path starts with under each --srcPrefixesFile line, given the path Hadoop makes of it.
    private static final String PREFIX_DRIVER = LOAD_PROGRAM + """
            print(json.dumps([program.prefix_start(line, path) for line, path in json.loads(sys.stdin.read())]))
            """;
    private static final long MIB = 1024 * 1024;
    // The Release Guide's log layout, by day and hour.
    private static final List<List<Object>> LOGS = List.of(
            file("2017-02-22/05/a.log", 3 * MIB), file("2017-02-22/05/b.log", 3 * MIB),
            file("2017-02-22/05/c.log.gz", 5 * MIB), file("2017-02-22/06/d.log", MIB),
            file("2017-02-22/06/notes.txt", 10));

    @TempDir
    static Path folder;

    @BeforeAll
    static void needsPython() throws Exception {
        boolean python;
        try {
            python = BootstrapProcess.run(new ProcessBuilder("python3", "--version")).exit() == 0;
        } catch (IOException e) {
            python = false;
        }
        assumeTrue(python, "the host has no python3");
        try (InputStream in = S3DistCpProgramTest.class.getClassLoader()
                .getResourceAsStream(EmrSparkSteps.S3_DIST_CP_RESOURCE)) {
            Files.write(folder.resolve("s3-dist-cp.py"), in.readAllBytes());
        }
    }

    @Test
    void optionsTakeEitherFormAndGenericSettingsTheirOwn() throws Exception {
        JsonNode parsed = run(List.of(new Case(List.of("--src", "s3://bucket/logs", "--dest=hdfs:///output",
                "--targetSize=2", "-D", "fs.s3a.connection.maximum=8", "-Dmapreduce.job.reduces=1", "--deleteOnSuccess",
                "--requirePreviousManifest=false")))).get(0);

        assertEquals(MAPPER.readTree("""
                {"src": "s3://bucket/logs", "dest": "hdfs:///output", "targetSize": "2", "deleteOnSuccess": true,
                 "requirePreviousManifest": false}"""), parsed.path("options"));
        assertEquals(MAPPER.readTree("[[\"fs.s3a.connection.maximum\", \"8\"], [\"mapreduce.job.reduces\", \"1\"]]"),
                parsed.path("settings"));
    }

    @Test
    void requirePreviousManifestTakesTrueOrFalseInEitherFormAndAloneIsTrue() throws Exception {
        List<String> argv = List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output");
        List<JsonNode> answers = run(List.of(
                new Case(concat(argv, "--requirePreviousManifest", "false")),
                new Case(concat(argv, "--requirePreviousManifest", "TRUE")),
                new Case(concat(argv, "--requirePreviousManifest", "--deleteOnSuccess")),
                new Case(concat(argv, "--requirePreviousManifest=maybe"))));

        assertEquals(List.of("false", "true", "true"), answers.subList(0, 3).stream()
                .map(answer -> answer.path("options").path("requirePreviousManifest").asText()).toList());
        assertTrue(answers.get(2).path("options").path("deleteOnSuccess").asBoolean());
        assertEquals("--requirePreviousManifest takes true or false, not maybe", answers.get(3).path("error").asText());
    }

    @Test
    void aCopyThatWouldLoseDataFailsBeforeAnythingIsWritten() throws Exception {
        List<List<Object>> logs = new ArrayList<>(LOGS);
        logs.add(file("2017-02-22/05/c.log", 4));
        List<JsonNode> answers = run(List.of(
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--outputCodec=gzip"), logs,
                        ".gz"),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=s3a://bucket/logs/"), LOGS),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output",
                        "--outputManifest=2017-02-22/06/d.log"), LOGS),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=s3a://bucket/logs", "--srcPattern=.*/05/[ab]\\.log",
                        "--outputCodec=gzip"), LOGS, ".gz")));

        assertEquals("2017-02-22/05/c.log and 2017-02-22/05/c.log.gz would both be written to"
                + " hdfs:///output/2017-02-22/05/c.log.gz", answers.get(0).path("error").asText());
        assertEquals("2017-02-22/05/a.log would be written over its source s3a://bucket/logs/2017-02-22/05/a.log",
                answers.get(1).path("error").asText());
        assertEquals("2017-02-22/06/d.log and the manifest would both be written to"
                + " hdfs:///output/2017-02-22/06/d.log", answers.get(2).path("error").asText());
        // The same folder is fine when every output has a name of its own.
        assertEquals(List.of("2017-02-22/05/a.log.gz", "2017-02-22/05/b.log.gz"), targets(answers.get(3)));
    }

    @Test
    void anOutputThatWouldLeaveTheDestinationFailsTheCopy() throws Exception {
        // A manifest's baseName is the output's path under --dest, and Hadoop's paths resolve a "..".
        List<JsonNode> answers = run(List.of(
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--copyFromManifest",
                        "--previousManifest=s3://bucket/manifest.gz"),
                        List.of(file("2017-02-22/../../victim", 3))),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--outputManifest=../manifest.gz"),
                        LOGS)));

        assertEquals(List.of("2017-02-22/../../victim leads out of --dest hdfs:///output",
                "../manifest.gz leads out of --dest hdfs:///output"),
                answers.stream().map(answer -> answer.path("error").asText()).toList());
    }

    @Test
    void anOutputWithNoFileNameFailsTheCopy() throws Exception {
        // Hadoop's paths would write it as its folder.
        List<String> argv = List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output");
        List<JsonNode> answers = run(List.of(
                new Case(concat(argv, "--groupBy=.*/(z*)[ab]\\.log"), LOGS),
                new Case(concat(argv, "--outputManifest="), LOGS)));

        assertEquals(List.of("2017-02-22/05/a.log, 2017-02-22/05/b.log would be written to"
                        + " hdfs:///output/2017-02-22/05/, which names no file",
                "the manifest would be written to hdfs:///output/, which names no file"),
                answers.stream().map(answer -> answer.path("error").asText()).toList());
    }

    @Test
    void optionsS3DistCpRefusesFailItBeforeAnythingIsCopied() throws Exception {
        List<JsonNode> answers = run(List.of(
                new Case(List.of("--src=s3://bucket/logs/")),
                new Case(List.of("--src=s3://amzn_s3_demo/logs/", "--dest=hdfs:///output")),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--groupBy=.*\\.log")),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--outputCodec=zip")),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--targetSize=ten")),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--deleteOnSuccess=yes")),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--srcPatern=.*"))));

        assertEquals(List.of("--dest is required",
                "S3DistCp does not support Amazon S3 bucket names that contain the underscore character:"
                        + " s3://amzn_s3_demo/logs/",
                "The --groupBy regular expression .*\\.log has no parenthetical group to name the concatenated"
                        + " files by",
                "--outputCodec takes gzip, gz, lzo, lzop, snappy or none, not zip",
                "--targetSize takes a whole number, not ten",
                "--deleteOnSuccess takes no value",
                "Unknown option --srcPatern"), answers.stream().map(answer -> answer.path("error").asText()).toList());
    }

    @Test
    void aCopyKeepsEachFilesFolderUnderTheDestinationAndASourcePatternMatchesTheWholePath() throws Exception {
        List<JsonNode> answers = run(List.of(
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output"), LOGS),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--srcPattern=.*\\.log"), LOGS),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--srcPattern=\\d+"), LOGS)));

        assertEquals(outputs("""
                [["2017-02-22/05/a.log", ["2017-02-22/05/a.log"]], ["2017-02-22/05/b.log", ["2017-02-22/05/b.log"]],
                 ["2017-02-22/05/c.log.gz", ["2017-02-22/05/c.log.gz"]],
                 ["2017-02-22/06/d.log", ["2017-02-22/06/d.log"]],
                 ["2017-02-22/06/notes.txt", ["2017-02-22/06/notes.txt"]]]"""), answers.get(0).path("outputs"));
        assertEquals(List.of("2017-02-22/05/a.log", "2017-02-22/05/b.log", "2017-02-22/06/d.log"),
                targets(answers.get(1)));
        assertEquals(List.of(), targets(answers.get(2)));
    }

    @Test
    void anOutputCodecRenamesEachFileForItsExtensionAndNoneTakesTheSourcesOff() throws Exception {
        List<String> argv = List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", "--srcPattern=.*/05/.*");
        List<JsonNode> answers = run(List.of(
                new Case(argv, LOGS, ".gz"),
                new Case(argv, LOGS, "")));

        assertEquals(List.of("2017-02-22/05/a.log.gz", "2017-02-22/05/b.log.gz", "2017-02-22/05/c.log.gz"),
                targets(answers.get(0)));
        assertEquals(List.of("2017-02-22/05/a.log", "2017-02-22/05/b.log", "2017-02-22/05/c.log"),
                targets(answers.get(1)));
    }

    @Test
    void groupByConcatenatesTheFilesItsGroupsNameAndTargetSizeBreaksAGroupIntoNumberedParts() throws Exception {
        String groupBy = "--groupBy=.*/(\\d\\d)/.*\\.log.*";
        List<JsonNode> answers = run(List.of(
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", groupBy), LOGS),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", groupBy, "--targetSize=6"), LOGS),
                new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output", groupBy, "--targetSize=6",
                        "--outputCodec=gzip"), LOGS, ".gz")));

        assertEquals(outputs("""
                [["2017-02-22/05/05", ["2017-02-22/05/a.log", "2017-02-22/05/b.log", "2017-02-22/05/c.log.gz"]],
                 ["2017-02-22/06/06", ["2017-02-22/06/d.log"]]]"""), answers.get(0).path("outputs"));
        // "broken into parts as: myfile0.gz, myfile1.gz", and a group within the size stays whole.
        assertEquals(outputs("""
                [["2017-02-22/05/050", ["2017-02-22/05/a.log", "2017-02-22/05/b.log"]],
                 ["2017-02-22/05/051", ["2017-02-22/05/c.log.gz"]], ["2017-02-22/06/06", ["2017-02-22/06/d.log"]]]"""),
                answers.get(1).path("outputs"));
        assertEquals(List.of("2017-02-22/05/050.gz", "2017-02-22/05/051.gz", "2017-02-22/06/06.gz"),
                targets(answers.get(2)));
    }

    @Test
    void anAppendedCopyTakesTheFilesPlaceOnlyOnceItCanAndTheOldFileComesBackOtherwise() throws Exception {
        List<JsonNode> answers = runDriver(REPLACE_DRIVER, List.of(
                List.of(),
                List.of(List.of("t.new", "t")),
                List.of(List.of("t", "t.old")),
                List.of(List.of("t.new", "t"), List.of("t.old", "t"))));

        assertEquals(MAPPER.readTree("{\"files\": {\"t\": \"new\"}}"), answers.get(0));
        assertEquals(MAPPER.readTree("""
                {"files": {"t": "old", "t.new": "new"}, "error": "Could not rename t.new to t"}"""), answers.get(1));
        assertEquals(MAPPER.readTree("""
                {"files": {"t": "old", "t.new": "new"}, "error": "Could not move t aside to replace it"}"""),
                answers.get(2));
        // Should even the way back fail, the old content keeps the name it stepped aside to.
        assertEquals(MAPPER.readTree("""
                {"files": {"t.new": "new", "t.old": "old"},
                 "error": "Could not rename t.new to t; what it held is at t.old"}"""), answers.get(3));
    }

    @Test
    void withoutAnOutputCodecAGroupKeepsTheCompressionItsFilesShareAndAMixedOneHasNone() throws Exception {
        List<String> argv = List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output");
        List<JsonNode> answers = run(List.of(
                new Case(concat(argv, "--groupBy=.*/(\\d\\d)/.*\\.log.*"), LOGS),
                new Case(concat(argv, "--groupBy=.*/(\\d\\d)/.*\\.gz"), LOGS),
                new Case(concat(argv, "--groupBy=.*/(\\d\\d)/.*(\\.gz)"), LOGS)));

        // 05 holds a.log, b.log and c.log.gz, written uncompressed; 06 holds d.log alone.
        assertEquals(List.of("2017-02-22/05/05", "2017-02-22/06/06"), targets(answers.get(0)));
        assertEquals(List.of("2017-02-22/05/05.gz"), targets(answers.get(1)));
        // A group's name ending in the extension does not get it twice.
        assertEquals(List.of("2017-02-22/05/05.gz"), targets(answers.get(2)));
    }

    @Test
    void aSourcePrefixEndingInASlashNamesThatFolderAloneAndAnyOtherIsAPrefixAsS3ListsOne() throws Exception {
        // Hadoop's Path drops the line's trailing slash.
        List<JsonNode> answers = runDriver(PREFIX_DRIVER, List.of(
                List.of("s3://bucket/logs/day/", "/logs/day"),
                List.of("s3://bucket/logs/day", "/logs/day")));

        assertEquals("/logs/day/", answers.get(0).asText());
        assertFalse("/logs/day-old/a.log".startsWith(answers.get(0).asText()));
        assertEquals("/logs/day", answers.get(1).asText());
        assertTrue("/logs/day-old/a.log".startsWith(answers.get(1).asText()));
    }

    @Test
    void numberFilesPutsASequentialNumberBeforeEachFileFromTheStartingIndex() throws Exception {
        List<JsonNode> answers = run(List.of(new Case(List.of("--src=s3://bucket/logs/", "--dest=hdfs:///output",
                "--srcPattern=.*\\.log", "--numberFiles", "--startingIndex=1"), LOGS)));

        assertEquals(List.of("2017-02-22/05/1a.log", "2017-02-22/05/2b.log", "2017-02-22/06/3d.log"),
                targets(answers.get(0)));
    }

    private static List<Object> file(String relative, long size) {
        return List.of("s3a://bucket/logs/" + relative, relative, size);
    }

    private static List<String> concat(List<String> argv, String... more) {
        List<String> all = new ArrayList<>(argv);
        all.addAll(List.of(more));
        return all;
    }

    private record Case(List<String> argv, List<List<Object>> files, String outputExtension) {
        Case(List<String> argv) {
            this(argv, null, null);
        }

        Case(List<String> argv, List<List<Object>> files) {
            this(argv, files, null);
        }
    }

    private static List<JsonNode> run(List<Case> cases) throws Exception {
        List<Object> json = cases.stream().map(c -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("argv", c.argv());
            if (c.files() != null) {
                node.put("files", c.files());
                node.put("extensions", Map.of("s3a://bucket/logs/2017-02-22/05/c.log.gz", ".gz"));
                node.put("outputExtension", c.outputExtension());
            }
            return (Object) node;
        }).toList();
        return runDriver(DRIVER, json);
    }

    private static List<JsonNode> runDriver(String program, Object cases) throws Exception {
        Path driver = folder.resolve("driver.py");
        Files.writeString(driver, program);
        Path input = folder.resolve("cases.json");
        Files.writeString(input, MAPPER.writeValueAsString(cases));
        BootstrapProcess.Result ended = BootstrapProcess.run(new ProcessBuilder("python3", "-I",
                driver.toString(), folder.resolve("s3-dist-cp.py").toString()).redirectInput(input.toFile()));
        assertEquals(0, ended.exit(), ended.output());
        List<JsonNode> answers = new ArrayList<>();
        MAPPER.readTree(ended.output()).forEach(answers::add);
        return answers;
    }

    private static JsonNode outputs(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static List<String> targets(JsonNode answer) {
        List<String> targets = new ArrayList<>();
        answer.path("outputs").forEach(output -> targets.add(output.get(0).asText()));
        return targets;
    }
}
