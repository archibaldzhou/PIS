package com.pis.grossing;
/** Private immutable object contract. Real upload/staging/storage adapters need separate approval. */
public interface PhotoStore {
    record ObjectInfo(String key,String sha256,int bytes,int width,int height) { }
    ObjectInfo validateSynthetic(String base64);
    byte[] read(String key,String sha256);
    ObjectInfo sample();
}
