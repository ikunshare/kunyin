#include <jni.h>
#include <zlib.h>
#include <cstdlib>
#include <cstring>
#include <cctype>

#define DES_ENCRYPT 1
#define DES_DECRYPT 0

static const uint8_t sbox[8][64] = {
        {14, 4,  13, 1,  2,  15, 11, 8,  3,  10, 6,  12, 5,  9,  0,  7,  0,  15, 7,  4,  14, 2,  13, 1,  10, 6, 12, 11, 9,  5,  3,  8, 4,  1,  14, 8,  13, 6,  2,  11, 15, 12, 9,  7,  3,  10, 5,  0,  15, 12, 8,  2,  4,  9,  1,  7,  5,  11, 3,  14, 10, 0, 6,  13},
        {15, 1,  8,  14, 6,  11, 3,  4,  9,  7,  2,  13, 12, 0,  5,  10, 3,  13, 4,  7,  15, 2,  8,  15, 12, 0, 1,  10, 6,  9,  11, 5, 0,  14, 7,  11, 10, 4,  13, 1,  5,  8,  12, 6,  9,  3,  2,  15, 13, 8,  10, 1,  3,  15, 4,  2,  11, 6,  7,  12, 0,  5, 14, 9},
        {10, 0,  9,  14, 6,  3,  15, 5,  1,  13, 12, 7,  11, 4,  2,  8,  13, 7,  0,  9,  3,  4,  6,  10, 2,  8, 5,  14, 12, 11, 15, 1, 13, 6,  4,  9,  8,  15, 3,  0,  11, 1,  2,  12, 5,  10, 14, 7,  1,  10, 13, 0,  6,  9,  8,  7,  4,  15, 14, 3,  11, 5, 2,  12},
        {7,  13, 14, 3,  0,  6,  9,  10, 1,  2,  8,  5,  11, 12, 4,  15, 13, 8,  11, 5,  6,  15, 0,  3,  4,  7, 2,  12, 1,  10, 14, 9, 10, 6,  9,  0,  12, 11, 7,  13, 15, 1,  3,  14, 5,  2,  8,  4,  3,  15, 0,  6,  10, 10, 13, 8,  9,  4,  5,  11, 12, 7, 2,  14},
        {2,  12, 4,  1,  7,  10, 11, 6,  8,  5,  3,  15, 13, 0,  14, 9,  14, 11, 2,  12, 4,  7,  13, 1,  5,  0, 15, 10, 3,  9,  8,  6, 4,  2,  1,  11, 10, 13, 7,  8,  15, 9,  12, 5,  6,  3,  0,  14, 11, 8,  12, 7,  1,  14, 2,  13, 6,  15, 0,  9,  10, 4, 5,  3},
        {12, 1,  10, 15, 9,  2,  6,  8,  0,  13, 3,  4,  14, 7,  5,  11, 10, 15, 4,  2,  7,  12, 9,  5,  6,  1, 13, 14, 0,  11, 3,  8, 9,  14, 15, 5,  2,  8,  12, 3,  7,  0,  4,  10, 1,  13, 11, 6,  4,  3,  2,  12, 9,  5,  15, 10, 11, 14, 1,  7,  6,  0, 8,  13},
        {4,  11, 2,  14, 15, 0,  8,  13, 3,  12, 9,  7,  5,  10, 6,  1,  13, 0,  11, 7,  4,  9,  1,  10, 14, 3, 5,  12, 2,  15, 8,  6, 1,  4,  11, 13, 12, 3,  7,  14, 10, 15, 6,  8,  0,  5,  9,  2,  6,  11, 13, 8,  1,  4,  10, 7,  9,  5,  0,  15, 14, 2, 3,  12},
        {13, 2,  8,  4,  6,  15, 11, 1,  10, 9,  3,  14, 5,  0,  12, 7,  1,  15, 13, 8,  10, 3,  7,  4,  12, 5, 6,  11, 0,  14, 9,  2, 7,  11, 4,  1,  9,  12, 14, 2,  0,  6,  10, 13, 15, 3,  5,  8,  2,  1,  14, 7,  4,  10, 8,  13, 15, 12, 9,  0,  3,  5, 6,  11}
};

static const int key_rnd_shift[16] = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};
static const int key_perm_c[28] = {56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58,
                                   50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35};
static const int key_perm_d[28] = {62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60,
                                   52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3};
static const int key_compression[48] = {13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
                                        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
                                        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28,
                                        31};

