/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.session;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.expression.function.EsqlFunctionRegistry;
import org.elasticsearch.xpack.esql.inference.InferenceSettings;
import org.elasticsearch.xpack.esql.parser.EsqlConfig;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.parser.QueryParams;
import org.elasticsearch.xpack.esql.telemetry.PlanTelemetry;

import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for {@link EsqlSession#gatherPlanTelemetry}, which populates {@link PlanTelemetry} from
 * a pre-built logical plan tree (bypassing the parser). This matters for internal callers such as
 * the Prometheus plugin that construct plans programmatically via plan builders rather than parsing
 * an ES|QL string.
 * <p>
 * Each test parses a query string to obtain both the reference {@link PlanTelemetry} and the
 * {@link org.elasticsearch.xpack.esql.plan.logical.LogicalPlan}, then asserts that running
 * {@link EsqlSession#gatherPlanTelemetry} post-hoc on that same plan produces identical telemetry.
 */
public class EsqlSessionTelemetryTests extends ESTestCase {

    private static final EsqlFunctionRegistry FUNCTION_REGISTRY = new EsqlFunctionRegistry();
    private static final EsqlParser PARSER = new EsqlParser(new EsqlConfig(FUNCTION_REGISTRY));

    /**
     * A single {@link org.elasticsearch.xpack.esql.capabilities.TelemetryAware} node with no
     * function expressions: only the command label should be recorded.
     */
    public void testTelemetryAwareNodeWithNoFunctions() {
        assertEquivalentTelemetry("ROW x = 1");
    }

    /**
     * A chain of {@link org.elasticsearch.xpack.esql.capabilities.TelemetryAware} nodes with no
     * function expressions: all command labels across the tree should be recorded.
     */
    public void testTelemetryAwareNodesAcrossTreeWithNoFunctions() {
        assertEquivalentTelemetry("ROW x = 1 | WHERE true | LIMIT 100");
    }

    /**
     * An {@link org.elasticsearch.xpack.esql.expression.function.UnresolvedFunction} in an
     * expression (named function call, as produced by the parser): the function name should be
     * recorded directly — exercises the {@code instanceof UnresolvedFunction} branch in
     * {@link EsqlSession#gatherPlanTelemetry}.
     */
    public void testUnresolvedFunction() {
        assertEquivalentTelemetry("ROW y = 1 | EVAL x = TO_LONG(y)");
    }

    /**
     * A concrete {@link org.elasticsearch.xpack.esql.core.expression.function.Function} instance
     * (not an {@link org.elasticsearch.xpack.esql.expression.function.UnresolvedFunction}) in an
     * expression. The parser produces concrete function instances for inline cast expressions (e.g.
     * {@code y::long}), so this exercises the {@code else} branch in
     * {@link EsqlSession#gatherPlanTelemetry} via a parsed query. The same branch also covers
     * functions instantiated directly in programmatically-built plans (e.g. by
     * {@code PromqlQueryPlanBuilder}).
     */
    public void testConcreteFunction() {
        assertEquivalentTelemetry("ROW y = 1 | EVAL x = y::long");
    }

    /**
     * Parses {@code query} to obtain both the reference {@link PlanTelemetry} and the logical plan,
     * then asserts that {@link EsqlSession#gatherPlanTelemetry} produces identical telemetry when
     * run post-hoc on that same plan.
     */
    private static void assertEquivalentTelemetry(String query) {
        PlanTelemetry fromParsing = new PlanTelemetry(FUNCTION_REGISTRY);
        var plan = PARSER.parseQuery(query, new QueryParams(), fromParsing, new InferenceSettings(Settings.EMPTY));

        PlanTelemetry fromPostHoc = new PlanTelemetry(FUNCTION_REGISTRY);
        EsqlSession.gatherPlanTelemetry(plan, fromPostHoc);

        assertThat("commands", fromPostHoc.commands(), equalTo(fromParsing.commands()));
        assertThat("functions", fromPostHoc.functions(), equalTo(fromParsing.functions()));
    }
}
