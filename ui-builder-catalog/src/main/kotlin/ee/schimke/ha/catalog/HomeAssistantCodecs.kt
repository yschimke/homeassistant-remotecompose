package ee.schimke.ha.catalog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BlindsClosed
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.SensorOccupied
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Window
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.composeai.discovery.AdapterObjectCodec
import ee.schimke.composeai.discovery.AdapterValueCodec
import ee.schimke.composeai.discovery.AdapterValueCodecs
import ee.schimke.composeai.discovery.ExportProjection
import ee.schimke.composeai.discovery.WireColor
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaBambuPrintPhase
import ee.schimke.ha.rc.components.HaHeadingStyle
import ee.schimke.ha.rc.components.haServiceData
import ee.schimke.ha.rc.ui.HaAlarmActionUiData
import ee.schimke.ha.rc.ui.HaAlarmPanelUiData
import ee.schimke.ha.rc.ui.HaAlarmStatusUiData
import ee.schimke.ha.rc.ui.HaArcDialStyle
import ee.schimke.ha.rc.ui.HaArcDialUiData
import ee.schimke.ha.rc.ui.HaAreaActionUiData
import ee.schimke.ha.rc.ui.HaAreaCardUiData
import ee.schimke.ha.rc.ui.HaAreaStatUiData
import ee.schimke.ha.rc.ui.HaBambuAmsUiData
import ee.schimke.ha.rc.ui.HaBambuControlButtonUiData
import ee.schimke.ha.rc.ui.HaBambuPrintControlUiData
import ee.schimke.ha.rc.ui.HaBambuPrintStatusUiData
import ee.schimke.ha.rc.ui.HaBambuSpoolDetailUiData
import ee.schimke.ha.rc.ui.HaBambuSpoolSlotUiData
import ee.schimke.ha.rc.ui.HaButtonUiData
import ee.schimke.ha.rc.ui.HaCalendarEventUiData
import ee.schimke.ha.rc.ui.HaCalendarUiData
import ee.schimke.ha.rc.ui.HaCardLayout
import ee.schimke.ha.rc.ui.HaClockUiData
import ee.schimke.ha.rc.ui.HaEntitiesUiData
import ee.schimke.ha.rc.ui.HaEntityRowUiData
import ee.schimke.ha.rc.ui.HaGaugeBandsUiData
import ee.schimke.ha.rc.ui.HaGaugeStyle
import ee.schimke.ha.rc.ui.HaGaugeUiData
import ee.schimke.ha.rc.ui.HaGlanceCellUiData
import ee.schimke.ha.rc.ui.HaGlanceUiData
import ee.schimke.ha.rc.ui.HaHeadingUiData
import ee.schimke.ha.rc.ui.HaHistoryGraphRowUiData
import ee.schimke.ha.rc.ui.HaHistoryGraphUiData
import ee.schimke.ha.rc.ui.HaLogbookEntryUiData
import ee.schimke.ha.rc.ui.HaLogbookUiData
import ee.schimke.ha.rc.ui.HaMarkdownUiData
import ee.schimke.ha.rc.ui.HaMediaControlUiData
import ee.schimke.ha.rc.ui.HaModeChipUiData
import ee.schimke.ha.rc.ui.HaPictureCardUiData
import ee.schimke.ha.rc.ui.HaPictureElementPositionUiData
import ee.schimke.ha.rc.ui.HaPictureElementUiData
import ee.schimke.ha.rc.ui.HaPictureElementsUiData
import ee.schimke.ha.rc.ui.HaPictureEntityUiData
import ee.schimke.ha.rc.ui.HaPictureGlanceCellUiData
import ee.schimke.ha.rc.ui.HaPictureGlanceUiData
import ee.schimke.ha.rc.ui.HaSensorCardUiData
import ee.schimke.ha.rc.ui.HaStatisticCardUiData
import ee.schimke.ha.rc.ui.HaStatisticsChartType
import ee.schimke.ha.rc.ui.HaStatisticsGraphUiData
import ee.schimke.ha.rc.ui.HaTileUiData
import ee.schimke.ha.rc.ui.HaTodoItemUiData
import ee.schimke.ha.rc.ui.HaTodoListUiData
import ee.schimke.ha.rc.ui.HaToggleAccentUi
import ee.schimke.ha.rc.ui.HaUnsupportedUiData
import ee.schimke.ha.rc.ui.HaWeatherDayUiData
import ee.schimke.ha.rc.ui.HaWeatherExtraUiData
import ee.schimke.ha.rc.ui.HaWeatherForecastUiData
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Wire codecs for the real Tier-2 `Ha*UiData` models. Each `obj` codec names the real model class
 * and decodes straight into it, so a registered adapter calls the actual card composable with the
 * value a design stores. The non-scalar leaves are mapped codecs: they publish exactly what the
 * typed API's own codec publishes (a Material icon key, a colour string, an enum member) and
 * resolve it to the Compose value here, in the catalog's own Android process.
 */

private const val UI = "ee.schimke.ha.rc.ui"
private const val COMPONENTS = "ee.schimke.ha.rc.components"

/**
 * A codec publishing [wire]'s shape, export and validation, decoding through [read] to the value
 * the real model holds. [write] must invert [read] on every value the catalog stores.
 */
private class MappedCodec<W, T>(
  private val wire: AdapterValueCodec<W>,
  private val read: (W) -> T,
  private val write: (T) -> W,
) : AdapterValueCodec<T> {
  // Every member is overridden, defaults included: the codec interface is compiled into both
  // `screen-model` and `preview-discovery` with different default-method strategies, so a call to
  // an interface default from app code can find the other copy abstract at run time.
  override val jsonType: String = wire.jsonType
  override val kotlinType: String = wire.kotlinType
  override val literalType: String = wire.literalType
  override val stateType: String? = wire.stateType
  override val exportCodec: ExportProjection.Codec? = wire.exportCodec
  override val allowedValues: Set<String>? = wire.allowedValues
  override val format: String? = wire.format
  override val nullable: Boolean = wire.nullable
  override val editor: JsonObject? = wire.editor

  override fun shape(): JsonObject = wire.shape()

  override fun encode(value: T): JsonElement = wire.encode(write(value))

  override fun decode(value: JsonElement): T = read(wire.decode(value))

  override fun wrap(value: T): JsonObject = wire.wrap(write(value))

  override fun unwrap(wrapper: JsonObject): T = read(wire.unwrap(wrapper))
}

// ——— icons ———

