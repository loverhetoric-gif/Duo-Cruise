package com.yuki.duocruise.updater;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Ed25519 の署名の確かめ(RFC 8032 の 5.1.7)。Android の版に頼らないよう、Java だけで書く。
 * Upbeater が配布のマニフェストに付ける署名を、埋め込んだ公開鍵で確かめるために使う。
 * 速さは要らない(15 分に 1 回、1 つ確かめるだけ)ため、分かりやすさを優先して BigInteger で書く。
 */
final class Ed25519 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger TWO = BigInteger.valueOf(2);
    /** 基点 B(y = 4/5、x は正の偶数側)。 */
    private static final BigInteger[] B;
    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger bx = recoverX(by, 0);
        B = new BigInteger[]{bx, by, BigInteger.ONE, bx.multiply(by).mod(P)};
    }

    private Ed25519() {}

    /** pub(32 バイト)・sig(64 バイト)で msg を確かめる。形が違う・確かめられないときは false。 */
    static boolean verify(byte[] pub, byte[] msg, byte[] sig) {
        try {
            if (pub == null || sig == null || msg == null || pub.length != 32 || sig.length != 64) return false;
            BigInteger[] a = decode(pub);
            byte[] rEnc = Arrays.copyOfRange(sig, 0, 32);
            BigInteger[] r = decode(rEnc);
            if (a == null || r == null) return false;
            BigInteger s = le(Arrays.copyOfRange(sig, 32, 64));
            if (s.compareTo(L) >= 0) return false;
            MessageDigest h = MessageDigest.getInstance("SHA-512");
            h.update(rEnc);
            h.update(pub);
            h.update(msg);
            BigInteger k = le(h.digest()).mod(L);
            BigInteger[] sb = mul(s, B);
            BigInteger[] rka = add(r, mul(k, a));
            return equal(sb, rka);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 体の計算(拡張座標 X:Y:Z:T、x = X/Z、y = Y/Z、x*y = T/Z) ----------

    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = TWO.multiply(p[3]).multiply(q[3]).multiply(D).mod(P);
        BigInteger dd = TWO.multiply(p[2]).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = dd.subtract(c), g = dd.add(c), hh = b.add(a);
        return new BigInteger[]{e.multiply(f).mod(P), g.multiply(hh).mod(P), f.multiply(g).mod(P), e.multiply(hh).mod(P)};
    }

    private static BigInteger[] mul(BigInteger s, BigInteger[] p) {
        BigInteger[] q = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = s.bitLength() - 1; i >= 0; i--) {
            q = add(q, q);
            if (s.testBit(i)) q = add(q, p);
        }
        return q;
    }

    private static boolean equal(BigInteger[] p, BigInteger[] q) {
        return p[0].multiply(q[2]).subtract(q[0].multiply(p[2])).mod(P).signum() == 0
                && p[1].multiply(q[2]).subtract(q[1].multiply(p[2])).mod(P).signum() == 0;
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        if (y.compareTo(P) >= 0) return null;
        BigInteger x2 = y.multiply(y).subtract(BigInteger.ONE).multiply(D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(P)).mod(P);
        if (x2.signum() == 0) return sign == 0 ? BigInteger.ZERO : null;
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P);
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null;
        if ((x.testBit(0) ? 1 : 0) != sign) x = P.subtract(x);
        return x;
    }

    /** 32 バイトの点を読む。点でなければ null。 */
    private static BigInteger[] decode(byte[] s) {
        byte[] b = s.clone();
        int sign = (b[31] >> 7) & 1;
        b[31] &= 0x7f;
        BigInteger y = le(b);
        BigInteger x = recoverX(y, sign);
        if (x == null) return null;
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    /** 小さい桁が先の並び(リトルエンディアン)を数にする。 */
    private static BigInteger le(byte[] b) {
        byte[] r = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) r[b.length - i] = b[i];
        return new BigInteger(r);
    }
}
