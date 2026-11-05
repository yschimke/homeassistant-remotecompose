package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

// ---------------------------------------------------------------------------------------------
// Layouts as a slot: how a style arranges and frames cards, as data the cards read — a tile's
// composition and chip, a media card's panel and artwork, a gauge's and an expanded reading's
// layout, how an unavailable card is labelled, whether the card wears a frame. A card asks
// [haLayouts] by role; the one place a bespoke family names its layouts is [familyLayouts].
//
// A theme's own layouts (HaComponentSlot.Layouts) win; otherwise its family's. A kit therefore
// keeps the arrangements of the style it extends unless it picks others: layouts are composition,
// not drawing, and a kit's drawings already come from its grammar. See
// docs/architecture/theme-kits.md.
// ---------------------------------------------------------------------------------------------

/** How a tile composes its icon, name and state. */
enum class HaTileComposition {
  /** The cards' own row: icon, then name over state. */
  Standard,
  /** The family's own tile ([FamilyTileInfo]: an LCD screen, a Braun scale, an LCARS bar, …). */
  Family,
  /** The row rearranged by [HaLayouts.tileArrangement] (the softer community styles). */
  Arranged,
}

/**
 * A rearranged tile row ([HaTileComposition.Arranged]): the identity (icon) at the end rather than
 * the start, the state before the name, or the state trailing at the end of the row.
 */
data class HaTileArrangement(
  val identityAtEnd: Boolean = false,
  val stateFirst: Boolean = false,
  val stateTrailing: Boolean = false,
)

/** The face behind a tile's icon when it has on / off chrome. */
enum class HaTileChip {
  /** A tinted round chip in the theme's control shape. */
  Tinted,
  /** A moulded two-part paddle with a moving indicator (IKEA). */
  Paddle,
  /** A machined key face with a pilot lamp (Console). */
  KeyFace,
}

/** How a media card lays out its title, transport and artwork. */
enum class HaMediaLayout {
  /** Title, transport and seek beside the artwork. */
  ArtBeside,
  /** Artwork beside the title, the transport centred under it (a control centre). */
  ControlCentre,
  /** Title over a transport row with small artwork at its end (landscape). */
  Landscape,
  /** A wide artwork banner over the title and a centred transport (cinema). */
  Cinema,
}

/** What fills a media card's artwork space when there is no artwork. */
enum class HaArtwork {
  /** A note icon on a tint of the accent. */
  Tinted,
  /** A recessed slot with a taped service label (Console). */
  TapedSlot,
}

/** How a reading (a gauge, an expanded measurement) lays out its meter and text. */
enum class HaReadingLayout {
  /** Name, meter, then value and range in a column. */
  Standard,
  /** The value inside the meter, the name under it (Home Assistant's own gauge). */
  ValueInside,
  /** The community styles' reading layouts ([CommunityReadingLayout]). */
  Community,
}

/** How a card sets a short note — an empty state, a placeholder ("No entities", "No samples"). */
enum class HaNoteStyle {
  /** Plain secondary text. */
  Text,
  /** White service tape on the panel (Console). */
  Tape,
}

/** How a clock card shows the time. */
enum class HaClockFace {
  /** The time as text in the theme's type. */
  Text,
  /** Seven-segment LED modules behind smoked glass (Console). */
  LedModules,
  /**
   * A rugged digital watch's LCD window: italic segment digits with their unlit segments ghosted,
   * small accent seconds, over a fine mesh.
   */
  LcdWindow,
}

/** How a value slider draws itself. */
enum class HaSliderStyle {
  /** A track filled to the value, with a knob. */
  Track,
  /** Tracks either side of a handle that narrows while held (Material 3 Expressive). */
  SplitTrack,
}

/** How a progress ring (a printer's job) draws itself. */
enum class HaProgressRing {
  /** A plain arc. */
  Arc,
  /** A wavy indicator that ripples while the job runs (Material 3 Expressive). */
  Wavy,
}

/** How an unavailable card says so. */
enum class HaUnavailableLabel {
  /** The label on a translucent plate in the theme's control shape. */
  Plate,
  /** White service tape on the panel (Console). */
  Tape,
}

/**
 * A style's layouts. Every field defaults to the cards' own, so a family or kit states only what it
 * changes.
 *
 * @property tile how a tile composes itself; [tileArrangement] for an arranged one.
 * @property tileChip the face behind a tile's on / off icon.
 * @property serviceTileKey whether a tile whose tap calls a service mounts its plain icon on the
 *   theme's key.
 * @property cardFrame whether the card wears its family's frame ([communityFrame]).
 * @property media and [artwork]: the media card's composition and empty artwork.
 * @property gauge and [expandedReading]: how a gauge and an expanded measurement lay out.
 * @property unavailable how an unavailable card is labelled.
 * @property notes how a card sets a short note: an empty state or a placeholder.
 * @property keyFaces whether the theme's keys are solid faces (a label in the face's ink, a larger
 *   mount for a checkbox or a printer control) rather than tinted outlines.
 * @property clock how a clock card shows the time.
 * @property entitiesGrid whether an entities card lays its rows out two up, as accessory tiles.
 * @property slider and [progressRing]: how a slider and a progress ring draw themselves.
 */
