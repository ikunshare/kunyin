#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "AlacDecoderCore.h"

#define LOG_TAG "KunAlac"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {
constexpr size_t kErrorSize = 128;

void throwError(JNIEnv *env, const char *className, const char *message) {
    jclass clazz = env->FindClass(className);
    if (clazz != nullptr) env->ThrowNew(clazz, message);
}

AlacDecoderCore *asCore(jlong handle) {
    return reinterpret_cast<AlacDecoderCore *>(static_cast<uintptr_t>(handle));
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ikunshare_music_platform_am_AlacNativeTemplate_nativeCreate(
    JNIEnv *env, jobject, jbyteArray cookie) {
    if (cookie == nullptr) {
        throwError(env, "java/lang/IllegalArgumentException", "ALAC magic cookie 为空");
        return 0;
    }
    const jsize length = env->GetArrayLength(cookie);
    std::vector<uint8_t> bytes(static_cast<size_t>(length) > 0 ? static_cast<size_t>(length) : 1);
    if (length > 0) {
        env->GetByteArrayRegion(cookie, 0, length, reinterpret_cast<jbyte *>(bytes.data()));
    }

    char error[kErrorSize] = {0};
    auto core = AlacDecoderCore::create(bytes.data(), static_cast<size_t>(length), error, sizeof(error));
    if (core == nullptr) {
        throwError(env,
                   std::strstr(error, "分配") != nullptr
                       ? "java/lang/OutOfMemoryError"
                       : "java/lang/IllegalArgumentException",
                   error);
        return 0;
    }
    LOGI("ALAC(ffmpeg) frames=%u channels=%u bitDepth=%u rate=%u",
         core->framesPerPacket(), core->channels(), core->bitDepth(), core->sampleRate());
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(core.release()));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ikunshare_music_platform_am_AlacNativeTemplate_nativeDecode(
    JNIEnv *env, jobject, jlong handleValue, jbyteArray packet, jint framesPerPacket) {
    AlacDecoderCore *core = asCore(handleValue);
    if (core == nullptr) {
        throwError(env, "java/lang/IllegalStateException", "ALAC 解码器已释放");
        return nullptr;
    }
    if (packet == nullptr) {
        throwError(env, "java/lang/IllegalArgumentException", "ALAC 数据包为空");
        return nullptr;
    }
    const jsize packetLength = env->GetArrayLength(packet);
    if (packetLength <= 0) {
        throwError(env, "java/lang/IllegalArgumentException", "ALAC 数据包为空");
        return nullptr;
    }
    // framesPerPacket 只是调用方的预期；ffmpeg 从码流里读出实际帧数，用不上它。
    (void) framesPerPacket;

    std::vector<uint8_t> input(static_cast<size_t>(packetLength));
    env->GetByteArrayRegion(packet, 0, packetLength, reinterpret_cast<jbyte *>(input.data()));

    char error[kErrorSize] = {0};
    std::vector<uint8_t> output;
    if (!core->decode(input.data(), input.size(), output, error, sizeof(error))) {
        throwError(env, "java/io/IOException", error);
        return nullptr;
    }

    const auto sampleCount = static_cast<jsize>(output.size());
    jbyteArray result = env->NewByteArray(sampleCount);
    if (result != nullptr) {
        env->SetByteArrayRegion(
            result, 0, sampleCount, reinterpret_cast<const jbyte *>(output.data()));
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ikunshare_music_platform_am_AlacNativeTemplate_nativeSampleRate(
    JNIEnv *, jobject, jlong handleValue) {
    AlacDecoderCore *core = asCore(handleValue);
    return core == nullptr ? 0 : static_cast<jint>(core->sampleRate());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ikunshare_music_platform_am_AlacNativeTemplate_nativeChannels(
    JNIEnv *, jobject, jlong handleValue) {
    AlacDecoderCore *core = asCore(handleValue);
    return core == nullptr ? 0 : static_cast<jint>(core->channels());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ikunshare_music_platform_am_AlacNativeTemplate_nativeBitDepth(
    JNIEnv *, jobject, jlong handleValue) {
    AlacDecoderCore *core = asCore(handleValue);
    return core == nullptr ? 0 : static_cast<jint>(core->bitDepth());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ikunshare_music_platform_am_AlacNativeTemplate_nativeDestroy(
    JNIEnv *, jobject, jlong handleValue) {
    delete asCore(handleValue);
}
