package com.adagiostream.android.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Single-line label for [androidx.compose.material3.SegmentedButton] that
 * shrinks to fit — 4–5 segments are tight on phone widths, and a wrapped label
 * breaks the row's alignment (beads_adagio-bzx).  Pair with `icon = {}` on the
 * SegmentedButton so the selected-checkmark reservation doesn't eat the width;
 * selection stays visible via container color and announced via semantics.
 *
 * Ellipsis is the fallback when even the 9.sp floor can't fit (e.g. very large
 * system font scale).
 */
@Composable
fun SegmentLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 14.sp, stepSize = 0.5.sp),
    )
}
