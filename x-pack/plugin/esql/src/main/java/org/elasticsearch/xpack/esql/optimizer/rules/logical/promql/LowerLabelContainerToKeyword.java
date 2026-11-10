/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical.promql;

import org.elasticsearch.xpack.esql.VerificationException;
import org.elasticsearch.xpack.esql.analysis.AnalyzerContext;
import org.elasticsearch.xpack.esql.common.Failure;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.NameId;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.convert.ToString;
import org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.AbstractTimeSeriesMetadataFunction;
import org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.ToTimeSeriesMetadata;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.Project;
import org.elasticsearch.xpack.esql.plan.logical.promql.PromqlCommand;
import org.elasticsearch.xpack.esql.rule.ParameterizedRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Temporary compatibility for nodes before 9.7, which cannot read the typed label container: lowers it in a translated PromQL
 * plan to the keyword {@code _timeseries} whenever such a node participates ({@link PromqlCommand#usesLabelContainer}).
 * <p>
 * Translation and {@link PushDownPromqlLabels} produce one representation: the series read their label container as keyword
 * views of the source, cast to a typed label container above the collapse ({@link ToTimeSeriesMetadata}). The keyword form is that plan
 * without the casts: each view is the keyword column it was cast from, and the label container of a series without labels is
 * null. What has no keyword form fails: reading a label out of the label container, or leaving labels out of it after the collapse
 * ({@link AbstractTimeSeriesMetadataFunction}).
 * <p>
 * Remove the rule once every supported version reads the typed label container.
 */
public final class LowerLabelContainerToKeyword extends ParameterizedRule<LogicalPlan, LogicalPlan, AnalyzerContext> {

    @Override
    public LogicalPlan apply(LogicalPlan plan, AnalyzerContext context) {
        if (PromqlCommand.usesLabelContainer(context) || plan.anyMatch(LowerLabelContainerToKeyword::readsTyped) == false) {
            return plan;
        }
        return lower(plan);
    }

    /** {@code plan} over a keyword label container. */
    static LogicalPlan lower(LogicalPlan plan) {
        return new Lowering().lower(plan);
    }

    private static boolean readsTyped(LogicalPlan node) {
        return node.expressions().stream().anyMatch(expression -> expression.anyMatch(LowerLabelContainerToKeyword::isTyped));
    }

    private static boolean isTyped(Expression expression) {
        return expression.resolved() && expression.dataType() == DataType.TSMD_DATA_TYPE;
    }

    /**
     * A cast of a keyword label container to typed, or the rendering of a typed label container as keyword: both are the keyword column.
     */
    private static boolean isCast(Expression expression) {
        return (expression instanceof ToTimeSeriesMetadata cast && cast.field() instanceof Literal == false)
            || (expression instanceof ToString render && isTyped(render.field()));
    }

    /** One bottom-up pass; each node reads the columns of its input as they were lowered. */
    private static final class Lowering {
        /** Each column of a lowered node, by id. */
        private final Map<NameId, Attribute> lowered = new HashMap<>();
        /** The keyword column each dropped cast stood for, by the cast's id. */
        private final Map<NameId, Attribute> casts = new HashMap<>();
        /** Projections left bare by dropping the casts over them: a projection above only reorders their columns. */
        private final Set<LogicalPlan> bare = Collections.newSetFromMap(new IdentityHashMap<>());

        LogicalPlan lower(LogicalPlan plan) {
            return plan.transformUp(node -> {
                LogicalPlan rewritten = switch (node) {
                    case Eval eval -> withoutCasts(eval);
                    case Project project -> project.withProjections(project.projections().stream().map(this::withoutCast).toList());
                    default -> node;
                };
                rewritten = rewritten.transformExpressionsOnly(Expression.class, this::lower);
                if (rewritten instanceof Project outer && bare.contains(outer.child())) {
                    rewritten = reorder(outer, (Project) outer.child());
                }
                rewritten.output().forEach(attribute -> lowered.put(attribute.id(), attribute));
                return rewritten;
            });
        }

        /** {@code eval} without the fields that only cast: their readers read the keyword column. */
        private LogicalPlan withoutCasts(Eval eval) {
            var fields = new ArrayList<Alias>();
            for (Alias field : eval.fields()) {
                if (isCast(field.child())) {
                    casts.put(field.id(), (Attribute) lower(field.child()));
                } else {
                    fields.add(field);
                }
            }
            if (fields.isEmpty() && eval.child() instanceof Project projection) {
                bare.add(projection);
            }
            return fields.size() == eval.fields().size() ? eval
                : fields.isEmpty() ? eval.child()
                : new Eval(eval.source(), eval.child(), fields);
        }

        /** {@code inner} in the order {@code outer} projects its columns. */
        private static Project reorder(Project outer, Project inner) {
            Map<NameId, NamedExpression> byId = new HashMap<>();
            inner.projections().forEach(projection -> byId.put(projection.id(), projection));
            var projections = new ArrayList<NamedExpression>(outer.projections().size());
            for (NamedExpression projection : outer.projections()) {
                NamedExpression column = byId.get(projection.id());
                if (column == null || projection instanceof Attribute == false) {
                    return outer;
                }
                projections.add(column);
            }
            return new Project(outer.source(), inner.child(), projections);
        }

        private NamedExpression withoutCast(NamedExpression projection) {
            if (projection instanceof Alias alias && isCast(alias.child())) {
                Attribute column = (Attribute) lower(alias.child());
                casts.put(alias.id(), column);
                return column;
            }
            return projection;
        }

        /** {@code expression} over a keyword label container. Top-down: a cast is gone before its operand is looked at. */
        private Expression lower(Expression expression) {
            return switch (expression) {
                case ToTimeSeriesMetadata cast when cast.field() instanceof Literal -> new Literal(cast.source(), null, DataType.KEYWORD);
                case ToTimeSeriesMetadata cast -> lower(cast.field());
                case ToString render when isTyped(render.field()) -> lower(render.field());
                case AbstractTimeSeriesMetadataFunction function -> throw new VerificationException(
                    List.of(
                        Failure.fail(
                            function,
                            "leaving labels out of _timeseries requires a typed label container [{}]",
                            function.sourceText()
                        )
                    )
                );
                case Attribute attribute when casts.containsKey(attribute.id()) -> casts.get(attribute.id());
                case Attribute attribute when isTyped(attribute) -> {
                    Attribute below = lowered.get(attribute.id());
                    yield below != null && isTyped(below) == false ? below : attribute.withDataType(DataType.KEYWORD);
                }
                default -> {
                    List<Expression> children = expression.children();
                    List<Expression> rewritten = children.stream().map(this::lower).toList();
                    yield rewritten.equals(children) ? expression : expression.replaceChildren(rewritten);
                }
            };
        }
    }
}
