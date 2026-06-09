package com.edgedetection.jni

import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * VIT Tracker - визуально-инерциальный трекинг цели
 *
 * Получает видеопоток RGBA и данные гироскопа, компенсирует ego-motion,
 * определяет 3D-положение цели и выдаёт точку упреждения.
 */
class VITTracker {
    companion object {
        private const val TAG = "VITTracker"
        private var libraryLoaded = false

        init {
            try {
                System.loadLibrary("vit_tracker")
                libraryLoaded = true
                Log.i(TAG, "Native vit_tracker library loaded successfully!")
            } catch (e: UnsatisfiedLinkError) {
                libraryLoaded = false
                Log.e(TAG, "Failed to load native vit_tracker library: ${e.message}")
            }
        }

        fun isLibraryLoaded(): Boolean = libraryLoaded
    }

    /**
     * Состояние цели на каждом кадре
     */
    data class TargetState(
        val detected: Boolean,
        val tracking: Boolean,
        val bboxX: Float, val bboxY: Float, val bboxW: Float, val bboxH: Float,
        val distanceM: Float,
        val worldX: Float, val worldY: Float, val worldZ: Float,
        val velX: Float, val velY: Float, val velZ: Float,
        val leadX: Float, val leadY: Float, val leadZ: Float,
        val azimuthDeg: Float,
        val elevationDeg: Float,
        val confidence: Float
    ) {
        companion object {
            val EMPTY = TargetState(
                detected = false, tracking = false,
                bboxX = 0f, bboxY = 0f, bboxW = 0f, bboxH = 0f,
                distanceM = 0f,
                worldX = 0f, worldY = 0f, worldZ = 0f,
                velX = 0f, velY = 0f, velZ = 0f,
                leadX = 0f, leadY = 0f, leadZ = 0f,
                azimuthDeg = 0f, elevationDeg = 0f,
                confidence = 0f
            )
        }
    }

    private var initialized = false

    /**
     * Инициализация трекера
     *
     * @param context Context для доступа к assets
     * @param targetWidth Ширина цели в метрах (по умолчанию 2.0)
     * @param targetLength Длина цели в метрах (по умолчанию 4.0)
     */
    fun init(context: Context, targetWidth: Float = 2.0f, targetLength: Float = 4.0f): Boolean {
        if (!libraryLoaded) {
            Log.e(TAG, "Cannot init - library not loaded")
            return false
        }

        // Load calibration from assets
        val calibJson = try {
            context.assets.open("calib.json").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.w(TAG, "calib.json not found in assets, using defaults: ${e.message}")
            // Default calibration for 640x480 with ~60° FOV
            """{"fx":640.0,"fy":640.0,"cx":320.0,"cy":240.0,"k1":0.0,"k2":0.0,"imu_to_cam":[[1,0,0],[0,1,0],[0,0,1]]}"""
        }

        val ok = nativeInit(calibJson, targetWidth, targetLength)
        initialized = ok
        Log.i(TAG, "VITTracker init: $ok")
        return ok
    }

    /**
     * Обработать кадр
     *
     * @param rgbaBuffer Direct ByteBuffer с RGBA данными кадра
     * @param width Ширина кадра
     * @param height Высота кадра
     * @param frameTimestampNs Таймстемп кадра (наносекунды)
     * @param gyroX Гироскоп X (rad/s)
     * @param gyroY Гироскоп Y (rad/s)
     * @param gyroZ Гироскоп Z (rad/s)
     * @param gyroTimestampNs Таймстемп гироскопа (наносекунды)
     * @param tFlightSec Время полёта снаряда (секунды, по умолчанию 2.0)
     */
    fun processFrame(
        rgbaBuffer: ByteBuffer,
        width: Int, height: Int, frameTimestampNs: Long,
        gyroX: Float, gyroY: Float, gyroZ: Float, gyroTimestampNs: Long,
        tFlightSec: Float = 2.0f
    ): TargetState {
        if (!initialized || !libraryLoaded) {
            return TargetState.EMPTY
        }

        return nativeProcessFrame(
            rgbaBuffer, width, height, frameTimestampNs,
            gyroX, gyroY, gyroZ, gyroTimestampNs,
            tFlightSec
        )
    }

    /**
     * Получить матрицу стабилизации для OpenGL шейдера
     * @return 3x3 matrix (9 floats) or null
     */
    fun getStabMatrix(): FloatArray? {
        if (!initialized) return null
        val mat = FloatArray(9)
        return if (nativeGetStabMatrix(mat)) mat else null
    }

    /**
     * Сброс трекера (очистить состояние, не пересоздавая)
     */
    fun reset() {
        initialized = false
        nativeReset()
    }

    /**
     * Полное освобождение ресурсов
     */
    fun release() {
        initialized = false
        nativeRelease()
    }

    // ======== Native methods ========

    private external fun nativeInit(
        calibrationJson: String,
        targetWidth: Float,
        targetLength: Float
    ): Boolean

    private external fun nativeProcessFrame(
        rgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        frameTimestampNs: Long,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        gyroTimestampNs: Long,
        tFlightSec: Float
    ): TargetState

    private external fun nativeGetStabMatrix(outMatrix: FloatArray): Boolean

    private external fun nativeReset()

    private external fun nativeRelease()
}