static inline int bitnum(const uint8_t *a, int b, int c) {
    return ((a[(b / 32) * 4 + 3 - (b % 32) / 8] >> (7 - b % 8)) & 1) << c;
}

static inline int bitnum_intr(uint32_t a, int b, int c) {
    return ((a >> (31 - b)) & 1) << c;
}

static inline uint32_t bitnum_intl(uint32_t a, int b, int c) {
    return ((a << b) & 0x80000000u) >> c;
}

static inline int sbox_bit(int a) {
    return (a & 32) | ((a & 31) >> 1) | ((a & 1) << 4);
}

static void initial_permutation(const uint8_t *input, uint32_t *s0, uint32_t *s1) {
    *s0 = bitnum(input, 57, 31) | bitnum(input, 49, 30) | bitnum(input, 41, 29) |
          bitnum(input, 33, 28) | bitnum(input, 25, 27) | bitnum(input, 17, 26) |
          bitnum(input, 9, 25) | bitnum(input, 1, 24) | bitnum(input, 59, 23) |
          bitnum(input, 51, 22) | bitnum(input, 43, 21) | bitnum(input, 35, 20) |
          bitnum(input, 27, 19) | bitnum(input, 19, 18) | bitnum(input, 11, 17) |
          bitnum(input, 3, 16) | bitnum(input, 61, 15) | bitnum(input, 53, 14) |
          bitnum(input, 45, 13) | bitnum(input, 37, 12) | bitnum(input, 29, 11) |
          bitnum(input, 21, 10) | bitnum(input, 13, 9) | bitnum(input, 5, 8) |
          bitnum(input, 63, 7) | bitnum(input, 55, 6) | bitnum(input, 47, 5) |
          bitnum(input, 39, 4) | bitnum(input, 31, 3) | bitnum(input, 23, 2) |
          bitnum(input, 15, 1) | bitnum(input, 7, 0);
    *s1 = bitnum(input, 56, 31) | bitnum(input, 48, 30) | bitnum(input, 40, 29) |
          bitnum(input, 32, 28) | bitnum(input, 24, 27) | bitnum(input, 16, 26) |
          bitnum(input, 8, 25) | bitnum(input, 0, 24) | bitnum(input, 58, 23) |
          bitnum(input, 50, 22) | bitnum(input, 42, 21) | bitnum(input, 34, 20) |
          bitnum(input, 26, 19) | bitnum(input, 18, 18) | bitnum(input, 10, 17) |
          bitnum(input, 2, 16) | bitnum(input, 60, 15) | bitnum(input, 52, 14) |
          bitnum(input, 44, 13) | bitnum(input, 36, 12) | bitnum(input, 28, 11) |
          bitnum(input, 20, 10) | bitnum(input, 12, 9) | bitnum(input, 4, 8) |
          bitnum(input, 62, 7) | bitnum(input, 54, 6) | bitnum(input, 46, 5) |
          bitnum(input, 38, 4) | bitnum(input, 30, 3) | bitnum(input, 22, 2) |
          bitnum(input, 14, 1) | bitnum(input, 6, 0);
}

