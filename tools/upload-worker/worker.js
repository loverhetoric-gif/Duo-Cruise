// 走行の記録(JSONL・動画クリップ)の受け口。Cloudflare Worker + R2。
// 機器(Duo Cruise)が SIM から PUT /<key> で送る。Authorization: Bearer <TOKEN> が一致したら R2 に保存。
//
// 設定:
//   R2 バインディング  BUCKET  → バケット duo-trace
//   秘密(環境変数)     TOKEN   → 機器と同じトークン(wrangler secret / ダッシュボードで設定)
//
// 機器からの保存:   PUT  /20261031/20261031-120000.jsonl
//                  PUT  /20261031/123456.mp4
// 一覧(ブラウザ):  GET  /?token=<TOKEN>          … キーの一覧(JSON)
// 取り出し:         GET  /<key>?token=<TOKEN>     … ファイルを返す
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const key = decodeURIComponent(url.pathname.slice(1)); // 先頭の / を外す

    const bearer = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
    const query = url.searchParams.get("token") || "";
    const ok = env.TOKEN && (bearer === env.TOKEN || query === env.TOKEN);
    if (!ok) return new Response("forbidden", { status: 403 });

    if (request.method === "PUT") {
      if (!key) return new Response("no key", { status: 400 });
      await env.BUCKET.put(key, request.body, {
        httpMetadata: { contentType: request.headers.get("Content-Type") || "application/octet-stream" },
      });
      return new Response("ok", { status: 201 });
    }

    if (request.method === "GET") {
      if (!key) {
        // 一覧(新しい順ではなくキー順)。ブラウザで中身を確認する用
        const list = await env.BUCKET.list({ limit: 1000 });
        const items = list.objects.map((o) => ({ key: o.key, size: o.size, uploaded: o.uploaded }));
        return Response.json({ count: items.length, items });
      }
      const obj = await env.BUCKET.get(key);
      if (!obj) return new Response("not found", { status: 404 });
      const headers = new Headers();
      obj.writeHttpMetadata(headers);
      headers.set("etag", obj.httpEtag);
      return new Response(obj.body, { headers });
    }

    return new Response("method not allowed", { status: 405 });
  },
};
