# Agent Instructions

This project is an Android Studio Kotlin application. Follow these expectations for any changes within this directory.

## Kotlin & Android Conventions
- Follow the official Kotlin coding conventions with four-space indentation and `UpperCamelCase` class names.
- Prefer `val` over `var` whenever possible and write concise, single-responsibility functions.
- Keep Android resources tidy: strings in `res/values/strings.xml`, colours in `res/values/colors.xml`, and dimensions in `res/values/dimens.xml`.
- Use descriptive view IDs and avoid hard-coded text or colours in layouts.
- When defining theming for screens, consume the existing theme definitions in `one.globalconnect.paymentapp.ui.theme.Color.kt`, `one.globalconnect.paymentapp.ui.theme.Theme.kt`, and `one.globalconnect.paymentapp.ui.theme.Style.kt`. Each build variant has its own implementation, so ensure any new UI elements reference these theme-managed values instead of introducing variant-specific overrides.

## Gradle & Build Scripts
- Use idiomatic Groovy DSL in `build.gradle` files and group dependencies by configuration with alphabetical ordering.
- Reuse existing version constants or Gradle properties where available to avoid duplication or conflicts.

## Testing & Verification
- Run `./gradlew lint` and `./gradlew test` when feasible before submitting changes.
- Install debug variants on development devices unless release behavior is explicitly being validated.
- Note any skipped checks, emulator limitations, or manual validation steps in the PR description.

## PR Expectations
- Summaries should list affected modules/features and call out user-facing changes or significant refactors.
