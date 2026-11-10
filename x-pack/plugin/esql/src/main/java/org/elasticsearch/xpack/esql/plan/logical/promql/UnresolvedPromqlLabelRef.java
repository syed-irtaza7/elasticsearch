/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xpack.esql.core.capabilities.Unresolvable;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.NameId;
import org.elasticsearch.xpack.esql.core.expression.Nullability;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A label in the declared output of a PromQL node, before translation: a label by name, or the _timeseries label container
 * holding every label not declared. The analyzer resolves the command's output against these; translation itself reads labels
 * off one label container.
 * <p>
 * A label container reference's unset is what the node leaves out of its input's label container: labels dropped from it
 * (without(...), label_replace destination, arith dropping __name__, ...).
 */
public final class UnresolvedPromqlLabelRef extends Attribute implements Unresolvable {

    private final @Nullable Set<String> unset;

    /** A reference to the promoted label name. */
    public static UnresolvedPromqlLabelRef label(Source source, String name) {
        return new UnresolvedPromqlLabelRef(source, name, null, null);
    }

    /** The label container of the operator's input after applying unset. */
    public static UnresolvedPromqlLabelRef labelContainer(Source source, Set<String> unset) {
        return new UnresolvedPromqlLabelRef(source, PromqlLabels.labelContainerName(Set.of()), unset, null);
    }

    /** A reference to what an element of a tsIdentity stands for: its label container, or one of its promoted labels. */
    public static UnresolvedPromqlLabelRef of(Source source, Attribute element) {
        return element instanceof UnresolvedPromqlLabelRef labelContainer && labelContainer.isLabelContainer()
            ? labelContainer(source, labelContainer.unset())
            : label(source, PromqlLabels.labelName(element));
    }

    /**
     * References for a plan's {@code tsIdentity}: the label container (if any), then each promoted label.
     * The tuple of those two is the series identity used for grouping, matching, and union dedup.
     */
    public static List<Attribute> tsIdentity(Source source, List<Attribute> tsIdentity) {
        var result = new ArrayList<Attribute>();
        UnresolvedPromqlLabelRef labelContainer = PromqlLabels.labelContainer(tsIdentity);
        if (labelContainer != null) {
            result.add(of(source, labelContainer));
        }
        for (Attribute attribute : tsIdentity) {
            if (PromqlLabels.isLabelContainer(attribute) == false) {
                result.add(of(source, attribute));
            }
        }
        return result;
    }

    public UnresolvedPromqlLabelRef(Source source, String name, @Nullable Set<String> unset, @Nullable NameId id) {
        super(source, null, name, id);
        this.unset = unset == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(unset));
    }

    /**
     * Labels this label container transform drops from its input blob (without, label_replace dst, ...).
     * Null for a promoted label. Once traced, absolute against the source.
     */
    public @Nullable Set<String> unset() {
        return unset;
    }

    public boolean isLabelContainer() {
        return unset != null;
    }

    /** Traced form: unset absolute against the source series, named via labelContainerName. */
    public UnresolvedPromqlLabelRef againstSource(Set<String> unset) {
        assert isLabelContainer() : "only the label container leaves labels out [" + name() + "]";
        return new UnresolvedPromqlLabelRef(source(), PromqlLabels.labelContainerName(unset), unset, id());
    }

    /** True when both read the same column. */
    public boolean reads(UnresolvedPromqlLabelRef other) {
        return name().equals(other.name()) && Objects.equals(unset, other.unset);
    }

    @Override
    public String unresolvedMessage() {
        return "unresolved PromQL label [" + name() + "]";
    }

    @Override
    public boolean resolved() {
        return false;
    }

    @Override
    public DataType dataType() {
        return DataType.KEYWORD;
    }

    @Override
    protected NodeInfo<UnresolvedPromqlLabelRef> info() {
        return NodeInfo.create(this, UnresolvedPromqlLabelRef::new, name(), unset, id());
    }

    @Override
    protected Attribute clone(
        Source source,
        String qualifier,
        String name,
        DataType type,
        Nullability nullability,
        NameId id,
        boolean synthetic
    ) {
        return new UnresolvedPromqlLabelRef(source, name, unset, id);
    }

    @Override
    protected String label() {
        return "l";
    }

    @Override
    public boolean isDimension() {
        return false;
    }

    @Override
    public boolean isMetric() {
        return false;
    }

    @Override
    public String getWriteableName() {
        throw new UnsupportedOperationException("doesn't escape the analyzer");
    }

    @Override
    public void writeTo(StreamOutput out) {
        throw new UnsupportedOperationException("doesn't escape the analyzer");
    }

    @Override
    protected int innerHashCode(boolean ignoreIds) {
        return Objects.hash(super.innerHashCode(ignoreIds), unset);
    }

    @Override
    protected boolean innerEquals(Object o, boolean ignoreIds) {
        UnresolvedPromqlLabelRef other = (UnresolvedPromqlLabelRef) o;
        return super.innerEquals(o, ignoreIds) && Objects.equals(unset, other.unset);
    }
}
