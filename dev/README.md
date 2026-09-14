ADK development utilities such as Spring REST server for agent.

## Serving the dev UI

The UI and its assets are served under `/dev-ui/`, and both `/` and `/dev-ui`
redirect there, keeping the query string. The assets are not served from the
origin root: `/adk_favicon.svg` and the like return 404, and only the `/dev-ui/`
form resolves.

## Behind a reverse proxy

When a gateway publishes this server under a path prefix and strips it, tell the
server the address browsers actually reach it on:

```properties
adk.web.backend-url=https://gateway.example.com/my-app
```

That one value does both halves: the entry redirect carries the prefix, and the
UI's own API calls go back through it. Nothing has to be forwarded by the proxy,
and nothing is read from the request.

It must be an absolute URL. The UI reads a value without a scheme as the host of
its live/websocket connection, so a bare `/my-app` makes that socket dial a host
named `my-app`.

Include any `server.servlet.context-path` in the value: in the redirect it
replaces the context path rather than stacking on it.

Leave it unset and nothing changes: the redirect is unprefixed and the bundled
`backendUrl` is served as it always was. A deployment that already restores the
prefix with Spring's own `server.forward-headers-strategy=framework` keeps
working that way; this property takes precedence over it for the redirect's
path.

Turning that Spring setting on is a decision to trust forwarded headers, and
both the standard `Forwarded` header and the `X-Forwarded-*` family are supplied
by the client unless something overwrites them. The proxy at the edge has to
strip or overwrite both kinds arriving from outside, or a caller can tell the
server it was reached somewhere it was not. Setting `adk.web.backend-url` does
not require that setting at all.
