package com.fakestripe.hosted

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The two hosted pages (checkout, customer portal) are deliberately plain HTML with
 * no JavaScript: their job is to *redirect like Stripe*, not to look like it. They
 * are also labelled as a simulator on every screen so nobody mistakes one for the
 * real payment page.
 */
private const val STYLE = """
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 40px 16px; font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
         background: #f5f6f8; color: #1a1f36; }
  .card { max-width: 520px; margin: 0 auto; background: #fff; border: 1px solid #e3e8ee; border-radius: 10px;
          padding: 28px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
  .tag { display: inline-block; font-size: 11px; letter-spacing: .08em; text-transform: uppercase; font-weight: 600;
         color: #b45309; background: #fef3c7; border-radius: 4px; padding: 3px 7px; margin-bottom: 18px; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  h2 { font-size: 14px; text-transform: uppercase; letter-spacing: .04em; color: #697386; margin: 26px 0 10px; }
  .muted { color: #697386; }
  .amount { font-size: 30px; font-weight: 600; margin: 12px 0 2px; }
  .row { display: flex; justify-content: space-between; gap: 12px; padding: 9px 0; border-bottom: 1px solid #f0f2f5; }
  .row:last-child { border-bottom: 0; }
  label { display: block; font-weight: 500; margin: 18px 0 6px; }
  input { width: 100%; padding: 10px 12px; border: 1px solid #cfd7df; border-radius: 6px; font-size: 15px;
          font-family: ui-monospace, SFMono-Regular, Menlo, monospace; background: #fff; color: inherit; }
  button { width: 100%; padding: 11px 16px; border: 0; border-radius: 6px; font-size: 15px; font-weight: 600;
           cursor: pointer; margin-top: 14px; }
  .primary { background: #0a2540; color: #fff; }
  .secondary { background: #fff; color: #1a1f36; border: 1px solid #cfd7df; }
  .danger { background: #fff; color: #b3261e; border: 1px solid #f2c2be; }
  .inline { display: inline-block; width: auto; margin: 0; padding: 7px 14px; font-size: 13px; }
  .error { background: #fdf2f2; border: 1px solid #f5c6c6; color: #b3261e; border-radius: 6px; padding: 11px 13px;
           margin-bottom: 18px; font-size: 14px; }
  .note { background: #f7f9fc; border: 1px solid #e3e8ee; border-radius: 6px; padding: 12px 14px; margin-top: 22px;
          font-size: 13px; color: #4f566b; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; }
  a { color: #0a2540; }
  @media (prefers-color-scheme: dark) {
    body { background: #14161a; color: #e6e8eb; }
    .card { background: #1c1f24; border-color: #2c3138; box-shadow: none; }
    .row { border-bottom-color: #262a30; }
    .muted, h2 { color: #9aa4b2; }
    input { background: #14161a; border-color: #3a4048; }
    .primary { background: #4b8cf5; color: #0b1220; }
    .secondary, .danger { background: #1c1f24; border-color: #3a4048; color: #e6e8eb; }
    .note { background: #14161a; border-color: #2c3138; color: #9aa4b2; }
    .error { background: #2a1618; border-color: #5c2a2a; color: #f7a9a3; }
    a { color: #8ab4ff; }
  }
"""

fun page(title: String, body: String): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${esc(title)}</title>
<style>$STYLE</style>
</head>
<body>
<div class="card">
<div class="tag">fake-stripe simulator · no real money</div>
$body
</div>
</body>
</html>
""".trimIndent()

fun esc(value: String?): String = (value ?: "").replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** `1000, "usd"` -> `$10.00`; unknown currencies fall back to `10.00 CHF`. */
fun money(amount: Long, currency: String): String {
    val major = "%.2f".format(amount / 100.0)
    return when (currency.lowercase()) {
        "usd" -> "$$major"
        "eur" -> "€$major"
        "gbp" -> "£$major"
        else -> "$major ${currency.uppercase()}"
    }
}

/** Unix seconds -> `12 Aug 2026`, for period ends shown to a human. */
fun date(epochSeconds: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epochSeconds))
