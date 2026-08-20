# Agent Instructions

This project is an Android Studio Kotlin application. Follow these guidelines when editing files under this directory.

## Kotlin & Android Conventions
- Follow the official Kotlin coding conventions with four-space indentation and `UpperCamelCase` class names.
- Prefer `val` over `var` whenever possible and keep functions focused on a single responsibility.
- Keep Android resources organised: strings in `res/values/strings.xml`, colours in `res/values/colors.xml`, and dimensions in `res/values/dimens.xml`.
- When modifying UI layouts, ensure view IDs are descriptive and avoid hard-coded text.

## Gradle & Build Scripts
- Use idiomatic Kotlin DSL in `build.gradle.kts` files and keep dependency declarations alphabetised within each block.
- Reuse existing version constants when available and avoid introducing conflicting versions.

## Testing & Verification
- Run `./gradlew lint` for static analysis and `./gradlew test` for unit tests when practical.
- Document any skipped checks or known limitations in the PR message.

## PR Expectations
- Summaries should mention affected modules and highlight user-visible changes or notable refactors.
