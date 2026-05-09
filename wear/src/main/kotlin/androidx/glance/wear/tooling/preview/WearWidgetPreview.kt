/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// TODO: delete this vendored copy once
// androidx.glance.wear:wear-tooling-preview ships WearWidgetPreview in
// a published release. Source: AOSP gerrit change
// I36504163576c4869ecd67732321dc7535edf3467
// (https://android-review.googlesource.com/c/platform/frameworks/support/+/4045856).
// We're shipping it locally so @Preview entries on our slot widgets
// can render today; the file is byte-identical to the upstream patch
// so the eventual delete is a clean lift.

package androidx.glance.wear.tooling.preview

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.remote.tooling.preview.RemoteDocPreview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.core.WearWidgetParams
import kotlinx.coroutines.runBlocking

/**
 * Previews a [GlanceWearWidget] within a widget container in the Android Studio Preview.
 *
 * This utility function calls [GlanceWearWidget.provideWidgetData] to get the widget's layout,
 * captures it into a Remote Compose document, and displays it using [RemoteDocPreview]. This
 * ensures the preview accurately reflects how the widget will appear to users, including
 * container-specific properties like padding and corner radius.
 *
 * @param widget The [GlanceWearWidget] instance to preview.
 * @param params The [WearWidgetParams] describing the widget's container type, dimensions, and
 *   padding.
 * @param modifier The [Modifier] to be applied to the preview container.
 */
@SuppressLint("RestrictedApiAndroidX")
@Composable
public fun WearWidgetPreview(
    widget: GlanceWearWidget,
    params: WearWidgetParams,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val document =
        remember(widget, params, context) {
            runBlocking {
                val widgetData = widget.provideWidgetData(context, params)
                widgetData.captureRawContent(context, params).rcDocument
            }
        }

    RemoteDocPreview(
        document,
        modifier =
            modifier
                .width((params.widthDp + 2f * params.horizontalPaddingDp).dp)
                .height((params.heightDp + 2f * params.verticalPaddingDp).dp),
    )
}
