#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <cstdint>

#define MIN(a, b) ((a) < (b) ? (a) : (b))

#define V1_KEY_SIZE           128
#define V1_OFFSET_BOUNDARY    0x7FFF
#define FIRST_SEGMENT_SIZE    0x0080
#define OTHER_SEGMENT_SIZE    0x1400
#define RC4_STREAM_CACHE_SIZE (OTHER_SEGMENT_SIZE + 512)

static const uint8_t EKEY_V2_PREFIX[] = "UVFNdXNpYyBFbmNWMixLZXk6";
#define EKEY_V2_PREFIX_LEN 24

static const uint8_t EKEY_V2_KEY1[16] = {
        0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
        0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28,
};
static const uint8_t EKEY_V2_KEY2[16] = {
        0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
        0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54,
};

static const uint8_t EKEY_SIMPLE_KEY[8] = {105, 86, 70, 56, 43, 32, 21, 11};

static const int8_t B64_TABLE[256] = {
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63,
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1,
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1,
        -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
};

static int b64_decode(const uint8_t *src, int srcLen, uint8_t *dst) {
    while (srcLen > 0 && (src[srcLen - 1] == '=' || src[srcLen - 1] <= ' '))
        srcLen--;
    int out = 0, buf = 0, bits = 0;
    for (int i = 0; i < srcLen; i++) {
        int v = B64_TABLE[src[i]];
        if (v < 0) continue;
        buf = (buf << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            dst[out++] = (uint8_t) (buf >> bits);
        }
    }
    return out;
}

#define B2I(p) ((uint32_t)(p)[0]<<24 | (uint32_t)(p)[1]<<16 | (uint32_t)(p)[2]<<8 | (uint32_t)(p)[3])
#define I2B(v, p) { (p)[0]=(uint8_t)((v)>>24); (p)[1]=(uint8_t)((v)>>16); \
                     (p)[2]=(uint8_t)((v)>>8);  (p)[3]=(uint8_t)(v); }

static void tea_decrypt_block(uint8_t *block, const uint32_t k[4]) {
    uint32_t y = B2I(block);
    uint32_t z = B2I(block + 4);
    uint32_t sum = 0x9e3779b9u * 16;
    for (int r = 0; r < 16; r++) {
        z -= ((y << 4) + k[2]) ^ (y + sum) ^ ((y >> 5) + k[3]);
        y -= ((z << 4) + k[0]) ^ (z + sum) ^ ((z >> 5) + k[1]);
        sum -= 0x9e3779b9u;
    }
    I2B(y, block)
    I2B(z, block + 4)
}

static uint8_t *tea_decrypt(const uint8_t *cipher, int cipherLen,
                            const uint8_t *key16, int *outLen) {
    if (cipherLen < 16 || (cipherLen % 8) != 0) return nullptr;

    uint32_t k[4];
    for (int i = 0; i < 4; i++) k[i] = B2I(key16 + i * 4);

    auto *dec = (uint8_t *) malloc(cipherLen);
    memcpy(dec, cipher, cipherLen);

    uint8_t iv1[8] = {}, iv2[8] = {}, nextIv1[8];
    for (int i = 0; i < cipherLen; i += 8) {
        uint8_t *p = &dec[i];
        memcpy(nextIv1, p, 8);
        for (int x = 0; x < 8; x++) p[x] ^= iv2[x];
        tea_decrypt_block(p, k);
        memcpy(iv2, p, 8);
        for (int x = 0; x < 8; x++) p[x] ^= iv1[x];
        memcpy(iv1, nextIv1, 8);
    }

    int pad = dec[0] & 7;
    int hdr = 3 + pad;
    int tail = 7;
    if (hdr + tail > cipherLen) {
        free(dec);
        return nullptr;
    }

    int payLen = cipherLen - hdr - tail;
    auto *payload = (uint8_t *) malloc(payLen);
    memcpy(payload, dec + hdr, payLen);
    free(dec);

    *outLen = payLen;
    return payload;
}

static int ekey_decrypt_v1(const uint8_t *ekey, int ekeyLen,
                           uint8_t **outKey, int *outKeyLen) {
    if (ekeyLen < 12) return -1;

    auto *raw = (uint8_t *) malloc(ekeyLen);
    int rawLen = b64_decode(ekey, ekeyLen, raw);
    if (rawLen < 12) {
        free(raw);
        return -1;
    }

    uint8_t teaKey[16];
    for (int i = 0; i < 8; i++) {
        teaKey[i * 2] = EKEY_SIMPLE_KEY[i];
        teaKey[i * 2 + 1] = raw[i];
    }

    int payLen;
    uint8_t *payload = tea_decrypt(raw + 8, rawLen - 8, teaKey, &payLen);
    if (!payload) {
        free(raw);
        return -1;
    }

    *outKeyLen = 8 + payLen;
    *outKey = (uint8_t *) malloc(*outKeyLen);
    memcpy(*outKey, raw, 8);
    memcpy(*outKey + 8, payload, payLen);

    free(payload);
    free(raw);
    return 0;
}

