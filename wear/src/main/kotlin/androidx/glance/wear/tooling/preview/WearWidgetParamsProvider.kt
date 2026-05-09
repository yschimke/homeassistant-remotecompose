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
// androidx.glance.wear:wear-tooling-preview ships
// WearWidgetParamsProvider in a published release. Source:
// AOSP gerrit change I36504163576c4869ecd67732321dc7535edf3467
// (https://android-review.googlesource.com/c/platform/frameworks/support/+/4045856).
// We're shipping it locally so @Preview entries on our slot widgets
// have a parameter source today; the file is byte-identical to the
// upstream patch so the eventual delete is a clean lift.

package androidx.glance.wear.tooling.preview

import android.annotation.SuppressLint

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId

/**
 * A [PreviewParameterProvider] that provides a variety of [WearWidgetParams] for Wear previews.
 *
 * This provider allows testing how a widget layout adapts to different container sizes and types,
 * such as large rectangular containers versus small square ones. To use it, annotate your preview
 * parameter with `@PreviewParameter(WearWidgetParamsProvider::class)`.
 */
@SuppressLint("RestrictedApi")
public class WearWidgetParamsProvider : PreviewParameterProvider<WearWidgetParams> {
    @SuppressLint("RestrictedApi")
    override val values: Sequence<WearWidgetParams> =
        sequenceOf(
            // Large Widget Preview
            WearWidgetParams(
                instanceId = WidgetInstanceId("widgets", 1),
                containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
                widthDp = 200f,
                heightDp = 112f,
                verticalPaddingDp = 8f,
                horizontalPaddingDp = 8f,
                cornerRadiusDp = 26f,
            ),
            // Small Widget Preview
            WearWidgetParams(
                instanceId = WidgetInstanceId("widgets", 2),
                containerType = ContainerInfo.CONTAINER_TYPE_SMALL,
                widthDp = 200f,
                heightDp = 60f,
                verticalPaddingDp = 8f,
                horizontalPaddingDp = 8f,
                cornerRadiusDp = 26f,
            ),
        )
}