data class HaLayouts(
  val tile: HaTileComposition = HaTileComposition.Standard,
  val tileArrangement: HaTileArrangement = HaTileArrangement(),
  val tileChip: HaTileChip = HaTileChip.Tinted,
  val serviceTileKey: Boolean = false,
  val cardFrame: Boolean = false,
  val media: HaMediaLayout = HaMediaLayout.ArtBeside,
  val artwork: HaArtwork = HaArtwork.Tinted,
  val gauge: HaReadingLayout = HaReadingLayout.Standard,
  val expandedReading: HaReadingLayout = HaReadingLayout.Standard,
  val unavailable: HaUnavailableLabel = HaUnavailableLabel.Plate,
  val notes: HaNoteStyle = HaNoteStyle.Text,
  val keyFaces: Boolean = false,
  val clock: HaClockFace = HaClockFace.Text,
  /**
   * Whether a glance cell is a watch's complication window: its icon and value together in a small
   * recessed LCD tile, greyscale, lit when on and dim when off (Rugged Digital).
   */
  val glanceComplication: Boolean = false,
  val entitiesGrid: Boolean = false,
  val slider: HaSliderStyle = HaSliderStyle.Track,
  val progressRing: HaProgressRing = HaProgressRing.Arc,
) : HaSlotRenderer {
  companion object {
    /** The cards' own layouts. */
    val Standard = HaLayouts()
  }
}

/** The active theme's layouts: its own, else its family's. */
@Composable
@ReadOnlyComposable
internal fun haLayouts(): HaLayouts {
  val theme = currentHaUberTheme().theme
  return theme.components.layouts ?: familyLayouts(theme.visuals)
}

/**
 * A bespoke family's layouts — the one place a family names them. Each is exactly what the cards
 * used to branch to for that family.
 */
internal fun familyLayouts(family: HaVisualFamily): HaLayouts = FamilyLayoutTable.getValue(family)

private val FamilyLayoutTable: Map<HaVisualFamily, HaLayouts> =
  HaVisualFamily.entries.associateWith { family ->
    val ownTile =
      family in
        setOf(
          HaVisualFamily.Expressive,
          HaVisualFamily.Trinitron,
          HaVisualFamily.Lcd,
          HaVisualFamily.Braun,
          HaVisualFamily.Nixie,
          HaVisualFamily.Console,
          HaVisualFamily.Ikea,
          HaVisualFamily.Mushroom,
          HaVisualFamily.Catppuccin,
          HaVisualFamily.Obsidian,
          HaVisualFamily.Noctis,
          HaVisualFamily.Lcars,
          HaVisualFamily.Aeon,
        )
    val community = family.isCommunity
    HaLayouts(
      tile =
        when {
          ownTile -> HaTileComposition.Family
          community -> HaTileComposition.Arranged
          else -> HaTileComposition.Standard
        },
      tileArrangement =
        HaTileArrangement(
          identityAtEnd = family == HaVisualFamily.Minimalist || family == HaVisualFamily.Arctic,
          stateFirst = family == HaVisualFamily.Cupertino || family == HaVisualFamily.Minimalist,
          stateTrailing = family == HaVisualFamily.Bubble,
        ),
      tileChip =
        when (family) {
          HaVisualFamily.Ikea -> HaTileChip.Paddle
          HaVisualFamily.Console -> HaTileChip.KeyFace
          else -> HaTileChip.Tinted
        },
      serviceTileKey = family == HaVisualFamily.Console,
      cardFrame = community,
      media =
        when (family) {
          HaVisualFamily.Cupertino -> HaMediaLayout.ControlCentre
          HaVisualFamily.Arctic -> HaMediaLayout.Landscape
          HaVisualFamily.Aeon -> HaMediaLayout.Cinema
          else -> HaMediaLayout.ArtBeside
        },
      artwork = if (family == HaVisualFamily.Console) HaArtwork.TapedSlot else HaArtwork.Tinted,
      gauge =
        when {
          family == HaVisualFamily.HomeAssistant -> HaReadingLayout.ValueInside
          community -> HaReadingLayout.Community
          else -> HaReadingLayout.Standard
        },
      expandedReading = if (community) HaReadingLayout.Community else HaReadingLayout.Standard,
      unavailable =
        if (family == HaVisualFamily.Console) HaUnavailableLabel.Tape else HaUnavailableLabel.Plate,
      notes = if (family == HaVisualFamily.Console) HaNoteStyle.Tape else HaNoteStyle.Text,
      keyFaces = family == HaVisualFamily.Console,
      clock = if (family == HaVisualFamily.Console) HaClockFace.LedModules else HaClockFace.Text,
      entitiesGrid = family == HaVisualFamily.Cupertino || family == HaVisualFamily.Minimalist,
      slider =
        if (family == HaVisualFamily.Expressive) HaSliderStyle.SplitTrack else HaSliderStyle.Track,
      progressRing =
        if (family == HaVisualFamily.Expressive) HaProgressRing.Wavy else HaProgressRing.Arc,
    )
  }
