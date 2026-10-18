package ee.schimke.ha.cards

import ee.schimke.composeai.discovery.PreviewTargetInference
import io.github.classgraph.ClassGraph
import kotlin.test.Test
import kotlin.test.assertTrue

/** The actual compiled previews must publish semantic scopes, not only the cards inside them. */
class HaAuthoringDiscoveryTest {
  @Test
  fun bindingAndStyleScopesAreDiscoverableWithTheConfiguredLibraryOwners() {
    val prefixes =
      System.getProperty("haCards.componentLibraryPrefixes").orEmpty().split(',').filter {
        it.isNotEmpty()
      }
    ClassGraph()
      .enableAllInfo()
      .acceptPackages("ee.schimke.ha.cards", "androidx.compose", "androidx.wear.compose")
      .scan()
      .use { scan ->
        val previews = scan.getClassInfo("ee.schimke.ha.cards.HaCardsCatalogPreviewsKt")
        val projectClasses =
          scan.allClasses
            .filter { it.name.startsWith("ee.schimke.ha.cards.") }
            .map { it.name }
            .toSet()
        val targets =
          listOf("HaCardTemplateSticker", "HaCardUberThemeSticker")
            .flatMap { name ->
              val method = previews.getMethodInfo(name).single()
              PreviewTargetInference.inferComponents(
                previews,
                method,
                scan,
                projectClasses,
                prefixes,
              )
            }
            .map { it.functionName }
            .toSet()
        assertTrue("HaCardTemplate" in targets, "The published binding scope is missing: $targets")
        assertTrue("HaCardUberTheme" in targets, "The published style scope is missing: $targets")
      }
  }
}
