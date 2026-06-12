#include "vit_tracker.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

// ======== Constructor / Destructor ========

VITTracker::VITTracker()
    : fx_(0), fy_(0), cx_(0), cy_(0), k1_(0), k2_(0),
      calib_loaded_(false), tracking_active_(false), tracker_initialized_(false),
      velocity_initialized_(false), last_frame_ts_ns_(0), lost_counter_(0),
      frames_since_detection_(0), vel_x_filtered_(0), vel_y_filtered_(0), vel_z_filtered_(0)
{
    std::fill(imu_to_cam_, imu_to_cam_ + 9, 0.0f);
    imu_to_cam_[0] = imu_to_cam_[4] = imu_to_cam_[8] = 1.0f; // identity

    std::fill(stab_homography_, stab_homography_ + 9, 0.0f);
    stab_homography_[0] = stab_homography_[4] = stab_homography_[8] = 1.0f;
}

VITTracker::~VITTracker() {
    release();
}

// ======== Init ========

bool VITTracker::init(const char* calib_json, float target_w, float target_l) {
    target_width_m_ = target_w;
    target_length_m_ = target_l;

    if (!parseCalibration(calib_json)) {
        VIT_LOGE("Failed to parse calibration JSON");
        // Use reasonable defaults for 640x480, ~60° FOV
        fx_ = 640.0f;
        fy_ = 640.0f;
        cx_ = 320.0f;
        cy_ = 240.0f;
        k1_ = 0.0f;
        k2_ = 0.0f;
    }

    // Build K and K_inv
    K_ = (cv::Mat_<double>(3,3) <<
        fx_, 0, cx_,
        0, fy_, cy_,
        0, 0, 1);

    K_inv_ = K_.inv();

    // Build distortion coefficients
    dist_coeffs_ = (cv::Mat_<double>(4,1) << k1_, k2_, 0.0, 0.0);

    // Build R_imu_to_cam
    // imu_to_cam_ is stored row-major, OpenCV Mat is row-major
    VIT_LOGI("Calibration loaded: fx=%.1f fy=%.1f cx=%.1f cy=%.1f",
             fx_, fy_, cx_, cy_);
    VIT_LOGI("Target size: %.1f x %.1f m", target_width_m_, target_length_m_);

    calib_loaded_ = true;
    return true;
}

// ======== Release ========

void VITTracker::release() {
    tracker_ = nullptr;
    gyro_buffer_.clear();
    pos_buffer_.clear();
    tracking_active_ = false;
    tracker_initialized_ = false;
    velocity_initialized_ = false;
    calib_loaded_ = false;
}

// ======== Reset ========

void VITTracker::reset() {
    tracker_ = nullptr;
    gyro_buffer_.clear();
    pos_buffer_.clear();
    tracking_active_ = false;
    tracker_initialized_ = false;
    velocity_initialized_ = false;
    last_frame_ts_ns_ = 0;
    lost_counter_ = 0;
    frames_since_detection_ = 0;
    vel_x_filtered_ = vel_y_filtered_ = vel_z_filtered_ = 0.0f;
    last_dR_ = cv::Mat::eye(3, 3, CV_64F);
    std::fill(stab_homography_, stab_homography_ + 9, 0.0f);
    stab_homography_[0] = stab_homography_[4] = stab_homography_[8] = 1.0f;
    last_blobs_.clear();
}

// ======== Парсинг JSON ========

