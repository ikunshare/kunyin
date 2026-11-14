#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <mutex>

#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace {
using FromJson = void *(*)(const char *, std::size_t);
using Decrypt = std::size_t (*)(const void *, const std::uint8_t *, std::size_t, std::uint8_t *);
using Destroy = void (*)(void *);

FromJson fromJson = nullptr;
Decrypt decryptSample = nullptr;
Destroy destroyTemplate = nullptr;
std::once_flag symbolsOnce;

void loadSymbols() {
#ifdef _WIN32
    HMODULE module = GetModuleHandleA("temari.dll");
    if (module == nullptr) module = LoadLibraryA("temari.dll");
    if (module == nullptr) return;
    fromJson = reinterpret_cast<FromJson>(GetProcAddress(module, "tmpl_from_json"));
    decryptSample = reinterpret_cast<Decrypt>(GetProcAddress(module, "decrypt_sample_ffi"));
    destroyTemplate = reinterpret_cast<Destroy>(GetProcAddress(module, "tmpl_destroy"));
#else
    void *library = dlopen("libtemari.so", RTLD_NOW | RTLD_GLOBAL);
    if (library == nullptr) return;
    fromJson = reinterpret_cast<FromJson>(dlsym(library, "tmpl_from_json"));
    decryptSample = reinterpret_cast<Decrypt>(dlsym(library, "decrypt_sample_ffi"));
    destroyTemplate = reinterpret_cast<Destroy>(dlsym(library, "tmpl_destroy"));
#endif
}

bool symbolsReady() {
    std::call_once(symbolsOnce, loadSymbols);
    return fromJson != nullptr && decryptSample != nullptr && destroyTemplate != nullptr;
}

jlong toHandle(void *handle) {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(handle));
}

void *fromHandle(jlong handle) {
    return reinterpret_cast<void *>(static_cast<std::uintptr_t>(handle));
}

jbyteArray failure(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message);
    return nullptr;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ikunshare_music_platform_am_TemariTemplate_nativeCreate(JNIEnv *env, jobject, jbyteArray json) {
    if (!symbolsReady()) {
        env->ThrowNew(env->FindClass("java/io/IOException"), "Temari 原生库不可用");
        return 0;
    }
    const jsize length = env->GetArrayLength(json);
    if (length == 0) return 0;
    jbyte *bytes = env->GetByteArrayElements(json, nullptr);
    if (bytes == nullptr) return 0;
    void *handle = fromJson(reinterpret_cast<const char *>(bytes), static_cast<std::size_t>(length));
    env->ReleaseByteArrayElements(json, bytes, JNI_ABORT);
    return toHandle(handle);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ikunshare_music_platform_am_TemariTemplate_nativeDecrypt(
    JNIEnv *env,
    jobject,
    jlong handle,
    jbyteArray sample
) {
    if (!symbolsReady()) return failure(env, "Temari 原生库不可用");
    const jsize length = env->GetArrayLength(sample);
    if (length == 0) return env->NewByteArray(0);
    jbyteArray result = env->NewByteArray(length);
    if (result == nullptr) return nullptr;
    jbyte *input = env->GetByteArrayElements(sample, nullptr);
    jbyte *output = env->GetByteArrayElements(result, nullptr);
    if (input == nullptr || output == nullptr) {
        if (input != nullptr) env->ReleaseByteArrayElements(sample, input, JNI_ABORT);
        if (output != nullptr) env->ReleaseByteArrayElements(result, output, JNI_ABORT);
        return nullptr;
    }
    const std::size_t written = decryptSample(
        fromHandle(handle),
        reinterpret_cast<const std::uint8_t *>(input),
        static_cast<std::size_t>(length),
        reinterpret_cast<std::uint8_t *>(output)
    );
    env->ReleaseByteArrayElements(sample, input, JNI_ABORT);
    if (written != static_cast<std::size_t>(length)) {
        env->ReleaseByteArrayElements(result, output, JNI_ABORT);
        return failure(env, "Temari 样本解密失败");
    }
    env->ReleaseByteArrayElements(result, output, 0);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ikunshare_music_platform_am_TemariTemplate_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    if (symbolsReady()) destroyTemplate(fromHandle(handle));
}
