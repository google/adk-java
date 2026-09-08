ADK development utilities such as Spring REST server for agent.

## Serving the dev UI

The UI and its assets are served under `/dev-ui/`, and both `/` and `/dev-ui`
redirect there, keeping the query string. The assets are not served from the
origin root: `/adk_favicon.svg` and the like return 404, and only the `/dev-ui/`
form resolves.