/**
 * The closed icon set a design may pick, keyed as the UI builder's Material icon table keys them
 * (`filled/lightbulb`): the Material glyphs the Tier-2 preview fixtures and catalog defaults draw.
 * Live cards draw Home Assistant's own Material Design Icons instead (`rc-converter`'s
 * `HaIconMap`), which this table doesn't list.
 */
val HomeAssistantIcons: Map<String, ImageVector> =
  listOf(
      Icons.Filled.AcUnit,
      Icons.Filled.Air,
      Icons.Filled.Alarm,
      Icons.Filled.Battery4Bar,
      Icons.Filled.BlindsClosed,
      Icons.Filled.CleaningServices,
      Icons.Filled.Cloud,
      Icons.Filled.Devices,
      Icons.Filled.DoorFront,
      Icons.Filled.ElectricBolt,
      Icons.Filled.EnergySavingsLeaf,
      Icons.Filled.Garage,
      Icons.Filled.Home,
      Icons.Filled.Image,
      Icons.Filled.Lightbulb,
      Icons.Filled.Lock,
      Icons.Filled.LockOpen,
      Icons.Filled.Mic,
      Icons.Filled.Mood,
      Icons.Filled.MusicNote,
      Icons.Filled.Pause,
      Icons.Filled.PlayArrow,
      Icons.Filled.PowerSettingsNew,
      Icons.Filled.Refresh,
      Icons.Filled.RssFeed,
      Icons.Filled.SensorDoor,
      Icons.Filled.SensorOccupied,
      Icons.Filled.SettingsInputComponent,
      Icons.Filled.Shield,
      Icons.Filled.Speaker,
      Icons.Filled.Stop,
      Icons.Filled.Thermostat,
      Icons.Filled.Tv,
      Icons.Filled.Umbrella,
      Icons.Filled.Videocam,
      Icons.Filled.WaterDrop,
      Icons.Filled.WbCloudy,
      Icons.Filled.WbSunny,
      Icons.Filled.Wifi,
      Icons.Filled.Window,
    )
    .associateBy(::iconKey)

/** `Filled.LockOpen` → `filled/lockOpen`, the builder's icon-table key. */
private fun iconKey(icon: ImageVector): String {
  val (style, member) = icon.name.split('.', limit = 2)
  return style.lowercase() + "/" + member.replaceFirstChar { it.lowercase() }
}

/**
 * An `ImageVector` field: published and exported as [AdapterValueCodecs.materialIcon] over
 * [HomeAssistantIcons]'s keys, resolved to the glyph when decoded.
 */
val IconCodec: AdapterValueCodec<ImageVector> =
  MappedCodec(
    AdapterValueCodecs.materialIcon(HomeAssistantIcons.keys),
    read = { HomeAssistantIcons.getValue(it) },
    write = { icon ->
      iconKey(icon).also {
        require(it in HomeAssistantIcons) { "${icon.name} is not in the catalog's icon set" }
      }
    },
  )

// ——— colours ———

/**
 * The colour export: `Color(0xAARRGGBB)` literals. No theme roles are offered (an empty token set):
 * the cards draw on the Home Assistant Remote Compose theme, not a Material colour scheme, so a
 * Material role would resolve against the wrong palette.
 */
private val ColorExport =
  ExportProjection.Codec.Color(
    schemeRootFqn = "androidx.compose.material3.MaterialTheme",
    tokens = emptySet(),
  )

/**
 * A `Color` field: `#RRGGBB` / `#AARRGGBB` or `transparent`, as [AdapterValueCodecs.color] stores
 * it. Every card colour is required, so the empty "component default" is refused.
 */
val ColorCodec: AdapterValueCodec<Color> =
  MappedCodec(
    AdapterValueCodecs.color(ColorExport),
    read = { wire ->
      when (wire) {
        is WireColor.Argb -> Color(wire.argb.toInt())
        WireColor.Transparent -> Color.Transparent
        WireColor.Unset -> throw IllegalArgumentException("a card colour is required")
        is WireColor.Role -> throw IllegalArgumentException("`${wire.token}` is not a hex colour")
      }
    },
    write = { color ->
      if (color == Color.Transparent) WireColor.Transparent
      else WireColor.Argb(color.toArgb().toLong() and 0xFFFFFFFFL)
    },
  )

// ——— enums ———

/** A Kotlin enum as [AdapterValueCodecs.enumOf], its wire values the members' lower-camel names. */
inline fun <reified E : Enum<E>> enumCodec(typeFqn: String): AdapterValueCodec<E> {
  val members = enumValues<E>().associateBy { it.name.replaceFirstChar { c -> c.lowercase() } }
  return mappedCodec(
    AdapterValueCodecs.enumOf(typeFqn, members.mapValues { (_, e) -> "$typeFqn.${e.name}" }),
    read = { members.getValue(it) },
    write = { it.name.replaceFirstChar { c -> c.lowercase() } },
  )
}

/** Public entry to [MappedCodec] for [enumCodec]'s inline body. */
@PublishedApi
internal fun <W, T> mappedCodec(
  wire: AdapterValueCodec<W>,
  read: (W) -> T,
  write: (T) -> W,
): AdapterValueCodec<T> = MappedCodec(wire, read, write)

val HeadingStyleCodec = enumCodec<HaHeadingStyle>("$COMPONENTS.HaHeadingStyle")
val GaugeStyleCodec = enumCodec<HaGaugeStyle>("$UI.HaGaugeStyle")
val ArcDialStyleCodec = enumCodec<HaArcDialStyle>("$UI.HaArcDialStyle")
val CardLayoutCodec = enumCodec<HaCardLayout>("$UI.HaCardLayout")
val StatisticsChartTypeCodec = enumCodec<HaStatisticsChartType>("$UI.HaStatisticsChartType")
val BambuPrintPhaseCodec = enumCodec<HaBambuPrintPhase>("$COMPONENTS.HaBambuPrintPhase")

// ——— shorthands ———

private val Text = AdapterValueCodecs.String
private val Bool = AdapterValueCodecs.Boolean
private val OptionalText = AdapterValueCodecs.nullable(AdapterValueCodecs.String)

// ——— actions ———

/**
 * [HaAction] as a union of every case. `callService.serviceData` is the service-data JSON object as
 * text (format `json`), parsed with [haServiceData] — the export writes the same call — and refused
 * when it is not an object.
 */
