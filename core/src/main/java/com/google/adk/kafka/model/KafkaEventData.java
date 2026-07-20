package com.google.adk.kafka.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Main Kafka event data structure representing the top-level event wrapper. This corresponds to the
 */
public class KafkaEventData {

  @JsonProperty("business_event")
  private String businessEvent;

  @JsonProperty("event_attributes")
  private EventAttributes eventAttributes;

  // Constructors
  public KafkaEventData() {}

  public KafkaEventData(String businessEvent, EventAttributes eventAttributes) {
    this.businessEvent = businessEvent;
    this.eventAttributes = eventAttributes;
  }

  // Getters and setters
  public String getBusinessEvent() {
    return businessEvent;
  }

  public void setBusinessEvent(String businessEvent) {
    this.businessEvent = businessEvent;
  }

  public EventAttributes getEventAttributes() {
    return eventAttributes;
  }

  public void setEventAttributes(EventAttributes eventAttributes) {
    this.eventAttributes = eventAttributes;
  }

  /**
   * Event attributes nested class containing session and event metadata. This corresponds to the Go
   * EventAttributes struct.
   */
  public static class EventAttributes {

    @JsonProperty("id")
    private String id;

    @JsonProperty("appName")
    private String appName;

    @JsonProperty("state")
    private Object state;

    @JsonProperty("event_data")
    private String eventData;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("lastUpdateTime")
    private Double lastUpdateTime;

    // Constructors
    public EventAttributes() {}

    public EventAttributes(
        String id,
        String appName,
        Object state,
        String eventData,
        String userId,
        Double lastUpdateTime) {
      this.id = id;
      this.appName = appName;
      this.state = state;
      this.eventData = eventData;
      this.userId = userId;
      this.lastUpdateTime = lastUpdateTime;
    }

    // Getters and setters
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getAppName() {
      return appName;
    }

    public void setAppName(String appName) {
      this.appName = appName;
    }

    public Object getState() {
      return state;
    }

    public void setState(Object state) {
      this.state = state;
    }

    public String getEventData() {
      return eventData;
    }

    public void setEventData(String eventData) {
      this.eventData = eventData;
    }

    public String getUserId() {
      return userId;
    }

    public void setUserId(String userId) {
      this.userId = userId;
    }

    public Double getLastUpdateTime() {
      return lastUpdateTime;
    }

    public void setLastUpdateTime(Double lastUpdateTime) {
      this.lastUpdateTime = lastUpdateTime;
    }
  }

  /** Events data wrapper containing the array of events. This corresponds to the Go */
  public static class EventsData {

    @JsonProperty("events")
    private List<Event> events;

    // Constructors
    public EventsData() {}

    public EventsData(List<Event> events) {
      this.events = events;
    }

    // Getters and setters
    public List<Event> getEvents() {
      return events;
    }

    public void setEvents(List<Event> events) {
      this.events = events;
    }
  }

  /** struct. */
  public static class Event {

    @JsonProperty("id")
    private String id;

    @JsonProperty("author")
    private String author;

    @JsonProperty("actions")
    private Actions actions;

    @JsonProperty("content")
    private Content content;

    @JsonProperty("timestamp")
    private Long timestamp;

    @JsonProperty("invocationId")
    private String invocationId;

    // Constructors
    public Event() {}

    public Event(
        String id,
        String author,
        Actions actions,
        Content content,
        Long timestamp,
        String invocationId) {
      this.id = id;
      this.author = author;
      this.actions = actions;
      this.content = content;
      this.timestamp = timestamp;
      this.invocationId = invocationId;
    }

    // Getters and setters
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getAuthor() {
      return author;
    }

    public void setAuthor(String author) {
      this.author = author;
    }

    public Actions getActions() {
      return actions;
    }

    public void setActions(Actions actions) {
      this.actions = actions;
    }

    public Content getContent() {
      return content;
    }

    public void setContent(Content content) {
      this.content = content;
    }

    public Long getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(Long timestamp) {
      this.timestamp = timestamp;
    }

    public String getInvocationId() {
      return invocationId;
    }

    public void setInvocationId(String invocationId) {
      this.invocationId = invocationId;
    }
  }

  /**
   * Actions structure containing various action deltas and configurations. This corresponds to the
   * Go Actions struct.
   */
  public static class Actions {

