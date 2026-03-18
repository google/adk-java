package com.google.adk.skills;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Function.identity;
import static org.apache.arrow.util.Preconditions.checkArgument;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads skills from a local directory. */
public final class LocalSkillLoader extends AbstractSkillLoader {
  private static final Logger logger = LoggerFactory.getLogger(LocalSkillLoader.class);

  private final Path skillsBasePath;

  /**
   * @param skillsBasePath Path to the base directory containing skills.
   */
  public LocalSkillLoader(Path skillsBasePath) {
    this.skillsBasePath = skillsBasePath;
  }

  @Override
  public Skill loadSkill(String skillId) {
    Path skillDir = skillsBasePath.resolve(skillId);
    checkArgument(Files.isDirectory(skillDir), "Skill directory not found: %s", skillDir);

    Path skillMd = findSkillMd(skillDir);
    checkArgument(skillMd != null, "SKILL.md not found in %s", skillDir);

    String content;
    try {
      content = Files.readString(skillMd, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read SKILL.md", e);
    }

    ParsedSkillMd parsed = parseSkillMdContent(content);
    checkArgument(
        skillId.equals(parsed.frontmatter().name()),
        "Skill name '%s' does not match directory name '%s'.",
        parsed.frontmatter().name(),
        skillId);

    ImmutableMap<String, byte[]> references = loadDirFiles(skillDir.resolve("references"));
    ImmutableMap<String, byte[]> assets = loadDirFiles(skillDir.resolve("assets"));
    ImmutableMap<String, Script> scripts =
        loadDirFiles(skillDir.resolve("scripts"), bytes -> Script.create(new String(bytes, UTF_8)));

    return Skill.builder()
        .frontmatter(parsed.frontmatter())
        .instructions(parsed.body())
        .resources(
            Resources.builder().references(references).assets(assets).scripts(scripts).build())
        .build();
  }

  @Override
  protected ImmutableMap<String, ParsedSkillMd> loadAllParsedSkills() {
    if (!Files.isDirectory(skillsBasePath)) {
      logger.warn("Skills base path is not a directory: {}", skillsBasePath);
      return ImmutableMap.of();
    }

    ImmutableMap.Builder<String, ParsedSkillMd> builder = ImmutableMap.builder();

    try (Stream<Path> stream = Files.list(skillsBasePath)) {
      stream
          .filter(Files::isDirectory)
          .map(LocalSkillLoader::findSkillMd)
          .filter(Objects::nonNull)
          .forEach(
              skillMd -> {
                try {
                  builder.put(
                      skillMd.getParent().getFileName().toString(),
                      parseSkillMdContent(Files.readString(skillMd, UTF_8)));
                } catch (IllegalArgumentException | IOException e) {
                  logger.warn("Skipping invalid skill in directory: {}", skillMd.getParent(), e);
                }
              });
    } catch (IOException e) {
      logger.warn("Failed to list skills in directory", e);
    }

    return builder.buildOrThrow();
  }

  @Nullable
  private static Path findSkillMd(Path dir) {
    Path skillMd = dir.resolve("SKILL.md");
    if (!Files.exists(skillMd)) {
      skillMd = dir.resolve("skill.md");
    }
    return Files.exists(skillMd) ? skillMd : null;
  }

  private static byte[] readAllBytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read file: " + file, e);
    }
  }

  private static ImmutableMap<String, byte[]> loadDirFiles(Path dir) {
    return loadDirFiles(dir, identity());
  }

  private static <T> ImmutableMap<String, T> loadDirFiles(Path dir, Function<byte[], T> valueFunc) {
    if (!Files.isDirectory(dir)) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, T> builder = ImmutableMap.builder();
    try (Stream<Path> paths = Files.walk(dir)) {
      paths
          .filter(Files::isRegularFile)
          .forEach(
              path ->
                  builder.put(
                      dir.relativize(path).toString(), valueFunc.apply(readAllBytes(path))));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to traverse directory: " + dir, e);
    }
    return builder.buildOrThrow();
  }
}
