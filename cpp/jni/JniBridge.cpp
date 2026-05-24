#include "JniBridge.h"

#include "core/DistanceModel.h"
#include "vibration/VibrationEngine.h"

#include <QString>

namespace jni_bridge {

void onDistanceUpdate(float meters)
{
    if (auto *m = distanceModelInstance()) {
        m->setDistanceMetersFromAnyThread(meters);
    }
    VibrationEngine::instance().updateDistanceMeters(meters);
}

void onStatusUpdate(const char *utf8)
{
    if (auto *m = distanceModelInstance()) {
        m->setStatusTextFromAnyThread(QString::fromUtf8(utf8 ? utf8 : ""));
    }
}

}

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_blindassist_ARCoreManager_nativeOnDistance(JNIEnv *, jobject, jfloat meters)
{
    jni_bridge::onDistanceUpdate(static_cast<float>(meters));
}

JNIEXPORT void JNICALL
Java_com_example_blindassist_ARCoreManager_nativeSetDistanceSpeechEnabled(JNIEnv *, jobject, jboolean enabled)
{
    if (auto *m = distanceModelInstance()) {
        m->setDistanceSpeechEnabledFromAnyThread(enabled == JNI_TRUE);
    }
}

JNIEXPORT jintArray JNICALL
Java_com_example_blindassist_VibrationManager_nativeGetVibrationParams(JNIEnv *env, jobject)
{
    const auto p = VibrationEngine::instance().params();
    jint out[3] = { p.active ? 1 : 0, static_cast<jint>(p.amplitude), static_cast<jint>(p.intervalMs) };
    jintArray arr = env->NewIntArray(3);
    if (!arr) {
        return nullptr;
    }
    env->SetIntArrayRegion(arr, 0, 3, out);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_example_blindassist_ARCoreManager_nativeOnStatus(JNIEnv *env, jobject, jstring text)
{
    const char *utf8 = text ? env->GetStringUTFChars(text, nullptr) : nullptr;
    jni_bridge::onStatusUpdate(utf8);
    if (text && utf8) {
        env->ReleaseStringUTFChars(text, utf8);
    }
}

} // extern "C"
