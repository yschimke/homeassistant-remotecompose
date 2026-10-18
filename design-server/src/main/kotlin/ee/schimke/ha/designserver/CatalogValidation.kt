package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.*
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface

/** Publication is stricter than personal variants: never silently drop live values or actions. */
internal fun catalogTemplateProblem(
  bytes: ByteArray,
  baseBytes: ByteArray,
  key: DesignKey,
): String? {
  val document =
    runCatching { RcDocumentCodec.decode(bytes, RcWireLimits()) }.getOrNull()
      ?: return "Invalid Remote Compose output"
  val base = RcDocumentCodec.decode(baseBytes, RcWireLimits())
  val profile =
    when (key.surface) {
      DesignSurface.MobileApp,
      DesignSurface.WearApp -> RcTargetProfiles.ANDROIDX_EXPERIMENTAL
      DesignSurface.WearWidget -> RcTargetProfiles.WEAR_WIDGETS
      DesignSurface.Launcher ->
        if (key.rcApiLevel <= 6) RcTargetProfiles.WIDGETS_V6 else RcTargetProfiles.WIDGETS_V7
    }
  document.validateFor(profile).firstOrNull()?.let {
    return "Unsupported target operation: ${it.message}"
  }
  fun bindings(doc: RcDocument) =
    doc.namedVariables.associate { it.name.removePrefix(DesignLibrary.USER_PREFIX) to it.type }
  val actual = bindings(document)
  val required = bindings(base)
  if (required.any { (name, type) -> actual[name] != type })
    return "Required live bindings are missing or changed type"
  // The catalog's Remote Material palette exports WearM3 roles with in-document defaults.
  // Arbitrary entity colors still need an existing host contract, just like numeric readings.
  if (
    actual.keys.any {
      '.' in it &&
        it !in required &&
        !(it.startsWith("WearM3.") && actual[it] == RcNamedVariable.COLOR_TYPE)
    }
  )
    return "Unknown host binding"
  // Compare semantic host payloads, not generated text IDs. An expression-generated host payload
  // needs its own equivalence checker; refuse that migration instead of treating it as equivalent.
  fun actions(doc: RcDocument): Set<String>? {
    val text = doc.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    return buildSet {
      for (op in doc.operations) when (op) {
        is RcHostNamedAction -> {
          val name = text[op.nameTextId] ?: return null
          val payload =
            when (val value = op.value) {
              is RcHostNamedActionValue.TextValue -> text[value.valueId] ?: return null
              RcHostNamedActionValue.None -> ""
              else -> return null
            }
          add("named:$name:$payload")
        }
        is RcHostAction -> add("id:${op.actionId}")
        // Metadata host actions do not have a portable HA contract here yet.
        is RcHostMetadataAction -> return null
        else -> Unit
      }
    }
  }
  val expectedActions =
    actions(base) ?: return "Contract has action expressions requiring equivalence review"
  val actualActions = actions(document) ?: return "Source has unsupported host action expressions"
  if (actualActions != expectedActions)
    return "Host actions differ from the supported card contract"
  return null
}
