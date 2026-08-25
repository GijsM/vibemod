// sdk: the generated-code contract, Paper flavor (ARCHITECTURE-V2 §4.1).
// Bukkit-typed and therefore compiled against paper-api - but `compileOnly`,
// because at runtime these classes live inside the host jar and the host
// supplies Bukkit. The mod flavor (`src/mod/java`) arrives in Phase D.

dependencies {
    api(project(":sdk-client"))
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}
