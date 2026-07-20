/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.memory;

/**
 * @author manoj.kumar
 */
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.mapdb.DataInput2;
import org.mapdb.DataOutput2;
import org.mapdb.Serializer;

public class VectorSerializer implements Serializer<Vector> {

  @Override
  public int fixedSize() {
    return -1; // Variable size due to content and metadata
  }

  @Override
  public void serialize(DataOutput2 out, Vector vector) throws IOException {
    // Serialize the ID
    out.writeUTF(vector.getId());

    // Serialize the content
    out.writeUTF(vector.getContent());

    // Serialize the embedding array
    out.writeInt(vector.getEmbedding().length);
    for (double value : vector.getEmbedding()) {
      out.writeDouble(value);
    }

    // Serialize the metadata
    out.writeInt(vector.getMetadata().size());
    for (Map.Entry<String, Object> entry : vector.getMetadata().entrySet()) {
      out.writeUTF(entry.getKey());
      byte[] bytes = entry.getValue().toString().getBytes(); // Serialize metadata value as bytes
      out.writeInt(bytes.length);
      out.write(bytes);
    }
  }

  @Override
  public Vector deserialize(DataInput2 in, int available) throws IOException {
    // Deserialize the ID
    String id = in.readUTF();

    // Deserialize the content
    String content = in.readUTF();

    // Deserialize the embedding array
    int embeddingLength = in.readInt();
    double[] embedding = new double[embeddingLength];
    for (int i = 0; i < embeddingLength; i++) {
      embedding[i] = in.readDouble();
    }

    // Deserialize the metadata
    int metadataSize = in.readInt();
    Map<String, Object> metadata = new HashMap<>();
    for (int i = 0; i < metadataSize; i++) {
      String key = in.readUTF();
      int length = in.readInt();
      byte[] bytes = new byte[length];
      in.readFully(bytes);
      metadata.put(key, new String(bytes)); // Deserialize metadata value from bytes
    }

    return new Vector(id, content, embedding, metadata);
  }
}
