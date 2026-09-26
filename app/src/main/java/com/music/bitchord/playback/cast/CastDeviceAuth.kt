package com.music.bitchord.playback.cast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Proves that the device on the other end of a Cast connection is a genuine
 * Cast device before anything is sent to it — the check Google's own Cast
 * library makes, reimplemented from the open-source Open Screen project
 * (`cast/sender/channel/cast_auth_util.cc`, `cast/common/certificate`).
 *
 * Cast devices serve TLS with a short-lived self-signed certificate, which no
 * public CA can vouch for. What vouches for them instead is a device
 * certificate issued under Google's Cast root CAs and burned into the device.
 * The sender sends a random nonce on the device-auth channel; the device signs
 * the nonce plus the DER bytes of the TLS certificate it is serving with its
 * device key, and returns the signature with its certificate chain. Verifying
 * that chain to a Cast root, and the signature over *this connection's* TLS
 * certificate, binds the encrypted channel to a real Cast device: a machine
 * that is not one cannot produce the signature, and one relaying another
 * device's answer would be serving a different TLS certificate.
 *
 * Plain JVM code, unit-tested.
 */
internal object CastDeviceAuth {

    const val NAMESPACE = "urn:x-cast:com.google.cast.tp.deviceauth"

    private const val NONCE_BYTES = 16
    private const val MIN_RSA_BITS = 2048

    /** The TLS certificate doubles as the signature's expiry and must be short-lived. */
    private val MAX_TLS_CERT_LIFETIME_MS = TimeUnit.DAYS.toMillis(4)

    /** The two roots Open Screen trusts for Cast device certificates. */
    private val CAST_ROOTS: List<X509Certificate> by lazy {
        listOf(
            // CN=Cast Root CA, OU=Cast, O=Google Inc — valid until 2034-03-28.
            // SHA-256 80:9A:F1:47:00:B3:FE:26:11:AD:59:7E:B1:58:4D:63:54:31:3B:64:CC:DB:33:90:F0:97:FA:3E:58:26:D6:CA
            "MIIDxTCCAq2gAwIBAgIBAjANBgkqhkiG9w0BAQUFADB1MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UE" +
            "BwwNTW91bnRhaW4gVmlldzETMBEGA1UECgwKR29vZ2xlIEluYzENMAsGA1UECwwEQ2FzdDEVMBMGA1UEAwwMQ2FzdCBSb290IENB" +
            "MB4XDTE0MDQwMjE3MzQyNloXDTM0MDMyODE3MzQyNlowdTELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNV" +
            "BAcMDU1vdW50YWluIFZpZXcxEzARBgNVBAoMCkdvb2dsZSBJbmMxDTALBgNVBAsMBENhc3QxFTATBgNVBAMMDENhc3QgUm9vdCBD" +
            "QTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALrZZZ3aOdPBd/bU0K6PWAhoOUqV7XDP/XkIqarl6binLaBnR4qeyc9w" +
            "swWHaRHscJiXw+bDw+u9xrA9/E/BXjif2s9zMAZbeTfBXoyHR5SaQZIq1pXEcVwnXQixgMaSvRvjQZeh7HWfVZ4+n48cx2VkB9Oz" +
            "lqEEn5HE3gp7bNnIwHgxoBlCqeiD48788c7CLiRGlQkZysBGsuUButdP87/2aa2ZBPqgBzkO5t9RRwfA5KlcS5TFL7OgMH/nlWuy" +
            "rzIN8YzVbct7R6cIq8sno03PSlrxBdH4YsUQKnRpquZLlvub2GPkWGbTrYpu/3te+aVWHi2CMVvw4iTmQUofrhMCAwEAAaNgMF4w" +
            "DwYDVR0TBAgwBgEB/wIBAjAdBgNVHQ4EFgQUfJoefd95VLzXzF7KmYZFeWV0KBkwHwYDVR0jBBgwFoAUfJoefd95VLzXzF7KmYZF" +
            "eWV0KBkwCwYDVR0PBAQDAgEGMA0GCSqGSIb3DQEBBQUAA4IBAQCA9Fr7PSgZUSDX1PsSl0pl8lg1kncwavHXtlEaf5rNx3sDQq1V" +
            "agCv8OEGwr1reHXb/kERU0o5u5o6xlk0Lywz47LWXH/deOtxWznag5DFMeI/I+/a6ystd17ew0PSyWtZgsrV7fqhZFvL8Q0aYuGc" +
            "6KcYcPBfF5b47Ybbrh3gzz5dLu4WbZUrPP2X8wVaJGhNObb45Fi69eAmeFHFW11OCeVsR4t6Wi6JU+bMNlsmPPhyQwKC0ivN8NOj" +
            "7BM+UtWDPQfcHUNlejMCAaPOt9ZgUTsJwiOKMv6YGWBik4XNNEbb1SMPedp3ACoCbYNYzgN3NeGjIJPCSqKkRhx1LB9N",
            // CN=Eureka Root CA, OU=Google TV, O=Google Inc — valid until 2032-12-12.
            // SHA-256 CA:F6:D1:E3:7B:53:22:03:E0:1A:76:BF:07:18:7B:B7:31:CC:D3:88:01:56:5A:B2:21:1A:2C:0A:B7:F3:BC:46
            "MIIDwzCCAqugAwIBAgIBATANBgkqhkiG9w0BAQUFADB8MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UE" +
            "BwwNTW91bnRhaW4gVmlldzETMBEGA1UECgwKR29vZ2xlIEluYzESMBAGA1UECwwJR29vZ2xlIFRWMRcwFQYDVQQDDA5FdXJla2Eg" +
            "Um9vdCBDQTAeFw0xMjEyMTcyMjM5MzNaFw0zMjEyMTIyMjM5MzNaMHwxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlh" +
            "MRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRMwEQYDVQQKDApHb29nbGUgSW5jMRIwEAYDVQQLDAlHb29nbGUgVFYxFzAVBgNVBAMM" +
            "DkV1cmVrYSBSb290IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuRHQ6hLcMuHfXDNrGXMdnZ7QOXa/pYQJpv1u" +
            "bencjzZO6YgCvZ/06ET9TPWaAlZqRypjbFhFzHxmJNx5ecMqpLKLoPeitc0Gftu+7AyG8g0kYHSEyikjhALYp+078ewmR1TjsS3m" +
            "ZA/2csXpmFIXwPzyLCDIQPhHyTKeO5exi/WYJHBjZhnBUugEBT1fjbzYS693mG8feNG2UCdN5OwUaWcfWK+poBEmPJQyB3/X6Wkf" +
            "rj9PY4qPidbyGXhcIY6xtlfYwOHufW7d8ToKavG6//mDL9y1pCAXYzbvyGIZzFbOsuoxiUt4WMG/AxOZ4BLyiKqblNrddnkXHjTR" +
            "CsQHRQIDAQABo1AwTjAdBgNVHQ4EFgQURE4qR1jYuUiR9k/OdKkdMpqNjekwHwYDVR0jBBgwFoAURE4qR1jYuUiR9k/OdKkdMpqN" +
            "jekwDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQUFAAOCAQEAP8gmoG5cBUB5oZipM95odIXurrccM1mwEd6f9E/T61EJfUd+blGF" +
            "9FTNg5glsbqwV+yT2xLi7FFJepZzm8iWbYWM0+E8+jLiWAx3bYcMNAGqMKl24MDn214b6RAwpOAJSSa5WM1aB+VQdd6aO/ZTfrFT" +
            "XkUnTxfjCDOyUAq79PwllyneQXUw+nc4qmWKc0/qEXvrfBdgJw68PnZS2IvtGvjrN7sR/a5wFwr+4K0Gsx9pinIEwsAzC9YvY0wz" +
            "ERS4YjaIxQNlARmj7wC7bw6S/zQcodYx0Fxen5l9x8q9fHIL9Fylfm4EqNKZLFEBFP6iSPB+voQNtNPi8w593ov1Mw==",
        ).map { parseCertificate(Base64.getDecoder().decode(it)) }
    }

    fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)

    /**
     * `DeviceAuthMessage { challenge: AuthChallenge { signature_algorithm: RSASSA_PKCS1v15,
     * sender_nonce, hash_algorithm: SHA256 } }`.
     */
    fun challenge(nonce: ByteArray): ByteArray {
        val challenge = ByteArrayOutputStream().apply {
            with(CastProtocol) {
                writeVarintField(1, 1) // RSASSA_PKCS1v15
                writeBytesField(2, nonce)
                writeVarintField(3, 1) // SHA256
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            with(CastProtocol) { writeBytesField(1, challenge) }
        }.toByteArray()
    }

    /** The fields of an AuthResponse the verification reads. */
    class Response(
        val signature: ByteArray,
        val deviceCertificate: ByteArray,
        val intermediates: List<ByteArray>,
        val senderNonce: ByteArray,
        val sha256: Boolean,
    )

    /** Parses a DeviceAuthMessage; throws [AuthException] for an error reply or a malformed one. */
    fun parseResponse(message: ByteArray): Response {
        var response: ByteArray? = null
        var errored = false
        ProtoReader(message).forEachField { field, value ->
            when (field) {
                2 -> response = value as? ByteArray
                3 -> errored = true
            }
        }
        if (errored) throw AuthException("the device reported an authentication error")
        val body = response ?: throw AuthException("no authentication response")
        var signature: ByteArray? = null
        var certificate: ByteArray? = null
        val intermediates = ArrayList<ByteArray>()
        var nonce = ByteArray(0)
        var hash = 0L
        ProtoReader(body).forEachField { field, value ->
            when (field) {
                1 -> signature = value as? ByteArray
                2 -> certificate = value as? ByteArray
                3 -> (value as? ByteArray)?.let(intermediates::add)
                5 -> nonce = value as? ByteArray ?: nonce
                6 -> hash = value as? Long ?: hash
            }
        }
        return Response(
            signature = signature ?: throw AuthException("response has no signature"),
            deviceCertificate = certificate ?: throw AuthException("response has no device certificate"),
            intermediates = intermediates,
            senderNonce = nonce,
            sha256 = hash == 1L,
        )
    }

    /**
     * Verifies [response] for a connection whose TLS peer presented
     * [peerCertificateDer]. Throws [AuthException] with the reason when the
     * device is not provably a Cast device.
     *
     * [roots] and [now] are parameters for tests; the defaults are the real
     * Cast roots and the current time.
     */
    fun verify(
        response: Response,
        peerCertificateDer: ByteArray,
        sentNonce: ByteArray,
        roots: List<X509Certificate> = CAST_ROOTS,
        now: Date = Date(),
    ) {
        // The TLS certificate itself: current, and short-lived, since the
        // signature below is only as fresh as it is.
        val peer = parseCertificate(peerCertificateDer)
        if (now.before(peer.notBefore)) throw AuthException("TLS certificate is not valid yet")
        if (now.after(peer.notAfter)) throw AuthException("TLS certificate has expired")
        if (peer.notAfter.time - now.time > MAX_TLS_CERT_LIFETIME_MS) {
            throw AuthException("TLS certificate lifetime is too long")
        }
        // Older devices do not echo the nonce back; Open Screen accepts that
        // too, and the signature still covers this connection's certificate.
        if (response.senderNonce.isNotEmpty() && !response.senderNonce.contentEquals(sentNonce)) {
            throw AuthException("sender nonce mismatch")
        }

        val device = parseCertificate(response.deviceCertificate)
        verifyChain(device, response.intermediates.map(::parseCertificate), roots, now)

        val signed = response.senderNonce + peerCertificateDer
        val verifier = Signature.getInstance(if (response.sha256) "SHA256withRSA" else "SHA1withRSA")
        verifier.initVerify(device.publicKey)
        verifier.update(signed)
        if (!verifier.verify(response.signature)) throw AuthException("device signature does not match")
    }

    /**
     * The device certificate, through the intermediates, to one of [roots]:
     * each link signed by the next, every certificate current, issuers marked
     * as CAs allowed to sign certificates, the device allowed to sign data,
     * and every key RSA of at least 2048 bits.
     */
    private fun verifyChain(
        device: X509Certificate,
        intermediates: List<X509Certificate>,
        roots: List<X509Certificate>,
        now: Date,
    ) {
        checkKey(device)
        checkCurrent(device, now)
        val deviceUsage = device.keyUsage
        if (deviceUsage != null && !deviceUsage[0]) throw AuthException("device certificate cannot sign")

        var current = device
        val remaining = intermediates.toMutableList()
        repeat(intermediates.size + 1) {
            roots.firstOrNull { issued(current, it) }?.let { root ->
                checkCurrent(root, now)
                return
            }
            val issuer = remaining.firstOrNull { issued(current, it) }
                ?: throw AuthException("certificate chain does not lead to a Cast root")
            remaining.remove(issuer)
            checkKey(issuer)
            checkCurrent(issuer, now)
            if (issuer.basicConstraints < 0) throw AuthException("intermediate is not a CA")
            val usage = issuer.keyUsage
            if (usage != null && !usage[5]) throw AuthException("intermediate cannot sign certificates")
            current = issuer
        }
        throw AuthException("certificate chain does not lead to a Cast root")
    }

    private fun issued(child: X509Certificate, issuer: X509Certificate): Boolean {
        if (child.issuerX500Principal != issuer.subjectX500Principal) return false
        return runCatching { child.verify(issuer.publicKey) }.isSuccess
    }

    private fun checkCurrent(certificate: X509Certificate, now: Date) {
        runCatching { certificate.checkValidity(now) }.onFailure {
            throw AuthException("certificate outside its validity: ${certificate.subjectX500Principal.name}")
        }
    }

    private fun checkKey(certificate: X509Certificate) {
        val key = certificate.publicKey as? RSAPublicKey ?: throw AuthException("certificate key is not RSA")
        if (key.modulus.bitLength() < MIN_RSA_BITS) throw AuthException("certificate key is too small")
    }

    private fun parseCertificate(der: ByteArray): X509Certificate =
        try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        } catch (e: Exception) {
            throw AuthException("unreadable certificate: ${e.message}")
        }

    class AuthException(message: String) : Exception(message)

    /** Just enough protobuf to read an AuthResponse: varints and length-delimited fields. */
    private class ProtoReader(private val bytes: ByteArray) {
        private var index = 0

        fun forEachField(block: (field: Int, value: Any) -> Unit) {
            try {
                while (index < bytes.size) {
                    val key = varint().toInt()
                    val field = key ushr 3
                    when (key and 0x7) {
                        0 -> block(field, varint())
                        2 -> {
                            val length = varint().toInt()
                            if (length < 0 || index + length > bytes.size) throw AuthException("truncated field")
                            block(field, bytes.copyOfRange(index, index + length))
                            index += length
                        }
                        1 -> index += 8
                        5 -> index += 4
                        else -> throw AuthException("malformed authentication message")
                    }
                }
            } catch (e: IOException) {
                throw AuthException("malformed authentication message")
            }
        }

        private fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (index >= bytes.size) throw AuthException("truncated varint")
                val b = bytes[index++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw AuthException("varint too long")
            }
        }
    }
}
