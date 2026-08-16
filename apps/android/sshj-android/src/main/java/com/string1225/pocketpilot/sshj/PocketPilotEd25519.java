/*
 * Copyright (c) 2026 String Shi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.string1225.pocketpilot.sshj;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Signature;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Provides Ed25519 primitives from an unregistered, app-bundled provider instance. */
public final class PocketPilotEd25519 {
    private static final String ALGORITHM = "Ed25519";
    private static final Provider PROVIDER = new BouncyCastleProvider();

    private PocketPilotEd25519() {
    }

    public static KeyFactory newKeyFactory() throws NoSuchAlgorithmException {
        return KeyFactory.getInstance(ALGORITHM, PROVIDER);
    }

    public static Signature newSignature() throws NoSuchAlgorithmException {
        return Signature.getInstance(ALGORITHM, PROVIDER);
    }
}
