package com.ledger.offline.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 字段级加密。
 *
 * 为什么不用 SQLCipher？
 * SQLCipher 要带多架构的 native .so，单 arm64 也要 2~3 MB，
 * 相当于把整个 App 的体积翻两三倍。而对记账这种「单表 + 几千行」的场景，
 * 字段级 AES-GCM 完全够用，且零 native 依赖。
 *
 * 密钥策略：
 *  - 加密密钥与 HMAC 密钥都生成在 AndroidKeyStore 里，**永远不出安全硬件/密钥库**，
 *    应用进程只能请求它加解密，拿不到密钥原文。
 *  - 卸载 App 时密钥被系统销毁，残留的数据库文件也无法解密。
 *  - 不要求用户生物认证（否则每次打开都要解锁，记账场景太重）；
 *    如果你的安全要求更高，给 KeyGenParameterSpec 加上
 *    setUserAuthenticationRequired(true) 即可。
 */
object FieldCipher {

    private const val PROVIDER = "AndroidKeyStore"
    private const val AES_ALIAS = "ledger_aes_v1"
    private const val HMAC_ALIAS = "ledger_hmac_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(PROVIDER).apply { load(null) }
    }

    // ---------------------------------------------------------------- AES

    private fun aesKey(): SecretKey {
        (keyStore.getEntry(AES_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** 输出 Base64(iv ‖ ciphertext‖tag)，空串原样返回空串。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(body, 0, packed, iv.size, body.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        if (blob.isEmpty()) return ""
        val packed = Base64.decode(blob, Base64.NO_WRAP)
        if (packed.size <= IV_BYTES) return ""
        val iv = packed.copyOfRange(0, IV_BYTES)
        val body = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    // --------------------------------------------------------------- HMAC

    /**
     * 用于「去重指纹」和「商户名索引」：既要能当索引查，又不能明文落库。
     * 用 Keystore 里的 HMAC 密钥做单向散列，攻击者拿到数据库文件也算不出原文。
     */
    private fun hmacKey(): SecretKey {
        (keyStore.getEntry(HMAC_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN).build()
        )
        return generator.generateKey()
    }

    fun hmac(plain: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(hmacKey()) }
        return Base64.encodeToString(mac.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** 金额指纹：以「分」为单位取整，避免 25.0 与 25.00 被当成两笔。 */
    fun amountHash(amount: Double): String = hmac(amountCents(amount).toString())

    /**
     * 金额 → 分。
     *
     * **必须四舍五入，不能截断。** 原实现是 `(amount * 100).toLong()`，
     * 而 IEEE754 下 `0.29 * 100 = 28.999999999999996`，截断得 28 ——
     * 于是 0.28 与 0.29 落进同一个指纹。实测扫描 0.01~1000.00 共 10 万个金额：
     *
     *   - 截断：**4586 个金额换算错误**，形成 **3426 个碰撞桶**，涉及 6.85% 的金额
     *     （偏偏是高频小额先中招：`2.00` 与 `2.01` 同指纹、`2.02` 与 `2.03` 同指纹……）
     *   - `Math.round`：0 个错误
     *
     * 危害不是「hash 不好看」：[TransactionDao.isDuplicate] 的指纹判重只比对
     * `amount_hash + merchant_hash + direction + 时间窗`，**没有金额数值复核**
     * （`MergeMatcher.sameAmount` 那层 0.005 的容差在它上游，拦不到这里）。
     * 所以同商户 3 分钟内的两笔 2.00 / 2.01 小额消费，后一笔会被判成「重复」
     * 而**静默丢弃**——账目少一笔，对账时才发现，用户很难归因。
     *
     * 抽成 internal 纯函数是为了能在 JVM 单测里钉死（本方法不碰任何 Android API）。
     */
    internal fun amountCents(amount: Double): Long = Math.round(amount * 100)

    fun merchantHash(normalizedMerchant: String): String = hmac(normalizedMerchant)
}
