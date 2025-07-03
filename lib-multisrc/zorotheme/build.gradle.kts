plugins {
    id("lib-multisrc")
}

baseVersionCode = 6

dependencies {
    implementation(project(":lib:megacloud-extractor"))
    implementation(project(":lib:streamtape-extractor"))
}
