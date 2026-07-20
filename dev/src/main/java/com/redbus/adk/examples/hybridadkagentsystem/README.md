# Hybrid Multi-Language ADK Agent: Product Information Assistant

![Project Status](https://img.shields.io/badge/status-active-success)
![Python Version](https://img.shields.io/badge/python-3.10%2B-blue)
![Java Version](https://img.shields.io/badge/java-17%2B-red)
![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)

A hybrid AI agent system integrating Python and Java to create an intelligent product information assistant, leveraging Google's ADK, LLMs (Ollama), and ClickHouse DB.

## Table of Contents

* [Project Description](#project-description)
* [Features](#features)
* [Design Patterns and Architecture](#design-patterns-and-architecture)
* [System Requirements](#system-requirements)
* [License](#license)

---

## Project Description

This project showcases a sophisticated, enterprise-grade approach to building intelligent conversational agents by strategically combining the strengths of multiple programming languages. At its core lies a **Python-based intelligent agent service** (using FastAPI and Google ADK for Python), meticulously crafted to act as the brain. This service interacts with a powerful Large Language Model (LLM) – specifically, a locally hosted Llama 3.2 model via Ollama – to understand natural language queries. Crucially, it's augmented with custom tools, including a dedicated **ClickHouse database connector**, enabling the agent to autonomously query a product catalog and inventory database to retrieve real-time, accurate product and stock information.

Complementing this, a **Java-based proxy agent** (developed with Google's ADK for Java) serves as the resilient front-end orchestrator. This Java proxy seamlessly communicates with the Python service over HTTP, receiving user requests, forwarding them, and intelligently processing the structured responses. This unique hybrid architecture is designed to facilitate the **seamless integration of cutting-edge Python AI/ML capabilities** into robust, scalable, and often pre-existing Java enterprise applications and infrastructure, allowing organizations to leverage the best of both worlds without compromising on performance or maintainability.

---

## Features

* **Intelligent Natural Language Understanding:** Processes complex user queries using a Large Language Model (Llama 3.2 via Ollama) for accurate intent recognition and response generation.
* **Real-time Data Retrieval:** Connects directly to a ClickHouse database to fetch live product details, descriptions, pricing, and current inventory levels.
* **Multi-Language Hybrid Architecture:** Demonstrates robust interoperability between Python (for specialized AI/LLM logic and data tools) and Java (for enterprise-grade orchestration, integration, and scalability).
* **Google ADK Integration:** Leverages the Google Agent Development Kit in both Python and Java, facilitating structured agent capabilities and streamlined communication protocols.
* **Microservice Design:** The Python agent is deployed as an independent FastAPI service, enabling flexible deployment, independent scaling, and high availability.
* **Modular and Extensible:** Designed for easy addition of new specialized tools or AI capabilities within the Python agent, or new integration points within the Java proxy, with minimal impact across the system.
* **Support for Local LLMs:** Configured to integrate with local LLM serving solutions like Ollama, offering flexibility for development, privacy-sensitive applications, and reduced API costs.
* **Enterprise Integration Ready:** Provides a blueprint for incorporating advanced AI functionalities into existing Java-centric enterprise ecosystems, leveraging Java's strengths in large-scale system management.

---

## Design Patterns and Architecture

This system incorporates several key design patterns and architectural choices to ensure its flexibility, scalability, and maintainability:

* **Microservices Architecture:**
    * The Python agent service and the Java proxy agent operate as independent, loosely coupled services. This allows for separate development, deployment, scaling, and technology choices for each component based on its specific responsibilities and performance needs.
* **Proxy Pattern:**
    * The Java agent acts as a proxy for the Python product information agent. It intercepts requests, forwards them to the Python service, and then relays the responses back to the client. This pattern abstracts the complexity of interacting with the Python service from the client and enables integration with Java-based enterprise systems.
* **Hybrid Architecture:**
    * This system explicitly combines two distinct technology stacks (Python and Java) to leverage their respective strengths. Python is used for its rich AI/ML ecosystem and LLM integration, while Java is used for its enterprise-grade robustness, performance, and scalability in backend systems.
* **Agent-Based System:**
    * Both the Python and Java components are structured as "agents" utilizing Google's Agent Development Kit (ADK). This promotes a clear, standardized way of defining intelligent entities that can perform tasks, use tools, and interact with each other.
* **Tool Use / Function Calling:**
    * The Large Language Model (LLM) within the Python agent is empowered through "tool use" (also known as function calling). It can intelligently determine when to invoke external functions (like `ClickHouse` queries) to retrieve specific, factual data necessary to answer a user's query, extending its capabilities beyond general knowledge.
* **Client-Server Communication (RESTful API):**
    * The Python agent exposes its functionalities via a RESTful API (using FastAPI), allowing the Java proxy agent to communicate with it using standard HTTP requests. This provides a common, language-agnostic interface between the two services.

---

## System Requirements

Ensure you have the following installed on your system:

* **Python:** Version **3.10** or higher.
* **Java Development Kit (JDK):** Version **17** or higher.
* **Apache Maven:** (Recommended for Java project management)
* **Docker** (Recommended for ClickHouse and Ollama for easy setup) or direct installations of:
    * **ClickHouse:** A running ClickHouse instance (default port 8123/9000).
    * **Ollama:** A running Ollama server (default port 11434) with the `llama3.2:latest` model downloaded.

---

## License

This project is licensed under the **Apache 2.0 License** - see the `LICENSE` file for more details.

## Contributors

* Sandeep Belgavi