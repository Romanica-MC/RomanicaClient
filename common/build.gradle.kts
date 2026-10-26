import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.objectweb.asm.*
import java.util.*

plugins {
	id("java")
	id("org.jetbrains.kotlin.jvm")
	id("org.jetbrains.kotlin.plugin.lombok")
	id("com.gradleup.shadow")
}

group = project.property("maven_group").toString() + "." + project.property("archives_base_name").toString()
base.archivesName.set(project.property("archives_base_name").toString() + "-common")

dependencies {
	compileOnly("net.fabricmc:fabric-loader:${project.property("fabric_loader")}")
	compileOnly("net.fabricmc:sponge-mixin:0.16.1+mixin.0.8.7")
	compileOnly("org.jetbrains:annotations:24.0.0")

	// Kotlin stdlib for compiling this module's Kotlin sources. Provided at runtime by
	// fabric-language-kotlin in the version modules — must never leak to consumers
	// (see the kotlin.stdlib.default.dependency flag in settings.gradle).
	compileOnly(kotlin("stdlib"))

	// take the oldest version just to build against
	compileOnly("io.github.axolotlclient:AxolotlClient-config:${project.property("config")}+1.8.9") {
		isTransitive = false
	}
	compileOnly("io.github.axolotlclient.AxolotlClient-config:AxolotlClientConfig-common:${project.property("config")}")

	compileOnly("com.google.guava:guava:17.0")
	compileOnly("org.apache.httpcomponents:httpclient:4.3.3")
	compileOnly("com.google.code.gson:gson:2.10")
	compileOnly("commons-io:commons-io:2.4")
	compileOnly("org.apache.commons:commons-lang3:3.3.2")
	compileOnly("it.unimi.dsi:fastutil:8.5.9")
	compileOnly("org.lwjgl:lwjgl-glfw:3.3.2")
	compileOnly("org.lwjgl:lwjgl-tinyfd:3.3.2")
	compileOnly("org.lwjgl:lwjgl-sdl:3.4.1")
	// Windows DPAPI for TokenVault. Minecraft ships JNA and jna-platform at runtime.
	compileOnly("net.java.dev.jna:jna:5.15.0")
	compileOnly("net.java.dev.jna:jna-platform:5.15.0")

	shadow(implementation("io.github.cdagaming:DiscordIPC:0.11.3") {
		isTransitive = false
	})
	shadow(runtimeOnly(compileOnly("com.kohlschutter.junixsocket:junixsocket-common:2.10.1")!!)!!)
	shadow(runtimeOnly(compileOnly("com.kohlschutter.junixsocket:junixsocket-native-common:2.10.1")!!)!!)

	shadow(runtimeOnly(compileOnly("com.github.mizosoft.methanol:methanol:1.9.0")!!)!!)
	shadow(runtimeOnly(compileOnly("io.nayuki:qrcodegen:1.8.0")!!)!!)

	compileOnly("net.hypixel:mod-api:1.0.1")
	compileOnly("com.mojang:brigadier:1.0.18")

	compileOnly("org.slf4j:slf4j-api:2.0.1")
}

tasks.jar {
	enabled = false
}

tasks.build {
	dependsOn(tasks.shadowJar)
}

tasks.processResources {
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
	exclude("blobfox_*.png")
}

// Rewrites the "@FOX_PRIDE@"/"@FOX_TRANS@" placeholder constants in the compiled AltIcons
// class with the base64 blobfox icons. AltIcons is compiled by the Kotlin compiler, but the
// rewrite stays registered on BOTH compile tasks so a sibling class moved back to Java (or
// another AltIcons-prefixed class compiled from Java) keeps working. Each task's walk only
// rewrites AltIcons classes found in its own output directory, and the rewrite is idempotent.
val rewriteAltIconPlaceholders = Action<Task> {
	val foxes = inputs.files.files.filter { it.name.startsWith("blobfox_") }
	val pride = foxes.first { it.name == "blobfox_pride_128.png" }
	val trans = foxes.first { it.name == "blobfox_pride_trans_128.png" }
	outputs.files.files.forEach { d ->
		d.walkTopDown().filter { f -> f.name.startsWith("AltIcons") }
			.forEach {
				val reader = ClassReader(it.readBytes())
				val writer = ClassWriter(0)
				val visitor = object : ClassVisitor(Opcodes.ASM9, writer) {
					override fun visitMethod(
						access: Int,
						name: String?,
						descriptor: String?,
						signature: String?,
						exceptions: Array<out String?>?
					): MethodVisitor? {
						val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
						if (name == "getAltIcon") {
							return object : MethodVisitor(Opcodes.ASM9, mv) {
								override fun visitLdcInsn(value: Any?) {
									super.visitLdcInsn(when (value) {
										"@FOX_PRIDE@" -> Base64.getEncoder().encodeToString(pride.readBytes())
										"@FOX_TRANS@" -> Base64.getEncoder().encodeToString(trans.readBytes())
										else -> value
									})
								}
							}
						}
						return mv
					}
				}
				reader.accept(visitor, 0)
				it.writeBytes(writer.toByteArray())
			}
	}
}

listOf(tasks.compileJava, tasks.compileKotlin).forEach { compileTask ->
	compileTask.configure {
		inputs.files(sourceSets.main.get().resources.files.filter { it.name.startsWith("blobfox_") })
		actions.addLast(rewriteAltIconPlaceholders)
	}
}

tasks.withType(JavaCompile::class).configureEach {
	options.encoding = "UTF-8"

	if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_18)) {
		options.release = 17
	}
}

tasks.withType(AbstractArchiveTask::class).configureEach {
	isPreserveFileTimestamps = false
	isReproducibleFileOrder = true
}

tasks.shadowJar {
	archiveClassifier.set("")
	mergeServiceFiles()
	minimize {
		exclude(dependency("com.github.mizosoft.methanol:.*:.*"))
		exclude(dependency("io.github.CDAGaming:DiscordIPC:.*"))
		exclude(dependency("com.kohlschutter.junixsocket:junixsocket-common:.*"))
		exclude(dependency("com.kohlschutter.junixsocket:junixsocket-native-common:.*"))
	}

	relocate("com.jagrosh", "io.github.axolotlclient.shadow.jagrosh")
	relocate("com.github.mizosoft", "io.github.axolotlclient.shadow.mizosoft")
	relocate("io.nayuki", "io.github.axolotlclient.shadow.nayuki")

	// The Kotlin runtime reaches the client via fabric-language-kotlin (a mod dependency
	// of the version modules, not jar-in-jar) — never bundle it into the common shadow jar.
	dependencies {
		exclude(dependency("org.jetbrains.kotlin:.*:.*"))
	}

	append("../LICENSE")
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	withSourcesJar()
}

kotlin {
	// Match the Java 17 release target this module compiles its Java sources with.
	compilerOptions {
		jvmTarget.set(JvmTarget.JVM_17)
	}
}

publishing {
	publications {
		create("shadow", MavenPublication::class) {
			artifactId = base.archivesName.get()
			from(components["shadow"])
			artifact(tasks.sourcesJar)
		}
	}
	repositories {
		maven {
			name = "owlMaven"
			val repository = if (project.version.toString().contains("beta") || project.version.toString()
					.contains("alpha")
			) "snapshots" else "releases"
			url = uri("https://maven.axolotlclient.com/$repository")
			credentials(PasswordCredentials::class)
			authentication {
				create<BasicAuthentication>("basic")
			}
		}
	}
}
