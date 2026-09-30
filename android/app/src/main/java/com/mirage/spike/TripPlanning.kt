package com.mirage.spike

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import com.mirage.spike.engine.*
import java.util.Calendar

object TripPlanning {
    fun distance(origin: LatLng, stops: List<ItineraryStop>): Double {
        var previous=origin;var total=0.0
        for(stop in stops){total+=Geo.haversine(previous,stop.point);previous=stop.point}
        return total
    }
    /** Geometric proposal only; preserves the final destination and all stop metadata. */
    fun suggest(origin: LatLng, stops: List<ItineraryStop>): List<ItineraryStop> {
        require(stops.size<=100)
        require(stops.none{it.arriveByMillis!=null}) {"Remove arrival targets or reorder manually so their sequence stays under your control"}
        if(stops.size<3) return stops
        val remaining=stops.dropLast(1).toMutableList();val ordered=mutableListOf<ItineraryStop>();var p=origin
        while(remaining.isNotEmpty()) {val i=remaining.indices.minByOrNull{Geo.haversine(p,remaining[it].point)}!!;val next=remaining.removeAt(i);ordered+=next;p=next.point}
        ordered+=stops.last()
        return if(distance(origin,ordered)<distance(origin,stops))ordered else stops
    }
}

internal fun chooseTripTime(context: Context, initial: Long = System.currentTimeMillis(), onSet: (Long)->Unit) {
    val c=Calendar.getInstance().apply{timeInMillis=initial}
    DatePickerDialog(context,{_,year,month,day ->
        c.set(year,month,day)
        TimePickerDialog(context,{_,hour,minute -> c.set(Calendar.HOUR_OF_DAY,hour);c.set(Calendar.MINUTE,minute);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);onSet(c.timeInMillis)},c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),false).show()
    },c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show()
}
internal fun tripTime(value: Long)=java.text.SimpleDateFormat("MMM d, h:mm a",java.util.Locale.getDefault()).format(java.util.Date(value))
