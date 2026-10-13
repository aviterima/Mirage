package com.mirage.spike

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

/** Optional standalone model check; CI supplies the verified file before this suite. */
@ExperimentalLanguageModel
class LocalModelAcceptanceTest {
    @Test fun nativeModelInterpretsARealCommandOffline() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        LocalLanguageModel.configure(context)
        assertTrue("Experimental build and verified model are required for native inference", LocalLanguageModel.interpretationEnabled && LocalLanguageModel.ready)
        val instructions=context.assets.open("voice-intent-prompt.txt").bufferedReader().use { it.readText() }
        val user="Saved catalog: [\"Home\"]\nUpcoming stops: []\nInstruction: "+JSONObject.quote("After this take me to my saved home location.")
        val began=android.os.SystemClock.elapsedRealtime()
        val result=VoiceIntent.parse(LocalLanguageModel.interpret(instructions,user))
        android.util.Log.i("MirageModelTest", "Native inference elapsed_ms=${android.os.SystemClock.elapsedRealtime()-began}; action=${result.action}")
        assertEquals("add_saved",result.action);assertEquals("Home",result.target);assertEquals(Placement.NEXT,result.placement)
    }
}
