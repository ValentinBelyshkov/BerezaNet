#include "vit_tracker.h"
#include <algorithm>
#include <cmath>
#include <numeric>

VITTracker::VITTracker() {
    stab_homography_[0] = stab_homography_[4] = stab_homography_[8] = 1.0f;
}

VITTracker::~VITTracker() {
    release();
}

bool VITTracker::init(const char* calib_json, float target_w, float target_l) {
    target_width_m_ = target_w;
    if (!parseCalibration(calib_json)) {
        VIT_LOGW("Failed to parse calibration, using defaults");
        fx_ = 640.0f;
        fy_ = 640.0f;
        cx_ = 320.0f;
        cy_ = 240.0f;
    }
    calib_loaded_ = true;
    return true;
}

void VITTracker::reset() {
    position_history.clear();
}

void VITTracker::release() {
    position_history.clear();
}

TargetState VITTracker::processFrame(
    uint8_t* rgba_data, int w, int h, int64_t frame_ts_ns,
    float gyro_x, float gyro_y, float gyro_z, int64_t gyro_ts_ns,
    float t_flight_sec
) {
    TargetState state;
    state.timestamp_ns = frame_ts_ns;

    if (!rgba_data || w <= 0 || h <= 0) return state;

    cv::Mat frame(h, w, CV_8UC4, rgba_data);
    cv::Mat gray, blurred, edges;

    // Python: gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    // Note: input is RGBA
    cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);
    
    // Python: blurred = cv2.GaussianBlur(gray, self.blur_kernel, 0)
    cv::GaussianBlur(gray, blurred, blur_kernel, 0);
    
    // Python: edges = cv2.Canny(blurred, self.canny_low, self.canny_high)
    cv::Canny(blurred, edges, canny_low, canny_high);

    std::vector<TrackedBlob> blobs = detectBlobs(edges);

    if (!blobs.empty()) {
        const TrackedBlob& best_blob = blobs[0];
        double timestamp = frame_ts_ns / 1e9;
        updatePositionHistory(best_blob, timestamp);

        state.detected = true;
        state.tracking = true;
        state.bbox_x = best_blob.x - best_blob.size / 2.0f;
        state.bbox_y = best_blob.y - best_blob.size / 2.0f;
        state.bbox_w = best_blob.size;
        state.bbox_h = best_blob.size;
        state.confidence = best_blob.confidence;

        // Distance estimation
        if (best_blob.size > 0.1f) {
            state.distance_m = (target_width_m_ * fx_) / best_blob.size;
        } else {
            state.distance_m = 1000.0f;
        }

        // Predict position
        // In Android landscape: 
        // gyro_x is pitch (rotation around X axis)
        // gyro_y is yaw (rotation around Y axis)
        // Values are in rad/s
        cv::Point2f predicted = predictPosition(
            (double)t_flight_sec,
            best_blob,
            gyro_y, // yaw rate
            gyro_x, // pitch rate
            w, h
        );

        state.lead_x_m = predicted.x; // We use lead_x_m to store pixel X for now as requested
        state.lead_y_m = predicted.y; // and lead_y_m for pixel Y
        
        // Angles
        state.azimuth_deg = (float)(std::atan2(best_blob.x - cx_, fx_) * 180.0 / CV_PI);
        state.elevation_deg = (float)(std::atan2(best_blob.y - cy_, fy_) * 180.0 / CV_PI);

        // Fill other fields for compatibility
        state.world_x_m = predicted.x;
        state.world_y_m = predicted.y;
        
        // Velocity (pixel/s)
        cv::Point2f vel = getBlobVelocity();
        state.vel_x_mps = vel.x;
        state.vel_y_mps = vel.y;
    }

    return state;
}

