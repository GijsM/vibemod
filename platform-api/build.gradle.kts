// platform-api: the host SPI plus the platform-neutral screen model
// (ARCHITECTURE-V2 §2, §3). JDK + Adventure + sdk-client only - never Bukkit,
// never net.minecraft.

dependencies {
    api(project(":sdk-client"))
    // Adventure is the project's text currency and is provided by every host
    // (paper-api transitively on Paper, adventure-platform-mod on loaders).
    compileOnlyApi("net.kyori:adventure-api:${property("adventureVersion")}")
}

// Enforces the ARCHITECTURE-V2 §1 rule: no platform types in platform-api.
tasks.register("checkPlatformFree") {
    group = "verification"
    description = "Fails if platform-api imports a platform-specific package."
    val sources = fileTree("src/main/java") { include("**/*.java") }
    val banned = listOf("org.bukkit", "io.papermc", "net.minecraft", "net.fabricmc", "net.neoforged")
    inputs.files(sources)
    outputs.upToDateWhen { false }
    doLast {
        val offenders = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ") && banned.any { trimmed.startsWith("import $it") }) {
                    offenders += "${file.name}: $trimmed"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("platform-api must stay platform-free; found:\n" + offenders.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn("checkPlatformFree") }
