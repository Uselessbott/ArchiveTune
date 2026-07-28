from pathlib import Path

gradle = Path("app/build.gradle.kts")

text = gradle.read_text(encoding="utf-8")

if "printRuntimeClasspath" in text:
    print("Task already exists.")
    raise SystemExit()

text += """

tasks.register("printRuntimeClasspath") {
    doLast {
        println("========== Runtime Artifacts ==========")
        configurations
            .getByName("gmsMobileUniversalDebugRuntimeClasspath")
            .resolvedConfiguration
            .resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }
            .forEach {
                println("${it.moduleVersion.id} -> ${it.file.name}")
            }
        println("=======================================")
    }
}
"""

gradle.write_text(text, encoding="utf-8")
print("Done.")