    @JsonProperty("stateDelta")
    private Object stateDelta;

    @JsonProperty("artifactDelta")
    private Object artifactDelta;

    @JsonProperty("requestedAuthConfigs")
    private Object requestedAuthConfigs;

    @JsonProperty("transferToAgent")
    private String transferToAgent;

    // Constructors
    public Actions() {}

    public Actions(
        Object stateDelta,
        Object artifactDelta,
        Object requestedAuthConfigs,
        String transferToAgent) {
      this.stateDelta = stateDelta;
      this.artifactDelta = artifactDelta;
      this.requestedAuthConfigs = requestedAuthConfigs;
      this.transferToAgent = transferToAgent;
    }

    // Getters and setters
    public Object getStateDelta() {
      return stateDelta;
    }

    public void setStateDelta(Object stateDelta) {
      this.stateDelta = stateDelta;
    }

    public Object getArtifactDelta() {
      return artifactDelta;
    }

    public void setArtifactDelta(Object artifactDelta) {
      this.artifactDelta = artifactDelta;
    }

    public Object getRequestedAuthConfigs() {
      return requestedAuthConfigs;
    }

    public void setRequestedAuthConfigs(Object requestedAuthConfigs) {
      this.requestedAuthConfigs = requestedAuthConfigs;
    }

    public String getTransferToAgent() {
      return transferToAgent;
    }

    public void setTransferToAgent(String transferToAgent) {
      this.transferToAgent = transferToAgent;
    }
  }

  /**
   * Content structure containing role and parts information. This corresponds to the Go Content
   * struct.
   */
  public static class Content {

    @JsonProperty("role")
    private String role;

    @JsonProperty("parts")
    private List<Part> parts;

    // Constructors
    public Content() {}

    public Content(String role, List<Part> parts) {
      this.role = role;
      this.parts = parts;
    }

    // Getters and setters
    public String getRole() {
      return role;
    }

    public void setRole(String role) {
      this.role = role;
    }

    public List<Part> getParts() {
      return parts;
    }

    public void setParts(List<Part> parts) {
      this.parts = parts;
    }
  }

  /**
   * Part structure representing individual content parts. This handles the varied part types (text,
   * functionCall, functionResponse).
   */
  public static class Part {

    @JsonProperty("text")
    private String text;

    @JsonProperty("functionCall")
    private FunctionCall functionCall;

    @JsonProperty("functionResponse")
    private FunctionResponse functionResponse;

    // Constructors
    public Part() {}

    public Part(String text, FunctionCall functionCall, FunctionResponse functionResponse) {
      this.text = text;
      this.functionCall = functionCall;
      this.functionResponse = functionResponse;
    }

    // Getters and setters
    public String getText() {
      return text;
    }

    public void setText(String text) {
      this.text = text;
    }

    public FunctionCall getFunctionCall() {
      return functionCall;
    }

    public void setFunctionCall(FunctionCall functionCall) {
      this.functionCall = functionCall;
    }

    public FunctionResponse getFunctionResponse() {
      return functionResponse;
    }

    public void setFunctionResponse(FunctionResponse functionResponse) {
      this.functionResponse = functionResponse;
    }

    /** Get the part type based on which field is populated. */
    public String getPartType() {
      if (text != null) {
        return "text";
      } else if (functionCall != null) {
        return "functionCall";
      } else if (functionResponse != null) {
        return "functionResponse";
      }
      return "unknown";
    }
  }

  /** Function call structure. */
  public static class FunctionCall {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("args")
    private String args;

    // Constructors
    public FunctionCall() {}

    public FunctionCall(String id, String name, String args) {
      this.id = id;
      this.name = name;
      this.args = args;
    }

    // Getters and setters
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getArgs() {
      return args;
    }

    public void setArgs(String args) {
      this.args = args;
    }
  }

  /** Function response structure. */
  public static class FunctionResponse {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("response")
    private String response;

    // Constructors
    public FunctionResponse() {}

    public FunctionResponse(String id, String name, String response) {
      this.id = id;
      this.name = name;
      this.response = response;
    }

    // Getters and setters
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getResponse() {
      return response;
    }

    public void setResponse(String response) {
      this.response = response;
    }
  }
}
