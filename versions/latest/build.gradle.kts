@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	id("org.jetbrains.kotlin.jvm")
	id("org.jetbrains.kotlin.plugin.lombok")
}

// Must match the Kotlin version of fabric-language-kotlin (see the modImplementation
// dependency below), and the JVM target must match the Java 25 toolchain of this project.
val fabricLanguageKotlin = "1.14.2+kotlin.2.4.21"

val minecraftVersion = "26.3"
val minecraftFriendly = "26.3"
val modmenu = "18.0.0-alpha.8"
val fapi = "0.161.0+26.3"
group = project.property("maven_group") as String
version = "${project.property("version")}+$minecraftFriendly"
base.archivesName = "ImperiumClient"

loom {
	accessWidenerPath.set(file("src/main/resources/axolotlclient.classtweaker"))
	mods {
		create("axolotlclient") {
			sourceSet("main")
		}
		create("axolotlclient-test") {
			sourceSet("test")
		}
	}
	runs {
		create("clientGameTest") {
			client()
			configName = "Client Game Test"
			vmArg("-Dfabric.client.gametest")
			vmArg("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
			// Cap the test client's heap: gametest worlds are small, and on shared build
			// machines an uncapped client has caused native OOM kills mid-suite.
			vmArg("-Xmx1600m")
			runDir("build/run/clientGameTest")
			source(sourceSets.getByName("test"))
			// Headless CI subset (see ImperiumGametestSubsets in the test source set):
			// -Pimperium.fastGametests keeps only the logic/protocol tests, for GitHub's
			// GPU-less runners where the render-heavy tests cannot finish under software GL.
			if (project.hasProperty("imperium.fastGametests")) {
				vmArg("-Dimperiumclient.gametest.subset=ci")
			}
		}
	}
	decompilers {
		decompilerOptions.named("vineflower") {
			options.put("mark-corresponding-synthetics", "1")
		}
	}
}

repositories {
	maven("https://maven.noxcrew.com/public")
	maven("https://maven.enginehub.org/repo/")
}

dependencies {
	minecraft("com.mojang:minecraft:$minecraftVersion")
	implementation("net.fabricmc:fabric-loader:${project.property("fabric_loader")}")

	implementation("net.fabricmc.fabric-api:fabric-api:$fapi")

	// Kotlin language adapter: provides the "kotlin" entrypoint adapter and the Kotlin
	// runtime on the client (declared in fabric.mod.json depends, NOT jar-in-jar).
	implementation("net.fabricmc:fabric-language-kotlin:$fabricLanguageKotlin")

	implementation("io.github.axolotlclient:AxolotlClient-config:${project.property("config")}+$minecraftFriendly")
	include("io.github.axolotlclient:AxolotlClient-config:${project.property("config")}+$minecraftFriendly")
	implementation(include("io.github.axolotlclient:AxolotlClient-config-rounded:${project.property("config")}+$minecraftFriendly")!!)

	compileOnly("com.terraformersmc:modmenu:$modmenu")

	api(include(project(path = ":common", configuration = "shadow"))!!)

	val noxesiumVersion = "3.1.0"
	compileOnly("maven.modrinth:noxesium:$noxesiumVersion")
	implementation("com.noxcrew.noxesium:api:$noxesiumVersion")
	//localRuntime("org.khelekore:prtree:1.5")

	compileOnly("maven.modrinth:e4mc:6.2.2-fabric-modern")

	implementation("net.hypixel:mod-api:1.0.2")
	include(implementation("maven.modrinth:hypixel-mod-api:1.0.2+build.1+mc26.1")!!)

	testImplementation(platform("org.junit:junit-bom:5.13.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Plain JUnit tests (src/test/kotlin) for logic that needs no game. They share the test
// source set with the client gametests, which the JUnit engine does not pick up.
tasks.test {
	useJUnitPlatform()
}

tasks.processResources {
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType(JavaCompile::class).configureEach {
	options.encoding = "UTF-8"

	if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_26)) {
		options.release = 25
	}
}

java {
	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
	// Match the Java 25 toolchain used by this project (see java {} above).
	compilerOptions {
		jvmTarget.set(JvmTarget.JVM_25)
	}
}

tasks.runClient {
	classpath(sourceSets.getByName("test").runtimeClasspath)
	jvmArgs("-XX:+AllowEnhancedClassRedefinition", "-XX:+IgnoreUnrecognizedVMOptions")
}

// Configure the maven publication
publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			artifactId = base.archivesName.get()
			from(components["java"])
		}
	}

	repositories {
		maven {
			name = "owlMaven"
			val repository = if (project.version.toString().contains("beta") || project.version.toString().contains("alpha")) "snapshots" else "releases"
			url = uri("https://maven.axolotlclient.com/$repository")
			credentials(PasswordCredentials::class)
			authentication {
				create<BasicAuthentication>("basic")
			}
		}
	}
}