bool VITTracker::parseCalibration(const char* json) {
    if (!json || strlen(json) == 0) return false;

    // Minimal JSON parser for the calib.json structure
    // Expected format: {"fx":640,"fy":640,"cx":320,"cy":240,"k1":0,"k2":0,"imu_to_cam":[[1,0,0],[0,1,0],[0,0,1]]}
    std::string s(json);

    auto findNum = [&s](const std::string& key, float& val) -> bool {
        auto pos = s.find("\"" + key + "\"");
        if (pos == std::string::npos) return false;
        pos = s.find(':', pos);
        if (pos == std::string::npos) return false;
        pos++;
        while (pos < s.size() && (s[pos] == ' ' || s[pos] == '\t')) pos++;
        std::string num;
        bool neg = false;
        if (pos < s.size() && s[pos] == '-') { neg = true; pos++; }
        bool hasDot = false;
        while (pos < s.size() && (isdigit(s[pos]) || s[pos] == '.')) {
            if (s[pos] == '.') hasDot = true;
            num += s[pos];
            pos++;
        }
        if (num.empty()) return false;
        val = std::stof(num);
        if (neg) val = -val;
        return true;
    };

    findNum("fx", fx_);
    findNum("fy", fy_);
    findNum("cx", cx_);
    findNum("cy", cy_);
    findNum("k1", k1_);
    findNum("k2", k2_);

    // Parse imu_to_cam matrix
    auto matPos = s.find("\"imu_to_cam\"");
    if (matPos != std::string::npos) {
        auto bracketPos = s.find('[', matPos);
        if (bracketPos != std::string::npos) {
            int row = 0, col = 0;
            auto p = bracketPos;
            int depth = 0;
            while (p < s.size() && row < 3) {
                if (s[p] == '[') {
                    depth++;
                } else if (s[p] == ']') {
                    depth--;
                    // Закрытие внутренней скобки = конец строки матрицы
                    if (depth == 1 && col > 0) {
                        row++;
                        col = 0;
                    }
                } else if (s[p] == '-' || isdigit(s[p]) || s[p] == '.') {
                    auto start = p;
                    if (s[p] == '-') p++;
                    while (p < s.size() && (isdigit(s[p]) || s[p] == '.' || s[p] == 'e' || s[p] == 'E' || s[p] == '+' || s[p] == '-')) {
                        // Поддержка научной нотации, но осторожно с '-' после 'e'
                        if ((s[p] == '+' || s[p] == '-') && p > start && s[p-1] != 'e' && s[p-1] != 'E') break;
                        p++;
                    }
                    if (p > start && row < 3 && col < 3) {
                        float val = std::stof(s.substr(start, p - start));
                        imu_to_cam_[row * 3 + col] = val;
                        col++;
                    }
                    continue; // не инкрементируем p
                }
                p++;
            }
        }
    }

    // VIT_LOGI("Parsed calib: fx=%.2f fy=%.2f cx=%.2f cy=%.2f k1=%.6f k2=%.6f",
    //          fx_, fy_, cx_, cy_, k1_, k2_);

    return (fx_ > 0 && fy_ > 0);
}

// ======== Основной processFrame ========

