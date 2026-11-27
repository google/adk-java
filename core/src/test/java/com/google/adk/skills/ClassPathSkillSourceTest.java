/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.skills;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import com.google.common.reflect.ClassPath.ResourceInfo;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ClassPathSkillSourceTest {

  private static final String BASE_PATH = "skills/";

  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  // =========================================================================
  // Constructor & Base Path Normalization
  // =========================================================================

  @Test
  public void testConstructor_normalizesPathWithoutTrailingSlash() {
    SkillSource source = new ClassPathSkillSource("skills");
    Frontmatter fm = source.loadFrontmatter("normal-skill").blockingGet();
    assertThat(fm.name()).isEqualTo("normal-skill");
  }

  @Test
  public void testConstructor_emptyBasePath() {
    SkillSource emptySource = new ClassPathSkillSource("");
    ImmutableMap<String, Frontmatter> skills = emptySource.listFrontmatters().blockingGet();
    assertThat(skills).containsKey("root-skill");
  }

  // =========================================================================
  // listFrontmatters
  // =========================================================================

  @Test
  public void testListFrontmatters() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableMap<String, Frontmatter> skills = source.listFrontmatters().blockingGet();

    assertThat(skills).hasSize(2);
    assertThat(skills).containsKey("normal-skill");
    assertThat(skills).containsKey("underscore-skill");

    assertThat(skills.get("normal-skill").description()).isEqualTo("A normal skill with a hyphen");
    assertThat(skills.get("underscore-skill").description())
        .isEqualTo("A skill with an underscore");
  }

  // =========================================================================
  // loadFrontmatter
  // =========================================================================

  @Test
  public void testLoadFrontmatter() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    Frontmatter fm = source.loadFrontmatter("normal-skill").blockingGet();

    assertThat(fm.name()).isEqualTo("normal-skill");
    assertThat(fm.description()).isEqualTo("A normal skill with a hyphen");
  }

  @Test
  public void testLoadFrontmatter_underscoreMapping() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    Frontmatter fm = source.loadFrontmatter("underscore-skill").blockingGet();

    assertThat(fm.name()).isEqualTo("underscore-skill");
    assertThat(fm.description()).isEqualTo("A skill with an underscore");
  }

  @Test
  public void testLoadFrontmatter_skillNotFound() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    var single = source.loadFrontmatter("non-existent");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.SKILL_NOT_FOUND);
  }

  // =========================================================================
  // loadInstructions
  // =========================================================================

  @Test
  public void testLoadInstructions() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    String instructions = source.loadInstructions("normal-skill").blockingGet();

    assertThat(instructions).isEqualTo("body 1");
  }

  // =========================================================================
  // listResources
  // =========================================================================

  @Test
  public void testListResources_skillNotFound() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    var single = source.listResources("non-existent", "assets");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.SKILL_NOT_FOUND);
  }

  @Test
  public void testListResources_resourceDirectoryNotFound() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    var single = source.listResources("normal-skill", "non-existent-dir");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.RESOURCE_NOT_FOUND);
  }

  @Test
  public void testListResources() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableList<String> resources = source.listResources("normal-skill", "assets").blockingGet();
    assertThat(resources).containsExactly("assets/spec/spec.txt");
  }

  @Test
  public void testListResources_excludesSkillMd() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableList<String> resources = source.listResources("normal-skill", "").blockingGet();
    assertThat(resources).containsExactly("assets/spec/spec.txt", "resource/extra.txt");
  }

  @Test
  public void testListResources_underscoreMapping() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableList<String> resources =
        source.listResources("underscore-skill", "resource").blockingGet();
    assertThat(resources).containsExactly("resource/dummy.txt");
  }

  // =========================================================================
  // loadResource
  // =========================================================================

  @Test
  public void testLoadResource() throws Exception {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ByteSource byteSource =
        source.loadResource("normal-skill", "assets/spec/spec.txt").blockingGet();
    assertThat(byteSource.asCharSource(UTF_8).read().trim()).isEqualTo("A spec file");
  }

  @Test
  public void testLoadResource_underscoreMapping() throws Exception {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ByteSource byteSource =
        source.loadResource("underscore-skill", "resource/dummy.txt").blockingGet();
    assertThat(byteSource.asCharSource(UTF_8).read().trim()).isEqualTo("dummy content");
  }

  @Test
  public void testLoadResource_notFound() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    var single = source.loadResource("normal-skill", "non-existent.txt");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.RESOURCE_NOT_FOUND);
  }

  // =========================================================================
  // Error Handling & Conflicts
  // =========================================================================

  @Test
  public void testConflictingSkillMds() {
    SkillSource source = new ClassPathSkillSource("skills_conflict/");
    var single = source.listFrontmatters();
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.SKILL_LOAD_ERROR);
    assertThat(cause).hasMessageThat().contains("Conflicting SKILL.md files found for skill 'a-b'");
  }

  // =========================================================================
  // Packaged Resources & ClassLoader Compatibility
  // =========================================================================

  @Test
  public void ordinaryJarWithoutDirectoryEntries() throws Exception {
    Path jar = skillJar("ordinary.jar", "", "ordinary-skill");
    try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      assertSkill(new ClassPathSkillSource("skills/", loader), "ordinary-skill");
    }
  }

  @Test
  public void jarUrlWithoutDirectoryEntries() throws Exception {
    Path jar = skillJar("jar-url.jar", "", "jar-skill");
    try (URLClassLoader loader = new URLClassLoader(new URL[] {jarRoot(jar, "")}, null)) {
      assertThat(loader.getResource("skills/jar-skill/SKILL.md")).isNotNull();
      assertSkill(new ClassPathSkillSource("skills/", loader), "jar-skill");
    }
  }

  @Test
  public void jarSubdirectoryIsRelativeToClassLoaderRoot() throws Exception {
    Path jar = skillJar("application with spaces.jar", "BOOT-INF/classes/", "app-skill");
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {jarRoot(jar, "BOOT-INF/classes/")}, null)) {
      assertSkill(new ClassPathSkillSource("skills", loader), "app-skill");
      assertThat(new ClassPathSkillSource("", loader).listFrontmatters().blockingGet().keySet())
          .containsExactly("root-skill");
    }
  }

  @Test
  public void scansJarUrlsAlongsideFileUrlsAndParentLoaders() throws Exception {
    Path parentJar = skillJar("parent.jar", "", "parent-skill");
    Path childJar = skillJar("child.jar", "", "child-skill");
    try (URLClassLoader parent = new URLClassLoader(new URL[] {jarRoot(parentJar, "")}, null);
        URLClassLoader child = new URLClassLoader(new URL[] {childJar.toUri().toURL()}, parent)) {
      ClassPathSkillSource source = new ClassPathSkillSource("skills/", child);
      assertThat(source.listFrontmatters().blockingGet().keySet())
          .containsExactly("parent-skill", "child-skill");
      assertThat(source.loadInstructions("parent-skill").blockingGet()).isEqualTo("Instructions.");
      assertThat(source.loadInstructions("child-skill").blockingGet()).isEqualTo("Instructions.");
    }
  }

  @Test
  public void retainsGenericSuperclassAndProtectedResourceInfoMethods() throws Exception {
    Path jar = skillJar("legacy.jar", "", "legacy-skill");
    try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      AbstractSkillSource<ResourceInfo> source = new ClassPathSkillSource("skills/", loader);
      ResourceInfo skill = source.findSkillMdPath("legacy-skill").blockingGet();
      assertThat(skill.getResourceName()).isEqualTo("skills/legacy-skill/SKILL.md");
      assertThat(source.listSkills().count().blockingGet()).isEqualTo(1);
      ResourceInfo resource =
          source.findResourcePath("legacy-skill", "references/nested/guide.txt").blockingGet();
      try (var stream = Channels.newInputStream(source.openChannel(resource))) {
        assertThat(new String(stream.readAllBytes(), UTF_8)).isEqualTo("Reference text.");
      }
      assertThat(source.loadInstructions("legacy-skill").blockingGet()).isEqualTo("Instructions.");
    }
  }

  @Test
  public void scansLazilyAndSharesNativeScanWithProtectedMethods() throws Exception {
    Path jar = skillJar("cached.jar", "", "cached-skill");
    AtomicInteger scans = new AtomicInteger();
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {jar.toUri().toURL()}, null) {
          @Override
          public URL[] getURLs() {
            scans.incrementAndGet();
            return super.getURLs();
          }
        }) {
      ClassPathSkillSource source = new ClassPathSkillSource("skills/", loader);
      assertThat(scans.get()).isEqualTo(0);
      source.listFrontmatters().blockingGet();
      int scansAfterDiscovery = scans.get();
      assertThat(scansAfterDiscovery).isGreaterThan(0);

      // Public and protected lookups reuse discovery, regardless of its scan phases.
      assertSkill(source, "cached-skill");
      source.findSkillMdPath("cached-skill").blockingGet();
      source.findResourcePath("cached-skill", "references/nested/guide.txt").blockingGet();
      source.listSkills().toList().blockingGet();
      assertThat(scans.get()).isEqualTo(scansAfterDiscovery);
    }
  }

  @Test
  public void sameArchiveExposedAsFileAndJarDoesNotDuplicateSkills() throws Exception {
    Path jar = skillJar("duplicate-url.jar", "", "single-skill");
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {jar.toUri().toURL(), jarRoot(jar, "")}, null)) {
      assertSkill(new ClassPathSkillSource("skills/", loader), "single-skill");
    }
  }

  @Test
  public void retainsNativeDuplicateNameConflictsAcrossLoaders() throws Exception {
    Path parentJar = skillJar("native-parent.jar", "", "conflicting-skill");
    Path childJar = skillJar("native-child.jar", "", "conflicting-skill");
    try (URLClassLoader parent = new URLClassLoader(new URL[] {parentJar.toUri().toURL()}, null);
        URLClassLoader child = new URLClassLoader(new URL[] {childJar.toUri().toURL()}, parent)) {
      ClassPathSkillSource source = new ClassPathSkillSource("skills/", child);
      RuntimeException error =
          assertThrows(RuntimeException.class, () -> source.listFrontmatters().blockingGet());
      assertThat(error).hasCauseThat().isInstanceOf(SkillSourceException.class);
      assertThat(error).hasCauseThat().hasMessageThat().contains("Conflicting SKILL.md");
      assertThrows(
          RuntimeException.class, () -> source.findSkillMdPath("conflicting-skill").blockingGet());
    }
  }

  @Test
  public void nativeReadsRetainTheDiscoveringLoader() throws Exception {
    Path jar = skillJar("native-origin.jar", "", "parent-skill");
    URL replacement = temporary.newFile("replacement.txt").toURI().toURL();
    try (URLClassLoader parent = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
        URLClassLoader child =
            new URLClassLoader(new URL[0], parent) {
              @Override
              public URL getResource(String name) {
                return name.startsWith("skills/") ? replacement : super.getResource(name);
              }
            }) {
      assertSkill(new ClassPathSkillSource("skills/", child), "parent-skill");
    }
  }

  @Test
  public void jarReadsFollowSuppliedLoaderPrecedence() throws Exception {
    Path parentJar = skillJar("jar-parent.jar", "", "shared-skill", "Parent instructions.");
    Path childJar = skillJar("jar-child.jar", "", "shared-skill", "Child instructions.");
    try (URLClassLoader parent = new URLClassLoader(new URL[] {jarRoot(parentJar, "")}, null);
        URLClassLoader child = new URLClassLoader(new URL[] {jarRoot(childJar, "")}, parent)) {
      ClassPathSkillSource source = new ClassPathSkillSource("skills/", child);
      assertThat(source.listFrontmatters().blockingGet().keySet()).containsExactly("shared-skill");
      assertThat(source.loadInstructions("shared-skill").blockingGet())
          .isEqualTo("Parent instructions.");
      assertThat(source.listResources("shared-skill", "references").blockingGet())
          .containsExactly("references/nested/guide.txt");
    }
  }

  @Test
  public void doesNotOpenUnsupportedProtocolRoots() throws Exception {
    URL root =
        new URL(
            null,
            "custom:/skills/",
            new URLStreamHandler() {
              @Override
              protected URLConnection openConnection(URL url) {
                throw new AssertionError("Unsupported root must not be opened");
              }
            });
    try (URLClassLoader loader = new URLClassLoader(new URL[] {root}, null)) {
      assertThat(new ClassPathSkillSource("skills/", loader).listFrontmatters().blockingGet())
          .isEmpty();
    }
  }

  private void assertSkill(ClassPathSkillSource source, String name) throws IOException {
    assertThat(source.listFrontmatters().blockingGet().keySet()).containsExactly(name);
    assertThat(source.loadFrontmatter(name).blockingGet().name()).isEqualTo(name);
    assertThat(source.loadInstructions(name).blockingGet()).isEqualTo("Instructions.");
    assertThat(source.listResources(name, "references").blockingGet())
        .containsExactly("references/nested/guide.txt");
    assertThat(source.loadResource(name, "references/nested/guide.txt").blockingGet().read())
        .isEqualTo("Reference text.".getBytes(UTF_8));
  }

  private Path skillJar(String filename, String prefix, String name) throws IOException {
    return skillJar(filename, prefix, name, "Instructions.");
  }

  private Path skillJar(String filename, String prefix, String name, String instructions)
      throws IOException {
    Path path = temporary.newFile(filename).toPath();
    Map<String, String> files =
        ImmutableMap.of(
            prefix + "skills/" + name + "/SKILL.md",
            "---\nname: " + name + "\ndescription: A packaged skill\n---\n" + instructions,
            prefix + "skills/" + name + "/references/nested/guide.txt",
            "Reference text.",
            prefix + "root-skill/SKILL.md",
            "---\nname: root-skill\ndescription: At the classpath root\n---\nInstructions.",
            "outside/hidden-skill/SKILL.md",
            "---\nname: hidden-skill\ndescription: Not in the classpath root\n---\nHidden.");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
      for (Map.Entry<String, String> file : files.entrySet()) {
        output.putNextEntry(new JarEntry(file.getKey()));
        output.write(file.getValue().getBytes(UTF_8));
        output.closeEntry();
      }
    }
    return path;
  }

  private static URL jarRoot(Path jar, String prefix) throws IOException {
    return new URL("jar:" + jar.toUri().toURL() + "!/" + prefix);
  }
}
