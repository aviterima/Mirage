package com.mirage.spike

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun parsePlannerTime(text: String, zone: ZoneId = ZoneId.systemDefault()): Long {
    val date = LocalDateTime.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val offsets = zone.rules.getValidOffsets(date)
    require(offsets.size == 1) { "This time is missing or occurs twice because of a clock change. Choose another time." }
    return date.toInstant(offsets.single()).toEpochMilli()
}

/** An app-owned entry gives the calendar's text alternative the same voice controls. */
@Composable
internal fun VoiceTimeDialog(title: String, fieldKey: String, initial: Long?, onSet: (Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm") }
    fun formatted(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(formatter)
    var text by rememberSaveable(fieldKey) { mutableStateOf(initial?.let(::formatted).orEmpty()) }
    val parsed = runCatching { parsePlannerTime(text) }
    val inputBusy = voiceFieldBusy(fieldKey)
    fun close() { discardVoiceField(context, fieldKey); onDismiss() }
    AlertDialog(onDismissRequest = ::close, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceTextField(text, { text = it }, fieldKey, "$title date and time", kind = "datetime",
                context = "Local time zone: ${ZoneId.systemDefault().id}. Preserve the date when only the time changes.",
                modifier = Modifier.fillMaxWidth().testTag("voiceTimeInput"), singleLine = false,
                placeholder = { Text(if (LocalLanguageModel.interpretationEnabled)
                    "Tomorrow at 9 AM, or 2026-10-15T09:00" else "2026-10-15T09:00") })
            Text(if (LocalLanguageModel.interpretationEnabled)
                "${ZoneId.systemDefault().id} · Review spoken or natural-language times before setting."
                else "${ZoneId.systemDefault().id} · Use the calendar or edit the date and time below (YYYY-MM-DDTHH:mm).",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !inputBusy, onClick = { chooseTripTime(context, parsed.getOrNull() ?: initial ?: System.currentTimeMillis()) { text = formatted(it) } }) { Text("Choose on calendar") }
        }
    }, confirmButton = { TextButton(enabled = parsed.isSuccess && !inputBusy, onClick = {
        parsed.getOrNull()?.let { discardVoiceField(context, fieldKey); onSet(it) }
    }, modifier = Modifier.testTag("voiceTimeSet")) { Text("Set time") } },
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } })
}
