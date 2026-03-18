package com.google.adk.skills;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Function.identity;
import static org.apache.arrow.util.Preconditions.checkArgument;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads skills from a Google Cloud Storage bucket. */
public final class GcsSkillLoader extends AbstractSkillLoader {
  private static final Logger logger = LoggerFactory.getLogger(GcsSkillLoader.class);

  private final Storage storage;
  private final String bucketName;
  private final String basePrefix;

  /**
   * @param bucketName Name of the GCS bucket.
   * @param skillsBasePath Base directory within the bucket. Can be null or empty for root.
   */
  public GcsSkillLoader(String bucketName, @Nullable String skillsBasePath) {
    this(StorageOptions.getDefaultInstance().getService(), bucketName, skillsBasePath);
  }

  /**
   * @param storage Storage instance.
   * @param bucketName Name of the GCS bucket.
   * @param skillsBasePath Base directory within the bucket. Can be null or empty for root.
   */
  public GcsSkillLoader(Storage storage, String bucketName, @Nullable String skillsBasePath) {
    this.storage = storage;
    this.bucketName = bucketName;
    String prefix =
        skillsBasePath != null && !skillsBasePath.isEmpty() ? skillsBasePath.trim() : "";
    if (!prefix.isEmpty() && !prefix.endsWith("/")) {
      prefix += "/";
    }
    this.basePrefix = prefix;
  }

  @Override
  public Skill loadSkill(String skillId) {
    Bucket bucket = getBucket();
    String skillDirPrefix = basePrefix + skillId + "/";
    Blob manifestBlob = bucket.get(skillDirPrefix + "SKILL.md");

    checkArgument(
        manifestBlob != null && manifestBlob.exists(),
        "SKILL.md not found at gs://%s/%sSKILL.md",
        bucketName,
        skillDirPrefix);

    ParsedSkillMd parsed = parseSkillMdContent(new String(manifestBlob.getContent(), UTF_8));

    checkArgument(
        skillId.equals(parsed.frontmatter().name()),
        "Skill name '%s' does not match directory name '%s'.",
        parsed.frontmatter().name(),
        skillId);

    ImmutableMap<String, byte[]> references =
        loadGcsDirFiles(bucket, skillDirPrefix + "references/");
    ImmutableMap<String, byte[]> assets = loadGcsDirFiles(bucket, skillDirPrefix + "assets/");
    ImmutableMap<String, Script> scripts =
        loadGcsDirFiles(
            bucket, skillDirPrefix + "scripts/", bytes -> Script.create(new String(bytes, UTF_8)));

    return Skill.builder()
        .frontmatter(parsed.frontmatter())
        .instructions(parsed.body())
        .resources(
            Resources.builder().references(references).assets(assets).scripts(scripts).build())
        .build();
  }

  @Override
  protected ImmutableMap<String, ParsedSkillMd> loadAllParsedSkills() {
    Bucket bucket = getBucket();

    ImmutableMap.Builder<String, ParsedSkillMd> builder = ImmutableMap.builder();

    for (Blob blob :
        bucket
            .list(
                Storage.BlobListOption.prefix(basePrefix),
                Storage.BlobListOption.currentDirectory())
            .iterateAll()) {
      if (!blob.isDirectory()) {
        continue;
      }
      String skillPrefix = blob.getName(); // Ends with /
      List<String> parts = Splitter.on('/').omitEmptyStrings().splitToList(skillPrefix);
      String skillId = parts.get(parts.size() - 1);

      Blob manifestBlob = bucket.get(skillPrefix + "SKILL.md");
      if (manifestBlob != null && manifestBlob.exists()) {
        try {
          builder.put(skillId, parseSkillMdContent(new String(manifestBlob.getContent(), UTF_8)));
        } catch (IllegalArgumentException e) {
          logger.warn("Skipping invalid skill in bucket: {}", skillId, e);
        }
      }
    }
    return builder.buildOrThrow();
  }

  private Bucket getBucket() {
    Bucket bucket = storage.get(bucketName);
    checkArgument(bucket != null, "Bucket not found: %s", bucketName);
    return bucket;
  }

  private static ImmutableMap<String, byte[]> loadGcsDirFiles(Bucket bucket, String prefix) {
    return loadGcsDirFiles(bucket, prefix, identity());
  }

  private static <T> ImmutableMap<String, T> loadGcsDirFiles(
      Bucket bucket, String prefix, Function<byte[], T> valueFunc) {
    return bucket
        .list(Storage.BlobListOption.prefix(prefix))
        .streamAll()
        .filter(Predicate.not(Blob::isDirectory))
        .map(blob -> Map.entry(blob.getName().substring(prefix.length()), blob))
        .filter(e -> !e.getKey().isEmpty())
        .collect(toImmutableMap(Entry::getKey, e -> valueFunc.apply(e.getValue().getContent())));
  }
}
