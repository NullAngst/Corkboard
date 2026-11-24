// The one place a version number is written down. Dependabot bumps it.
// AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no separate Kotlin plugin to version.
plugins {
    id("com.android.application") version "9.5.1" apply false
}
