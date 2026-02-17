# Contribution Guidelines

**Before You Start:**
Please take a look at [ADK Contribution Guidelines](https://google.github.io/adk-docs/contributing-guide/).

## Samples

The samples folder hosts minimal examples to test different features. These samples are intentionally simplistic and focused on testing specific scenarios.

**Note:** This is different from the [google/adk-samples](https://github.com/google/adk-samples) repository, which hosts more complex end-to-end samples for customers to use or modify directly.

## Coding Guidelines For PR Approval

*   **Base Interfaces:** Inherit from base interfaces (e.g. `BaseLlm`) for compatibility. This allows existing tooling to work seamlessly with your new components.
*   **Asynchronous and Streaming:** Keep our code asynchronous (e.g. RxJava `Flowable`).
*   **Readability:** Write clear, well-commented code.
*   **Consistency:** Adhere to the project's coding/formatting style.
*   **Testing:** Include unit and integration tests for new features and bug fixes.
*   **Error Handling:** Handle errors gracefully with informative messages.
*   **Documentation:** Document your code and its usage.

## Compatibility Guarantees

* All code in `contrib/` is explicitly not covered by any sort of backwards API Compatibility Guarantees, even across minor (patch) releases.
* The maintainers (committers) of this project are going to merge any changes in both implementations and interfaces without API stability concerns.
* Contributors are welcome to raise their PRs based on this policy, and incrementally improve code without worrying about API breakage.

## Contrib Graduation

We'll consider graduating community contributions into officially maintained
plugins based on:

*   **Usage:** Widespread use by the community (e.g., at least x users over y months).
*   **Stability:** Stable and reliable code.
*   **Compatibility:** Integrates well with the core framework.
