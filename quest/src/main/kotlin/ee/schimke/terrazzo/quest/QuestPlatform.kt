package ee.schimke.terrazzo.quest

import android.os.Build

/** Whether this Horizon OS build has the Android-14 room-mapping/passthrough route. */
internal fun supportsQuestRoomMapping(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
  sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
