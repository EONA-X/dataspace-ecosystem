package org.eclipse.edc.dse.telemetry.services.storage;

import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers
class S3CompatibleStorageServiceIntegrationTest {

    private static final int S3_API_PORT = 3900;
    private static final String BUCKET_NAME = "test-bucket";
    private static final String ACCESS_KEY = "garagetest";
    private static final String SECRET_KEY = "garagetestgaragetest123";
    private static final String REGION = "us-east-1";

    // minio/minio was pulled from Docker Hub (repository now 404s there, see #605), and MinIO's
    // licensing/distribution direction pushed the move off it entirely. GarageHQ is our own mirror
    // of dxflrs/garage on Harbor. Single-node bootstrap (layout + default key/bucket) is handled by
    // the --single-node/--default-bucket server flags (Garage >= 2.3.0), config in garage-test.toml.
    @Container
    private static final GenericContainer<?> GARAGE = new GenericContainer<>(
            DockerImageName.parse("n164hw2l.gra7.container-registry.ovh.net/tooling/garage:v2.4.1"))
            .withExposedPorts(S3_API_PORT)
            .withCopyFileToContainer(MountableFile.forClasspathResource("garage-test.toml"), "/etc/garage.toml")
            .withEnv("GARAGE_DEFAULT_ACCESS_KEY", ACCESS_KEY)
            .withEnv("GARAGE_DEFAULT_SECRET_KEY", SECRET_KEY)
            .withEnv("GARAGE_DEFAULT_BUCKET", BUCKET_NAME)
            .withCommand("/garage", "server", "--single-node", "--default-bucket")
            .waitingFor(Wait.forLogMessage(".*S3 API server listening.*\\n", 1));

    private S3CompatibleStorageService storageService;
    private Monitor monitor;

    @BeforeEach
    void setUp() {
        monitor = mock(Monitor.class);
        storageService = new S3CompatibleStorageService(
                monitor,
                s3Url(),
                ACCESS_KEY,
                SECRET_KEY,
                BUCKET_NAME,
                REGION
        );
    }

    @AfterEach
    void tearDown() {
        storageService.close();
    }

    @Test
    void shouldUploadAndDownloadFile() throws Exception {
        byte[] data = "Hello MinIO integration test".getBytes(StandardCharsets.UTF_8);
        String path = uniquePath("upload-download");

        String url = storageService.upload(path, data);

        assertThat(url).contains(BUCKET_NAME).contains(path);

        try (InputStream is = storageService.download(path)) {
            byte[] downloaded = is.readAllBytes();
            assertThat(new String(downloaded, StandardCharsets.UTF_8))
                    .isEqualTo("Hello MinIO integration test");
        }
    }

    @Test
    void shouldOverwriteExistingObject() throws Exception {
        String path = uniquePath("overwrite");

        storageService.upload(path, "original".getBytes(StandardCharsets.UTF_8));
        storageService.upload(path, "updated".getBytes(StandardCharsets.UTF_8));

        try (InputStream is = storageService.download(path)) {
            assertThat(new String(is.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("updated");
        }
    }

    @Test
    void shouldGeneratePresignedUrlWithCorrectFormat() {
        String path = uniquePath("presigned-format");
        storageService.upload(path, "Presigned URL test".getBytes(StandardCharsets.UTF_8));

        String presignedUrl = storageService.generatePresignedUrl(path, Duration.ofMinutes(10));

        assertThat(presignedUrl)
                .contains(BUCKET_NAME)
                .contains(path)
                .contains("X-Amz-Signature");
    }

    @Test
    void shouldGeneratePresignedUrlThatIsActuallyUsable() throws Exception {
        byte[] data = "Presigned content".getBytes(StandardCharsets.UTF_8);
        String path = uniquePath("presigned-usable");
        storageService.upload(path, data);

        String presignedUrl = storageService.generatePresignedUrl(path, Duration.ofMinutes(10));

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(presignedUrl))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("Presigned content");
    }

    @Test
    void shouldGeneratePresignedUrlForNonExistentObjectWithoutThrowingException() throws Exception {
        String path = uniquePath("presigned-nonexistent");

        String presignedUrl = storageService.generatePresignedUrl(path, Duration.ofMinutes(10));

        assertThatCode(() -> storageService.generatePresignedUrl(path, Duration.ofMinutes(10)))
                .doesNotThrowAnyException();

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(presignedUrl))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void shouldCreateBucketIfItDoesNotExist() {
        String newBucket = "new-bucket-" + UUID.randomUUID();

        try (S3CompatibleStorageService serviceWithNewBucket = new S3CompatibleStorageService(
                monitor,
                s3Url(),
                ACCESS_KEY,
                SECRET_KEY,
                newBucket,
                REGION
        )) {
            String path = uniquePath("bucket-creation");
            assertThat(serviceWithNewBucket.upload(path, "data".getBytes(StandardCharsets.UTF_8)))
                    .contains(newBucket);
        }
    }

    @Test
    void shouldThrowStorageExceptionWhenDownloadingNonExistentPath() {
        String nonExistentPath = uniquePath("does-not-exist");

        assertThatThrownBy(() -> storageService.download(nonExistentPath))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(nonExistentPath);
    }

    private String uniquePath(String baseName) {
        return "reports/" + baseName + "-" + UUID.randomUUID() + ".csv";
    }

    private static String s3Url() {
        return "http://" + GARAGE.getHost() + ":" + GARAGE.getMappedPort(S3_API_PORT);
    }
}
