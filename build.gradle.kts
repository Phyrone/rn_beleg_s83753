import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.7.20"
    id("com.github.johnrengelman.shadow") version "7.1.2"

}

group = "me.samuel"
//version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {


    implementation("org.fusesource.jansi:jansi:2.4.0")
    implementation("info.picocli:picocli:4.7.0")
    implementation("io.ktor:ktor-network:2.1.3")
    implementation("com.google.guava:guava:31.1-jre")
    //implementation("me.tongfei:progressbar:0.9.5")
    implementation("com.google.flogger:flogger:0.7.4")
    implementation("com.google.flogger:flogger-slf4j-backend:0.7.4")
    implementation("ch.qos.logback:logback-classic:1.4.5")

//    implementation("com.google.flogger:flogger-system-backend:0.7.4")

    testImplementation(kotlin("test"))
}
tasks {


    test {
        useJUnitPlatform()
    }

    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }
    jar {
        enabled = false
    }
    shadowJar {
        enabled = true
        archiveClassifier.set("")
        minimize {
            exclude(dependency("com.google.flogger:flogger-slf4j-backend"))
            exclude(dependency("ch.qos.logback:logback-classic"))
        }


    }
    val extractTask = create<Copy>("extract-to-bin") {
        dependsOn(shadowJar.get())
        group = "build"
        from(zipTree(shadowJar.get().outputs.files.singleFile))
        into(projectDir.resolve("bin"))
    }
    clean {
        this.delete(project.projectDir.resolve("bin"))
    }

    create<Tar>("create-final-archive") {
        dependsOn(clean.get(), extractTask)
        group = "build"
        destinationDirectory.set(buildDir.resolve("archive"))

        archiveBaseName.set("s83753")

        compression = Compression.GZIP

        into("/s83753/") {
            from(projectDir)
            exclude("build")
        }
    }
}

application {
    mainClass.set("me.samuel.beleg_htw.rn.Main")
}