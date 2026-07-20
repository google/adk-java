/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.a2a.converters.PartConverter;
import com.google.genai.types.Blob;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
import io.a2a.spec.FileWithUri;
import io.a2a.spec.TextPart;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Tests for image, audio, and video support in A2A. */
class MediaSupportTest {

  // Sample base64 encoded data (1x1 pixel PNG)
  private static final String SAMPLE_IMAGE_BASE64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  // Sample base64 encoded data (minimal WAV file)
  private static final String SAMPLE_AUDIO_BASE64 =
      "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=";

  // Sample base64 encoded data (minimal MP4 file)
  private static final String SAMPLE_VIDEO_BASE64 =
      "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAAAZhtZGF0AAACrgYF//+q3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE0OCByMTkzOCA1YzY1MDk1IC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAxNyAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MzoweDExMyBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTEgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0zIGJfcHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2JpYXM9MCBkaXJlY3Q9MSB3ZWlnaHRiPTEgb3Blbl9nb3A9MCB3ZWlnaHRwPTIga2V5aW50PTI1MCBrZXlpbnRfbWluPTI1IHNjZW5lY3V0PTQwIGludHJhX3JlZnJlc2g9MCByY19sb29rYWhlYWQ9NDAgcmM9Y3JmIG1idHJlZT0xIGRyYWZ0PTEgdHRfcGVyZnJhbWU9MSB0cmVfbG9va2FoZWFkPTQw";

  @Test
  void testTextPart_conversion() {
    // A2A TextPart to GenAI Part
    TextPart textPart = new TextPart("Hello, world!");
    Part genaiPart = PartConverter.toGenaiPart(textPart);

    assertThat(genaiPart.text()).isPresent();
    assertThat(genaiPart.text().get()).isEqualTo("Hello, world!");

    // GenAI Part to A2A TextPart
    Part genaiTextPart = Part.builder().text("Hello, world!").build();
    io.a2a.spec.Part<?> a2aPart = PartConverter.fromGenaiPart(genaiTextPart, false);

    assertThat(a2aPart).isNotNull();
    assertThat(a2aPart).isInstanceOf(TextPart.class);
    assertThat(((TextPart) a2aPart).getText()).isEqualTo("Hello, world!");
  }

  @Test
  void testImageFilePart_withUri() {
    // A2A FilePart (Image) with URI to GenAI Part
    FilePart imagePart =
        new FilePart(new FileWithUri("image/png", "test.png", "https://example.com/image.png"));

    Part genaiPart = PartConverter.toGenaiPart(imagePart);

    assertThat(genaiPart.fileData()).isPresent();
    FileData fileData = genaiPart.fileData().get();
    assertThat(fileData.fileUri()).isPresent();
    assertThat(fileData.fileUri().get()).isEqualTo("https://example.com/image.png");
    assertThat(fileData.mimeType()).isPresent();
    assertThat(fileData.mimeType().get()).isEqualTo("image/png");
  }

  @Test
  void testImageFilePart_withBytes() {
    // A2A FilePart (Image) with base64 bytes to GenAI Part
    FilePart imagePart =
        new FilePart(new FileWithBytes("image/png", "test.png", SAMPLE_IMAGE_BASE64));

    Part genaiPart = PartConverter.toGenaiPart(imagePart);

    assertThat(genaiPart.inlineData()).isPresent();
    Blob blob = genaiPart.inlineData().get();
    assertThat(blob.mimeType()).isPresent();
    assertThat(blob.mimeType().get()).isEqualTo("image/png");
    assertThat(blob.data()).isPresent();
    assertThat(blob.data().get().length).isGreaterThan(0);
  }

  @Test
  void testAudioFilePart_withUri() {
    // A2A FilePart (Audio) with URI to GenAI Part
    FilePart audioPart =
        new FilePart(new FileWithUri("audio/mpeg", "test.mp3", "https://example.com/audio.mp3"));

    Part genaiPart = PartConverter.toGenaiPart(audioPart);

    assertThat(genaiPart.fileData()).isPresent();
    FileData fileData = genaiPart.fileData().get();
    assertThat(fileData.mimeType()).isPresent();
    assertThat(fileData.mimeType().get()).isEqualTo("audio/mpeg");
  }

  @Test
  void testAudioFilePart_withBytes() {
    // A2A FilePart (Audio) with base64 bytes to GenAI Part
    FilePart audioPart =
        new FilePart(new FileWithBytes("audio/wav", "test.wav", SAMPLE_AUDIO_BASE64));

    Part genaiPart = PartConverter.toGenaiPart(audioPart);

    assertThat(genaiPart.inlineData()).isPresent();
    Blob blob = genaiPart.inlineData().get();
    assertThat(blob.mimeType()).isPresent();
    assertThat(blob.mimeType().get()).isEqualTo("audio/wav");
  }

