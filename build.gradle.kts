plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.pixelagents"
version = "1.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.plugins.terminal")
        // instrumentationTools() - no longer needed in IntelliJ Platform Gradle Plugin 2.x
    }
    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        name = "Pixel Agents"
        version = "1.2.0"
        description = "Pixel art office where your Claude Code agents come to life as animated characters"
        ideaVersion {
            sinceBuild = "243"
        }
    }
}

tasks {
    val buildWebview by registering(Exec::class) {
        workingDir = file("webview-ui")
        commandLine("npm", "run", "build")
        // Vite outputs to ../dist/webview which we copy to resources
        doLast {
            copy {
                from(file("dist/webview"))
                into(file("src/main/resources/webview-dist"))
            }
        }
    }

    processResources {
        dependsOn(buildWebview)
    }
}
