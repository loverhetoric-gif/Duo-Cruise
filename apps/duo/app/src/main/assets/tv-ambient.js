// アンビエント(YouTube の上下の帯を、動画の色の光で埋める)。TvPane が TV 版 YouTube のページへ入れる。
// 呼び方: この中身のあとに "(true)" または "(false)" を付けて実行する(true でオン)。2 回目以降はオン・オフを渡すだけ。
//
// 見え方:
//  - 動画が枠より細く、上下に帯が出ているときだけ光る(16:9 でぴったりのときは何もしない)
//  - 帯の色は、動画を 32×18 に縮めてぼかし、暗く(42%)・少し鮮やかにしたもの
//  - 色は 1 秒 10 回、前の色に 3 割ずつ寄せる(場面が変わっても 0.5 秒ほどでなめらかに移る。ちらつかない)
//  - 光は動画の縁がいちばん明るく、画面の上下の端へ向かって暗くなる(動画から漏れた光に見せる)
//  - 動画の上下の縁は 28px(高さの 8% まで)かけて光に溶かし、境目の線を消す(字幕にかからない幅)
//  - 枠が動く間(マップクローズへの切り替えなど)は毎コマ合わせ直すので、縁がずれない
//  - 一時停止中・操作の直後(TvPane が hold を入れる)は出さない(TV 版の操作の表示を隠さない)
//  - 出す・消すは 0.8 秒かけて。描くのは見えている間だけ(消えている間は 0.25 秒ごとに様子を見るだけ)
(function (on) {
  var A = window.__duoAmb;
  if (A) { A.on = on; return; }
  A = window.__duoAmb = { on: on, hold: 0 };

  var c = document.createElement('canvas');           // 画面に出す層
  c.id = 'duo-amb';
  c.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;pointer-events:none;'
    + 'z-index:2147483646;opacity:0;transition:opacity .8s ease';
  var cx = c.getContext('2d');
  var s = document.createElement('canvas');           // 動画の色(32×18、なめらかに追う)
  s.width = 32; s.height = 18;
  var sx = s.getContext('2d');
  var b = document.createElement('canvas');           // ぼかし・減衰を済ませた光(1 秒 10 回だけ作る)
  var bx = b.getContext('2d');

  var SAMPLE_MS = 100, FOLLOW = 0.3, FADE_PX = 28, FADE_MAX = 0.08;
  var lastSample = 0, primed = false, shown = false, lastKey = '';

  // 動画の中身が実際に映っている四角(要素の中で縦横比を保って収めた位置)
  function picture(v) {
    var r = v.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return null;
    var ar = v.videoWidth / v.videoHeight, w = r.width, h = r.height;
    if (w / h > ar) w = h * ar; else h = w / ar;
    return { x: r.left + (r.width - w) / 2, y: r.top + (r.height - h) / 2, w: w, h: h };
  }

  function wanted(v, W, H) {
    if (!A.on || !v || !v.videoWidth || v.readyState < 2 || v.paused || v.ended || Date.now() < A.hold) return null;
    var g = picture(v);
    if (!g || g.w < W * 0.9 || g.h > H - 12) return null; // 横いっぱい・上下に帯があるときだけ
    return g;
  }

  function sample(v, g, W, H) {
    sx.globalAlpha = primed ? FOLLOW : 1;
    sx.drawImage(v, 0, 0, 32, 18);
    sx.globalAlpha = 1;
    bx.globalCompositeOperation = 'copy';
    bx.filter = 'blur(16px) brightness(0.42) saturate(1.35)';
    bx.drawImage(s, -b.width * 0.1, -b.height * 0.1, b.width * 1.2, b.height * 1.2);
    bx.filter = 'none';
    bx.globalCompositeOperation = 'source-over';
    // 減衰: 動画の縁で 0、画面の上下の端で 7 割暗く
    var t = Math.max(0, Math.min(1, g.y / H)), u = Math.max(t, Math.min(1, (g.y + g.h) / H));
    var gr = bx.createLinearGradient(0, 0, 0, b.height);
    gr.addColorStop(0, 'rgba(0,0,0,0.7)');
    gr.addColorStop(t, 'rgba(0,0,0,0)');
    gr.addColorStop(u, 'rgba(0,0,0,0)');
    gr.addColorStop(1, 'rgba(0,0,0,0.7)');
    bx.fillStyle = gr;
    bx.fillRect(0, 0, b.width, b.height);
    primed = true;
  }

  function compose(g, W) {
    var k = c.width / W, fy = g.y * k, fh = g.h * k;
    cx.globalCompositeOperation = 'copy';
    cx.drawImage(b, 0, 0);
    // 動画の部分をくり抜く。上下の縁だけ少しずつ(光に溶かす)
    var e = Math.min(FADE_MAX, FADE_PX * k / fh);
    var gr = cx.createLinearGradient(0, fy, 0, fy + fh);
    gr.addColorStop(0, 'rgba(0,0,0,0)');
    gr.addColorStop(e, 'rgba(0,0,0,1)');
    gr.addColorStop(1 - e, 'rgba(0,0,0,1)');
    gr.addColorStop(1, 'rgba(0,0,0,0)');
    cx.globalCompositeOperation = 'destination-out';
    cx.fillStyle = gr;
    cx.fillRect(g.x * k, fy, g.w * k, fh);
    cx.globalCompositeOperation = 'source-over';
  }

  function frame(now) {
    var g = null;
    try {
      var v = document.querySelector('video'), W = innerWidth, H = innerHeight;
      g = wanted(v, W, H);
      if (g) {
        if (!c.isConnected) document.documentElement.appendChild(c);
        var w2 = Math.max(1, Math.round(W / 2)), h2 = Math.max(1, Math.round(H / 2)); // 半分の細かさで十分(ぼけた光なので)
        if (c.width !== w2 || c.height !== h2) { c.width = b.width = w2; c.height = b.height = h2; primed = false; lastKey = ''; }
        var fresh = !primed || now - lastSample >= SAMPLE_MS;
        if (fresh) { lastSample = now; sample(v, g, W, H); }
        var key = [Math.round(g.x), Math.round(g.y), Math.round(g.w), Math.round(g.h)].join(',');
        if (fresh || key !== lastKey) { compose(g, W); lastKey = key; }
      } else {
        primed = false; // 次に出すときは、その時の色から始める(前の動画の色を引きずらない)
      }
    } catch (err) {
      g = null; // 描けない動画(保護付きなど)は、光を出さない(今までどおりの黒)
    }
    if (!!g !== shown) { shown = !!g; c.style.opacity = shown ? '1' : '0'; }
    if (shown) requestAnimationFrame(frame);
    else setTimeout(function () { requestAnimationFrame(frame); }, 250);
  }
  requestAnimationFrame(frame);
})
