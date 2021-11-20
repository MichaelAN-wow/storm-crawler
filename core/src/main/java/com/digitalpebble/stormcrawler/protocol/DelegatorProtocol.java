/**
 * Licensed to DigitalPebble Ltd under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.digitalpebble.stormcrawler.protocol;

import com.digitalpebble.stormcrawler.Metadata;
import crawlercommons.robots.BaseRobotRules;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.apache.storm.Config;
import org.slf4j.LoggerFactory;

/**
 * Protocol implementation that enables selection from a collection of sub-protocols using filters
 * based on each call's metadata
 *
 * <p>Is configured like this
 *
 * <pre>
 * protocol.delegator.config:
 * - className: "com.digitalpebble.stormcrawler.protocol.httpclient.HttpProtocol"
 * filters:
 * domain: "example.com"
 * depth: "3"
 * test: "true"
 * - className: "com.digitalpebble.stormcrawler.protocol.okhttp.HttpProtocol"
 * filters:
 * js: "true"
 * - className: "com.digitalpebble.stormcrawler.protocol.okhttp.HttpProtocol"
 * </pre>
 *
 * The last one in the list must not have filters as it is used as a default value. The protocols
 * are tried for matches in the order in which they are listed in the configuration. The first to
 * match gets used to fetch a URL.
 *
 * @since 2.2
 */
public class DelegatorProtocol implements Protocol {

    private static final String DELEGATOR_CONFIG_KEY = "protocol.delegator.config";

    protected static final org.slf4j.Logger LOG = LoggerFactory.getLogger(DelegatorProtocol.class);

    static class Filter {

        String key;
        String value;

        public Filter(String k, String v) {
            key = k;
            value = v;
        }
    }

    static class FilteredProtocol {

        final Protocol protoInstance;
        final List<Filter> filters = new LinkedList<>();

        Protocol getProtocolInstance() {
            return protoInstance;
        }

        public FilteredProtocol(String protocolimplementation, Object f, Config config) {
            // load the protocol
            Class protocolClass;
            try {
                protocolClass = Class.forName(protocolimplementation);
                boolean interfaceOK = Protocol.class.isAssignableFrom(protocolClass);
                if (!interfaceOK) {
                    throw new RuntimeException(
                            "Class " + protocolimplementation + " does not implement Protocol");
                }
                this.protoInstance = (Protocol) protocolClass.newInstance();
                this.protoInstance.configure(config);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Can't load class " + protocolimplementation);
            } catch (InstantiationException e) {
                throw new RuntimeException("Can't instanciate class " + protocolimplementation);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                        "IllegalAccessException for class " + protocolimplementation);
            }

            // instantiate filters
            if (f != null) {
                if (f instanceof Map) {
                    ((Map<String, String>) f)
                            .forEach(
                                    (k, v) -> {
                                        filters.add(new Filter(k, v));
                                    });
                } else {
                    throw new RuntimeException("Can't instanciate filter " + f);
                }
            }

            // log filters found
            LOG.info("Loaded {} filters for {}", filters.size(), protocolimplementation);
        }

        public ProtocolResponse getProtocolOutput(String url, Metadata metadata) throws Exception {
            return protoInstance.getProtocolOutput(url, metadata);
        }

        public BaseRobotRules getRobotRules(String url) {
            return protoInstance.getRobotRules(url);
        }

        public void cleanup() {
            protoInstance.cleanup();
        }

        boolean isMatch(final Metadata metadata) {
            // if this FP has no filters - it can handle anything
            if (filters.isEmpty()) return true;

            // check that all its filters are satisfied
            for (Filter f : filters) {
                if (f.value == null || f.value.equals("")) {
                    // just interested in the fact that the key exists
                    if (!metadata.containsKey(f.key)) {
                        LOG.trace("Key {} not found in metadata {}", f.key, metadata);
                        return false;
                    }
                } else {
                    // interested in the value associated with the key
                    if (!metadata.containsKeyWithValue(f.key, f.value)) {
                        LOG.trace(
                                "Key {} not found with value {} in metadata {}",
                                f.key,
                                f.value,
                                metadata);
                        return false;
                    }
                }
            }

            return true;
        }
    }

    private final LinkedList<FilteredProtocol> protocols = new LinkedList<>();

    @Override
    public void configure(Config conf) {
        Object obj = conf.get(DELEGATOR_CONFIG_KEY);

        if (obj == null)
            throw new RuntimeException("DelegatorProtocol declared but no config set for it");

        // should contain a list of maps
        // each map having a className and optionally a number of filters
        if (obj instanceof Collection) {
            for (Map subConf : (Collection<Map>) obj) {
                String className = (String) subConf.get("className");
                Object filters = subConf.get("filters");
                protocols.add(new FilteredProtocol(className, filters, conf));
            }
        } else { // single value?
            throw new RuntimeException(
                    "DelegatorProtocol declared but single object found in config " + obj);
        }

        // check that the last protocol has no filter
        if (!protocols.peekLast().filters.isEmpty()) {
            throw new RuntimeException(
                    "The last sub protocol has filters but must not as it acts as the default");
        }
    }

    final FilteredProtocol getProtocolFor(String url, Metadata metadata) {

        for (FilteredProtocol p : protocols) {
            if (p.isMatch(metadata)) {
                return p;
            }
        }

        return null;
    }

    @Override
    public BaseRobotRules getRobotRules(String url) {

        FilteredProtocol proto = getProtocolFor(url, Metadata.empty);
        if (proto == null) {
            throw new RuntimeException("No sub protocol eligible to retrieve robots");
        }
        return proto.getRobotRules(url);
    }

    @Override
    public ProtocolResponse getProtocolOutput(String url, Metadata metadata) throws Exception {

        // go through the filtered protocols to find which one to use
        FilteredProtocol proto = getProtocolFor(url, metadata);
        if (proto == null) {
            throw new RuntimeException(
                    "No sub protocol eligible to retrieve " + url + "given " + metadata.toString());
        }
        // execute and return protocol with url-meta combo
        return proto.getProtocolOutput(url, metadata);
    }

    @Override
    public void cleanup() {
        for (FilteredProtocol p : protocols) p.cleanup();
    }
}
