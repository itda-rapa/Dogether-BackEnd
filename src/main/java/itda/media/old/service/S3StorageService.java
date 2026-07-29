//package itda.media.service;
//
//import itda.common.properties.S3Properties;
//import java.time.Duration;
//import org.springframework.stereotype.Service;
//import software.amazon.awssdk.services.s3.S3Client;
//import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
//import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
//import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
//import software.amazon.awssdk.services.s3.model.PutObjectRequest;
//import software.amazon.awssdk.services.s3.model.GetObjectRequest;
//import software.amazon.awssdk.services.s3.presigner.S3Presigner;
//import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
//import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
//
//@Service
//public class S3StorageService {
//
//    private final S3Client s3Client;
//    private final S3Presigner s3Presigner;
//    private final S3Properties properties;
//
//    public S3StorageService(
//            S3Client s3Client,
//            S3Presigner s3Presigner,
//            S3Properties properties
//    ) {
//        this.s3Client = s3Client;
//        this.s3Presigner = s3Presigner;
//        this.properties = properties;
//    }
//
//    public String createUploadUrl(
//            String objectKey,
//            String contentType,
//            long sizeBytes,
//            Duration ttl
//    ) {
//        PutObjectRequest putRequest = PutObjectRequest.builder()
//                .bucket(properties.bucket())
//                .key(objectKey)
//                .contentType(contentType)
//                .contentLength(sizeBytes)
//                .build();
//
//        return s3Presigner.presignPutObject(
//                        PutObjectPresignRequest.builder()
//                                .signatureDuration(ttl)
//                                .putObjectRequest(putRequest)
//                                .build()
//                )
//                .url()
//                .toString();
//    }
//
//    public HeadObjectResponse head(String objectKey) {
//        return s3Client.headObject(
//                HeadObjectRequest.builder()
//                        .bucket(properties.bucket())
//                        .key(objectKey)
//                        .build()
//        );
//    }
//
//    public String createViewUrl(String objectKey, Duration ttl) {
//        GetObjectRequest getRequest = GetObjectRequest.builder()
//                .bucket(properties.bucket())
//                .key(objectKey)
//                .build();
//
//        return s3Presigner.presignGetObject(
//                        GetObjectPresignRequest.builder()
//                                .signatureDuration(ttl)
//                                .getObjectRequest(getRequest)
//                                .build()
//                )
//                .url()
//                .toString();
//    }
//
//    public void delete(String objectKey) {
//        s3Client.deleteObject(
//                DeleteObjectRequest.builder()
//                        .bucket(properties.bucket())
//                        .key(objectKey)
//                        .build()
//        );
//    }
//}
