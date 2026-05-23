plugins {
    id("java")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

allprojects {
    group = "me.bill"
    version = "1.1.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java")

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