static void inverse_permutation(uint32_t s0, uint32_t s1, uint8_t *out) {
    out[3] = bitnum_intr(s1, 7, 7) | bitnum_intr(s0, 7, 6) | bitnum_intr(s1, 15, 5) |
             bitnum_intr(s0, 15, 4) | bitnum_intr(s1, 23, 3) | bitnum_intr(s0, 23, 2) |
             bitnum_intr(s1, 31, 1) | bitnum_intr(s0, 31, 0);
    out[2] = bitnum_intr(s1, 6, 7) | bitnum_intr(s0, 6, 6) | bitnum_intr(s1, 14, 5) |
             bitnum_intr(s0, 14, 4) | bitnum_intr(s1, 22, 3) | bitnum_intr(s0, 22, 2) |
             bitnum_intr(s1, 30, 1) | bitnum_intr(s0, 30, 0);
    out[1] = bitnum_intr(s1, 5, 7) | bitnum_intr(s0, 5, 6) | bitnum_intr(s1, 13, 5) |
             bitnum_intr(s0, 13, 4) | bitnum_intr(s1, 21, 3) | bitnum_intr(s0, 21, 2) |
             bitnum_intr(s1, 29, 1) | bitnum_intr(s0, 29, 0);
    out[0] = bitnum_intr(s1, 4, 7) | bitnum_intr(s0, 4, 6) | bitnum_intr(s1, 12, 5) |
             bitnum_intr(s0, 12, 4) | bitnum_intr(s1, 20, 3) | bitnum_intr(s0, 20, 2) |
             bitnum_intr(s1, 28, 1) | bitnum_intr(s0, 28, 0);
    out[7] = bitnum_intr(s1, 3, 7) | bitnum_intr(s0, 3, 6) | bitnum_intr(s1, 11, 5) |
             bitnum_intr(s0, 11, 4) | bitnum_intr(s1, 19, 3) | bitnum_intr(s0, 19, 2) |
             bitnum_intr(s1, 27, 1) | bitnum_intr(s0, 27, 0);
    out[6] = bitnum_intr(s1, 2, 7) | bitnum_intr(s0, 2, 6) | bitnum_intr(s1, 10, 5) |
             bitnum_intr(s0, 10, 4) | bitnum_intr(s1, 18, 3) | bitnum_intr(s0, 18, 2) |
             bitnum_intr(s1, 26, 1) | bitnum_intr(s0, 26, 0);
    out[5] = bitnum_intr(s1, 1, 7) | bitnum_intr(s0, 1, 6) | bitnum_intr(s1, 9, 5) |
             bitnum_intr(s0, 9, 4) | bitnum_intr(s1, 17, 3) | bitnum_intr(s0, 17, 2) |
             bitnum_intr(s1, 25, 1) | bitnum_intr(s0, 25, 0);
    out[4] = bitnum_intr(s1, 0, 7) | bitnum_intr(s0, 0, 6) | bitnum_intr(s1, 8, 5) |
             bitnum_intr(s0, 8, 4) | bitnum_intr(s1, 16, 3) | bitnum_intr(s0, 16, 2) |
             bitnum_intr(s1, 24, 1) | bitnum_intr(s0, 24, 0);
}

static uint32_t des_f(uint32_t state, const uint8_t *key) {
    uint32_t t1 =
            bitnum_intl(state, 31, 0) | ((state & 0xF0000000u) >> 1) | bitnum_intl(state, 4, 5) |
            bitnum_intl(state, 3, 6) | ((state & 0x0F000000u) >> 3) | bitnum_intl(state, 8, 11) |
            bitnum_intl(state, 7, 12) | ((state & 0x00F00000u) >> 5) | bitnum_intl(state, 12, 17) |
            bitnum_intl(state, 11, 18) | ((state & 0x000F0000u) >> 7) | bitnum_intl(state, 16, 23);
    uint32_t t2 =
            bitnum_intl(state, 15, 0) | ((state & 0x0000F000u) << 15) | bitnum_intl(state, 20, 5) |
            bitnum_intl(state, 19, 6) | ((state & 0x00000F00u) << 13) | bitnum_intl(state, 24, 11) |
            bitnum_intl(state, 23, 12) | ((state & 0x000000F0u) << 11) |
            bitnum_intl(state, 28, 17) | bitnum_intl(state, 27, 18) | ((state & 0x0000000Fu) << 9) |
            bitnum_intl(state, 0, 23);
    uint8_t lrgstate[6] = {(uint8_t) ((t1 >> 24) & 0xFF), (uint8_t) ((t1 >> 16) & 0xFF),
                           (uint8_t) ((t1 >> 8) & 0xFF), (uint8_t) ((t2 >> 24) & 0xFF),
                           (uint8_t) ((t2 >> 16) & 0xFF), (uint8_t) ((t2 >> 8) & 0xFF)};
    for (int i = 0; i < 6; i++) lrgstate[i] ^= key[i];
    state = (sbox[0][sbox_bit(lrgstate[0] >> 2)] << 28) |
            (sbox[1][sbox_bit(((lrgstate[0] & 0x03) << 4) | (lrgstate[1] >> 4))] << 24) |
            (sbox[2][sbox_bit(((lrgstate[1] & 0x0F) << 2) | (lrgstate[2] >> 6))] << 20) |
            (sbox[3][sbox_bit(lrgstate[2] & 0x3F)] << 16) |
            (sbox[4][sbox_bit(lrgstate[3] >> 2)] << 12) |
            (sbox[5][sbox_bit(((lrgstate[3] & 0x03) << 4) | (lrgstate[4] >> 4))] << 8) |
            (sbox[6][sbox_bit(((lrgstate[4] & 0x0F) << 2) | (lrgstate[5] >> 6))] << 4) |
            sbox[7][sbox_bit(lrgstate[5] & 0x3F)];
    return bitnum_intl(state, 15, 0) | bitnum_intl(state, 6, 1) | bitnum_intl(state, 19, 2) |
           bitnum_intl(state, 20, 3) | bitnum_intl(state, 28, 4) | bitnum_intl(state, 11, 5) |
           bitnum_intl(state, 27, 6) | bitnum_intl(state, 16, 7) | bitnum_intl(state, 0, 8) |
           bitnum_intl(state, 14, 9) | bitnum_intl(state, 22, 10) | bitnum_intl(state, 25, 11) |
           bitnum_intl(state, 4, 12) | bitnum_intl(state, 17, 13) | bitnum_intl(state, 30, 14) |
           bitnum_intl(state, 9, 15) | bitnum_intl(state, 1, 16) | bitnum_intl(state, 7, 17) |
           bitnum_intl(state, 23, 18) | bitnum_intl(state, 13, 19) | bitnum_intl(state, 31, 20) |
           bitnum_intl(state, 26, 21) | bitnum_intl(state, 2, 22) | bitnum_intl(state, 8, 23) |
           bitnum_intl(state, 18, 24) | bitnum_intl(state, 12, 25) | bitnum_intl(state, 29, 26) |
           bitnum_intl(state, 5, 27) | bitnum_intl(state, 21, 28) | bitnum_intl(state, 10, 29) |
           bitnum_intl(state, 3, 30) | bitnum_intl(state, 24, 31);
}

