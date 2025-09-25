# Debug Instructions

The updated ToolConverter now includes debug logging. To see what arguments Spring AI is actually passing:

1. Run the Anthropic test with your API key:
   ```bash
   mvn test -Dtest=AnthropicApiIntegrationTest#testAgentWithToolsAndRealApi
   ```

2. Look for the debug output in the console:
   ```
   === DEBUG: Spring AI calling tool 'getWeatherInfo' ===
   Raw args from Spring AI: {actual_arguments_here}
   Args type: java.util.HashMap
   Args keys: [key1, key2, ...]
     key1 -> value1 (java.lang.String)
     key2 -> value2 (java.lang.Object)
   Processed args for ADK: {processed_arguments}
   ```

3. This will show us exactly what format Spring AI is using so we can fix the argument processing logic.

The current issue is that our argument processing isn't handling the specific format that Anthropic/Spring AI is using.