package com.digitalpebble.stormcrawler.warc;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.fs.Path;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy.Units;
import org.apache.storm.hdfs.bolt.sync.CountSyncPolicy;
import org.apache.storm.hdfs.common.AbstractHDFSWriter;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.utils.Utils;

import com.digitalpebble.stormcrawler.protocol.ProtocolResponse;
import com.digitalpebble.stormcrawler.util.ConfUtils;

@SuppressWarnings("serial")
public class WARCHdfsBolt extends GzipHdfsBolt {

    private Map<String, String> header_fields = new HashMap<>();

    // by default remains as is-pre 1.17
    private String protocolMDprefix = "";

    private boolean withRequestRecords = false;

    public WARCHdfsBolt() {
        super();
        FileSizeRotationPolicy rotpol = new FileSizeRotationPolicy(1.0f,
                Units.GB);
        withRotationPolicy(rotpol);
        // dummy sync policy
        withSyncPolicy(new CountSyncPolicy(10));
        // default local filesystem
        withFsUrl("file:///");
    }

    public WARCHdfsBolt withHeader(Map<String, String> header_fields) {
        this.header_fields = header_fields;
        return this;
    }

    public WARCHdfsBolt withRequestRecords() {
        withRequestRecords = true;
        return this;
    }

    @Override
    public void doPrepare(Map conf, TopologyContext topologyContext,
            OutputCollector collector) throws IOException {
        super.doPrepare(conf, topologyContext, collector);
        protocolMDprefix = ConfUtils.getString(conf,
                ProtocolResponse.PROTOCOL_MD_PREFIX_PARAM, "");
        withRecordFormat(new WARCRecordFormat(protocolMDprefix));
        if (withRequestRecords) {
            addRecordFormat(new WARCRequestRecordFormat(protocolMDprefix), 0);
        }
    }

    @Override
    protected AbstractHDFSWriter makeNewWriter(Path path, Tuple tuple)
            throws IOException {
        AbstractHDFSWriter writer = super.makeNewWriter(path, tuple);

        Instant now = Instant.now();

        // overrides the filename and creation date in the headers
        header_fields.put("WARC-Date", WARCRecordFormat.WARC_DF.format(now));
        header_fields.put("WARC-Filename", path.getName());

        byte[] header = WARCRecordFormat.generateWARCInfo(header_fields);

        // write the header at the beginning of the file
        if (header != null && header.length > 0) {
            super.out.write(Utils.gzip(header));
        }

        return writer;
    }

}
