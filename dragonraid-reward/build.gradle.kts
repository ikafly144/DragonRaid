dependencies {
    api(project(":dragonraid-api"))
    compileOnly(libs.folia.api)
    testImplementation(libs.folia.api)
}
