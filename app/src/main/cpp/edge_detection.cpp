#include <jni.h>
#include <opencv2/opencv.hpp>
#include <android/log.h>

#define LOG_TAG "EdgeDetection-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace cv;

// ======== КОНФИГ ПРИЦЕЛА ========
const int CROSS_SIZE = 40;      // Размер перекрестия (зелёного)
const int CROSS_THICKNESS = 3;  // Толщина линий
const int SQUARE_SIZE = 30;     // Размер квадрата упреждения
const int SQUARE_THICKNESS = 4;
const int DOT_RADIUS = 5;       // Радиус красной точки (ствол)

// Цвета в RGBA
const Scalar COLOR_RED(255, 0, 0, 255);
const Scalar COLOR_YELLOW(255, 255, 0, 255);
const Scalar COLOR_GREEN(0, 255, 0, 255);
const Scalar COLOR_TEXT(0, 255, 0, 255);

// ======== УТИЛИТЫ РИСОВАНИЯ ========

/**
 * Рисует квадрат с углами (для точки упреждения — стиль "прицел")
 */
static void drawAimSquare(Mat& img, Point center, const Scalar& color, int size, int thickness) {
    int half = size / 2;
    int gap = size / 4; // Зазор в углах для стиля

    // Верхняя левая угловая линия
    line(img, Point(center.x - half, center.y - half),
         Point(center.x - gap, center.y - half), color, thickness, LINE_AA);
    line(img, Point(center.x - half, center.y - half),
         Point(center.x - half, center.y - gap), color, thickness, LINE_AA);

    // Верхняя правая
    line(img, Point(center.x + gap, center.y - half),
         Point(center.x + half, center.y - half), color, thickness, LINE_AA);
    line(img, Point(center.x + half, center.y - half),
         Point(center.x + half, center.y - gap), color, thickness, LINE_AA);

    // Нижняя левая
    line(img, Point(center.x - half, center.y + gap),
         Point(center.x - half, center.y + half), color, thickness, LINE_AA);
    line(img, Point(center.x - half, center.y + half),
         Point(center.x - gap, center.y + half), color, thickness, LINE_AA);

    // Нижняя правая
    line(img, Point(center.x + gap, center.y + half),
         Point(center.x + half, center.y + half), color, thickness, LINE_AA);
    line(img, Point(center.x + half, center.y + gap),
         Point(center.x + half, center.y + half), color, thickness, LINE_AA);
}

/**
 * Рисует перекрестие (+) в заданной точке
 */
static void drawPlusCrosshair(Mat& img, Point center, const Scalar& color, int size, int thickness) {
    line(img, Point(center.x - size, center.y), Point(center.x + size, center.y),
         color, thickness, LINE_AA);
    line(img, Point(center.x, center.y - size), Point(center.x, center.y + size),
         color, thickness, LINE_AA);
    // Точка в центре
    circle(img, center, thickness + 1, color, -1, LINE_AA);
}

// ======== ОСНОВНАЯ ФУНКЦИЯ ========

