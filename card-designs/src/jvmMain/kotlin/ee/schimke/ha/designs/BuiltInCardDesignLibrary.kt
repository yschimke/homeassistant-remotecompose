package ee.schimke.ha.designs

import ee.schimke.ha.rc.CommonCaptureHost
import java.io.File
import kotlin.system.exitProcess

/**
 * Writes the designs the phone app ships ([CardDesignLibrary.BUILT_IN_THEMES]) into a directory,
 * captured on the JVM as the published library is, so a built-in design is the same file the design
 * server serves for its key. `./gradlew :card-designs:generateBuiltInCardDesigns`.
 */
fun main(args: Array<String>) {
  val target =
    File(
      args.singleOrNull()
        ?: run {
          System.err.println("usage: BuiltInCardDesignLibrary <output directory>")
          exitProcess(2)
        }
    )
  val count =
    CardDesignLibrary.generateBuiltIn(target, CommonCaptureHost(LibraryCaptureProfile.OPERATIONS))
  println("built-in card designs: $count designs in $target")
  // The Compose scenes leave non-daemon threads behind.
  exitProcess(0)
}
