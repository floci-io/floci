# Rebuilding athena-glue-ddl-permissions.json by hand

Use this only when `tools/aws/fetch_athena_glue_ddl_permissions.py` can no longer read the page, because its layout
changed. Fix the script when you can; this is for when you cannot, or to check what the script wrote. Give the
following to whoever (or whatever) rebuilds the file, together with the page.

## The prompt

Read the AWS documentation page
https://docs.aws.amazon.com/athena/latest/ug/fine-grained-access-to-glue-resources.html
("Configure access to databases and tables in the AWS Glue Data Catalog"), and in it the table under "Examples of
database and table-level permissions", whose first column is headed "DDL statement".

Rewrite `tools/aws/sources/athena-glue-ddl-permissions.json` so it holds exactly what that table says, in this shape:

```json
{
  "_source": {"page": "<the page's URL>", "fetcher": "tools/aws/fetch_athena_glue_ddl_permissions.py",
              "prompt": "tools/aws/sources/athena-glue-ddl-permissions.prompt.md", "retrieved": "<YYYY-MM-DD>",
              "note": "<keep the note the file has>"},
  "examples": {"<the example database's name>": "database", "<the example table's name>": "table"},
  "statements": {
    "<the statement as the first column writes it>": {
      "text": "<the second column's sentences before the policy, whitespace collapsed to single spaces>",
      "policy": [<each statement of the example policy, as JSON objects, in the page's order>]
    }
  }
}
```

Rules:

1. One entry in `statements` for each row of the table, in the page's order, named as the first column names it
   (`CREATE TABLE`, `MSCK REPAIR TABLE`).
2. `policy` is the row's example policy copied as it stands: every statement, with its `Sid` when it has one, its
   `Effect`, and its `Action` and `Resource` lists in the page's order. The ARNs keep the page's example Region,
   account, database and table (`arn:aws:glue:us-east-1:123456789012:table/example_db/test`) and its wildcards.
3. `examples` comes from the sentence above the table that says which names to replace ("replace the example_db
   database and test table with your own database and table names"): each example name, mapped to `database` or
   `table` as that sentence says.
4. Add nothing the page does not say. Do not infer an action a statement "probably" needs, do not drop one that looks
   unnecessary, do not merge or split statements, and do not add a row for a statement the table has no row for
   (`SELECT`, `INSERT INTO`, `ALTER TABLE ADD PARTITION` have none). Keep each row's sentence as written, since the
   generator reads conditions from it ("If your table does not have partitions, do not include partition actions").
5. If the page no longer has such a table, or a row's policy is not a list of `Allow` statements with `Action` and
   `Resource` lists, stop and report it rather than guessing.

When the file is written, run:

```
python3 tools/aws/fetch_athena_glue_ddl_permissions.py --verify   # once the script reads the page again
make iam-request-resources-sync                                     # regenerate the IAM table from it
make iam-request-resources-check iam-request-resources-test
```

`make iam-request-resources-sync` refuses an action the Service Reference does not list for Glue and an ARN that is no
Glue resource type's format, so a mistyped row fails there rather than reaching the IAM table.
