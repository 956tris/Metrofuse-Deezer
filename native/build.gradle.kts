import java.util.Properties
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val hostApiJar = providers.gradleProperty("metrofuseApiJar").map { rootProject.file(it) }
val hostSdkJar = providers.gradleProperty("metrofuseSdkJar").map { rootProject.file(it) }
val hostLibraries =
    listOf(
        libs.runtime,
        libs.foundation,
        libs.material3,
        libs.kotlinx.coroutines.core,
        libs.kotlinx.serialization.json,
        libs.ktor.client.core
    )

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
    jvmToolchain(25)
    sourceSets {
        getByName("desktopMain").dependencies {
            compileOnly(files(hostApiJar, hostSdkJar))
            hostLibraries.forEach { compileOnly(it) }
        }
        getByName("desktopTest").dependencies {
            implementation(files(hostApiJar, hostSdkJar))
            hostLibraries.forEach { implementation(it) }
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
        }
    }
}

val compilation = kotlin.targets["desktop"].compilations["main"]
val manifestSource = layout.projectDirectory.file("manifest.json")
val manifestFile = layout.buildDirectory.file("plugin-manifest/manifest.json")
val generatePluginManifest = tasks.register("generatePluginManifest") {
    val composeAbi = "kotlin-${libs.versions.kotlin.get()}_compose-${libs.versions.compose.get()}_api-1.1.0"
    inputs.file(manifestSource)
    inputs.property("composeAbi", composeAbi)
    outputs.file(manifestFile)
    doLast {
        val output = manifestFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            manifestSource.asFile.readText().replaceFirst(
                "{",
                "{\n  \"composeAbi\": \"$composeAbi\","
            )
        )
    }
}

tasks.register<Jar>("packagePluginJar") {
    dependsOn("desktopMainClasses", generatePluginManifest)
    archiveFileName.set("deezer.jar")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/plugins"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(compilation.output.allOutputs)
    from(manifestFile)
    from(rootProject.file("LICENSE"))
    from(layout.projectDirectory.file("SOURCE"))
    from(layout.projectDirectory.dir("assets")) { into("assets") }
}

val preparedAndroid = layout.buildDirectory.dir("plugin-apk/input")
val preparePluginApk = tasks.register<Sync>("preparePluginApk") {
    dependsOn("desktopMainClasses", generatePluginManifest)
    into(preparedAndroid)
    from(manifestFile) { into("assets/metrofuse") }
    from(rootProject.file("LICENSE")) { into("assets") }
    from(layout.projectDirectory.file("SOURCE")) { into("assets") }
    from(layout.projectDirectory.dir("assets")) { into("assets") }
}
val androidCode = tasks.register<Jar>("packageAndroidPluginClasses") {
    dependsOn("desktopMainClasses")
    archiveFileName.set("plugin-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("plugin-apk"))
    from(compilation.output.allOutputs)
}
val androidSdk = providers.provider {
    val properties = Properties()
    rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use(properties::load)
    file(
        requireNotNull(
            properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
        ) { "Set sdk.dir or ANDROID_HOME" }
    )
}
val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val dexOutput = layout.buildDirectory.dir("plugin-apk/dex")
val resourcesApk = layout.buildDirectory.file("plugin-apk/resources.apk")
val dexPlugin = tasks.register<Exec>("dexPluginApk") {
    dependsOn(preparePluginApk, androidCode)
    inputs.files(
        compilation.compileDependencyFiles
    ).withPropertyName("compileClasspath").withNormalizer(ClasspathNormalizer::class.java)
    inputs.file(androidCode.flatMap { it.archiveFile })
    outputs.dir(dexOutput)
    doFirst {
        val sdk = androidSdk.get()
        val output = dexOutput.get().asFile.apply { mkdirs() }
        output.listFiles().orEmpty().filter { it.extension == "dex" }.forEach { it.delete() }
        commandLine(
            listOf(
                jdk.get().executablePath.asFile, "-cp",
                sdk.resolve(
                    "build-tools/37.0.0/lib/d8.jar"
                ),
                "com.android.tools.r8.D8", "--min-api", "24", "--lib",
                sdk.resolve(
                    "platforms/android-37.0/android.jar"
                ),
                "--output", output
            ) +
                compilation.compileDependencyFiles.files.filter {
                    it.extension == "jar"
                }.flatMap { listOf("--classpath", it) } +
                androidCode.get().archiveFile.get().asFile
        )
    }
}
val linkPluginResources = tasks.register<Exec>("linkPluginApkResources") {
    dependsOn(preparePluginApk)
    inputs.file(layout.projectDirectory.file("AndroidManifest.xml"))
    outputs.file(resourcesApk)
    doFirst {
        val sdk = androidSdk.get()
        val executable = if (System.getProperty(
                "os.name"
            ).startsWith("Windows")
        ) {
            "aapt2.exe"
        } else {
            "aapt2"
        }
        commandLine(
            sdk.resolve(
                "build-tools/37.0.0/$executable"
            ),
            "link", "--manifest", layout.projectDirectory.file("AndroidManifest.xml").asFile,
            "-I",
            sdk.resolve(
                "platforms/android-37.0/android.jar"
            ),
            "--min-sdk-version", "24", "--target-sdk-version", "37", "-o", resourcesApk.get().asFile
        )
    }
}
tasks.register<Zip>("packagePluginApk") {
    dependsOn(dexPlugin, linkPluginResources)
    archiveFileName.set("deezer.apk")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/plugins"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(dexOutput)
    from(preparedAndroid.map { it.dir("assets") }) { into("assets") }
    from({ zipTree(resourcesApk.get().asFile) })
}
