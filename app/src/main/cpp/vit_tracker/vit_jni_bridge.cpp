#include <jni.h>
#include <android/log.h>
#include "vit_tracker.h"

#define JNI_LOG_TAG "VIT-JNI"
#define JNI_LOGI(...) __android_log_print(ANDROID_LOG_INFO, JNI_LOG_TAG, __VA_ARGS__)
#define JNI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, JNI_LOG_TAG, __VA_ARGS__)

// Global tracker instance
static VITTracker* g_tracker = nullptr;

extern "C" {

// ======== nativeInit ========
JNIEXPORT jboolean JNICALL
Java_com_edgedetection_jni_VITTracker_nativeInit(
    JNIEnv* env, jobject thiz,
    jstring calibration_json,
    jfloat target_width,
    jfloat target_length)
{
    if (g_tracker) {
        delete g_tracker;
        g_tracker = nullptr;
    }

    const char* json = env->GetStringUTFChars(calibration_json, nullptr);
    if (!json) {
        JNI_LOGE("Failed to get calibration JSON string");
        return JNI_FALSE;
    }

    g_tracker = new VITTracker();
    bool ok = g_tracker->init(json, (float)target_width, (float)target_length);

    env->ReleaseStringUTFChars(calibration_json, json);

    JNI_LOGI("nativeInit result: %s", ok ? "OK" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ======== nativeProcessFrame ========
JNIEXPORT jobject JNICALL
Java_com_edgedetection_jni_VITTracker_nativeProcessFrame(
    JNIEnv* env, jobject thiz,
    jlong mat_addr,
    jlong frame_timestamp_ns,
    jfloat gyro_x,
    jfloat gyro_y,
    jfloat gyro_z,
    jlong gyro_timestamp_ns,
    jfloat t_flight_sec,
    jfloat pitch,
    jfloat yaw,
    jfloat roll)
{
    // Default return: empty TargetState
    jclass stateClass = env->FindClass("com/edgedetection/jni/VITTracker$TargetState");
    if (!stateClass) {
        JNI_LOGE("TargetState class not found");
        return nullptr;
    }

    cv::Mat& rgba_mat = *(cv::Mat*)mat_addr;
    uint8_t* rgba_data = rgba_mat.data;
    int width = rgba_mat.cols;
    int height = rgba_mat.rows;

    if (!g_tracker) {
        // Return empty state
        jmethodID constructor = env->GetMethodID(stateClass, "<init>",
            "(ZZFFFFFFFFFFF)V");
        if (!constructor) return nullptr;
        return env->NewObject(stateClass, constructor,
            JNI_FALSE, JNI_FALSE,   // detected, tracking
            0.0f, 0.0f, 0.0f, 0.0f, // bbox
            0.0f,                    // distance
            0.0f, 0.0f, 0.0f,       // world xyz
            0.0f, 0.0f, 0.0f,       // vel xyz
            0.0f, 0.0f, 0.0f,       // lead xyz
            0.0f, 0.0f,             // azimuth, elevation
            0.0f);                   // confidence
    }

    TargetState ts = g_tracker->processFrame(
        rgba_data, width, height,
        (int64_t)frame_timestamp_ns,
        (float)gyro_x, (float)gyro_y, (float)gyro_z,
        (int64_t)gyro_timestamp_ns,
        (float)t_flight_sec,
        (float)pitch, (float)yaw, (float)roll
    );

    // Create TargetState object
    jmethodID constructor = env->GetMethodID(stateClass, "<init>",
        "(ZZFFFFFFFFFFF)V");
    if (!constructor) return nullptr;

    return env->NewObject(stateClass, constructor,
        ts.detected ? JNI_TRUE : JNI_FALSE,
        ts.tracking ? JNI_TRUE : JNI_FALSE,
        ts.bbox_x, ts.bbox_y, ts.bbox_w, ts.bbox_h,
        ts.distance_m,
        ts.world_x_m, ts.world_y_m, ts.world_z_m,
        ts.vel_x_mps, ts.vel_y_mps, ts.vel_z_mps,
        ts.lead_x_m, ts.lead_y_m, ts.lead_z_m,
        ts.azimuth_deg, ts.elevation_deg,
        ts.confidence);
}

// ======== nativeGetStabMatrix ========
JNIEXPORT jboolean JNICALL
Java_com_edgedetection_jni_VITTracker_nativeGetStabMatrix(
    JNIEnv* env, jobject thiz,
    jfloatArray out_matrix)
{
    if (!g_tracker) return JNI_FALSE;

    float mat[9];
    if (!g_tracker->getStabHomography(mat)) return JNI_FALSE;

    env->SetFloatArrayRegion(out_matrix, 0, 9, mat);
    return JNI_TRUE;
}

// ======== nativeReset ========
JNIEXPORT void JNICALL
Java_com_edgedetection_jni_VITTracker_nativeReset(
    JNIEnv* env, jobject thiz)
{
    if (g_tracker) {
        g_tracker->reset();
        JNI_LOGI("Tracker reset");
    }
}

// ======== nativeRelease ========
JNIEXPORT void JNICALL
Java_com_edgedetection_jni_VITTracker_nativeRelease(
    JNIEnv* env, jobject thiz)
{
    if (g_tracker) {
        g_tracker->release();
        delete g_tracker;
        g_tracker = nullptr;
        JNI_LOGI("Tracker released");
    }
}

JNIEXPORT void JNICALL
Java_com_edgedetection_jni_VITTracker_nativeDrawOverlay(
    JNIEnv* env, jobject thiz,
    jlong mat_addr,
    jboolean detected,
    jboolean tracking,
    jfloat bbox_x, jfloat bbox_y, jfloat bbox_w, jfloat bbox_h,
    jfloat confidence,
    jfloat pitch, jfloat yaw, jfloat roll,
    jfloat gx, jfloat gy, jfloat gz)
{
    if (!g_tracker) return;

    cv::Mat& mat = *(cv::Mat*)mat_addr;
    TargetState ts;
    ts.detected = (bool)detected;
    ts.tracking = (bool)tracking;
    ts.bbox_x = bbox_x;
    ts.bbox_y = bbox_y;
    ts.bbox_w = bbox_w;
    ts.bbox_h = bbox_h;
    ts.confidence = confidence;

    g_tracker->drawOverlay(mat, ts, pitch, yaw, roll, gx, gy, gz);
}

} // extern "C"
