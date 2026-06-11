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

    // Скорость (м/с)
    float vel_x_mps = 0.0f;
    float vel_y_mps = 0.0f;
    float vel_z_mps = 0.0f;

    // Точка упреждения
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

// ======== Gyro sample ========

struct GyroSample {
    int64_t timestamp_ns;
    float wx, wy, wz; // rad/s
};

// ======== 3D позиция с таймстемпом ========

struct TimedPosition {
    int64_t timestamp_ns;
    float x, y, z;
};

// ======== Lock-free circular buffer for gyro ========

class GyroBuffer {
public:
    static const int CAPACITY = 256;

    GyroBuffer() : head_(0), tail_(0) {}

    void push(int64_t ts, float wx, float wy, float wz) {
        int next = (head_ + 1) % CAPACITY;
        if (next == tail_.load(std::memory_order_acquire)) {
            // buffer full, overwrite oldest
            tail_.store((tail_.load(std::memory_order_relaxed) + 1) % CAPACITY,
                        std::memory_order_release);
        }
        buffer_[head_].timestamp_ns = ts;
        buffer_[head_].wx = wx;
        buffer_[head_].wy = wy;
        buffer_[head_].wz = wz;
        head_ = next;
    }

    bool getInterpolated(int64_t target_ts_ns, int64_t max_delta_ns,
                         float& out_wx, float& out_wy, float& out_wz) const {
        int tail = tail_.load(std::memory_order_acquire);
        int head = head_;

        if (tail == head) return false; // empty

        // Find two samples around target timestamp
        int prev_idx = -1;
        int next_idx = -1;
        int count = 0;
        int idx = tail;

        while (idx != head && count < CAPACITY) {
            if (buffer_[idx].timestamp_ns <= target_ts_ns) {
                prev_idx = idx;
            }
            if (buffer_[idx].timestamp_ns >= target_ts_ns && next_idx == -1) {
                next_idx = idx;
                break;
            }
            idx = (idx + 1) % CAPACITY;
            count++;
        }

        if (prev_idx != -1 && next_idx != -1 && prev_idx != next_idx) {
            // Interpolate between prev and next
            int64_t dt = buffer_[next_idx].timestamp_ns - buffer_[prev_idx].timestamp_ns;
            if (dt == 0) {
                out_wx = buffer_[prev_idx].wx;
                out_wy = buffer_[prev_idx].wy;
                out_wz = buffer_[prev_idx].wz;
                return true;
            }
            float alpha = (float)(target_ts_ns - buffer_[prev_idx].timestamp_ns) / (float)dt;
            alpha = std::max(0.0f, std::min(1.0f, alpha));
            out_wx = buffer_[prev_idx].wx + alpha * (buffer_[next_idx].wx - buffer_[prev_idx].wx);
            out_wy = buffer_[prev_idx].wy + alpha * (buffer_[next_idx].wy - buffer_[prev_idx].wy);
            out_wz = buffer_[prev_idx].wz + alpha * (buffer_[next_idx].wz - buffer_[prev_idx].wz);
            return true;
        }

        if (prev_idx != -1) {
            int64_t delta = target_ts_ns - buffer_[prev_idx].timestamp_ns;
            if (delta < 0) delta = -delta;
            if (delta <= max_delta_ns) {
                out_wx = buffer_[prev_idx].wx;
                out_wy = buffer_[prev_idx].wy;
                out_wz = buffer_[prev_idx].wz;
                return true;
            }
        }

        if (next_idx != -1) {
            int64_t delta = target_ts_ns - buffer_[next_idx].timestamp_ns;
            if (delta < 0) delta = -delta;
            if (delta <= max_delta_ns) {
                out_wx = buffer_[next_idx].wx;
                out_wy = buffer_[next_idx].wy;
                out_wz = buffer_[next_idx].wz;
                return true;
            }
        }

        return false;
    }

    void clear() {
        head_ = 0;
        tail_.store(0, std::memory_order_release);
    }

private:
    GyroSample buffer_[CAPACITY];
    int head_ = 0;
    std::atomic<int> tail_{0};
};

// ======== Main VIT Tracker Class ========

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

    // Для OpenGL шейдера: получить матрицу стабилизации H⁻¹
    bool getStabHomography(float out_matrix[9]) const;

private:
    // Калибровка
    float fx_, fy_, cx_, cy_;
    float k1_, k2_;
    float imu_to_cam_[9]; // 3x3 rotation matrix, row-major
    bool calib_loaded_ = false;

    // Размеры цели
    float target_width_m_ = 2.0f;
    float target_length_m_ = 4.0f;

    // IMU
    GyroBuffer gyro_buffer_;
    int64_t last_frame_ts_ns_ = 0;
    cv::Mat last_dR_; // 3x3 rotation matrix from last frame

    // Трекер
    cv::Ptr<cv::Tracker> tracker_;
    cv::Rect2d bbox_;
    int lost_counter_ = 0;
    bool tracking_active_ = false;
    bool tracker_initialized_ = false;
    int frames_since_detection_ = 0;

    // Кольцевой буфер позиций для скорости
    std::deque<TimedPosition> pos_buffer_;
    static const int MAX_POS_SAMPLES = 5;

    // Фильтрованная скорость (EMA)
    float vel_x_filtered_ = 0.0f;
    float vel_y_filtered_ = 0.0f;
    float vel_z_filtered_ = 0.0f;
    bool velocity_initialized_ = false;

    // Матрица стабилизации для OpenGL
    float stab_homography_[9] = {1,0,0, 0,1,0, 0,0,1};

    // K, K_inv and distortion coefficients
    cv::Mat K_;
    cv::Mat K_inv_;
    cv::Mat dist_coeffs_;

    // Парсинг calib.json
    bool parseCalibration(const char* json);

    // Синхронизация IMU
    bool syncGyro(int64_t frame_ts_ns, float& wx, float& wy, float& wz);

    // Компенсация вращения
    cv::Mat computeDifferentialRotation(float wx, float wy, float wz, float dt_sec);
    void stabilizeFrame(const cv::Mat& src, cv::Mat& dst, const cv::Mat& dR);

    // Детекция/трекинг
    bool detectTarget(const cv::Mat& frame, cv::Rect2d& out_bbox, float& confidence);
    bool trackTarget(const cv::Mat& frame, cv::Rect2d& out_bbox);

    // Триангуляция
    void compute3DPosition(const cv::Rect2d& bbox, float& x, float& y, float& z);

    // Скорость
    void updateVelocity(float x, float y, float z, int64_t ts_ns);

    // Точка упреждения
    void computeLeadPoint(float x, float y, float z, float t_flight,
                          float& lx, float& ly, float& lz);

    // Углы
    void computeAngles(float x, float y, float z,
                       float& azimuth_deg, float& elevation_deg);
};