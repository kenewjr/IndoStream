/**
 * Hanime1 Cloudflare Worker proxy
 * --------------------------------
 * Forwards a request to https://hanime1.me with the headers a real browser
 * would send. Deploy on https://workers.cloudflare.com (free tier handles
 * 100k requests/day) and paste the public URL into:
 *
 *   Hanime1/src/main/kotlin/com/hanime1/Hanime1Constants.kt
 *   -> internal const val CF_WORKER_URL = "https://YOUR.workers.dev"
 *
 * Endpoints
 *   GET /?url=<encoded hanime1 url>
 *
 * Only hanime1.me URLs are forwarded. Everything else returns 400.
 */

const ALLOWED_HOST = "hanime1.me";

const FORWARD_HEADERS = {
  "User-Agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  "Accept":
    "text/html,application/xhtml+xml,application/xml;q=0.9," +
    "image/avif,image/webp,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.9",
  "Accept-Encoding": "gzip, deflate, br",
  "Upgrade-Insecure-Requests": "1",
  "Sec-Fetch-Dest": "document",
  "Sec-Fetch-Mode": "navigate",
  "Sec-Fetch-Site": "none",
  "Sec-Fetch-User": "?1",
  "Cache-Control": "max-age=0",
  "Referer": "https://hanime1.me/",
};

addEventListener("fetch", (event) => {
  event.respondWith(handleRequest(event.request));
});

async function handleRequest(request) {
  const reqUrl = new URL(request.url);
  const target = reqUrl.searchParams.get("url");

  if (!target) {
    return new Response("Missing ?url=", { status: 400 });
  }

  let parsed;
  try {
    parsed = new URL(target);
  } catch {
    return new Response("Malformed url param", { status: 400 });
  }

  if (!parsed.hostname.endsWith(ALLOWED_HOST)) {
    return new Response(
      `Only ${ALLOWED_HOST} URLs are allowed`,
      { status: 400 },
    );
  }

  let upstream;
  try {
    upstream = await fetch(parsed.toString(), {
      method: "GET",
      headers: FORWARD_HEADERS,
      redirect: "follow",
    });
  } catch (err) {
    return new Response(`Upstream fetch failed: ${err}`, { status: 502 });
  }

  // Copy response body verbatim. Strip CSP / frame headers that would block
  // CloudStream's WebView on the rare iframe path.
  const headers = new Headers();
  const contentType =
    upstream.headers.get("Content-Type") || "text/html; charset=utf-8";
  headers.set("Content-Type", contentType);
  headers.set("Access-Control-Allow-Origin", "*");
  headers.set("Cache-Control", "no-store");

  return new Response(upstream.body, {
    status: upstream.status,
    headers,
  });
}
