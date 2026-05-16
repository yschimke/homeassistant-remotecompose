package ee.schimke.ha.rc.components

/**
 * Stable named-bitmap-registry name for a picture-entity card's image
 * slot.
 *
 * Used in two places that must agree:
 *  1. Capture time — passed as `RemoteHaImageUrl.name` so the doc
 *     registers the bitmap slot under this name (independent of the
 *     URL, which may rotate as HA refreshes its `?token=`).
 *  2. Runtime — passed to `StateUpdater.setUserLocalBitmap` so the
 *     host can override the player's URL-fetched bytes with a
 *     freshly fetched bitmap when the entity's `entity_picture`
 *     attribute rotates. The override channel takes precedence over
 *     the doc's URL-load result, so subsequent paints render the
 *     latest pushed bitmap without re-capturing the document.
 *
 * Mirrors `LiveValues`' "<entityId>.<channel>" convention used by
 * `state` / `is_on` bindings.
 */
fun pictureBindingName(entityId: String): String = "$entityId.entity_picture"
