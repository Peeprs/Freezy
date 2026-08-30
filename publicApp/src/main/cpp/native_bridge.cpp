#include <jni.h>
#include <cstdint>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

#include "encrypted_strings.h"

namespace {

std::string decodeString(int id) {
    for (const auto& entry : encrypted_strings::ENTRIES) {
        if (entry.id != id) continue;
        std::string decoded(entry.size, '\0');
        for (size_t i = 0; i < entry.size; ++i) {
            decoded[i] = static_cast<char>(
                entry.data[i] ^ encrypted_strings::KEY[i % sizeof(encrypted_strings::KEY)]
            );
        }
        return decoded;
    }
    return {};
}

#define ROTRIGHT(word, bits) (((word) >> (bits)) | ((word) << (32 - (bits))))
#define SSIG0(x) (ROTRIGHT(x, 7) ^ ROTRIGHT(x, 18) ^ ((x) >> 3))
#define SSIG1(x) (ROTRIGHT(x, 17) ^ ROTRIGHT(x, 19) ^ ((x) >> 10))
#define CH(x, y, z) (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x, y, z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define EP0(x) (ROTRIGHT(x, 2) ^ ROTRIGHT(x, 13) ^ ROTRIGHT(x, 22))
#define EP1(x) (ROTRIGHT(x, 6) ^ ROTRIGHT(x, 11) ^ ROTRIGHT(x, 25))

constexpr uint32_t SHA256_K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

std::string sha256(const std::string& input) {
    uint32_t h[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };
    std::vector<uint8_t> message(input.begin(), input.end());
    const uint64_t bitLength = message.size() * 8;
    message.push_back(0x80);
    while ((message.size() * 8) % 512 != 448) message.push_back(0x00);
    for (int i = 7; i >= 0; --i) message.push_back((bitLength >> (i * 8)) & 0xff);

    for (size_t offset = 0; offset < message.size(); offset += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(message[offset + i * 4]) << 24) |
                (static_cast<uint32_t>(message[offset + i * 4 + 1]) << 16) |
                (static_cast<uint32_t>(message[offset + i * 4 + 2]) << 8) |
                static_cast<uint32_t>(message[offset + i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            w[i] = SSIG1(w[i - 2]) + w[i - 7] + SSIG0(w[i - 15]) + w[i - 16];
        }

        uint32_t a = h[0], b = h[1], c = h[2], d = h[3];
        uint32_t e = h[4], f = h[5], g = h[6], tempH = h[7];
        for (int i = 0; i < 64; ++i) {
            const uint32_t t1 = tempH + EP1(e) + CH(e, f, g) + SHA256_K[i] + w[i];
            const uint32_t t2 = EP0(a) + MAJ(a, b, c);
            tempH = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += tempH;
    }

    std::ostringstream result;
    for (uint32_t value : h) result << std::hex << std::setw(8) << std::setfill('0') << value;
    return result.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_publicapp_N_a(JNIEnv* env, jclass, jint id) {
    const std::string value = decodeString(id);
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freezy_publicapp_N_b(
    JNIEnv* env,
    jclass,
    jstring androidId,
    jstring hardwareInfo
) {
    const char* idChars = env->GetStringUTFChars(androidId, nullptr);
    const char* hardwareChars = env->GetStringUTFChars(hardwareInfo, nullptr);
    const std::string combined = std::string(idChars ? idChars : "") + "|" +
        std::string(hardwareChars ? hardwareChars : "");
    if (idChars) env->ReleaseStringUTFChars(androidId, idChars);
    if (hardwareChars) env->ReleaseStringUTFChars(hardwareInfo, hardwareChars);

    // Conserva el mismo algoritmo de HWID de la aplicación completa para que
    // una licencia siga vinculada al mismo dispositivo al actualizar.
    const std::string salt = std::string("FREEZY_") + "SECRET_" + "SALT_" + "20" + "26";
    const std::string result = sha256(combined + salt);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freezy_publicapp_N_c(JNIEnv* env, jclass, jstring signerDigest) {
    if (signerDigest == nullptr) return JNI_FALSE;
    const char* chars = env->GetStringUTFChars(signerDigest, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    const std::string actual(chars);
    env->ReleaseStringUTFChars(signerDigest, chars);
    const std::string expected = decodeString(9);
    if (actual.size() != expected.size()) return JNI_FALSE;
    unsigned char difference = 0;
    for (size_t i = 0; i < actual.size(); ++i) {
        difference |= static_cast<unsigned char>(actual[i] ^ expected[i]);
    }
    return difference == 0 ? JNI_TRUE : JNI_FALSE;
}
