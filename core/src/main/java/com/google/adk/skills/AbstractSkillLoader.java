package com.google.adk.skills;

import static org.apache.arrow.util.Preconditions.checkArgument;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Base class containing common parsing logic for SkillLoaders. */
public abstract class AbstractSkillLoader implements SkillLoader {
  private static final Logger logger = LoggerFactory.getLogger(AbstractSkillLoader.class);
  private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  /** Record to hold the parsed SKILL.md content. */
  protected record ParsedSkillMd(Frontmatter frontmatter, String body) {}

  /**
   * Retrieves all available parsed SKILL.md files.
   *
   * @return A map where keys are skill IDs (directory names) and values are the parsed SKILL.md
   *     contents, or empty if none are found.
   */
  protected abstract ImmutableMap<String, ParsedSkillMd> loadAllParsedSkills();

  @Override
  public final ImmutableMap<String, Frontmatter> listSkills() {
    ImmutableMap.Builder<String, Frontmatter> builder = ImmutableMap.builder();
    loadAllParsedSkills()
        .forEach(
            (skillId, parsedSkill) -> {
              if (skillId.equals(parsedSkill.frontmatter().name())) {
                builder.put(skillId, parsedSkill.frontmatter());
              } else {
                logger.warn("Skipping invalid skill: Name does not match directory. {}", skillId);
              }
            });

    return builder.buildOrThrow();
  }

  protected final ParsedSkillMd parseSkillMdContent(String content) {
    checkArgument(content.startsWith("---"), "SKILL.md must start with YAML frontmatter (---)");

    String[] parts = content.split("---", 3);
    checkArgument(parts.length >= 3, "SKILL.md frontmatter not properly closed with ---");

    String frontmatterStr = parts[1];
    String body = parts[2].trim();

    try {
      Frontmatter fm = yamlMapper.readValue(frontmatterStr, Frontmatter.class);
      return new ParsedSkillMd(fm, body);
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid YAML in frontmatter", e);
    }
  }
}
