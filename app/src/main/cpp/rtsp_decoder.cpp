#include <jni.h>
#include <opencv2/opencv.hpp>
#include <android/log.h>
#include <cstring>

#define TAG "RtspNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * Convert an NV12 frame (produced by Android MediaCodec) to an RGBA cv::Mat.
 *
 * nv12   : contiguous buffer [Y plane (w*h bytes)] [UV interleaved (w*h/2 bytes)]
 * width  : frame width
 * height : frame height
 * matAddr: native address of the output cv::Mat (already allocated by Java caller)
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_edgedetection_core_camera_RtspCameraSource_nativeNv12ToRgba(
        JNIEnv* env, jclass,
        jbyteArray nv12Data, jint width, jint height,
        jlong matAddr) {

    cv::Mat& out = *(cv::Mat*) matAddr;

    jsize len = env->GetArrayLength(nv12Data);
    jbyte* data = env->GetByteArrayElements(nv12Data, nullptr);
    if (!data) { LOGE("GetByteArrayElements failed"); return; }

    // Wrap as NV12 Mat (height + height/2 rows, width cols, single channel)
    int expectedLen = width * height * 3 / 2;
    if (len < expectedLen) {
        LOGE("Buffer too small: %d < %d", (int)len, expectedLen);
        env->ReleaseByteArrayElements(nv12Data, data, JNI_ABORT);
        return;
    }

    cv::Mat yuv(height + height / 2, width, CV_8UC1, (void*) data);
    cv::cvtColor(yuv, out, cv::COLOR_YUV2RGBA_NV12);

    env->ReleaseByteArrayElements(nv12Data, data, JNI_ABORT);
    LOGD("nativeNv12ToRgba: %dx%d → RGBA %dx%d", width, height, out.cols, out.rows);
}
