package com.mirage.spike

import com.mirage.spike.engine.LiveStop

/** References are resolved by the app against a confirmed stop, never guessed by the model. */
class VoiceFollowUp {
    private var stopId: String? = null
    private var epoch = -1L
    private var expires = 0L
    fun remember(id: String, session: Long, now: Long = System.currentTimeMillis()) { stopId=id; epoch=session; expires=now+120_000 }
    fun clear() { stopId=null; expires=0 }
    fun resolve(text: String, stops: List<LiveStop>, currentIndex: Int, session: Long, now: Long = System.currentTimeMillis()): VoiceIntent? {
        if(session!=epoch || now>expires) { clear(); return null }
        val upcoming=stops.drop(currentIndex+1)
        val target=upcoming.singleOrNull{it.id==stopId} ?: return null
        if(upcoming.count{it.stop.name.equals(target.stop.name,true)}!=1) return null
        val normalized=CommandParser.normalize(text).removePrefix("actually ")
        val stay=Regex("^(?:make|set|change) (?:its|the) stay (?:to )?(.+)$").matchEntire(normalized)
        if(stay!=null) {
            val parsed=CommandParser.parse("stay for "+stay.groupValues[1]) as? SpokenCommand.Stay
            if(parsed!=null) return VoiceIntent("stay",target.stop.name,minutes=parsed.minutes)
        }
        val after=Regex("^(?:put|move) it after (.+)$").matchEntire(normalized)
        if(after!=null) {
            val rest=upcoming.filterNot{it.id==target.id}
            val anchor=rest.singleOrNull{it.stop.name.equals(after.groupValues[1],true)} ?: return null
            return VoiceIntent("move",target.stop.name,position=rest.indexOf(anchor)+2)
        }
        if(normalized in setOf("remove it","delete that stop")) return VoiceIntent("remove",target.stop.name)
        if(normalized in setOf("put it last","move it to the end")) return VoiceIntent("move",target.stop.name,position=upcoming.size)
        return null
    }
}
