package io.rakam.clickhouse.data;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.facebook.presto.rakam.RetryDriver;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpClientConfig;
import io.airlift.http.client.Request;
import io.airlift.http.client.StringResponseHandler;
import io.airlift.http.client.jetty.JettyHttpClient;
import io.airlift.http.client.jetty.JettyIoPool;
import io.airlift.http.client.jetty.JettyIoPoolConfig;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.rakam.clickhouse.BasicMemoryBuffer;
import io.rakam.clickhouse.StreamConfig;
import io.rakam.clickhouse.data.backup.BackupService;
import org.rakam.util.ProjectCollection;

import javax.ws.rs.core.UriBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static io.airlift.http.client.StringResponseHandler.createStringResponseHandler;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.rakam.clickhouse.analysis.ClickHouseQueryExecution.getSystemSocksProxy;
import static org.rakam.util.ValidationUtil.checkCollection;

public class KinesisRecordProcessor
        implements IRecordProcessor
{
    private static final Logger logger = Logger.get(KinesisRecordProcessor.class);
    protected static final JettyHttpClient HTTP_CLIENT = new JettyHttpClient(
            new HttpClientConfig()
                    .setConnectTimeout(new Duration(10, SECONDS))
                    .setSocksProxy(getSystemSocksProxy()), new JettyIoPool("rakam-clickhouse", new JettyIoPoolConfig()),
            ImmutableSet.of());

    private final BasicMemoryBuffer streamBuffer;
    private final MessageTransformer context;

    public KinesisRecordProcessor(StreamConfig streamConfig)
    {
        this.streamBuffer = new BasicMemoryBuffer(streamConfig);
        context = new MessageTransformer();
    }

    @Override
    public void initialize(String shardId)
    {
    }

    @Override
    public void processRecords(List<Record> records, IRecordProcessorCheckpointer checkpointer)
    {
        for (Record record : records) {
            streamBuffer.consumeRecord(record, record.getSequenceNumber());
        }

        if (streamBuffer.shouldFlush()) {

            Map<ProjectCollection, byte[]> pages;
            try {
                pages = context.convert(streamBuffer.getRecords());
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }

            for (Map.Entry<ProjectCollection, byte[]> entry : pages.entrySet()) {

                try {
                    RetryDriver.retry()
                            .run("insert", (Callable<Void>) () -> {
                                CompletableFuture<Void> future = new CompletableFuture<>();
                                executeRequest(entry.getKey(), entry.getValue(), future);
                                future.join();
                                return null;
                            });
                }
                catch (Exception e) {
                    logger.error(e);
                }
            }

            try {
                checkpointer.checkpoint();
            }
            catch (InvalidStateException | ShutdownException e) {
                throw Throwables.propagate(e);
            }
        }
    }

    private void executeRequest(ProjectCollection key, byte[] value, CompletableFuture<Void> future)
    {
        URI uri = UriBuilder
                .fromPath("/").scheme("http").host("127.0.0.1").port(8123)
                .queryParam("query", format("INSERT INTO %s.%s FORMAT RowBinary",
                        key.project, checkCollection(key.collection, '`'))).build();

        HttpClient.HttpResponseFuture<StringResponseHandler.StringResponse> f = HTTP_CLIENT.executeAsync(Request.builder()
                .setUri(uri)
                .setMethod("POST")
                .setBodyGenerator(out -> out.write(value))
                .build(), createStringResponseHandler());

        f.addListener(() -> {
            try {
                StringResponseHandler.StringResponse stringResponse = f.get(1L, MINUTES);
                if (stringResponse.getStatusCode() == 200) {
                    future.complete(null);
                }
                else {
                    RuntimeException ex = new RuntimeException(stringResponse.getStatusMessage() + " : "
                            + stringResponse.getBody().split("\n", 2)[0]);
                    future.completeExceptionally(ex);
                }
            }
            catch (InterruptedException | ExecutionException | TimeoutException e) {
                future.completeExceptionally(e);
                logger.error(e);
            }
        }, Runnable::run);
    }

    @Override
    public void shutdown(IRecordProcessorCheckpointer checkpointer, ShutdownReason reason)
    {

    }
}

