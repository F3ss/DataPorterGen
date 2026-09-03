# DataPorterGen development guide

This file applies to the whole repository. Read it before changing code, tests, configuration, Docker assets, or documentation.

## Project intent

DataPorterGen is a Java 21 command-line application that copies one MongoDB database to another while preserving raw BSON, collection metadata, indexes, and views. Correctness, BSON fidelity, bounded resource use, safe failure behavior, and secret-free diagnostics take priority over convenience.

Use `README.md` as the user-facing contract, and keep it aligned with behavior visible to operators.

## Architecture boundaries

- Organize migration and generation as vertical slices under `com.dataporter.migration` and `com.dataporter.generation`; keep only genuinely cross-feature contracts under `com.dataporter.shared`.
- Keep every feature's `domain` independent of Spring, MongoDB Driver, Jackson, logging, and filesystem APIs.
- Keep orchestration, retry, ordering, and bounded concurrency in each feature's `application` package.
- Define infrastructure dependencies in each feature's `ports/out`; implement them in `adapters`.
- Keep Spring wiring and property binding in `config` and `bootstrap`.
- Do not expose `MongoClient`, `Document`, `Bson`, or driver-specific types outside the MongoDB adapter.
- Preserve `shared.bson.BsonPayload` as the BSON-specific raw-byte document boundary. Never introduce JSON as an intermediate representation for copied documents.
- Do not require future database adapters to use `BsonPayload`; add database-specific payload and metadata models when their concrete requirements are known instead of introducing a speculative universal SPI.
- Preserve the migration order: validate, inspect source, select plan, validate target, prepare target, create collections, copy data, create indexes, create views, verify, report.

## Behavioral invariants

- `include-collections` and `exclude-collections` are mutually exclusive exact-name filters.
- When both filters are empty, migrate all user collections. When one is populated, apply only that filter. When both are populated, fail before connecting to or modifying the target.
- Unknown selected collection names fail after source inspection and before target access. System collections and view names are not valid collection selections.
- Keep view dependency selection and ordering deterministic. Do not infer dependencies from `$lookup` or `$unionWith` pipelines unless the documented contract changes.
- Never recreate the automatic `_id_` index or copy server-generated identifiers such as collection UUIDs.
- Never manually replay an ambiguous document batch. Retry only operations classified as safe and transient.
- `FAIL_IF_EXISTS` must not overwrite user objects. `DROP_AND_RECREATE` is the only strategy allowed to drop the target database. `MERGE` never drops target objects; it requires compatible selected metadata and resolves exact-`_id` conflicts by overwriting the target document with the source document (source wins). The removed `FAIL`/`KEEP_TARGET`/`REPLACE_TARGET` document policy setting must not return.
- Verification must remain bounded and streaming. `METADATA_AND_COUNTS` must not claim document identity; `FULL` compares raw BSON.
- Do not log document contents, credentials, tokens, or sensitive URI query values. Sanitize errors before logging or persisting reports.

## Change workflow

1. Inspect the relevant production code, tests, and `README.md` before editing.
2. Make the smallest change that preserves the boundaries and invariants above.
3. Add or update focused tests for the changed behavior once the implementation is in place; run tests after implementation, not test-first.
4. Refactor only with the focused tests green.
5. Update `README.md`, configuration examples, and Docker fixtures when their documented contract changes.
6. Review the diff for accidental secrets, generated output, unrelated formatting, and user-owned changes.

Prefer immutable domain values, explicit errors, deterministic ordering, try-with-resources, bounded queues/batches, and constructor injection. Avoid speculative abstractions and dependencies that do not serve a current requirement.

## Verification

Run the relevant tests once the implementation is complete, then use these gates before handing off a code change:

```bash
./gradlew test
./gradlew integrationTest
./gradlew clean test integrationTest build
```

Unit tests must not require Spring, Docker, or MongoDB. Use Testcontainers for behavior that depends on real MongoDB semantics. If Docker is unavailable, report that integration tests were skipped; do not describe them as passed. Documentation-only changes may use targeted validation instead of the full Gradle suite.

For Compose changes, run when Docker is available:

```bash
docker compose config
docker compose up --build --abort-on-container-exit --exit-code-from migration-verifier
```

Do not remove Compose volumes unless the user explicitly requests a full reset.

## Model usage

- Use strong models for planning and for writing or changing code, including diagnosing test failures.
- Cheaper models may run the test suites and report results.
- When tests fail, escalate the failure analysis to a stronger model instead of iterating on cheap fixes.

## Git and handoff

- Preserve unrelated working-tree changes and never rewrite history without explicit approval.
- Do not commit generated build output, reports, IDE state, credentials, or local prompt files.
- Use concise imperative commit subjects.
- Before a requested push, state the branch, remote, and commits being published; verify the final `git status` afterward.
- In the handoff, summarize behavior changed, validation run, skipped checks, and remaining risks.
