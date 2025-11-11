# Hello World Agent Sample

This directory contains the minimal Java sample for the Google ADK. It defines a
single agent (`com.example.helloworld.HelloWorldAgent`) and a small console
runner (`HelloWorldRun`) that demonstrates tool invocation for dice rolling and
prime checking.

For configuration-driven examples that complement this code sample, see the
config-based collection in `../configagent/README.md`.

## Project Layout

```
├── HelloWorldAgent.java  // Agent definition and tool wiring
├── HelloWorldRun.java    // Console runner entry point
├── pom.xml               // Maven configuration and exec main class
└── README.md             // This file
```

## Prerequisites

- Java 17+
- Maven 3.9+

## Build and Run

Compile the project and launch the sample conversation:

```bash
mvn clean compile exec:java
```

The runner sends a starter prompt (`Hi. Roll a die of 60 sides.`) and prints the
agent's response. To explore additional prompts, pass the `--run-extended`
argument:

```bash
mvn exec:java -Dexec.args="--run-extended"
```

## Next Steps

* Review `HelloWorldAgent.java` to see how function tools are registered.
* Compare with the configuration-based samples in `../configagent/README.md` for
  more complex agent setups (callbacks, multi-agent coordination, and custom
  registries).
