package ee.schimke.terrazzo.core.wearsync

/**
 * A Terrazzo widget placed on a paired watch, as the watch reported it, with the card the phone
 * chose for it. The phone owns the choice: [WearSyncManager.chooseWatchWidgetCard] changes it.
 */
data class WatchWidget(
  /** The watch's Data Layer node id. */
  val nodeId: String,
  /** The watch's name, empty when it didn't say. */
  val nodeName: String,
  /** The Glance Wear widget instance id, unique on its watch. */
  val instanceId: Int,
  val large: Boolean,
  /** The chosen card's key, or null while there is no card to show. */
  val cardKey: String?,
  /** The cards this widget can show, in the watch's order: [cardKey] is one of them. */
  val options: List<WatchWidgetCardOption>,
  /**
   * Whether it keeps up with Home Assistant while the phone app is closed: off until the user turns
   * it on ([WearSyncManager.setWatchWidgetLive]).
   */
  val liveUpdates: Boolean = false,
)

/** A card a watch widget can show: one the phone shows on the watch. */
data class WatchWidgetCardOption(val cardKey: String, val title: String)
