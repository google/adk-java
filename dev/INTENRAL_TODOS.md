# Dev TODOs

This file contains TODOs for the dev ADK module based on
[Recommendations for making ADK Java more idiomatic](http://go/idiomatic-adk-java).

## Dev UI

-   [ ] **Conditional UI**: Add a configuration property (e.g.,
    `adk.web.ui.enabled`) to conditionally enable/disable serving Dev UI static
    assets (in `AdkWebServer`).
-   [ ] **Integration Tests**: Add E2E tests (Selenium/Playwright/HtmlUnit) for
    Dev UI to verify interaction between frontend assets and Spring Boot
    backend.
-   [ ] **Integration Tests**: Test critical paths like loading UI, WebSocket
    connection, sending/receiving messages, and rich content handling (images).

## Production Readiness

-   [ ] **Actuators**: Enable and configure Spring Boot Actuator endpoints for
    monitoring and management.
-   [ ] **Actuators**: Configure startup and readiness probes for production
    environments.
