package com.yuki.duocruise.updater;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

/** Ed25519 の確かめ:RFC 8032 の試験の値と、Java に組み込みの Ed25519 で作った署名で確かめる。 */
public class Ed25519Test {
    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    // RFC 8032 7.1 の TEST 1(空のメッセージ)・TEST 2(1 バイト)・TEST 3(2 バイト)
    static final String[][] RFC = {
        {"d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
         "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"},
        {"3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
         "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"},
        {"fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
         "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"},
    };

    @Test public void rfc8032Vectors() {
        for (String[] v : RFC) assertTrue(Ed25519.verify(hex(v[0]), hex(v[1]), hex(v[2])));
    }

    @Test public void rejectsTampering() {
        String[] v = RFC[1];
        byte[] pub = hex(v[0]), msg = hex(v[1]), sig = hex(v[2]);
        byte[] m2 = msg.clone(); m2[0] ^= 1;
        assertFalse("中身を変えたら通らない", Ed25519.verify(pub, m2, sig));
        for (int i : new int[]{0, 31, 32, 63}) {
            byte[] s2 = sig.clone(); s2[i] ^= 1;
            assertFalse("署名を変えたら通らない " + i, Ed25519.verify(pub, msg, s2));
        }
        byte[] p2 = pub.clone(); p2[5] ^= 1;
        assertFalse("別の鍵では通らない", Ed25519.verify(p2, msg, sig));
        assertFalse(Ed25519.verify(Arrays.copyOf(pub, 31), msg, sig));
        assertFalse(Ed25519.verify(pub, msg, Arrays.copyOf(sig, 63)));
        assertFalse(Ed25519.verify(null, msg, sig));
    }

    @Test public void rejectsNonCanonicalS() {
        // S に群の位数 L を足した署名は、数としては同じ点になるが受け付けない(RFC 8032 の決まり)
        String[] v = RFC[0];
        byte[] sig = hex(v[2]);
        java.math.BigInteger l = java.math.BigInteger.ONE.shiftLeft(252).add(new java.math.BigInteger("27742317777372353535851937790883648493"));
        byte[] sLe = Arrays.copyOfRange(sig, 32, 64);
        byte[] be = new byte[33];
        for (int i = 0; i < 32; i++) be[32 - i] = sLe[i];
        java.math.BigInteger s = new java.math.BigInteger(be).add(l);
        byte[] sb = s.toByteArray();
        byte[] out = sig.clone();
        Arrays.fill(out, 32, 64, (byte) 0);
        for (int i = 0; i < sb.length && i < 32; i++) out[32 + i] = sb[sb.length - 1 - i];
        assertFalse(Ed25519.verify(hex(v[0]), hex(v[1]), out));
    }

    /** Java 組み込みの Ed25519(JDK 15 以降)で署名したものが、すべて通ることを確かめる。 */
    @Test public void matchesJdkSignatures() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        Random r = new Random(7);
        for (int n = 0; n < 20; n++) {
            KeyPair kp = g.generateKeyPair();
            byte[] enc = kp.getPublic().getEncoded();          // X.509 の形(先頭 12 バイトのあとに 32 バイト)
            byte[] pub = Arrays.copyOfRange(enc, enc.length - 32, enc.length);
            byte[] msg = new byte[r.nextInt(300)];
            r.nextBytes(msg);
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(kp.getPrivate());
            s.update(msg);
            byte[] sig = s.sign();
            assertTrue("組み込みの署名 " + n, Ed25519.verify(pub, msg, sig));
            if (msg.length > 0) { msg[0] ^= 0x40; assertFalse(Ed25519.verify(pub, msg, sig)); }
        }
    }
}
