package com.yuki.duocruise.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/** Upbeater から受け取る版の決まり(hub/src/manifest.ts の verifyManifest・evaluateManifest と同じ)と、まとめの取り出し。 */
public class UpbeaterTest {
    static final String APP = "11111111-2222-3333-4444-555555555555";
    static final long NOW = 1_800_000_000_000L;
    KeyPair key;
    Map<Integer, byte[]> trusted;

    @Before public void setUp() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] enc = key.getPublic().getEncoded();
        trusted = new HashMap<>();
        trusted.put(1, Arrays.copyOfRange(enc, enc.length - 32, enc.length));
    }

    JSONObject body(long seq) throws Exception {
        return new JSONObject().put("v", 1).put("app_id", APP).put("os", "android").put("seq", seq).put("commit_sha", "abc")
                .put("release_id", "r").put("sha256", "AB".repeat(32)).put("size", 1234)
                .put("url", Upbeater.HUB + "/v1/artifacts/00000000-0000-0000-0000-000000000000?t=x")
                .put("issued_at", NOW - 1000).put("expires_at", NOW + 600_000).put("key_generation", 1);
    }

    JSONObject sign(JSONObject b, int gen, KeyPair k) throws Exception {
        byte[] payload = b.toString().getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k.getPrivate());
        s.update(payload);
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        return new JSONObject().put("payload", e.encodeToString(payload)).put("signature", e.encodeToString(s.sign())).put("key_generation", gen);
    }

    void rejects(JSONObject signed, long current, String why) {
        try {
            Upbeater.accept(signed, trusted, Collections.<Integer>emptySet(), APP, current, NOW);
            fail("断るはず: " + why);
        } catch (Exception e) {
            assertTrue(why + " → " + e.getMessage(), e.getMessage().contains(why));
        }
    }

    @Test public void acceptsNewerRelease() throws Exception {
        Upbeater.Offer o = Upbeater.accept(sign(body(5), 1, key), trusted, Collections.<Integer>emptySet(), APP, 4, NOW);
        assertEquals(5, o.seq);
        assertEquals("ab".repeat(32), o.sha256);
        assertEquals(1234, o.size);
        assertFalse(o.rollback);
    }

    @Test public void rejectsBadSignaturesAndKeys() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        rejects(sign(body(5), 1, other), 0, "署名が合いません");
        rejects(sign(body(5), 2, key), 0, "知らない鍵");
        JSONObject s = sign(body(5), 1, key);
        Set<Integer> revoked = new HashSet<>(Collections.singleton(1));
        try { Upbeater.accept(s, trusted, revoked, APP, 0, NOW); fail(); } catch (Exception e) { assertTrue(e.getMessage().contains("失効")); }
        // 中身をすり替えると通らない
        JSONObject t = sign(body(5), 1, key);
        byte[] p = Base64.getUrlDecoder().decode(t.getString("payload"));
        String swapped = new String(p, StandardCharsets.UTF_8).replace("\"seq\":5", "\"seq\":9");
        t.put("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(swapped.getBytes(StandardCharsets.UTF_8)));
        rejects(t, 0, "署名が合いません");
        // 外側の世代と中の世代が違うものは断る
        rejects(sign(body(5).put("key_generation", 2), 1, key), 0, "形が違います");
    }

    @Test public void rejectsWrongTarget() throws Exception {
        rejects(sign(body(5).put("app_id", "other"), 1, key), 0, "別のアプリ");
        rejects(sign(body(5).put("os", "ios"), 1, key), 0, "別の OS");
        rejects(sign(body(5).put("expires_at", NOW - 1), 1, key), 0, "期限");
        rejects(sign(body(5).put("url", "https://evil.example/v1/artifacts/x"), 1, key), 0, "Upbeater ではありません");
        rejects(sign(body(5).put("size", 0), 1, key), 0, "大きさ");
        rejects(sign(body(5).put("sha256", "zz"), 1, key), 0, "SHA-256");
    }

    @Test public void rollbackNeedsSignedInstruction() throws Exception {
        rejects(sign(body(5), 1, key), 5, "同じ版");
        rejects(sign(body(3), 1, key), 5, "巻き戻しの指示の無い古い版");
        rejects(sign(body(3).put("rollback", new JSONObject().put("to_seq", 2)), 1, key), 5, "巻き戻しの指示の無い古い版");
        Upbeater.Offer o = Upbeater.accept(sign(body(3).put("rollback", new JSONObject().put("to_seq", 3)), 1, key), trusted, Collections.<Integer>emptySet(), APP, 5, NOW);
        assertTrue(o.rollback);
        assertEquals(3, o.seq);
    }

    /**
     * Upbeater の Hub と同じ作り方(hub/scripts/signing-key.mjs の鍵・WebCrypto の Ed25519・base64url)で署名した値。
     * 使い捨ての鍵なので、リポジトリに置いてよい。Hub の署名を Java の確かめで通せることを確かめる。
     */
    static final String HUB_PUB = "_d3Iom_iZpF1NQD24bb_WMwVrSiFiCtZHzfLhLgZ0tQ";
    static final String HUB_SIGNED = "{\"payload\":\"eyJ2IjoxLCJhcHBfaWQiOiIxMTExMTExMS0yMjIyLTMzMzMtNDQ0NC01NTU1NTU1NTU1NTUiLCJvcyI6ImFuZHJvaWQiLCJzZXEiOjcsImNvbW1pdF9zaGEiOiJhYmMiLCJyZWxlYXNlX2lkIjoiciIsInNoYTI1NiI6ImFiYWJhYmFiYWJhYmFiYWJhYmFiYWJhYmFiYWJhYmFiYWJhYmFiYWJhYmFiYWJhYmFiYWJhYmFiYWJhYmFiYWIiLCJzaXplIjoxMjM0LCJ1cmwiOiJodHRwczovL3VwYmVhdGVyLWh1Yi50d2lsaWdodC1zdGFyLTlkOWQud29ya2Vycy5kZXYvdjEvYXJ0aWZhY3RzLzAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMD90PXgiLCJpc3N1ZWRfYXQiOjEsImV4cGlyZXNfYXQiOjQxMDI0NDQ4MDAwMDAsImtleV9nZW5lcmF0aW9uIjoxfQ\",\"signature\":\"ggoIpZl43_asjcGi3YdZ-lsU_hz4QTKxzWVwpoUKlYRBQ2Gcp-6tI8c8WrxAkrNsBXuu_OEWoGr_wtrdd-dSDg\",\"key_generation\":1}";

    @Test public void acceptsHubSignature() throws Exception {
        Map<Integer, byte[]> hub = new HashMap<>();
        hub.put(1, Upbeater.b64url(HUB_PUB));
        Upbeater.Offer o = Upbeater.accept(new JSONObject(HUB_SIGNED), hub, Collections.<Integer>emptySet(), APP, 6, NOW);
        assertEquals(7, o.seq);
        try { Upbeater.accept(new JSONObject(HUB_SIGNED), trusted, Collections.<Integer>emptySet(), APP, 6, NOW); fail(); }
        catch (Exception e) { assertTrue(e.getMessage().contains("署名が合いません")); }
    }

    File zip(String... namesAndContents) throws Exception {
        File z = Files.createTempFile("bundle", ".zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(z))) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return z;
    }

    @Test public void unzipsOnlyFlatSafeNames() throws Exception {
        File dir = new File(Files.createTempDirectory("b").toFile(), "current");
        Upbeater.unzip(zip("manifest.json", "{}", "duo-main.apk", "A", "prev-duo-main.apk", "B"), dir, 1000);
        assertEquals("A", new String(Files.readAllBytes(new File(dir, "duo-main.apk").toPath()), StandardCharsets.UTF_8));
        for (String bad : new String[]{"../evil.apk", "sub/x.apk", "/abs.apk", ".hidden"}) {
            try {
                Upbeater.unzip(zip("manifest.json", "{}", bad, "x"), dir, 1000);
                fail("断るはず: " + bad);
            } catch (Exception e) {
                assertFalse("失敗したら何も残さない", dir.exists());
            }
        }
        try { Upbeater.unzip(zip("duo-main.apk", "A"), dir, 1000); fail(); } catch (Exception e) { assertTrue(e.getMessage().contains("manifest.json")); }
        try { Upbeater.unzip(zip("manifest.json", "{}", "big.apk", "x".repeat(5000)), dir, 1000); fail(); } catch (Exception e) { assertTrue(e.getMessage().contains("大きすぎ")); }
    }
}
