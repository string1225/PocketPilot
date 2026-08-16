package com.string1225.pocketpilot.sshj;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.hierynomus.sshj.signature.SignatureEdDSA;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.Ed25519KeyFactory;
import net.schmizz.sshj.signature.Signature;
import org.junit.Test;

public class PocketPilotEd25519Test {
    @Test
    public void patchedSshjEd25519RoundTripDoesNotRegisterProvider() throws Exception {
        final List<String> providersBefore = providerNames();
        final PrivateKey privateKey = Ed25519KeyFactory.getPrivateKey(hex(PRIVATE_KEY_SEED));
        final PublicKey publicKey = Ed25519KeyFactory.getPublicKey(hex(PUBLIC_KEY));
        final byte[] message = "pocketpilot-ed25519-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        final Signature signer = new SignatureEdDSA.Factory().create();
        signer.initSign(privateKey);
        signer.update(message);
        final byte[] rawSignature = signer.sign();
        final byte[] sshSignature = new Buffer.PlainBuffer()
                .putString("ssh-ed25519")
                .putBytes(rawSignature)
                .getCompactData();

        final Signature verifier = new SignatureEdDSA.Factory().create();
        verifier.initVerify(publicKey);
        verifier.update(message);

        assertTrue(verifier.verify(sshSignature));
        assertArrayEquals(providersBefore.toArray(), providerNames().toArray());
    }

    private static List<String> providerNames() {
        return Arrays.stream(Security.getProviders())
                .map(java.security.Provider::getName)
                .collect(Collectors.toList());
    }

    private static byte[] hex(final String value) {
        final byte[] output = new byte[value.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return output;
    }

    private static final String PRIVATE_KEY_SEED =
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    private static final String PUBLIC_KEY =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
}
