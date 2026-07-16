/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.memory;

/**
 * @author manoj.kumar
 */
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.mapdb.DataInput2;
import org.mapdb.DataOutput2;
import org.mapdb.serializer.GroupSerializer;

public class VectorGroupSerializer implements GroupSerializer<Vector> {

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

  @Override
  public void valueArraySerialize(DataOutput2 out, Object vals) throws IOException {
    Vector[] vectors = (Vector[]) vals;
    out.writeInt(vectors.length);
    for (Vector vector : vectors) {
      serialize(out, vector);
    }
  }

  @Override
  public Vector[] valueArrayDeserialize(DataInput2 in, int size) throws IOException {
    int length = in.readInt();
    Vector[] vectors = new Vector[length];
    for (int i = 0; i < length; i++) {
      vectors[i] = deserialize(in, -1);
    }
    return vectors;
  }

  @Override
  public Vector valueArrayGet(Object vals, int pos) {
    return ((Vector[]) vals)[pos];
  }

  @Override
  public int valueArraySize(Object vals) {
    return ((Vector[]) vals).length;
  }

  @Override
  public Object valueArrayEmpty() {
    return new Vector[0];
  }

  @Override
  public Object valueArrayPut(Object vals, int pos, Vector newValue) {
    Vector[] vectors = (Vector[]) vals;
    Vector[] newArray = new Vector[vectors.length + 1];
    System.arraycopy(vectors, 0, newArray, 0, pos);
    newArray[pos] = newValue;
    System.arraycopy(vectors, pos, newArray, pos + 1, vectors.length - pos);
    return newArray;
  }

  @Override
  public Object valueArrayUpdateVal(Object vals, int pos, Vector newValue) {
    Vector[] vectors = (Vector[]) vals;
    vectors[pos] = newValue;
    return vectors;
  }

  @Override
  public Object valueArrayFromArray(Object[] objects) {
    return objects;
  }

  @Override
  public Object valueArrayCopyOfRange(Object vals, int from, int to) {
    Vector[] vectors = (Vector[]) vals;
    Vector[] newArray = new Vector[to - from];
    System.arraycopy(vectors, from, newArray, 0, to - from);
    return newArray;
  }

  @Override
  public Object valueArrayDeleteValue(Object vals, int pos) {
    Vector[] vectors = (Vector[]) vals;
    pos = pos - 1;

    // Check if the array is empty or the position is out of bounds
    if (vectors.length == 0 || pos < 0 || pos >= vectors.length) {
      throw new ArrayIndexOutOfBoundsException("Position out of bounds or array is empty");
    }

    // Create a new array with one less element
    Vector[] newArray = new Vector[vectors.length - 1];

    // Copy the elements before the position
    if (pos > 0) {
      System.arraycopy(vectors, 0, newArray, 0, pos);
    }

    // Copy the elements after the position
    if (pos < vectors.length - 1) {
      System.arraycopy(vectors, pos + 1, newArray, pos, vectors.length - pos - 1);
    }

    return newArray;
  }

  @Override
  public int valueArraySearch(Object o, Vector a) {
    Vector[] array = (Vector[]) o;
    for (int i = 0; i < array.length; i++) {
      if (array[i].getId().equals(a.getId())) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public int valueArraySearch(Object o, Vector a, Comparator comparator) {
    Vector[] array = (Vector[]) o;
    return Arrays.binarySearch(array, a, comparator);
  }
}
