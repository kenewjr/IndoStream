# Hanime1 — Proxy Setup

`hanime1.me` returns **HTTP 403 (no Cloudflare challenge)** to many residential
and mobile IPs. This is geo/IP-level filtering, not a JS challenge, so cookies
and user-agent tweaks alone will not help. The plugin now chains four
fetch strategies inside `safeGet()`:

| # | Method                | Effort  | Reliability     |
|---|-----------------------|---------|-----------------|
| 1 | Direct GET + retries  | none    | depends on IP   |
| 2 | Public CORS proxies   | none    | medium (rate-limited, sometimes down) |
| 3 | Cloudflare Worker     | ~5 min  | **highest**     |
| 4 | HTTP / SOCKS proxy    | varies  | as good as your proxy |

If method 1 (direct) works on your network, the rest are skipped — there is no
runtime cost when you do not need them.

## Recommendation

> **Use the Cloudflare Worker (method 3).**
> It is free, takes ~5 minutes, runs on Cloudflare's edge (very low latency),
> and is far more reliable than the public proxies in method 2, which are
> shared with the entire internet and frequently rate-limited or 502.

## Method 3 — Deploy the Cloudflare Worker (recommended)

1. Open <https://workers.cloudflare.com> and click **Sign Up** (or **Log in**).
2. Click **Create a Worker** (or **Create application** → **Create Worker**).
3. Give it a name, e.g. `hanime1-proxy`. The public URL becomes
   `https://hanime1-proxy.<your-subdomain>.workers.dev`.
4. Click **Deploy**, then **Edit code**.
5. Replace the default code with the contents of
   [`Hanime1/cloudflare-worker.js`](./cloudflare-worker.js).
6. Click **Save and deploy**.
7. Copy your worker URL and paste it into
   [`Hanime1Constants.kt`](./src/main/kotlin/com/hanime1/Hanime1Constants.kt):

   ```kotlin
   internal const val CF_WORKER_URL: String =
       "https://hanime1-proxy.YOURUSER.workers.dev"
   ```

8. Rebuild the plugin (`./gradlew :Hanime1:make`) and reinstall.

> The free plan gives **100 000 requests/day** which is well above what one
> CloudStream user generates. The worker only forwards requests for
> `hanime1.me`, so it cannot be abused as an open proxy.

## Method 2 — Public proxies (zero setup)

If you do nothing, the plugin will already try these in order:

- `api.allorigins.win`
- `corsproxy.io`
- `api.codetabs.com`
- `thingproxy.freeboard.io`

They work, but expect occasional 429 / 502 / timeouts. Method 3 is strongly
preferred for daily use.

## Method 4 — User HTTP / SOCKS proxy (advanced)

If you already run a proxy (e.g. a local Shadowsocks / Trojan / Clash client,
or a paid HTTP proxy), edit the same constants file:

```kotlin
internal const val USER_PROXY_HOST: String = "127.0.0.1"
internal const val USER_PROXY_PORT: Int = 1080
internal const val USER_PROXY_IS_SOCKS: Boolean = true   // or false for HTTP
```

The plugin will route hanime1.me requests through this proxy as the last
fallback (after methods 1, 2 and 3 fail).

## Verifying it works

After installing the rebuilt plugin, look in `logcat -s Hanime1`:

```
D Hanime1: safeGet direct OK code=200 for https://hanime1.me/
```

If you see this you are done. If you instead see:

```
W Hanime1: safeGet direct failed, trying public proxies for ...
D Hanime1: safeGet proxy OK: https://corsproxy.io/? for ...
```

…method 1 is blocked but the chain recovered. If you want lower latency,
deploy the worker (method 3).
