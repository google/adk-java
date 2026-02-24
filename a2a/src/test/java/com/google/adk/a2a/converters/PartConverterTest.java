package com.google.adk.a2a.converters;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.a2a.spec.DataPart;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
import io.a2a.spec.FileWithUri;
import io.a2a.spec.TextPart;
import java.util.Base64;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PartConverterTest {

  @Test
  public void toGenaiPart_withNullPart_returnsEmpty() {
    assertThat(PartConverter.toGenaiPart(null)).isEmpty();
  }

  @Test
  public void toGenaiPart_withTextPart_returnsGenaiTextPart() {
    TextPart textPart = new TextPart("Hello");

    Optional<Part> result = PartConverter.toGenaiPart(textPart);

    assertThat(result).isPresent();
    assertThat(result.get().text()).hasValue("Hello");
  }

  @Test
  public void toGenaiPart_withFilePartUri_returnsGenaiFilePart() {
    FilePart filePart = new FilePart(new FileWithUri("text/plain", "file.txt", "http://file.txt"));

    Optional<Part> result = PartConverter.toGenaiPart(filePart);

    assertThat(result).isPresent();
    assertThat(result.get().fileData()).isPresent();
    FileData fileData = result.get().fileData().get();
    assertThat(fileData.mimeType()).hasValue("text/plain");
    assertThat(fileData.fileUri()).hasValue("http://file.txt");
  }

  @Test
  public void toGenaiPart_withFilePartBytes_returnsGenaiBlobPart() {
    byte[] bytes = "file content".getBytes(UTF_8);
    String encoded = Base64.getEncoder().encodeToString(bytes);
    FilePart filePart = new FilePart(new FileWithBytes("text/plain", "file.txt", encoded));

    Optional<Part> result = PartConverter.toGenaiPart(filePart);

    assertThat(result).isPresent();
    assertThat(result.get().inlineData()).isPresent();
    Blob blob = result.get().inlineData().get();
    assertThat(blob.mimeType()).hasValue("text/plain");
    assertThat(blob.data().get()).isEqualTo(bytes);
  }

  @Test
  public void toGenaiPart_withFilePartBytes_handlesNullBytes() {
    FilePart filePart = new FilePart(new FileWithBytes("text/plain", "file.txt", null));
    assertThat(PartConverter.toGenaiPart(filePart)).isEmpty();
  }

  @Test
  public void toGenaiPart_withFilePartBytes_handlesInvalidBase64() {
    FilePart filePart =
        new FilePart(new FileWithBytes("text/plain", "file.txt", "invalid-base64!"));
    assertThat(PartConverter.toGenaiPart(filePart)).isEmpty();
  }

  @Test
  public void toGenaiPart_withDataPartFunctionCall_returnsGenaiFunctionCallPart() {
    ImmutableMap<String, Object> data =
        ImmutableMap.of("name", "func", "id", "1", "args", ImmutableMap.of());
    DataPart dataPart =
        new DataPart(
            data,
            ImmutableMap.of(
                PartConverter.A2A_DATA_PART_METADATA_TYPE_KEY,
                PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_CALL));

    Optional<Part> result = PartConverter.toGenaiPart(dataPart);

    assertThat(result).isPresent();
    assertThat(result.get().functionCall()).isPresent();
    FunctionCall functionCall = result.get().functionCall().get();
    assertThat(functionCall.name()).hasValue("func");
    assertThat(functionCall.id()).hasValue("1");
    assertThat(functionCall.args()).hasValue(ImmutableMap.of());
  }

  @Test
  public void toGenaiPart_withDataPartFunctionCallByNameAndArgs_returnsGenaiFunctionCallPart() {
    ImmutableMap<String, Object> data =
        ImmutableMap.of("name", "func", "id", "1", "args", ImmutableMap.of("param", "value"));
    DataPart dataPart = new DataPart(data, null);

    Optional<Part> result = PartConverter.toGenaiPart(dataPart);

    assertThat(result).isPresent();
    assertThat(result.get().functionCall()).isPresent();
    FunctionCall functionCall = result.get().functionCall().get();
    assertThat(functionCall.name()).hasValue("func");
    assertThat(functionCall.id()).hasValue("1");
    assertThat(functionCall.args()).hasValue(ImmutableMap.of("param", "value"));
  }

  @Test
  public void toGenaiPart_withDataPartFunctionResponse_returnsGenaiFunctionResponsePart() {
    ImmutableMap<String, Object> data =
        ImmutableMap.of("name", "func", "id", "1", "response", ImmutableMap.of());
    DataPart dataPart =
        new DataPart(
            data,
            ImmutableMap.of(
                PartConverter.A2A_DATA_PART_METADATA_TYPE_KEY,
                PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_RESPONSE));

    Optional<Part> result = PartConverter.toGenaiPart(dataPart);

    assertThat(result).isPresent();
    assertThat(result.get().functionResponse()).isPresent();
    FunctionResponse functionResponse = result.get().functionResponse().get();
    assertThat(functionResponse.name()).hasValue("func");
    assertThat(functionResponse.id()).hasValue("1");
    assertThat(functionResponse.response()).hasValue(ImmutableMap.of());
  }

  @Test
  public void
      toGenaiPart_withDataPartFunctionResponseByNameAndResponse_returnsGenaiFunctionResponsePart() {
    ImmutableMap<String, Object> data =
        ImmutableMap.of("name", "func", "id", "1", "response", ImmutableMap.of("result", "value"));
    DataPart dataPart = new DataPart(data, null);

    Optional<Part> result = PartConverter.toGenaiPart(dataPart);

    assertThat(result).isPresent();
    assertThat(result.get().functionResponse()).isPresent();
    FunctionResponse functionResponse = result.get().functionResponse().get();
    assertThat(functionResponse.name()).hasValue("func");
    assertThat(functionResponse.id()).hasValue("1");
    assertThat(functionResponse.response()).hasValue(ImmutableMap.of("result", "value"));
  }

  @Test
  public void toGenaiPart_withOtherDataPart_returnsGenaiTextPartWithJson() {
    ImmutableMap<String, Object> data = ImmutableMap.of("key", "value");
    DataPart dataPart = new DataPart(data, null);

    Optional<Part> result = PartConverter.toGenaiPart(dataPart);

    assertThat(result).isPresent();
    assertThat(result.get().text()).hasValue("{\"key\":\"value\"}");
  }

  @Test
  public void toGenaiParts_convertsAllSupportedParts() {
    ImmutableList<io.a2a.spec.Part<?>> a2aParts =
        ImmutableList.of(
            new TextPart("text"),
            new FilePart(new FileWithUri("text/plain", "file.txt", "http://file.txt")));

    ImmutableList<Part> result = PartConverter.toGenaiParts(a2aParts);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).text()).hasValue("text");
    assertThat(result.get(1).fileData()).isPresent();
  }

  @Test
  public void convertGenaiPartToA2aPart_withNullPart_returnsEmpty() {
    assertThat(PartConverter.convertGenaiPartToA2aPart(null)).isEmpty();
  }

  @Test
  public void convertGenaiPartToA2aPart_withTextPart_returnsEmpty() {
    Part part = Part.builder().text("text").build();
    assertThat(PartConverter.convertGenaiPartToA2aPart(part)).isEmpty();
  }

  @Test
  public void convertGenaiPartToA2aPart_withFunctionCallPart_returnsDataPart() {
    Part part =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("func")
                    .id("1")
                    .args(ImmutableMap.of("param", "value"))
                    .build())
            .build();

    Optional<DataPart> result = PartConverter.convertGenaiPartToA2aPart(part);

    assertThat(result).isPresent();
    DataPart dataPart = result.get();
    assertThat(dataPart.getData())
        .containsExactly("name", "func", "id", "1", "args", ImmutableMap.of("param", "value"));
    assertThat(dataPart.getMetadata())
        .containsEntry(
            PartConverter.A2A_DATA_PART_METADATA_TYPE_KEY,
            PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_CALL);
  }

  @Test
  public void convertGenaiPartToA2aPart_withFunctionResponsePart_returnsDataPart() {
    Part part =
        Part.builder()
            .functionResponse(
                FunctionResponse.builder()
                    .name("func")
                    .id("1")
                    .response(ImmutableMap.of("result", "value"))
                    .build())
            .build();

    Optional<DataPart> result = PartConverter.convertGenaiPartToA2aPart(part);

    assertThat(result).isPresent();
    DataPart dataPart = result.get();
    assertThat(dataPart.getData())
        .containsExactly("name", "func", "id", "1", "response", ImmutableMap.of("result", "value"));
    assertThat(dataPart.getMetadata())
        .containsEntry(
            PartConverter.A2A_DATA_PART_METADATA_TYPE_KEY,
            PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_RESPONSE);
  }

  @Test
  public void fromGenaiPart_withNullPart_returnsEmpty() {
    assertThat(PartConverter.fromGenaiPart(null)).isEmpty();
  }

  @Test
  public void fromGenaiPart_withTextPart_returnsTextPart() {
    Part part = Part.builder().text("text").build();

    Optional<io.a2a.spec.Part<?>> result = PartConverter.fromGenaiPart(part);

    assertThat(result).isPresent();
    assertThat(result.get()).isInstanceOf(TextPart.class);
    assertThat(((TextPart) result.get()).getText()).isEqualTo("text");
  }

  @Test
  public void fromGenaiPart_withFileDataPart_returnsFilePartWithUri() {
    Part part =
        Part.builder()
            .fileData(FileData.builder().mimeType("text/plain").fileUri("http://file.txt").build())
            .build();

    Optional<io.a2a.spec.Part<?>> result = PartConverter.fromGenaiPart(part);

    assertThat(result).isPresent();
    assertThat(result.get()).isInstanceOf(FilePart.class);
    FilePart filePart = (FilePart) result.get();
    assertThat(filePart.getFile()).isInstanceOf(FileWithUri.class);
    FileWithUri fileWithUri = (FileWithUri) filePart.getFile();
    assertThat(fileWithUri.mimeType()).isEqualTo("text/plain");
    assertThat(fileWithUri.uri()).isEqualTo("http://file.txt");
  }

  @Test
  public void fromGenaiPart_withInlineDataPart_returnsFilePartWithBytes() {
    byte[] bytes = "content".getBytes(UTF_8);
    Part part =
        Part.builder()
            .inlineData(Blob.builder().mimeType("text/plain").data(bytes).build())
            .build();

    Optional<io.a2a.spec.Part<?>> result = PartConverter.fromGenaiPart(part);

    assertThat(result).isPresent();
    assertThat(result.get()).isInstanceOf(FilePart.class);
    FilePart filePart = (FilePart) result.get();
    assertThat(filePart.getFile()).isInstanceOf(FileWithBytes.class);
    FileWithBytes fileWithBytes = (FileWithBytes) filePart.getFile();
    assertThat(fileWithBytes.mimeType()).isEqualTo("text/plain");
    assertThat(Base64.getDecoder().decode(fileWithBytes.bytes())).isEqualTo(bytes);
  }

  @Test
  public void fromGenaiPart_withFunctionCallPart_returnsDataPart() {
    Part part =
        Part.builder()
            .functionCall(
                FunctionCall.builder().name("func").id("1").args(ImmutableMap.of()).build())
            .build();

    Optional<io.a2a.spec.Part<?>> result = PartConverter.fromGenaiPart(part);

    assertThat(result).isPresent();
    assertThat(result.get()).isInstanceOf(DataPart.class);
    DataPart dataPart = (DataPart) result.get();
    assertThat(dataPart.getData())
        .containsExactly("name", "func", "id", "1", "args", ImmutableMap.of());
    assertThat(dataPart.getMetadata())
        .containsEntry(
            PartConverter.A2A_DATA_PART_METADATA_TYPE_KEY,
            PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_CALL);
  }

  @Test
  public void fromGenaiPart_withFunctionResponsePart_returnsDataPart() {
    Part part =
        Part.builder()
            .functionResponse(
                FunctionResponse.builder().name("func").id("1").response(ImmutableMap.of()).build())
            .build();

    Optional<io.a2a.spec.Part<?>> result = PartConverter.fromGenaiPart(part);

    assertThat(result).isPresent();
    assertThat(result.get()).isInstanceOf(DataPart.class);
    DataPart dataPart = (DataPart) result.get();
    assertThat(dataPart.getData())
        .containsExactly("name", "func", "id", "1", "response", ImmutableMap.of());
    assertThat(dataPart.getMetadata())
        .containsEntry(
            PartConverter.A2A_DATA_PART_METADATA_TYPE_KEY,
            PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_RESPONSE);
  }

  @Test
  public void toGenaiPart_dataPartWithEmptyStringCoercedToEmptyMap() {
    ImmutableMap<String, Object> data = ImmutableMap.of("name", "func", "id", "1", "args", "");
    DataPart dataPart = new DataPart(data, null);

    Optional<Part> result = PartConverter.toGenaiPart(dataPart);

    assertThat(result).isPresent();
    assertThat(result.get().functionCall()).isPresent();
    assertThat(result.get().functionCall().get().args()).hasValue(ImmutableMap.of());
  }

  @Test
  public void toGenaiPart_dataPartWithNonMapCoercedToMap() {
    ImmutableMap<String, Object> data = ImmutableMap.of("name", "func", "id", "1", "args", 123);
    DataPart dataPart = new DataPart(data, null);

    Optional<Part> result = PartConverter.toGenaiPart(dataPart);

    assertThat(result).isPresent();
    assertThat(result.get().functionCall()).isPresent();
    assertThat(result.get().functionCall().get().args()).hasValue(ImmutableMap.of("value", 123));
  }
}
