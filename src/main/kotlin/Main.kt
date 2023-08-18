import com.drew.imaging.ImageMetadataReader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.io.FilenameFilter
import java.util.*


fun main(args: Array<String>) = Deduper().main(args)

class Deduper : CliktCommand() {
    val rootDir: File by option().file().required().validate { it.isDirectory }
    val deleteDupes: Boolean by option().boolean().default(false).help("Delete duplicates")

    override fun run() {

        println("Root Dir = $rootDir")

        val dupesFound = mutableSetOf<Photo>()

        fun collectDir(dir: File) {

            println("Collecting Dir $dir...")
            val collector = Collector()

            //process files first
            dir.listFiles(NonDirectoryFilter)?.forEach {
                if (allowedMedia(it))
                    collector.collect(it)
                else
                    println("!!! Discarding disallowed file: $it")
            } ?: throw RuntimeException("Failed to list files in $dir")

            //then recurse
            dir.listFiles(DirectoryFilter)?.forEach {
                collectDir(it)
            } ?: throw RuntimeException("Failed to list dirs in $dir")

            dupesFound.addAll(collector.dupes)
        }

        collectDir(rootDir)

        dupesFound.forEach {
            if (deleteDupes) {
                println("Deleting ${it}")
                it.delete()
            } else {
                println("Not deleting ${it}")
            }
        }
    }
}

fun allowedMedia(file: File): Boolean = arrayOf("jpg", "jpeg").contains(file.extension.lowercase(Locale.getDefault()))

object DirectoryFilter : FilenameFilter {
    override fun accept(dir: File?, name: String?) = isDirectory(dir, name)
}

object NonDirectoryFilter : FilenameFilter {
    override fun accept(dir: File?, name: String?) = !isDirectory(dir, name)
}

private fun isDirectory(dir: File?, name: String?) = File(dir, name!!).isDirectory


typealias Photo = File
typealias CollectionResult = Boolean
typealias Fingerprint = String


class Collector {

    val fingerprints = mutableMapOf<Fingerprint, Photo>()
    val dupes = mutableSetOf<Photo>()

    fun collect(photo: Photo): CollectionResult {
        println("Collecting File: $photo")
        fingerprint(photo)
        return false
    }

    fun fingerprint(photo: Photo) {
        val metadata = ImageMetadataReader.readMetadata(photo)

        val expectedUnique = arrayOf(Pair("Canon Makernote", "Image Unique ID"))

        for (directory in metadata.directories) {
            for (tag in directory.tags) {
                for (expected in expectedUnique) {
                    if (expected.first == directory.name && expected.second == tag.tagName) {
                        addFingerprint(expected.toString(), tag.description, photo)
                    }
                }
            }
        }
    }

    fun addFingerprint(type: String, fingerprint: Fingerprint, photo: Photo) {
        val existing = fingerprints.put("$type:$fingerprint", photo)

        if (existing != null) {
            dupes.add(photo)
        }
    }

    fun getDupes(): Collection<Photo> = dupes
}