package ee.schimke.ha.rc.components

/**
 * Stable named-bitmap-registry name for a picture-entity card's image slot.
 *
 * Used in two places that must agree:
 * 1. Capture time — passed as `RemoteHaImageUrl.name` so the doc registers the bitmap slot under
 *    this name (independent of the URL, which may rotate as HA refreshes its `?token=`).
 * 2. Runtime — the name a host would pass to `setUserLocalBitmap` to swap in a freshly fetched
 *    bitmap when the entity's `entity_picture` rotates, without re-capturing the document. That
 *    override works from alpha22 (`NamedBitmapOverrideTest`; it was a no-op under alpha010, #277);
 *    no host pushes one yet, so a rotated picture still arrives by URL reload or a re-capture.
 *
 * Mirrors `LiveValues`' "<entityId>.<channel>" convention used by `state` / `is_on` bindings.
 */
fun pictureBindingName(entityId: String): String = "$entityId.entity_picture"
