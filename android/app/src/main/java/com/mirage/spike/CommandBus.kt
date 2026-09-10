package com.mirage.spike

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Automation entry point. `adb shell am start -n com.mirage.app/com.mirage.spike.MainActivity
 * --es cmd pause --es token XXXX` lands here and is executed by the screen's ViewModel.
 */
object CommandBus {
    val commands = MutableSharedFlow<Map<String, String>>(extraBufferCapacity = 16)
}
