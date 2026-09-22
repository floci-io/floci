# SDD ledger - plan: docs/superpowers/plans/2026-09-22-redshift-spectrum.md

Execution mode: inline in D:\floci, no git worktree.

Pre-flight: shared interfaces found and checked.
- Task 1 produces `SpectrumExternalSchema`, `SpectrumExternalTable`, `SpectrumColumn`, and `SpectrumCatalog`; Tasks 2, 3, 4, 5, and 6 consume catalog metadata. The plan's record shapes are consistent with the design spec.
- Task 2 produces typed DDL statements; Task 6 consumes them. The parser's `Optional.empty()` for non-Spectrum SQL and exception behavior are consistent.
- Task 3 produces `SpectrumRow` and `SpectrumS3Reader`; Task 5 consumes both. The reader remains S3-facing and does not own PostgreSQL wire state.
- Task 4 produces `SpectrumQuery` and `SpectrumQueryRewriter`; Task 6 consumes both. The rewriter receives only a generated materialization identifier.
- Task 5 produces `Materialization`; Task 6 consumes it while preserving backend ownership. No conflicting interface found.

Ruling: the standard Bash workspace/task scripts cannot run in this Windows environment because Git Bash fails `CreateFileMapping` with Win32 error 5. Use the same ledger manually and keep the exact plan/test sequence.

Task 1: Ruling: use `mvn -B -Dtest=SpectrumCatalogTest test` instead of the plan's `surefire:test` command because the latter does not compile test classes in this workspace and reports no matching tests. Cost if wrong: focused verification would be incomplete.
Task 1: Ruling: fix `SpectrumCatalog.table` to pass the account id required by `AccountAwareStorageBackend.getForAccount`, and wrap duplicate-column construction inside `assertThrows`; both were pre-existing defects in the uncommitted Task 1 files. Cost if wrong: catalog lookup would not compile or the test would not assert the intended validation.
Task 1: complete (tests: `mvn -B -Dtest=SpectrumCatalogTest test` -> 5/5 passed)
Task 2: Ruling: treat `CREATE EXTERNAL DATABASE IF NOT EXISTS` as the optional suffix after a supported external-schema statement, because the plan calls it a suffix and standalone external-database SQL is outside the Phase 1 grammar. Cost if wrong: clients using a standalone form would need a later parser extension.
Task 2: complete (tests: `mvn -B -Dtest=SpectrumSqlParserTest test` -> 5/5 passed)
