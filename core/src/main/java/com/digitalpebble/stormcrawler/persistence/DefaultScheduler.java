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

import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.util.ConfUtils;

/**
 * Schedules a nextFetchDate based on the configuration
 **/
public class DefaultScheduler extends Scheduler {

    /** Date far in the future used for never-refetch items. */
    public static final Date NEVER = new Calendar.Builder()
            .setCalendarType("iso8601").setDate(2099, Calendar.DECEMBER, 31)
            .build().getTime();

    // fetch intervals in minutes
    private int defaultfetchInterval;
    private int fetchErrorFetchInterval;
    private int errorFetchInterval;

    private List<String[]> customIntervals;

    /*
     * (non-Javadoc)
     * 
     * @see com.shopstyle.discovery.crawler.Scheduler#init(java.util.Map)
     */
    @SuppressWarnings("rawtypes")
    @Override
    public void init(Map stormConf) {
        defaultfetchInterval = ConfUtils.getInt(stormConf,
                Constants.defaultFetchIntervalParamName, 1440);
        fetchErrorFetchInterval = ConfUtils.getInt(stormConf,
                Constants.fetchErrorFetchIntervalParamName, 120);
        errorFetchInterval = ConfUtils.getInt(stormConf,
                Constants.errorFetchIntervalParamName, 44640);

        // loads any custom key values
        // must be of form fetchInterval.keyname=value
        // e.g. fetchInterval.isFeed=true
        Pattern pattern = Pattern.compile("^fetchInterval\\.(.+)=(.+)");
        Iterator<String> keyIter = stormConf.keySet().iterator();
        while (keyIter.hasNext()) {
            String key = keyIter.next();
            Matcher m = pattern.matcher(key);
            if (m.matches()) {
                if (customIntervals == null) {
                    customIntervals = new LinkedList<>();
                }
                String mdname = m.group(1);
                String mdvalue = m.group(2);
                int customInterval = ConfUtils.getInt(stormConf, key, -1);
                if (customInterval != -1) {
                    customIntervals.add(new String[] { mdname, mdvalue,
                            Integer.toString(customInterval) });
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.shopstyle.discovery.crawler.Scheduler#schedule(com.digitalpebble.
     * stormcrawler.persistence .Status,
     * com.digitalpebble.stormcrawler.Metadata)
     */
    @Override
    public Date schedule(Status status, Metadata metadata) {

        int minutesIncrement = 0;

        switch (status) {
        case FETCHED:
            minutesIncrement = checkMetadata(metadata);
            break;
        case FETCH_ERROR:
            minutesIncrement = fetchErrorFetchInterval;
            break;
        case ERROR:
            minutesIncrement = errorFetchInterval;
            break;
        case REDIRECTION:
            minutesIncrement = defaultfetchInterval;
            break;
        default:
            // leave it to now e.g. DISCOVERED
        }

        // a value of -1 means never fetch
        // we use a conventional value
        if (minutesIncrement == -1) {
            return NEVER;
        }

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, minutesIncrement);

        return cal.getTime();
    }

    /**
     * Returns the first matching custom interval or the defaultfetchInterval
     **/
    private final int checkMetadata(Metadata metadata) {
        if (customIntervals == null)
            return defaultfetchInterval;

        for (String[] customMd : customIntervals) {
            String[] values = metadata.getValues(customMd[0]);
            if (values == null)
                continue;
            for (String v : values) {
                if (v.equals(customMd[1])) {
                    return Integer.parseInt(customMd[2]);
                }
            }
        }

        return defaultfetchInterval;
    }
}
