#include <jni.h>
#include <cstring>
#include <cstdint>

static const uint8_t KG_SIGNING_KEY[] = "9046ad4ecae74a70aa750c1bb2307ae6";
#define KG_SIGNING_KEY_LEN 32

// ==================== MD5 ====================

struct MD5State {
    uint32_t a, b, c, d;
    uint64_t count;
    uint8_t buf[64];
};

static const uint32_t MD5_T[64] = {
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613,
        0xfd469501,
        0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e,
        0x49b40821,
        0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681,
        0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9,
        0x8d2a4c8a,
        0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60,
        0xbebfbc70,
        0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8,
        0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d,
        0x85845dd1,
        0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb,
        0xeb86d391,
};
static const uint8_t MD5_S[64] = {
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
};

#define ROTL32(x, n) (((x)<<(n))|((x)>>(32-(n))))

static void md5_transform(MD5State *s, const uint8_t block[64]) {
    uint32_t M[16];
    for (int i = 0; i < 16; i++)
        M[i] = (uint32_t) block[i * 4] | ((uint32_t) block[i * 4 + 1] << 8) |
               ((uint32_t) block[i * 4 + 2] << 16) | ((uint32_t) block[i * 4 + 3] << 24);

    uint32_t a = s->a, b = s->b, c = s->c, d = s->d;
    for (int i = 0; i < 64; i++) {
        uint32_t f;
        int g;
        if (i < 16) {
            f = (b & c) | ((~b) & d);
            g = i;
        }
        else if (i < 32) {
            f = (d & b) | ((~d) & c);
            g = (5 * i + 1) % 16;
        }
        else if (i < 48) {
            f = b ^ c ^ d;
            g = (3 * i + 5) % 16;
        }
        else {
            f = c ^ (b | (~d));
            g = (7 * i) % 16;
        }
        uint32_t tmp = d;
        d = c;
        c = b;
        b = b + ROTL32((a + f + MD5_T[i] + M[g]), MD5_S[i]);
        a = tmp;
    }
    s->a += a;
    s->b += b;
    s->c += c;
    s->d += d;
}

static void md5_init(MD5State *s) {
    s->a = 0x67452301;
    s->b = 0xefcdab89;
    s->c = 0x98badcfe;
    s->d = 0x10325476;
    s->count = 0;
}

static void md5_update(MD5State *s, const uint8_t *data, uint32_t len) {
    uint32_t idx = (uint32_t) (s->count & 63);
    s->count += len;
    for (uint32_t i = 0; i < len; i++) {
        s->buf[idx++] = data[i];
        if (idx == 64) {
            md5_transform(s, s->buf);
            idx = 0;
        }
    }
}

static void md5_final(MD5State *s, uint8_t digest[16]) {
    uint64_t bits = s->count * 8;
    uint8_t pad = 0x80;
    md5_update(s, &pad, 1);
    pad = 0;
    while ((s->count & 63) != 56) md5_update(s, &pad, 1);
    uint8_t lenBytes[8];
    for (int i = 0; i < 8; i++) lenBytes[i] = (uint8_t) (bits >> (i * 8));
    md5_update(s, lenBytes, 8);
    for (int i = 0; i < 4; i++) {
        digest[i] = (uint8_t) (s->a >> (i * 8));
        digest[i + 4] = (uint8_t) (s->b >> (i * 8));
        digest[i + 8] = (uint8_t) (s->c >> (i * 8));
        digest[i + 12] = (uint8_t) (s->d >> (i * 8));
    }
}

static void md5(const uint8_t *data, uint32_t len, uint8_t out[16]) {
    MD5State s{};
    md5_init(&s);
    md5_update(&s, data, len);
    md5_final(&s, out);
}

// ==================== SHA256 ====================

static const uint32_t SHA256_K[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7,
        0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc,
        0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351,
        0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e,
        0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585,
        0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f,
        0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
};

struct SHA256State {
    uint32_t h[8];
    uint64_t count;
    uint8_t buf[64];
};

