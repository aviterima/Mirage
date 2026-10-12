package com.mirage.spike

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirage.spike.store.SavedScenario
import com.mirage.spike.store.savedItemSearch

/** Selects a place for one planner field; opening or dismissing never loads a plan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerSavedPlacesSheet(
    places: List<SavedScenario>,
    target: String,
    onPick: (SavedScenario) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = savedItemSearch(places, query, "SNAP").filter { it.dest != null }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp)
            .testTag("plannerSavedPlaces"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved places", style = MaterialTheme.typography.titleLarge)
            Text("Choose $target", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(query, { query = it }, label = { Text("Search names, aliases or addresses") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("plannerSavedPlaceSearch"))
            if (matches.isEmpty()) {
                Text(if (places.none { it.kind == "SNAP" && it.dest != null })
                    "No saved places yet. Save a place from Place mode to use it here."
                    else "No saved places match. Try another name or address.")
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(matches, key = { it.id }) { place ->
                    SavedPlannerPlaceRow(place, Modifier.testTag("plannerSavedPlace-${place.id}")) { onPick(place) }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
internal fun PlannerSavedMatches(places: List<SavedScenario>, target: String, onPick: (SavedScenario) -> Unit) {
    if (places.isEmpty()) return
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text("Saved places · $target", style = MaterialTheme.typography.labelMedium)
            places.take(5).forEach { place ->
                SavedPlannerPlaceRow(place, Modifier.testTag("plannerSavedSuggestion-${place.id}")) { onPick(place) }
            }
            if (places.size > 5) Text("Use Saved places to browse all matches.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SavedPlannerPlaceRow(place: SavedScenario, modifier: Modifier, onPick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onPick)
        .semantics { contentDescription = "Choose saved place ${place.name}" }
        .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Bookmark, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text((if (place.favorite) "★ " else "") + place.name, fontWeight = FontWeight.SemiBold)
            val detail = place.destAddress.ifBlank { place.destName.takeUnless { it == place.name }.orEmpty() }
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
