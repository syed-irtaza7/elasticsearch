/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql;

import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.convert.ToDouble;
import org.elasticsearch.xpack.esql.expression.promql.function.FunctionType;
import org.elasticsearch.xpack.esql.expression.promql.function.PromqlFunctionDefinition;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationContext.IntermediateResult;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.LabelMatcher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Base class for PromQL histogram functions that evaluate classic histogram buckets grouped by their {@code le} label.
 */
public abstract sealed class HistogramFunctionCall extends PromqlFunctionCall permits HistogramFraction, HistogramQuantile {
    public static final String LE_LABEL = "le";
    /**
     * Labels a classic histogram function leaves out of its input's label container: {@code le}, which it collapses, and the metric
     * name, which it drops like Prometheus does. Within one metric bucket rows differ by {@code le} alone.
     */
    private static final Set<String> COLLAPSED_LABELS = Set.of(LE_LABEL, LabelMatcher.NAME);

    private List<Attribute> output;

    protected HistogramFunctionCall(Source source, LogicalPlan child, PromqlFunctionDefinition definition, List<Expression> parameters) {
        super(source, child, definition, parameters);
    }

    /**
     * Builds the aggregate expression for this function call:
     * The aggregate expression will be invoked with the bucket counts and their upper bounds (the le labels).
     */
    public abstract Expression buildAggregateFunction(Expression count, Expression upperBound);

    @Override
    public final List<Attribute> output() {
        if (output == null) {
            var keys = new ArrayList<Attribute>();
            for (Attribute attr : child().output()) {
                if (attr instanceof UnresolvedPromqlLabelRef labelContainer && labelContainer.isLabelContainer()) {
                    var excluded = new LinkedHashSet<>(labelContainer.unset());
                    excluded.addAll(COLLAPSED_LABELS);
                    keys.add(UnresolvedPromqlLabelRef.labelContainer(source(), excluded));
                } else if (MetadataAttribute.isTimeSeriesAttributeName(attr.name())
                    || LE_LABEL.equals(PromqlLabels.labelName(attr)) == false) {
                        keys.add(attr);
                    }
            }
            output = List.copyOf(keys);
        }
        return output;
    }

    @Override
    public final FunctionType functionType() {
        return FunctionType.HISTOGRAM;
    }

    @Override
    public boolean isIdentityTransparent() {
        // Reshapes labels (drops `le`) but is not a grouping boundary for relabel placement: a relabel below it still
        // feeds the enclosing aggregation.
        return true;
    }

    @Override
    public IntermediateResult translate(TranslationContext context) {
        IntermediateResult result = context.translate(child());
        if (result.kind().constant) {
            return result;
        }

        // Native histograms - distinguishable only by the value type - are regular value transformations. They carry no
        // `le`, so the inferred identity holds for them too.
        if (result.value().resolved() && result.value().dataType().isHistogram()) {
            return applyValueFunction(context, result);
        }

        // Classic counter-backed histograms evaluate their buckets by `le`: the series collapse as they are, then group by
        // their labels but `le`, reading each bucket's bound off it. Prometheus drops the metric name from the result.
        // An input without `le` has a null bound and evaluates to nothing; the verifier warns, like Prometheus.
        if (result.kind().afterInitialAggregation == false) {
            result = context.emitInitialAggregate(result, result.value());
        }
        // Bucket counts are consumed as doubles; counter buckets are frequently integer/long typed, so cast explicitly.
        Expression count = new ToDouble(source(), result.value());
        Expression labels = result.labels();
        Expression le = labels == null ? new Literal(source(), null, DataType.KEYWORD) : TranslationContext.get(labels, LE_LABEL);
        IntermediateResult quantiles = context.emitAggregate(
            result,
            labels == null ? null : TranslationContext.unset(labels, List.of(LE_LABEL)),
            buildAggregateFunction(count, le)
        );
        return quantiles.labels() == null ? quantiles : quantiles.withLabels(labels(quantiles.labels()));
    }

    /** Prometheus drops the metric name from a histogram function's result. */
    @Override
    protected Expression labels(Expression input) {
        return TranslationContext.unset(input, COLLAPSED_LABELS);
    }
}