#define ROTR32(x, n) (((x)>>(n))|((x)<<(32-(n))))
#define CH(x, y, z)  (((x)&(y))^((~(x))&(z)))
#define MAJ(x, y, z) (((x)&(y))^((x)&(z))^((y)&(z)))
#define EP0(x) (ROTR32(x,2)^ROTR32(x,13)^ROTR32(x,22))
#define EP1(x) (ROTR32(x,6)^ROTR32(x,11)^ROTR32(x,25))
#define SIG0(x) (ROTR32(x,7)^ROTR32(x,18)^((x)>>3))
#define SIG1(x) (ROTR32(x,17)^ROTR32(x,19)^((x)>>10))

static void sha256_transform(SHA256State *s, const uint8_t block[64]) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++)
        w[i] = ((uint32_t) block[i * 4] << 24) | ((uint32_t) block[i * 4 + 1] << 16) |
               ((uint32_t) block[i * 4 + 2] << 8) | (uint32_t) block[i * 4 + 3];
    for (int i = 16; i < 64; i++)
        w[i] = SIG1(w[i - 2]) + w[i - 7] + SIG0(w[i - 15]) + w[i - 16];

    uint32_t a = s->h[0], b = s->h[1], c = s->h[2], d = s->h[3],
            e = s->h[4], f = s->h[5], g = s->h[6], hh = s->h[7];
    for (int i = 0; i < 64; i++) {
        uint32_t t1 = hh + EP1(e) + CH(e, f, g) + SHA256_K[i] + w[i];
        uint32_t t2 = EP0(a) + MAJ(a, b, c);
        hh = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = b;
        b = a;
        a = t1 + t2;
    }
    s->h[0] += a;
    s->h[1] += b;
    s->h[2] += c;
    s->h[3] += d;
    s->h[4] += e;
    s->h[5] += f;
    s->h[6] += g;
    s->h[7] += hh;
}

static void sha256_init(SHA256State *s) {
    s->h[0] = 0x6a09e667;
    s->h[1] = 0xbb67ae85;
    s->h[2] = 0x3c6ef372;
    s->h[3] = 0xa54ff53a;
    s->h[4] = 0x510e527f;
    s->h[5] = 0x9b05688c;
    s->h[6] = 0x1f83d9ab;
    s->h[7] = 0x5be0cd19;
    s->count = 0;
}

static void sha256_update(SHA256State *s, const uint8_t *data, uint32_t len) {
    uint32_t idx = (uint32_t) (s->count & 63);
    s->count += len;
    for (uint32_t i = 0; i < len; i++) {
        s->buf[idx++] = data[i];
        if (idx == 64) {
            sha256_transform(s, s->buf);
            idx = 0;
        }
    }
}

static void sha256_final(SHA256State *s, uint8_t digest[32]) {
    uint64_t bits = s->count * 8;
    uint8_t pad = 0x80;
    sha256_update(s, &pad, 1);
    pad = 0;
    while ((s->count & 63) != 56) sha256_update(s, &pad, 1);
    uint8_t lenBytes[8];
    for (int i = 0; i < 8; i++) lenBytes[i] = (uint8_t) (bits >> ((7 - i) * 8));
    sha256_update(s, lenBytes, 8);
    for (int i = 0; i < 8; i++)
        for (int j = 0; j < 4; j++)
            digest[i * 4 + j] = (uint8_t) (s->h[i] >> ((3 - j) * 8));
}

// ==================== HMAC-SHA256 ====================

static void hmac_sha256(const uint8_t *key, uint32_t keyLen,
                        const uint8_t *msg, uint32_t msgLen,
                        uint8_t out[32]) {
    uint8_t k[64];
    memset(k, 0, 64);
    if (keyLen > 64) {
        SHA256State ks{};
        sha256_init(&ks);
        sha256_update(&ks, key, keyLen);
        sha256_final(&ks, k);
    } else {
        memcpy(k, key, keyLen);
    }

    uint8_t ipad[64], opad[64];
    for (int i = 0; i < 64; i++) {
        ipad[i] = k[i] ^ 0x36;
        opad[i] = k[i] ^ 0x5c;
    }

    SHA256State inner{};
    sha256_init(&inner);
    sha256_update(&inner, ipad, 64);
    sha256_update(&inner, msg, msgLen);
    uint8_t innerHash[32];
    sha256_final(&inner, innerHash);

    SHA256State outer{};
    sha256_init(&outer);
    sha256_update(&outer, opad, 64);
    sha256_update(&outer, innerHash, 32);
    sha256_final(&outer, out);
}

