plugins {
    `kotlin-dsl`
}

group = "com.wu.nearbycheckin.buildlogic"

dependencies {
    implementation(
        "org.jetbrains.kotlin:kotlin-gradle-plugin:" +
            libs.versions.kotlin.get()
    )
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = "nearbycheckin.kotlin.library"
            implementationClass =
                "com.wu.nearbycheckin.buildlogic.KotlinLibraryConventionPlugin"
        }
        register("testing") {
            id = "nearbycheckin.testing"
            implementationClass =
                "com.wu.nearbycheckin.buildlogic.TestingConventionPlugin"
        }
    }
}
