import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class PinnedDownloadTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val output = destination.get().asFile
        output.parentFile.mkdirs()
        if (!output.isFile) {
            URI(sourceUrl.get()).toURL().openStream().buffered().use { input ->
                output.outputStream().buffered().use(input::copyTo)
            }
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(output.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == expectedSha256.get()) {
            output.delete()
            "The pinned download failed its SHA-256 check."
        }
    }
}
