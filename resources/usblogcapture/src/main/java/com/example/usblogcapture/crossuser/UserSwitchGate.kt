package com.example.usblogcapture.crossuser

/**
 * Wraps IUserSwitchObserver as a plain contract, decoupled from the reflection
 * registration itself. [dispatchBeforeSwitch] is the important one: call it from
 * your `onUserSwitching(newUserId, IRemoteCallback reply)` implementation and only
 * invoke `reply` once [BeforeSwitchHandler.onBeforeSwitch]'s `done()` fires — that's
 * what actually blocks the switch until USB handles are released, closing the race
 * with vold's interrupt.
 */
class UserSwitchGate {

    fun interface BeforeSwitchHandler {
        /** Must call `done()` once all USB handles are released. */
        fun onBeforeSwitch(newUserId: Int, done: () -> Unit)
    }

    fun interface SwitchCompleteHandler {
        fun onSwitchComplete(newUserId: Int)
    }

    private var beforeSwitchHandler: BeforeSwitchHandler? = null
    private var switchCompleteHandler: SwitchCompleteHandler? = null

    fun setBeforeSwitchHandler(handler: BeforeSwitchHandler) {
        beforeSwitchHandler = handler
    }

    fun setSwitchCompleteHandler(handler: SwitchCompleteHandler) {
        switchCompleteHandler = handler
    }

    /** Call from IUserSwitchObserver.onUserSwitching(newUserId, reply). */
    fun dispatchBeforeSwitch(newUserId: Int, reply: () -> Unit) {
        val handler = beforeSwitchHandler
        if (handler == null) {
            reply()
            return
        }
        handler.onBeforeSwitch(newUserId, done = reply)
    }

    /** Call from IUserSwitchObserver.onUserSwitchComplete(newUserId). */
    fun dispatchSwitchComplete(newUserId: Int) {
        switchCompleteHandler?.onSwitchComplete(newUserId)
    }
}
