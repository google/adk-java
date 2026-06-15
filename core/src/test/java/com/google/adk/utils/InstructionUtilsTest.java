package com.google.adk.utils;

import static com.google.adk.testing.TestUtils.createRootAgent;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.genai.types.Part;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class InstructionUtilsTest {

  private InvocationContext templateContext;

  @Before
  public void setUp() {
    InMemorySessionService sessionService = new InMemorySessionService();
    templateContext =
        InvocationContext.builder()
            .sessionService(sessionService)
            .artifactService(new InMemoryArtifactService())
            .memoryService(new InMemoryMemoryService())
            .invocationId("invocationId")
            .agent(createRootAgent())
            .session(sessionService.createSession("test-app", "test-user").blockingGet())
            .build();
  }

  @Test
  public void injectSessionState_nullTemplate_throwsNullPointerException() {
    String template = null;

    assertThrows(
        NullPointerException.class,
        () -> InstructionUtils.injectSessionState(templateContext, template).blockingGet());
  }

  @Test
  public void injectSessionState_nullContext_throwsNullPointerException() {
    String template = "test";

    assertThrows(
        NullPointerException.class,
        () -> InstructionUtils.injectSessionState(null, template).blockingGet());
  }

  @Test
  public void injectSessionState_withMultipleStateVariables_replacesStatePlaceholders() {
    var testContext = templateContext.toBuilder().build();
    testContext.session().state().put("greeting", "Hi");
    testContext.session().state().put("user", "Alice");
    String template = "Greet the user with: {greeting} {user}.";

    String result = InstructionUtils.injectSessionState(testContext, template).blockingGet();

    assertThat(result).isEqualTo("Greet the user with: Hi Alice.");
  }

  @Test
  public void injectSessionState_stateVariablePlaceholderWithSpaces_trimsAndReplacesVariable() {
    var testContext = templateContext.toBuilder().build();
    testContext.session().state().put("name", "James");
    String template = "The user you are helping is: {  name  }.";

    String result = InstructionUtils.injectSessionState(testContext, template).blockingGet();

    assertThat(result).isEqualTo("The user you are helping is: James.");
  }

  @Test
  public void injectSessionState_stateVariablePlaceholderWithMultipleBraces_replacesVariable() {
    var testContext = templateContext.toBuilder().build();
    testContext.session().state().put("user:name", "Charlie");
    String template = "Use the user name: {{user:name}}.";

    String result = InstructionUtils.injectSessionState(testContext, template).blockingGet();

    assertThat(result).isEqualTo("Use the user name: Charlie.");
  }

  @Test
  public void injectSessionState_stateVariableWithNonStringValue_convertsValueToString() {
    InvocationContext testContext = templateContext.toBuilder().build();
    testContext.session().state().put("app:count", 123);
    String template = "The current count is: {app:count}.";

    String result = InstructionUtils.injectSessionState(testContext, template).blockingGet();

    assertThat(result).isEqualTo("The current count is: 123.");
  }

  @Test
  public void injectSessionState_missingNonOptionalStateVariable_throwsIllegalArgumentException() {
    String template = "Use the name {user:name} and the id {app:id}.";

    assertThrows(
        IllegalArgumentException.class,
        () -> InstructionUtils.injectSessionState(templateContext, template).blockingGet());
  }

  @Test
  public void injectSessionState_missingOptionalStateVariable_replacesWithEmptyString() {
    InvocationContext testContext = templateContext.toBuilder().build();
    testContext.session().state().put("user:first_name", "John");
    testContext.session().state().put("user:last_name", "Doe");
    String template =
        "The user's full name is: {user:first_name} {user:middle_name?} {user:last_name}.";

    String result = InstructionUtils.injectSessionState(testContext, template).blockingGet();

    assertThat(result).isEqualTo("The user's full name is: John  Doe.");
  }

  @Test
  public void injectSessionState_withValidArtifact_replacesWithArtifactText() {
    InvocationContext testContext = templateContext.toBuilder().build();
    Session session = testContext.session();
    var unused =
        testContext
            .artifactService()
            .saveArtifact(
                session.appName(),
                session.userId(),
                session.id(),
                "knowledge.txt",
                Part.fromText("This is a knowledge document."))
            .blockingGet();
    String template = "Include this knowledge: {artifact.knowledge.txt}.";

    String result = InstructionUtils.injectSessionState(testContext, template).blockingGet();

    assertThat(result)
        .isEqualTo("Include this knowledge: {\"text\":\"This is a knowledge document.\"}.");
  }

  @Test
  public void injectSessionState_missingNonOptionalArtifact_throwsIllegalArgumentException() {
    InvocationContext testContext = templateContext.toBuilder().build();
    String template = "Include this knowledge: {artifact.missing_knowledge.txt}.";

    assertThrows(
        IllegalArgumentException.class,
        () -> InstructionUtils.injectSessionState(testContext, template).blockingGet());
  }

  @Test
  public void injectSessionState_missingOptionalArtifact_replacesWithEmptyString() {
    InvocationContext testContext = templateContext.toBuilder().build();
    String template = "Include this additional info: {artifact.optional_info.txt?}.";

    String result = InstructionUtils.injectSessionState(testContext, template).blockingGet();

    assertThat(result).isEqualTo("Include this additional info: .");
  }

  @Test
  public void injectSessionState_invalidStateVariableNameSyntax_returnsPlaceholderAsIs() {
    String template = "Remember these values: {invalid-name!}, {another:bad:name},  {? }, and {}.";

    String result = InstructionUtils.injectSessionState(templateContext, template).blockingGet();

    assertThat(result)
        .isEqualTo("Remember these values: {invalid-name!}, {another:bad:name},  {? }, and {}.");
  }

  @Test
  public void injectSessionState_stateVariableWithValidPrefix_replacesVariable() {
    var testContext = templateContext.toBuilder().build();
    testContext.session().state().put("app:assistant_name", "Trippy");
    String template = "Set the assistant name to: {app:assistant_name}.";

    String result = InstructionUtils.injectSessionState(testContext, template).blockingGet();

    assertThat(result).isEqualTo("Set the assistant name to: Trippy.");
  }

  @Test
  public void injectSessionState_stateVariableWithInvalidPrefix_returnsPlaceholderAsIs() {
    String template = "Set the value: {invalidprefix:var}.";

    String result = InstructionUtils.injectSessionState(templateContext, template).blockingGet();

    assertThat(result).isEqualTo(template);
    assertThat(templateContext.session().state()).doesNotContainKey("invalidprefix:var");
  }

  @Test
  public void
      injectSessionState_stateVariableWithValidPrefixButInvalidIdentifier_returnsPlaceholderAsIs() {
    String varWithInvalidIdentifier = State.USER_PREFIX + "invalid-var";
    String template =
        "When the user says 'Hello', respond with: {" + varWithInvalidIdentifier + "}.";

    String result = InstructionUtils.injectSessionState(templateContext, template).blockingGet();

    assertThat(result).isEqualTo(template);
  }
}
