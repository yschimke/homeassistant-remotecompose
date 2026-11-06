package ee.schimke.ha.designserver

import ee.schimke.ha.designs.UiBuilderDesignCompiler
import java.util.Base64

/** Exercises the image decoder used when publishing a design with photos, inside the image. */
fun main() {
  val png =
    Base64.getDecoder()
      .decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=",
      )
  check(UiBuilderDesignCompiler.fitPictures(mapOf("image" to png), 1024).containsKey("image"))
  println("Design image decoding works")
}
