import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class StripNativeRuntimeTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    abstract val stripExecutable: RegularFileProperty

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @TaskAction
    fun strip() {
        val source = sourceDirectory.get().asFile
        val destination = destinationDirectory.get().asFile
        destination.deleteRecursively()
        source.walkTopDown().filter { it.isFile }.forEach { input ->
            val output = destination.resolve(input.relativeTo(source).path)
            output.parentFile.mkdirs()
            input.copyTo(output, overwrite = true)
            val result = ProcessBuilder(
                stripExecutable.get().asFile.absolutePath,
                "--strip-debug",
                output.absolutePath
            ).inheritIO().start().waitFor()
            check(result == 0) { "Could not strip ${input.name}." }
        }
    }
}
