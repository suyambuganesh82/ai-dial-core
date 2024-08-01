package com.epam.aidial.core;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.data.AutoSharedData;
import com.epam.aidial.core.data.Bucket;
import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.BlobWriteStream;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.multipart.MultipartForm;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Slf4j
public class FileApiTest extends ResourceBaseTest {

    private static final String TEST_FILE_CONTENT = "Test file content";
    private static final String TEST_FILE_ETAG = "ac79653edeb65ab5563585f2d5f14fe9";

    @Test
    public void testBucket(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", "/v1/bucket")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.json(Bucket.class))
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(new Bucket("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt", null), response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testPerRequestBucket(Vertx vertx, VertxTestContext context) {
        // creating per-request API key with proxyKey1 as originator
        // and proxyKey2 caller
        ApiKeyData projectApiKeyData = apiKeyStore.getApiKeyData("proxyKey1").result();
        ApiKeyData apiKeyData2 = new ApiKeyData();
        apiKeyData2.setOriginalKey(projectApiKeyData.getOriginalKey());

        // set deployment ID for proxyKey2
        apiKeyData2.setSourceDeployment("EPM-RTC-RAIL");
        apiKeyStore.assignPerRequestApiKey(apiKeyData2);

        String apiKey2 = apiKeyData2.getPerRequestKey();

        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify proxyKey1 bucket
            client.get(serverPort, "localhost", "/v1/bucket")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals("{\"bucket\":\"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST\"}", response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));
            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify proxyKey2 bucket
            client.get(serverPort, "localhost", "/v1/bucket")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals("{\"bucket\":\"7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt\"}", response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));
            return promise.future();
        }).andThen((result) -> {
            // verify per-request key bucket and app-data
            client.get(serverPort, "localhost", "/v1/bucket")
                    .putHeader("Api-key", apiKey2)
                    .as(BodyCodec.json(Bucket.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(new Bucket("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                                    "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL"), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testEmptyFilesList(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);

        MetadataBase emptyBucketResponse = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of())
                .setPermissions(ResourceAccessType.ALL);
        client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.json(ResourceFolderMetadata.class))
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertEquals("application/json", response.getHeader(HttpHeaders.CONTENT_TYPE));
                        assertEquals(emptyBucketResponse, response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testMetadataContentType(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);

        MetadataBase emptyBucketResponse = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of())
                .setPermissions(ResourceAccessType.ALL);

        client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                .putHeader("Api-key", "proxyKey2")
                .putHeader(HttpHeaders.ACCEPT, MetadataBase.MIME_TYPE)
                .as(BodyCodec.json(ResourceFolderMetadata.class))
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(MetadataBase.MIME_TYPE, response.getHeader(HttpHeaders.CONTENT_TYPE));
                        assertEquals(emptyBucketResponse, response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testFileNotFound(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.buffer())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(404, response.statusCode());
                        assertNull(response.body());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testInvalidFileUploadUrl(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.string())
                .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                        context.succeeding(response -> {
                            context.verify(() -> {
                                assertEquals(400, response.statusCode());
                                assertEquals("File name is missing", response.body());
                                context.completeNow();
                            });
                        })
                );
    }

    @Test
    public void testInvalidFileUploadUrl2(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.put(serverPort, "localhost", "/v1/files/testbucket/")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.string())
                .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                        context.succeeding(response -> {
                            context.verify(() -> {
                                assertEquals(400, response.statusCode());
                                assertEquals("Url has invalid bucket: files/testbucket/", response.body());
                                context.completeNow();
                            });
                        })
                );
    }

    @Test
    public void testDownloadFromAnotherBucket(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(2);
        WebClient client = WebClient.create(vertx);

        ResourceItemMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt", 17, "text/custom")
                .setEtag(TEST_FILE_ETAG);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );
            return promise.future();
        }).andThen((mapper) -> {
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(403, response.statusCode());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileUploadIntoAnotherBucket(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                .putHeader("Api-key", "proxyKey1")
                .as(BodyCodec.string())
                .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                        context.succeeding(response -> {
                            context.verify(() -> {
                                assertEquals(403, response.statusCode());
                                context.completeNow();
                            });
                        })
                );
    }

    @Test
    public void testFileUploadWithInvalidPath(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        Future.succeededFuture().compose(mapper -> {
            Promise<Void> promise = Promise.promise();
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(400, response.statusCode());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );
            return promise.future();
        }).compose(mapper -> {
            Promise<Void> promise = Promise.promise();
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1./file")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(400, response.statusCode());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );
            return promise.future();
        }).andThen(ignore -> {
            // verify file with invalid path can be downloaded
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(404, response.statusCode());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileUpload(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(4);
        WebClient client = WebClient.create(vertx);

        Set<ResourceAccessType> permissions = ResourceAccessType.ALL;
        MetadataBase emptyFolderResponse = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of())
                .setPermissions(permissions);
        FileMetadata expectedFileMetadata = (FileMetadata) new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "файл.txt", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/%D1%84%D0%B0%D0%B9%D0%BB.txt", 0, "text/custom")
                .setEtag(TEST_FILE_ETAG);
        MetadataBase expectedFolderMetadata = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/",
                        List.of(newFileMetadata(expectedFileMetadata).setPermissions(permissions)))
                .setPermissions(permissions);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(ResourceFolderMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(emptyFolderResponse, response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/%D1%84%D0%B0%D0%B9%D0%BB.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("файл.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    assertEquals(TEST_FILE_ETAG, response.getHeader(HttpHeaders.ETAG));
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // read test file
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/%D1%84%D0%B0%D0%B9%D0%BB.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(TEST_FILE_CONTENT, response.body());
                        assertEquals(TEST_FILE_ETAG, response.getHeader(HttpHeaders.ETAG));
                        checkpoint.flag();
                        promise.complete();
                    })));

            return promise.future();
        }).andThen((result) -> {
            // verify uploaded file can be listed
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(ProxyUtil.MAPPER.writeValueAsString(expectedFolderMetadata), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testBigFileUpload(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(4);
        WebClient client = WebClient.create(vertx);

        byte[] content = new byte[(int) (BlobWriteStream.MIN_PART_SIZE_BYTES * 1.5)];
        String etag = "682fdfa22b3f97021b6d3cc3f00baa2b";
        IntStream.range(0, content.length).forEach(i -> content[i] = (byte) i);
        Set<ResourceAccessType> permissions = ResourceAccessType.ALL;
        FileMetadata expectedFileMetadata = (FileMetadata) new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.bin", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin", content.length, "application/x-binary")
                .setEtag(etag);
        MetadataBase expectedFolderMetadata = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/",
                        List.of(newFileMetadata(expectedFileMetadata).setPermissions(permissions)))
                .setPermissions(permissions);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(ResourceFolderMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(List.of(), response.body().getItems());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.bin", content, "application/x-binary"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    assertEquals(etag, response.getHeader(HttpHeaders.ETAG));

                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // read test file
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.buffer())
                    .send(context.succeeding(response -> context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertArrayEquals(content, response.body().getBytes());
                        assertEquals(etag, response.getHeader(HttpHeaders.ETAG));
                        checkpoint.flag();
                        promise.complete();
                    })));

            return promise.future();
        }).andThen((result) -> {
            // verify uploaded file can be listed
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(ProxyUtil.MAPPER.writeValueAsString(expectedFolderMetadata), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testBigFileEvents(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(4);
        WebClient client = WebClient.create(vertx);
        EventStream events = subscribe("""
                 {
                  "resources": [
                    {
                      "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin"
                    }
                  ]
                 }
                """,
                "Api-key", "proxyKey2");

        byte[] content = new byte[(int) (BlobWriteStream.MIN_PART_SIZE_BYTES * 1.5)];
        IntStream.range(0, content.length).forEach(i -> content[i] = (byte) i);
        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(ResourceFolderMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(List.of(), response.body().getItems());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.bin", content, "application/x-binary"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());

                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // update test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.bin", content, "application/x-binary"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());

                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            // delete test file
            client.delete(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin")
                    .putHeader("Api-key", "proxyKey2")
                    .send(context.succeeding(response -> context.verify(() -> {
                        assertEquals(200, response.statusCode());

                        checkpoint.flag();
                    })));
        });
        verifyJsonNotExact("""
                {
                  "url" : "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin",
                  "action" : "CREATE",
                  "timestamp" : "@ignore"
                }
                """, events.take());
        verifyJsonNotExact("""
                {
                  "url" : "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin",
                  "action" : "UPDATE",
                  "timestamp" : "@ignore"
                }
                """, events.take());
        verifyJsonNotExact("""
                {
                  "url" : "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.bin",
                  "action" : "DELETE",
                  "timestamp" : "@ignore"
                }
                """, events.take());
        events.close();
    }

    @Test
    public void testFileUploadToAppdata(Vertx vertx, VertxTestContext context) {
        // creating per-request API key with proxyKey1 as originator
        // and proxyKey2 caller
        ApiKeyData projectApiKeyData = apiKeyStore.getApiKeyData("proxyKey1").result();
        ApiKeyData apiKeyData2 = new ApiKeyData();
        apiKeyData2.setOriginalKey(projectApiKeyData.getOriginalKey());
        // set deployment ID for proxyKey2
        apiKeyData2.setSourceDeployment("EPM-RTC-RAIL");
        apiKeyStore.assignPerRequestApiKey(apiKeyData2);

        String apiKey2 = apiKeyData2.getPerRequestKey();

        Checkpoint checkpoint = context.checkpoint(4);
        WebClient client = WebClient.create(vertx);

        Set<ResourceAccessType> permissions = EnumSet.allOf(ResourceAccessType.class);
        FileMetadata expectedFileMetadata = (FileMetadata) new FileMetadata("3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                "file.txt", "appdata/EPM-RTC-RAIL", "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/file.txt", 0, "text/custom")
                .setEtag(TEST_FILE_ETAG);
        MetadataBase expectedFolderMetadata = new ResourceFolderMetadata(ResourceType.FILE, "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                        "EPM-RTC-RAIL", "appdata", "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/",
                        List.of(newFileMetadata(expectedFileMetadata).setPermissions(permissions)))
                .setPermissions(permissions);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify per-request key bucket and app-data
            client.get(serverPort, "localhost", "/v1/bucket")
                    .putHeader("Api-key", apiKey2)
                    .as(BodyCodec.json(Bucket.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(new Bucket("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                                    "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL"), response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));
            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload file to the app data folder with pre-request api key
            client.put(serverPort, "localhost", "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/file.txt")
                    .putHeader("Api-key", apiKey2)
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify that proxyKey2 can't upload files to the app data with own api-key
            client.put(serverPort, "localhost", "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(403, response.statusCode());
                                    assertEquals("You don't have an access to: files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/file.txt",
                                            response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            // verify uploaded file can be listed by bucket owner
            client.get(serverPort, "localhost", "/v1/metadata/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL/?permissions=true")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(ProxyUtil.MAPPER.writeValueAsString(expectedFolderMetadata), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testDownloadSharedFile(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        // creating per-request API key with proxyKey2 as originator
        // and proxyKey1 caller
        ApiKeyData projectApiKeyData = apiKeyStore.getApiKeyData("proxyKey2").result();
        ApiKeyData apiKeyData1 = new ApiKeyData();
        apiKeyData1.setOriginalKey(projectApiKeyData.getOriginalKey());
        // set deployment ID for proxyKey1
        apiKeyData1.setSourceDeployment("EPM-RTC-GPT");
        apiKeyData1.setAttachedFiles(Map.of("files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", new AutoSharedData(ResourceAccessType.READ_ONLY)));
        apiKeyStore.assignPerRequestApiKey(apiKeyData1);

        String apiKey1 = apiKeyData1.getPerRequestKey();

        ResourceItemMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", "folder1", "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", 17, "text/plain")
                .setEtag(TEST_FILE_ETAG);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // proxyKey2 uploads file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify caller can't download shared file with own api-key
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(403, response.statusCode());
                            assertEquals("You don't have an access to: files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).andThen((result) -> {
            // verify pre-request api key can download shared file
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", apiKey1)
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(TEST_FILE_CONTENT, response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testDownloadFileWithinSharedFolder(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        // creating per-request API key with proxyKey2 as originator
        // and proxyKey1 caller
        ApiKeyData projectApiKeyData = apiKeyStore.getApiKeyData("proxyKey2").result();
        ApiKeyData apiKeyData1 = new ApiKeyData();
        apiKeyData1.setOriginalKey(projectApiKeyData.getOriginalKey());
        // set deployment ID for proxyKey1
        apiKeyData1.setSourceDeployment("EPM-RTC-GPT");
        apiKeyData1.setAttachedFolders(Map.of("files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/", new AutoSharedData(ResourceAccessType.READ_ONLY)));
        apiKeyStore.assignPerRequestApiKey(apiKeyData1);

        String apiKey1 = apiKeyData1.getPerRequestKey();

        ResourceItemMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", "folder1", "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", 17, "text/plain")
                .setEtag(TEST_FILE_ETAG);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // proxyKey2 uploads file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify caller can't download shared file with own api-key
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey1")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(403, response.statusCode());
                            assertEquals("You don't have an access to: files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).andThen((result) -> {
            // verify pre-request api key can download shared file
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", apiKey1)
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(TEST_FILE_CONTENT, response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileDownload(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(2);
        WebClient client = WebClient.create(vertx);

        String etag = TEST_FILE_ETAG;
        ResourceItemMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", "folder1", "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", 17, "text/plain")
                .setEtag(etag);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(TEST_FILE_CONTENT, response.body());
                            assertEquals(etag, response.headers().get(HttpHeaders.ETAG));
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testListFileWithFolder(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(4);
        WebClient client = WebClient.create(vertx);

        Set<ResourceAccessType> permissions = ResourceAccessType.ALL;
        MetadataBase emptyFolderResponse = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of())
                .setPermissions(permissions);

        FileMetadata expectedFileMetadata1 = (FileMetadata) new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt", 0, "text/custom")
                .setEtag(TEST_FILE_ETAG);
        MetadataBase expectedFileMetadata2 = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "file.txt", "folder1", "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt", 0, "text/custom")
                .setEtag(TEST_FILE_ETAG);
        MetadataBase expectedFolder1Metadata = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        "folder1", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/")
                .setPermissions(permissions);
        MetadataBase expectedRootFolderMetadata = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/",
                        List.of(newFileMetadata(expectedFileMetadata1).setPermissions(permissions), expectedFolder1Metadata))
                .setPermissions(permissions);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(ResourceFolderMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(emptyFolderResponse, response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file1
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata1, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file2
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder1/file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata2, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            // verify uploaded files can be listed
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(ProxyUtil.MAPPER.writeValueAsString(expectedRootFolderMetadata), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testListFileWithDefaultContentType(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        Set<ResourceAccessType> permissions = EnumSet.allOf(ResourceAccessType.class);
        MetadataBase emptyFolderResponse = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/", List.of())
                .setPermissions(permissions);

        ResourceItemMetadata expectedFileMetadata1 = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "image.png", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/image.png", 0, "binary/octet-stream")
                .setEtag(TEST_FILE_ETAG);

        FileMetadata expectedImageMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "image.png", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/image.png", 0, "image/png");
        MetadataBase expectedRootFolderMetadata = new ResourceFolderMetadata(ResourceType.FILE, "7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                        null, null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/",
                        List.of(newFileMetadata(expectedImageMetadata).setPermissions(permissions)))
                .setPermissions(permissions);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(ResourceFolderMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(emptyFolderResponse, response.body());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file1
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/image.png")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("filename", TEST_FILE_CONTENT, "binary/octet-stream"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata1, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).andThen((result) -> {
            // verify uploaded files can be listed
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/?permissions=true")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(ProxyUtil.MAPPER.writeValueAsString(expectedRootFolderMetadata), response.body());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    @Test
    public void testFileDelete(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(3);
        WebClient client = WebClient.create(vertx);

        ResourceItemMetadata expectedFileMetadata = new FileMetadata("7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt",
                "test_file.txt", null, "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt", 17, "text/plain")
                .setEtag(TEST_FILE_ETAG);

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("test_file.txt", TEST_FILE_CONTENT),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(expectedFileMetadata, response.body());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // delete file
            client.delete(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).andThen((mapper) -> {
            // try to download deleted file
            client.get(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.string())
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(404, response.statusCode());
                            checkpoint.flag();
                        });
                    }));
        });
    }


    @Test
    public void testIfMatch(Vertx vertx, VertxTestContext context) {
        Checkpoint checkpoint = context.checkpoint(7);
        WebClient client = WebClient.create(vertx);
        String newContent = "NEW CONTENT";
        String newEtag = "bb6ed8b95d44dba4f8e4a99ebaca9a00";

        Future.succeededFuture().compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify no files
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(ResourceFolderMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertTrue(response.body().getItems().isEmpty());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // upload a test file
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("test_file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(TEST_FILE_ETAG, response.getHeader(HttpHeaders.ETAG));
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // update the test file with incorrect ETag
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .putHeader(HttpHeaders.IF_MATCH, "123")
                    .sendMultipartForm(generateMultipartForm("test_file.txt", TEST_FILE_CONTENT, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(412, response.statusCode());
                                    assertEquals("ETag 123 is rejected", response.bodyAsString());
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // update the test file with correct ETag
            client.put(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .putHeader(HttpHeaders.IF_MATCH, TEST_FILE_ETAG)
                    .as(BodyCodec.json(FileMetadata.class))
                    .sendMultipartForm(generateMultipartForm("test_file.txt", newContent, "text/custom"),
                            context.succeeding(response -> {
                                context.verify(() -> {
                                    assertEquals(200, response.statusCode());
                                    assertEquals(newEtag, response.getHeader(HttpHeaders.ETAG));
                                    checkpoint.flag();
                                    promise.complete();
                                });
                            })
                    );

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // verify uploaded file is listed with new etag
            client.get(serverPort, "localhost", "/v1/metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .as(BodyCodec.json(FileMetadata.class))
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            assertEquals(newEtag, response.body().getEtag());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).compose((mapper) -> {
            Promise<Void> promise = Promise.promise();
            // delete the test file with incorrect ETag
            client.delete(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .putHeader(HttpHeaders.IF_MATCH, TEST_FILE_ETAG)
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(412, response.statusCode());
                            assertEquals("ETag %s is rejected".formatted(TEST_FILE_ETAG), response.bodyAsString());
                            checkpoint.flag();
                            promise.complete();
                        });
                    }));

            return promise.future();
        }).andThen((result) -> {
            // delete the test file with correct ETag
            client.delete(serverPort, "localhost", "/v1/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/test_file.txt")
                    .putHeader("Api-key", "proxyKey2")
                    .putHeader(HttpHeaders.IF_MATCH, newEtag)
                    .send(context.succeeding(response -> {
                        context.verify(() -> {
                            assertEquals(200, response.statusCode());
                            checkpoint.flag();
                        });
                    }));
        });
    }

    private static MultipartForm generateMultipartForm(String fileName, String content) {
        return generateMultipartForm(fileName, content, "text/plan");
    }

    private static MultipartForm generateMultipartForm(String fileName, String content, String contentType) {
        return MultipartForm.create().textFileUpload("attachment", fileName, Buffer.buffer(content), contentType);
    }

    private static MultipartForm generateMultipartForm(String fileName, byte[] content, String contentType) {
        return MultipartForm.create().binaryFileUpload("attachment", fileName, Buffer.buffer(content), contentType);
    }

    private static FileMetadata newFileMetadata(FileMetadata expectedFileMetadata) {
        return new FileMetadata(
                expectedFileMetadata.getBucket(),
                expectedFileMetadata.getName(),
                expectedFileMetadata.getParentPath(),
                expectedFileMetadata.getUrl(),
                expectedFileMetadata.getContentLength(),
                expectedFileMetadata.getContentType());
    }
}