static int ekey_decrypt_v2(const uint8_t *ekey, int ekeyLen,
                           uint8_t **outKey, int *outKeyLen) {
    auto *raw = (uint8_t *) malloc(ekeyLen);
    int rawLen = b64_decode(ekey, ekeyLen, raw);

    int dec1Len;
    uint8_t *dec1 = tea_decrypt(raw, rawLen, EKEY_V2_KEY1, &dec1Len);
    free(raw);
    if (!dec1) return -1;

    int dec2Len;
    uint8_t *dec2 = tea_decrypt(dec1, dec1Len, EKEY_V2_KEY2, &dec2Len);
    free(dec1);
    if (!dec2) return -1;

    int v1Len = 0;
    while (v1Len < dec2Len && dec2[v1Len] != 0) v1Len++;

    int result = ekey_decrypt_v1(dec2, v1Len, outKey, outKeyLen);
    free(dec2);
    return result;
}

static int ekey_decrypt(const uint8_t *ekey, int ekeyLen,
                        uint8_t **outKey, int *outKeyLen) {
    if (ekeyLen > EKEY_V2_PREFIX_LEN &&
        memcmp(ekey, EKEY_V2_PREFIX, EKEY_V2_PREFIX_LEN) == 0) {
        return ekey_decrypt_v2(ekey + EKEY_V2_PREFIX_LEN,
                               ekeyLen - EKEY_V2_PREFIX_LEN,
                               outKey, outKeyLen);
    }
    return ekey_decrypt_v1(ekey, ekeyLen, outKey, outKeyLen);
}

static inline uint8_t qmc1_transform(const uint8_t key[V1_KEY_SIZE],
                                     uint8_t value, int64_t offset) {
    if (offset > V1_OFFSET_BOUNDARY)
        offset = offset % V1_OFFSET_BOUNDARY;
    return value ^ key[offset % V1_KEY_SIZE];
}

static void key_compress(const uint8_t *src, int srcLen, uint8_t dst[V1_KEY_SIZE]) {
    for (int i = 0; i < V1_KEY_SIZE; i++) {
        int idx = (i * i + 71214) % srcLen;
        uint8_t b = src[idx];
        uint32_t shift = (idx + 4) % 8;
        dst[i] = (uint8_t) ((b << shift) | (b >> shift));
    }
}

static void map_decrypt(const uint8_t key[V1_KEY_SIZE],
                        uint8_t *data, int len, int64_t offset) {
    for (int i = 0; i < len; i++)
        data[i] = qmc1_transform(key, data[i], offset + i);
}

static double rc4_hash(const uint8_t *key, int len) {
    uint32_t h = 1;
    for (int i = 0; i < len; i++) {
        if (key[i] == 0) continue;
        uint32_t next = h * (uint32_t) key[i];
        if (next == 0 || next <= h) break;
        h = next;
    }
    return (double) h;
}

static uint64_t rc4_segment_key(uint64_t id, uint8_t seed, double hash) {
    if (seed == 0) return 0;
    return (uint64_t) (hash / (double) ((id + 1) * (uint64_t) seed) * 100.0);
}

static void rc4_init_stream(const uint8_t *key, int n,
                            uint8_t *out, int outLen) {
    auto *s = (uint8_t *) malloc(n);
    for (int i = 0; i < n; i++) s[i] = (uint8_t) i;

    int j = 0;
    for (int i = 0; i < n; i++) {
        j = (j + s[i] + key[i % n]) % n;
        uint8_t t = s[i];
        s[i] = s[j];
        s[j] = t;
    }

    int si = 0, sj = 0;
    for (int k = 0; k < outLen; k++) {
        si = (si + 1) % n;
        sj = (sj + s[si]) % n;
        uint8_t t = s[si];
        s[si] = s[sj];
        s[sj] = t;
        out[k] = s[(s[si] + s[sj]) % n];
    }
    free(s);
}

typedef enum {
    CIPHER_MAP, CIPHER_RC4
} CipherType;

typedef struct {
    uint8_t key[V1_KEY_SIZE];
} MapCtx;

typedef struct {
    uint8_t *key;
    int keyLen;
    double hash;
    uint8_t keyStream[RC4_STREAM_CACHE_SIZE];
} RC4Ctx;

