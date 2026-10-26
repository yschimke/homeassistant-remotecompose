package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.temperatureUnitFor
import ee.schimke.ha.rc.components.HaAction
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A control in an entity's more-info sheet: what it shows and the [HaAction] each input sends.
 * [entityControls] picks them by domain and by what the entity says it supports
 * (`supported_features`, `supported_color_modes`, the attributes it reports), so a dimmer gets a
 * brightness slider and a plain switch doesn't.
 */
sealed interface EntityControl {
  /**
   * An on/off switch; a direction the entity can't take ([turnOn] or [turnOff] null) is disabled.
   */
  data class Power(val on: Boolean, val turnOn: HaAction?, val turnOff: HaAction?) : EntityControl

  /**
   * A slider over [range], in [step]s, set when released: brightness, a cover's position, a fan's
   * speed, a volume, a number entity.
   */
  data class Level(
    val label: String,
    val value: Float?,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val unit: String?,
    val set: (Float) -> HaAction,
  ) : EntityControl

  /** A target nudged up or down by [step]: a thermostat's setpoint. */
  data class Stepper(
    val label: String,
    val value: Float?,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val unit: String?,
    val set: (Float) -> HaAction,
  ) : EntityControl

  /** A row of commands: open / stop / close, play / pause, lock / unlock, press. */
  data class Buttons(val buttons: List<Button>) : EntityControl

  /** One of [options]: an HVAC mode, a select's option, a fan preset. */
  data class Choice(
    val label: String,
    val options: List<String>,
    val selected: String?,
    val choose: (String) -> HaAction,
  ) : EntityControl

  data class Button(
    val label: String,
    val kind: ButtonKind,
    val action: HaAction,
    /** Shown as the current one: the state the command leads to is the state now. */
    val active: Boolean = false,
  )

  enum class ButtonKind {
    Open,
    Close,
    Stop,
    Play,
    Pause,
    Previous,
    Next,
    Lock,
    Unlock,
    Press,
    Dock,
  }
}

/**
 * [entityId]'s controls, in the order the sheet shows them; empty for a read-only entity.
 * [temperatureUnit] is the instance's (`HaSnapshot.temperatureUnit`), for a thermostat that doesn't
 * name its own.
 */
