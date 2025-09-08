plugins {
    kotlin("multiplatform") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
}

group = "br.com.vrsoftware"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {

    linuxX64("linux") {
        binaries {
            executable {
                entryPoint = "main"
                baseName = "vragentepdv-linux"
            }
        }
    }

    mingwX64("windows") {
        binaries {
            executable {
                entryPoint = "main"
                baseName = "vragentepdv-windows"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val linuxMain by getting {
            dependencies {
            }
        }

        val windowsMain by getting {
            dependencies {
            }
        }
    }
}

tasks.register("buildAll") {
    dependsOn("linuxX64Binaries", "mingwX64Binaries")
    description = "Gerando executáveis para Linux e Windows"
    group = "build"
}

tasks.register<Zip>("packageRelease") {
    dependsOn("buildAll")
    archiveFileName.set("salt-installer-${version}.zip")
    destinationDirectory.set(file("$buildDir/releases"))

    from("build/bin/linux/releaseExecutable") {
        into("linux")
    }
    from("build/bin/windows/releaseExecutable") {
        into("windows")
    }
    from("README.md")
    from("LICENSE")
}