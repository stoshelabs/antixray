import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("java")
}

group = "dev.stoshe.antixray"
val pluginVersion = (findProperty("version") as? String)
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
    ?: "1.1.0"
version = pluginVersion

val javaVersion = (findProperty("javaVersion") as? String) ?: "25"
val javaLanguageVersion = javaVersion.substringBefore('.').toInt()
val patchline = (findProperty("patchline") as? String) ?: "release"
val includesPack = ((findProperty("includesPack") as? String)?.toBoolean()) ?: false
val loadUserMods = ((findProperty("loadUserMods") as? String)?.toBoolean()) ?: false

// Locate the Hytale installation so we can compile against HytaleServer.jar.
// Order: -Phytale_home override -> OS default install -> bundled libs/HytaleServer.jar fallback.
val hytaleHome: String by extra {
    if (project.hasProperty("hytale_home")) {
        project.findProperty("hytale_home") as String
    } else {
        val os = DefaultNativePlatform.getCurrentOperatingSystem()
        when {
            os.isWindows -> "${System.getProperty("user.home")}/AppData/Roaming/Hytale"
            os.isMacOsX -> "${System.getProperty("user.home")}/Library/Application Support/Hytale"
            os.isLinux -> {
                val flatpakPath = "${System.getProperty("user.home")}/.var/app/com.hypixel.HytaleLauncher/data/Hytale"
                if (file(flatpakPath).exists()) flatpakPath
                else "${System.getProperty("user.home")}/.local/share/Hytale"
            }
            else -> ""
        }
    }
}

val installedServerJar = "$hytaleHome/install/$patchline/package/game/latest/Server/HytaleServer.jar"
val installedAssets = "$hytaleHome/install/$patchline/package/game/latest/Assets.zip"
val bundledServerJar = "libs/HytaleServer.jar"

// Prefer the installed jar; otherwise fall back to a jar dropped into libs/.
val hytaleServerJar = if (file(installedServerJar).exists()) installedServerJar else bundledServerJar
val hytaleAssets = installedAssets

if (!file(hytaleServerJar).exists()) {
    throw GradleException(
        "HytaleServer.jar not found. Looked at:\n" +
        "  $installedServerJar\n" +
        "  $bundledServerJar\n" +
        "Set -Phytale_home=/path/to/Hytale or drop HytaleServer.jar into libs/."
    )
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaLanguageVersion))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(javaLanguageVersion)
    options.compilerArgs.add("-Xlint:deprecation")
}

repositories {
    mavenCentral()
    flatDir { dirs("libs") }
}

dependencies {
    compileOnly(files(hytaleServerJar))
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("manifest.json") {
        expand("version" to project.version)
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("AntiXray")
    archiveVersion.set(project.version.toString())

    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }

    from("src/main/resources")
}

fun createServerArgs(): List<String> {
    val args = mutableListOf(
        "--allow-op",
        "--disable-sentry",
        "--assets=\"$hytaleAssets\""
    )
    val modsPaths = mutableListOf<String>()
    if (loadUserMods) modsPaths.add("$hytaleHome/UserData/Mods")
    if (includesPack) modsPaths.add(sourceSets.main.get().output.resourcesDir?.parentFile?.absolutePath ?: "")
    if (modsPaths.isNotEmpty()) args.add("--mods=\"${modsPaths.joinToString(",")}\"")
    return args
}

tasks.register<JavaExec>("runServer") {
    group = "hytale"
    description = "Runs a local Hytale server using files from the game install."
    dependsOn("classes")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaLanguageVersion))
    })
    mainClass.set("com.hypixel.hytale.Main")
    args = createServerArgs()
    classpath = files(
        hytaleServerJar,
        sourceSets.main.get().output.classesDirs,
        sourceSets.main.get().output.resourcesDir?.absolutePath
    )
    workingDir = file("run")
    standardInput = System.`in`
}