static void des_crypt(const uint8_t *input, const uint8_t key[16][6], uint8_t *output) {
    uint32_t s0, s1;
    initial_permutation(input, &s0, &s1);
    for (int i = 0; i < 15; i++) {
        uint32_t prev = s1;
        s1 = des_f(s1, key[i]) ^ s0;
        s0 = prev;
    }
    s0 = des_f(s1, key[15]) ^ s0;
    inverse_permutation(s0, s1, output);
}

static void key_schedule(const uint8_t *key, int mode, uint8_t schedule[16][6]) {
    uint32_t c = 0, d = 0;
    for (int i = 0; i < 28; i++) {
        c |= bitnum(key, key_perm_c[i], 31 - i);
        d |= bitnum(key, key_perm_d[i], 31 - i);
    }
    for (int i = 0; i < 16; i++) {
        c = ((c << key_rnd_shift[i]) | (c >> (28 - key_rnd_shift[i]))) & 0xFFFFFFF0u;
        d = ((d << key_rnd_shift[i]) | (d >> (28 - key_rnd_shift[i]))) & 0xFFFFFFF0u;
        int togen = (mode == DES_DECRYPT) ? (15 - i) : i;
        for (int j = 0; j < 6; j++) schedule[togen][j] = 0;
        for (int j = 0; j < 24; j++)
            schedule[togen][j / 8] |= bitnum_intr(c, key_compression[j], 7 - (j % 8));
        for (int j = 24; j < 48; j++)
            schedule[togen][j / 8] |= bitnum_intr(d, key_compression[j] - 27, 7 - (j % 8));
    }
}

static void tripledes_key_setup(const uint8_t *key, int mode, uint8_t schedule[3][16][6]) {
    if (mode == DES_ENCRYPT) {
        key_schedule(key, DES_ENCRYPT, schedule[0]);
        key_schedule(key + 8, DES_DECRYPT, schedule[1]);
        key_schedule(key + 16, DES_ENCRYPT, schedule[2]);
    } else {
        key_schedule(key + 16, DES_DECRYPT, schedule[0]);
        key_schedule(key + 8, DES_ENCRYPT, schedule[1]);
        key_schedule(key, DES_DECRYPT, schedule[2]);
    }
}

static void
tripledes_crypt(const uint8_t *input, const uint8_t schedule[3][16][6], uint8_t *output) {
    uint8_t buf[8];
    des_crypt(input, schedule[0], buf);
    des_crypt(buf, schedule[1], output);
    des_crypt(output, schedule[2], buf);
    memcpy(output, buf, 8);
}

