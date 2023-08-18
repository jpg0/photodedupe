import com.drew.imaging.ImageMetadataReader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.file
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import java.io.File
import java.io.FilenameFilter
import java.util.*


fun main(args: Array<String>) = Deduper().main(args)

class Deduper : CliktCommand() {
    val rootDir: File by option().file().required().validate { it.isDirectory }
    val deleteDupes: Boolean by option().boolean().default(false).help("Delete duplicates")
    val verbose: Boolean by option().boolean().default(false).help("Verbose output")

    override fun run() {

//        println("Root Dir = $rootDir")


        val dupesFound = mutableSetOf<Photo>()

        ProgressBarBuilder()
            .setTaskName("Fingerprinting")
            .setInitialMax(0)
            .setStyle(ProgressBarStyle.ASCII).build().use { pb ->

            fun collectDir(dir: File) {

//            println("Collecting Dir $dir...")
                val collector = Collector()

                //process files first
                val files = dir.listFiles(NonDirectoryFilter) ?: throw RuntimeException("Failed to list files in $dir")

                pb.maxHint(pb.max + files.size)

                files.forEach {
                    when (allowOrIgnoreMedia(it)) {
                        true -> collector.collect(it)
                        false -> if (verbose) println("Ignoring $it")
                        null -> println("!!! Discarding unknown file: $it")
                    }
                    pb.step()
                }

                //then recurse
                dir.listFiles(DirectoryFilter)?.forEach {
                    collectDir(it)
                } ?: throw RuntimeException("Failed to list dirs in $dir")

                dupesFound.addAll(collector.dupes)
            }

            collectDir(rootDir)

        }

        ProgressBarBuilder()
            .setTaskName("Deleting")
            .setInitialMax(dupesFound.size.toLong())
            .setStyle(ProgressBarStyle.ASCII).build().use { pb ->

            dupesFound.forEach {
                pb.extraMessage = it.name
                if (deleteDupes) {
                    it.delete()
                    if (verbose)
                        println("Deleted ${it}")
                } else {
                    println("Not deleting ${it}")
                }
                pb.step()
            }

        }
    }
}

val ALLOWED_EXTS = arrayOf("jpg", "jpeg")
val IGNORED_EXTS = arrayOf("mp4", "ds_store")

fun allowOrIgnoreMedia(file: File): Boolean? = file.extension.lowercase(Locale.getDefault()).let { ext ->
    return if (ALLOWED_EXTS.contains(ext)) {
        true
    } else if (IGNORED_EXTS.contains(ext)) {
        false
    } else null
}

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
//        println("Collecting File: $photo")
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