  @Test
  void testVideoFilePart_withUri() {
    // A2A FilePart (Video) with URI to GenAI Part
    FilePart videoPart =
        new FilePart(new FileWithUri("video/mp4", "test.mp4", "https://example.com/video.mp4"));

    Part genaiPart = PartConverter.toGenaiPart(videoPart);

    assertThat(genaiPart.fileData()).isPresent();
    FileData fileData = genaiPart.fileData().get();
    assertThat(fileData.mimeType()).isPresent();
    assertThat(fileData.mimeType().get()).isEqualTo("video/mp4");
  }

  @Test
  void testVideoFilePart_withBytes() {
    // A2A FilePart (Video) with base64 bytes to GenAI Part
    FilePart videoPart =
        new FilePart(new FileWithBytes("video/mp4", "test.mp4", SAMPLE_VIDEO_BASE64));

    Part genaiPart = PartConverter.toGenaiPart(videoPart);

    assertThat(genaiPart.inlineData()).isPresent();
    Blob blob = genaiPart.inlineData().get();
    assertThat(blob.mimeType()).isPresent();
    assertThat(blob.mimeType().get()).isEqualTo("video/mp4");
  }

  @Test
  void testGenAIImagePart_toA2A() {
    // GenAI Part (Image FileData) to A2A FilePart
    Part genaiImagePart =
        Part.builder()
            .fileData(
                FileData.builder()
                    .fileUri("https://example.com/image.jpg")
                    .mimeType("image/jpeg")
                    .displayName("photo.jpg")
                    .build())
            .build();

    io.a2a.spec.Part<?> a2aPart = PartConverter.fromGenaiPart(genaiImagePart, false);

    assertThat(a2aPart).isNotNull();
    assertThat(a2aPart).isInstanceOf(FilePart.class);
    FilePart filePart = (FilePart) a2aPart;
    assertThat(filePart.getFile()).isInstanceOf(FileWithUri.class);
    FileWithUri fileWithUri = (FileWithUri) filePart.getFile();
    assertThat(fileWithUri.uri()).isEqualTo("https://example.com/image.jpg");
    assertThat(fileWithUri.mimeType()).isEqualTo("image/jpeg");
  }

  @Test
  void testGenAIAudioPart_toA2A() {
    // GenAI Part (Audio InlineData) to A2A FilePart
    byte[] audioBytes = Base64.getDecoder().decode(SAMPLE_AUDIO_BASE64);
    Part genaiAudioPart =
        Part.builder()
            .inlineData(
                Blob.builder()
                    .data(audioBytes)
                    .mimeType("audio/wav")
                    .displayName("sound.wav")
                    .build())
            .build();

    io.a2a.spec.Part<?> a2aPart = PartConverter.fromGenaiPart(genaiAudioPart, false);

    assertThat(a2aPart).isNotNull();
    assertThat(a2aPart).isInstanceOf(FilePart.class);
    FilePart filePart = (FilePart) a2aPart;
    assertThat(filePart.getFile()).isInstanceOf(FileWithBytes.class);
    FileWithBytes fileWithBytes = (FileWithBytes) filePart.getFile();
    assertThat(fileWithBytes.mimeType()).isEqualTo("audio/wav");
    assertThat(fileWithBytes.bytes()).isEqualTo(SAMPLE_AUDIO_BASE64);
  }

  @Test
  void testGenAIVideoPart_toA2A() {
    // GenAI Part (Video FileData) to A2A FilePart
    Part genaiVideoPart =
        Part.builder()
            .fileData(
                FileData.builder()
                    .fileUri("https://example.com/video.mp4")
                    .mimeType("video/mp4")
                    .displayName("movie.mp4")
                    .build())
            .build();

    io.a2a.spec.Part<?> a2aPart = PartConverter.fromGenaiPart(genaiVideoPart, false);

    assertThat(a2aPart).isNotNull();
    assertThat(a2aPart).isInstanceOf(FilePart.class);
    FilePart filePart = (FilePart) a2aPart;
    assertThat(filePart.getFile()).isInstanceOf(FileWithUri.class);
    FileWithUri fileWithUri = (FileWithUri) filePart.getFile();
    assertThat(fileWithUri.mimeType()).isEqualTo("video/mp4");
  }

  @Test
  void testMultipleMediaTypes_inMessage() {
    // Test that multiple media types can be converted together
    FilePart imagePart =
        new FilePart(new FileWithUri("image/jpeg", "photo.jpg", "https://example.com/photo.jpg"));
    FilePart audioPart =
        new FilePart(new FileWithUri("audio/mpeg", "sound.mp3", "https://example.com/sound.mp3"));
    FilePart videoPart =
        new FilePart(new FileWithUri("video/mp4", "movie.mp4", "https://example.com/movie.mp4"));

    Part imageGenai = PartConverter.toGenaiPart(imagePart);
    Part audioGenai = PartConverter.toGenaiPart(audioPart);
    Part videoGenai = PartConverter.toGenaiPart(videoPart);

    assertThat(imageGenai.fileData().get().mimeType().get()).isEqualTo("image/jpeg");
    assertThat(audioGenai.fileData().get().mimeType().get()).isEqualTo("audio/mpeg");
    assertThat(videoGenai.fileData().get().mimeType().get()).isEqualTo("video/mp4");
  }
}
