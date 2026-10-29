package com.github.serezhka.airplay.protocol.pairing;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import net.i2p.crypto.eddsa.KeyPairGenerator;

import java.security.KeyPair;

/**
 * Receiver Ed25519 identity for one AirPlay process (legacy FairPlay pair-setup / pair-verify).
 * The public key is what mDNS advertises as {@code pk} and what {@code /pair-setup} returns.
 * <p>
 * Not the HKP controller identity saved by the cast client ({@code pairing_id} + seed) —
 * that lives in the client credential store.
 */
public final class PairingIdentity {

    private final KeyPair keyPair;

    private PairingIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static PairingIdentity generate() {
        return new PairingIdentity(new KeyPairGenerator().generateKeyPair());
    }

    /** Rebuilds a saved identity, so the receiver keeps the same {@code pk} across restarts. */
    public static PairingIdentity fromSeed(byte[] seed) {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKeySpec privateSpec = new EdDSAPrivateKeySpec(seed, spec);
        return new PairingIdentity(new KeyPair(
                new EdDSAPublicKey(new EdDSAPublicKeySpec(privateSpec.getA(), spec)),
                new EdDSAPrivateKey(privateSpec)));
    }

    public byte[] seed() {
        return ((EdDSAPrivateKey) keyPair.getPrivate()).getSeed();
    }

    public byte[] publicKey() {
        return ((EdDSAPublicKey) keyPair.getPublic()).getAbyte();
    }

    public String publicKeyHex() {
        StringBuilder sb = new StringBuilder();
        for (byte b : publicKey()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    KeyPair keyPair() {
        return keyPair;
    }
}
