package com.adagiostream.android.ui.screens.licenses

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class LicenseEntry(
    val name: String,
    val license: String,
    val url: String,
    val licenseText: String,
)

private val licenses = listOf(
    LicenseEntry(
        name = "libVLC",
        license = "LGPL 2.1",
        url = "https://www.videolan.org/vlc/",
        licenseText = LGPL_21_SHORT,
    ),
    LicenseEntry(
        name = "OkHttp",
        license = "Apache 2.0",
        url = "https://square.github.io/okhttp/",
        licenseText = APACHE_20_SHORT,
    ),
    LicenseEntry(
        name = "Coil",
        license = "Apache 2.0",
        url = "https://coil-kt.github.io/coil/",
        licenseText = APACHE_20_SHORT,
    ),
    LicenseEntry(
        name = "Jetpack Compose",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack/compose",
        licenseText = APACHE_20_SHORT,
    ),
    LicenseEntry(
        name = "Hilt / Dagger",
        license = "Apache 2.0",
        url = "https://dagger.dev/hilt/",
        licenseText = APACHE_20_SHORT,
    ),
    LicenseEntry(
        name = "AndroidX Media3",
        license = "Apache 2.0",
        url = "https://developer.android.com/media/media3",
        licenseText = APACHE_20_SHORT,
    ),
    LicenseEntry(
        name = "Kotlin Serialization",
        license = "Apache 2.0",
        url = "https://github.com/Kotlin/kotlinx.serialization",
        licenseText = APACHE_20_SHORT,
    ),
    LicenseEntry(
        name = "Kotlin Coroutines",
        license = "Apache 2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines",
        licenseText = APACHE_20_SHORT,
    ),
    LicenseEntry(
        name = "Reorderable",
        license = "Apache 2.0",
        url = "https://github.com/Calvin-LL/Reorderable",
        licenseText = APACHE_20_SHORT,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Open Source Licenses") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            items(licenses) { entry ->
                LicenseCard(entry)
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LicenseCard(entry: LicenseEntry) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = entry.license,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = entry.licenseText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val APACHE_20_SHORT = """Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License."""

private const val LGPL_21_SHORT = """This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA"""
