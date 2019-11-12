/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.monitoring.exporter.MonitoringDoc;
import org.elasticsearch.xpack.core.monitoring.exporter.MonitoringTemplateUtils;
import org.elasticsearch.xpack.core.watcher.watch.Watch;
import org.elasticsearch.xpack.monitoring.collector.Collector;
import org.elasticsearch.xpack.monitoring.exporter.Exporters;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.core.ClientHelper.MONITORING_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;
import static org.elasticsearch.xpack.core.monitoring.exporter.MonitoringTemplateUtils.LAST_UPDATED_VERSION;
import static org.elasticsearch.xpack.core.monitoring.exporter.MonitoringTemplateUtils.PIPELINE_IDS;
import static org.elasticsearch.xpack.core.monitoring.exporter.MonitoringTemplateUtils.pipelineName;

/**
 * The {@code MonitoringService} is a service that does the work of publishing the details to the monitoring cluster.
 * <p>
 * If this service is stopped, then the attached, monitored node is not going to publish its details to the monitoring cluster. Given
 * service life cycles, the intended way to temporarily stop the publishing is using the start and stop methods.
 */
public class MonitoringService extends AbstractLifecycleComponent {
    
    private final AtomicBoolean installingSomething = new AtomicBoolean(false);
    
    private static final Logger logger = LogManager.getLogger(MonitoringService.class);


    /**
     * Minimum value for sampling interval (1 second)
     */
    public static final TimeValue MIN_INTERVAL = TimeValue.timeValueSeconds(1L);

    /*
     * Dynamically controls enabling or disabling the collection of Monitoring data only from Elasticsearch.
     * <p>
      * This should only be used while transitioning to Metricbeat-based data collection for Elasticsearch with
      * {@linkplain #ENABLED} set to {@code true}. By setting this to {@code false} and that value to {@code true},
      * Kibana, Logstash, Beats, and APM Server can all continue to report their stats through this cluster until they
      * are transitioned to being monitored by Metricbeat as well.
      */
    public static final Setting<Boolean> ELASTICSEARCH_COLLECTION_ENABLED =
            Setting.boolSetting("xpack.monitoring.elasticsearch.collection.enabled", true,
                                Setting.Property.Dynamic, Setting.Property.NodeScope);

    /**
     * Dynamically controls enabling or disabling the collection of Monitoring data from Elasticsearch as well as other products
     * in the stack.
     */
    public static final Setting<Boolean> ENABLED =
            Setting.boolSetting("xpack.monitoring.collection.enabled", false,
                                Setting.Property.Dynamic, Setting.Property.NodeScope);

    /**
     * Sampling interval between two collections (default to 10s)
     */
    public static final Setting<TimeValue> INTERVAL =
            Setting.timeSetting("xpack.monitoring.collection.interval", TimeValue.timeValueSeconds(10), MIN_INTERVAL,
                                Setting.Property.Dynamic, Setting.Property.NodeScope);

    /** State of the monitoring service, either started or stopped **/
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** Task in charge of collecting and exporting monitoring data **/
    private final MonitoringExecution monitor = new MonitoringExecution();

    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final Set<Collector> collectors;
    private final Exporters exporters;

    private volatile boolean elasticsearchCollectionEnabled;
    private volatile boolean enabled;
    private volatile TimeValue interval;
    private volatile ThreadPool.Cancellable scheduler;
    private final Client client;

    MonitoringService(Settings settings, ClusterService clusterService, ThreadPool threadPool,
                      Set<Collector> collectors, Exporters exporters, Client client) {
        this.clusterService = Objects.requireNonNull(clusterService);
        this.threadPool = Objects.requireNonNull(threadPool);
        this.collectors = Objects.requireNonNull(collectors);
        this.exporters = Objects.requireNonNull(exporters);
        this.elasticsearchCollectionEnabled = ELASTICSEARCH_COLLECTION_ENABLED.get(settings);
        this.enabled = ENABLED.get(settings);
        this.interval = INTERVAL.get(settings);
        this.client = Objects.requireNonNull(client);

        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(ELASTICSEARCH_COLLECTION_ENABLED, this::setElasticsearchCollectionEnabled);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(ENABLED, this::setMonitoringActive);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(INTERVAL, this::setInterval);
    }

    void setElasticsearchCollectionEnabled(final boolean enabled) {
        this.elasticsearchCollectionEnabled = enabled;
        scheduleExecution();
    }

    void setMonitoringActive(final boolean enabled) {
        this.enabled = enabled;
        scheduleExecution();
    }

    void setInterval(final TimeValue interval) {
        this.interval = interval;
        scheduleExecution();
    }

    public TimeValue getInterval() {
        return interval;
    }

    public boolean isMonitoringActive() {
        return isStarted() && enabled;
    }

    boolean isElasticsearchCollectionEnabled() {
        return this.elasticsearchCollectionEnabled;
    }

