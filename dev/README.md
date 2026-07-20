# Telemetry Export for JDBC

This document describes how to capture telemetry data for JDBC calls.

## Configuration

The application uses OpenTelemetry to capture telemetry data. To capture JDBC telemetry, you need to use the OpenTelemetry Java Agent. The agent automatically instruments JDBC calls and exports the data to your configured OpenTelemetry backend.

No code changes are required to enable JDBC telemetry.

For more information on how to configure the OpenTelemetry Java Agent, please refer to the [OpenTelemetry documentation](https://opentelemetry.io/docs/instrumentation/java/automatic/).
