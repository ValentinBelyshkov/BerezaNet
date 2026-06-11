#pragma once

#include <cstdint>
#include <cstring>
#include <atomic>
#include <vector>
#include <deque>
#include <opencv2/opencv.hpp>
#include <android/log.h>

#define VIT_LOG_TAG "VIT-Tracker"
#define VIT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VIT_LOG_TAG, __VA_ARGS__)
#define VIT_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, VIT_LOG_TAG, __VA_ARGS__)
#define VIT_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VIT_LOG_TAG, __VA_ARGS__)
#define VIT_LOGW(...) __android_log_print(ANDROID_LOG_WARN, VIT_LOG_TAG, __VA_ARGS__)

// ======== Структура результата ========

struct TargetState {
    bool detected = false;
    bool tracking = false;

    // 2D на стабилизированном кадре
    float bbox_x = 0.0f, bbox_y = 0.0f, bbox_w = 0.0f, bbox_h = 0.0f;

    // 3D оценка
    float distance_m = 0.0f;
    float world_x_m = 0.0f;
    float world_y_m = 0.0f;
    float world_z_m = 0.0f;

    // Скорость (м/с или пикс/с в зависимости от логики, тут оставим м/с для совместимости)
    float vel_x_mps = 0.0f;
    float vel_y_mps = 0.0f;
    float vel_z_mps = 0.0f;

    // Точка упреждения (в пикселях для новой логики)
    float lead_x_m = 0.0f;
    float lead_y_m = 0.0f;
    float lead_z_m = 0.0f;

    // Угловое направление
    float azimuth_deg = 0.0f;
    float elevation_deg = 0.0f;

    // Метаданные
    float confidence = 0.0f;
    int64_t timestamp_ns = 0;
};

struct TrackedBlob {
    float x;
    float y;
    float size;
    float confidence;
};

struct PositionSample {
    double time;
    float x;
    float y;
};

class VITTracker {
public:
    VITTracker();
    ~VITTracker();

    bool init(const char* calib_json, float target_w, float target_l);
    TargetState processFrame(
        uint8_t* rgba_data, int w, int h, int64_t frame_ts_ns,
        float gyro_x, float gyro_y, float gyro_z, int64_t gyro_ts_ns,
        float t_flight_sec
    );
    void reset();
    void release();

    // Для OpenGL шейдера: получить матрицу стабилизации H⁻¹ (в новой логике может быть единичной)
    bool getStabHomography(float out_matrix[9]) const;

private:
    // Параметры SphereTracker
    int canny_low = 50;
    int canny_high = 150;
    int min_blob_size = 10;
    int max_blob_size = 500;
    float min_circularity = 0.5f;
    cv::Size blur_kernel = cv::Size(5, 5);
    size_t history_size = 30;

    std::deque<PositionSample> position_history;

    // Калибровка (сохраняем для FOV)
    float fx_ = 640.0f, fy_ = 640.0f, cx_ = 320.0f, cy_ = 240.0f;
    float target_width_m_ = 2.0f;
    bool calib_loaded_ = false;

    // Матрица стабилизации (для совместимости с шейдером)
    float stab_homography_[9] = {1,0,0, 0,1,0, 0,0,1};

    void updatePositionHistory(const TrackedBlob& blob, double timestamp);
    cv::Point2f getBlobVelocity();
    cv::Point2f predictPosition(
        double future_time,
        const TrackedBlob& current_blob,
        float gyro_yaw_rate,
        float gyro_pitch_rate,
        int screen_width,
        int screen_height
    );

    std::vector<TrackedBlob> detectBlobs(const cv::Mat& edges);
    bool parseCalibration(const char* json);
};
