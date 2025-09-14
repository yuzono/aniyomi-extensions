plugins {
    id("lib-multisrc")
}

baseVersionCode = 20

dependencies {
    api(project(":lib:dopeflix-extractor"))
}
