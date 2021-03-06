/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.client.transport;

import org.elasticsearch.action.ActionModule;
import com.google.common.collect.ImmutableList;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.*;
import org.elasticsearch.action.count.CountRequest;
import org.elasticsearch.action.count.CountResponse;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequest;
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse;
import org.elasticsearch.action.explain.ExplainRequest;
import org.elasticsearch.action.explain.ExplainResponse;
import org.elasticsearch.action.mlt.MoreLikeThisRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.GenericClient;
import org.elasticsearch.client.support.AbstractServerSearchClient;
import org.elasticsearch.client.transport.support.InternalTransportSearchClient;
import org.elasticsearch.cluster.ClusterNameModule;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.io.CachedStreams;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.concurrent.ThreadLocals;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.EnvironmentModule;
import org.elasticsearch.monitor.MonitorService;
import org.elasticsearch.node.internal.InternalSettingsPerparer;
import org.elasticsearch.plugins.PluginsModule;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.search.TransportSearchModule;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolModule;
import org.elasticsearch.transport.TransportModule;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.action.count.CountRequestBuilder;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequestBuilder;
import org.elasticsearch.action.explain.ExplainRequestBuilder;
import org.elasticsearch.action.mlt.MoreLikeThisRequestBuilder;
import org.elasticsearch.action.search.MultiSearchRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchScrollRequestBuilder;
import org.elasticsearch.threadpool.server.ServerThreadPool;


import java.util.concurrent.TimeUnit;


import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;

/**
 * The transport client allows to create a client that is not part of the cluster, but simply connects to one
 * or more nodes directly by adding their respective addresses using {@link #addTransportAddress(org.elasticsearch.common.transport.TransportAddress)}.
 * <p/>
 * <p>The transport client important modules used is the {@link org.elasticsearch.transport.TransportModule} which is
 * started in client mode (only connects, no bind).
 */
public class TransportSearchClient extends AbstractServerSearchClient {

    private final Injector injector;

    private final Settings settings;

    private final Environment environment;


    private final PluginsService pluginsService;

    private final TransportClientNodesService nodesService;

    private final InternalTransportSearchClient internalClient;
    
    private final ThreadPool threadPool;


    /**
     * Constructs a new transport client with settings loaded either from the classpath or the file system (the
     * <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with <tt>config/</tt>).
     */
    public TransportSearchClient() throws ElasticSearchException {
        this(ImmutableSettings.Builder.EMPTY_SETTINGS, true);
    }

    /**
     * Constructs a new transport client with explicit settings and settings loaded either from the classpath or the file
     * system (the <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with <tt>config/</tt>).
     */
    public TransportSearchClient(Settings settings) {
        this(settings, true);
    }

    /**
     * Constructs a new transport client with explicit settings and settings loaded either from the classpath or the file
     * system (the <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with <tt>config/</tt>).
     */
    public TransportSearchClient(Settings.Builder settings) {
        this(settings.build(), true);
    }

    /**
     * Constructs a new transport client with the provided settings and the ability to control if settings will
     * be loaded from the classpath / file system (the <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with
     * <tt>config/</tt>).
     *
     * @param settings           The explicit settings.
     * @param loadConfigSettings <tt>true</tt> if settings should be loaded from the classpath/file system.
     * @throws ElasticSearchException
     */
    public TransportSearchClient(Settings.Builder settings, boolean loadConfigSettings) throws ElasticSearchException {
        this(settings.build(), loadConfigSettings);
    }

