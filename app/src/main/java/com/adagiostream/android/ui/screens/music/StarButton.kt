package com.adagiostream.android.ui.screens.music

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable

/**
 * A toggle button that renders a filled star when [starred] is true and an
 * outlined star when false (baw.5.2).
 *
 * Used in album and genre track rows.  [onToggle] is called when the user taps
 * the button; callers are responsible for optimistic state updates.
 */
@Composable
fun StarButton(starred: Boolean, onToggle: () -> Unit) {
    IconToggleButton(checked = starred, onCheckedChange = { onToggle() }) {
        if (starred) {
            Icon(Icons.Filled.Star, contentDescription = "Starred")
        } else {
            Icon(Icons.Outlined.Star, contentDescription = "Not starred")
        }
    }
}
