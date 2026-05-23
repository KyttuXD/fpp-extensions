plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

group = "me.bill"
    version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":fpp-aichat"))
    implementation(project(":fpp-chat"))
    implementation(project(":fpp-command"))
    implementation(project(":fpp-groups"))
    implementation(project(":fpp-list"))
    implementation(project(":fpp-luckperms"))
    implementation(project(":fpp-nametag"))
    // implementation(project(":fpp-pathfinder")) // removed - functionality in base plugin
    implementation(project(":fpp-peaks"))
    implementation(project(":fpp-ping"))
    implementation(project(":fpp-skin"))
    // implementation(project(":fpp-swap")) // incompatible with current FPP API
    implementation(project(":fpp-waypoints"))
}

tasks.shadowJar {
    archiveBaseName.set("fpp-spoof")
    archiveVersion.set(project.version.toString())
    
    dependsOn(
        ":fpp-aichat:jar",
        ":fpp-chat:jar",
        ":fpp-command:jar",
        ":fpp-groups:jar",
        ":fpp-list:jar",
        ":fpp-luckperms:jar",
        ":fpp-nametag:jar",
        ":fpp-peaks:jar",
        ":fpp-ping:jar",
        ":fpp-skin:jar",
        ":fpp-waypoints:jar"
    )
}

tasks.register<Copy>("copySpoof") {
    from(tasks.shadowJar)
    into("../../fake-player-plugin/build/extensions")
}

tasks.build {
    finalizedBy("copySpoof")
}
