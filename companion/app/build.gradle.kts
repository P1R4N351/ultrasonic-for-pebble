plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "io.github.p1r4n351.ultrasonic.pebble"
  compileSdk = 36

  defaultConfig {
    applicationId = "io.github.p1r4n351.ultrasonic.pebble"
    minSdk = 31
    targetSdk = 36
    versionCode = 2
    versionName = "0.1.1"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    warningsAsErrors = false
    abortOnError = true
  }

  testOptions {
    unitTests.all {
      it.systemProperty(
        "watchappPackageJson",
        rootProject.file("../watchapp/package.json").absolutePath,
      )
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    allWarningsAsErrors.set(true)
  }
}

// PebbleKit 2's pure-JVM modules ship Java 21 bytecode. D8 handles that for the APK, but
// host-side unit tests load those classes directly, so they need a 21 runtime.
tasks.withType<Test>().configureEach {
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

dependencies {
  implementation(libs.media3.session)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.guava)
  implementation(libs.pebblekit2.client)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
}
