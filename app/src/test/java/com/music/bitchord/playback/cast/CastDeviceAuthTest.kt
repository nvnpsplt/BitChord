package com.music.bitchord.playback.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/**
 * Device authentication against a test PKI made with OpenSSL: a root, an
 * intermediate, a "device" certificate, and a short-lived TLS certificate the
 * device signed together with the sender's nonce.
 */
class CastDeviceAuthTest {

    private fun cert(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private val roots = listOf(cert(ROOT))

    /** Inside the TLS certificate's two-day window, whenever the test runs. */
    private val now = Date(cert(PEER).notBefore.time + 3_600_000L)

    private fun response(
        signature: ByteArray = SIGNATURE,
        device: ByteArray = DEVICE,
        intermediates: List<ByteArray> = listOf(ICA),
        nonce: ByteArray = NONCE,
    ) = CastDeviceAuth.Response(signature, device, intermediates, nonce, sha256 = true)

    @Test
    fun genuineDevice_verifies() {
        CastDeviceAuth.verify(response(), PEER, NONCE, roots, now)
    }

    @Test
    fun signatureOverAnotherTlsCertificate_isRejected() {
        // A relayed answer: signed for one connection, presented on another.
        assertThrows(CastDeviceAuth.AuthException::class.java) {
            CastDeviceAuth.verify(response(), PEER_LONG, NONCE, roots, Date(cert(PEER_LONG).notBefore.time + 3_600_000L))
        }
    }

    @Test
    fun wrongNonce_isRejected() {
        assertThrows(CastDeviceAuth.AuthException::class.java) {
            CastDeviceAuth.verify(response(), PEER, ByteArray(16), roots, now)
        }
    }

    @Test
    fun tamperedSignature_isRejected() {
        val bad = SIGNATURE.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertThrows(CastDeviceAuth.AuthException::class.java) {
            CastDeviceAuth.verify(response(signature = bad), PEER, NONCE, roots, now)
        }
    }

    @Test
    fun missingIntermediate_isRejected() {
        assertThrows(CastDeviceAuth.AuthException::class.java) {
            CastDeviceAuth.verify(response(intermediates = emptyList()), PEER, NONCE, roots, now)
        }
    }

    @Test
    fun chainToAnUntrustedRoot_isRejected() {
        // The real Cast roots did not issue the test chain.
        assertThrows(CastDeviceAuth.AuthException::class.java) {
            CastDeviceAuth.verify(response(), PEER, NONCE, now = now)
        }
    }

    @Test
    fun longLivedTlsCertificate_isRejected() {
        assertThrows(CastDeviceAuth.AuthException::class.java) {
            CastDeviceAuth.verify(response(), PEER_LONG, NONCE, roots, Date(cert(PEER_LONG).notBefore.time + 3_600_000L))
        }
    }

    @Test
    fun challenge_carriesTheNonce() {
        val nonce = ByteArray(16) { it.toByte() }
        val message = CastDeviceAuth.challenge(nonce)
        // DeviceAuthMessage.challenge (field 1, length-delimited) wrapping
        // AuthChallenge { 1: 1, 2: nonce, 3: 1 }.
        assertEquals(0x0A, message[0].toInt())
        val inner = message.copyOfRange(2, message.size)
        assertArrayEquals(byteArrayOf(0x08, 0x01, 0x12, 16), inner.copyOfRange(0, 4))
        assertArrayEquals(nonce, inner.copyOfRange(4, 20))
        assertArrayEquals(byteArrayOf(0x18, 0x01), inner.copyOfRange(20, 22))
    }

    @Test
    fun parseResponse_readsTheWireFormat() {
        fun field(number: Int, bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write((number shl 3) or 2)
            var length = bytes.size
            while (length >= 0x80) {
                out.write((length and 0x7F) or 0x80)
                length = length ushr 7
            }
            out.write(length)
            out.write(bytes)
            return out.toByteArray()
        }
        val authResponse = field(1, SIGNATURE) + field(2, DEVICE) + field(3, ICA) + field(5, NONCE) +
            byteArrayOf(0x30, 0x01) // hash_algorithm = SHA256
        val parsed = CastDeviceAuth.parseResponse(field(2, authResponse))
        assertArrayEquals(SIGNATURE, parsed.signature)
        assertArrayEquals(DEVICE, parsed.deviceCertificate)
        assertEquals(1, parsed.intermediates.size)
        assertArrayEquals(NONCE, parsed.senderNonce)
        assertTrue(parsed.sha256)
        CastDeviceAuth.verify(parsed, PEER, NONCE, roots, now)
    }

    @Test
    fun errorReply_isRejected() {
        assertThrows(CastDeviceAuth.AuthException::class.java) {
            CastDeviceAuth.parseResponse(byteArrayOf(0x1A, 0x02, 0x08, 0x00))
        }
    }

    private companion object {
        fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

        val ROOT: ByteArray = decode(
            "MIIDIzCCAgugAwIBAgIUcc5xOeTZwnwmi814eKX2F9RrnlswDQYJKoZIhvcNAQELBQAwGTEXMBUGA1UEAwwOVGVzdCBDYXN0IFJv" +
            "b3QwHhcNMjYwOTI2MDcwNTU3WhcNMzYwOTIzMDcwNTU3WjAZMRcwFQYDVQQDDA5UZXN0IENhc3QgUm9vdDCCASIwDQYJKoZIhvcN" +
            "AQEBBQADggEPADCCAQoCggEBALjQSMywy8m8UaL5cLdEhDb4SfdcWKBuTCgM8lA1kKo5DS4qIxX64OG4oY/Q9jhXbquRuKhSHb+y" +
            "xwo+nNHuUF6zeYKCLBTOVMbKc5/2h2dnjwdvPRIWjlPn92YYyABGSMFWY8veAcS2LeJcyQ791+PPvRFKsQF4L32lj2cuAbeVzPN8" +
            "NDdkXoq/A83Sh9rSS4EkemljUf7HysHtRwzYao2H92YCmWLfTxHrW1yIEIuXphJ9GwWiySxtTmx3sABhwcvMA8UuBiCzou/dUzvR" +
            "l6tgqSQAMWQ9RtvbYTEi3XF9UgmldMv5NnFYYwaHlQ9r+l/o8vWXboJg6nNX9s947YECAwEAAaNjMGEwHQYDVR0OBBYEFBAh6Vfx" +
            "rTKfkZ7tOFYRpszUc/oRMB8GA1UdIwQYMBaAFBAh6VfxrTKfkZ7tOFYRpszUc/oRMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/" +
            "BAQDAgEGMA0GCSqGSIb3DQEBCwUAA4IBAQADrkGdkHlI1m8Mnl/r/XZmkMG5MWW+M5rnblHm7JwtGvzX/6qTFAbnlv+2dpYoYSH/" +
            "GnZSL6dr2WZG3mCFuLFVw16qKV2+5XMTxqAJ2FxXLbAQFPf43p2+dskiDJUmMkUWkklxkWvPy3CV3WOJgLR5e1wdWma6ULo9dAS8" +
            "mbagqT2A1l0rgCY2ysKnfgsbdJ/wVWHV4HnjDhHLeOPzM+mUTCBI0222hkgHbGZ9WLTL9jigc08OwXr8zhzJk3EcZVm5VedyOvwC" +
            "jpy91LD56jPWnyOvxVZRmvMJmfbBHntLhxnvg3SIHovo2a0jV+Mo92n79xxAEqqRJ9vjS4OthwTN",
        )

        val ICA: ByteArray = decode(
            "MIIDIjCCAgqgAwIBAgIUe75hkOyUBp0ej1P0PgA+eK3AF6IwDQYJKoZIhvcNAQELBQAwGTEXMBUGA1UEAwwOVGVzdCBDYXN0IFJv" +
            "b3QwHhcNMjYwOTI2MDcwNTU3WhcNMzYwOTIzMDcwNTU3WjAYMRYwFAYDVQQDDA1UZXN0IENhc3QgSUNBMIIBIjANBgkqhkiG9w0B" +
            "AQEFAAOCAQ8AMIIBCgKCAQEAwnZMVBiZVu92dHXI9eU9a5+vFEDZgFZZm7LkBNb0ZCsc068UQUr5AAuZfqdi9zof0qfxztMDHYF1" +
            "rWFjW8OAeLQrcZczhrq+FsC/36GHprGoSROhxVMbPQZNs3wR1b0AlYzCby1kerYufD/6/SYtmjHbtjkIlZvT6/DFaKarmGK0YbTv" +
            "RmRO4cJGYLClDnioO//lg52NNfHD19zBAeLfPFn0dD2HyfUEAed2O4cm9jUBRiQdV5HH53wsTTKMMP4E4vgVM8btcwMcsaC0DbGj" +
            "59DC6cP43XVXyCiDNUOuRpLVBP+zvHMtgV2gt0REueTcZzNY+K0HxQimHFzUHYC2qwIDAQABo2MwYTAPBgNVHRMBAf8EBTADAQH/" +
            "MA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQUimI9y9GlXuUbMoF8fzym/fM/DzEwHwYDVR0jBBgwFoAUECHpV/GtMp+Rnu04VhGm" +
            "zNRz+hEwDQYJKoZIhvcNAQELBQADggEBAH2/lpUEfpCLzJZApHmgfHwTcDZIOY2u2qPeR0otLr4hbYTDJlSQzEv9IweLy6571V+K" +
            "5KmwaGF1d6PBDdOSdT5GALzr29NdQlRnnSikRXvWO0mg2ShARJ/NTYx75XyfPuC4uWWLYHEpBk3Q/orslWphZ+5YF4g9JiqP5Csp" +
            "CU4LfhO1h8O1c1nNPSc27doxvADTfYHkrW/3YCbJKf1lsbd77LT4vK1XYu5lQtYV3TI7gRETUNnrEi+24kZdS0Ul8YMgzEdigkcQ" +
            "F7w9mu+mn6FYjxFa5iAMZS7ut6YNWsh3ixRBajoQ7ukhLJHEWuhZ45hQ0CqAG2YZcEt5GwoAWE4=",
        )

        val DEVICE: ByteArray = decode(
            "MIIDIDCCAgigAwIBAgIUGPNlkM5mYyZPf6Bh0r+Z/HjqBy4wDQYJKoZIhvcNAQELBQAwGDEWMBQGA1UEAwwNVGVzdCBDYXN0IElD" +
            "QTAeFw0yNjA5MjYwNzA1NTdaFw0zNjA5MjMwNzA1NTdaMBoxGDAWBgNVBAMMD1Rlc3QgQ2hyb21lY2FzdDCCASIwDQYJKoZIhvcN" +
            "AQEBBQADggEPADCCAQoCggEBAJS8iYASkaQuqmhHVVbZ4AUV7DhLgwyuqAH1L1Di6AugviBap/ndUgwejXJH3+J2IlohEDxswed7" +
            "j0xUjO7EuHsg4RZtmbIJwDSMBN5zvPprCVXpfWfPNOondhXBVbwpqmd+Z8qkVeg9oIyFTgAPxaFDcSBLxNKHc6mpbV4k+Dqt4jrd" +
            "4oDBzpFJ4Xsil7J9pqMIDeTG0ZbqrDJiC8ua57pzSEf+beNZ1DoEZhx4FiIU6dea34Qih7kW2zS7s6DOVdVYKYSxz0nUXEuvoXb8" +
            "iH58xrg/r2H0GNKECuwkhgdBjjbhOr1YZPtyuJ+FfB1ESdra6UOy+b+TWv93XpvEby0CAwEAAaNgMF4wDAYDVR0TAQH/BAIwADAO" +
            "BgNVHQ8BAf8EBAMCB4AwHQYDVR0OBBYEFESxLTQSoc0KnJDPeHB3dP2qjXnlMB8GA1UdIwQYMBaAFIpiPcvRpV7lGzKBfH88pv3z" +
            "Pw8xMA0GCSqGSIb3DQEBCwUAA4IBAQBVlSsgbApOP77cG9YJpaF+BgRCGpvvCpRwyS0/ukf9mkqKK946oK3tlBh/lVoHrpEgmoFR" +
            "o0VxrJaeAlPYn1DMZtWOl6uvesfqtaMxO8cxozrNCggBSDgqiY8I4eKomPylesFybI68gfcsBseOHwNBWb4AcMwIp3cAiMg9NQkJ" +
            "hWg4TrWNbzQ1L8QT67+Y19A9OtL4o/Oyy5LM27qMGyBD9s96Xf/U4iqdTxgS3Cy4ePJtHWC/991+6ndh+TfbomMpCKCfCQr/VPrx" +
            "NmPR1/6NIqoINRC8wFRKFEbxyLAre2HkOc6JeEwuIDdLYpTJvwPX33miy7gSmYWRHutm9csm",
        )

        val PEER: ByteArray = decode(
            "MIIC/zCCAeegAwIBAgIUS71mFgwuxCZFyjneQ8DDncPDl/QwDQYJKoZIhvcNAQELBQAwDzENMAsGA1UEAwwEcGVlcjAeFw0yNjA5" +
            "MjYwNzA1NThaFw0yNjA5MjgwNzA1NThaMA8xDTALBgNVBAMMBHBlZXIwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC3" +
            "LT1D/C1tgBlcmceOh79muB3ZmX/sQRMOhxJ/ZkKA9DP+fv7hcmT8+YkPvlRbWjkapcTJWYs282uHb67NIchxmWITc9Z/3EyQ0cwJ" +
            "lHT9FWTpRHp4P/kVQl5OX3u6nhOU8wDTJQJpdzC2ckH+9qWhCiWNU1t/tq7U6dWLPtIZo2umvR9RIzudiQ6q9KnmJaCHQVFJCZ0C" +
            "lP2PUJEQPeBRESzTvkCz6G2upQGJAKBLGoA5guGlwSljF7d+HsP2+keAmj+iAVyCdpyZRY8vlYHCoqk7kvn+Gikgz625q6Iql98f" +
            "+xLFCUqfthkW0GSeKd+aHg0KmtlABiEV3vQS/ht5AgMBAAGjUzBRMB0GA1UdDgQWBBR2jUcOmaqGoQnikvp7eet94fViwjAfBgNV" +
            "HSMEGDAWgBR2jUcOmaqGoQnikvp7eet94fViwjAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQBPr6cIbSG8tSrM" +
            "ZPaeJt9dpKO7vU8YC0HK5i+mc6RF4HzOp/KrJ/4Ul9YHB0odyg+Fbdw5NqyDEMuWbMv3gnN2FF9cqJOErXPjEk1Z9sPGY7LL0thI" +
            "cUYmOb3D06Ax23cpMN47Yz5ptLWIOK1XPfYnHgV8cyKVUZL4oSd8iuPxyWD1mDcvGd0Ep8Q5aXxk7elsviwtUqr5mnJSu2msglDb" +
            "mY4yS2IHZJ7A/dlEWN4MxZkEnU31swMRJoJNFTBtGMMdcU5q97Ev70T2x0mu3pSj95uTjc6KXtX8UuhgJeHRXKBdinYizCGFkOkk" +
            "e3vEDlgtUEh8FTR5u4Fy5c4cuVle",
        )

        val PEER_LONG: ByteArray = decode(
            "MIIDBzCCAe+gAwIBAgIUGDyIWMaxOuEK7YpHvRXXbk1EgOkwDQYJKoZIhvcNAQELBQAwEzERMA8GA1UEAwwIcGVlcmxvbmcwHhcN" +
            "MjYwOTI2MDcwNTU5WhcNMjYxMDI2MDcwNTU5WjATMREwDwYDVQQDDAhwZWVybG9uZzCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC" +
            "AQoCggEBAJqT9foqH5pMLcj74XnOLsrRQq3cE16qg/4zZTrrqWGEGXHU/WNhNIyB/2C0JBgRqO1BcepDE1H8taXX3rIna9x2X1H7" +
            "F4dvppyCv0qUG6KBN9wbsmy8rRXEwb8m7y7Iei4YElk/ldLpfbdQdpAg5Mn4Mvud7rf3rMk0KCpjGqRmhJw4s3fXdPtJvn4I1d6a" +
            "km7rc2QaKcct/nwLPjp0f35Q1tDvN87UFgbdX44xYsX0QfwRZ8PPxnYwCOx5N6Ydz0KojYN7WOIdWs81TcWZUS2BekKNoSb5w4J8" +
            "ScIO2iDel9IX532JVP/dU9cLaRBcOim/MlzOKFoRhwGb69l1xS8CAwEAAaNTMFEwHQYDVR0OBBYEFJVoVLh+si14UT7BqNiSXM5n" +
            "ho9IMB8GA1UdIwQYMBaAFJVoVLh+si14UT7BqNiSXM5nho9IMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAIsB" +
            "D7WeYNRE0QN1n67xrD3JJmZSXDuYCUBOt/W11iNn22Hz3Yk3fSph6DQQ63d/NZvVyLofaOg4uWNlWlTSc/siIBvRGVO1U1jj9Ry1" +
            "PCH4P7aqbNKtzyDM7JXq2WAKRF2reXSCkH0hcSWLy4k9Y1WgxqlD0AeL3XHtLfNxcnuQCW6T5OmR4CrZL3iMWCjbWLHDPhNh4dH9" +
            "DhBX+Bnr3P8KoxZ4RuUWPLExsgNpOt7DP+eGwFVXOgYTdCtgHdNlP5uPQXY68tuGLtlQGrfWk0duaaiBFRhOkEtErZAlyeomJZqC" +
            "OdU1NGlXMz1aUp61OVGhpCyISEc5HmHryrkeBHI=",
        )

        val NONCE: ByteArray = decode(
            "eQRXNc07/vnS4TXaI5gB+w==",
        )

        val SIGNATURE: ByteArray = decode(
            "Dg06wBeYQPox8itJDchKx799oSxgjp5L9saMZTHtol9dc/8ZKTX+LP7GrY+mxoXPS3IavaUzHYeBsh+aEWRuJakL1oVthaBHIpYT" +
            "TZ8/ivEWUiDnC1kfYR1DNzLi0XaCWMSzdtpUwDdksXsgxlNa0c/S1lNGRrj396zzD6yvT1/WvkS1GB4+a7o29Y42ofmFuKKlzcmC" +
            "b1HJFBJ4jqQeTKGDfC18ZQXA3awJwi233XVBcj9di33LyRTN5GOFjQdPuag+DkEFf855A9iM3y0PxC8kAzBOlz7uA9Wb0VKXTs4d" +
            "7EVWXiakpdU/zH7EjRXZ79P0Fp/cy76Qr/0mdhAx2A==",
        )
    }
}