fun entityControls(
  entityId: String,
  state: EntityState?,
  temperatureUnit: String? = null,
): List<EntityControl> {
  if (state == null || state.state == "unavailable") return emptyList()
  val domain = entityId.substringBefore('.')
  val attrs = state.attributes
  fun call(service: String, data: Map<String, JsonElement> = emptyMap(), on: String = domain) =
    HaAction.CallService(on, service, entityId, JsonObject(data))
  fun power() =
    EntityControl.Power(
      on = state.state == "on",
      turnOn = call("turn_on"),
      turnOff = call("turn_off"),
    )
  val features = attrs.long("supported_features") ?: 0L
  fun supports(bit: Long) = features and bit != 0L

  return when (domain) {
    "light" ->
      buildList {
        add(power())
        val modes = attrs.strings("supported_color_modes")
        if (modes.any { it in DIMMABLE_COLOR_MODES } || attrs["brightness"] != null) {
          add(
            EntityControl.Level(
              label = "Brightness",
              value = attrs.double("brightness")?.let { (it / 255.0 * 100).toFloat() },
              range = 1f..100f,
              step = 1f,
              unit = "%",
            ) {
              call("turn_on", mapOf("brightness_pct" to JsonPrimitive(it.roundToInt())))
            }
          )
        }
        val minK = attrs.double("min_color_temp_kelvin")
        val maxK = attrs.double("max_color_temp_kelvin")
        if ("color_temp" in modes && minK != null && maxK != null && maxK > minK) {
          add(
            EntityControl.Level(
              label = "Colour temperature",
              value = attrs.double("color_temp_kelvin")?.toFloat(),
              range = minK.toFloat()..maxK.toFloat(),
              step = 100f,
              unit = "K",
            ) {
              call("turn_on", mapOf("color_temp_kelvin" to JsonPrimitive(it.roundToInt())))
            }
          )
        }
        attrs
          .strings("effect_list")
          .takeIf { it.isNotEmpty() }
          ?.let { effects ->
            add(
              EntityControl.Choice("Effect", effects, attrs.string("effect")) {
                call("turn_on", mapOf("effect" to JsonPrimitive(it)))
              }
            )
          }
      }
    "switch",
    "input_boolean",
    "automation",
    "siren" -> listOf(power())
    "fan" ->
      buildList {
        add(power())
        if (supports(FAN_SET_SPEED) || attrs["percentage"] != null) {
          val step = attrs.double("percentage_step")?.toFloat()?.takeIf { it > 0f } ?: 1f
          add(
            EntityControl.Level(
              "Speed",
              attrs.double("percentage")?.toFloat(),
              0f..100f,
              step,
              "%",
            ) {
              call("set_percentage", mapOf("percentage" to JsonPrimitive(it.roundToInt())))
            }
          )
        }
        attrs
          .strings("preset_modes")
          .takeIf { it.isNotEmpty() }
          ?.let { presets ->
            add(
              EntityControl.Choice("Preset", presets, attrs.string("preset_mode")) {
                call("set_preset_mode", mapOf("preset_mode" to JsonPrimitive(it)))
              }
            )
          }
      }
    "cover" ->
      buildList {
        // A cover that reports no features still takes the three basic commands.
        val basic = features == 0L
        val buttons = buildList {
          if (basic || supports(COVER_OPEN)) {
            add(
              Button(
                "Open",
                EntityControl.ButtonKind.Open,
                call("open_cover"),
                state.state == "open",
              )
            )
          }
          if (basic || supports(COVER_STOP)) {
            add(Button("Stop", EntityControl.ButtonKind.Stop, call("stop_cover")))
          }
          if (basic || supports(COVER_CLOSE)) {
            add(
              Button(
                "Close",
                EntityControl.ButtonKind.Close,
                call("close_cover"),
                state.state == "closed",
              )
            )
          }
        }
        if (buttons.isNotEmpty()) add(EntityControl.Buttons(buttons))
        if (supports(COVER_SET_POSITION)) {
          add(
            EntityControl.Level(
              "Position",
              attrs.double("current_position")?.toFloat(),
              0f..100f,
              1f,
              "%",
            ) {
              call("set_cover_position", mapOf("position" to JsonPrimitive(it.roundToInt())))
            }
          )
        }
      }
    "climate" ->
      buildList {
        if (supports(CLIMATE_TARGET_TEMPERATURE) && attrs["temperature"] != null) {
          val unit = temperatureUnitFor(state, temperatureUnit)
          val step =
            attrs.double("target_temp_step")?.toFloat()?.takeIf { it > 0f }
              ?: if (unit == "°F") 1f else 0.5f
          val min = attrs.double("min_temp")?.toFloat() ?: 7f
          val max = attrs.double("max_temp")?.toFloat()?.takeIf { it > min } ?: 35f
          add(
            EntityControl.Stepper(
              "Target",
              attrs.double("temperature")?.toFloat(),
              min..max,
              step,
              unit,
            ) {
              call("set_temperature", mapOf("temperature" to JsonPrimitive(decimal(it))))
            }
          )
        }
        attrs
          .strings("hvac_modes")
          .takeIf { it.isNotEmpty() }
          ?.let { modes ->
            add(
              EntityControl.Choice("Mode", modes, state.state) {
                call("set_hvac_mode", mapOf("hvac_mode" to JsonPrimitive(it)))
              }
            )
          }
        attrs
          .strings("preset_modes")
          .takeIf { it.isNotEmpty() }
          ?.let { presets ->
            add(
              EntityControl.Choice("Preset", presets, attrs.string("preset_mode")) {
                call("set_preset_mode", mapOf("preset_mode" to JsonPrimitive(it)))
              }
            )
          }
      }
    "humidifier" ->
      buildList {
        add(power())
        if (attrs["humidity"] != null) {
          val min = attrs.double("min_humidity")?.toFloat() ?: 0f
          val max = attrs.double("max_humidity")?.toFloat()?.takeIf { it > min } ?: 100f
          add(
            EntityControl.Level(
              "Target humidity",
              attrs.double("humidity")?.toFloat(),
              min..max,
              1f,
              "%",
            ) {
              call("set_humidity", mapOf("humidity" to JsonPrimitive(it.roundToInt())))
            }
          )
        }
      }
    "lock" ->
      listOf(
        EntityControl.Buttons(
          listOf(
            Button("Lock", EntityControl.ButtonKind.Lock, call("lock"), state.state == "locked"),
            Button(
              "Unlock",
              EntityControl.ButtonKind.Unlock,
              call("unlock"),
              state.state == "unlocked",
            ),
          )
        )
      )
    "media_player" ->
      buildList {
        if (supports(MEDIA_TURN_ON) || supports(MEDIA_TURN_OFF)) {
          add(
            EntityControl.Power(
              on = state.state != "off",
              turnOn = call("turn_on").takeIf { supports(MEDIA_TURN_ON) },
              turnOff = call("turn_off").takeIf { supports(MEDIA_TURN_OFF) },
            )
          )
        }
        val transport = buildList {
          if (supports(MEDIA_PREVIOUS)) {
            add(Button("Previous", EntityControl.ButtonKind.Previous, call("media_previous_track")))
          }
          if (state.state == "playing" && supports(MEDIA_PAUSE)) {
            add(Button("Pause", EntityControl.ButtonKind.Pause, call("media_pause")))
          } else if (supports(MEDIA_PLAY)) {
            add(Button("Play", EntityControl.ButtonKind.Play, call("media_play")))
          }
          if (supports(MEDIA_NEXT)) {
            add(Button("Next", EntityControl.ButtonKind.Next, call("media_next_track")))
          }
        }
        if (transport.isNotEmpty()) add(EntityControl.Buttons(transport))
        if (supports(MEDIA_VOLUME_SET)) {
          add(
            EntityControl.Level(
              "Volume",
              attrs.double("volume_level")?.let { (it * 100).toFloat() },
              0f..100f,
              1f,
              "%",
            ) {
              call("volume_set", mapOf("volume_level" to JsonPrimitive(it.roundToInt() / 100.0)))
            }
          )
        }
        attrs
          .strings("source_list")
          .takeIf { it.isNotEmpty() && supports(MEDIA_SELECT_SOURCE) }
          ?.let {
            add(
              EntityControl.Choice("Source", it, attrs.string("source")) { source ->
                call("select_source", mapOf("source" to JsonPrimitive(source)))
              }
            )
          }
      }
    "vacuum" ->
      listOf(
        EntityControl.Buttons(
          buildList {
            if (state.state == "cleaning" && supports(VACUUM_PAUSE)) {
              add(Button("Pause", EntityControl.ButtonKind.Pause, call("pause")))
            } else {
              add(Button("Start", EntityControl.ButtonKind.Play, call("start")))
            }
            if (supports(VACUUM_RETURN_HOME) || features == 0L) {
              add(
                Button(
                  "Dock",
                  EntityControl.ButtonKind.Dock,
                  call("return_to_base"),
                  state.state == "docked",
                )
              )
            }
          }
        )
      )
    "button",
    "input_button" ->
      listOf(
        EntityControl.Buttons(
          listOf(Button("Press", EntityControl.ButtonKind.Press, call("press")))
        )
      )
    "scene" ->
      listOf(
        EntityControl.Buttons(
          listOf(Button("Activate", EntityControl.ButtonKind.Press, call("turn_on")))
        )
      )
    "script" ->
      listOf(
        EntityControl.Buttons(
          listOf(
            if (state.state == "on") Button("Stop", EntityControl.ButtonKind.Stop, call("turn_off"))
            else Button("Run", EntityControl.ButtonKind.Play, call("turn_on"))
          )
        )
      )
    "number",
    "input_number" -> {
      val min = attrs.double("min")?.toFloat() ?: return emptyList()
      val max = attrs.double("max")?.toFloat()?.takeIf { it > min } ?: return emptyList()
      val step = attrs.double("step")?.toFloat()?.takeIf { it > 0f } ?: 1f
      listOf(
        EntityControl.Level(
          "Value",
          state.state.toFloatOrNull(),
          min..max,
          step,
          attrs.string("unit_of_measurement"),
        ) {
          call("set_value", mapOf("value" to JsonPrimitive(decimal(it))))
        }
      )
    }
    "select",
    "input_select" ->
      attrs
        .strings("options")
        .takeIf { it.isNotEmpty() }
        ?.let { options ->
          listOf(
            EntityControl.Choice("Option", options, state.state) {
              call("select_option", mapOf("option" to JsonPrimitive(it)))
            }
          )
        } ?: emptyList()
    else -> emptyList()
  }
}

