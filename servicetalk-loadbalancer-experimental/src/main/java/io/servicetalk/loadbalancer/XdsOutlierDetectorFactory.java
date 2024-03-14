/*
 * Copyright © 2024 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.loadbalancer;

import io.servicetalk.client.api.LoadBalancedConnection;
import io.servicetalk.concurrent.api.Executor;

import static java.util.Objects.requireNonNull;

/**
 * A factory of {@link XdsOutlierDetector} instances.
 * See the {@link XdsOutlierDetector} for a detailed description and history of the xDS protocol.
 * @param <ResolvedAddress> the type of the resolved address.
 * @param <C> the type of the load balanced connection.
 */
public final class XdsOutlierDetectorFactory<ResolvedAddress, C extends LoadBalancedConnection>
        implements OutlierDetectorFactory<ResolvedAddress, C> {

    private final OutlierDetectorConfig config;

    public XdsOutlierDetectorFactory(final OutlierDetectorConfig config) {
        this.config = requireNonNull(config, "config");
    }

    @Override
    public OutlierDetector<ResolvedAddress, C> newOutlierDetector(
            final Executor executor, String lbDescription) {
        return new XdsOutlierDetector<>(executor, config, lbDescription);
    }
}