TargetState VITTracker::processFrame(
    uint8_t* rgba_data, int w, int h, int64_t frame_ts_ns,
    float gyro_x, float gyro_y, float gyro_z, int64_t gyro_ts_ns,
    float t_flight_sec,
    float pitch, float yaw, float roll)
{
    TargetState state;
    state.timestamp_ns = frame_ts_ns;

    if (!calib_loaded_ || !rgba_data || w <= 0 || h <= 0) {
        return state;
    }

    // Always update last_frame_ts_ns_ if it is the first frame
    if (last_frame_ts_ns_ == 0) {
        last_frame_ts_ns_ = frame_ts_ns;
    }

    // 1. Push gyro sample into buffer
    gyro_buffer_.push(gyro_ts_ns, gyro_x, gyro_y, gyro_z);

    // 2. Create OpenCV Mat from RGBA data (no copy - wraps the buffer)
    cv::Mat frame(h, w, CV_8UC4, rgba_data);

    // 3. Compensation for distortion
    cv::Mat undistorted;
    cv::undistort(frame, undistorted, K_, dist_coeffs_);

    // 4. Sync gyro - find or interpolate gyro for this frame timestamp
    float gx = 0, gy = 0, gz = 0;
    bool gyro_ok = syncGyro(frame_ts_ns, gx, gy, gz);
    cv::Mat frame_for_tracking;

    if (gyro_ok) {
        // Transform gyro to camera coordinate system using imu_to_cam rotation
        float gx_cam = imu_to_cam_[0] * gx + imu_to_cam_[1] * gy + imu_to_cam_[2] * gz;
        float gy_cam = imu_to_cam_[3] * gx + imu_to_cam_[4] * gy + imu_to_cam_[5] * gz;
        float gz_cam = imu_to_cam_[6] * gx + imu_to_cam_[7] * gy + imu_to_cam_[8] * gz;

        // Compute dt since last frame
        float dt_sec = 0.0f;
        if (last_frame_ts_ns_ > 0 && frame_ts_ns > last_frame_ts_ns_) {
            dt_sec = (float)((double)(frame_ts_ns - last_frame_ts_ns_) / 1.0e9);
            if (dt_sec > 0.1f) dt_sec = 0.033f; // cap at ~30fps
        } else {
            dt_sec = 0.033f; // default 30fps
        }

        // Compute differential rotation dR = exp(ω × dt)
        cv::Mat dR = computeDifferentialRotation(gx_cam, gy_cam, gz_cam, dt_sec);

        // Stabilize frame using homography H = K * dR * K_inv
        cv::Mat stabilized;
        stabilizeFrame(undistorted, stabilized, dR);

        // Store dR for next frame
        last_dR_ = dR.clone();

        // Update stab_homography_ for OpenGL
        cv::Mat H = K_ * dR * K_inv_;
        // Store H_inv for OpenGL (we need to transform UVs)
        cv::Mat H_inv = H.inv(cv::DECOMP_LU);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                stab_homography_[i * 3 + j] = (float)H_inv.at<double>(i, j);
            }
        }

        frame_for_tracking = stabilized;
    } else {
        VIT_LOGW("No gyro data for frame ts=%lld, using raw frame", (long long)frame_ts_ns);
        frame_for_tracking = undistorted;

        // Reset stabilization homography to identity since no stabilization is applied
        std::fill(stab_homography_, stab_homography_ + 9, 0.0f);
        stab_homography_[0] = stab_homography_[4] = stab_homography_[8] = 1.0f;
    }

    // Always update last_frame_ts_ns_
    last_frame_ts_ns_ = frame_ts_ns;

    // 5. Blob detection for visualization (even if tracking)
    {
        cv::Mat gray;
        cv::cvtColor(frame_for_tracking, gray, cv::COLOR_RGBA2GRAY);
        cv::Mat binary;
        cv::adaptiveThreshold(gray, binary, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C, 
                              cv::THRESH_BINARY_INV, 21, 10);
        cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3));
        cv::morphologyEx(binary, binary, cv::MORPH_OPEN, kernel);
        cv::morphologyEx(binary, binary, cv::MORPH_CLOSE, kernel);
        std::vector<std::vector<cv::Point>> contours;
        cv::findContours(binary, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

        struct TempBlob {
            cv::Point2f center;
            float size;
            float area;
        };
        std::vector<TempBlob> temp_blobs;
        for (auto& contour : contours) {
            double area = cv::contourArea(contour);
            if (area < 10.0 || area > 5000.0) continue;
            cv::Rect r = cv::boundingRect(contour);
            temp_blobs.push_back({cv::Point2f(r.x + (float)r.width/2.0f, r.y + (float)r.height/2.0f), (float)std::max(r.width, r.height), (float)area});
        }
        std::sort(temp_blobs.begin(), temp_blobs.end(), [](const TempBlob& a, const TempBlob& b) {
            return a.area > b.area;
        });

        last_blobs_.clear();
        for (size_t i = 0; i < std::min((size_t)5, temp_blobs.size()); ++i) {
            last_blobs_.push_back({temp_blobs[i].center.x, temp_blobs[i].center.y, temp_blobs[i].size});
        }
    }

    // 6. Tracking logic
    cv::Rect2d bbox;
    if (tracking_active_ && tracker_initialized_) {
        bool track_ok = trackTarget(frame_for_tracking, bbox);
        if (track_ok) {
            lost_counter_ = 0;
            frames_since_detection_++;
            bbox_ = bbox;
            state.detected = true;
            state.tracking = true;
            state.confidence = std::min(1.0f, 0.5f + frames_since_detection_ * 0.05f);

            // Triangulation
            float wx, wy, wz;
            compute3DPosition(bbox, wx, wy, wz);
            state.bbox_x = (float)bbox.x;
            state.bbox_y = (float)bbox.y;
            state.bbox_w = (float)bbox.width;
            state.bbox_h = (float)bbox.height;
            state.world_x_m = wx;
            state.world_y_m = wy;
            state.world_z_m = wz;
            state.distance_m = wz;

            updateVelocity(wx, wy, wz, frame_ts_ns);
            state.vel_x_mps = vel_x_filtered_;
            state.vel_y_mps = vel_y_filtered_;
            state.vel_z_mps = vel_z_filtered_;

            computeLeadPoint(wx, wy, wz, t_flight_sec,
                             state.lead_x_m, state.lead_y_m, state.lead_z_m);
            computeAngles(wx, wy, wz,
                          state.azimuth_deg, state.elevation_deg);
        } else {
            lost_counter_++;
            frames_since_detection_ = 0;
            if (lost_counter_ > 5) {
                tracking_active_ = false;
                tracker_initialized_ = false;
                tracker_ = nullptr;
                VIT_LOGI("Tracker lost target, switching to detection mode");
            }
            state.confidence = std::max(0.0f, 1.0f - lost_counter_ * 0.2f);
        }
    } else {
        // Detection mode
        if (!last_blobs_.empty()) {
            state.detected = true;
            state.tracking = false;
            state.bbox_x = last_blobs_[0].x - last_blobs_[0].size/2;
            state.bbox_y = last_blobs_[0].y - last_blobs_[0].size/2;
            state.bbox_w = last_blobs_[0].size;
            state.bbox_h = last_blobs_[0].size;
            state.confidence = 0.8f;
            
            // Try to initialize tracker with best blob
            cv::Rect2d detected_bbox(state.bbox_x, state.bbox_y, state.bbox_w, state.bbox_h);
            try {
                tracker_ = cv::TrackerMIL::create();
                tracker_->init(frame_for_tracking, detected_bbox);
                tracker_initialized_ = true;
                tracking_active_ = true;
                frames_since_detection_ = 0;
            } catch (...) {}
        }
    }

    return state;
}

