from pathlib import Path

gradle = Path("app/build.gradle.kts")

text = gradle.read_text(encoding="utf-8")

if "printRuntimeClasspath" in text:
    print("Task already exists.")
    raise SystemExit(0)

task = r"""

tasks.register("printRuntimeClasspath") {
    doLast {
        println("========== Runtime Artifacts ==========")

        configurations
            .getByName("gmsMobileUniversalDebugRuntimeClasspath")
            .resolvedConfiguration
            .resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }
            .forEach {
                println("${it.moduleVersion.id} -> ${it.file.absolutePath}")
            }

        println("=======================================")
    }
}
"""

gradle.write_text(text + task, encoding="utf-8")

print("✓ Added printRuntimeClasspath Gradle task.")
