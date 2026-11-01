package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.design.HaSharedValues

/** Shared Home Assistant sources declared by these selected, playable design templates. */
fun sharedDesignNames(templates: Iterable<ByteArray>): Set<String> =
  templates
    .flatMap { bytes ->
      runCatching {
          RcDocumentCodec.decode(bytes, RcWireLimits())
            .operations
            .filterIsInstance<RcNamedVariable>()
            .map { it.name }
        }
        .getOrDefault(emptyList())
    }
    .filterTo(linkedSetOf()) { HaSharedValues.valueOf(it) != null }

fun sharedDesignDependencies(templates: Iterable<ByteArray>): Map<String, Set<String>> =
  HaSharedValues.dependencies(sharedDesignNames(templates))
