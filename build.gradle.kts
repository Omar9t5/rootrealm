// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") apply false version "8.11.0"
    id("com.android.library") apply false version "8.11.0"
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}