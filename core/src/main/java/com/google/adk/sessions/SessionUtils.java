package com.google.adk.sessions;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Utility functions for session service. */
public final class SessionUtils {

  public SessionUtils() {}

  public static Content encodeContent(Content content) {
    List<Part> encodedParts = new ArrayList<>();
    for (Part part : content.parts().orElse(ImmutableList.of())) {
      boolean isInlineDataPresent = false;
      if (part.inlineData() != null) {
        Optional<Blob> inlineDataOptional = part.inlineData();
        if (inlineDataOptional.isPresent()) {
          Blob inlineDataBlob = inlineDataOptional.get();
          Optional<byte[]> dataOptional = inlineDataBlob.data();
          if (dataOptional.isPresent()) {
            byte[] dataBytes = dataOptional.get();
            byte[] encodedData = Base64.getEncoder().encode(dataBytes);
            encodedParts.add(
                part.toBuilder().inlineData(Blob.builder().data(encodedData).build()).build());
            isInlineDataPresent = true;
          }
        }
      }
      if (!isInlineDataPresent) {
        encodedParts.add(part);
      }
    }
    return Content.builder().parts(encodedParts).build();
  }

  public static Content decodeContent(Content content) {
    List<Part> decodedParts = new ArrayList<>();
    for (Part part : content.parts().orElse(ImmutableList.of())) {
      boolean isInlineDataPresent = false;
      if (part.inlineData() != null) {
        Optional<Blob> inlineDataOptional = part.inlineData();
        if (inlineDataOptional.isPresent()) {
          Blob inlineDataBlob = inlineDataOptional.get();
          Optional<byte[]> dataOptional = inlineDataBlob.data();
          if (dataOptional.isPresent()) {
            byte[] dataBytes = dataOptional.get();
            byte[] decodedData = Base64.getDecoder().decode(dataBytes);
            decodedParts.add(
                part.toBuilder().inlineData(Blob.builder().data(decodedData).build()).build());
            isInlineDataPresent = true;
          }
        }
      }
      if (!isInlineDataPresent) {
        decodedParts.add(part);
      }
    }
    return Content.builder().parts(decodedParts).build();
  }
}
