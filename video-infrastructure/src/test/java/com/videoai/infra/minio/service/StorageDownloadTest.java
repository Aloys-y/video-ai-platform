package com.videoai.infra.minio.service;
import com.videoai.infra.minio.config.MinioConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class StorageDownloadTest {
 @TempDir Path root;
 private ResponseInputStream<GetObjectResponse> response(String body, long length) {
  return new ResponseInputStream<>(GetObjectResponse.builder().contentLength(length).build(), new ByteArrayInputStream(body.getBytes()));
 }
 @Test void truncatedBodyRetriesFromScratch() throws Exception {
  var s3=mock(S3Client.class); var config=new MinioConfig(); config.setBucketName("test");
  when(s3.getObject(any(GetObjectRequest.class))).thenReturn(response("bad",8),response("complete",8));
  var target=root.resolve("video");
  new StorageService(s3,null,config).downloadToFile("key",target,100,Duration.ofSeconds(10),0);
  assertEquals("complete",Files.readString(target)); verify(s3,times(2)).getObject(any(GetObjectRequest.class));
 }
 @Test void retriesAreBoundedAndPartialFileIsRemoved() throws Exception {
  var s3=mock(S3Client.class); var config=new MinioConfig(); config.setBucketName("test");
  when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(i->response("bad",8));
  var target=root.resolve("video");
  assertThrows(IOException.class,()->new StorageService(s3,null,config).downloadToFile("key",target,100,Duration.ofSeconds(10),0));
  assertFalse(Files.exists(target));verify(s3,times(3)).getObject(any(GetObjectRequest.class));
 }
 @Test void existingFileIsNeverDeletedOrRetried() throws Exception {
  var s3=mock(S3Client.class);var config=new MinioConfig();config.setBucketName("test");
  when(s3.getObject(any(GetObjectRequest.class))).thenReturn(response("complete",8));
  var target=Files.writeString(root.resolve("video"),"keep");
  assertThrows(IOException.class,()->new StorageService(s3,null,config).downloadToFile("key",target,100,Duration.ofSeconds(10),0));
  assertEquals("keep",Files.readString(target));verify(s3,times(1)).getObject(any(GetObjectRequest.class));
 }
}
