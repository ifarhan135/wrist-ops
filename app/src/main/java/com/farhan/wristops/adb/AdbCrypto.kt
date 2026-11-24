package com.farhan.wristops.adb

import android.content.Context
import android.util.Base64
import com.farhan.wristops.Prefs
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * The RSA key the watch identifies itself with, in the exact shape adb expects.
 *
 * The key is generated once and kept in SharedPreferences, so the "Allow debugging
 * from this device?" prompt on the TV only has to be accepted a single time.
 */
class AdbCrypto private constructor(
    private val privateKey: PrivateKey,
    private val publicKey: RSAPublicKey
) {

    /**
     * adb hands us a 20 byte SHA-1 token and wants it signed with PKCS#1 v1.5.
     * OpenSSL's RSA_sign(NID_sha1, ...) prepends an ASN.1 DigestInfo header before
     * padding, so we do the same by hand and encrypt the result.
     */
    fun signToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    /**
     * The public key as adb wants it on the wire: a little-endian RSAPublicKey
     * struct, base64'd, with a trailing " user@host" and a NUL byte.
     */
    fun adbPublicKeyPayload(): ByteArray {
        val encoded = Base64.encodeToString(encodeStruct(), Base64.NO_WRAP)
        return ("$encoded wristops@watch\u0000").toByteArray(Charsets.US_ASCII)
    }

    /**
     * struct RSAPublicKey {
     *     uint32_t modulus_size_words;   // 64
     *     uint32_t n0inv;                // -1 / n[0] mod 2^32
     *     uint8_t  modulus[256];         // little-endian
     *     uint8_t  rr[256];              // little-endian, (2^2048)^2 mod n
     *     uint32_t exponent;             // 65537
     * }
     */
    private fun encodeStruct(): ByteArray {
        val n = publicKey.modulus
        val out = ByteArray(STRUCT_SIZE)
        var offset = 0

        offset = putInt(out, offset, MODULUS_WORDS)

        // n0inv = 2^32 - ((n mod 2^32)^-1 mod 2^32)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        offset = putInt(out, offset, n0inv.toLong().toInt())

        System.arraycopy(toLittleEndian(n, MODULUS_BYTES), 0, out, offset, MODULUS_BYTES)
        offset += MODULUS_BYTES

        // rr = (2^2048)^2 mod n
        val two = BigInteger.valueOf(2L)
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).modPow(two, n)
        System.arraycopy(toLittleEndian(rr, MODULUS_BYTES), 0, out, offset, MODULUS_BYTES)
        offset += MODULUS_BYTES

        putInt(out, offset, publicKey.publicExponent.toInt())
        return out
    }

    private fun putInt(buffer: ByteArray, offset: Int, value: Int): Int {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        return offset + 4
    }

    /** Fixed-width little-endian bytes, dropping BigInteger's sign byte. */
    private fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        val bigEndian = ByteArray(size)
        if (raw.size >= size) {
            // Copy the low `size` bytes, skipping any leading sign byte.
            System.arraycopy(raw, raw.size - size, bigEndian, 0, size)
        } else {
            System.arraycopy(raw, 0, bigEndian, size - raw.size, raw.size)
        }
        val littleEndian = ByteArray(size)
        for (i in 0 until size) {
            littleEndian[i] = bigEndian[size - 1 - i]
        }
        return littleEndian
    }

    companion object {
        private const val MODULUS_BYTES = 256
        private const val MODULUS_WORDS = MODULUS_BYTES / 4
        private const val STRUCT_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

        /** ASN.1 DigestInfo header for SHA-1. */
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        /** Loads the stored key, generating and saving one on first use. */
        fun load(context: Context): AdbCrypto {
            val prefs = Prefs.get(context)
            val storedPrivate = prefs.getString(Prefs.KEY_ADB_PRIVATE, null)
            val storedPublic = prefs.getString(Prefs.KEY_ADB_PUBLIC, null)

            if (storedPrivate != null && storedPublic != null) {
                try {
                    val factory = KeyFactory.getInstance("RSA")
                    val priv = factory.generatePrivate(
                        PKCS8EncodedKeySpec(Base64.decode(storedPrivate, Base64.NO_WRAP))
                    )
                    val pub = factory.generatePublic(
                        X509EncodedKeySpec(Base64.decode(storedPublic, Base64.NO_WRAP))
                    ) as RSAPublicKey
                    return AdbCrypto(priv, pub)
                } catch (e: Exception) {
                    // Fall through and make a fresh pair.
                }
            }

            val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048)
            }.generateKeyPair()

            prefs.edit()
                .putString(
                    Prefs.KEY_ADB_PRIVATE,
                    Base64.encodeToString((pair.private as RSAPrivateKey).encoded, Base64.NO_WRAP)
                )
                .putString(
                    Prefs.KEY_ADB_PUBLIC,
                    Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
                )
                .apply()

            return AdbCrypto(pair.private, pair.public as RSAPublicKey)
        }
    }
}