// ======== Draw overlay ========

void VITTracker::drawOverlay(cv::Mat& frame, const TargetState& state, 
                             float pitch, float yaw, float roll,
                             float gx, float gy, float gz)
{
    // Draw blobs
    for (size_t i = 0; i < last_blobs_.size(); ++i) {
        cv::Scalar color = (i == 0) ? cv::Scalar(0, 255, 0, 255) : cv::Scalar(255, 100, 0, 255);
        cv::Point2f center(last_blobs_[i].x, last_blobs_[i].y);
        cv::circle(frame, center, last_blobs_[i].size / 2.0f, color, 2);
        cv::circle(frame, center, 3, cv::Scalar(255, 0, 0, 255), -1);
    }

    // DRAW INFO TEXT
    int ty = 40;
    int line_step = 30;
    cv::Scalar txt_color(0, 255, 0, 255);
    float txt_size = 0.7f;

    auto draw_text = [&](const std::string& text) {
        cv::putText(frame, text, cv::Point(20, ty), cv::FONT_HERSHEY_SIMPLEX, txt_size, txt_color, 2);
        ty += line_step;
    };

    draw_text("=== CAMERA ===");
    
    char buf[128];
    snprintf(buf, sizeof(buf), "Pitch: %7.2f (Rate: %6.1f deg/s)", pitch, gx * 180.0f / (float)CV_PI);
    draw_text(buf);
    snprintf(buf, sizeof(buf), "Yaw:   %7.2f (Rate: %6.1f deg/s)", yaw, gy * 180.0f / (float)CV_PI);
    draw_text(buf);
    snprintf(buf, sizeof(buf), "Roll:  %7.2f (Rate: %6.1f deg/s)", roll, gz * 180.0f / (float)CV_PI);
    draw_text(buf);

    ty += 10;
    draw_text("=== TRACKER ===");
    if (state.detected) {
        snprintf(buf, sizeof(buf), "Blob: x=%-4.0f y=%-4.0f", state.bbox_x + state.bbox_w/2, state.bbox_y + state.bbox_h/2);
        draw_text(buf);
        snprintf(buf, sizeof(buf), "Size: %.1fpx", state.bbox_w);
        draw_text(buf);
        snprintf(buf, sizeof(buf), "Conf: %.2f", state.confidence);
        draw_text(buf);
    } else {
        draw_text("ПОИСК...");
    }
}

// ======== Gyro sync ========

bool VITTracker::syncGyro(int64_t frame_ts_ns, float& wx, float& wy, float& wz) {
    const int64_t MAX_DELTA_NS = 5 * 1000 * 1000; // 5ms
    return gyro_buffer_.getInterpolated(frame_ts_ns, MAX_DELTA_NS, wx, wy, wz);
}

// ======== Differential rotation using Rodrigues ========

cv::Mat VITTracker::computeDifferentialRotation(float wx, float wy, float wz, float dt_sec) {
    float theta = std::sqrt(wx*wx + wy*wy + wz*wz) * dt_sec;
    if (theta < 1e-10f) return cv::Mat::eye(3, 3, CV_64F);
    float rx = wx * dt_sec;
    float ry = wy * dt_sec;
    float rz = wz * dt_sec;
    cv::Mat rvec = (cv::Mat_<double>(3,1) << rx, ry, rz);
    cv::Mat R;
    cv::Rodrigues(rvec, R);
    return R;
}

// ======== Stabilize frame ========

