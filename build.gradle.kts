import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "nl.redlabs.epsonreset"

// Set by the release workflow from the v* tag. Absent everywhere else, which is the difference
// between a release and a working copy — see the version resource below.
val releaseVersion: String? =
    System.getenv("APP_VERSION")?.removePrefix("v")?.takeIf { it.isNotBlank() }

// What the installers are stamped with. Not the same string as the tag: the macOS .dmg carries it
// as CFBundleVersion, and Compose rejects anything that isn't MAJOR[.MINOR][.PATCH] with MAJOR > 0
// — so "1.2.0-rc1" and "0.9.0" both fail the build at configuration time. Strip the pre-release
// and build metadata, and fall back for an untagged build, where jpackage would reject "dev".
val installerVersion: String =
    releaseVersion
        ?.substringBefore('+')
        ?.substringBefore('-')
        ?.takeIf { core -> core.matches(Regex("""[1-9]\d*(\.\d+){0,2}""")) }
        ?: "1.0.0"

// The full tag, which is what shows up in the jar name. The version resource below keeps it too —
// only the installers need the trimmed form.
version = releaseVersion ?: installerVersion

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of("21"))
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // USB transport. usb4java ships no darwin-aarch64 native, so libusb is bound directly through
    // JNA instead; the same 5.19 that already carries an arm64 macOS dylib elsewhere in the stack.
    implementation("net.java.dev.jna:jna:5.19.0")

    testImplementation(kotlin("test"))
    // Lets the view-model tests drive the coroutines it launches to completion deterministically,
    // instead of sleeping and hoping. Pinned to the version Compose already puts on the classpath.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Formatting. The rules themselves live in .editorconfig so the IDE and the command line read the
// same file; this only pins the engine version and decides what a violation does.
ktlint {
    version.set(providers.gradleProperty("ktlint.version"))
    // The generated version resource is a build output, not source.
    filter { exclude { it.file.path.contains("${File.separator}build${File.separator}") } }
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

// Git hooks are versioned in .githooks/ rather than written into .git/hooks, so a change to them
// arrives with a pull instead of having to be re-installed. All this task does is point git at
// that directory — it is idempotent, and `git config --unset core.hooksPath` undoes it.
val installGitHooks = tasks.register("installGitHooks") {
    group = "verification"
    description = "Point git at the versioned hooks in .githooks/"

    val hooksDir = layout.projectDirectory.dir(".githooks").asFile
    val gitDir = layout.projectDirectory.dir(".git").asFile
    val workingDir = layout.projectDirectory.asFile

    doLast {
        if (!gitDir.exists()) {
            logger.lifecycle("Not a git checkout — skipping hook installation.")
            return@doLast
        }
        // Executable bits do not survive every checkout (Windows, zip exports), so restore them
        // rather than letting git fail with "hook not executable" at commit time.
        hooksDir.listFiles()?.forEach { it.setExecutable(true, false) }

        fun git(vararg args: String): Pair<Int, String> {
            val process =
                ProcessBuilder("git", *args)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            return process.waitFor() to output
        }

        val (_, current) = git("config", "--get", "core.hooksPath")
        if (current == ".githooks") {
            logger.lifecycle("Git hooks already installed (core.hooksPath=.githooks).")
            return@doLast
        }

        val (status, output) = git("config", "core.hooksPath", ".githooks")
        check(status == 0) { "git config core.hooksPath failed ($status): $output" }
        logger.lifecycle("Git hooks installed: core.hooksPath=.githooks")
    }
}

// A fresh clone gets the hooks from the first build, without anyone having to read the README to
// find out they exist. Not on a runner, though: sync-printer-data commits and pushes as the bot,
// and hooks installed there would run ktlint against its commit and the whole suite against its
// push. `./gradlew installGitHooks` still works anywhere, for whoever actually wants it.
if (System.getenv("CI").isNullOrBlank()) {
    tasks.named("build") { dependsOn(installGitHooks) }
}

// The running app has to know its own version to tell whether a GitHub release is newer than it.
// jpackage's --app-version reaches the installer but not the classpath, so the value is written
// into a resource here instead. An untagged build gets "dev", which UpdateCheck refuses to compare
// — a working copy is never told to upgrade to the release it is ahead of.
val generateVersionResource = tasks.register("generateVersionResource") {
    val outputDir = layout.buildDirectory.dir("generated/version")
    val version = releaseVersion ?: "dev"

    inputs.property("version", version)
    outputs.dir(outputDir)

    doLast {
        outputDir.get().file("app-version.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=$version\n")
        }
    }
}

