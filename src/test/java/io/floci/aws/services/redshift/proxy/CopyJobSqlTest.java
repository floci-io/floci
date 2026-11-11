package io.floci.aws.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

class CopyJobSqlTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/Load";
    private static final String COPY = "COPY public.target_table FROM 's3://demo-bucket/staging-folder' IAM_ROLE '"
            + ROLE + "'";

    private static String create(String suffix) {
        return CopyJobSql.rewrite(COPY + " " + suffix, false);
    }

    @Test
    void createRegistersTheJobWithTheCopyBucketPrefixAndRole() {
        String sql = create("JOB CREATE My_Job AUTO ON;");

        assertThat(sql, startsWith("CALL floci_internal.create_copy_job(E'my_job', E'" + ROLE + "', "));
        assertThat(sql, containsString("E'public.target_table', E'demo-bucket', E'staging-folder')"));
        assertThat(sql, containsString("', true, E'public.target_table'"));
    }

    @Test
    void createDefaultsToAutoOnAndKeepsAutoOff() {
        assertThat(create("JOB CREATE j"), containsString(", true, E'public.target_table'"));
        assertThat(create("JOB CREATE j AUTO OFF"), containsString(", false, E'public.target_table'"));
    }

    @Test
    void createKeepsAQuotedNameAsWrittenAndDoublesTheQuotesOfTheCopyText() {
        String sql = create("JOB CREATE \"Mixed\"");

        assertThat(sql, containsString("create_copy_job(E'Mixed', "));
        assertThat(sql, containsString("IAM_ROLE ''" + ROLE + "''"));
    }

    @Test
    void createIsRefusedInATransactionBlock() {
        assertThat(CopyJobSql.rewrite(COPY + " JOB CREATE j", true),
                containsString("reject_copy_job(E'25001'"));
    }

    @Test
    void createRefusesWhatAWorkingJobCannotCarry() {
        assertThat(create("MAXERROR 5 JOB CREATE j"), containsString("MAXERROR or IGNOREALLERRORS"));
        assertThat(create("IGNOREALLERRORS JOB CREATE j"), containsString("MAXERROR or IGNOREALLERRORS"));
        assertThat(CopyJobSql.rewrite("COPY t FROM 's3://b/p' IAM_ROLE '" + ROLE + "' MANIFEST JOB CREATE j", false),
                containsString("manifest"));
        assertThat(CopyJobSql.rewrite("COPY t FROM 's3://b/p' CREDENTIALS 'aws_access_key_id=A;"
                + "aws_secret_access_key=S' JOB CREATE j", false), containsString("IAM_ROLE"));
        assertThat(CopyJobSql.rewrite("COPY t FROM 's3://b/p' IAM_ROLE default JOB CREATE j", false),
                containsString("needs an IAM_ROLE"));
        assertThat(CopyJobSql.rewrite("COPY t FROM 's3://b/p' JOB CREATE j", false),
                containsString("needs an IAM_ROLE"));
    }

    @Test
    void createRefusesAHyphenInAQuotedName() {
        assertThat(create("JOB CREATE \"my-job\""), containsString("cannot contain a hyphen"));
    }

    @Test
    void anOptionNamedLikeAToleranceInAPathDoesNotRefuseTheJob() {
        String sql = CopyJobSql.rewrite("COPY t FROM 's3://b/maxerror/' IAM_ROLE '" + ROLE + "' JOB CREATE j", false);

        assertThat(sql, startsWith("CALL floci_internal.create_copy_job("));
    }

    @Test
    void listAndShowReadTheSystemView() {
        assertThat(CopyJobSql.rewrite("COPY JOB LIST;", false),
                equalTo("SELECT * FROM pg_catalog.sys_copy_job ORDER BY job_id"));
        assertThat(CopyJobSql.rewrite("copy job show My_Job", false),
                equalTo("SELECT * FROM pg_catalog.sys_copy_job WHERE job_name = E'my_job'"));
    }

    @Test
    void showEscapesApostrophesAndBackslashesInQuotedJobNames() {
        assertThat(CopyJobSql.rewrite("COPY JOB SHOW \"o'brien\\new\"", false),
                equalTo("SELECT * FROM pg_catalog.sys_copy_job WHERE job_name = E'o''brien\\\\new'"));
    }

    @Test
    void alterAndDropCallTheProcedures() {
        assertThat(CopyJobSql.rewrite("COPY JOB ALTER j AUTO OFF", false),
                equalTo("CALL floci_internal.alter_copy_job(E'j', false)"));
        assertThat(CopyJobSql.rewrite("COPY JOB DROP j", false),
                equalTo("CALL floci_internal.drop_copy_job(E'j')"));
        assertThat(CopyJobSql.rewrite("COPY JOB DROP j", true), containsString("reject_copy_job(E'25001'"));
    }

    @Test
    void runIsExecutedByTheS3Interceptor() {
        assertThat(CopyJobSql.rewrite("COPY JOB RUN j", false), equalTo("COPY JOB RUN j"));
    }

    @Test
    void statementsThatAreNotJobStatementsAreLeftAlone() {
        assertThat(CopyJobSql.matches(COPY), equalTo(false));
        assertThat(CopyJobSql.matches("SELECT 1"), equalTo(false));
        assertThat(CopyJobSql.matches("COPY JOB LIST"), equalTo(true));
        assertThat(CopyJobSql.matches(COPY + " JOB CREATE j"), equalTo(true));
        assertThat(CopyJobSql.rewrite(COPY, false), not(containsString("floci_internal")));
        assertThat(CopyJobSql.rewrite("SELECT 1", false), equalTo("SELECT 1"));
    }

    @Test
    void targetAndColumnNamesAreNotOptions() {
        for (String target : new String[]{"public.manifest", "public.\"maxerror\"", "t (credentials)"}) {
            assertThat(CopyJobSql.rewrite("COPY " + target + " FROM 's3://b/p' IAM_ROLE '" + ROLE
                    + "' JOB CREATE j", false), startsWith("CALL floci_internal.create_copy_job("));
        }
    }

    @Test
    void storesOptionsThatAreOnlyValidatedWhenTheJobRuns() {
        assertThat(create("FORMAT AS PARQUET JOB CREATE j"), startsWith("CALL floci_internal.create_copy_job("));
    }

    @Test
    void commentsAndEscapedIdentifiersAreSupported() {
        assertThat(CopyJobSql.rewrite("/* jobs */ COPY /* comment */ JOB SHOW \"a\"\"b\"; -- end", false),
                equalTo("SELECT * FROM pg_catalog.sys_copy_job WHERE job_name = E'a\"b'"));
        assertThat(CopyJobSql.rewrite(COPY + " /* MAXERROR */ JOB CREATE j -- end", false),
                startsWith("CALL floci_internal.create_copy_job("));
    }

    @Test
    void quotedJobCreateTextDoesNotTurnAnOrdinaryCopyIntoAJob() {
        String sql = "COPY t FROM 's3://b/JOB CREATE j'";
        assertThat(CopyJobSql.matches(sql), equalTo(false));
        assertThat(CopyJobSql.rewrite(sql, false), equalTo(sql));
    }

    @Test
    void rewritesEachStatementWithoutAbsorbingOtherStatementsIntoTheJob() {
        assertThat(CopyJobSql.rewrite("COPY JOB LIST; SELECT 1", false),
                equalTo("SELECT * FROM pg_catalog.sys_copy_job ORDER BY job_id; SELECT 1"));
        assertThat(CopyJobSql.rewrite(COPY + "; SELECT 1 JOB CREATE j", false),
                equalTo(COPY + "; SELECT 1 JOB CREATE j"));
        assertThat(CopyJobSql.rewrite("BEGIN; " + COPY + " JOB CREATE j; COMMIT", false),
                containsString("reject_copy_job(E'25001'"));
    }

    @Test
    void unterminatedQuotedNamesCannotReferToAnExistingJob() {
        for (String name : new String[]{"\"jobx", "\"jobx\"\""}) {
            assertThat(CopyJobSql.rewrite("COPY JOB DROP " + name, false),
                    containsString("reject_copy_job(E'42601'"));
            assertThat(create("JOB CREATE " + name), containsString("reject_copy_job(E'42601'"));
        }
    }

    @Test
    void unterminatedCommentsCannotExecuteAJobCommand() {
        assertThat(CopyJobSql.rewrite("COPY JOB DROP j /* unfinished", false),
                containsString("reject_copy_job(E'42601'"));
        assertThat(create("JOB CREATE j /* outer /* nested */"),
                containsString("reject_copy_job(E'42601'"));
    }

    @Test
    void invalidCopyTargetsAreRejectedBeforeRegisteringAJob() {
        for (String target : new String[]{"t bogus", "t (a b)", "t (a,)", "t."}) {
            assertThat(CopyJobSql.rewrite("COPY " + target + " FROM 's3://b/p' IAM_ROLE '" + ROLE
                    + "' JOB CREATE j", false), containsString("reject_copy_job("));
        }
    }

    @Test
    void jobNameLengthIsLimitedInUtf8Bytes() {
        assertThat(create("JOB CREATE " + "a".repeat(127)), startsWith("CALL floci_internal.create_copy_job("));
        assertThat(create("JOB CREATE " + "a".repeat(129)), containsString("reject_copy_job(E'42602'"));
        assertThat(create("JOB CREATE \"" + "é".repeat(64) + "\""), containsString("reject_copy_job(E'42602'"));
        assertThat(create("JOB CREATE \"" + "a".repeat(8192) + "\""), containsString("reject_copy_job(E'42602'"));
    }
}
