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
// Snapshot helper that renders a Glance Wear widget inside a
// simulated circular watch face (black background, app icon overlay at
// the top, widget below). Mirrors the WearWidget sample preview helper
// from https://github.com/android/wear-os-samples/pull/1371. The
// upstream sample marks the helper as "temporary, drop once
// androidx.glance.wear:wear-tooling-preview ships the equivalent
// surface"; same deal here.
//
// The simpler box-only variant (no watch-face overlay) is provided
// by the vendored androidx.glance.wear.tooling.preview.WearWidgetPreview
// in this directory.

package androidx.glance.wear.tooling.preview

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.remote.tooling.preview.RemoteDocumentPreview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.core.WearWidgetParams
import ee.schimke.ha.rc.components.R
import kotlinx.coroutines.runBlocking

/**
 * Previews a [GlanceWearWidget] inside a simulated Wear OS watch face
 * — a circular black surface with the app icon at the top and the
 * widget rendered below.
 *
 * Goes a step beyond [WearWidgetPreview] (which renders just the
 * widget rectangle) so reviewers can sanity-check how a widget reads
 * against the surrounding watch chrome.
 *
 * @param widget The [GlanceWearWidget] instance to preview.
 * @param params The [WearWidgetParams] describing container type,
 *   dimensions and padding.
 * @param modifier The [Modifier] applied to the inner widget surface.
 */
@SuppressLint("RestrictedApi")
@Composable
public fun WearWidgetPreviewSnapshot(
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

    Box(
        modifier = Modifier.size(227.dp).clip(CircleShape).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        RemoteDocumentPreview(
            document,
            modifier =
                modifier
                    .offset(y = 14.dp)
                    .width((params.widthDp + 2f * params.horizontalPaddingDp).dp)
                    .height((params.heightDp + 2f * params.verticalPaddingDp).dp),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(top = 10.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFE0E0E0)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    colorFilter = ColorFilter.tint(Color(0xFF424242)),
                )
            }
        }
    }
}
