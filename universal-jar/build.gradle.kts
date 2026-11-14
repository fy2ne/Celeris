plugins {
    java
    `maven-publish`
}

group = "dev.fy2ne"
version = "0.1.0-beta"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
}

dependencies {
    compileOnly("io.netty:netty-all:4.1.115.Final")
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0")
    compileOnly("net.fabricmc:fabric-loader:0.16.10")
    compileOnly("net.neoforged.fancymodloader:loader:4.0.34")

    testImplementation("io.netty:netty-all:4.1.115.Final")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    val os = System.getProperty("os.name").lowercase()
    val libName = when {
        os.contains("win") -> "celeris_core.dll"
        os.contains("mac") || os.contains("darwin") -> "libceleris_core.dylib"
        else -> "libceleris_core.so"
    }
    val nativeFile = rootProject.file("target/release/$libName")
    if (nativeFile.exists()) {
        systemProperty("celeris.native.path", nativeFile.absolutePath)
    }
    if (JavaVersion.current().majorVersion.toInt() >= 24) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

tasks.jar {
    archiveBaseName.set("Celeris")
    archiveVersion.set(project.version.toString())
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "dev.fy2ne"
            artifactId = "celeris"
            version = project.version.toString()
            pom {
                name.set("Celeris")
                description.set("Zero-client-mod Rust + Netty priority packet scheduler and TCP pacer for Minecraft servers")
                url.set("https://github.com/fy2ne/Celeris")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/fy2ne/Celeris")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
