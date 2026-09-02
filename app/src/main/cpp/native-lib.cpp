#include <jni.h>
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_whispersubtitles_MainActivity_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("Whisper native bridge ready");
}