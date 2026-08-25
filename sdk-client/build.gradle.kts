// sdk-client: the generated-code CLIENT contract. Pure JDK, no dependencies at
// all (ARCHITECTURE-V2 Decision 9) - it must compile and load everywhere,
// including inside a Minecraft client with no Bukkit/Adventure on the classpath.

// Belt-and-braces guard for Decision 9: nothing outside java.* may be imported.
tasks.register("checkPureJdk") {
    group = "verification"
    description = "Fails if sdk-client imports anything outside java.*"
    val sources = fileTree("src/main/java") { include("**/*.java") }
    inputs.files(sources)
    outputs.upToDateWhen { false }
    doLast {
        val offenders = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ") && !trimmed.startsWith("import java.")) {
                    offenders += "${file.name}: $trimmed"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("sdk-client must be pure JDK; found:\n" + offenders.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn("checkPureJdk") }
