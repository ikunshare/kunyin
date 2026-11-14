#include "AlacDecoderCore.h"

#include <cstdio>
#include <cstring>

#include "libavutil/channel_layout.h"
#include "libavutil/mem.h"
#include "libavutil/samplefmt.h"

namespace {
constexpr int kMaxChannels = 8;
constexpr int kMaxFramesPerPacket = 16384;
/** ffmpeg 的 alac 解码器要求 36 字节原子头：size + "alac" + version + frameLength + 24 字节配置。 */
constexpr int kExtradataSize = 36;
/** ALACSpecificConfig 在原子头里的偏移。 */
constexpr int kConfigOffset = 12;

uint32_t readBe32(const uint8_t *data) {
    return (static_cast<uint32_t>(data[0]) << 24) | (static_cast<uint32_t>(data[1]) << 16) |
        (static_cast<uint32_t>(data[2]) << 8) | static_cast<uint32_t>(data[3]);
}

void writeBe32(uint8_t *dst, uint32_t value) {
    dst[0] = static_cast<uint8_t>(value >> 24);
    dst[1] = static_cast<uint8_t>(value >> 16);
    dst[2] = static_cast<uint8_t>(value >> 8);
    dst[3] = static_cast<uint8_t>(value);
}

void setError(char *error, size_t errorSize, const char *message) {
    if (error == nullptr || errorSize == 0) return;
    std::snprintf(error, errorSize, "%s", message);
}

/**
 * ALAC 的 magic cookie 在不同封装里有几种偏移：裸 24 字节 ALACSpecificConfig、
 * 带 4 字节 version/flags、带 8 字节盒头、带 12 字节 'alac' 盒头。
 * 与 Apple 参考解码器一致：先按盒类型跳过 'frma' / 'alac' 头，再用配置自身的合理性兜底试探。
 */
int findConfigOffset(const uint8_t *bytes, size_t size) {
    const auto looksLikeConfig = [&](int offset) {
        if (static_cast<size_t>(offset) + 24 > size) return false;
        const uint8_t *config = bytes + offset;
        const uint32_t frameLength = readBe32(config);
        const uint32_t bitDepth = config[5];
        const uint32_t channels = config[9];
        const uint32_t sampleRate = readBe32(config + 20);
        return frameLength > 0 && frameLength <= kMaxFramesPerPacket &&
            channels > 0 && channels <= kMaxChannels &&
            bitDepth >= 16 && bitDepth <= 32 && sampleRate > 0;
    };

    int at = 0;
    const auto hasType = [&](int offset, const char *type) {
        return static_cast<size_t>(offset) + 8 <= size &&
            std::memcmp(bytes + offset + 4, type, 4) == 0;
    };
    if (hasType(at, "frma")) at += 12;
    if (hasType(at, "alac")) at += 12;
    if (looksLikeConfig(at)) return at;

    for (int offset : {0, 4, 8, 12}) {
        if (looksLikeConfig(offset)) return offset;
    }
    return at;
}

/**
 * 把 cookie 里的 24 字节 ALACSpecificConfig 包成 ffmpeg 需要的 36 字节原子头：
 *   32 bits atom size (36) / 32 bits tag ("alac") / 32 bits version (0) /
 *   32 bits samples per frame / 24 bytes ALACSpecificConfig（原样大端）
 */
std::vector<uint8_t> buildExtradata(const uint8_t *config) {
    std::vector<uint8_t> out(kExtradataSize, 0);
    writeBe32(out.data(), static_cast<uint32_t>(kExtradataSize));
    std::memcpy(out.data() + 4, "alac", 4);
    std::memcpy(out.data() + kConfigOffset, config, 24);
    return out;
}

/**
 * 把 AVFrame 的平面数据写成交错小端 PCM。
 *
 * ffmpeg 的 alac 解码器输出：
 *   - 16 位：AV_SAMPLE_FMT_S16P，样本即原值。
 *   - 20 位：S32P，样本已左移 12 位。
 *   - 24 位：S32P，样本已左移 8 位。
 *   - 32 位：S32P，原值。
 * 20/24 位统一按 24 位容器输出（右移 8 位取高 24 位），与 FLAC 编码路径的位深一致。
 */
void writePcm(const AVFrame *frame, uint32_t channels, uint32_t bitDepth, uint8_t *dst) {
    const int samples = frame->nb_samples;
    if (frame->format == AV_SAMPLE_FMT_S16P) {
        for (int i = 0; i < samples; i++) {
            for (uint32_t ch = 0; ch < channels; ch++) {
                const auto value = static_cast<uint16_t>(
                    reinterpret_cast<const int16_t *>(frame->extended_data[ch])[i]);
                *dst++ = static_cast<uint8_t>(value);
                *dst++ = static_cast<uint8_t>(value >> 8);
            }
        }
        return;
    }

    const int container = AlacDecoderCore::bytesPerSample(bitDepth);
    const int shift = bitDepth == 20 || bitDepth == 24 ? 8 : 0;
    for (int i = 0; i < samples; i++) {
        for (uint32_t ch = 0; ch < channels; ch++) {
            const int32_t value =
                reinterpret_cast<const int32_t *>(frame->extended_data[ch])[i] >> shift;
            const auto bits = static_cast<uint32_t>(value);
            dst[0] = static_cast<uint8_t>(bits);
            dst[1] = static_cast<uint8_t>(bits >> 8);
            dst[2] = static_cast<uint8_t>(bits >> 16);
            if (container == 4) dst[3] = static_cast<uint8_t>(bits >> 24);
            dst += container;
        }
    }
}
}  // namespace

