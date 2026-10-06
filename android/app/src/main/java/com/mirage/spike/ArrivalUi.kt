package com.mirage.spike

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirage.spike.engine.ArrivalActivity

@Composable
internal fun ArrivalActivityPicker(value: ArrivalActivity, onChange: (ArrivalActivity)->Unit) {
    Text("On arrival", style=MaterialTheme.typography.labelLarge)
    ArrivalActivity.entries.chunked(2).forEach { choices ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            choices.forEach { choice ->
                FilterChip(selected=value==choice,onClick={onChange(choice)},
                    label={Text(choice.label)},modifier=Modifier.weight(1f))
            }
        }
    }
    if(value != ArrivalActivity.OUTDOOR) Text(
        "Walk from the road to the destination pin, then stay there. Place the pin inside the building for an indoor stay. The walking connection is approximate; rooms, tables and floors are not mapped. Flights arrive directly at their pin.",
        style=MaterialTheme.typography.bodySmall)
}

@Composable
internal fun StayChoicePicker(minutes: Int, untilLeave: Boolean, onMinutes: (Int)->Unit, onHold: (Boolean)->Unit) {
    Text("After arrival",style=MaterialTheme.typography.labelLarge)
    Column {
        FilterChip(selected=untilLeave,onClick={onHold(true)},label={Text("Stay until I leave")})
        FilterChip(selected=!untilLeave && minutes==0,onClick={onHold(false);onMinutes(0)},label={Text("Continue immediately")})
        FilterChip(selected=!untilLeave && minutes>0,onClick={onHold(false);onMinutes(if(minutes>0)minutes else 30)},label={Text("Stay for a set time")})
    }
    if(!untilLeave && minutes>0) Row {
        TextButton(onClick={onMinutes((minutes-5).coerceAtLeast(1))}) {Text("− 5 min")}
        Text("$minutes min")
        TextButton(onClick={onMinutes((minutes+5).coerceAtMost(1440))}) {Text("+ 5 min")}
    }
    if(untilLeave) Text("Departure is manual. Later arrival estimates exclude this wait.",style=MaterialTheme.typography.bodySmall)
    if(!untilLeave && minutes==0) Text("Continues to the next stop; the final stop remains held until Stop.",style=MaterialTheme.typography.bodySmall)
}