    boolean shouldScheduleExecution() {
        return isElasticsearchCollectionEnabled() && isMonitoringActive();
    }

    private String threadPoolName() {
        return ThreadPool.Names.GENERIC;
    }

    boolean isStarted() {
        return started.get();
    }
    
    private boolean hasTemplate(final ClusterState clusterState, final String templateName) {
        final IndexTemplateMetaData template = clusterState.getMetaData().getTemplates().get(templateName);

        return template != null && hasValidVersion(template.getVersion(), LAST_UPDATED_VERSION);
    }
    
    /**
     * Determine if the {@code version} is defined and greater than or equal to the {@code minimumVersion}.
     *
     * @param version The version to check
     * @param minimumVersion The minimum version required to be a "valid" version
     * @return {@code true} if the version exists and it's &gt;= to the minimum version. {@code false} otherwise.
     */
    private boolean hasValidVersion(final Object version, final long minimumVersion) {
        return version instanceof Number && ((Number)version).intValue() >= minimumVersion;
    }
    
    /**
     * Acknowledge success / failure for any given creation attempt (e.g., template or pipeline).
     */
    private class ResponseActionListener<Response> implements ActionListener<Response> {

        private final String type;
        private final String name;
        private final AtomicInteger countDown;
        private final AtomicBoolean setup;

        private ResponseActionListener(String type, String name, AtomicInteger countDown) {
            this(type, name, countDown, null);
        }

        private ResponseActionListener(String type, String name, AtomicInteger countDown, @Nullable AtomicBoolean setup) {
            this.type = Objects.requireNonNull(type);
            this.name = Objects.requireNonNull(name);
            this.countDown = Objects.requireNonNull(countDown);
            this.setup = setup;
        }

        @Override
        public void onResponse(Response response) {
            responseReceived(countDown, true, setup);
            if (response instanceof AcknowledgedResponse) {
                if (((AcknowledgedResponse)response).isAcknowledged()) {
                    logger.trace("successfully set monitoring {} [{}]", type, name);
                } else {
                    logger.error("failed to set monitoring {} [{}]", type, name);
                }
            } else {
                logger.trace("successfully handled monitoring {} [{}]", type, name);
            }
        }

        @Override
        public void onFailure(Exception e) {
            responseReceived(countDown, false, setup);
            logger.error((Supplier<?>) () -> new ParameterizedMessage("failed to set monitoring {} [{}]", type, name), e);
        }
    }
    
    private void responseReceived(final AtomicInteger pendingResponses, final boolean success, final @Nullable AtomicBoolean setup) {
        if (setup != null && success == false) {
            setup.set(false);
        }

        if (pendingResponses.decrementAndGet() <= 0) {
            logger.trace("all installation requests returned a response");
            if (installingSomething.compareAndSet(true, false) == false) {
                throw new IllegalStateException("could not reset installing flag to false");
            }
        }
    }
    
    // FIXME this should use the IndexTemplateMetaDataUpgrader
    private void putTemplate(String template, String source, ActionListener<AcknowledgedResponse> listener) {
        logger.debug("installing template [{}]", template);

        PutIndexTemplateRequest request = new PutIndexTemplateRequest(template).source(source, XContentType.JSON);
        assert !Thread.currentThread().isInterrupted() : "current thread has been interrupted before putting index template!!!";

        executeAsyncWithOrigin(client.threadPool().getThreadContext(), MONITORING_ORIGIN, request, listener,
                client.admin().indices()::putTemplate);
    }

    
    /**
     * When on the elected master, we setup all resources (mapping types, templates, and pipelines) before we attempt to run the exporter.
     * If those resources do not exist, then we will create them.
     *
     * @return {@code true} indicates that all resources are "ready" and the exporter can be used. {@code false} to stop and wait.
     */
    private boolean preFlightResourceCreation() {
        logger.info("running monitoring assets pre-flight creation");
        // we are on the elected master
        // Check that there is nothing that could block metadata updates

        // build a list for everything that is missing, but do not start execution
        final List<Runnable> asyncActions = new ArrayList<>();
        final AtomicInteger pendingResponses = new AtomicInteger(0);

        
        final Map<String, String> templates = Arrays.stream(MonitoringTemplateUtils.TEMPLATE_IDS)
                .collect(Collectors.toMap(MonitoringTemplateUtils::templateName, MonitoringTemplateUtils::loadTemplate));
        // Check that each required template exists, installing it if needed
//        final List<Entry<String, String>> missingTemplates = templates.entrySet()
//                .stream()
//                .filter((e) -> hasTemplate(clusterService.state(), e.getKey()) == false)
//                .collect(Collectors.toList());
        

            for (Entry<String, String> template : templates.entrySet()) {
                asyncActions.add(() -> putTemplate(template.getKey(), template.getValue(),
                        new ResponseActionListener<>("template", template.getKey(), pendingResponses)));
            }

        if (asyncActions.size() > 0) {
            if (installingSomething.compareAndSet(false, true)) {
                pendingResponses.set(asyncActions.size());
                try (ThreadContext.StoredContext ignore = client.threadPool().getThreadContext().stashWithOrigin(MONITORING_ORIGIN)) {
                    asyncActions.forEach(Runnable::run);
                }
            } else {
                // let the cluster catch up since requested installations may be ongoing
                return false;
            }
        } else {
            logger.debug("monitoring index templates and pipelines are installed on master node, service can start");
        }

        // everything is setup (or running)
        return true;
    }

