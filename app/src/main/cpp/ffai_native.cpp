#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <vector>

#define LOG_TAG "FFAI_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Extern declarations for other modules
extern "C" {
    // Gesture engine exports
    JNIEXPORT jlong JNICALL Java_com_ffai_gestures_engine_NativeGestureEngine_createEngine(JNIEnv* env, jobject thiz);
    JNIEXPORT void JNICALL Java_com_ffai_gestures_engine_NativeGestureEngine_destroyEngine(JNIEnv* env, jobject thiz, jlong handle);
    JNIEXPORT jboolean JNICALL Java_com_ffai_gestures_engine_NativeGestureEngine_classifyGesture(
        JNIEnv* env, jobject thiz, jlong handle, jfloatArray points, jint pointCount);
    
    // Camera engine exports
    JNIEXPORT jlong JNICALL Java_com_ffai_camera_NativeCameraController_createController(JNIEnv* env, jobject thiz);
    JNIEXPORT void JNICALL Java_com_ffai_camera_NativeCameraController_destroyController(JNIEnv* env, jobject thiz, jlong handle);
    JNIEXPORT jfloatArray JNICALL Java_com_ffai_camera_NativeCameraController_smoothPath(
        JNIEnv* env, jobject thiz, jlong handle, jfloatArray waypoints, jint count);
}

// Native initialization
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("FFAI Native Library loaded");
    return JNI_VERSION_1_6;
}

// Math utilities
class MathUtils {
public:
    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
    
    static float smoothStep(float edge0, float edge1, float x) {
        float t = (x - edge0) / (edge1 - edge0);
        t = fmaxf(0.0f, fminf(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }
    
    static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return sqrtf(dx * dx + dy * dy);
    }
    
    static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        
        return 0.5f * (
            (2.0f * p1) +
            (-p0 + p2) * t +
            (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 +
            (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3
        );
    }
};

// Easing functions
class Easing {
public:
    static float easeOutCubic(float t) {
        float f = 1.0f - t;
        return 1.0f - f * f * f;
    }
    
    static float easeInOutCubic(float t) {
        if (t < 0.5f) {
            return 4.0f * t * t * t;
        }
        float f = 2.0f * t - 2.0f;
        return 1.0f + f * f * f * 0.5f;
    }
    
    static float easeOutQuart(float t) {
        float f = 1.0f - t;
        return 1.0f - f * f * f * f;
    }
    
    static float easeOutElastic(float t) {
        const float p = 0.3f;
        return powf(2.0f, -10.0f * t) * sinf((t - p / 4.0f) * (2.0f * M_PI) / p) + 1.0f;
    }
};

// Simple gesture structure
struct GesturePoint {
    float x;
    float y;
    float pressure;
    int64_t timestamp;
    float velocity;
};

// JNI implementations
extern "C" JNIEXPORT jlong JNICALL
Java_com_ffai_gestures_engine_NativeGestureEngine_createEngine(JNIEnv* env, jobject thiz) {
    LOGI("Creating gesture engine");
    // Return a handle (in production, this would be an actual object pointer)
    return reinterpret_cast<jlong>(new int(1));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ffai_gestures_engine_NativeGestureEngine_destroyEngine(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("Destroying gesture engine");
    delete reinterpret_cast<int*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ffai_gestures_engine_NativeGestureEngine_classifyGesture(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray points, jint pointCount) {
    
    if (handle == 0 || points == nullptr || pointCount < 2) {
        return JNI_FALSE;
    }
    
    jfloat* pointsArray = env->GetFloatArrayElements(points, nullptr);
    if (pointsArray == nullptr) {
        return JNI_FALSE;
    }
    
    // Simple gesture classification logic
    // In production, this would use a trained ML model
    
    float startX = pointsArray[0];
    float startY = pointsArray[1];
    float endX = pointsArray[(pointCount - 1) * 2];
    float endY = pointsArray[(pointCount - 1) * 2 + 1];
    
    float distance = MathUtils::distance(startX, startY, endX, endY);
    
    // Classify based on distance and movement pattern
    bool isSwipe = distance > 50.0f;
    bool isTap = distance < 10.0f;
    
    env->ReleaseFloatArrayElements(points, pointsArray, JNI_ABORT);
    
    LOGD("Gesture classified: distance=%.2f, isSwipe=%d, isTap=%d", 
         distance, isSwipe, isTap);
    
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ffai_camera_NativeCameraController_createController(JNIEnv* env, jobject thiz) {
    LOGI("Creating camera controller");
    return reinterpret_cast<jlong>(new int(1));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ffai_camera_NativeCameraController_destroyController(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("Destroying camera controller");
    delete reinterpret_cast<int*>(handle);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ffai_camera_NativeCameraController_smoothPath(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray waypoints, jint count) {
    
    if (handle == 0 || waypoints == nullptr || count < 2) {
        return nullptr;
    }
    
    jfloat* input = env->GetFloatArrayElements(waypoints, nullptr);
    if (input == nullptr) {
        return nullptr;
    }
    
    // Generate smooth interpolated path using Catmull-Rom splines
    const int segments = 10;  // Points per segment
    const int outputCount = (count - 1) * segments + 1;
    
    std::vector<float> output;
    output.reserve(outputCount * 2);
    
    for (int i = 0; i < count - 1; i++) {
        float p0x = (i > 0) ? input[(i - 1) * 2] : input[i * 2];
        float p0y = (i > 0) ? input[(i - 1) * 2 + 1] : input[i * 2 + 1];
        float p1x = input[i * 2];
        float p1y = input[i * 2 + 1];
        float p2x = input[(i + 1) * 2];
        float p2y = input[(i + 1) * 2 + 1];
        float p3x = (i + 2 < count) ? input[(i + 2) * 2] : input[(i + 1) * 2];
        float p3y = (i + 2 < count) ? input[(i + 2) * 2 + 1] : input[(i + 1) * 2 + 1];
        
        for (int j = 0; j < segments; j++) {
            float t = static_cast<float>(j) / segments;
            
            float x = MathUtils::catmullRom(p0x, p1x, p2x, p3x, t);
            float y = MathUtils::catmullRom(p0y, p1y, p2y, p3y, t);
            
            output.push_back(x);
            output.push_back(y);
        }
    }
    
    // Add last point
    output.push_back(input[(count - 1) * 2]);
    output.push_back(input[(count - 1) * 2 + 1]);
    
    env->ReleaseFloatArrayElements(waypoints, input, JNI_ABORT);
    
    jfloatArray result = env->NewFloatArray(output.size());
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, output.size(), output.data());
    }
    
    LOGD("Generated smooth path: %d input points -> %zu output points", 
         count, output.size() / 2);
    
    return result;
}
