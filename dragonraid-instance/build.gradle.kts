dependencies {
    api(project(":dragonraid-api"))
    compileOnly(libs.folia.api)
    compileOnly(libs.thenextlvl.worlds)
    testImplementation(libs.folia.api)
    testImplementation(libs.thenextlvl.worlds)
}
