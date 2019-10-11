/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler.elasticsearch.persistence;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.persistence.EmptyQueueListener;
import com.digitalpebble.stormcrawler.util.ConfUtils;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Uses collapsing spouts to get an initial set of URLs and keys to query for
 * and gets emptyQueue notifications from the URLBuffer to query ES for a
 * specific key.
 * 
 * @since 1.15
 */

public class HybridSpout extends AggregationSpout
        implements EmptyQueueListener {

    private static final Logger LOG = LoggerFactory
            .getLogger(HybridSpout.class);

    protected static final String RELOADPARAMNAME = "es.status.max.urls.per.reload";

    private int bufferReloadSize = 10;

    private Cache<String, Object[]> searchAfterCache;

    @Override
    public void open(Map stormConf, TopologyContext context,
            SpoutOutputCollector collector) {
        super.open(stormConf, context, collector);
        bufferReloadSize = ConfUtils.getInt(stormConf, RELOADPARAMNAME,
                maxURLsPerBucket);
        buffer.setEmptyQueueListener(this);
        searchAfterCache = CacheBuilder.newBuilder().build();
    }

    @Override
    public void emptyQueue(String queueName) {

        LOG.info("Emptied buffer queue for {}", queueName);

        if (!currentBuckets.contains(queueName)) {
            // not interested in this one any more
            return;
        }

        LOG.info("Querying for more docs for {}", queueName);

        if (queryDate == null) {
            queryDate = new Date();
            lastTimeResetToNOW = Instant.now();
        }

        String formattedQueryDate = ISODateTimeFormat.dateTimeNoMillis()
                .print(queryDate.getTime());

        BoolQueryBuilder queryBuilder = boolQuery().filter(QueryBuilders
                .rangeQuery("nextFetchDate").lte(formattedQueryDate));

        queryBuilder.filter(QueryBuilders.termQuery(partitionField, queueName));

        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.query(queryBuilder);
        sourceBuilder.from(0);
        sourceBuilder.size(bufferReloadSize);
        sourceBuilder.explain(false);
        sourceBuilder.trackTotalHits(false);

        // sort within a bucket
        if (StringUtils.isNotBlank(bucketSortField)) {
            FieldSortBuilder sorter = SortBuilders.fieldSort(bucketSortField)
                    .order(SortOrder.ASC);
            sourceBuilder.sort(sorter);
            // use the url as a tie breaker for search after
            sourceBuilder
                    .sort(SortBuilders.fieldSort("url").order(SortOrder.ASC));
        }

        // do we have a search after for this one?
        Object[] searchAfterValues = searchAfterCache.getIfPresent(queueName);
        if (searchAfterValues != null) {
            sourceBuilder.searchAfter(searchAfterValues);
        }

        SearchRequest request = new SearchRequest(indexName);

        request.source(sourceBuilder);

        // https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-preference.html
        // _shards:2,3
        if (shardID != -1) {
            request.preference("_shards:" + shardID);
        }

        // dump query to log
        LOG.debug("{} ES query {} - {}", logIdprefix, queueName,
                request.toString());

        client.searchAsync(request, RequestOptions.DEFAULT, this);
    }

    /**
     * gets the results for a specific host
     */
    public void onResponse(SearchResponse response) {

        // aggregations? process with the super class
        if (response.getAggregations() != null) {
            // delete all entries from the searchAfterCache when
            // we get the results from the aggregation spouts
            searchAfterCache.invalidateAll();
            super.onResponse(response);
            return;
        }

        int alreadyprocessed = 0;
        int numDocs = 0;

        SearchHit[] hits = response.getHits().getHits();

        Object[] sortValues = null;

        // retrieve the key for these results
        String key = null;

        for (SearchHit hit : hits) {
            numDocs++;
            key = (String) hit.getSourceAsMap().get(partitionField);
            sortValues = hit.getSortValues();
            if (!addHitToBuffer(hit)) {
                alreadyprocessed++;
            }
        }

        // no key if no results have been found
        if (key != null) {
            this.searchAfterCache.put(key, sortValues);
        }

        eventCounter.scope("ES_queries").incrBy(1);
        eventCounter.scope("ES_docs").incrBy(numDocs);
        eventCounter.scope("already_being_processed").incrBy(alreadyprocessed);

        LOG.info(
                "{} ES term query returned {} hits  in {} msec with {} already being processed for {}",
                logIdprefix, numDocs, response.getTook().getMillis(),
                alreadyprocessed, key);
    }

    /**
     * A failure caused by an exception at some phase of the task.
     */
    public void onFailure(Exception e) {
        LOG.error("Exception with ES query", e);
    }

}
