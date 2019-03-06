/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.exporter.http;

import org.apache.http.client.config.RequestConfig.Builder;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.common.Nullable;

import org.apache.http.HttpHost;

/**
 * {@code ProxyRequestConfigCallback} set HTTP proxy settings
 */
class ProxyRequestConfigCallback implements RestClientBuilder.RequestConfigCallback {

    @Nullable
    private final String proxyHost;
    @Nullable
    private final String proxyPort; // FIXME does this get coerced to a string somewhere when the config is generated?

    private HttpHost proxyHostPort;

    /**
     * Create a new {@link ProxyRequestConfigCallback}.
     *
     * @param proxyHost Hostname of the proxy server
     * @param proxyPort Port of the proxy server
     */
    ProxyRequestConfigCallback(@Nullable final String proxyHost, @Nullable final String proxyPort) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
    }

    /**
     * Sets the {@linkplain Builder#setProxy(HttpHost) proxy settings}.
     *
     * @param requestConfigBuilder The request to configure.
     * @return Always {@code requestConfigBuilder}.
     */
    @Override
    public Builder customizeRequestConfig(Builder requestConfigBuilder) {
        proxyHostPort = HttpHost(proxyHost, proxyPort);
        requestConfigBuilder.setProxy(proxyHostPort);

        return requestConfigBuilder;
    }

}
