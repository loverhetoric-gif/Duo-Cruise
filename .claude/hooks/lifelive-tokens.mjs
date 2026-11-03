#!/usr/bin/env node
// Claude Code の終了時（Stop）のフック：この会話で実際に使ったトークン数を LIFElive に送る（docs/使用量の学習_設計書.md 6）
// - LIFELIVE_URL・LIFELIVE_TOKEN が無ければ、何もせず静かに終わる
// - 送れなくても Claude Code の作業は絶対に止めない（何があっても終了コード 0、3 秒で打ち切る）
// - 追加の部品は使わない（Node だけ）
import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";

const quit = () => process.exit(0);
setTimeout(quit, 3000).unref();
process.on("uncaughtException", quit);
process.on("unhandledRejection", quit);

async function main() {
  const url = (process.env.LIFELIVE_URL || "").replace(/\/+$/, "");
  const token = process.env.LIFELIVE_TOKEN || "";
  if (!url || !token) return;

  // Claude Code から標準入力で { session_id, transcript_path, cwd, ... } が届く
  let input = "";
  for await (const chunk of process.stdin) input += chunk;
  const hook = JSON.parse(input || "{}");
  if (!hook.transcript_path || !hook.session_id) return;

  // 会話の記録（1 行 1 件）から、返答ごとの使用量を合計する。同じ返答が何行かに分かれて入るので、返答の番号で 1 回だけ数える
  const seen = new Set();
  const sum = { input_tokens: 0, output_tokens: 0, cache_read_tokens: 0, cache_write_tokens: 0 };
  let model = null, started = null, ended = null;
  for (const line of readFileSync(hook.transcript_path, "utf8").split("\n")) {
    if (!line) continue;
    let r;
    try { r = JSON.parse(line); } catch { continue; }
    if (r.timestamp) { started ??= r.timestamp; ended = r.timestamp; }
    const m = r.type === "assistant" ? r.message : null;
    if (!m?.usage || seen.has(m.id ?? r.uuid)) continue;
    seen.add(m.id ?? r.uuid);
    sum.input_tokens += m.usage.input_tokens || 0;
    sum.output_tokens += m.usage.output_tokens || 0;
    sum.cache_read_tokens += m.usage.cache_read_input_tokens || 0;
    sum.cache_write_tokens += m.usage.cache_creation_input_tokens || 0;
    if (m.model && m.model !== "<synthetic>") model = m.model;
  }

  const git = (...a) => { try { return execFileSync("git", a, { cwd: hook.cwd || process.cwd(), timeout: 1000, stdio: ["ignore", "pipe", "ignore"] }).toString().trim(); } catch { return null; } };
  const remote = git("remote", "get-url", "origin");
  const repo = remote ? remote.replace(/\.git$/, "").split(/[/:]/).pop() : (git("rev-parse", "--show-toplevel") || "").split("/").pop() || null;

  await fetch(`${url}/api/code-sessions`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      session_id: hook.session_id, repo, branch: git("rev-parse", "--abbrev-ref", "HEAD"), model, ...sum,
      started_at: started, ended_at: ended,
      // スマホ・ウェブの Claude Code（クラウド）では CLAUDE_CODE_REMOTE が付く
      where: process.env.CLAUDE_CODE_REMOTE ? "cloud" : "local",
    }),
    signal: AbortSignal.timeout(2500),
  });
}

main().then(quit, quit);