static uint8_t *z_inflate(const uint8_t *in, size_t in_len, size_t *out_len) {
    if (!in_len) {
        *out_len = 0;
        return nullptr;
    }
    z_stream strm;
    memset(&strm, 0, sizeof(strm));
    strm.next_in = (Bytef *) in;
    strm.avail_in = in_len;
    if (inflateInit(&strm) != Z_OK) {
        *out_len = 0;
        return nullptr;
    }

    size_t buf_size = 4096;
    uint8_t *out = (uint8_t *) malloc(buf_size);
    if (!out) {
        inflateEnd(&strm);
        *out_len = 0;
        return nullptr;
    }

    int ret;
    while (1) {
        if (strm.total_out >= buf_size) {
            buf_size *= 2;
            uint8_t *tmp = (uint8_t *) realloc(out, buf_size);
            if (!tmp) {
                free(out);
                inflateEnd(&strm);
                *out_len = 0;
                return nullptr;
            }
            out = tmp;
        }
        strm.next_out = out + strm.total_out;
        strm.avail_out = buf_size - strm.total_out;
        ret = inflate(&strm, Z_NO_FLUSH);
        if (ret == Z_STREAM_END) break;
        if (ret != Z_OK) {
            free(out);
            inflateEnd(&strm);
            *out_len = 0;
            return nullptr;
        }
    }
    inflateEnd(&strm);
    *out_len = strm.total_out;
    return out;
}

