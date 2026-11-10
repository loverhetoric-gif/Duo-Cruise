// ナビの声の間の YouTube(クロスフェード → 無音でスロー → 戻す)。TvPane が TV 版 YouTube のページへ入れる。
// 一度入れると window.__duoVoice(on, o) が使える。o = { base: 戻す音量(0〜1), mode: 'slow' | 'pause' }。
// 呼ぶ側(DuoActivity)は、声の流れが開いたらすぐ on=true、全部の声の流れが閉じてから 0.3 秒の余韻をおいて on=false
// (0.3 秒以内に次の声が始まれば、一つの声としてつながる)。
//
// 声が始まったとき(on=true):
//   1. 音量を 0.5 秒で 0 へ。最初の 0.2 秒で -10 dB(声が聞こえ始めるころには十分小さく)、次の 0.3 秒で -45 dB、そして 0
//      (声の頭は CarPlay 側で 0.15 秒かけて立ち上げるので、交差して聞こえる)
//   2. 音量 0 で消音(muted)にする。その声(の流れ)が 1 秒を超えて続いたら、速さを 0.4 秒かけて 0.1 倍へ
//      (呼ぶ側は流れが閉じてから 0.3 秒の余韻をおいて off にするので、on から 1.3 秒たっても on のときだけ)
//      (急に止まって見えないように)。mode='pause' なら一時停止
//      1 秒以内に終わる短い声(チャイム・一言)はスローにせず、音量の上げ下げだけ(動画は普通の速さのまま)
// 声が終わったとき(on=false):
//   3. 無音のまま、速さを 0.3 秒で元の速さへ(一時停止なら再生)
//   4. 速さが元に戻ったのを確かめてから消音を外し、1.5 秒かけて元の音量へ。-45 dB から dB でなめらかに大きく
//      (ゆっくり始まるので、声と声の間が 1 秒未満なら YouTube はほとんど聞こえない)
// 戻している途中で次の声が始まったら:
//   ・速さを戻している最中(無音) → そのままスローへ戻る。音は出ない
//   ・音量を戻している最中 → その時点の音量から、同じ曲線でなめらかに下げ直す(音が急に途切れない)
//   ・「1 秒を超えたらスロー」はその声の始まりから数える(短い声が続いても、1 回ずつが 1 秒未満ならスローにしない)
// スローの音は一切聞かせない(本人の指定):
//   ・速さを変えるのは、音量 0 かつ消音の間だけ。消音を外すのは、速さが元に戻ったのを確かめてから
//   ・消音の間に YouTube が音量・消音を戻したら、その瞬間に押さえ直す(volumechange・play を見張る)
//   ・声の間に YouTube が速さを変えたら、すぐ 0.1 倍に戻す(ratechange を見張る。1 秒ごとの確認もする)
// 音量の跳びを出さない:
//   ・下げる・戻す途中に YouTube が音量を変えたら、その瞬間に今の目標の音量へ戻す
//   ・動画が入れ替わったら(次の動画など)、新しい動画にも今の状態(目標の音量・消音・スロー)をすぐ当てる。
//     動かす相手は毎回「今の動画」を取り直す(古い動画だけ動かして新しい動画が取り残されないように)
//   ・Duo Cruise の他の音量の操作(__duoFade)は、ここが動いている間は止める
// 変わってから戻すのではなく、変わらないようにする:
//   ・声の対応中は、動画の音量・消音・速さへの書き込み(YouTube 自身のもの)を受け止め、今の目標の値にする
//     (HTMLMediaElement の volume・muted・playbackRate の書き込み口に挟む。対応中だけ。戻し切ったら素通し)
//     音が出ている間に YouTube が速さ・消音を変えたら(見ている人の操作)、それを新しい元の状態として受け入れる
//   ・新しい動画がページに現れたら、その場で今の状態を当てる(MutationObserver)
// 元の状態(声が始まる前の速さ・消音)を覚えて、そこへ戻す(1.25 倍で見ていれば 1.25 倍へ、消音していたら消音のまま)。
(function () {
  if (window.__duoVoice) return;
  var DOWN_FIRST_MS = 200, DOWN_FIRST_DB = -10, DOWN_REST_MS = 300, FLOOR_DB = -45;
  var RELEASE_MS = 300, SLOW_AFTER_MS = 1000 + RELEASE_MS, RATE_DOWN_MS = 400, RATE_UP_MS = 300, UP_MS = 1500, SLOW = 0.1;
  var S = {
    on: false,       // 声が鳴っている(呼ぶ側の合図)
    active: false,   // 声のために YouTube をいじっている(戻し切るまで true)
    mute: false,     // 消音で守っている(この間だけ速さを変えてよい)
    settled: false,  // スロー(または一時停止)に入り切った
    paused: false,   // こちらで一時停止した
    target: 1,       // 今の目標の音量(消音していない間)
    rateNow: 1,      // 今の目標の速さ
    base: 1, mode: 'slow',
    rate0: 1, muted0: false, // 声が始まる前の速さ・消音
    voiceAt: 0,      // 今の声の始まり
    timer: 0, seq: 0, slowTimer: 0
  };

  function vid() { return document.querySelector('video'); }
  function stop() { if (S.timer) { clearInterval(S.timer); S.timer = 0; } }
  function clearSlowTimer() { if (S.slowTimer) { clearTimeout(S.slowTimer); S.slowTimer = 0; } }
  // ms かけて step(0〜1) を呼ぶ。別の動きが始まったら(seq が変わったら)止まる
  function anim(ms, step, done) {
    stop();
    var my = ++S.seq, t0 = performance.now();
    if (ms <= 0) { step(1); if (done) done(); return; }
    S.timer = setInterval(function () {
      if (my !== S.seq) return;
      var x = Math.min(1, (performance.now() - t0) / ms);
      step(x);
      if (x >= 1) { stop(); if (done && my === S.seq) done(); }
    }, 16);
  }
  function smooth(x) { return x * x * (3 - 2 * x); }
  function near(a, b) { return Math.abs(a - b) < 0.001; }
  function db(a, ref) { return a > 0 && ref > 0 ? Math.max(FLOOR_DB, 20 * Math.log10(a / ref)) : FLOOR_DB; }
  // 今の動画へ当てる(動画が入れ替わっても、新しい動画に当たる)
  function setVol(a) { S.target = Math.max(0, Math.min(1, a)); var v = vid(); if (v && v.volume !== S.target) raw(dV, v, S.target); }
  function setRate(r) { S.rateNow = r; var v = vid(); if (v && !near(v.playbackRate, r)) raw(dR, v, r); }
  function setMuted(v, m) { if (v && v.muted !== m) raw(dM, v, m); }

  // 今の状態を、この動画に当て直す
  function apply(v) {
    if (!v || !S.active) return;
    if (S.mute) {
      setMuted(v, true);
      if (v.volume !== 0) raw(dV, v, 0);
      if (!near(v.playbackRate, S.rateNow)) raw(dR, v, S.rateNow);
      if (S.paused && !v.paused) v.pause();
    } else {
      // 守っていない(音が出る)のに速さが元でない → 音が変になる前に消音して、元の速さへ
      if (!near(v.playbackRate, S.rate0)) { setMuted(v, true); raw(dR, v, S.rate0); setMuted(v, S.muted0); }
      if (Math.abs(v.volume - S.target) > 0.005) raw(dV, v, S.target);
    }
  }
  // 書き込み口に挟む(声の対応中だけ働く。こちらの書き込みは own=true で素通し)
  var P = HTMLMediaElement.prototype, own = false;
  var dV = Object.getOwnPropertyDescriptor(P, 'volume'), dM = Object.getOwnPropertyDescriptor(P, 'muted'),
      dR = Object.getOwnPropertyDescriptor(P, 'playbackRate');
  function raw(d, el, x) { own = true; try { d.set.call(el, x); } finally { own = false; } }
  if (dV && dM && dR) {
    Object.defineProperty(P, 'volume', { configurable: true, enumerable: dV.enumerable, get: dV.get, set: function (x) {
      if (own || !S.active) return dV.set.call(this, x);
      dV.set.call(this, S.mute ? 0 : S.target);
    } });
    Object.defineProperty(P, 'muted', { configurable: true, enumerable: dM.enumerable, get: dM.get, set: function (x) {
      if (own || !S.active) return dM.set.call(this, x);
      if (S.mute) return dM.set.call(this, true);
      S.muted0 = !!x; dM.set.call(this, x); // 音が出ている間の消音の操作は、見ている人の操作として受け入れる
    } });
    Object.defineProperty(P, 'playbackRate', { configurable: true, enumerable: dR.enumerable, get: dR.get, set: function (x) {
      if (own || !S.active) return dR.set.call(this, x);
      if (S.mute) return dR.set.call(this, S.rateNow);
      S.rate0 = x; S.rateNow = x; dR.set.call(this, x); // 音が出ている間の速さの操作は、見ている人の操作として受け入れる
    } });
  }
  // 新しい動画が現れたら、その場で今の状態を当てる
  new MutationObserver(function () { if (S.active) apply(vid()); })
    .observe(document.documentElement, { childList: true, subtree: true });

  function watch(e) { if (e.target && e.target.tagName === 'VIDEO' && e.target === vid()) apply(e.target); }
  ['volumechange', 'ratechange', 'play', 'playing', 'loadstart', 'loadedmetadata'].forEach(function (n) {
    document.addEventListener(n, watch, true);
  });

  // 1. 今の音量から 0 へ(-10 dB → -45 dB → 0)。途中から始めるときは、その dB の位置から続ける
  function goDown() {
    if (window.__duoFade) { clearInterval(window.__duoFade); window.__duoFade = 0; }
    clearSlowTimer();
    if (S.mute) { afterSilent(); return; } // もう無音(戻している途中など) → そのまま次へ
    var ref = S.base > 0 ? S.base : 1, d0 = db(S.target, ref);
    // 全体の dB の道のり(0 → -10 → -45)のうち、今どこにいるかから、残りの時間を決める
    var startMs = d0 >= DOWN_FIRST_DB ? DOWN_FIRST_MS * (d0 / DOWN_FIRST_DB)
                                      : DOWN_FIRST_MS + DOWN_REST_MS * ((d0 - DOWN_FIRST_DB) / (FLOOR_DB - DOWN_FIRST_DB));
    var total = DOWN_FIRST_MS + DOWN_REST_MS, left = Math.max(0, total - startMs);
    anim(left, function (x) {
      var t = startMs + left * x, d;
      if (t <= DOWN_FIRST_MS) d = DOWN_FIRST_DB * (t / DOWN_FIRST_MS);
      else d = DOWN_FIRST_DB + (FLOOR_DB - DOWN_FIRST_DB) * smooth((t - DOWN_FIRST_MS) / DOWN_REST_MS);
      setVol(x >= 1 ? 0 : ref * Math.pow(10, d / 20));
    }, function () {
      setVol(0);
      S.mute = true;
      apply(vid());
      afterSilent();
    });
  }

  // 2. 無音になった。その声が 1 秒を超えたらスロー(短い声のうちに終われば、スローにしないまま戻る)
  function afterSilent() {
    if (!S.on) return;
    if (S.mode === 'slow' && !near(S.rateNow, S.rate0)) { goSlow(); return; } // 速さを戻している途中 → スローへ戻る
    var wait = Math.max(0, SLOW_AFTER_MS - (performance.now() - S.voiceAt));
    var my = S.seq;
    S.slowTimer = setTimeout(function () { S.slowTimer = 0; if (my === S.seq && S.on) goSlow(); }, wait);
  }

  function goSlow() {
    if (!S.mute) return; // 無音でないなら速さは変えない
    if (S.mode === 'pause') {
      var v = vid();
      S.paused = true;
      if (v && !v.paused) v.pause();
      S.settled = true;
      return;
    }
    var r0 = S.rateNow;
    anim(RATE_DOWN_MS * Math.min(1, Math.abs(r0 - SLOW) / Math.max(0.001, Math.abs(S.rate0 - SLOW))),
      function (x) { setRate(r0 + (SLOW - r0) * smooth(x)); },
      function () { setRate(SLOW); S.settled = true; });
  }

  // 3・4. 速さを元へ(無音のまま) → 確かめてから消音を外し、音量を元へ
  function goUp() {
    S.settled = false;
    clearSlowTimer();
    var volUp = function () {
      setRate(S.rate0);
      if (S.mute) {
        setVol(0);
        var v = vid();
        if (v && !near(v.playbackRate, S.rate0)) raw(dR, v, S.rate0);
        S.mute = false;
        if (v) setMuted(v, S.muted0);
      }
      var ref = S.base, d0 = db(S.target, ref);
      anim(UP_MS * (d0 / FLOOR_DB), function (x) {
        var d = d0 * (1 - smooth(x));
        setVol(x >= 1 ? ref : ref * Math.pow(10, d / 20));
      }, function () { setVol(ref); S.active = false; });
    };
    if (S.paused) { S.paused = false; var v = vid(); if (v) v.play(); volUp(); return; }
    var r0 = S.rateNow;
    if (near(r0, S.rate0)) { volUp(); return; }
    anim(RATE_UP_MS * Math.min(1, Math.abs(S.rate0 - r0) / Math.max(0.001, Math.abs(S.rate0 - SLOW))),
      function (x) { setRate(r0 + (S.rate0 - r0) * smooth(x)); },
      function () { setRate(S.rate0); volUp(); });
  }

  window.__duoVoice = function (on, o) {
    o = o || {};
    if (typeof o.base === 'number') { S.base = o.base; if (!S.active) S.target = o.base; }
    if (o.mode) S.mode = o.mode;
    if (on === S.on) return;
    S.on = on;
    var v = vid();
    if (on) {
      S.voiceAt = performance.now();
      if (!S.active) { // 戻し切ったあとの新しい声。元の状態を覚える
        S.active = true;
        S.rate0 = v ? (v.playbackRate || 1) : 1;
        S.muted0 = v ? v.muted : false;
        S.rateNow = S.rate0;
        S.target = v ? v.volume : S.base;
        if (S.target <= 0) S.target = S.base;
      }
      S.settled = false;
      goDown();
    } else {
      if (!S.active) return;
      goUp();
    }
  };
  window.__duoVoiceState = function () { return JSON.stringify(S); };
  window.__duoVoiceActive = function () { return S.active; };

  // 1 秒ごとの確認(動画が入れ替わった・YouTube が戻した場合の保険)
  setInterval(function () {
    if (!S.active) return;
    apply(vid());
    if (S.on && S.settled && S.mode === 'slow' && !near(S.rateNow, SLOW)) setRate(SLOW);
  }, 1000);
})();
