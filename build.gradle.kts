allprojects {
    repositories {
        mavenCentral()
        google()
        maven(url = "https://jitpack.io")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_1_8.toString()
        }
    }
}
