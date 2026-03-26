package com.astra.wakeup.ui

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

private const val ASTRA_PREFS = "astra"
private const val DEVICE_IDENTITY_VERSION = 1
private const val PEM_PUBLIC_HEADER = "-----BEGIN PUBLIC KEY-----"
private const val PEM_PUBLIC_FOOTER = "-----END PUBLIC KEY-----"
private const val PEM_PRIVATE_HEADER = "-----BEGIN PRIVATE KEY-----"
private const val PEM_PRIVATE_FOOTER = "-----END PRIVATE KEY-----"
private val ED25519_SPKI_PREFIX = byteArrayOf(
    0x30.toByte(),
    0x2a.toByte(),
    0x30.toByte(),
    0x05.toByte(),
    0x06.toByte(),
    0x03.toByte(),
    0x2b.toByte(),
    0x65.toByte(),
    0x70.toByte(),
    0x03.toByte(),
    0x21.toByte(),
    0x00.toByte()
)

data class OpenClawDeviceIdentity(
    val version: Int = DEVICE_IDENTITY_VERSION,
    val deviceId: String,
    val publicKeyPem: String,
    val privateKeyPem: String,
    val createdAtMs: Long,
    val algorithm: String = "Ed25519"
)

data class OpenClawSignedDeviceAssertion(
    val deviceId: String,
    val publicKey: String,
    val signature: String,
    val signedAtMs: Long,
    val nonce: String
)

object OpenClawGatewayCrypto {
    fun ensureDeviceIdentity(context: Context): Result<OpenClawDeviceIdentity> {
        val prefs = context.getSharedPreferences(ASTRA_PREFS, Context.MODE_PRIVATE)
        loadIdentity(prefs)?.let { return Result.success(it) }

        return runCatching {
            val generated = generateEd25519Identity()
            persistIdentity(prefs, generated)
            generated
        }
    }

    fun currentDeviceIdentity(context: Context): OpenClawDeviceIdentity? {
        val prefs = context.getSharedPreferences(ASTRA_PREFS, Context.MODE_PRIVATE)
        return loadIdentity(prefs)
    }

    fun signConnectChallenge(
        context: Context,
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        nonce: String,
        platform: String = "android",
        deviceFamily: String = Build.MODEL.orEmpty(),
        signatureToken: String? = null
    ): Result<OpenClawSignedDeviceAssertion> {
        if (nonce.isBlank()) return Result.failure(IllegalArgumentException("Missing connect nonce"))

        return ensureDeviceIdentity(context).mapCatching { identity ->
            val signedAtMs = System.currentTimeMillis()
            val payload = buildDeviceAuthPayloadV3(
                deviceId = identity.deviceId,
                clientId = clientId,
                clientMode = clientMode,
                role = role,
                scopes = scopes,
                signedAtMs = signedAtMs,
                token = signatureToken,
                nonce = nonce,
                platform = platform,
                deviceFamily = deviceFamily
            )
            val signature = signEd25519(identity.privateKeyPem, payload)
            OpenClawSignedDeviceAssertion(
                deviceId = identity.deviceId,
                publicKey = publicKeyRawBase64UrlFromPem(identity.publicKeyPem),
                signature = signature,
                signedAtMs = signedAtMs,
                nonce = nonce
            )
        }
    }

