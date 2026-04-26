package com.google.adk.agents.base.serialize;

import static com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.MINIMIZE_QUOTES;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import jakarta.inject.Provider;

public final class YamlMapperProvider implements Provider<ObjectMapper> {

  @Override
  public ObjectMapper get() {
    YAMLFactory factory = new YAMLFactory().enable(MINIMIZE_QUOTES);
    ObjectMapper mapper = new ObjectMapper(factory);
    mapper.registerModule(new GuavaModule());
    // mapper.registerModule(new Jdk8Module());
    mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);
    return mapper;
  }
}
