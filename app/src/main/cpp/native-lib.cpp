#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <iomanip>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperNative"

static struct whisper_context * g_ctx = nullptr;
static JavaVM * g_jvm = nullptr;
static jobject g_callback_obj = nullptr;
static jmethodID g_log_mid = nullptr;
static jmethodID g_prog_mid = nullptr;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

void send_native_log(const char* msg) {
    if (!g_jvm || !g_callback_obj || !g_log_mid) return;
    JNIEnv *env;
    bool attached = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    jstring jmsg = env->NewStringUTF(msg);
    env->CallVoidMethod(g_callback_obj, g_log_mid, jmsg);
    env->DeleteLocalRef(jmsg);
    if (attached) g_jvm->DetachCurrentThread();
}

static void whisper_log_cb(enum ggml_log_level level, const char * text, void * user_data) {
    send_native_log(text);
}

std::string format_time_srt(int64_t t) {
    int64_t msec = t * 10;
    int hr = msec / (3600 * 1000);
    msec %= (3600 * 1000);
    int min = msec / (60 * 1000);
    msec %= (60 * 1000);
    int sec = msec / 1000;
    msec %= 1000;

    std::ostringstream ss;
    ss << std::setfill('0') << std::setw(2) << hr << ":"
       << std::setfill('0') << std::setw(2) << min << ":"
       << std::setfill('0') << std::setw(2) << sec << ","
       << std::setfill('0') << std::setw(3) << msec;
    return ss.str();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_whispersubtitles_WhisperEngine_initModel(
        JNIEnv* env, jobject thiz, jstring model_path_str, jobject callback) {
    
    if (g_callback_obj) env->DeleteGlobalRef(g_callback_obj);
    g_callback_obj = env->NewGlobalRef(callback);
    
    jclass clz = env->GetObjectClass(callback);
    g_log_mid = env->GetMethodID(clz, "onNativeLog", "(Ljava/lang/String;)V");
    g_prog_mid = env->GetMethodID(clz, "onProgress", "(I)V");

    whisper_log_set(whisper_log_cb, nullptr);

    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    
    if (g_ctx) whisper_free(g_ctx);
    g_ctx = whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(model_path_str, model_path);

    return g_ctx != nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_whispersubtitles_WhisperEngine_transcribeToSrt(
        JNIEnv* env, jobject thiz, jfloatArray audio_data, jint num_threads) {
    
    if (!g_ctx) return env->NewStringUTF("");

    jsize len = env->GetArrayLength(audio_data);
    jfloat *samples = env->GetFloatArrayElements(audio_data, nullptr);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.n_threads = num_threads > 0 ? num_threads : 4;
    wparams.language = "auto";
    wparams.translate = false;

    wparams.progress_callback = [](struct whisper_context * ctx, struct whisper_state * state, int progress, void * user_data) {
        if (!g_jvm || !g_callback_obj || !g_prog_mid) return;
        JNIEnv *env;
        bool attached = false;
        if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            g_jvm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
        env->CallVoidMethod(g_callback_obj, g_prog_mid, progress);
        if (attached) g_jvm->DetachCurrentThread();
    };

    if (whisper_full(g_ctx, wparams, samples, len) != 0) {
        env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
        return env->NewStringUTF("");
    }

    std::ostringstream srt;
    const int n_segments = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char * text = whisper_full_get_segment_text(g_ctx, i);
        int64_t t0 = whisper_full_get_segment_t0(g_ctx, i);
        int64_t t1 = whisper_full_get_segment_t1(g_ctx, i);

        srt << (i + 1) << "\n";
        srt << format_time_srt(t0) << " --> " << format_time_srt(t1) << "\n";
        srt << text << "\n\n";
    }

    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
    return env->NewStringUTF(srt.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_whispersubtitles_WhisperEngine_freeModel(JNIEnv* env, jobject thiz) {
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }
}
