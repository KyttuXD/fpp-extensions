plugins {
    id("java")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

group = "me.bill"
    version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files("../../fake-player-plugin/build/libs/fake-player-plugin-1.6.6.12.1-all.jar"))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.jar {
    archiveBaseName.set("fpp-list")
    archiveVersion.set("")
}

tasks.register<Copy>("copyExtension") {
    from(tasks.jar)
    into("../../fake-player-plugin/build/extensions")
}

tasks.build {
    finalizedBy("copyExtension")
}
