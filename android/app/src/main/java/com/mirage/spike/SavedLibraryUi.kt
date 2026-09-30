package com.mirage.spike

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mirage.spike.engine.LiveSession
import com.mirage.spike.store.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedPlansDialog(vm: MirageViewModel, active: Boolean = false, onDismiss: () -> Unit, onLoaded: (SavedScenario) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("All") }
    var favorites by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Pair<SavedScenario,String>?>(null) }
    var editText by remember { mutableStateOf("") }
    var incoming by remember { mutableStateOf<List<SavedScenario>?>(null) }
    var exportText by remember { mutableStateOf("") }
    var connector by remember { mutableStateOf<SavedScenario?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) scope.launch {
            message = runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportText) } ?: error("Cannot open destination")
            }; "Backup exported" }.getOrElse { "Export failed: ${it.message}" }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                    while(true) { val count=input.read(buffer); if(count<0) break; out.write(buffer,0,count); require(out.size()<=BackupCodec.MAX_BYTES) { "Backup exceeds 2 MB" } }
                    out.toByteArray()
                } ?: error("Cannot open backup")
                BackupCodec.decode(bytes.toString(Charsets.UTF_8))
            } }.onSuccess { incoming=it }.onFailure { message="Import failed: ${it.message}" }
        }
    }
    ModalBottomSheet(onDismissRequest=onDismiss, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Saved plans", style=MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                TextButton(onClick={ exportText=BackupCodec.encode(vm.savedScenarios); export.launch("Mirage-backup.json") }) { Text("Export backup") }
                TextButton(onClick={ import.launch(arrayOf("application/json","text/plain","application/octet-stream")) }) { Text("Import backup") }
            }
            Row {
                OutlinedTextField(name,{name=it},placeholder={Text("Name, e.g. Lunch run")},modifier=Modifier.weight(1f),singleLine=true)
                TextButton(onClick={
                    val saved=if(active) vm.saveActiveScenario(name) else vm.saveScenario(name)
                    message=if(saved) "Saved $name" else vm.error ?: "Could not save"
                    if(saved) name=""
                },enabled=name.isNotBlank() && if(active) LiveSession.plan!=null else vm.canSaveScenario) { Text("Save") }
            }
            if(!active && vm.draftSavedName!=null) TextButton(onClick={message=if(vm.saveScenario(vm.draftSavedName!!,true))"Changes saved" else vm.error.orEmpty()}) {Text("Save changes to ${vm.draftSavedName}")}
            if(active && LiveSession.state.value.savedId != null) TextButton(onClick={
                message=if(vm.saveActiveScenario(LiveSession.state.value.savedName,true)) "Changes saved" else vm.error ?: "Could not save"
            }) { Text("Save changes") }
            OutlinedTextField(query,{query=it},label={Text("Search saved names or addresses")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                listOf("All","Places","Routes","Itineraries").forEach { label -> TextButton(onClick={kind=label}) { Text(if(kind==label) "• $label" else label) } }
            }
            Row {
                FilterChip(selected=favorites,onClick={favorites=!favorites},label={Text("Favorites")})
                if(vm.libraryUndoAvailable) TextButton(onClick={vm.undoLibrary();message=vm.notice ?: vm.error.orEmpty()}) { Text("Undo library change") }
            }
            Text("Favorites first, then recently used",style=MaterialTheme.typography.bodySmall)
            if(message.isNotBlank()) Text(message)
            val items=vm.savedScenarios.filter { item ->
                (!favorites || item.favorite) && (kind=="All" || item.kind==mapOf("Places" to "SNAP","Routes" to "ROUTE","Itineraries" to "ITINERARY")[kind]) &&
                    (item.name+" "+item.destAddress+" "+item.aliases.joinToString()).contains(query,true)
            }.sortedWith(compareByDescending<SavedScenario>{it.favorite}.thenByDescending{it.lastUsedAt}.thenByDescending{it.createdAt})
            if(items.isEmpty()) Text("No saved items match. Try another tab or search.")
            items.forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row {
                            Column(Modifier.weight(1f).clickable {selected=if(selected==item.id)null else item.id}) {
                                Text((if(item.favorite) "★ " else "")+item.name,style=MaterialTheme.typography.titleMedium)
                                Text(if(item.kind=="ITINERARY") "${item.stops.size} stops · ${item.stops.sumOf{it.dwellMinutes}} min staying" else item.startName+" → "+item.destName,style=MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick={vm.loadScenario(item);onLoaded(item)},modifier=Modifier.semantics{contentDescription="Load ${item.name}"}) { Text("Open") }
                            var menu by remember(item.id) {mutableStateOf(false)}
                            Box {
                                IconButton(onClick={menu=true}) { Icon(Icons.Default.MoreVert,"Options for ${item.name}") }
                                DropdownMenu(menu,{menu=false}) {
                                    DropdownMenuItem(text={Text(if(item.favorite) "Remove favorite" else "Add favorite")},onClick={menu=false;vm.favoriteScenario(item.id)})
                                    listOf("Rename","Duplicate","Voice aliases").forEach { action -> DropdownMenuItem(text={Text(action)},onClick={menu=false;edit=item to action;editText=if(action=="Voice aliases") item.aliases.joinToString(", ") else if(action=="Duplicate") item.name+" copy" else item.name}) }
                                    DropdownMenuItem(text={Text("Share this item")},onClick={
                                        menu=false
                                        scope.launch {
                                            runCatching {
                                                val file=withContext(Dispatchers.IO) {java.io.File(context.cacheDir,"shared-trips").apply{mkdirs()}.let {dir->java.io.File(dir,"Mirage-${java.util.UUID.randomUUID()}.json").apply{writeText(BackupCodec.encode(listOf(item)))}}}
                                                val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".sharing",file)
                                                val intent=android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/json").putExtra(android.content.Intent.EXTRA_STREAM,uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                context.startActivity(android.content.Intent.createChooser(intent,"Share ${item.name}"))
                                            }.onFailure{message="Could not share: ${it.message}"}
                                        }
                                    })
                                    DropdownMenuItem(text={Text("Export this item")},onClick={menu=false;exportText=BackupCodec.encode(listOf(item));export.launch("Mirage-itinerary.json")})
                                    DropdownMenuItem(text={Text("Delete")},onClick={menu=false;message=if(vm.deleteScenario(item.id))"Deleted ${item.name}. Undo is available." else vm.error.orEmpty()})
                                }
                            }
                        }
                        if(selected==item.id) {
                            if(item.destAddress.isNotBlank()) Text(item.destAddress)
                            item.stops.forEachIndexed { i,s -> Text("${i+1}. ${s.name} · ${s.dwellMinutes} min · ${s.mode.name.lowercase()}") }
                            if(item.kind=="SNAP") {
                                TextButton(onClick={vm.useSavedPlaceAsStart(item);onLoaded(item)}) {Text("Use as start")}
                                TextButton(onClick={vm.useSavedPlaceAsDestination(item);onLoaded(item)}) {Text("Use as destination")}
                                TextButton(onClick={vm.addSavedPlaceStop(item);onLoaded(item)}) {Text("Add as stop")}
                            }
                            if(item.kind=="ROUTE") TextButton(onClick={if(vm.appendSavedRoute(item))onLoaded(item) else if(vm.error?.contains("connecting leg")==true)connector=item else message=vm.error.orEmpty()}) {Text("Add to itinerary")}
                            if(item.kind=="ITINERARY") TextButton(onClick={vm.appendSavedItinerary(item);onLoaded(item)}) {Text("Add its stops")}
                        }
                    }
                }
            }
            TextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth()) {Text("Done")}
        }
    }
    edit?.let { (item,action) -> AlertDialog(onDismissRequest={edit=null},title={Text(action)},text={Column {
        OutlinedTextField(editText,{editText=it},label={Text(if(action=="Voice aliases") "Comma-separated names" else "Name")})
        vm.error?.let { Text(it) }
    }},confirmButton={TextButton(onClick={val ok=if(action=="Voice aliases")vm.setAliases(item.id,editText) else vm.renameScenario(item.id,editText,action=="Duplicate");if(ok)edit=null}){Text("Save")}},dismissButton={TextButton(onClick={edit=null}){Text("Cancel")}}) }
    incoming?.let { items -> AlertDialog(onDismissRequest={incoming=null},title={Text("Import ${items.size} saved items?")},text={Text("Existing items stay unchanged. Matching names receive a numbered suffix; exact matching records are skipped. No credentials are imported.")},confirmButton={TextButton(onClick={if(vm.importScenarios(items)){incoming=null;message=vm.notice.orEmpty()}}){Text("Import")}},dismissButton={TextButton(onClick={incoming=null}){Text("Cancel")}}) }
    connector?.let { item -> AlertDialog(onDismissRequest={connector=null},title={Text("Connect these routes?")},text={Text("Add a connecting leg to ${item.startName}? Review its mode and timing before starting.")},confirmButton={TextButton(onClick={if(vm.appendSavedRoute(item,true)){connector=null;onLoaded(item)}}){Text("Add connecting leg")}},dismissButton={TextButton(onClick={connector=null}){Text("Cancel")}}) }
}
