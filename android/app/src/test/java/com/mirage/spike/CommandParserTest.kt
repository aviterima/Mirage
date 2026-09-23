package com.mirage.spike

import com.mirage.spike.engine.TravelMode
import org.junit.Assert.*
import org.junit.Test

class CommandParserTest {
    @Test fun `stop is ambiguous but explicit stop restores reality`() {
        assertEquals(SpokenCommand.ClarifyStop, CommandParser.parse("Stop!"))
        assertEquals(SpokenCommand.Stop, CommandParser.parse("Stop simulation and return to my real location"))
    }
    @Test fun `spoken itinerary keeps the stay attached to the correct destination`() {
        val cmd = CommandParser.parse("Drive to the library then stay for thirty minutes then walk to the cafe") as SpokenCommand.Journey
        assertEquals(2, cmd.legs.size)
        assertEquals(30, cmd.legs[0].minutes)
        assertEquals(TravelMode.WALK, cmd.legs[1].mode)
        assertEquals("the cafe", cmd.legs[1].query)
    }
    @Test fun `queue intent and immediate intent remain distinct`() {
        assertTrue((CommandParser.parse("After this, drive to Sky Harbor") as SpokenCommand.Journey).next)
        assertFalse((CommandParser.parse("drive to Sky Harbor") as SpokenCommand.Journey).next)
    }
    @Test fun `unknown instruction never silently triggers a command`() {
        assertTrue(CommandParser.parse("don't stop the simulation") is SpokenCommand.Unknown)
        assertTrue(CommandParser.parse("drive") is SpokenCommand.Unknown)
        assertTrue(CommandParser.parse("extend my holiday") is SpokenCommand.Unknown)
        assertTrue(CommandParser.parse("after this, snap to London") is SpokenCommand.Unknown)
    }
    @Test fun `durations accept speech and reject excessive stays`() {
        assertEquals(SpokenCommand.Extend(30), CommandParser.parse("stay for another half an hour"))
        assertNull(CommandParser.minutes("100 hours"))
    }
    @Test fun `ordinal targets upcoming stops`() {
        assertEquals(SpokenCommand.Remove(2), CommandParser.parse("remove the second stop"))
        assertEquals(SpokenCommand.Choice(2), CommandParser.parse("the second one"))
    }
}
