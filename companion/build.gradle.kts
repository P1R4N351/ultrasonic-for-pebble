// AGP 9 compiles Kotlin itself; the serialization plugin is declared here only to pin the
// Kotlin Gradle Plugin to 2.3.21, matching the 2.3.x metadata PebbleKit 2 1.2.0 ships.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}