extern "C" {

/**
 * Расширенная функция детекции с оверлеями прицела
 *
 * targetX, targetY — координаты точки упреждения в пикселях относительно кадра
 * targetDetected — 1 если цель обнаружена, 0 если нет
 * aligned — 1 если ствол совмещён с точкой упреждения, 0 если нет
 */
JNIEXPORT void JNICALL
Java_com_edgedetection_EdgeDetector_detectEdgesWithReticle(
        JNIEnv *env,
        jclass clazz,
        jlong inputAddr,
        jlong outputAddr,
        jint lowerThreshold,
        jint upperThreshold,
        jint blurSize,
        jint targetX,
        jint targetY,
        jboolean targetDetected,
        jboolean aligned) {

    try {
        Mat &input = *(Mat *) inputAddr;
        Mat &output = *(Mat *) outputAddr;

        int frameW = input.cols;
        int frameH = input.rows;
        Point screenCenter(frameW / 2, frameH / 2);

        // ======== 1. ОБРАБОТКА ИЗОБРАЖЕНИЯ (как раньше) ========
        Mat gray;
        cvtColor(input, gray, COLOR_RGBA2GRAY);

        int kernelSize = blurSize;
        if (kernelSize < 1) kernelSize = 1;
        if (kernelSize % 2 == 0) kernelSize += 1;

        Mat blurred;
        GaussianBlur(gray, blurred, Size(kernelSize, kernelSize), 0);

        Mat edges;
        Canny(blurred, edges, lowerThreshold, upperThreshold);

        // Конвертируем edges в RGBA для оверлея
        cvtColor(edges, output, COLOR_GRAY2RGBA);

        // ======== 2. ОВЕРЛЕИ ПРИЦЕЛА ========

        // 2.1 Красная точка — ствол (всегда по центру экрана)
        // Было: drawCrosshair(output, screenCenter, COLOR_RED, CROSS_SIZE, CROSS_THICKNESS);
        circle(output, screenCenter, DOT_RADIUS, COLOR_RED, -1, LINE_AA);

        // 2.2 Жёлтый квадрат — точка упреждения ИИ (только при детекции)
        if (targetDetected) {
            Point aimPoint(targetX, targetY);

            // Проверка границ экрана
            if (aimPoint.x >= 0 && aimPoint.x < frameW &&
                aimPoint.y >= 0 && aimPoint.y < frameH) {

                drawAimSquare(output, aimPoint, COLOR_YELLOW, SQUARE_SIZE, SQUARE_THICKNESS);

                // Линия от центра к точке упреждения (визуализация вектора)
                line(output, screenCenter, aimPoint,
                     Scalar(255, 255, 0, 128), 1, LINE_AA);
            }
        }

        // 2.3 Зелёное перекрестие — совмещение (цель захвачена)
        if (aligned && targetDetected) {
            // Рисуем поверх красной точки или рядом — здесь делаем "двойное" перекрестие
            // Зелёное + поверх красной точки = визуальный сигнал "готов к огню"
            drawPlusCrosshair(output, screenCenter, COLOR_GREEN, CROSS_SIZE - 5, CROSS_THICKNESS + 1);

            // Дополнительно: круг "захвата"
            circle(output, screenCenter, CROSS_SIZE + 15, COLOR_GREEN, 2, LINE_AA);

            // Текст "LOCKED"
            putText(output, "LOCKED", Point(screenCenter.x + 50, screenCenter.y - 30),
                    FONT_HERSHEY_SIMPLEX, 0.8, COLOR_GREEN, 2);
        }

        // ======== 3. ИНФО-ТЕКСТ ========
        String lowerText = "Lower: " + std::to_string(lowerThreshold);
        String upperText = "Upper: " + std::to_string(upperThreshold);
        String blurText = "Blur: " + std::to_string(kernelSize);

       // putText(output, lowerText, Point(10, 50),
       //         FONT_HERSHEY_SIMPLEX, 1.0, COLOR_TEXT, 2);
       // putText(output, upperText, Point(10, 100),
       //         FONT_HERSHEY_SIMPLEX, 1.0, COLOR_TEXT, 2);
      //  putText(output, blurText, Point(10, 150),
      //          FONT_HERSHEY_SIMPLEX, 1.0, COLOR_TEXT, 2);

        // Отладочный текст
        if (targetDetected) {
            String aimText = "AIM: " + std::to_string(targetX) + "," + std::to_string(targetY);
            putText(output, aimText, Point(10, 200),
                    FONT_HERSHEY_SIMPLEX, 0.7, COLOR_YELLOW, 2);
        }
        output = input;
        // ======== 4. ОЧИСТКА ========
        gray.release();
        blurred.release();
        edges.release();

    } catch (cv::Exception &e) {
        LOGE("OpenCV Error: %s", e.what());
    } catch (...) {
        LOGE("Unknown error in edge detection");
    }
}

/**
 * Обратная совместимость: старый метод без прицела
 */
JNIEXPORT void JNICALL
Java_com_edgedetection_EdgeDetector_detectEdges(
        JNIEnv *env,
        jclass clazz,
        jlong inputAddr,
        jlong outputAddr,
        jint lowerThreshold,
        jint upperThreshold,
        jint blurSize) {

    // Вызываем новый метод без цели
    Java_com_edgedetection_EdgeDetector_detectEdgesWithReticle(
            env, clazz, inputAddr, outputAddr,
            lowerThreshold, upperThreshold, blurSize,
            0, 0, JNI_FALSE, JNI_FALSE
    );
}

} // extern "C"