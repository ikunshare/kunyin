// 宿主侧验证器：用与 Android 完全相同的 AlacDecoderCore 解码 ALAC 包，
// 输出交错小端 PCM，供与 ffmpeg CLI 的结果逐字节比对。
//
// 用法：alac_verify <packets.bin> <out.pcm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "AlacDecoderCore.h"

namespace {
bool readAll(FILE *file, void *dst, size_t size) {
    return fread(dst, 1, size, file) == size;
}

uint32_t readBe32(const uint8_t *data) {
    return (static_cast<uint32_t>(data[0]) << 24) | (static_cast<uint32_t>(data[1]) << 16) |
        (static_cast<uint32_t>(data[2]) << 8) | static_cast<uint32_t>(data[3]);
}
}  // namespace

int main(int argc, char **argv) {
    if (argc < 3) {
        std::fprintf(stderr, "用法: alac_verify <packets.bin> <out.pcm>\n");
        return 2;
    }
    FILE *input = std::fopen(argv[1], "rb");
    if (input == nullptr) {
        std::perror("打开输入失败");
        return 2;
    }

    uint8_t lengthBytes[4];
    if (!readAll(input, lengthBytes, 4)) return 2;
    const uint32_t cookieSize = readBe32(lengthBytes);
    std::vector<uint8_t> cookie(cookieSize);
    if (!readAll(input, cookie.data(), cookieSize)) return 2;

    char error[128] = {0};
    auto core = AlacDecoderCore::create(cookie.data(), cookie.size(), error, sizeof(error));
    if (core == nullptr) {
        std::fprintf(stderr, "初始化失败: %s\n", error);
        return 1;
    }
    std::printf("channels=%u bitDepth=%u rate=%u framesPerPacket=%u\n",
                core->channels(), core->bitDepth(), core->sampleRate(),
                core->framesPerPacket());

    FILE *output = std::fopen(argv[2], "wb");
    if (output == nullptr) {
        std::perror("打开输出失败");
        return 2;
    }

    std::vector<uint8_t> packet;
    std::vector<uint8_t> pcm;
    size_t packets = 0;
    size_t frames = 0;
    const int container = AlacDecoderCore::bytesPerSample(core->bitDepth());
    while (readAll(input, lengthBytes, 4)) {
        const uint32_t packetSize = readBe32(lengthBytes);
        packet.resize(packetSize);
        if (!readAll(input, packet.data(), packetSize)) break;
        error[0] = '\0';
        if (!core->decode(packet.data(), packet.size(), pcm, error, sizeof(error))) {
            std::fprintf(stderr, "第 %zu 包解码失败: %s\n", packets, error);
            return 1;
        }
        std::fwrite(pcm.data(), 1, pcm.size(), output);
        frames += pcm.size() / (core->channels() * container);
        packets++;
    }

    std::printf("packets=%zu frames=%zu\n", packets, frames);
    std::fclose(output);
    std::fclose(input);
    return 0;
}
