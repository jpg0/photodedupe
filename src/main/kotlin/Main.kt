import com.drew.imaging.ImageMetadataReader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import java.io.File
import java.io.FilenameFilter
import java.util.*
import java.util.concurrent.ForkJoinPool
import java.util.stream.Collectors


fun main(args: Array<String>) = Deduper().main(args)

var verboseOutput = false

fun verbose(s: String) {
    if (verboseOutput) println(s)
}

fun log(s: String) = println(s)

class Deduper : CliktCommand() {
    val rootDir: File by option().file().required().validate { it.isDirectory }
    val deleteDupes: Boolean by option().boolean().default(false).help("Delete duplicates")
    val verbose: Boolean by option().flag(default = false).help("Verbose output")
    val threads: Int by option().int().default(1)
    val dupesFile: File? by option().file().validate { !it.exists() }

    override fun run() {

//        println("Root Dir = $rootDir")

        verboseOutput = this.verbose
        val numTasks = threads
        val pool = ForkJoinPool(numTasks)

        val dupesFound = ProgressBarBuilder()
            .setTaskName("Fingerprinting")
            .setInitialMax(0)
            .setStyle(ProgressBarStyle.ASCII).build().use { pb ->

                fun collectDir(dir: File): Set<Photo> {

                    //process files
                    val files =
                        dir.listFiles(NonDirectoryFilter) ?: throw RuntimeException("Failed to list files in $dir")

                    pb.maxHint(pb.max + files.size)

                    //recurse
                    val dirs = dir.listFiles(DirectoryFilter) ?: throw RuntimeException("Failed to list dirs in $dir")

                    val dirDupes = pool.submit<Set<Photo>> {
                        dirs.asList().parallelStream().flatMap {
                            collectDir(it).stream()
                        }.collect(Collectors.toSet()).toSet()
                    }.get()

                    val fileDupes: Set<File> = pool.submit<Set<Photo>> {

                        val collector = Collector()

                        files.asList().mapNotNull {
                            val rv = when (allowOrIgnoreMedia(it)) {
                                true -> {
                                    if(collector.collect(it))
                                        it
                                    else
                                        null
                                }

                                false -> {
                                    verbose("Ignoring $it")
                                    null
                                }

                                null -> {
                                    log("!!! Discarding unknown file: $it")
                                    null
                                }
                            }
                            pb.step()
                            return@mapNotNull rv
                        }.toSet()
                    }.get()

                    return fileDupes + dirDupes
                }

                collectDir(rootDir)

            }

        if (dupesFound.size == 0) {
            log("No Dupes Found!")
        } else {

            log("${dupesFound.size} duplicates found!")

            if (deleteDupes) {
                ProgressBarBuilder()
                    .setTaskName("Deleting")
                    .setInitialMax(dupesFound.size.toLong())
                    .setStyle(ProgressBarStyle.ASCII).build().use { pb ->

                        dupesFound.forEach {
                            pb.extraMessage = it.name
                            it.delete()
                            pb.step()
                        }

                    }
            }

            if (dupesFile != null) {
                ProgressBarBuilder()
                    .setTaskName("Writing File")
                    .setInitialMax(dupesFound.size.toLong())
                    .setStyle(ProgressBarStyle.ASCII).build().use { pb ->

                        dupesFile!!.bufferedWriter().use { out ->
                            dupesFound.forEach {
                                pb.extraMessage = it.name
                                out.write(it.absolutePath)
                                out.newLine()
                                pb.step()
                            }
                        }
                    }
            }

        }
    }
}

val ALLOWED_EXTS = arrayOf("jpg", "jpeg")
val IGNORED_EXTS = arrayOf("mp4", "ds_store", "wav")
val IGNORED_PREFIX = "._"

fun allowOrIgnoreMedia(file: File): Boolean? = file.extension.lowercase(Locale.getDefault()).let { ext ->
    if (file.name.startsWith(IGNORED_PREFIX)) {
        false
    } else if (ALLOWED_EXTS.contains(ext)) {
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
        return fingerprint(photo)
    }

    fun fingerprint(photo: Photo): Boolean {
        val metadata = try {
            ImageMetadataReader.readMetadata(photo)
        } catch (e: Exception) {
            throw java.lang.RuntimeException("Failed to parse exif data for $photo", e)
        }

        val expectedUnique = arrayOf(Pair("Canon Makernote", "Image Unique ID"))

        var dupe = false

        for (directory in metadata.directories) {
            for (tag in directory.tags) {
                for (expected in expectedUnique) {
                    if (expected.first == directory.name && expected.second == tag.tagName) {
                        if (addFingerprint(expected.toString(), tag.description, photo))
                            dupe = true
                    }
                }
            }
        }

        return dupe
    }

    fun addFingerprint(type: String, fingerprint: Fingerprint, photo: Photo) : Boolean {
        val existing = fingerprints.put("$type:$fingerprint", photo)

        if (existing != null) {
            verbose("> $existing is a dupe of $photo")
            dupes.add(photo)
            return true
        }

        return false
    }

    fun getDupes(): Collection<Photo> = dupes
}