    private fun loadIdentity(prefs: android.content.SharedPreferences): OpenClawDeviceIdentity? {
        val deviceId = prefs.getString("gateway_device_id", null) ?: return null
        val publicKeyPem = prefs.getString("gateway_device_public_key_pem", null) ?: return null
        val privateKeyPem = prefs.getString("gateway_device_private_key_pem", null) ?: return null
        val createdAtMs = prefs.getLong("gateway_device_identity_created_at", 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val version = prefs.getInt("gateway_device_identity_version", DEVICE_IDENTITY_VERSION)
        val algorithm = prefs.getString("gateway_device_key_algorithm", "Ed25519") ?: "Ed25519"
        return OpenClawDeviceIdentity(version, deviceId, publicKeyPem, privateKeyPem, createdAtMs, algorithm)
    }

    private fun persistIdentity(
        prefs: android.content.SharedPreferences,
        identity: OpenClawDeviceIdentity
    ) {
        prefs.edit()
            .putInt("gateway_device_identity_version", identity.version)
            .putString("gateway_device_key_algorithm", identity.algorithm)
            .putString("gateway_device_id", identity.deviceId)
            .putString("gateway_device_public_key_pem", identity.publicKeyPem)
            .putString("gateway_device_private_key_pem", identity.privateKeyPem)
            .putLong("gateway_device_identity_created_at", identity.createdAtMs)
            .apply()
    }

    private fun generateEd25519Identity(): OpenClawDeviceIdentity {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val pair = generator.generateKeyPair()
        val publicKeyPem = publicKeyToPem(pair.public)
        val privateKeyPem = privateKeyToPem(pair.private)
        return OpenClawDeviceIdentity(
            deviceId = fingerprintPublicKey(publicKeyPem),
            publicKeyPem = publicKeyPem,
            privateKeyPem = privateKeyPem,
            createdAtMs = System.currentTimeMillis()
        )
    }

    private fun signEd25519(privateKeyPem: String, payload: String): String {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(parsePrivateKey(privateKeyPem))
        signature.update(payload.toByteArray(Charsets.UTF_8))
        return base64Url(signature.sign())
    }

    private fun buildDeviceAuthPayloadV3(
        deviceId: String,
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String?,
        nonce: String,
        platform: String?,
        deviceFamily: String?
    ): String {
        val normalizedScopes = scopes.mapNotNull { scope ->
            scope.trim().takeIf { it.isNotBlank() }
        }.joinToString(",")
        val normalizedPlatform = normalizeDeviceMetadataForAuth(platform)
        val normalizedFamily = normalizeDeviceMetadataForAuth(deviceFamily)
        return listOf(
            "v3",
            deviceId,
            clientId,
            clientMode,
            role,
            normalizedScopes,
            signedAtMs.toString(),
            token.orEmpty(),
            nonce,
            normalizedPlatform,
            normalizedFamily
        ).joinToString("|")
    }

    private fun normalizeDeviceMetadataForAuth(value: String?): String {
        return value?.trim()?.lowercase().orEmpty()
    }

    private fun publicKeyRawBase64UrlFromPem(publicKeyPem: String): String = base64Url(derivePublicKeyRaw(publicKeyPem))

    private fun fingerprintPublicKey(publicKeyPem: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(derivePublicKeyRaw(publicKeyPem))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun derivePublicKeyRaw(publicKeyPem: String): ByteArray {
        val spki = parsePublicKey(publicKeyPem).encoded
        return if (spki.size == ED25519_SPKI_PREFIX.size + 32 && spki.copyOfRange(0, ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)) {
            spki.copyOfRange(ED25519_SPKI_PREFIX.size, spki.size)
        } else {
            spki
        }
    }

    private fun parsePublicKey(pem: String): PublicKey {
        val der = pemBody(pem)
        return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val der = pemBody(pem)
        return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun publicKeyToPem(publicKey: PublicKey): String = toPem(PEM_PUBLIC_HEADER, PEM_PUBLIC_FOOTER, publicKey.encoded)

    private fun privateKeyToPem(privateKey: PrivateKey): String = toPem(PEM_PRIVATE_HEADER, PEM_PRIVATE_FOOTER, privateKey.encoded)

    private fun toPem(header: String, footer: String, der: ByteArray): String {
        val body = Base64.encodeToString(der, Base64.NO_WRAP)
            .chunked(64)
            .joinToString("\n")
        return "$header\n$body\n$footer\n"
    }

    private fun pemBody(pem: String): ByteArray {
        val body = pem
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString(separator = "")
        return Base64.decode(body, Base64.DEFAULT)
    }

    private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun describeAvailabilityError(error: Throwable): String {
        return when (error) {
            is java.security.NoSuchAlgorithmException -> "Ed25519 not available on this Android runtime"
            else -> error.message ?: error::class.java.simpleName
        }
    }

    fun identityDebugJson(context: Context): JSONObject {
        val identity = currentDeviceIdentity(context)
        return JSONObject().apply {
            put("present", identity != null)
            put("deviceId", identity?.deviceId)
            put("algorithm", identity?.algorithm)
            put("createdAtMs", identity?.createdAtMs)
        }
    }
}
