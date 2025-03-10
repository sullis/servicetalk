/*
 * Copyright © 2018, 2021 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.concurrent.Cancellable;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.HttpServiceContext;
import io.servicetalk.http.api.ReservedStreamingHttpConnection;
import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.http.api.StreamingHttpResponseFactory;
import io.servicetalk.http.api.StreamingHttpService;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.netty.internal.ExecutionContextExtension;
import io.servicetalk.transport.netty.internal.FlushStrategy.FlushSender;
import io.servicetalk.transport.netty.internal.MockFlushStrategy;
import io.servicetalk.transport.netty.internal.NettyConnectionContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import static io.servicetalk.concurrent.api.AsyncCloseables.newCompositeCloseable;
import static io.servicetalk.concurrent.api.Publisher.from;
import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.http.api.HttpExecutionStrategies.offloadNone;
import static io.servicetalk.http.netty.BuilderUtils.newClientBuilder;
import static io.servicetalk.http.netty.BuilderUtils.newServerBuilder;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

class FlushStrategyOverrideTest {

    @RegisterExtension
    static final ExecutionContextExtension SERVER_CTX =
            ExecutionContextExtension.cached("server-io", "server-executor")
                    .setClassLevel(true);
    @RegisterExtension
    static final ExecutionContextExtension CLIENT_CTX =
            ExecutionContextExtension.cached("client-io", "client-executor")
                    .setClassLevel(true);

    private StreamingHttpClient client;
    private ServerContext serverCtx;
    private ReservedStreamingHttpConnection conn;
    private FlushingService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new FlushingService();
        serverCtx = newServerBuilder(SERVER_CTX)
                .executionStrategy(offloadNone())
                .listenStreaming(service)
                .toFuture().get();
        client = newClientBuilder(serverCtx, CLIENT_CTX)
                .executionStrategy(offloadNone())
                .hostHeaderFallback(false)
                .buildStreaming();
        conn = client.reserveConnection(client.get("/")).toFuture().get();
    }

    @AfterEach
    void tearDown() throws Exception {
        newCompositeCloseable().appendAll(conn, client, serverCtx).closeAsync().toFuture().get();
    }

    @Test
    void overrideFlush() throws Throwable {
        NettyConnectionContext nctx = (NettyConnectionContext) conn.connectionContext();
        MockFlushStrategy clientStrategy = new MockFlushStrategy();
        Cancellable c = nctx.updateFlushStrategy((old, isOriginal) -> isOriginal ? clientStrategy : old);

        CountDownLatch reqWritten = new CountDownLatch(1);
        StreamingHttpRequest req = client.get("/flush").payloadBody(from(1, 2, 3)
                .map(count -> client.executionContext().bufferAllocator().fromAscii(count.toString()))
                .afterFinally(reqWritten::countDown));

        Future<? extends Collection<Object>> clientResp = conn.request(req)
                .flatMapPublisher(StreamingHttpResponse::messageBody).toFuture();
        reqWritten.await(); // Wait for request to be written.

        FlushSender clientFlush = clientStrategy.verifyApplied();
        clientStrategy.verifyWriteStarted();
        clientStrategy.verifyItemWritten(5 /* Header + 3 chunks + trailers*/);
        clientStrategy.verifyWriteTerminated();
        clientFlush.flush();

        MockFlushStrategy serverStrategy = service.getLastUsedStrategy();

        FlushSender serverFlush = serverStrategy.verifyApplied();
        serverStrategy.verifyWriteStarted();
        serverStrategy.verifyItemWritten(5 /* Header + 3 chunks + trailers*/);
        serverStrategy.verifyWriteTerminated();
        serverFlush.flush();

        Collection<Object> chunks = clientResp.get();
        assertThat("Unexpected items received.", chunks, hasSize(3 /*3 chunks (includes empty last chunk)*/));

        c.cancel(); // revert to flush on each.

        // No more custom strategies.
        Collection<Object> secondReqChunks = conn.request(conn.get("/"))
                .flatMapPublisher(StreamingHttpResponse::messageBody).toFuture().get();
        clientStrategy.verifyNoMoreInteractions();
        service.getLastUsedStrategy();
        serverStrategy.verifyNoMoreInteractions();
        assertThat("Unexpected payload for regular flush.", secondReqChunks, empty());
    }

    private static final class FlushingService implements StreamingHttpService {

        private final BlockingQueue<MockFlushStrategy> flushStrategies = new LinkedBlockingQueue<>();

        @Override
        public Single<StreamingHttpResponse> handle(final HttpServiceContext ctx, final StreamingHttpRequest request,
                                                    final StreamingHttpResponseFactory responseFactory) {
            if (request.path().startsWith("/flush")) {
                NettyConnectionContext nctx = (NettyConnectionContext) ctx;
                MockFlushStrategy strategy = new MockFlushStrategy();
                Cancellable c = nctx.updateFlushStrategy((old, isOriginal) -> isOriginal ? strategy : old);
                return succeeded(responseFactory.ok().payloadBody(request.payloadBody().afterFinally(() -> {
                    c.cancel();
                    flushStrategies.add(strategy);
                })));
            } else {
                return succeeded(responseFactory.ok().payloadBody(request.payloadBody()
                        .afterFinally(() -> flushStrategies.add(new MockFlushStrategy()))));
            }
        }

        MockFlushStrategy getLastUsedStrategy() throws InterruptedException {
            return flushStrategies.take();
        }
    }
}
