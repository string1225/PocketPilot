package com.string1225.pocketpilot.integrations.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.Security
import com.hierynomus.sshj.signature.SignatureEdDSA
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Ed25519KeyFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshjAndroidConfigTest {
    @Test
    fun patchedSshjEd25519WorksWithoutChangingProviders() {
        val providersBefore = Security.getProviders().map { it.name }
        val privateKey = Ed25519KeyFactory.getPrivateKey(hex(ED25519_PRIVATE_KEY_SEED))
        val publicKey = Ed25519KeyFactory.getPublicKey(hex(ED25519_PUBLIC_KEY))
        val message = "pocketpilot-provider-probe".toByteArray()
        val signer = SignatureEdDSA.Factory().create()
        signer.initSign(privateKey)
        signer.update(message)
        val sshSignature = Buffer.PlainBuffer()
            .putString("ssh-ed25519")
            .putBytes(signer.sign())
            .compactData
        val verifier = SignatureEdDSA.Factory().create()
        verifier.initVerify(publicKey)
        verifier.update(message)

        assertTrue(verifier.verify(sshSignature))
        assertEquals(providersBefore, Security.getProviders().map { it.name })
    }

    @Test
    fun configuredKeyExchangesMatchAndroidProviderCapabilities() {
        val providersBefore = Security.getProviders().map { it.name }
        val x25519Available = SshjClientConfigFactory.isX25519AvailableToSshj()
        val ecdhAvailable = SshjClientConfigFactory.isEcdhAvailableToSshj()
        val names = SshjClientConfigFactory.create()
            .keyExchangeFactories
            .map { it.name }

        assertEquals(x25519Available, names.any { it.startsWith("curve25519-") })
        assertEquals(ecdhAvailable, names.any { it.startsWith("ecdh-sha2-") })
        assertFalse(names.contains("diffie-hellman-group1-sha1"))
        assertFalse(names.contains("diffie-hellman-group14-sha1"))
        assertTrue(names.contains("diffie-hellman-group-exchange-sha256"))
        assertTrue(names.contains("diffie-hellman-group14-sha256"))
        assertTrue(names.contains("diffie-hellman-group16-sha512"))
        assertTrue(names.contains("diffie-hellman-group18-sha512"))
        assertTrue(names.any { it != "ext-info-c" })
        assertEquals(32, SecurityUtils.getMessageDigest("SHA-256").digest("probe".toByteArray()).size)
        assertEquals(null, SecurityUtils.getSecurityProvider())
        assertEquals(providersBefore, Security.getProviders().map { it.name })
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val ED25519_PRIVATE_KEY_SEED =
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
        const val ED25519_PUBLIC_KEY =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    }
}
