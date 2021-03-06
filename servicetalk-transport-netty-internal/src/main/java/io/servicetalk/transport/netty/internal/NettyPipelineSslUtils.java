/*
 * Copyright © 2019-2020 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.transport.netty.internal;

import io.servicetalk.transport.api.ConnectionObserver.SecurityHandshakeObserver;
import io.servicetalk.transport.netty.internal.ConnectionObserverInitializer.ConnectionObserverHandler;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

/**
 * Utilities for {@link ChannelPipeline} and SSL/TLS.
 */
public final class NettyPipelineSslUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyPipelineSslUtils.class);

    private NettyPipelineSslUtils() {
        // no instances.
    }

    /**
     * Determine if the {@link ChannelPipeline} is configured for SSL/TLS.
     *
     * @param pipeline The pipeline to check.
     * @return {@code true} if the pipeline is configured to use SSL/TLS.
     */
    public static boolean isSslEnabled(ChannelPipeline pipeline) {
        return pipeline.get(SslHandler.class) != null || pipeline.get(SniHandler.class) != null;
    }

    /**
     * Extracts the {@link SSLSession} from the {@link ChannelPipeline} if the {@link SslHandshakeCompletionEvent}
     * is successful and reports the result to {@link SecurityHandshakeObserver} if available.
     *
     * @param pipeline the {@link ChannelPipeline} which contains handler containing the {@link SSLSession}.
     * @param sslEvent the event indicating a SSL/TLS handshake completed.
     * @param failureConsumer invoked if a failure is encountered.
     * @param shouldReport {@code true} if the handshake status should be reported to {@link SecurityHandshakeObserver}.
     * @return The {@link SSLSession} or {@code null} if none can be found.
     */
    @Nullable
    public static SSLSession extractSslSessionAndReport(ChannelPipeline pipeline,
                                                        SslHandshakeCompletionEvent sslEvent,
                                                        Consumer<Throwable> failureConsumer,
                                                        boolean shouldReport) {
        final SecurityHandshakeObserver observer = shouldReport ? handshakeObserver(pipeline) : null;
        if (sslEvent.isSuccess()) {
            final SslHandler sslHandler = pipeline.get(SslHandler.class);
            if (sslHandler != null) {
                final SSLSession session = sslHandler.engine().getSession();
                if (observer != null) {
                    observer.handshakeComplete(session);
                }
                return session;
            } else {
                deliverFailureCause(failureConsumer, new IllegalStateException("Unable to find " +
                        SslHandler.class.getName() + " in the pipeline."), observer);
            }
        } else {
            deliverFailureCause(failureConsumer, sslEvent.cause(), observer);
        }
        return null;
    }

    private static void deliverFailureCause(final Consumer<Throwable> failureConsumer, final Throwable cause,
                                            @Nullable final SecurityHandshakeObserver securityObserver) {
        if (securityObserver != null) {
            securityObserver.handshakeFailed(cause);
        }
        failureConsumer.accept(cause);
    }

    @Nullable
    private static SecurityHandshakeObserver handshakeObserver(final ChannelPipeline pipeline) {
        final ConnectionObserverHandler handler = pipeline.get(ConnectionObserverHandler.class);
        if (handler == null) {
            LOGGER.warn("Expected to report the handshake completion event, but unable to find {} in the pipeline.",
                    ConnectionObserverHandler.class);
            return null;
        }
        final SecurityHandshakeObserver handshakeObserver = handler.handshakeObserver();
        if (handshakeObserver == null) {
            LOGGER.warn("Expected to report the handshake completion event, but {} was not initialized.",
                    SecurityHandshakeObserver.class);
            return null;
        }
        return handshakeObserver;
    }
}
