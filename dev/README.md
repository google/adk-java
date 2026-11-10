ADK development utilities such as Spring REST server for agent.

## Serving the dev UI

The UI and its assets are served under `/dev-ui/`, and both `/` and `/dev-ui`
redirect there, keeping the query string. The assets are not served from the
origin root: `/adk_favicon.svg` and the like return 404, and only the `/dev-ui/`
form resolves.

## Listening address

`AdkWebServer.main()` and `AdkWebServer.start()` listen on `127.0.0.1` unless
`server.address` is set, because the server's endpoints do not authenticate
callers. To accept connections from other machines, such as in a container, set
the environment variable `SERVER_ADDRESS=0.0.0.0`, or pass
`--server.address=0.0.0.0` to `main()`. Put authentication in front of the
server first. `mvn google-adk:web` listens on `localhost` and takes `-Dhost`
instead.

## Behind a reverse proxy

If the server runs behind a reverse proxy that strips a path prefix (for
example, forwarding `https://gateway.example.com/my-app/` to `/`), set
`adk.web.backend-url` to the external URL that browsers use to reach the server:

```properties
adk.web.backend-url=https://gateway.example.com/my-app
```

Setting a usable `adk.web.backend-url`:

-   Sets `backendUrl` in `/dev-ui/assets/config/runtime-config.json` so the UI
    sends API and WebSocket requests through the proxy URL.
-   Prepends the URL's path (`/my-app`) to the `/` and `/dev-ui` entry redirects
    (`/my-app/dev-ui/`).

The value must be an absolute `http://` or `https://` URL without credentials,
query parameters, or a fragment. A value that does not meet those constraints is
ignored with a warning: it is neither served nor used as a redirect prefix, so
the bundled `backendUrl` stands exactly as when the property is unset. The UI
parses values without an `http://` or `https://` scheme as a WebSocket host, so
a relative path like `/my-app` cannot be used. If the application also sets
`server.servlet.context-path`, include it in `adk.web.backend-url`; when
`adk.web.backend-url` has a path prefix, the redirect uses that path directly
instead of prepending the context path or `X-Forwarded-Prefix`.

When `adk.web.backend-url` is unset, `/` and `/dev-ui` redirect to `/dev-ui/`
and the bundled `backendUrl` value is preserved.

The server applies `Forwarded` and `X-Forwarded-*` headers to the request URL by
default, as with `server.forward-headers-strategy=framework`. Make sure your
reverse proxy strips or overwrites these headers from untrusted clients. Set
`server.forward-headers-strategy=none` to ignore them, or `native` to let the
web server handle them instead. Behind a proxy that terminates TLS, keep them
on: with `none`, a page served over plain HTTP under the same host name passes
the same-origin check.

## Cross-origin requests

By default, the REST API and the `/run_live` WebSocket accept only same-origin
requests. The dev UI served by the server itself is same-origin and needs no
configuration.

To allow other origins, for example a UI served by a separate development
server, set `adk.web.cors.origins` to a comma-separated list of origins:

```properties
adk.web.cors.origins=http://localhost:4200
```

Behind a reverse proxy, the same-origin check uses the URL that the proxy
reports in forwarded headers, so the dev UI keeps working behind a proxy on
another machine that terminates TLS. A proxy on a non-default port must include
the port in its forwarded headers. List the external origin in
`adk.web.cors.origins` if the proxy does not report the external URL, or if it
reports `ws` or `wss` as the scheme of WebSocket requests (as Traefik does).
Avoid `*`: it lets any website open in your browser call the server.

### DNS rebinding

The server also blocks pages that resolve their own domain to this machine. When
`server.address` is a loopback address (the default for `AdkWebServer.main()`,
`AdkWebServer.start()` and `mvn google-adk:web`), it rejects a non-loopback
`Host`. On a request that arrives over loopback, it rejects a non-loopback
`Origin`. Origins in `adk.web.cors.origins`, and their hosts, are exempt. A UI
reached over loopback under another name, through a same-machine proxy or a
hosts-file alias, therefore needs its origin listed. `*` turns both checks off.