sourceSets["main"].resources.srcDir(generateVersionResource)

// Headless self-check — database, libusb, connected devices, and a dry run. Everything a bug
// report needs, e.g. `./gradlew diagnose --args="XP-245"`.
tasks.register<JavaExec>("diagnose") {
    group = "verification"
    description = "Print environment, database, USB scan, and a dry run for one model"
    mainClass.set("nl.redlabs.epsonreset.Diagnostics")
    classpath = sourceSets["main"].runtimeClasspath
}

// Recovery: list saved pre-reset backups, preview one, or write it back. Previews by default —
// `--args="<file> --live"` is what actually touches the EEPROM.
tasks.register<JavaExec>("restore") {
    group = "verification"
    description = "List or replay an EEPROM backup taken before a reset"
    mainClass.set("nl.redlabs.epsonreset.RestoreTool")
    classpath = sourceSets["main"].runtimeClasspath
}

// Hardware experiment: dump the D4 handshake and try several read-command shapes to find which
// one the firmware answers. Read-only; see debug/ReadProbe.kt.
tasks.register<JavaExec>("readProbe") {
    group = "debug"
    description = "Dump raw D4 exchanges while probing EEPROM read framing"
    mainClass.set("nl.redlabs.epsonreset.debug.ReadProbe")
    classpath = sourceSets["main"].runtimeClasspath
}

// Hardware experiment: the network path, one stage at a time. Prints every byte before sending it
// and stops at the first thing that doesn't answer. Read-only; see debug/NetworkProbe.kt.
tasks.register<JavaExec>("netProbe") {
    group = "debug"
    description = "Probe a network printer in stages: connect, status, then one EEPROM read"
    mainClass.set("nl.redlabs.epsonreset.debug.NetworkProbe")
    classpath = sourceSets["main"].runtimeClasspath
}

// Hardware experiment: ask the printer for its own status (ink levels, maintenance/waste state)
// instead of computing a percentage against a maximum nobody publishes. Read-only.
tasks.register<JavaExec>("statusProbe") {
    group = "debug"
    description = "Query the printer's ESC/P remote status for waste-pad and ink fields"
    mainClass.set("nl.redlabs.epsonreset.debug.StatusProbe")
    classpath = sourceSets["main"].runtimeClasspath
}

compose.desktop {
    application {
        mainClass = "nl.redlabs.epsonreset.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // jlink builds the bundled runtime from exactly the modules named here — nothing is
            // inferred. JNA reaches for sun.misc.Unsafe, so without jdk.unsupported the installers
            // ship a runtime whose USB layer dies on first call while `./gradlew run` (full JDK)
            // works fine. `./gradlew suggestRuntimeModules` re-derives this list.
            modules("java.instrument", "jdk.unsupported")

            // Filesystem-level name: the macOS .app, the Linux binary and the Debian package all
            // derive from it, and dpkg rejects a package name containing a space. The user-facing
            // title stays "Epson Reset" — Main.kt's window title, the Windows installer's
            // AppName, the Linux menu entry.
            packageName = "EpsonReset"
            packageVersion = installerVersion
            description = "Reset Epson waste ink pad counters"
            vendor = "redlabs"

            linux {
                appCategory = "Utility"
                menuGroup = "Epson Reset"
                shortcut = true
            }

            macOS {
                bundleID = "nl.redlabs.epsonreset"
                appCategory = "public.app-category.utilities"
                dockName = "Epson Reset"

                // Unsigned unless SIGN_APP=true and a certificate is in the keychain; CI sets that
                // only when the signing secrets exist, so a fork still gets a (unsigned) .dmg.
                signing {
                    sign.set(System.getenv("SIGN_APP")?.toBoolean() ?: false)
                    identity.set(System.getenv("IDENTITY") ?: "")
                }

                notarization {
                    appleID.set(System.getenv("APPLE_ID") ?: "")
                    password.set(System.getenv("NOTARIZATION_PASSWORD") ?: "")
                    teamID.set(System.getenv("TEAM_ID") ?: "")
                }
            }
        }
    }
}