    @Override
    protected void doStart() {
        logger.info("SETTINGS MONITORING SERVICE AS ACTIVE");

        if (started.compareAndSet(false, true)) {
            try {
                logger.debug("monitoring service is starting");
                preFlightResourceCreation();
                scheduleExecution();
                logger.debug("monitoring service started");
            } catch (Exception e) {
                logger.error((Supplier<?>) () -> new ParameterizedMessage("failed to start monitoring service"), e);
                started.set(false);
                throw e;
            }
        }
    }

    @Override
    protected void doStop() {
        if (started.getAndSet(false)) {
            logger.debug("monitoring service is stopping");
            cancelExecution();
            logger.debug("monitoring service stopped");
        }
    }

    @Override
    protected void doClose() {
        logger.debug("monitoring service is closing");
        monitor.close();
        exporters.close();
        logger.debug("monitoring service closed");
    }

    void scheduleExecution() {
        if (scheduler != null) {
            cancelExecution();
        }
        if (shouldScheduleExecution()) {
            scheduler = threadPool.scheduleWithFixedDelay(monitor, interval, threadPoolName());
        }
    }

    void cancelExecution() {
        if (scheduler != null) {
            try {
                scheduler.cancel();
            } finally {
                scheduler = null;
            }
        }
    }

    /**
     * {@link MonitoringExecution} is a scheduled {@link Runnable} that periodically checks if monitoring
     * data can be collected and exported. It runs at a given interval corresponding to the monitoring
     * sampling interval. It first checks if monitoring is still enabled (because it might have changed
     * since the last time the task was scheduled: interval set to -1 or the monitoring service is stopped).
     * Since collecting and exporting data can take time, it uses a semaphore to track the current execution.
     */
    class MonitoringExecution extends AbstractRunnable implements Closeable {

        /**
         * Binary semaphore used to wait for monitoring execution to terminate before closing or stopping
         * the monitoring service. A semaphore is preferred over a ReentrantLock because the lock is
         * obtained by a thread and released by another thread.
         **/
        private final Semaphore semaphore = new Semaphore(1);

        @Override
        public void doRun() {
            if (shouldScheduleExecution() == false) {
                logger.debug("monitoring execution is skipped");
                return;
            }

            if (semaphore.tryAcquire() == false) {
                logger.debug("monitoring execution is skipped until previous execution terminated");
                return;
            }

            threadPool.executor(threadPoolName()).submit(new AbstractRunnable() {
                @Override
                protected void doRun() throws Exception {
                    final long timestamp = System.currentTimeMillis();
                    final long intervalInMillis = interval.getMillis();
                    final ClusterState clusterState = clusterService.state();

                    final Collection<MonitoringDoc> results = new ArrayList<>();
                    for (Collector collector : collectors) {
                        if (isStarted() == false) {
                            // Do not collect more data if the monitoring service is stopping
                            // otherwise some collectors might just fail.
                            return;
                        }

                        try {
                            Collection<MonitoringDoc> result = collector.collect(timestamp, intervalInMillis, clusterState);
                            if (result != null) {
                                results.addAll(result);
                            }
                        } catch (Exception e) {
                            logger.warn((Supplier<?>) () ->
                                    new ParameterizedMessage("monitoring collector [{}] failed to collect data", collector.name()), e);
                        }
                    }
                    if (shouldScheduleExecution()) {
                        exporters.export(results, ActionListener.wrap(r -> semaphore.release(), this::onFailure));
                    } else {
                        semaphore.release();
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    logger.warn("monitoring execution failed", e);
                    semaphore.release();
                }

                @Override
                public void onRejection(Exception e) {
                    logger.warn("monitoring execution has been rejected", e);
                    semaphore.release();
                }
            });
        }

        @Override
        public void onFailure(Exception e) {
            logger.warn("monitoring execution failed", e);
        }

        @Override
        public void close() {
            try {
                // Block until the lock can be acquired or 10s. The timed try acquire is necessary as there may be a failure that causes
                // the semaphore to not get released and then the node will hang forever on shutdown
                if (semaphore.tryAcquire(10L, TimeUnit.SECONDS) == false) {
                    logger.warn("monitoring execution did not complete after waiting for 10s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
