dependencies {
    api(project(":dragonraid-api"))
    compileOnly(libs.folia.api)
    compileOnly(libs.packetevents.spigot)
    compileOnly(libs.bettermodel.bukkit.api)
    testImplementation(libs.folia.api)
    testImplementation(libs.packetevents.spigot)
    testImplementation(libs.bettermodel.bukkit.api)
}
