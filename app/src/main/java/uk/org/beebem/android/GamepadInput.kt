package uk.org.beebem.android

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** True if a game controller (built-in handheld controls or an external pad) is connected. */
fun hasGameController(): Boolean = InputDevice.getDeviceIds().any { id ->
    val sources = InputDevice.getDevice(id)?.sources ?: 0
    (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
}

/**
 * Physical game-controller support (built-in handheld controls such as the
 * Anbernic RG557's, or a Bluetooth/USB pad).
 *
 * Each physical control is translated to a "control id" and looked up in the
 * current [JoypadMapping], so the per-disc mappings already configured for the
 * on-screen joypad apply to the physical buttons too:
 *
 *   D-pad, left stick, hat  -> up / down / left / right (shared with the on-screen pad)
 *   Face buttons            -> south / east / west / north, by position
 *                              (the letters vary: the RG557's bottom button is printed B
 *                              but sends BUTTON_A in Xbox mode)
 *   L1, R1, L2, R2, L3, R3, Start -> further per-disc slots
 *   (all physical-only buttons live in JoypadMapping.extras)
 *   Select + Start          -> SHIFT+BREAK (boot the disc in drive 0)
 *
 * While [configListener] is set (joypad CFG mode), presses are reported to it
 * instead of reaching the BBC, so the user can pick a key for that control.
 *
 * All methods are called on the main thread.
 */
class GamepadInput {

    var mapping: JoypadMapping = DefaultJoypadMapping

    /** Non-null while the joypad is in CFG mode. Receives the pressed control id. */
    var configListener: ((String) -> Unit)? = null
        set(value) {
            if (value != null) releaseAll()
            field = value
        }

    private val handler = Handler(Looper.getMainLooper())

    // control id -> bitmask of physical sources currently holding it
    private val sources = HashMap<String, Int>()
    // control id -> BBC key that was pressed for it (released even if the mapping changes)
    private val held = HashMap<String, JoypadKey>()
    // BBC matrix key -> number of controls holding it down
    private val matrixRefs = HashMap<Int, Int>()
    private var selectHeld = false

    // Analogue state, with hysteresis so a stick resting near the threshold doesn't chatter.
    private var stickUp = false; private var stickDown = false
    private var stickLeft = false; private var stickRight = false
    private var l2Axis = false; private var r2Axis = false

    // ── Key events ─────────────────────────────────────────────────────────

    /** True if this key event comes from a game controller rather than a typing keyboard. */
    fun isGamepadEvent(event: KeyEvent): Boolean {
        if (KeyEvent.isGamepadButton(event.keyCode)) return true
        if (event.keyCode !in DPAD_KEYS) return false
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return true
        // Arrow keys on a real keyboard stay as BBC cursor keys.
        val device = event.device ?: return false
        return device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    /** Returns true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val id = controlIdForKey(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) setSource(id, SRC_KEY, true)
            KeyEvent.ACTION_UP   -> setSource(id, SRC_KEY, false)
        }
        return true
    }

    // ── Motion events (sticks, hat, analogue triggers) ─────────────────────

    /** Returns true if the event was consumed. */
    fun onMotionEvent(event: MotionEvent): Boolean {
        val fromPad = event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
                      event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!fromPad || event.action != MotionEvent.ACTION_MOVE) return false

        // Hat (some controllers report the D-pad this way instead of as keys)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        setSource("left",  SRC_HAT, hx < -0.5f)
        setSource("right", SRC_HAT, hx >  0.5f)
        setSource("up",    SRC_HAT, hy < -0.5f)
        setSource("down",  SRC_HAT, hy >  0.5f)

        // Left stick
        val sx = event.getAxisValue(MotionEvent.AXIS_X)
        val sy = event.getAxisValue(MotionEvent.AXIS_Y)
        if (mapping.analogueStick && configListener == null) {
            // Drive the BBC analogue joystick: -1..1 -> 65535 (left/up) .. 0 (right/down).
            BeebEmNative.nativeSetJoystick(((1f - sx) * 32767.5f).toInt(), ((1f - sy) * 32767.5f).toInt())
            stickUp = false; stickDown = false; stickLeft = false; stickRight = false
        } else {
            stickLeft  = hysteresis(stickLeft,  -sx, STICK_ON, STICK_OFF)
            stickRight = hysteresis(stickRight,  sx, STICK_ON, STICK_OFF)
            stickUp    = hysteresis(stickUp,    -sy, STICK_ON, STICK_OFF)
            stickDown  = hysteresis(stickDown,   sy, STICK_ON, STICK_OFF)
        }
        setSource("left",  SRC_STICK, stickLeft)
        setSource("right", SRC_STICK, stickRight)
        setSource("up",    SRC_STICK, stickUp)
        setSource("down",  SRC_STICK, stickDown)

        // Analogue triggers (reported as LTRIGGER/RTRIGGER or BRAKE/GAS depending on device)
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        l2Axis = hysteresis(l2Axis, lt, TRIGGER_ON, TRIGGER_OFF)
        r2Axis = hysteresis(r2Axis, rt, TRIGGER_ON, TRIGGER_OFF)
        setSource("l2", SRC_AXIS, l2Axis)
        setSource("r2", SRC_AXIS, r2Axis)
        return true
    }

    // ── Housekeeping ──────────────────────────────────────────────────────

    /** Releases every BBC key held by the controller (pause, overlays, CFG mode). */
    fun releaseAll() {
        releaseHeldKeys()
        sources.clear()
        selectHeld = false
        stickUp = false; stickDown = false; stickLeft = false; stickRight = false
        l2Axis = false; r2Axis = false
        BeebEmNative.nativeSetJoystick(32768, 32768)   // centre the BBC joystick
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun setSource(id: String, bit: Int, on: Boolean) {
        val before = sources[id] ?: 0
        val after = if (on) before or bit else before and bit.inv()
        if (after == 0) sources.remove(id) else sources[id] = after
        if ((before == 0) == (after == 0)) return      // no change in overall pressed state
        if (after != 0) press(id) else release(id)
    }

    private fun press(id: String) {
        configListener?.let { listener ->
            if (id != "select") listener(id)
            return
        }
        if (id == "select") { selectHeld = true; return }
        if (id == "start" && selectHeld) { shiftBreak(); return }

        val key = keyFor(id) ?: return                  // unassigned extra button
        held[id] = key
        if (key.shifted) matrixDown(0, 0)
        matrixDown(key.row, key.col)
    }

    private fun release(id: String) {
        if (id == "select") { selectHeld = false; return }
        val key = held.remove(id) ?: return
        matrixUp(key.row, key.col)
        if (key.shifted) matrixUp(0, 0)
    }

    private fun releaseHeldKeys() {
        held.keys.toList().forEach { release(it) }
        held.clear()
        matrixRefs.clear()
    }

    // Same sequence as the ⋮ → SHF+BRK menu item.
    private fun shiftBreak() {
        releaseHeldKeys()
        BeebEmNative.nativeBreakKey(true)
        handler.postDelayed({ BeebEmNative.nativeKeyUp(0, 0) }, 1500)
    }

    private fun keyFor(id: String): JoypadKey? = when (id) {
        "up"    -> mapping.up
        "down"  -> mapping.down
        "left"  -> mapping.left
        "right" -> mapping.right
        "fire1" -> mapping.fire1
        "fire2" -> mapping.fire2
        "fire3" -> mapping.fire3
        else    -> mapping.extras[id]
    }

    // Reference-counted so two controls mapped to the same BBC key don't
    // release it while the other is still held.
    private fun matrixDown(row: Int, col: Int) {
        val k = row * 16 + col
        val n = (matrixRefs[k] ?: 0) + 1
        matrixRefs[k] = n
        if (n == 1) BeebEmNative.nativeKeyDown(row, col)
    }

    private fun matrixUp(row: Int, col: Int) {
        val k = row * 16 + col
        val n = matrixRefs[k] ?: return
        if (n <= 1) {
            matrixRefs.remove(k)
            BeebEmNative.nativeKeyUp(row, col)
        } else {
            matrixRefs[k] = n - 1
        }
    }

    private fun hysteresis(current: Boolean, value: Float, on: Float, off: Float) =
        if (current) value > off else value > on

    private fun controlIdForKey(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP       -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN     -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT     -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT    -> "right"
        // Android's face-button codes follow position (Xbox layout):
        // A bottom, B right, X left, Y top.
        KeyEvent.KEYCODE_BUTTON_A      -> "south"
        KeyEvent.KEYCODE_BUTTON_B      -> "east"
        KeyEvent.KEYCODE_BUTTON_X      -> "west"
        KeyEvent.KEYCODE_BUTTON_Y      -> "north"
        KeyEvent.KEYCODE_BUTTON_L1     -> "l1"
        KeyEvent.KEYCODE_BUTTON_R1     -> "r1"
        KeyEvent.KEYCODE_BUTTON_L2     -> "l2"
        KeyEvent.KEYCODE_BUTTON_R2     -> "r2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "l3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "r3"
        KeyEvent.KEYCODE_BUTTON_START  -> "start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "select"
        else -> null
    }

    private companion object {
        const val SRC_KEY   = 1
        const val SRC_HAT   = 2
        const val SRC_STICK = 4
        const val SRC_AXIS  = 8

        const val STICK_ON    = 0.5f
        const val STICK_OFF   = 0.35f
        const val TRIGGER_ON  = 0.5f
        const val TRIGGER_OFF = 0.3f

        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