val ServiceDataCodec: AdapterValueCodec<JsonObject> =
  AdapterValueCodecs.parsed(
    "kotlinx.serialization.json.JsonObject",
    "$COMPONENTS.haServiceData",
    "json",
    ::haServiceData,
    { it.toString() },
  )

val HaActionCodec: AdapterValueCodec<HaAction> =
  AdapterValueCodecs.union("$COMPONENTS.HaAction") {
    singleton("none", HaAction.None, "$COMPONENTS.HaAction.None")
    case(
      "toggle",
      AdapterValueCodecs.obj<HaAction.Toggle>("$COMPONENTS.HaAction.Toggle") {
        val entityId = field(HaAction.Toggle::entityId, Text)
        construct { HaAction.Toggle(it[entityId]) }
      },
    )
    case(
      "moreInfo",
      AdapterValueCodecs.obj<HaAction.MoreInfo>("$COMPONENTS.HaAction.MoreInfo") {
        val entityId = field(HaAction.MoreInfo::entityId, Text)
        construct { HaAction.MoreInfo(it[entityId]) }
      },
    )
    case(
      "navigate",
      AdapterValueCodecs.obj<HaAction.Navigate>("$COMPONENTS.HaAction.Navigate") {
        val path = field(HaAction.Navigate::path, Text)
        construct { HaAction.Navigate(it[path]) }
      },
    )
    case(
      "url",
      AdapterValueCodecs.obj<HaAction.Url>("$COMPONENTS.HaAction.Url") {
        val url = field(HaAction.Url::url, Text)
        construct { HaAction.Url(it[url]) }
      },
    )
    case(
      "callService",
      AdapterValueCodecs.obj<HaAction.CallService>("$COMPONENTS.HaAction.CallService") {
        val domain = field(HaAction.CallService::domain, Text)
        val service = field(HaAction.CallService::service, Text)
        val entityId = field(HaAction.CallService::entityId, OptionalText, default = null)
        val serviceData =
          field(
            HaAction.CallService::serviceData,
            ServiceDataCodec,
            default = JsonObject(emptyMap()),
          )
        construct { HaAction.CallService(it[domain], it[service], it[entityId], it[serviceData]) }
      },
    )
    case(
      "alarmKey",
      AdapterValueCodecs.obj<HaAction.AlarmKey>("$COMPONENTS.HaAction.AlarmKey") {
        val entityId = field(HaAction.AlarmKey::entityId, Text)
        val key = field(HaAction.AlarmKey::key, Text)
        construct { HaAction.AlarmKey(it[entityId], it[key]) }
      },
    )
    case(
      "alarmPin",
      AdapterValueCodecs.obj<HaAction.AlarmPin>("$COMPONENTS.HaAction.AlarmPin") {
        val entityId = field(HaAction.AlarmPin::entityId, Text)
        val pin = field(HaAction.AlarmPin::pin, Text)
        construct { HaAction.AlarmPin(it[entityId], it[pin]) }
      },
    )
    case(
      "alarmIntent",
      AdapterValueCodecs.obj<HaAction.AlarmIntent>("$COMPONENTS.HaAction.AlarmIntent") {
        val entityId = field(HaAction.AlarmIntent::entityId, Text)
        val service = field(HaAction.AlarmIntent::service, Text)
        val codeLength =
          field(
            HaAction.AlarmIntent::codeLength,
            AdapterValueCodecs.nullable(AdapterValueCodecs.Int),
            default = null,
          )
        construct { HaAction.AlarmIntent(it[entityId], it[service], it[codeLength]) }
      },
    )
  }

// ——— shared models ———

val ToggleAccentCodec: AdapterObjectCodec<HaToggleAccentUi> =
  AdapterValueCodecs.obj("$UI.HaToggleAccentUi") {
    val active = field(HaToggleAccentUi::activeAccent, ColorCodec)
    val inactive = field(HaToggleAccentUi::inactiveAccent, ColorCodec)
    val isOn = field(HaToggleAccentUi::isOn, AdapterValueCodecs.nullable(Bool), default = null)
    construct { HaToggleAccentUi(it[active], it[inactive], it[isOn]) }
  }

// ——— core cards ———

