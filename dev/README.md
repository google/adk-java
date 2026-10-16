ADK development utilities such as Spring REST server for agent.

## Serving the dev UI

The UI and its assets are served under `/dev-ui/`, and both `/` and `/dev-ui`
redirect there, keeping the query string. The assets are not served from the
origin root: `/adk_favicon.svg` and the like return 404, and only the `/dev-ui/`
form resolves.

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
and the bundled `backendUrl` value is preserved. If you instead enable
`server.forward-headers-strategy=framework`, make sure your reverse proxy strips
or overwrites incoming `Forwarded` and `X-Forwarded-*` headers from untrusted
clients.

## Cross-origin requests

By default, the REST API and the `/run_live` WebSocket accept only same-origin
requests. The dev UI served by the server itself is same-origin and needs no
configuration.

To allow other origins, for example a UI served by a separate development
server, set `adk.web.cors.origins` to a comma-separated list of origins:

```properties
adk.web.cors.origins=http://localhost:4200
```

Behind a reverse proxy, a request also counts as same-origin when its origin
matches the URL that the proxy reports in the `Forwarded` header, or in
`X-Forwarded-*` headers when `Forwarded` is absent. The dev UI therefore keeps
working behind a proxy on another machine that terminates TLS, such as Cloud
Run's. List the external origin in `adk.web.cors.origins` if the proxy changes
the scheme, host or port without reporting it; a reported scheme or host without
a port implies the scheme's default port. Avoid `*`: it lets any website open in
your browser call the server.

### DNS rebinding

To block pages that resolve their own domain to this machine, the server also
rejects a non-loopback `Host` when `server.address` is a loopback address (the
default for `mvn google-adk:web`), and a non-loopback `Origin` on a request that
arrives over loopback. Origins in `adk.web.cors.origins`, and their hosts, are
exempt, so a same-machine proxy that serves the UI under another name needs its
origin listed. `*` turns both checks off.
