import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    `java-library`
}

@DisableCachingByDefault(because = "The task performs fast integrity checks on a resolved dependency")
abstract class VerifySshjUpstream : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: RegularFileProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val expectedArchiveHash: Property<String>

    @get:Input
    abstract val expectedEntryHashes: MapProperty<String, String>

    @TaskAction
    fun verify() {
        val archiveFile = archive.get().asFile
        check(hash(archiveFile) == expectedArchiveHash.get()) {
            "SSHJ ${version.get()} artifact hash changed; review and rebase the Android patch before building."
        }
        ZipFile(archiveFile).use { zip ->
            expectedEntryHashes.get().forEach { (entryName, expectedHash) ->
                val entry = checkNotNull(zip.getEntry(entryName)) {
                    "SSHJ ${version.get()} no longer contains $entryName"
                }
                val actualHash = zip.getInputStream(entry).use { hash(it.readBytes()) }
                check(actualHash == expectedHash) {
                    "SSHJ ${version.get()} changed $entryName; review and rebase the Android patch."
                }
            }
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val sshjVersion = "0.40.0"
val upstreamSshj by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    upstreamSshj("com.hierynomus:sshj:$sshjVersion")
    compileOnly(files(upstreamSshj))

    api("org.slf4j:slf4j-api:2.0.17")
    api("org.bouncycastle:bcprov-jdk18on:1.80.2")
    api("org.bouncycastle:bcpkix-jdk18on:1.80")
    api("com.hierynomus:asn-one:0.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(files(upstreamSshj))
}

val patchedClasses = mapOf(
    "net/schmizz/sshj/common/Ed25519KeyFactory.class" to
        "e9bea0457eec5a63bf7c965a44141a0b0805310b9b4319103d90de57a3bac0a5",
    "com/hierynomus/sshj/signature/SignatureEdDSA.class" to
        "4fc9e66a391289fbdc0bf09af9470ba386a85d1620ef421f11b3e87c9e920347",
    "com/hierynomus/sshj/signature/SignatureEdDSA\$Factory.class" to
        "b8892bb7a95235ebe7c8f6f48c69c9601ac67703014fc12cb2d363301ee204b9",
)
val expectedUpstreamJarSha256 =
    "a6a5533f6580e0418dfcacbb5396680186698585c325fa950c788e749f24d1e2"
val verifyUpstreamSshj by tasks.registering(VerifySshjUpstream::class) {
    val originalJar = upstreamSshj.elements.map { it.single().asFile }
    archive.fileProvider(originalJar)
    version.set(sshjVersion)
    expectedArchiveHash.set(expectedUpstreamJarSha256)
    expectedEntryHashes.set(patchedClasses)
}

val extractedUpstreamClasses = layout.buildDirectory.dir("generated/upstream-sshj/classes")
val extractUpstreamSshj by tasks.registering(Sync::class) {
    dependsOn(verifyUpstreamSshj)
    from({ zipTree(upstreamSshj.singleFile) }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        patchedClasses.keys.forEach(::exclude)
    }
    into(extractedUpstreamClasses)
    includeEmptyDirs = false
}

sourceSets.named("main") {
    output.dir(mapOf("builtBy" to extractUpstreamSshj), extractedUpstreamClasses)
}

listOf("apiElements", "runtimeElements").forEach { configurationName ->
    configurations.named(configurationName) {
        outgoing.variants.named("classes") {
            artifact(extractedUpstreamClasses) {
                builtBy(extractUpstreamSshj)
                type = "java-classes-directory"
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(verifyUpstreamSshj)
    options.release.set(11)
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    dependsOn(verifyUpstreamSshj)
    archiveBaseName.set("sshj-android")
    archiveVersion.set(sshjVersion)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL

    from(layout.projectDirectory.file("LICENSE-SSHJ.txt")) {
        into("META-INF")
        rename { "LICENSE-SSHJ.txt" }
    }
    from(layout.projectDirectory.file("NOTICE-SSHJ.txt")) {
        into("META-INF")
        rename { "NOTICE-SSHJ.txt" }
    }
    manifest {
        attributes(
            "Implementation-Title" to "PocketPilot SSHJ Android compatibility build",
            "Implementation-Version" to sshjVersion,
            "PocketPilot-Upstream-SHA256" to expectedUpstreamJarSha256,
        )
    }

    doLast {
        val expectedHash = "fb3103bc269f9c1ba3c7f25e15173258c945c2e5a9284657958c1c03da66e89f"
        val digest = MessageDigest.getInstance("SHA-256")
        archiveFile.get().asFile.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        check(actualHash == expectedHash) {
            "Patched SSHJ jar is not reproducible: expected $expectedHash, got $actualHash"
        }
    }
}

tasks.test {
    useJUnit()
}
