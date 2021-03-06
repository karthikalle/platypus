package org.apache.platypus.server;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.StatusRuntimeException;
import org.apache.platypus.server.grpc.*;
import org.apache.platypus.server.utils.OneDocBuilder;
import org.apache.platypus.server.utils.ParallelDocumentIndexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class TestIndexManager {
    protected static final Logger logger = Logger.getLogger(YelpReviewsTest.class.getName());

    protected static void liveSettings(LuceneServerClient serverClient, String indexName) {
        LiveSettingsRequest liveSettingsRequest = LiveSettingsRequest.newBuilder()
                .setIndexName(indexName)
                .setIndexRamBufferSizeMB(256.0)
                .setMaxRefreshSec(1.0)
                .build();
        LiveSettingsResponse liveSettingsResponse = serverClient.getBlockingStub().liveSettings(liveSettingsRequest);
        logger.info(liveSettingsResponse.getResponse());
    }

    protected static String readResourceAsString(String path) throws IOException {
        return Files.readString(Paths.get(path));
    }

    protected static void setUpIndex(LuceneServerClient standaloneServerClient, Path standaloneDir, String indexName, String suggestionsFilePath, OneDocBuilder oneDocBuilder) throws IOException, ExecutionException, InterruptedException {
        // create index if it does not exist
        try {
            createIndex(standaloneServerClient, standaloneDir, indexName);
        } catch(StatusRuntimeException e){
            if (!e.getStatus().getCode().name().equals("ALREADY_EXISTS"))
                throw e;
        }
        //add live settings
        liveSettings(standaloneServerClient, indexName);

        // register fields
        registerFields(standaloneServerClient,  Paths.get("src", "test", "resources", "registerFieldsYelpSuggestTestPayload.json").toAbsolutePath().toString());

        // start index
        StartIndexRequest startIndexRequest = StartIndexRequest.newBuilder()
                .setIndexName(indexName)
                .setMode(Mode.STANDALONE)
                .setPrimaryGen(0)
                .setRestore(false)
                .build();
        startIndex(standaloneServerClient, startIndexRequest);
        // index docs
        long t1 = System.nanoTime();
        Stream.Builder<AddDocumentRequest> builder = Stream.builder();

        final ExecutorService indexService = YelpReviewsTest.createExecutorService(
                (Runtime.getRuntime().availableProcessors()) / 4,
                "LuceneIndexing");

        Path suggestionsPath = Paths.get(suggestionsFilePath);


        List<Future<Long>> results = ParallelDocumentIndexer.buildAndIndexDocs(
                oneDocBuilder,
                suggestionsPath,
                indexService,
                standaloneServerClient
        );

        //wait till all indexing done
        for (Future<Long> each : results) {
            try {
                Long genId = each.get();
                logger.info(String.format("ParallelDocumentIndexer.buildAndIndexDocs returned genId: %s", genId));
            }
            catch (ExecutionException | InterruptedException futureException){
                System.out.println(futureException.getCause());
            }
        }
        long t2 = System.nanoTime();

        System.out.println(
                String.format("IT took %s nanosecs to index documents", (t2 - t1))
        );

        // commit
        standaloneServerClient.getBlockingStub().commit(CommitRequest.newBuilder().setIndexName(indexName).build());

    }

    protected static void registerFields(LuceneServerClient serverClient, String path) throws IOException {
        String registerFieldsJson = readResourceAsString(path);
        FieldDefRequest fieldDefRequest = getFieldDefRequest(registerFieldsJson);
        FieldDefResponse fieldDefResponse = serverClient.getBlockingStub().registerFields(fieldDefRequest);
        logger.info(fieldDefResponse.getResponse());

    }

    private static FieldDefRequest getFieldDefRequest(String jsonStr) {
        logger.fine(String.format("Converting fields %s to proto FieldDefRequest", jsonStr));
        FieldDefRequest.Builder fieldDefRequestBuilder = FieldDefRequest.newBuilder();
        try {
            JsonFormat.parser().merge(jsonStr, fieldDefRequestBuilder);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
        FieldDefRequest fieldDefRequest = fieldDefRequestBuilder.build();
        logger.fine(String.format("jsonStr converted to proto FieldDefRequest %s", fieldDefRequest.toString()));
        return fieldDefRequest;
    }

    protected static void createIndex(LuceneServerClient serverClient, Path dir, String indexName) {
        CreateIndexResponse response = serverClient.getBlockingStub().createIndex(
                CreateIndexRequest.newBuilder()
                        .setIndexName(indexName)
                        .setRootDir(dir.resolve("index").toString())
                        .build());
        logger.info(response.getResponse());
    }

    protected static void startIndex(LuceneServerClient serverClient, StartIndexRequest startIndexRequest) {
        StartIndexResponse startIndexResponse = serverClient
                .getBlockingStub().startIndex(startIndexRequest);
        logger.info(
                String.format("numDocs: %s, maxDoc: %s, segments: %s, startTimeMS: %s",
                        startIndexResponse.getNumDocs(),
                        startIndexResponse.getMaxDoc(),
                        startIndexResponse.getSegments(),
                        startIndexResponse.getStartTimeMS()));
    }
}
