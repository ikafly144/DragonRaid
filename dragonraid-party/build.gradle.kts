dependencies {
    api(project(":dragonraid-api"))
    compileOnly(libs.folia.api)
    compileOnly(libs.packetevents.spigot)
    testImplementation(libs.folia.api)
    testImplementation(libs.packetevents.spigot)
}
