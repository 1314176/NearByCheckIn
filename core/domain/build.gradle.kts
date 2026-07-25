plugins {
    id("nearbycheckin.kotlin.library")
    id("nearbycheckin.testing")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}