static int b64_char_to_val(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static uint8_t *b64_decode_raw(const uint8_t *in, size_t in_len, size_t *out_len) {
    if (in_len == 0) {
        *out_len = 0;
        return nullptr;
    }
    size_t max_out = in_len * 3 / 4;
    uint8_t *out = (uint8_t *) malloc(max_out);
    if (!out) {
        *out_len = 0;
        return nullptr;
    }

    size_t i = 0, j = 0;
    int val = 0, valb = -8;
    for (i = 0; i < in_len; ++i) {
        int c = b64_char_to_val((char) in[i]);
        if (c == -1) break;
        val = (val << 6) + c;
        valb += 6;
        if (valb >= 0) {
            out[j++] = (val >> valb) & 0xFF;
            valb -= 8;
        }
    }
    if (j == 0 && i < in_len && in[i] != '=') {
        free(out);
        *out_len = 0;
        return nullptr;
    }
    *out_len = j;
    return out;
}

static const char b64_chars[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static char *b64_encode_raw(const uint8_t *in, size_t len) {
    size_t out_len = 4 * ((len + 2) / 3);
    char *out = (char *) malloc(out_len + 1);
    if (!out) return nullptr;

    size_t i, j;
    for (i = 0, j = 0; i < len;) {
        uint32_t octet_a = i < len ? in[i++] : 0;
        uint32_t octet_b = i < len ? in[i++] : 0;
        uint32_t octet_c = i < len ? in[i++] : 0;
        uint32_t triple = (octet_a << 0x10) + (octet_b << 0x08) + octet_c;
        out[j++] = b64_chars[(triple >> 3 * 6) & 0x3F];
        out[j++] = b64_chars[(triple >> 2 * 6) & 0x3F];
        out[j++] = (i > len + 1) ? '=' : b64_chars[(triple >> 1 * 6) & 0x3F];
        out[j++] = (i > len) ? '=' : b64_chars[(triple >> 0 * 6) & 0x3F];
    }
    out[out_len] = 0;
    return out;
}

static int hex_val(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return 0;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_ikunshare_sound_utils_lyric_LrcNative_decryptKrc(JNIEnv *env, jclass, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    if (len <= 4) return env->NewStringUTF("");

    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    uint8_t *xor_buf = (uint8_t *) malloc(len - 4);
    if (!xor_buf) {
        env->ReleaseByteArrayElements(data, buf, 0);
        return env->NewStringUTF("");
    }

    const uint8_t key[] = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105};
    for (int i = 0; i < len - 4; ++i) xor_buf[i] = (uint8_t) buf[i + 4] ^ key[i % 16];
    env->ReleaseByteArrayElements(data, buf, 0);

    size_t out_len;
    uint8_t *dec = z_inflate(xor_buf, len - 4, &out_len);
    free(xor_buf);

    if (!dec) return env->NewStringUTF("");

    uint8_t *str_buf = (uint8_t *) realloc(dec, out_len + 1);
    if (!str_buf) {
        free(dec);
        return env->NewStringUTF("");
    }
    str_buf[out_len] = 0;

    jstring res = env->NewStringUTF((char *) str_buf);
    free(str_buf);
    return res;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ikunshare_sound_utils_lyric_LrcNative_decryptKuwo(JNIEnv *env, jclass, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    uint8_t *u_buf = (uint8_t *) buf;

    int sep_idx = -1;
    for (int i = 0; i <= len - 4; ++i) {
        if (u_buf[i] == 0x0D && u_buf[i + 1] == 0x0A && u_buf[i + 2] == 0x0D &&
            u_buf[i + 3] == 0x0A) {
            sep_idx = i + 4;
            break;
        }
    }

    int start = (sep_idx != -1) ? sep_idx : 0;
    size_t dec_len;
    uint8_t *dec = z_inflate(u_buf + start, len - start, &dec_len);
    env->ReleaseByteArrayElements(data, buf, 0);

    if (!dec) return env->NewByteArray(0);

    size_t b64_dec_len;
    uint8_t *enc_data = b64_decode_raw(dec, dec_len, &b64_dec_len);

    if (!enc_data) {
        jbyteArray res = env->NewByteArray(dec_len);
        env->SetByteArrayRegion(res, 0, dec_len, (jbyte *) dec);
        free(dec);
        return res;
    }
    free(dec);

    const char *key_str = "yeelion";
    size_t key_len = 7;
    for (size_t i = 0; i < b64_dec_len; ++i) enc_data[i] ^= key_str[i % key_len];

    jbyteArray res = env->NewByteArray(b64_dec_len);
    env->SetByteArrayRegion(res, 0, b64_dec_len, (jbyte *) enc_data);
    free(enc_data);
    return res;
}

JNIEXPORT jstring JNICALL
Java_com_ikunshare_sound_utils_lyric_LrcNative_buildKuwoParams(JNIEnv *env, jclass,
                                                               jstring musicId) {
    const char *mId = env->GetStringUTFChars(musicId, nullptr);
    size_t id_len = strlen(mId);

    const char *prefix = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_";
    const char *suffix = "&lrcx=1";
    size_t params_len = strlen(prefix) + id_len + strlen(suffix);

    char *params = (char *) malloc(params_len + 1);
    if (!params) {
        env->ReleaseStringUTFChars(musicId, mId);
        return nullptr;
    }
    strcpy(params, prefix);
    strcat(params, mId);
    strcat(params, suffix);
    env->ReleaseStringUTFChars(musicId, mId);

    const char *key = "yeelion";
    size_t key_len = 7;
    for (size_t i = 0; i < params_len; ++i) params[i] ^= key[i % key_len];

    char *b64 = b64_encode_raw((uint8_t *) params, params_len);
    free(params);

    if (!b64) return nullptr;
    jstring res = env->NewStringUTF(b64);
    free(b64);
    return res;
}

JNIEXPORT jstring JNICALL
Java_com_ikunshare_sound_utils_lyric_LrcNative_decryptQrc(JNIEnv *env, jclass, jstring hexData) {
    if (!hexData) return env->NewStringUTF("");
    const char *hex = env->GetStringUTFChars(hexData, nullptr);
    size_t hex_len = strlen(hex);
    if (hex_len == 0 || hex_len % 2 != 0) {
        env->ReleaseStringUTFChars(hexData, hex);
        return env->NewStringUTF("");
    }

    size_t bin_len = hex_len / 2;
    uint8_t *encrypted = (uint8_t *) malloc(bin_len);
    if (!encrypted) {
        env->ReleaseStringUTFChars(hexData, hex);
        return nullptr;
    }

    for (size_t i = 0; i < bin_len; i++) {
        encrypted[i] = (hex_val(hex[i * 2]) << 4) | hex_val(hex[i * 2 + 1]);
    }
    env->ReleaseStringUTFChars(hexData, hex);

    const uint8_t key[] = {'!', '@', '#', ')', '(', '*', '$', '%', '1', '2', '3', 'Z', 'X', 'C',
                           '!', '@', '!', '@', '#', ')', '(', 'N', 'H', 'L'};
    uint8_t schedule[3][16][6];
    tripledes_key_setup(key, DES_DECRYPT, schedule);

    for (size_t i = 0; i < bin_len; i += 8) {
        if (i + 8 <= bin_len) tripledes_crypt(&encrypted[i], schedule, &encrypted[i]);
    }

    size_t dec_len;
    uint8_t *final_bytes = z_inflate(encrypted, bin_len, &dec_len);
    free(encrypted);

    if (!final_bytes) return env->NewStringUTF("");

    uint8_t *str_buf = (uint8_t *) realloc(final_bytes, dec_len + 1);
    if (!str_buf) {
        free(final_bytes);
        return env->NewStringUTF("");
    }
    str_buf[dec_len] = 0;

    jstring res = env->NewStringUTF((char *) str_buf);
    free(str_buf);
    return res;
}
}