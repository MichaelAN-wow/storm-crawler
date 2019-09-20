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

package com.digitalpebble.stormcrawler.persistence;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;

import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.Metadata;

/**
 * Simple implementation of a URLBuffer which rotates on the queues without
 * applying any priority.
 * 
 * @since 1.15
 **/

public class SimpleURLBuffer extends AbstractURLBuffer {

    static final Logger LOG = LoggerFactory.getLogger(SimpleURLBuffer.class);

    Map<String, Queue<URLMetadata>> queues = Collections
            .synchronizedMap(new LinkedHashMap<>());

    /** Total number of queues in the buffer **/
    public int numQueues() {
        return queues.size();
    }

    /**
     * Stores the URL and its Metadata under a given key.
     * 
     * @return false if the URL was already in the buffer, true if it wasn't and
     *         was added
     **/
    public synchronized boolean add(String URL, Metadata m, String key) {

        LOG.debug("Adding {}", URL);

        if (in_buffer.contains(URL)) {
            LOG.debug("already in buffer {}", URL);
            return false;
        }

        // determine which queue to use
        // configure with other than hostname
        if (key == null) {
            String partitionKey = partitioner.getPartition(URL, m);
            if (partitionKey == null) {
                partitionKey = "_DEFAULT_";
            }
        }

        // create the queue if it does not exist
        // and add the url
        queues.computeIfAbsent(key, k -> new LinkedList<URLMetadata>())
                .add(new URLMetadata(URL, m));
        return in_buffer.add(URL);
    }

    /**
     * Retrieves the next available URL, guarantees that the URLs are always
     * perfectly shuffled
     * 
     * @return null if no entries are available
     **/
    public synchronized Values next() {
        Iterator<Entry<String, Queue<URLMetadata>>> i = queues.entrySet()
                .iterator();

        if (!i.hasNext()) {
            LOG.debug("Empty iterator");
            return null;
        }

        Map.Entry<String, Queue<URLMetadata>> nextEntry = i.next();

        Queue<URLMetadata> queue = nextEntry.getValue();
        String queueName = nextEntry.getKey();

        // remove the entry
        i.remove();

        LOG.debug("Next queue {}", queueName);

        // remove the first element
        URLMetadata item = queue.poll();

        LOG.debug("Item {}", item.url);

        // any left? add to the end of the iterator
        if (!queue.isEmpty()) {
            LOG.debug("adding to the back of the queue {}", queueName);
            queues.put(queueName, queue);
        }
        // notify that the queue is empty
        else {
            if (listener != null) {
                listener.emptyQueue(queueName);
            }
        }

        // remove it from the list of URLs in the queue
        in_buffer.remove(item.url);
        return new Values(item.url, item.metadata);
    }

    class URLMetadata {
        String url;
        Metadata metadata;

        URLMetadata(String u, Metadata m) {
            url = u;
            metadata = m;
        }
    }

    @Override
    public synchronized boolean hasNext() {
        return !queues.isEmpty();
    }

}