    /**
     * Constructs a new transport client with the provided settings and the ability to control if settings will
     * be loaded from the classpath / file system (the <tt>elasticsearch.(yml|json)</tt> files optionally prefixed with
     * <tt>config/</tt>).
     *
     * @param pSettings          The explicit settings.
     * @param loadConfigSettings <tt>true</tt> if settings should be loaded from the classpath/file system.
     * @throws ElasticSearchException
     */
    public TransportSearchClient(Settings pSettings, boolean loadConfigSettings) throws ElasticSearchException {
        Tuple<Settings, Environment> tuple = InternalSettingsPerparer.prepareSettings(pSettings, loadConfigSettings);
        Settings settings = settingsBuilder().put(tuple.v1())
                .put("network.server", false)
                .put("node.client", true)
                .build();
        this.environment = tuple.v2();

        this.pluginsService = new PluginsService(tuple.v1(), tuple.v2());
        settings = pluginsService.updatedSettings();
        this.settings = settings;

        CompressorFactory.configure(settings);

        ModulesBuilder modules = new ModulesBuilder();
        modules.add(new PluginsModule(settings, pluginsService));
        modules.add(new EnvironmentModule(environment));
        modules.add(new SettingsModule(settings));
        modules.add(new NetworkModule());
        modules.add(new ClusterNameModule(settings));
        modules.add(new ThreadPoolModule(settings));
        modules.add(new TransportSearchModule());
        modules.add(new TransportModule(settings));
        modules.add(new ActionModule(true));
        modules.add(new ClientTransportModule());

        injector = modules.createInjector();

        injector.getInstance(TransportService.class).start();

        nodesService = injector.getInstance(TransportClientNodesService.class);
        threadPool = injector.getInstance(ServerThreadPool.class);
        internalClient = injector.getInstance(InternalTransportSearchClient.class);
    }

    /**
     * Returns the current registered transport addresses to use (added using
     * {@link #addTransportAddress(org.elasticsearch.common.transport.TransportAddress)}.
     */
    public ImmutableList<TransportAddress> transportAddresses() {
        return nodesService.transportAddresses();
    }

    /**
     * Returns the current connected transport nodes that this client will use.
     * <p/>
     * <p>The nodes include all the nodes that are currently alive based on the transport
     * addresses provided.
     */
    public ImmutableList<DiscoveryNode> connectedNodes() {
        return nodesService.connectedNodes();
    }

    /**
     * Returns the listed nodes in the transport client (ones added to it).
     */
    public ImmutableList<DiscoveryNode> listedNodes() {
        return nodesService.listedNodes();
    }

    /**
     * Adds a transport address that will be used to connect to.
     * <p/>
     * <p>The Node this transport address represents will be used if its possible to connect to it.
     * If it is unavailable, it will be automatically connected to once it is up.
     * <p/>
     * <p>In order to get the list of all the current connected nodes, please see {@link #connectedNodes()}.
     */
    public TransportSearchClient addTransportAddress(TransportAddress transportAddress) {
        nodesService.addTransportAddresses(transportAddress);
        return this;
    }

    /**
     * Adds a list of transport addresses that will be used to connect to.
     * <p/>
     * <p>The Node this transport address represents will be used if its possible to connect to it.
     * If it is unavailable, it will be automatically connected to once it is up.
     * <p/>
     * <p>In order to get the list of all the current connected nodes, please see {@link #connectedNodes()}.
     */
    public TransportSearchClient addTransportAddresses(TransportAddress... transportAddress) {
        nodesService.addTransportAddresses(transportAddress);
        return this;
    }

    /**
     * Removes a transport address from the list of transport addresses that are used to connect to.
     */
    public TransportSearchClient removeTransportAddress(TransportAddress transportAddress) {
        nodesService.removeTransportAddress(transportAddress);
        return this;
    }


    @Override
    public ThreadPool threadPool() {
        return threadPool;
    }

