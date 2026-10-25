package uk.org.beebem.android

import android.content.res.AssetManager
import java.nio.ByteBuffer

object BeebEmNative {
    init { System.loadLibrary("beebem") }

    external fun nativeInit(assetManager: AssetManager, dataDir: String): Boolean
    external fun nativeShutdown()
    external fun nativeReset(hardReset: Boolean)

    // Returns true if a complete BBC frame was ready and frameBuffer was filled.
    // Returns false if the BBC hasn't completed a new VSYNC since the last call;
    // caller should re-render the existing GL texture without uploading.
    external fun nativeRunFrame(frameBuffer: ByteBuffer, widthOut: IntArray, heightOut: IntArray, activeWidthOut: IntArray): Boolean
    // Displayed (non-border) rows of the frame last returned by nativeRunFrame:
    // out[0] = first row, out[1] = last row (inclusive), out[2] = rows per TV scanline.
    external fun nativeGetDisplayRows(out: IntArray)
    external fun nativeGetCycleCount(): Long
    // A copy of len bytes of the BBC's main RAM (0..&7FFF) from addr; null if out of range.
    external fun nativeReadMemory(addr: Int, len: Int): ByteArray?

    external fun nativeKeyDown(beebRow: Int, beebCol: Int)
    external fun nativeKeyUp(beebRow: Int, beebCol: Int)
    external fun nativeBreakKey(shift: Boolean)
    // BBC analogue joystick 1: x/y 0..65535 (65535 = left/up, 32768 = centre).
    external fun nativeSetJoystick(x: Int, y: Int)

    external fun nativeMountDisc(drive: Int, path: String, writeProtect: Boolean): Boolean
    external fun nativeEjectDisc(drive: Int)
    external fun nativeFlushDisc(drive: Int): Boolean

    external fun nativeSaveState(path: String): Boolean
    external fun nativeLoadState(path: String): Boolean

    external fun nativeSetSoundEnabled(enabled: Boolean)
    external fun nativeSetSpeedLimit(percent: Int)
}