val TileCodec: AdapterObjectCodec<HaTileUiData> =
  AdapterValueCodecs.obj("$UI.HaTileUiData") {
    val name = field(HaTileUiData::name, Text)
    val state = field(HaTileUiData::state, Text, default = "")
    val icon = field(HaTileUiData::icon, IconCodec)
    val accent = field(HaTileUiData::accent, ToggleAccentCodec)
    val tap = field(HaTileUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct { HaTileUiData(it[name], it[state], it[icon], it[accent], it[tap]) }
  }

val ButtonCodec: AdapterObjectCodec<HaButtonUiData> =
  AdapterValueCodecs.obj("$UI.HaButtonUiData") {
    val name = field(HaButtonUiData::name, Text)
    val icon = field(HaButtonUiData::icon, IconCodec)
    val accent = field(HaButtonUiData::accent, ToggleAccentCodec)
    val showName = field(HaButtonUiData::showName, Bool, default = true)
    val tap = field(HaButtonUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct { HaButtonUiData(it[name], it[icon], it[accent], it[showName], it[tap]) }
  }

val EntityRowCodec: AdapterObjectCodec<HaEntityRowUiData> =
  AdapterValueCodecs.obj("$UI.HaEntityRowUiData") {
    val name = field(HaEntityRowUiData::name, Text)
    val state = field(HaEntityRowUiData::state, Text, default = "")
    val icon = field(HaEntityRowUiData::icon, IconCodec)
    val accent = field(HaEntityRowUiData::accent, ToggleAccentCodec)
    val tap = field(HaEntityRowUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct { HaEntityRowUiData(it[name], it[state], it[icon], it[accent], it[tap]) }
  }

val EntitiesCodec: AdapterObjectCodec<HaEntitiesUiData> =
  AdapterValueCodecs.obj("$UI.HaEntitiesUiData") {
    val title = field(HaEntitiesUiData::title, OptionalText, default = null)
    val rows = field(HaEntitiesUiData::rows, AdapterValueCodecs.list(EntityRowCodec))
    construct { HaEntitiesUiData(it[title], it[rows]) }
  }

val GlanceCellCodec: AdapterObjectCodec<HaGlanceCellUiData> =
  AdapterValueCodecs.obj("$UI.HaGlanceCellUiData") {
    val name = field(HaGlanceCellUiData::name, Text)
    val state = field(HaGlanceCellUiData::state, Text, default = "")
    val icon = field(HaGlanceCellUiData::icon, IconCodec)
    val accent = field(HaGlanceCellUiData::accent, ToggleAccentCodec)
    val tap = field(HaGlanceCellUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct { HaGlanceCellUiData(it[name], it[state], it[icon], it[accent], it[tap]) }
  }

val GlanceCodec: AdapterObjectCodec<HaGlanceUiData> =
  AdapterValueCodecs.obj("$UI.HaGlanceUiData") {
    val title = field(HaGlanceUiData::title, OptionalText, default = null)
    val cells = field(HaGlanceUiData::cells, AdapterValueCodecs.list(GlanceCellCodec))
    construct { HaGlanceUiData(it[title], it[cells]) }
  }

val MarkdownCodec: AdapterObjectCodec<HaMarkdownUiData> =
  AdapterValueCodecs.obj("$UI.HaMarkdownUiData") {
    val title = field(HaMarkdownUiData::title, OptionalText, default = null)
    val lines = field(HaMarkdownUiData::lines, AdapterValueCodecs.list(Text))
    construct { HaMarkdownUiData(it[title], it[lines]) }
  }

val HeadingCodec: AdapterObjectCodec<HaHeadingUiData> =
  AdapterValueCodecs.obj("$UI.HaHeadingUiData") {
    val title = field(HaHeadingUiData::title, Text)
    val style = field(HaHeadingUiData::style, HeadingStyleCodec, default = HaHeadingStyle.Title)
    construct { HaHeadingUiData(it[title], it[style]) }
  }

val UnsupportedCodec: AdapterObjectCodec<HaUnsupportedUiData> =
  AdapterValueCodecs.obj("$UI.HaUnsupportedUiData") {
    val cardType = field(HaUnsupportedUiData::cardType, Text)
    construct { HaUnsupportedUiData(it[cardType]) }
  }

// ——— gauge / arc dial ———

private val OptionalDouble = AdapterValueCodecs.nullable(AdapterValueCodecs.Double)

val GaugeBandsCodec: AdapterObjectCodec<HaGaugeBandsUiData> =
  AdapterValueCodecs.obj("$UI.HaGaugeBandsUiData") {
    val green = field(HaGaugeBandsUiData::green, OptionalDouble, default = null)
    val yellow = field(HaGaugeBandsUiData::yellow, OptionalDouble, default = null)
    val red = field(HaGaugeBandsUiData::red, OptionalDouble, default = null)
    construct { HaGaugeBandsUiData(it[green], it[yellow], it[red]) }
  }

val GaugeCodec: AdapterObjectCodec<HaGaugeUiData> =
  AdapterValueCodecs.obj("$UI.HaGaugeUiData") {
    val name = field(HaGaugeUiData::name, Text)
    val valueText = field(HaGaugeUiData::valueText, Text)
    val unit = field(HaGaugeUiData::unit, OptionalText, default = null)
    val value = field(HaGaugeUiData::value, AdapterValueCodecs.Float)
    val min = field(HaGaugeUiData::min, AdapterValueCodecs.Double, default = 0.0)
    val max = field(HaGaugeUiData::max, AdapterValueCodecs.Double, default = 100.0)
    val severity = field(HaGaugeUiData::severity, GaugeBandsCodec, default = HaGaugeBandsUiData())
    val tap = field(HaGaugeUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct {
      HaGaugeUiData(
        it[name],
        it[valueText],
        it[unit],
        it[value],
        it[min],
        it[max],
        it[severity],
        it[tap],
      )
    }
  }

val ModeChipCodec: AdapterValueCodec<HaModeChipUiData> =
  AdapterValueCodecs.union("$UI.HaModeChipUiData") {
    case(
      "static",
      AdapterValueCodecs.obj<HaModeChipUiData.Static>("$UI.HaModeChipUiData.Static") {
        val initial = field(HaModeChipUiData.Static::initial, Text)
        construct { HaModeChipUiData.Static(it[initial]) }
      },
    )
    case(
      "toggle",
      AdapterValueCodecs.obj<HaModeChipUiData.Toggle>("$UI.HaModeChipUiData.Toggle") {
        val initiallyOn = field(HaModeChipUiData.Toggle::initiallyOn, Bool, default = false)
        val onLabel = field(HaModeChipUiData.Toggle::onLabel, Text)
        val offLabel = field(HaModeChipUiData.Toggle::offLabel, Text)
        construct { HaModeChipUiData.Toggle(it[initiallyOn], it[onLabel], it[offLabel]) }
      },
    )
  }

val ArcDialCodec: AdapterObjectCodec<HaArcDialUiData> =
  AdapterValueCodecs.obj("$UI.HaArcDialUiData") {
    val name = field(HaArcDialUiData::name, Text)
    val valueFraction = field(HaArcDialUiData::valueFraction, AdapterValueCodecs.Float)
    val targetFraction =
      field(
        HaArcDialUiData::targetFraction,
        AdapterValueCodecs.nullable(AdapterValueCodecs.Float),
        default = null,
      )
    val centerLabel = field(HaArcDialUiData::centerLabel, Text)
    val supportingLabel = field(HaArcDialUiData::supportingLabel, OptionalText, default = null)
    val modeChip =
      field(HaArcDialUiData::modeChip, AdapterValueCodecs.nullable(ModeChipCodec), default = null)
    val accent = field(HaArcDialUiData::accent, ColorCodec)
    val showSteppers = field(HaArcDialUiData::showSteppers, Bool, default = false)
    val centerIcon =
      field(HaArcDialUiData::centerIcon, AdapterValueCodecs.nullable(IconCodec), default = null)
    val tap = field(HaArcDialUiData::tapAction, HaActionCodec, default = HaAction.None)
    val increment = field(HaArcDialUiData::incrementAction, HaActionCodec, default = HaAction.None)
    val decrement = field(HaArcDialUiData::decrementAction, HaActionCodec, default = HaAction.None)
    construct {
      HaArcDialUiData(
        name = it[name],
        valueFraction = it[valueFraction],
        targetFraction = it[targetFraction],
        centerLabel = it[centerLabel],
        supportingLabel = it[supportingLabel],
        modeChip = it[modeChip],
        accent = it[accent],
        showSteppers = it[showSteppers],
        centerIcon = it[centerIcon],
        tapAction = it[tap],
        incrementAction = it[increment],
        decrementAction = it[decrement],
      )
    }
  }

// ——— graphs ———

private val Points = AdapterValueCodecs.list(AdapterValueCodecs.Float)

val HistoryGraphRowCodec: AdapterObjectCodec<HaHistoryGraphRowUiData> =
  AdapterValueCodecs.obj("$UI.HaHistoryGraphRowUiData") {
    val name = field(HaHistoryGraphRowUiData::name, Text)
    val summary = field(HaHistoryGraphRowUiData::summary, Text, default = "")
    val accent = field(HaHistoryGraphRowUiData::accent, ColorCodec)
    val points = field(HaHistoryGraphRowUiData::points, Points)
    construct { HaHistoryGraphRowUiData(it[name], it[summary], it[accent], it[points]) }
  }

private val HistoryRows = AdapterValueCodecs.list(HistoryGraphRowCodec)

val HistoryGraphCodec: AdapterObjectCodec<HaHistoryGraphUiData> =
  AdapterValueCodecs.obj("$UI.HaHistoryGraphUiData") {
    val title = field(HaHistoryGraphUiData::title, OptionalText, default = null)
    val rangeLabel = field(HaHistoryGraphUiData::rangeLabel, Text)
    val rows = field(HaHistoryGraphUiData::rows, HistoryRows)
    construct { HaHistoryGraphUiData(it[title], it[rangeLabel], it[rows]) }
  }

val StatisticsGraphCodec: AdapterObjectCodec<HaStatisticsGraphUiData> =
  AdapterValueCodecs.obj("$UI.HaStatisticsGraphUiData") {
    val title = field(HaStatisticsGraphUiData::title, OptionalText, default = null)
    val rangeLabel = field(HaStatisticsGraphUiData::rangeLabel, Text)
    val rows = field(HaStatisticsGraphUiData::rows, HistoryRows)
    val chartType =
      field(
        HaStatisticsGraphUiData::chartType,
        StatisticsChartTypeCodec,
        default = HaStatisticsChartType.Line,
      )
    val stacked = field(HaStatisticsGraphUiData::stacked, Bool, default = false)
    construct {
      HaStatisticsGraphUiData(it[title], it[rangeLabel], it[rows], it[chartType], it[stacked])
    }
  }

val StatisticCardCodec: AdapterObjectCodec<HaStatisticCardUiData> =
  AdapterValueCodecs.obj("$UI.HaStatisticCardUiData") {
    val name = field(HaStatisticCardUiData::name, Text)
    val valueLabel = field(HaStatisticCardUiData::valueLabel, Text)
    val unit = field(HaStatisticCardUiData::unit, OptionalText, default = null)
    val periodLabel = field(HaStatisticCardUiData::periodLabel, OptionalText, default = null)
    val accent = field(HaStatisticCardUiData::accent, ColorCodec)
    construct {
      HaStatisticCardUiData(it[name], it[valueLabel], it[unit], it[periodLabel], it[accent])
    }
  }

val SensorCardCodec: AdapterObjectCodec<HaSensorCardUiData> =
  AdapterValueCodecs.obj("$UI.HaSensorCardUiData") {
    val name = field(HaSensorCardUiData::name, Text)
    val valueLabel = field(HaSensorCardUiData::valueLabel, Text)
    val accent = field(HaSensorCardUiData::accent, ColorCodec)
    val points = field(HaSensorCardUiData::points, Points)
    val rangeLabel = field(HaSensorCardUiData::rangeLabel, OptionalText, default = null)
    construct {
      HaSensorCardUiData(it[name], it[valueLabel], it[accent], it[points], it[rangeLabel])
    }
  }

// ——— logbook / calendar / todo ———

val LogbookEntryCodec: AdapterObjectCodec<HaLogbookEntryUiData> =
  AdapterValueCodecs.obj("$UI.HaLogbookEntryUiData") {
    val name = field(HaLogbookEntryUiData::name, Text)
    val message = field(HaLogbookEntryUiData::message, Text)
    val whenText = field(HaLogbookEntryUiData::whenText, Text)
    val icon = field(HaLogbookEntryUiData::icon, IconCodec)
    construct { HaLogbookEntryUiData(it[name], it[message], it[whenText], it[icon]) }
  }

val LogbookCodec: AdapterObjectCodec<HaLogbookUiData> =
  AdapterValueCodecs.obj("$UI.HaLogbookUiData") {
    val title = field(HaLogbookUiData::title, OptionalText, default = null)
    val entries = field(HaLogbookUiData::entries, AdapterValueCodecs.list(LogbookEntryCodec))
    construct { HaLogbookUiData(it[title], it[entries]) }
  }

val CalendarEventCodec: AdapterObjectCodec<HaCalendarEventUiData> =
  AdapterValueCodecs.obj("$UI.HaCalendarEventUiData") {
    val whenLabel = field(HaCalendarEventUiData::whenLabel, Text)
    val summary = field(HaCalendarEventUiData::summary, Text)
    val accent = field(HaCalendarEventUiData::accent, ColorCodec)
    construct { HaCalendarEventUiData(it[whenLabel], it[summary], it[accent]) }
  }

val CalendarCodec: AdapterObjectCodec<HaCalendarUiData> =
  AdapterValueCodecs.obj("$UI.HaCalendarUiData") {
    val title = field(HaCalendarUiData::title, Text)
    val rangeLabel = field(HaCalendarUiData::rangeLabel, Text)
    val events = field(HaCalendarUiData::events, AdapterValueCodecs.list(CalendarEventCodec))
    construct { HaCalendarUiData(it[title], it[rangeLabel], it[events]) }
  }

val TodoItemCodec: AdapterObjectCodec<HaTodoItemUiData> =
  AdapterValueCodecs.obj("$UI.HaTodoItemUiData") {
    val summary = field(HaTodoItemUiData::summary, Text)
    val tap = field(HaTodoItemUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct { HaTodoItemUiData(it[summary], it[tap]) }
  }

val TodoListCodec: AdapterObjectCodec<HaTodoListUiData> =
  AdapterValueCodecs.obj("$UI.HaTodoListUiData") {
    val items = AdapterValueCodecs.list(TodoItemCodec)
    val title = field(HaTodoListUiData::title, Text)
    val active = field(HaTodoListUiData::activeItems, items)
    val completed = field(HaTodoListUiData::completedItems, items, default = emptyList())
    construct { HaTodoListUiData(it[title], it[active], it[completed]) }
  }

// ——— weather / media / alarm ———

val WeatherDayCodec: AdapterObjectCodec<HaWeatherDayUiData> =
  AdapterValueCodecs.obj("$UI.HaWeatherDayUiData") {
    val label = field(HaWeatherDayUiData::label, Text)
    val high = field(HaWeatherDayUiData::high, Text)
    val low = field(HaWeatherDayUiData::low, Text)
    val icon = field(HaWeatherDayUiData::icon, IconCodec)
    construct { HaWeatherDayUiData(it[label], it[high], it[low], it[icon]) }
  }

val WeatherExtraCodec: AdapterObjectCodec<HaWeatherExtraUiData> =
  AdapterValueCodecs.obj("$UI.HaWeatherExtraUiData") {
    val icon = field(HaWeatherExtraUiData::icon, IconCodec)
    val label = field(HaWeatherExtraUiData::label, Text)
    val value = field(HaWeatherExtraUiData::value, Text)
    construct { HaWeatherExtraUiData(it[icon], it[label], it[value]) }
  }

val WeatherForecastCodec: AdapterObjectCodec<HaWeatherForecastUiData> =
  AdapterValueCodecs.obj("$UI.HaWeatherForecastUiData") {
    val name = field(HaWeatherForecastUiData::name, Text)
    val condition = field(HaWeatherForecastUiData::condition, Text)
    val temperature = field(HaWeatherForecastUiData::temperature, Text)
    val supportingLine =
      field(HaWeatherForecastUiData::supportingLine, OptionalText, default = null)
    val highLow = field(HaWeatherForecastUiData::highLow, OptionalText, default = null)
    val icon = field(HaWeatherForecastUiData::icon, IconCodec)
    val days =
      field(
        HaWeatherForecastUiData::days,
        AdapterValueCodecs.list(WeatherDayCodec),
        default = emptyList(),
      )
    val extras =
      field(
        HaWeatherForecastUiData::extras,
        AdapterValueCodecs.list(WeatherExtraCodec),
        default = emptyList(),
      )
    construct {
      HaWeatherForecastUiData(
        name = it[name],
        condition = it[condition],
        temperature = it[temperature],
        supportingLine = it[supportingLine],
        highLow = it[highLow],
        icon = it[icon],
        days = it[days],
        extras = it[extras],
      )
    }
  }

val MediaControlCodec: AdapterObjectCodec<HaMediaControlUiData> =
  AdapterValueCodecs.obj("$UI.HaMediaControlUiData") {
    val playerName = field(HaMediaControlUiData::playerName, Text)
    val title = field(HaMediaControlUiData::title, Text)
    val artist = field(HaMediaControlUiData::artist, OptionalText, default = null)
    val accent = field(HaMediaControlUiData::accent, ColorCodec)
    val isPlaying = field(HaMediaControlUiData::isPlaying, Bool, default = false)
    val position =
      field(HaMediaControlUiData::positionFraction, AdapterValueCodecs.Float, default = 0f)
    val positionLabel = field(HaMediaControlUiData::positionLabel, OptionalText, default = null)
    val durationLabel = field(HaMediaControlUiData::durationLabel, OptionalText, default = null)
    val previous =
      field(HaMediaControlUiData::previousAction, HaActionCodec, default = HaAction.None)
    val playPause =
      field(HaMediaControlUiData::playPauseAction, HaActionCodec, default = HaAction.None)
    val next = field(HaMediaControlUiData::nextAction, HaActionCodec, default = HaAction.None)
    construct {
      HaMediaControlUiData(
        playerName = it[playerName],
        title = it[title],
        artist = it[artist],
        accent = it[accent],
        isPlaying = it[isPlaying],
        positionFraction = it[position],
        positionLabel = it[positionLabel],
        durationLabel = it[durationLabel],
        previousAction = it[previous],
        playPauseAction = it[playPause],
        nextAction = it[next],
      )
    }
  }

val AlarmStatusCodec: AdapterObjectCodec<HaAlarmStatusUiData> =
  AdapterValueCodecs.obj("$UI.HaAlarmStatusUiData") {
    val stateKey = field(HaAlarmStatusUiData::stateKey, AdapterValueCodecs.Int)
    val label = field(HaAlarmStatusUiData::label, Text)
    val accent = field(HaAlarmStatusUiData::accent, ColorCodec)
    val icon = field(HaAlarmStatusUiData::icon, IconCodec)
    construct { HaAlarmStatusUiData(it[stateKey], it[label], it[accent], it[icon]) }
  }

val AlarmActionCodec: AdapterObjectCodec<HaAlarmActionUiData> =
  AdapterValueCodecs.obj("$UI.HaAlarmActionUiData") {
    val label = field(HaAlarmActionUiData::label, Text)
    val accent = field(HaAlarmActionUiData::accent, ColorCodec)
    val tap = field(HaAlarmActionUiData::tapAction, HaActionCodec)
    construct { HaAlarmActionUiData(it[label], it[accent], it[tap]) }
  }

val AlarmPanelCodec: AdapterObjectCodec<HaAlarmPanelUiData> =
  AdapterValueCodecs.obj("$UI.HaAlarmPanelUiData") {
    val title = field(HaAlarmPanelUiData::title, Text)
    val initialState = field(HaAlarmPanelUiData::initialStateInt, AdapterValueCodecs.Int)
    val statuses = field(HaAlarmPanelUiData::statuses, AdapterValueCodecs.list(AlarmStatusCodec))
    val actions =
      field(
        HaAlarmPanelUiData::actions,
        AdapterValueCodecs.list(AlarmActionCodec),
        default = emptyList(),
      )
    val showKeypad = field(HaAlarmPanelUiData::showKeypad, Bool, default = false)
    val codeLength =
      field(
        HaAlarmPanelUiData::codeLength,
        AdapterValueCodecs.nullable(AdapterValueCodecs.Int),
        default = null,
      )
    // The model's own invariants (non-empty, distinct state keys) refuse a malformed panel.
    construct {
      HaAlarmPanelUiData(
        it[title],
        it[initialState],
        it[statuses],
        it[actions],
        it[showKeypad],
        it[codeLength],
      )
    }
  }

// ——— area / clock ———

val AreaStatCodec: AdapterObjectCodec<HaAreaStatUiData> =
  AdapterValueCodecs.obj("$UI.HaAreaStatUiData") {
    val icon = field(HaAreaStatUiData::icon, IconCodec)
    val label = field(HaAreaStatUiData::label, Text)
    construct { HaAreaStatUiData(it[icon], it[label]) }
  }

val AreaActionCodec: AdapterObjectCodec<HaAreaActionUiData> =
  AdapterValueCodecs.obj("$UI.HaAreaActionUiData") {
    val icon = field(HaAreaActionUiData::icon, IconCodec)
    val accent = field(HaAreaActionUiData::accent, ColorCodec)
    val active = field(HaAreaActionUiData::initiallyActive, Bool, default = false)
    val tap = field(HaAreaActionUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct { HaAreaActionUiData(it[icon], it[accent], it[active], it[tap]) }
  }

val AreaCodec: AdapterObjectCodec<HaAreaCardUiData> =
  AdapterValueCodecs.obj("$UI.HaAreaCardUiData") {
    val name = field(HaAreaCardUiData::name, Text)
    val stats = field(HaAreaCardUiData::stats, AdapterValueCodecs.list(AreaStatCodec))
    val actions =
      field(
        HaAreaCardUiData::actions,
        AdapterValueCodecs.list(AreaActionCodec),
        default = emptyList(),
      )
    construct { HaAreaCardUiData(it[name], it[stats], it[actions]) }
  }

val ClockCodec: AdapterObjectCodec<HaClockUiData> =
  AdapterValueCodecs.obj("$UI.HaClockUiData") {
    val title = field(HaClockUiData::title, OptionalText, default = null)
    val staticTime = field(HaClockUiData::staticTimeLabel, OptionalText, default = null)
    val secondary = field(HaClockUiData::secondaryLabel, OptionalText, default = null)
    val isLarge = field(HaClockUiData::isLarge, Bool, default = false)
    val use24Hour = field(HaClockUiData::use24Hour, Bool, default = true)
    val offset = field(HaClockUiData::zoneOffsetMinutes, AdapterValueCodecs.Int, default = 0)
    val showSeconds = field(HaClockUiData::showSeconds, Bool, default = false)
    construct {
      HaClockUiData(
        it[title],
        it[staticTime],
        it[secondary],
        it[isLarge],
        it[use24Hour],
        it[offset],
        it[showSeconds],
      )
    }
  }

// ——— picture family ———

val PictureCodec: AdapterObjectCodec<HaPictureCardUiData> =
  AdapterValueCodecs.obj("$UI.HaPictureCardUiData") {
    val name = field(HaPictureCardUiData::name, OptionalText, default = null)
    val captionUrl = field(HaPictureCardUiData::captionUrl, OptionalText, default = null)
    val placeholder = field(HaPictureCardUiData::placeholderIcon, IconCodec)
    val tap = field(HaPictureCardUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct { HaPictureCardUiData(it[name], it[captionUrl], it[placeholder], it[tap]) }
  }

val PictureGlanceCellCodec: AdapterObjectCodec<HaPictureGlanceCellUiData> =
  AdapterValueCodecs.obj("$UI.HaPictureGlanceCellUiData") {
    val icon = field(HaPictureGlanceCellUiData::icon, IconCodec)
    val accent = field(HaPictureGlanceCellUiData::accent, ColorCodec)
    val active = field(HaPictureGlanceCellUiData::initiallyActive, Bool, default = false)
    val label = field(HaPictureGlanceCellUiData::label, Text)
    val tap = field(HaPictureGlanceCellUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct { HaPictureGlanceCellUiData(it[icon], it[accent], it[active], it[label], it[tap]) }
  }

val PictureGlanceCodec: AdapterObjectCodec<HaPictureGlanceUiData> =
  AdapterValueCodecs.obj("$UI.HaPictureGlanceUiData") {
    val title = field(HaPictureGlanceUiData::title, OptionalText, default = null)
    val captionUrl = field(HaPictureGlanceUiData::captionUrl, OptionalText, default = null)
    val placeholder = field(HaPictureGlanceUiData::placeholderIcon, IconCodec)
    val cells = field(HaPictureGlanceUiData::cells, AdapterValueCodecs.list(PictureGlanceCellCodec))
    construct { HaPictureGlanceUiData(it[title], it[captionUrl], it[placeholder], it[cells]) }
  }

val PictureElementPositionCodec: AdapterObjectCodec<HaPictureElementPositionUiData> =
  AdapterValueCodecs.obj("$UI.HaPictureElementPositionUiData") {
    val left = field(HaPictureElementPositionUiData::leftFraction, AdapterValueCodecs.Float)
    val top = field(HaPictureElementPositionUiData::topFraction, AdapterValueCodecs.Float)
    construct { HaPictureElementPositionUiData(it[left], it[top]) }
  }

private val OptionalPosition = AdapterValueCodecs.nullable(PictureElementPositionCodec)

val PictureElementCodec: AdapterValueCodec<HaPictureElementUiData> =
  AdapterValueCodecs.union("$UI.HaPictureElementUiData") {
    case(
      "stateIcon",
      AdapterValueCodecs.obj<HaPictureElementUiData.StateIcon>(
        "$UI.HaPictureElementUiData.StateIcon"
      ) {
        val icon = field(HaPictureElementUiData.StateIcon::icon, IconCodec)
        val accent = field(HaPictureElementUiData.StateIcon::accent, ColorCodec)
        val active = field(HaPictureElementUiData.StateIcon::initiallyActive, Bool, default = false)
        val tap =
          field(HaPictureElementUiData.StateIcon::tapAction, HaActionCodec, default = HaAction.None)
        val position =
          field(HaPictureElementUiData.StateIcon::position, OptionalPosition, default = null)
        construct {
          HaPictureElementUiData.StateIcon(
            it[icon],
            it[accent],
            it[active],
            it[tap],
            it[position],
          )
        }
      },
    )
    case(
      "stateLabel",
      AdapterValueCodecs.obj<HaPictureElementUiData.StateLabel>(
        "$UI.HaPictureElementUiData.StateLabel"
      ) {
        val text = field(HaPictureElementUiData.StateLabel::text, Text)
        val position =
          field(HaPictureElementUiData.StateLabel::position, OptionalPosition, default = null)
        construct { HaPictureElementUiData.StateLabel(it[text], it[position]) }
      },
    )
    case(
      "serviceButton",
      AdapterValueCodecs.obj<HaPictureElementUiData.ServiceButton>(
        "$UI.HaPictureElementUiData.ServiceButton"
      ) {
        val label = field(HaPictureElementUiData.ServiceButton::label, Text)
        val accent = field(HaPictureElementUiData.ServiceButton::accent, ColorCodec)
        val tap =
          field(
            HaPictureElementUiData.ServiceButton::tapAction,
            HaActionCodec,
            default = HaAction.None,
          )
        val position =
          field(HaPictureElementUiData.ServiceButton::position, OptionalPosition, default = null)
        construct {
          HaPictureElementUiData.ServiceButton(it[label], it[accent], it[tap], it[position])
        }
      },
    )
  }

val PictureElementsCodec: AdapterObjectCodec<HaPictureElementsUiData> =
  AdapterValueCodecs.obj("$UI.HaPictureElementsUiData") {
    val captionUrl = field(HaPictureElementsUiData::captionUrl, OptionalText, default = null)
    val placeholder = field(HaPictureElementsUiData::placeholderIcon, IconCodec)
    val elements =
      field(HaPictureElementsUiData::elements, AdapterValueCodecs.list(PictureElementCodec))
    construct { HaPictureElementsUiData(it[captionUrl], it[placeholder], it[elements]) }
  }

val PictureEntityCodec: AdapterObjectCodec<HaPictureEntityUiData> =
  AdapterValueCodecs.obj("$UI.HaPictureEntityUiData") {
    val name = field(HaPictureEntityUiData::name, Text)
    val state = field(HaPictureEntityUiData::state, Text, default = "")
    val icon = field(HaPictureEntityUiData::icon, IconCodec)
    val accent = field(HaPictureEntityUiData::accent, ToggleAccentCodec)
    val showName = field(HaPictureEntityUiData::showName, Bool, default = true)
    val showState = field(HaPictureEntityUiData::showState, Bool, default = true)
    val frameStamp = field(HaPictureEntityUiData::frameStamp, OptionalText, default = null)
    val tap = field(HaPictureEntityUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct {
      HaPictureEntityUiData(
        name = it[name],
        state = it[state],
        icon = it[icon],
        accent = it[accent],
        showName = it[showName],
        showState = it[showState],
        frameStamp = it[frameStamp],
        tapAction = it[tap],
      )
    }
  }

// ——— Bambu Lab ———

val BambuPrintStatusCodec: AdapterObjectCodec<HaBambuPrintStatusUiData> =
  AdapterValueCodecs.obj("$UI.HaBambuPrintStatusUiData") {
    val printerName = field(HaBambuPrintStatusUiData::printerName, Text)
    val stage = field(HaBambuPrintStatusUiData::stage, Text)
    val progressLabel = field(HaBambuPrintStatusUiData::progressLabel, Text)
    val progress =
      field(HaBambuPrintStatusUiData::progressFraction, AdapterValueCodecs.Float, default = 0f)
    val accent = field(HaBambuPrintStatusUiData::accent, ColorCodec)
    val layer = field(HaBambuPrintStatusUiData::layerLine, OptionalText, default = null)
    val remaining = field(HaBambuPrintStatusUiData::remainingLine, OptionalText, default = null)
    val nozzle = field(HaBambuPrintStatusUiData::nozzleLine, OptionalText, default = null)
    val bed = field(HaBambuPrintStatusUiData::bedLine, OptionalText, default = null)
    val phase =
      field(
        HaBambuPrintStatusUiData::phase,
        BambuPrintPhaseCodec,
        default = HaBambuPrintPhase.Printing,
      )
    construct {
      HaBambuPrintStatusUiData(
        printerName = it[printerName],
        stage = it[stage],
        progressLabel = it[progressLabel],
        progressFraction = it[progress],
        accent = it[accent],
        layerLine = it[layer],
        remainingLine = it[remaining],
        nozzleLine = it[nozzle],
        bedLine = it[bed],
        phase = it[phase],
      )
    }
  }

val BambuControlButtonCodec: AdapterObjectCodec<HaBambuControlButtonUiData> =
  AdapterValueCodecs.obj("$UI.HaBambuControlButtonUiData") {
    val label = field(HaBambuControlButtonUiData::label, Text)
    val icon = field(HaBambuControlButtonUiData::icon, IconCodec)
    val accent = field(HaBambuControlButtonUiData::accent, ColorCodec)
    val tap = field(HaBambuControlButtonUiData::tapAction, HaActionCodec, default = HaAction.None)
    construct { HaBambuControlButtonUiData(it[label], it[icon], it[accent], it[tap]) }
  }

private val OptionalBambuButton = AdapterValueCodecs.nullable(BambuControlButtonCodec)

val BambuPrintControlCodec: AdapterObjectCodec<HaBambuPrintControlUiData> =
  AdapterValueCodecs.obj("$UI.HaBambuPrintControlUiData") {
    val printerName = field(HaBambuPrintControlUiData::printerName, Text)
    val pause = field(HaBambuPrintControlUiData::pause, OptionalBambuButton, default = null)
    val resume = field(HaBambuPrintControlUiData::resume, OptionalBambuButton, default = null)
    val stop = field(HaBambuPrintControlUiData::stop, OptionalBambuButton, default = null)
    construct { HaBambuPrintControlUiData(it[printerName], it[pause], it[resume], it[stop]) }
  }

val BambuSpoolSlotCodec: AdapterObjectCodec<HaBambuSpoolSlotUiData> =
  AdapterValueCodecs.obj("$UI.HaBambuSpoolSlotUiData") {
    val slotLabel = field(HaBambuSpoolSlotUiData::slotLabel, Text)
    val material = field(HaBambuSpoolSlotUiData::material, Text)
    val color = field(HaBambuSpoolSlotUiData::color, ColorCodec)
    val remain =
      field(
        HaBambuSpoolSlotUiData::remainPercent,
        AdapterValueCodecs.nullable(AdapterValueCodecs.Int),
        default = null,
      )
    val active = field(HaBambuSpoolSlotUiData::active, Bool, default = false)
    construct {
      HaBambuSpoolSlotUiData(it[slotLabel], it[material], it[color], it[remain], it[active])
    }
  }

val BambuAmsCodec: AdapterObjectCodec<HaBambuAmsUiData> =
  AdapterValueCodecs.obj("$UI.HaBambuAmsUiData") {
    val title = field(HaBambuAmsUiData::title, Text)
    val slots = field(HaBambuAmsUiData::slots, AdapterValueCodecs.list(BambuSpoolSlotCodec))
    construct { HaBambuAmsUiData(it[title], it[slots]) }
  }

val BambuSpoolDetailCodec: AdapterObjectCodec<HaBambuSpoolDetailUiData> =
  AdapterValueCodecs.obj("$UI.HaBambuSpoolDetailUiData") {
    val slot = field(HaBambuSpoolDetailUiData::slot, BambuSpoolSlotCodec)
    construct { HaBambuSpoolDetailUiData(it[slot]) }
  }
