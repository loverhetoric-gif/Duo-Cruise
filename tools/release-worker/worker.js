// Duo Cruise の配信口(GitHub のリリースの代わり)。Cloudflare Worker + R2。読むだけ。
//
// GitHub 停止中(2026-11-09〜)に、車の Duo Cruise Market(更新アプリ)が見に行く先。
// 更新アプリは GitHub のリリース API と同じ形の JSON を読むので、同じ形で返す:
//   GET /releases/latest          … 正式版のうち一番新しいもの
//   GET /releases?per_page=50     … テスト版も含めた一覧(新しい順)
//   GET /dl/<タグ>/<ファイル>      … APK・manifest.json
//
// 中身(R2 バケット duo-releases):
//   releases.json           … 一覧。GitHub の形(tag_name・prerelease・published_at・assets[])の配列。新しい順
//   <タグ>/<ファイル>        … 各版のファイル
// 書き込みは Mac の tools/publish-release.sh(wrangler のログイン)だけ。この Worker には書き込む口が無い。
// APK は manifest.json の sha256 と署名で端末が確かめるので、読むのに合言葉は付けない(GitHub の公開リリースと同じ)。

export default {
  async fetch(request, env) {
    if (request.method !== "GET" && request.method !== "HEAD") return new Response("method not allowed", { status: 405 });
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/" || path === "/health") return Response.json({ name: "duo-releases", ok: true });

    if (path === "/releases" || path === "/releases/latest") {
      const obj = await env.BUCKET.get("releases.json");
      const list = obj ? await obj.json() : [];
      const headers = { "Cache-Control": "no-store" };
      if (path === "/releases") {
        const n = Math.min(Math.max(parseInt(url.searchParams.get("per_page") || "30", 10) || 30, 1), 100);
        return Response.json(list.slice(0, n).map((r) => withUrls(r, url.origin)), { headers });
      }
      const latest = list.find((r) => !r.prerelease && !r.draft);
      if (!latest) return Response.json({ message: "Not Found" }, { status: 404, headers });
      return Response.json(withUrls(latest, url.origin), { headers });
    }

    if (path.startsWith("/dl/")) {
      const key = decodeURIComponent(path.slice(4));
      if (!key || key.includes("..") || key === "releases.json") return new Response("not found", { status: 404 });
      const obj = await env.BUCKET.get(key);
      if (!obj) return new Response("not found", { status: 404 });
      const headers = new Headers();
      obj.writeHttpMetadata(headers);
      headers.set("ETag", obj.httpEtag);
      headers.set("Content-Length", String(obj.size));
      return new Response(request.method === "HEAD" ? null : obj.body, { headers });
    }

    return new Response("not found", { status: 404 });
  },
};

// 置き場所の名前から、この Worker のダウンロード URL を付ける(ドメインが変わっても一覧を書き直さなくてよい)
function withUrls(r, origin) {
  return {
    ...r,
    assets: (r.assets || []).map((a) => ({
      ...a,
      browser_download_url: `${origin}/dl/${encodeURIComponent(r.tag_name)}/${encodeURIComponent(a.name)}`,
    })),
  };
}
