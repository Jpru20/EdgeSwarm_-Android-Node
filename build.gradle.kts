// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // We use "apply false" so the root project doesn't try to run these,
    // it just makes them available for the modules.
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}