int AlacDecoderCore::bytesPerSample(uint32_t bitDepth) {
    if (bitDepth <= 16) return 2;
    if (bitDepth <= 24) return 3;
    return 4;
}

std::unique_ptr<AlacDecoderCore> AlacDecoderCore::create(
    const uint8_t *cookie, size_t size, char *error, size_t errorSize) {
    if (cookie == nullptr || size < 24) {
        setError(error, errorSize, "ALAC magic cookie 过短");
        return nullptr;
    }
    const int offset = findConfigOffset(cookie, size);
    if (offset < 0 || static_cast<size_t>(offset) + 24 > size) {
        setError(error, errorSize, "ALAC magic cookie 过短");
        return nullptr;
    }
    const uint8_t *config = cookie + offset;
    const auto extradata = buildExtradata(config);

    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_ALAC);
    if (codec == nullptr) {
        setError(error, errorSize, "ffmpeg 未包含 ALAC 解码器");
        return nullptr;
    }

    std::unique_ptr<AlacDecoderCore> core(new AlacDecoderCore());
    core->ctx_ = avcodec_alloc_context3(codec);
    if (core->ctx_ == nullptr) {
        setError(error, errorSize, "ALAC 解码上下文分配失败");
        return nullptr;
    }
    core->ctx_->extradata = static_cast<uint8_t *>(
        av_mallocz(extradata.size() + AV_INPUT_BUFFER_PADDING_SIZE));
    if (core->ctx_->extradata == nullptr) {
        setError(error, errorSize, "ALAC extradata 分配失败");
        return nullptr;
    }
    std::memcpy(core->ctx_->extradata, extradata.data(), extradata.size());
    core->ctx_->extradata_size = static_cast<int>(extradata.size());
    // 单线程解码：每包只有几毫秒，帧级线程反而增加延迟与内存开销。
    core->ctx_->thread_count = 1;

    const int status = avcodec_open2(core->ctx_, codec, nullptr);
    if (status < 0) {
        char message[128];
        std::snprintf(message, sizeof(message), "ALAC 解码器初始化失败 (status=%d, cookie=%d)",
                      status, static_cast<int>(size));
        setError(error, errorSize, message);
        return nullptr;
    }

    core->packet_ = av_packet_alloc();
    core->frame_ = av_frame_alloc();
    if (core->packet_ == nullptr || core->frame_ == nullptr) {
        setError(error, errorSize, "ALAC 解码缓冲分配失败");
        return nullptr;
    }

    // 以 cookie 里的配置为准；解码器可能修正了部分字段，这里只做范围保护。
    const uint32_t frameLength = readBe32(config);
    const uint32_t bitDepth = config[5];
    const uint32_t channels = config[9];
    const uint32_t sampleRate = readBe32(config + 20);
    core->framesPerPacket_ =
        frameLength > kMaxFramesPerPacket ? kMaxFramesPerPacket : frameLength;
    if (core->framesPerPacket_ == 0) core->framesPerPacket_ = 4096;
    core->channels_ = (channels == 0 || channels > kMaxChannels) ? 2 : channels;
    core->bitDepth_ = (bitDepth < 16 || bitDepth > 32) ? 16 : bitDepth;
    core->sampleRate_ = sampleRate == 0 ? 44100 : sampleRate;
    return core;
}

bool AlacDecoderCore::decode(
    const uint8_t *packet, size_t size, std::vector<uint8_t> &output,
    char *error, size_t errorSize) {
    if (ctx_ == nullptr || packet_ == nullptr || frame_ == nullptr) {
        setError(error, errorSize, "ALAC 解码器已释放");
        return false;
    }
    if (packet == nullptr || size == 0) {
        setError(error, errorSize, "ALAC 数据包为空");
        return false;
    }

    int status = av_new_packet(packet_, static_cast<int>(size));
    if (status < 0) {
        setError(error, errorSize, "ALAC 数据包分配失败");
        return false;
    }
    std::memcpy(packet_->data, packet, size);

    status = avcodec_send_packet(ctx_, packet_);
    av_packet_unref(packet_);
    if (status < 0) {
        char message[96];
        std::snprintf(message, sizeof(message), "ALAC 数据包提交失败 (code=%d)", status);
        setError(error, errorSize, message);
        return false;
    }
    status = avcodec_receive_frame(ctx_, frame_);
    if (status < 0) {
        char message[96];
        std::snprintf(message, sizeof(message), "ALAC 数据包解码失败 (code=%d)", status);
        setError(error, errorSize, message);
        return false;
    }

    const AVFrame *frame = frame_;
    if (frame->nb_samples <= 0 || frame->ch_layout.nb_channels <= 0) {
        av_frame_unref(frame_);
        setError(error, errorSize, "ALAC 解码结果为空");
        return false;
    }

    const auto channels = static_cast<uint32_t>(frame->ch_layout.nb_channels);
    const auto sampleCount = static_cast<size_t>(frame->nb_samples) * channels *
        bytesPerSample(bitDepth_);
    output.resize(sampleCount);
    writePcm(frame, channels, bitDepth_, output.data());
    av_frame_unref(frame_);
    return true;
}

AlacDecoderCore::~AlacDecoderCore() {
    if (packet_ != nullptr) av_packet_free(&packet_);
    if (frame_ != nullptr) av_frame_free(&frame_);
    if (ctx_ != nullptr) avcodec_free_context(&ctx_);
}
