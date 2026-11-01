// Cloudflare Worker: the public, permanent address of the Tesco connector.
//
// Claude (web, desktop or the mobile apps) calls
//   https://<worker>.workers.dev/<SECRET>/mcp
// and this Worker forwards the request through Workers VPC and a private
// Cloudflare Tunnel to tesco-assistant on the user's laptop (127.0.0.1:8787).
// The laptop is never exposed to the internet; only requests carrying the
// secret path are forwarded.
//
// Bindings:
//   LAPTOP  vpc_service  -> 127.0.0.1:8787 via the "tesco-laptop" tunnel
//   SECRET  secret_text  -> long random path segment

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!env.SECRET || url.pathname !== `/${env.SECRET}/mcp`) {
      return new Response("Not found", { status: 404 });
    }

    const copy = request.method === "POST" ? request.clone() : null; // for the error reply's JSON-RPC id
    const headers = new Headers(request.headers);
    headers.delete("host");
    headers.delete("cf-connecting-ip");

    try {
      return await env.LAPTOP.fetch("http://127.0.0.1:8787/mcp", {
        method: request.method,
        headers,
        body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
      });
    } catch (err) {
      // Laptop off, asleep, or the helper isn't running.
      const id = copy ? await requestId(copy) : null;
      return Response.json(
        {
          jsonrpc: "2.0",
          id,
          error: {
            code: -32000,
            message:
              "The Tesco helper on the user's Windows laptop is not reachable (the laptop is probably off, asleep " +
              "or signed out of Windows). Ask the user to switch it on and sign in to Windows, then try again.",
          },
        },
        { status: 502 },
      );
    }
  },
};

async function requestId(request) {
  try {
    const body = await request.clone().json();
    return Array.isArray(body) ? null : (body?.id ?? null);
  } catch {
    return null;
  }
}
