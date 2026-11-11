package io.nekohasekai.sfa.compose.screen.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import java.text.Collator
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCountryPickerScreen(navController: NavController) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    var query by remember { mutableStateOf("") }
    val selected = remember { SmartSettings.countryOverride }
    val automatic = stringResource(R.string.smart_country_auto)
    val resolved = SmartSettings.automaticCountry
    val countries =
        remember(locale) {
            val collator = Collator.getInstance(locale)
            Locale
                .getISOCountries()
                .map { it to Locale("", it).getDisplayCountry(locale) }
                .sortedWith { left, right -> collator.compare(left.second, right.second) }
        }
    val filtered =
        remember(query, countries) {
            countries.filter { (code, name) -> name.contains(query, ignoreCase = true) || code.contains(query, ignoreCase = true) }
        }
    fun select(code: String) {
        SmartSettings.countryOverride = code
        navController.navigateUp()
    }
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.smart_country)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                }
            },
        )
    }
    val scaffoldPadding = LocalScaffoldPadding.current
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(top = scaffoldPadding.calculateTopPadding()),
    ) {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = { query = it },
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.smart_country_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().selectableGroup(),
            contentPadding = PaddingValues(bottom = scaffoldPadding.calculateBottomPadding() + 16.dp),
        ) {
            if (automatic.contains(query, ignoreCase = true)) {
                item(key = "automatic") {
                    ListItem(
                        headlineContent = { Text(automatic, style = MaterialTheme.typography.bodyLarge) },
                        supportingContent =
                            if (resolved.isEmpty()) {
                                null
                            } else {
                                {
                                    Text(
                                        Locale("", resolved).getDisplayCountry(locale),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            },
                        leadingContent = { RadioButton(selected = selected.isEmpty(), onClick = null) },
                        modifier = Modifier.selectable(selected = selected.isEmpty(), role = Role.RadioButton, onClick = { select("") }),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            items(filtered, key = { it.first }) { (code, name) ->
                ListItem(
                    headlineContent = {
                        Row {
                            Text("${countryFlag(code)} ", modifier = Modifier.clearAndSetSemantics {})
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    },
                    leadingContent = { RadioButton(selected = selected == code, onClick = null) },
                    trailingContent = { Text(code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.selectable(selected = selected == code, role = Role.RadioButton, onClick = { select(code) }),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

internal fun countryFlag(code: String): String = String(intArrayOf(0x1F1E6 + code[0].code - 'A'.code, 0x1F1E6 + code[1].code - 'A'.code), 0, 2)
