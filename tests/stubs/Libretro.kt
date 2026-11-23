package com.swordfish.libretrodroid

import android.content.Context
import android.graphics.RectF
import android.opengl.GLSurfaceView
import androidx.lifecycle.LifecycleObserver
import kotlinx.coroutines.flow.Flow

data class Variable(val key: String? = null, val value: String? = null, val description: String? = null)
data class RumbleEvent(val port: Int, val strengthWeak: Float, val strengthStrong: Float)
data class Controller(val id: Int = -1, val description: String? = null)
sealed interface ShaderConfig {
    object Default : ShaderConfig
    object CRT : ShaderConfig
    object LCD : ShaderConfig
    object Sharp : ShaderConfig
}
class GLRetroViewData(context: Context) {
    var coreFilePath: String? = null
    var gameFilePath: String? = null
    var systemDirectory: String = ""
    var savesDirectory: String = ""
    var variables: Array<Variable> = arrayOf()
    var saveRAMState: ByteArray? = null
    var shader: ShaderConfig = ShaderConfig.Default
    var rumbleEventsEnabled: Boolean = true
    var preferLowLatencyAudio: Boolean = true
    var skipDuplicateFrames: Boolean = false
}
class GLRetroView(context: Context, private val data: GLRetroViewData) : GLSurfaceView(context), LifecycleObserver {
    var audioEnabled: Boolean = true
    var frameSpeed: Int = 1
    var runAhead: Boolean = false
    var autoFrameDelay: Boolean = false
    fun isRunAheadActive(): Boolean = false
    var shader: ShaderConfig = ShaderConfig.Default
    var viewport: RectF = RectF()
    fun sendKeyEvent(action: Int, keyCode: Int, port: Int = 0) {}
    fun sendMotionEvent(source: Int, xAxis: Float, yAxis: Float, port: Int = 0) {}
    fun serializeState(useEmulationThread: Boolean = true): ByteArray = ByteArray(0)
    fun unserializeState(data: ByteArray, useEmulationThread: Boolean = true): Boolean = false
    fun serializeSRAM(useEmulationThread: Boolean = true): ByteArray = ByteArray(0)
    fun reset(useEmulationThread: Boolean = true) {}
    fun getGLRetroEvents(): Flow<GLRetroEvents> = TODO()
    fun getGLRetroErrors(): Flow<Int> = TODO()
    fun getRumbleEvents(): Flow<RumbleEvent> = TODO()
    fun setCheat(index: Int, enable: Boolean, code: String, useEmulationThread: Boolean = true) {}
    fun getControllers(): Array<Array<Controller>> = arrayOf()
    fun setControllerType(port: Int, type: Int) {}
    sealed class GLRetroEvents { object FrameRendered : GLRetroEvents(); object SurfaceCreated : GLRetroEvents() }
    companion object {
        const val MOTION_SOURCE_DPAD = 0
        const val MOTION_SOURCE_ANALOG_LEFT = 1
        const val MOTION_SOURCE_ANALOG_RIGHT = 2
        const val ERROR_GENERIC = -1
        const val ERROR_LOAD_LIBRARY = -2
        const val ERROR_LOAD_GAME = -3
        const val ERROR_GL_NOT_COMPATIBLE = -4
        const val ERROR_SERIALIZATION = -5
        const val ERROR_CHEAT = -6
    }
}