typedef struct {
    CipherType type;
    union {
        MapCtx map;
        RC4Ctx rc4;
    };
} MflacCtx;

static void rc4_decrypt(RC4Ctx *ctx, uint8_t *data, int len, int64_t offset) {
    int n = ctx->keyLen;
    int pos = 0;

    if (offset < FIRST_SEGMENT_SIZE) {
        int block = MIN(len, (int) (FIRST_SEGMENT_SIZE - offset));
        for (int i = 0; i < block; i++) {
            int64_t off = offset + i;
            uint8_t seed = ctx->key[(int) (off % n)];
            auto idx = (int) (rc4_segment_key((uint64_t) off, seed, ctx->hash) % (uint64_t) n);
            data[i] ^= ctx->key[idx];
        }
        pos = block;
        offset += block;
    }

    int excess = (int) (offset % OTHER_SEGMENT_SIZE);
    if (pos < len && excess != 0) {
        int block = MIN(len - pos, OTHER_SEGMENT_SIZE - excess);
        int id = (int) (offset / OTHER_SEGMENT_SIZE);
        uint8_t seed = ctx->key[id % n];
        int skip = (int) (rc4_segment_key((uint64_t) id, seed, ctx->hash) & 0x1FF);
        for (int i = 0; i < block; i++)
            data[pos + i] ^= ctx->keyStream[skip + excess + i];
        pos += block;
        offset += block;
    }

    while (pos < len) {
        int block = MIN(len - pos, OTHER_SEGMENT_SIZE);
        int id = (int) (offset / OTHER_SEGMENT_SIZE);
        uint8_t seed = ctx->key[id % n];
        int skip = (int) (rc4_segment_key((uint64_t) id, seed, ctx->hash) & 0x1FF);
        for (int i = 0; i < block; i++)
            data[pos + i] ^= ctx->keyStream[skip + i];
        pos += block;
        offset += block;
    }
}

static jlong create_cipher(uint8_t *key, int keyLen) {
    auto *ctx = (MflacCtx *) calloc(1, sizeof(MflacCtx));
    if (keyLen <= 300) {
        ctx->type = CIPHER_MAP;
        key_compress(key, keyLen, ctx->map.key);
        free(key);
    } else {
        ctx->type = CIPHER_RC4;
        ctx->rc4.key = key;
        ctx->rc4.keyLen = keyLen;
        ctx->rc4.hash = rc4_hash(key, keyLen);
        rc4_init_stream(key, keyLen, ctx->rc4.keyStream, RC4_STREAM_CACHE_SIZE);
    }
    return (jlong) ctx;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ikunshare_sound_utils_crypto_MflacCrypto_nativeInit(JNIEnv *env, jobject,
                                                             jbyteArray ekeyArr) {
    jsize ekeyLen = env->GetArrayLength(ekeyArr);
    if (ekeyLen < 12) return 0;

    jbyte *ekeyBytes = env->GetByteArrayElements(ekeyArr, nullptr);

    uint8_t *key = nullptr;
    int keyLen = 0;
    int rc = ekey_decrypt((const uint8_t *) ekeyBytes, ekeyLen, &key, &keyLen);

    env->ReleaseByteArrayElements(ekeyArr, ekeyBytes, JNI_ABORT);

    if (rc != 0 || !key || keyLen == 0) return 0;
    return create_cipher(key, keyLen);
}

JNIEXPORT void JNICALL
Java_com_ikunshare_sound_utils_crypto_MflacCrypto_nativeRelease(JNIEnv *, jobject, jlong handle) {
    auto *ctx = (MflacCtx *) handle;
    if (ctx) {
        if (ctx->type == CIPHER_RC4 && ctx->rc4.key) free(ctx->rc4.key);
        free(ctx);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_ikunshare_sound_utils_crypto_MflacCrypto_nativeDecryptChunk(JNIEnv *env, jobject,
                                                                     jlong handle,
                                                                     jbyteArray src, jlong offset) {
    auto *ctx = (MflacCtx *) handle;
    if (!ctx) return nullptr;

    jsize len = env->GetArrayLength(src);
    jbyteArray result = env->NewByteArray(len);
    jbyte *resBytes = env->GetByteArrayElements(result, nullptr);

    env->GetByteArrayRegion(src, 0, len, resBytes);

    auto *data = (uint8_t *) resBytes;
    if (ctx->type == CIPHER_MAP)
        map_decrypt(ctx->map.key, data, len, offset);
    else
        rc4_decrypt(&ctx->rc4, data, len, offset);

    env->ReleaseByteArrayElements(result, resBytes, 0);
    return result;
}

}