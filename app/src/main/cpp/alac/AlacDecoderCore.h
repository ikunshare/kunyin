#ifndef KUNYIN_ALAC_DECODER_CORE_H
#define KUNYIN_ALAC_DECODER_CORE_H

#include <cstdint>
#include <memory>
#include <vector>

// ffmpeg 的公开头文件不带 extern "C" 保护，C++ 里必须由调用方包裹，
// 否则符号会被名字修饰，链接期找不到 av_* 实现。
extern "C" {
#include "libavcodec/avcodec.h"
#include "libavutil/frame.h"
}

/**
 * ffmpeg ALAC 解码核心：不与 JNI 耦合，方便宿主侧做逐字节回归验证。
 *
 * 线程不安全——调用方（JNI 层已加 @Synchronized）负责串行化。
 */
class AlacDecoderCore {
public:
    /**
     * @param cookie ALAC magic cookie（含可选的盒头与 24 字节 ALACSpecificConfig）
     * @param error  失败时写入可读错误信息
     * @return 成功返回实例，失败返回 nullptr
     */
    static std::unique_ptr<AlacDecoderCore> create(
        const uint8_t *cookie, size_t size, char *error, size_t errorSize);

    ~AlacDecoderCore();

    AlacDecoderCore(const AlacDecoderCore &) = delete;
    AlacDecoderCore &operator=(const AlacDecoderCore &) = delete;

    /**
     * 解码一个 ALAC 包，返回交错小端 PCM。
     *
     * 位深决定容器宽度：16 位两字节；20/24 位三字节；32 位四字节。
     *
     * @param error 失败时写入可读错误信息
     */
    bool decode(const uint8_t *packet, size_t size,
                std::vector<uint8_t> &output, char *error, size_t errorSize);

    uint32_t sampleRate() const { return sampleRate_; }
    uint32_t channels() const { return channels_; }
    uint32_t bitDepth() const { return bitDepth_; }
    uint32_t framesPerPacket() const { return framesPerPacket_; }

    /** 该位深输出时每个样本占用的字节数。 */
    static int bytesPerSample(uint32_t bitDepth);

private:
    AlacDecoderCore() = default;

    AVCodecContext *ctx_ = nullptr;
    AVPacket *packet_ = nullptr;
    AVFrame *frame_ = nullptr;
    uint32_t framesPerPacket_ = 4096;
    uint32_t channels_ = 2;
    uint32_t bitDepth_ = 16;
    uint32_t sampleRate_ = 44100;
    std::vector<uint8_t> scratch_;
};

#endif  // KUNYIN_ALAC_DECODER_CORE_H