    /**
     * Closes the client.
     */
    @Override
    public void close() {
        injector.getInstance(TransportClientNodesService.class).close();
        injector.getInstance(TransportService.class).close();
        try {
            injector.getInstance(MonitorService.class).close();
        } catch (Exception e) {
            // ignore, might not be bounded
        }

        for (Class<? extends LifecycleComponent> plugin : pluginsService.services()) {
            injector.getInstance(plugin).close();
        }

        threadPool.shutdown();
        try {
            threadPool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
        try {
            threadPool.shutdownNow();
        } catch (Exception e) {
            // ignore
        }

        CacheRecycler.clear();
        CachedStreams.clear();
        ThreadLocals.clearReferencesThreadLocals();
    }

    @Override
    public Settings settings() {
        return this.settings;
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>, SearchClient extends GenericClient> 
            ActionFuture<Response> execute(Action<Request, Response, RequestBuilder, SearchClient> action, Request request) {
        return internalClient.execute(action, request);
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>, SearchClient extends GenericClient> 
            void execute(Action<Request, Response, RequestBuilder, SearchClient> action, Request request, ActionListener<Response> listener) {
        internalClient.execute(action, request, listener);
    }

    @Override
    public ActionFuture<DeleteByQueryResponse> deleteByQuery(DeleteByQueryRequest request) {
        return internalClient.deleteByQuery(request);
    }

    @Override
    public void deleteByQuery(DeleteByQueryRequest request, ActionListener<DeleteByQueryResponse> listener) {
        internalClient.deleteByQuery(request, listener);
    }

    @Override
    public ActionFuture<CountResponse> count(CountRequest request) {
        return internalClient.count(request);
    }

    @Override
    public void count(CountRequest request, ActionListener<CountResponse> listener) {
        internalClient.count(request, listener);
    }

    @Override
    public ActionFuture<SearchResponse> search(SearchRequest request) {
        return internalClient.search(request);
    }

    @Override
    public void search(SearchRequest request, ActionListener<SearchResponse> listener) {
        internalClient.search(request, listener);
    }

    @Override
    public ActionFuture<SearchResponse> searchScroll(SearchScrollRequest request) {
        return internalClient.searchScroll(request);
    }

    @Override
    public void searchScroll(SearchScrollRequest request, ActionListener<SearchResponse> listener) {
        internalClient.searchScroll(request, listener);
    }

    @Override
    public ActionFuture<MultiSearchResponse> multiSearch(MultiSearchRequest request) {
        return internalClient.multiSearch(request);
    }

    @Override
    public void multiSearch(MultiSearchRequest request, ActionListener<MultiSearchResponse> listener) {
        internalClient.multiSearch(request, listener);
    }

    @Override
    public ActionFuture<SearchResponse> moreLikeThis(MoreLikeThisRequest request) {
        return internalClient.moreLikeThis(request);
    }

    @Override
    public void moreLikeThis(MoreLikeThisRequest request, ActionListener<SearchResponse> listener) {
        internalClient.moreLikeThis(request, listener);
    }

    @Override
    public ActionFuture<ExplainResponse> explain(ExplainRequest request) {
        return internalClient.explain(request);
    }

    @Override
    public void explain(ExplainRequest request, ActionListener<ExplainResponse> listener) {
        internalClient.explain(request, listener);
    }

    @Override
    public DeleteByQueryRequestBuilder prepareDeleteByQuery(String... indices) {
        return internalClient.prepareDeleteByQuery(indices);
    }

    @Override
    public CountRequestBuilder prepareCount(String... indices) {
        return internalClient.prepareCount(indices);
    }

    @Override
    public SearchRequestBuilder prepareSearch(String... indices) {
        return internalClient.prepareSearch(indices);
    }

    @Override
    public SearchScrollRequestBuilder prepareSearchScroll(String scrollId) {
        return internalClient.prepareSearchScroll(scrollId);
    }

    @Override
    public MultiSearchRequestBuilder prepareMultiSearch() {
        return internalClient.prepareMultiSearch();
    }

    @Override
    public MoreLikeThisRequestBuilder prepareMoreLikeThis(String index, String type, String id) {
        return internalClient.prepareMoreLikeThis(index, type, id);
    }

    @Override
    public ExplainRequestBuilder prepareExplain(String index, String type, String id) {
        return internalClient.prepareExplain(index, type, id);
    }

}
