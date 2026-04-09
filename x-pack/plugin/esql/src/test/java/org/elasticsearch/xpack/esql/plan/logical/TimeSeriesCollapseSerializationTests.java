/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.Source;

import java.io.IOException;
import java.util.List;

public class TimeSeriesCollapseSerializationTests extends AbstractLogicalPlanSerializationTests<TimeSeriesCollapse> {
    @Override
    protected TimeSeriesCollapse createTestInstance() {
        Source source = randomSource();
        LogicalPlan child = randomChild(0);
        List<Attribute> collapseAttributes = randomFieldAttributes(1, 5, false);
        return new TimeSeriesCollapse(source, child, collapseAttributes);
    }

    @Override
    protected TimeSeriesCollapse mutateInstance(TimeSeriesCollapse instance) throws IOException {
        LogicalPlan child = instance.child();
        List<Attribute> collapseAttributes = instance.collapseAttributes();
        switch (between(0, 1)) {
            case 0 -> child = randomValueOtherThan(child, () -> randomChild(0));
            case 1 -> collapseAttributes = randomValueOtherThan(collapseAttributes, () -> randomFieldAttributes(1, 5, false));
            default -> throw new IllegalStateException();
        }
        return new TimeSeriesCollapse(instance.source(), child, collapseAttributes);
    }

    @Override
    protected boolean alwaysEmptySource() {
        return true;
    }
}
