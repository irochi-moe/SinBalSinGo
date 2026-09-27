plugins {
    java
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()
description = providers.gradleProperty("description").get()
val jdaVersion = providers.gradleProperty("jdaVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    exclusiveContent {
        forRepository { maven("https://jitpack.io") }
        filter { includeModule("com.github.irochi-moe", "GuRoYeokSiBal") }
    }
}

dependencies {
    // The server downloads JDA through plugin.yml's libraries, so it is not bundled; voice and encryption are unused.
    for (configuration in listOf("compileOnly", "testImplementation")) {
        add(configuration, "net.dv8tion:JDA:$jdaVersion") {
            exclude(module = "opus-java")
            exclude(module = "tink")
        }
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.11.0")
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("com.github.irochi-moe:GuRoYeokSiBal:v1.2") { isTransitive = false }
}

tasks {
    processResources {
        val projectVersion = version
        val projectDescription = project.description
        val projectJdaVersion = jdaVersion
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(mapOf("version" to projectVersion, "description" to projectDescription, "jdaVersion" to projectJdaVersion))
        }
    }

    test { useJUnitPlatform() }

    jar {
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