// ==================== Encoding ====================

static const char B64_ENC[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static int b64_encode(const uint8_t *src, int len, char *dst) {
    int o = 0;
    for (int i = 0; i < len; i += 3) {
        uint32_t v = (uint32_t) src[i] << 16;
        if (i + 1 < len) v |= (uint32_t) src[i + 1] << 8;
        if (i + 2 < len) v |= (uint32_t) src[i + 2];
        dst[o++] = B64_ENC[(v >> 18) & 63];
        dst[o++] = B64_ENC[(v >> 12) & 63];
        dst[o++] = (i + 1 < len) ? B64_ENC[(v >> 6) & 63] : '=';
        dst[o++] = (i + 2 < len) ? B64_ENC[v & 63] : '=';
    }
    dst[o] = 0;
    return o;
}

static const char HEX_CHARS[] = "0123456789abcdef";

static void hex_encode(const uint8_t *src, int len, char *dst) {
    for (int i = 0; i < len; i++) {
        dst[i * 2] = HEX_CHARS[src[i] >> 4];
        dst[i * 2 + 1] = HEX_CHARS[src[i] & 0x0f];
    }
    dst[len * 2] = 0;
}

// ==================== JNI Exports ====================

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_ikunshare_sound_utils_crypto_KgCrypto_getKgIdentity(JNIEnv *env, jobject, jstring body) {
    const char *bodyStr = env->GetStringUTFChars(body, nullptr);
    int bodyLen = (int) strlen(bodyStr);

    uint8_t keyMd5[16];
    md5(KG_SIGNING_KEY, KG_SIGNING_KEY_LEN, keyMd5);

    uint8_t hmacOut[32];
    hmac_sha256(keyMd5, 16, (const uint8_t *) bodyStr, bodyLen, hmacOut);
    env->ReleaseStringUTFChars(body, bodyStr);

    char b64[45];
    b64_encode(hmacOut, 32, b64);
    return env->NewStringUTF(b64);
}

JNIEXPORT jstring JNICALL
Java_com_ikunshare_sound_utils_crypto_KgCrypto_getSignature(JNIEnv *env, jobject, jstring body) {
    const char *bodyStr = env->GetStringUTFChars(body, nullptr);
    int bodyLen = (int) strlen(bodyStr);

    int totalLen = bodyLen + KG_SIGNING_KEY_LEN;
    auto *concat = new uint8_t[totalLen];
    memcpy(concat, bodyStr, bodyLen);
    memcpy(concat + bodyLen, KG_SIGNING_KEY, KG_SIGNING_KEY_LEN);
    env->ReleaseStringUTFChars(body, bodyStr);

    uint8_t hash[16];
    md5(concat, totalLen, hash);
    delete[] concat;

    char hex[33];
    hex_encode(hash, 16, hex);
    return env->NewStringUTF(hex);
}

JNIEXPORT jstring JNICALL
Java_com_ikunshare_sound_utils_crypto_KgCrypto_getTrialSignature(JNIEnv *env, jobject,
                                                                 jstring body) {
    const char *bodyStr = env->GetStringUTFChars(body, nullptr);
    int bodyLen = (int) strlen(bodyStr);

    uint8_t hmacOut[32];
    hmac_sha256(KG_SIGNING_KEY, KG_SIGNING_KEY_LEN,
                (const uint8_t *) bodyStr, bodyLen, hmacOut);
    env->ReleaseStringUTFChars(body, bodyStr);

    char hex[65];
    hex_encode(hmacOut, 32, hex);
    return env->NewStringUTF(hex);
}

}
