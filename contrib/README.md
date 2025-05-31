# Contribution Guidelines

**Before You Start:**
Please take a look at [ADK Contribution Guidelines](https://google.github.io/adk-docs/contributing-guide/).

**Coding Guidelines For PR Approval:**

*   **Base Interfaces:** Inherit from base interfaces (e.g. `BaseLlm`) for
compatibility. This allows existing tooling to work seamlessly with your new
components.
*   **Asynchronous and Streaming:** Keep our code asynchronous (e.g. RxJava
`Flowable`).
*   **Readability:** Write clear, well-commented code.
*   **Consistency:** Adhere to the project's coding/formatting style.
*   **Testing:** Include unit and integration tests for new features and bug
fixes.
*   **Error Handling:** Handle errors gracefully with informative messages.
*   **Documentation:** Document your code and its usage.

**Contrib Graduation:**

We'll consider graduating community contributions into officially maintained
plugins based on:

*   **Usage:** Widespread use by the community (e.g., at least x users over y
months).
*   **Stability:** Stable and reliable code.
*   **Compatibility:** Integrates well with the core framework.
