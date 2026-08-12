# MiMiTrends — Codex Development Guidelines

## Critical rules

1. Never commit, push, create a branch, or open a pull request without an explicit user request.
2. Never add AI attribution, signatures, or generated-by comments to source files.
3. Always include a short English proposed commit message after completed code changes.
4. Preserve user changes and unrelated dirty-worktree edits.
5. Use English for code, comments, logs, UI identifiers, and documentation added to the repository.

## Code quality

- No source file or top-level class may exceed 400 lines. Split by responsibility before crossing the limit.
- Prefer cohesive services and focused UI components over controller growth.
- Keep parsing, persistence, scheduling, presentation, and JavaFX layout in separate classes.
- Avoid artificial line compression, multiple unrelated statements per line, and refactors made only to satisfy a metric.
- Extract repeated behavior after it has a stable responsibility; do not create abstractions for incidental similarity.
- Use explicit Kotlin nullability at Java and JDBC boundaries.
- Add `@file:Suppress("SqlNoDataSourceInspection")` to Kotlin JDBC files containing inline SQL so the IDE does not report unconfigured-data-source warnings.
- Keep public APIs minimal. Prefer `private`, then `internal`, unless cross-module access is required.
- Use deterministic, bounded algorithms for UI work. Large collections must be sampled or processed away from the JavaFX thread.
- UI updates must execute on the JavaFX Application Thread; network, file, and database work must not block it.
- Avoid resize/layout feedback loops. Debounce geometry-driven recalculation and use a stability epsilon.

## Structure and naming

- One primary responsibility per file.
- Name classes after the behavior they own, such as `ScalableCsvImporter`, `BrokerTransactionStore`, or `TableColumnAutoFitter`.
- Keep model classes free of JavaFX and persistence dependencies.
- Keep database migrations transactional and append-only.
- Keep provider-specific formats and APIs behind provider-specific adapters.

## Logging and errors

- Use SLF4J and the existing `LogTag` categories.
- Logs must identify the component, operation, and relevant safe identifiers.
- Never log credentials, authorization tokens, cookies, or full sensitive account data.
- User-visible failures should be concise; detailed stack traces belong in the diagnostic log/dialog.

## UI and styling

- Reuse the project fonts and CSS classes; avoid large inline style strings.
- Preserve manual user resizing and preferences unless the feature explicitly requires recalculation.
- Measure rendered content for table auto-fit, clamp widths, bound sampling, and debounce resize handling.
- Validate layout changes against the actual JavaFX rendering, not CSS values alone.
- Liquid Glass styling should use layered translucent fills, a clear highlight edge, restrained shadow, and readable text contrast.

## Tests and verification

- Add or update tests for parsing, persistence, scheduling, market-time conversion, and deduplication changes.
- Run `./gradlew test` after code changes.
- Run `git diff --check` before reporting completion.
- A successful compilation alone is insufficient when relevant unit tests can be added.

## Git workflow

- Do not stage or commit unless explicitly requested.
- Proposed commit messages must be short, lowercase English and describe the completed change.
- Do not mix unrelated cleanup into a feature commit.