std::vector<TrackedBlob> VITTracker::detectBlobs(const cv::Mat& edges) {
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(edges, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

    std::vector<TrackedBlob> blobs;
    for (const auto& cnt : contours) {
        double area = cv::contourArea(cnt);
        if (area < min_blob_size * min_blob_size || area > max_blob_size * max_blob_size) {
            continue;
        }

        double perimeter = cv::arcLength(cnt, true);
        if (perimeter <= 0) continue;

        float circularity = (float)(4.0 * CV_PI * area / (perimeter * perimeter));
        if (circularity < min_circularity) {
            continue;
        }

        cv::Point2f center;
        float radius;
        cv::minEnclosingCircle(cnt, center, radius);

        TrackedBlob blob;
        blob.x = center.x;
        blob.y = center.y;
        blob.size = radius * 2.0f;
        blob.confidence = std::min(1.0f, circularity);
        blobs.push_back(blob);
    }

    std::sort(blobs.begin(), blobs.end(), [](const TrackedBlob& a, const TrackedBlob& b) {
        return a.size > b.size;
    });

    return blobs;
}

void VITTracker::updatePositionHistory(const TrackedBlob& blob, double timestamp) {
    PositionSample sample;
    sample.time = timestamp;
    sample.x = blob.x;
    sample.y = blob.y;
    position_history.push_back(sample);
    if (position_history.size() > history_size) {
        position_history.pop_front();
    }
}

cv::Point2f VITTracker::getBlobVelocity() {
    if (position_history.size() < 5) {
        return cv::Point2f(0, 0);
    }

    size_t n = std::min((size_t)10, position_history.size());
    std::vector<double> ts, xs, ys;
    double last_t = position_history.back().time;

    for (size_t i = position_history.size() - n; i < position_history.size(); ++i) {
        ts.push_back(position_history[i].time - last_t);
        xs.push_back(position_history[i].x);
        ys.push_back(position_history[i].y);
    }

    auto linReg = [](const std::vector<double>& t, const std::vector<double>& v) -> float {
        size_t n = t.size();
        double sum_t = std::accumulate(t.begin(), t.end(), 0.0);
        double sum_v = std::accumulate(v.begin(), v.end(), 0.0);
        double sum_tt = 0, sum_tv = 0;
        for (size_t i = 0; i < n; ++i) {
            sum_tt += t[i] * t[i];
            sum_tv += t[i] * v[i];
        }
        double denom = (n * sum_tt - sum_t * sum_t);
        if (std::abs(denom) < 1e-9) return 0;
        return (float)((n * sum_tv - sum_t * sum_v) / denom);
    };

    return cv::Point2f(linReg(ts, xs), linReg(ts, ys));
}

cv::Point2f VITTracker::predictPosition(
    double future_time,
    const TrackedBlob& current_blob,
    float gyro_yaw_rate,
    float gyro_pitch_rate,
    int screen_width,
    int screen_height
) {
    cv::Point2f blob_vel = getBlobVelocity();

    // pixels_per_rad = fx_
    float cam_vel_x = -gyro_yaw_rate * fx_;
    float cam_vel_y = gyro_pitch_rate * fy_;

    float obj_vel_x = blob_vel.x - cam_vel_x;
    float obj_vel_y = blob_vel.y - cam_vel_y;

    float blob_radius = current_blob.size / 2.0f;
    float max_speed = blob_radius * 5.0f;

    float speed = std::sqrt(obj_vel_x * obj_vel_x + obj_vel_y * obj_vel_y);
    if (speed > max_speed && max_speed > 0) {
        float scale = max_speed / speed;
        obj_vel_x *= scale;
        obj_vel_y *= scale;
    }

    float pred_x = current_blob.x + obj_vel_x * (float)future_time;
    float pred_y = current_blob.y + obj_vel_y * (float)future_time;

    pred_x = std::max(0.0f, std::min((float)screen_width, pred_x));
    pred_y = std::max(0.0f, std::min((float)screen_height, pred_y));

    return cv::Point2f(pred_x, pred_y);
}

bool VITTracker::getStabHomography(float out_matrix[9]) const {
    std::copy(stab_homography_, stab_homography_ + 9, out_matrix);
    return true;
}

bool VITTracker::parseCalibration(const char* json) {
    if (!json || strlen(json) == 0) return false;
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
        while (pos < s.size() && (isdigit(s[pos]) || s[pos] == '.')) {
            num += s[pos];
            pos++;
        }
        if (num.empty()) return false;
        val = std::stof(num);
        if (neg) val = -val;
        return true;
    };

    bool ok = true;
    ok &= findNum("fx", fx_);
    ok &= findNum("fy", fy_);
    ok &= findNum("cx", cx_);
    ok &= findNum("cy", cy_);
    return ok;
}
