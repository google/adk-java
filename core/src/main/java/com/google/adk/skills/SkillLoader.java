package com.google.adk.skills;

import static java.util.function.Function.identity;
import static org.apache.arrow.util.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map.Entry;

/** Interface for loading skills from various sources. */
public interface SkillLoader {

  /**
   * Load a complete skill by its ID.
   *
   * @param skillId The ID of the skill.
   * @return Skill object with all components loaded.
   */
  Skill loadSkill(String skillId);

  /**
   * List available skills in the configured source.
   *
   * @return Map mapping skill IDs to their frontmatter.
   */
  ImmutableMap<String, Frontmatter> listSkills();

  static SkillLoader fromSkills(Skill... skills) {
    return fromSkills(ImmutableList.copyOf(skills));
  }

  static SkillLoader fromSkills(List<Skill> skills) {
    ImmutableMap<String, Skill> skillMap =
        skills.stream().collect(ImmutableMap.toImmutableMap(Skill::name, identity()));
    return new SkillLoader() {
      @Override
      public Skill loadSkill(String skillId) {
        Skill skill = skillMap.get(skillId);
        checkArgument(skill != null, "Skill not found: %s", skillId);
        return skill;
      }

      @Override
      public ImmutableMap<String, Frontmatter> listSkills() {
        return skillMap.entrySet().stream()
            .collect(ImmutableMap.toImmutableMap(Entry::getKey, e -> e.getValue().frontmatter()));
      }
    };
  }
}
