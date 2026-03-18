/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.prometheus.rest;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.esql.action.EsqlQueryResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an {@link EsqlQueryResponse} from a {@link org.elasticsearch.xpack.esql.plan.logical.TsInfo} plan into the
 * Prometheus {@code /api/v1/series} JSON response format.
 *
 * <p>TsInfo column layout (indices 0–6):
 * <ol start="0">
 *   <li>metric_name</li>
 *   <li>data_stream</li>
 *   <li>unit</li>
 *   <li>metric_type</li>
 *   <li>field_type</li>
 *   <li>dimension_fields</li>
 *   <li>dimensions – JSON object, e.g. {@code {"labels.job":"prometheus","labels.__name__":"up"}}</li>
 * </ol>
 */
public class PrometheusSeriesResponseListener implements ActionListener<EsqlQueryResponse> {

    private static final Logger logger = LogManager.getLogger(PrometheusSeriesResponseListener.class);

    /** Column index of the {@code metric_name} field in TsInfo output. */
    private static final int METRIC_NAME_COL = 0;
    /** Column index of the {@code dimensions} field in TsInfo output. */
    private static final int DIMENSIONS_COL = 6;
    private static final String LABELS_PREFIX = "labels.";
    private static final String CONTENT_TYPE = "application/json";

    /**
     * Prometheus HTTP API error type strings, as defined in the Prometheus source:
     * https://github.com/prometheus/prometheus/blob/main/web/api/v1/api.go
     */
    private static final String ERROR_TYPE_EXECUTION = "execution";
    private static final String ERROR_TYPE_BAD_DATA = "bad_data";
    private static final String ERROR_TYPE_TIMEOUT = "timeout";

    private final RestChannel channel;

    public PrometheusSeriesResponseListener(RestChannel channel) {
        this.channel = channel;
    }

    @Override
    public void onResponse(EsqlQueryResponse response) {
        // Do NOT close/decRef the response here: the framework (via respondAndRelease) calls
        // decRef() after this method returns, which is the correct single release.
        try {
            List<Map<String, String>> seriesList = extractSeries(response);
            channel.sendResponse(buildSuccessResponse(seriesList));
        } catch (Exception e) {
            logger.debug("Failed to build series response", e);
            sendError(e);
        }
    }

    @Override
    public void onFailure(Exception e) {
        logger.debug("Series query failed", e);
        sendError(e);
    }

    // -------------------------------------------------------------------------
    // Response building
    // -------------------------------------------------------------------------

    private static List<Map<String, String>> extractSeries(EsqlQueryResponse response) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Iterable<Object> row : response.rows()) {
            String metricName = null;
            String dimensionsJson = null;
            int col = 0;
            for (Object value : row) {
                if (col == METRIC_NAME_COL) {
                    metricName = value != null ? value.toString() : null;
                } else if (col == DIMENSIONS_COL) {
                    dimensionsJson = value != null ? value.toString() : null;
                    break; // DIMENSIONS_COL is the last column we need
                }
                col++;
            }
            Map<String, String> labels = buildLabelMap(metricName, dimensionsJson);
            if (labels.isEmpty() == false) {
                result.add(labels);
            }
        }
        return result;
    }

    /**
     * Builds the label map for one TsInfo row. Parses {@code dimensionsJson} and, when
     * {@code dimensions} carries no {@code __name__} entry (OTel metrics), synthesises it
     * from the {@code metric_name} column.
     */
    static Map<String, String> buildLabelMap(String metricName, String dimensionsJson) {
        Map<String, String> labels = parseDimensions(dimensionsJson);
        // OTel metrics have no labels.__name__ in dimensions — synthesise it from metric_name
        if (labels.containsKey("__name__") == false && metricName != null) {
            labels.put("__name__", metricName);
        }
        return labels;
    }

    /**
     * Parses the {@code dimensions} JSON object and strips the {@code labels.} prefix from keys.
     * Example: {@code {"labels.__name__":"up","labels.job":"prometheus"}}
     * → {@code {"__name__":"up","job":"prometheus"}}
     */
    static Map<String, String> parseDimensions(String json) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return labels;
        }
        // Simple JSON object parser for {"key":"value",...} – all string-typed values
        // Use xcontent for robust parsing
        try (var parser = JsonXContent.jsonXContent.createParser(org.elasticsearch.xcontent.XContentParserConfiguration.EMPTY, json)) {
            parser.nextToken(); // START_OBJECT
            while (parser.nextToken() != org.elasticsearch.xcontent.XContentParser.Token.END_OBJECT) {
                String rawKey = parser.currentName();
                parser.nextToken();
                String value = parser.text();
                String labelName = rawKey.startsWith(LABELS_PREFIX) ? rawKey.substring(LABELS_PREFIX.length()) : rawKey;
                labels.put(labelName, value);
            }
        } catch (IOException e) {
            logger.debug("Failed to parse dimensions JSON [{}]", json, e);
        }
        return labels;
    }

    private static RestResponse buildSuccessResponse(List<Map<String, String>> seriesList) throws IOException {
        XContentBuilder builder = JsonXContent.contentBuilder();
        builder.startObject();
        builder.field("status", "success");
        builder.startArray("data");
        for (Map<String, String> labels : seriesList) {
            builder.startObject();
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                builder.field(entry.getKey(), entry.getValue());
            }
            builder.endObject();
        }
        builder.endArray();
        builder.endObject();
        return new RestResponse(RestStatus.OK, CONTENT_TYPE, Strings.toString(builder));
    }

    private void sendError(Exception e) {
        try {
            RestStatus httpStatus = RestStatus.INTERNAL_SERVER_ERROR;
            String errorType = ERROR_TYPE_EXECUTION;
            if (e instanceof ElasticsearchStatusException statusEx) {
                httpStatus = statusEx.status();
                if (httpStatus == RestStatus.BAD_REQUEST) {
                    errorType = ERROR_TYPE_BAD_DATA;
                }
            } else if (isTimeout(e)) {
                httpStatus = RestStatus.SERVICE_UNAVAILABLE;
                errorType = ERROR_TYPE_TIMEOUT;
            }
            XContentBuilder builder = JsonXContent.contentBuilder();
            builder.startObject();
            builder.field("status", "error");
            builder.field("errorType", errorType);
            builder.field("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            builder.endObject();
            channel.sendResponse(new RestResponse(httpStatus, CONTENT_TYPE, Strings.toString(builder)));
        } catch (Exception sendEx) {
            sendEx.addSuppressed(e);
            logger.warn("Failed to send error response", sendEx);
            try {
                channel.sendResponse(
                    new RestResponse(
                        RestStatus.INTERNAL_SERVER_ERROR,
                        RestResponse.TEXT_CONTENT_TYPE,
                        new BytesArray("Internal server error")
                    )
                );
            } catch (Exception ignored) {}
        }
    }

    private static boolean isTimeout(Exception e) {
        if (e == null) {
            return false;
        }
        String name = e.getClass().getSimpleName();
        return name.contains("Timeout") || name.contains("timeout");
    }
}