/** [value] snapped to [step]s from the start of [range], within it. */
fun snapToStep(value: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
  if (step <= 0f) return value.coerceIn(range)
  val steps = ((value - range.start) / step).roundToInt()
  return (range.start + steps * step).coerceIn(range)
}

/** [value] to three decimals: a stepped float sent as `21.5`, not `21.500002`. */
private fun decimal(value: Float): Double = round(value * 1000.0) / 1000.0

private typealias Button = EntityControl.Button

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.long(key: String): Long? =
  (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.strings(key: String): List<String> =
  (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

private val DIMMABLE_COLOR_MODES =
  setOf("brightness", "color_temp", "hs", "xy", "rgb", "rgbw", "rgbww", "white")

// Home Assistant's `supported_features` bits.
private const val FAN_SET_SPEED = 1L
private const val COVER_OPEN = 1L
private const val COVER_CLOSE = 2L
private const val COVER_SET_POSITION = 4L
private const val COVER_STOP = 8L
private const val CLIMATE_TARGET_TEMPERATURE = 1L
private const val MEDIA_PAUSE = 1L
private const val MEDIA_VOLUME_SET = 4L
private const val MEDIA_PREVIOUS = 16L
private const val MEDIA_NEXT = 32L
private const val MEDIA_TURN_ON = 128L
private const val MEDIA_TURN_OFF = 256L
private const val MEDIA_SELECT_SOURCE = 2048L
private const val MEDIA_PLAY = 16384L
private const val VACUUM_PAUSE = 4L
private const val VACUUM_RETURN_HOME = 16L
