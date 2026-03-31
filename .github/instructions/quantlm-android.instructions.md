---
description: "Use when editing this repository. Provides strong-preference conventions for Android Kotlin app code and project-wide change safety."
name: "QuantLM Repository Preferences"
applyTo: "**"
---

# QuantLM Repository Preferences

These guidelines are strong preferences for this workspace. If a task requires deviating, do so and explain the reason briefly in your response.

## Architecture Boundaries

- In `app/src/main/java/com/quantlm/yaser/**`, preserve clean architecture boundaries.
- Domain layer (`domain/**`) should stay platform-agnostic and depend only on Kotlin and domain models/interfaces.
- Data layer (`data/**`) should implement domain repository interfaces and handle persistence/inference/workers/services.
- Presentation layer (`presentation/**`) should own Compose UI and ViewModels; avoid moving business logic into Composables.
- Keep runtime app sources under `com.quantlm.yaser.*`; avoid adding sample-package runtime code (for example `org.example.*`).

## Hilt and Dependency Injection

- Prefer `@HiltViewModel` plus constructor injection for app ViewModels.
- Keep mutable dependencies internal and expose stable interfaces/types to callers.
- Prefer binding repository implementations in DI modules (for example `di/RepositoryModule.kt`) instead of service-locator patterns.

## State and Compose Patterns

- Prefer exposing immutable UI state from ViewModels (`StateFlow`/`Flow`) while keeping mutable flows private.
- Prefer collecting state in route/root Composables, then passing state and callbacks down.
- Prefer `hiltViewModel()` at screen entry points rather than deep leaf composables unless ownership requires it.
- Prefer stateless, reusable UI components; keep one-off UI state local with `remember`.

## Coroutines, Flow, and Data Access

- Prefer `suspend` for one-shot operations and `Flow` for streams/reactive updates.
- Avoid blocking calls (`runBlocking`, thread sleeps, or heavy work on main thread) in app runtime code.
- Keep Room access in DAOs/repositories and map entities to domain models in the data layer.

## Build and Verification Expectations

- For Kotlin/DI changes, prefer running `./gradlew.bat :app:compileDebugKotlin`.
- For dependency, native/CMake, or packaging changes, prefer `./gradlew.bat :app:assembleDebug`.
- If a change adds new source sets or packages, verify they are wired to the existing package structure and dependencies.

## Non-Android Areas

- For vendored or external reference directories (for example `app/src/main/cpp/llama-cpp/**` and `gallery-reference/**`), minimize edits and follow local conventions/docs in those directories.
- Keep changes focused and avoid large unrelated formatting rewrites across any file type.