void VITTracker::stabilizeFrame(const cv::Mat& src, cv::Mat& dst, const cv::Mat& dR) {
    cv::Mat H = K_ * dR * K_inv_;
    cv::warpPerspective(src, dst, H, src.size(), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
}

// ======== Get homography for OpenGL ========

bool VITTracker::getStabHomography(float out_matrix[9]) const {
    std::copy(stab_homography_, stab_homography_ + 9, out_matrix);
    return calib_loaded_;
}

// ======== Blob detection (Original method kept for internal logic if needed) ========

bool VITTracker::detectTarget(const cv::Mat& frame, cv::Rect2d& out_bbox, float& confidence) {
    // This is now redundant as it's partly integrated in processFrame,
    // but we keep it to avoid breaking other things if any.
    return false; 
}

// ======== Track target ========

bool VITTracker::trackTarget(const cv::Mat& frame, cv::Rect2d& out_bbox) {
    if (!tracker_ || !tracker_initialized_) return false;
    cv::Rect bbox_int;
    bool ok = tracker_->update(frame, bbox_int);
    if (!ok) return false;
    if (bbox_int.width < 5 || bbox_int.height < 5) return false;
    if (bbox_int.x < 0 || bbox_int.y < 0) return false;
    if (bbox_int.x + bbox_int.width > frame.cols || bbox_int.y + bbox_int.height > frame.rows) return false;
    out_bbox = cv::Rect2d(bbox_int);
    return true;
}

// ======== Triangulation ========

void VITTracker::compute3DPosition(const cv::Rect2d& bbox, float& x, float& y, float& z) {
    float u = (float)(bbox.x + bbox.width / 2.0);
    float v = (float)(bbox.y + bbox.height / 2.0);
    if (bbox.width > 2.0f) {
        z = (target_width_m_ * fx_) / (float)bbox.width;
        z = std::min(z, 5000.0f);
    } else {
        z = 1000.0f;
    }
    x = (u - cx_) * z / fx_;
    y = (v - cy_) * z / fy_;
}

// ======== Velocity estimation ========

void VITTracker::updateVelocity(float x, float y, float z, int64_t ts_ns) {
    TimedPosition tp;
    tp.timestamp_ns = ts_ns; tp.x = x; tp.y = y; tp.z = z;
    pos_buffer_.push_back(tp);
    if (pos_buffer_.size() > MAX_POS_SAMPLES) pos_buffer_.pop_front();
    if (pos_buffer_.size() < 2) return;
    const auto& prev = pos_buffer_[pos_buffer_.size() - 2];
    const auto& curr = pos_buffer_.back();
    double dt_sec = (double)(curr.timestamp_ns - prev.timestamp_ns) / 1.0e9;
    if (dt_sec < 0.001) return;
    float vx = (curr.x - prev.x) / (float)dt_sec;
    float vy = (curr.y - prev.y) / (float)dt_sec;
    float vz = (curr.z - prev.z) / (float)dt_sec;
    float speed = std::sqrt(vx*vx + vy*vy + vz*vz);
    if (speed > 50.0f) return;
    const float alpha = 0.3f;
    if (!velocity_initialized_) {
        vel_x_filtered_ = vx; vel_y_filtered_ = vy; vel_z_filtered_ = vz;
        velocity_initialized_ = true;
    } else {
        vel_x_filtered_ = vel_x_filtered_ * (1.0f - alpha) + vx * alpha;
        vel_y_filtered_ = vel_y_filtered_ * (1.0f - alpha) + vy * alpha;
        vel_z_filtered_ = vel_z_filtered_ * (1.0f - alpha) + vz * alpha;
    }
}

// ======== Lead point ========

void VITTracker::computeLeadPoint(float x, float y, float z, float t_flight,
                                   float& lx, float& ly, float& lz) {
    if (t_flight <= 0) t_flight = 2.0f;
    lx = x + vel_x_filtered_ * t_flight;
    ly = y + vel_y_filtered_ * t_flight;
    lz = z + vel_z_filtered_ * t_flight;
}

// ======== Angles ========

void VITTracker::computeAngles(float x, float y, float z,
                                float& azimuth_deg, float& elevation_deg) {
    if (z > 0.001f) {
        azimuth_deg = (float)(std::atan2((double)x, (double)z) * 180.0 / CV_PI);
        elevation_deg = (float)(std::atan2((double)y, (double)z) * 180.0 / CV_PI);
    } else {
        azimuth_deg = 0.0f; elevation_deg = 0.0f;
    }
}
