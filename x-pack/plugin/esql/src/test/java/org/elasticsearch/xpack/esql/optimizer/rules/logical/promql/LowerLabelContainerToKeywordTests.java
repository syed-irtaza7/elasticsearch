/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical.promql;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.VerificationException;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.convert.ToString;
import org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFilter;
import org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataPath;
import org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.ToTimeSeriesMetadata;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.Project;
import org.elasticsearch.xpack.esql.plan.logical.local.EmptyLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;

import java.util.List;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.TEST_CFG;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.as;
import static org.hamcrest.Matchers.containsString;

/**
 * The keyword form of a plan over a typed label container: casts give way to the keyword columns they cast, and what has no keyword
 * form fails.
 */
public class LowerLabelContainerToKeywordTests extends ESTestCase {

    private static final Source SOURCE = Source.EMPTY;

    /** The source's keyword {@code _timeseries}, as a collapse reads it. */
    private final Attribute keyword = new ReferenceAttribute(SOURCE, "_timeseries", DataType.KEYWORD);
    private final LogicalPlan source = new LocalRelation(SOURCE, List.of(keyword), EmptyLocalSupplier.EMPTY);

    /** {@code plan} with a reader of every column it outputs. */
    private static LogicalPlan read(LogicalPlan plan) {
        return new Project(SOURCE, plan, plan.output());
    }

    /** A reader of a cast reads the keyword column it casts; the cast goes. */
    public void testCastReadsItsKeywordColumn() {
        Alias typed = new Alias(SOURCE, "_timeseries", new ToTimeSeriesMetadata(SOURCE, keyword));
        LogicalPlan plan = new Project(SOURCE, new Eval(SOURCE, source, List.of(typed)), List.of(typed.toAttribute()));

        Project lowered = as(LowerLabelContainerToKeyword.lower(plan), Project.class);
        assertSame(source, lowered.child());
        assertEquals(List.of(keyword), lowered.projections());
    }

    /** A typed label container read through further columns reads keyword there too. */
    public void testReadersOfTypedColumnsGoKeyword() {
        Alias typed = new Alias(SOURCE, "_timeseries", new ToTimeSeriesMetadata(SOURCE, keyword));
        Alias renamed = new Alias(SOURCE, "series", typed.toAttribute());
        LogicalPlan plan = read(new Eval(SOURCE, new Eval(SOURCE, source, List.of(typed)), List.of(renamed)));

        Project lowered = as(LowerLabelContainerToKeyword.lower(plan), Project.class);
        Eval eval = as(lowered.child(), Eval.class);
        assertSame(source, eval.child());
        assertEquals(keyword, eval.fields().getFirst().child());
        assertEquals(DataType.KEYWORD, lowered.output().getLast().dataType());
    }

    /** The label container of a series without labels is null as a keyword label container. */
    public void testEmptyLabelContainerIsNull() {
        Alias empty = new Alias(SOURCE, "$empty", new ToTimeSeriesMetadata(SOURCE, Literal.keyword(SOURCE, "{}")));
        Project lowered = as(LowerLabelContainerToKeyword.lower(read(new Eval(SOURCE, source, List.of(empty)))), Project.class);
        assertEquals(new Literal(SOURCE, null, DataType.KEYWORD), as(lowered.child(), Eval.class).fields().getFirst().child());
    }

    /** Rendering a typed label container as keyword is the keyword column itself. */
    public void testRenderingIsTheKeywordColumn() {
        Alias typed = new Alias(SOURCE, "_timeseries", new ToTimeSeriesMetadata(SOURCE, keyword));
        Alias rendered = new Alias(SOURCE, "_timeseries", new ToString(SOURCE, typed.toAttribute(), TEST_CFG));
        LogicalPlan plan = new Project(
            SOURCE,
            new Eval(SOURCE, new Eval(SOURCE, source, List.of(typed)), List.of(rendered)),
            List.of(rendered.toAttribute())
        );

        Project lowered = as(LowerLabelContainerToKeyword.lower(plan), Project.class);
        assertSame(source, lowered.child());
        assertEquals(List.of(keyword), lowered.projections());
    }

    /** Leaving labels out of the label container, or reading one out of it, has no keyword form. */
    public void testDerivingLabelContainerFails() {
        Alias typed = new Alias(SOURCE, "_timeseries", new ToTimeSeriesMetadata(SOURCE, keyword));
        Source unsetSource = new Source(1, 1, "without (pod)");
        Alias unset = new Alias(
            SOURCE,
            "_timeseries$pod",
            new TimeSeriesMetadataFilter(unsetSource, typed.toAttribute(), List.of(Literal.keyword(SOURCE, "pod")), true)
        );
        Alias get = new Alias(SOURCE, "pod", new TimeSeriesMetadataPath(unsetSource, typed.toAttribute(), Literal.keyword(SOURCE, "pod")));
        for (Alias derived : List.of(unset, get)) {
            LogicalPlan plan = read(new Eval(SOURCE, new Eval(SOURCE, source, List.of(typed)), List.of(derived)));
            var e = expectThrows(VerificationException.class, () -> LowerLabelContainerToKeyword.lower(plan));
            assertThat(
                e.getMessage(),
                containsString("leaving labels out of _timeseries requires a typed label container [without (pod)]")
            );
        }
    }
}
