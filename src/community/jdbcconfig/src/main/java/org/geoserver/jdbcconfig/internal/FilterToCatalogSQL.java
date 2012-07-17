package org.geoserver.jdbcconfig.internal;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.geotools.filter.Capabilities;
import org.geotools.filter.LikeFilterImpl;
import org.opengis.filter.And;
import org.opengis.filter.ExcludeFilter;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterVisitor;
import org.opengis.filter.Id;
import org.opengis.filter.IncludeFilter;
import org.opengis.filter.MultiValuedFilter.MatchAction;
import org.opengis.filter.Not;
import org.opengis.filter.Or;
import org.opengis.filter.PropertyIsBetween;
import org.opengis.filter.PropertyIsEqualTo;
import org.opengis.filter.PropertyIsGreaterThan;
import org.opengis.filter.PropertyIsGreaterThanOrEqualTo;
import org.opengis.filter.PropertyIsLessThan;
import org.opengis.filter.PropertyIsLessThanOrEqualTo;
import org.opengis.filter.PropertyIsLike;
import org.opengis.filter.PropertyIsNil;
import org.opengis.filter.PropertyIsNotEqualTo;
import org.opengis.filter.PropertyIsNull;
import org.opengis.filter.capability.FilterCapabilities;
import org.opengis.filter.expression.Add;
import org.opengis.filter.expression.Divide;
import org.opengis.filter.expression.ExpressionVisitor;
import org.opengis.filter.expression.Function;
import org.opengis.filter.expression.Literal;
import org.opengis.filter.expression.Multiply;
import org.opengis.filter.expression.NilExpression;
import org.opengis.filter.expression.PropertyName;
import org.opengis.filter.expression.Subtract;
import org.opengis.filter.spatial.BBOX;
import org.opengis.filter.spatial.Beyond;
import org.opengis.filter.spatial.Contains;
import org.opengis.filter.spatial.Crosses;
import org.opengis.filter.spatial.DWithin;
import org.opengis.filter.spatial.Disjoint;
import org.opengis.filter.spatial.Equals;
import org.opengis.filter.spatial.Intersects;
import org.opengis.filter.spatial.Overlaps;
import org.opengis.filter.spatial.Touches;
import org.opengis.filter.spatial.Within;
import org.opengis.filter.temporal.After;
import org.opengis.filter.temporal.AnyInteracts;
import org.opengis.filter.temporal.Before;
import org.opengis.filter.temporal.Begins;
import org.opengis.filter.temporal.BegunBy;
import org.opengis.filter.temporal.During;
import org.opengis.filter.temporal.EndedBy;
import org.opengis.filter.temporal.Ends;
import org.opengis.filter.temporal.Meets;
import org.opengis.filter.temporal.MetBy;
import org.opengis.filter.temporal.OverlappedBy;
import org.opengis.filter.temporal.TContains;
import org.opengis.filter.temporal.TEquals;
import org.opengis.filter.temporal.TOverlaps;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

/**
 */
public class FilterToCatalogSQL implements FilterVisitor, ExpressionVisitor {

    public static final FilterCapabilities CAPABILITIES;
    static {
        Capabilities builder = new Capabilities();
        builder.addType(PropertyIsEqualTo.class);
        builder.addType(PropertyIsNotEqualTo.class);
        builder.addType(PropertyIsLike.class);
        builder.addType(PropertyIsNull.class);// whether a property exists at all
        builder.addType(PropertyIsNil.class);// whether the property exists AND it's value is null
        builder.addType(And.class);
        builder.addType(Or.class);

        CAPABILITIES = builder.getContents();
    }

    private final Class<?> queryType;

    private final DbMappings dbMappings;

    private Map<String, Object> namedParams;

    public FilterToCatalogSQL(Class<?> queryType, DbMappings dbMappings) {
        this.queryType = queryType;
        this.dbMappings = dbMappings;
        namedParams = Maps.newHashMap();
        List<Integer> concreteQueryTypes = dbMappings.getConcreteQueryTypes(queryType);
        namedParams.put("types", concreteQueryTypes);
    }

    /**
     * @return
     */
    public Map<String, Object> getNamedParameters() {
        return namedParams;
    }

    private StringBuilder append(Object extraData, String... s) {
        StringBuilder sb = (StringBuilder) extraData;
        for (String p : s) {
            sb.append(p);
        }
        return sb;
    }

    /**
     * @see org.opengis.filter.FilterVisitor#visitNullFilter(java.lang.Object)
     */
    @Override
    public Object visitNullFilter(Object extraData) {
        throw new UnsupportedOperationException("Do not use null as filter");
    }

    /**
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.ExcludeFilter,
     *      java.lang.Object)
     */
    @Override
    public Object visit(ExcludeFilter filter, Object extraData) {
        append(extraData, "(1=0) /* EXCLUDE */\n");
        return extraData;
    }

    /**
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.IncludeFilter,
     *      java.lang.Object)
     */
    @Override
    public Object visit(IncludeFilter filter, Object extraData) {
        append(extraData, "(1=1) /* INCLUDE */\n");
        return extraData;
    }

    /**
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsEqualTo,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsEqualTo filter, Object extraData) {
        PropertyName expression1 = (PropertyName) filter.getExpression1();
        Literal expression2 = (Literal) filter.getExpression2();
        MatchAction matchAction = filter.getMatchAction();
        boolean matchingCase = filter.isMatchingCase();

        final String propertyTypesParam = propertyTypesParam(expression1);

        final String expectedValue = expression2.evaluate(null, String.class);
        String valueParam = newParam("value", expectedValue);

        switch (matchAction) {
        // TODO: respect match action
        case ALL:
            break;
        case ANY:
            break;
        case ONE:
            break;
        default:
            throw new IllegalArgumentException("MatchAction: " + matchAction);
        }

        StringBuilder builder = append(extraData,
                "oid IN (SELECT oid FROM object_property WHERE property_type IN (:",
                propertyTypesParam, ") AND value = :", valueParam, ") /* ", filter.toString(),
                " */ \n");
        return builder;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsLike,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsLike filter, Object extraData) {
        final PropertyName expression1 = (PropertyName) filter.getExpression();
        // TODO: check for indexed property name

        final String propertyTypesParam = propertyTypesParam(expression1);

        final String literal = filter.getLiteral();
        final MatchAction matchAction = filter.getMatchAction();
        final char esc = filter.getEscape().charAt(0);
        final char multi = filter.getWildCard().charAt(0);
        final char single = filter.getSingleChar().charAt(0);
        final boolean matchCase = filter.isMatchingCase();

        final String pattern = LikeFilterImpl
                .convertToSQL92(esc, multi, single, matchCase, literal);

        String valueCol = matchCase ? "value" : "UPPER(value)";

        StringBuilder builder = append(extraData,
                "oid IN (SELECT oid FROM object_property WHERE property_type IN (:",
                propertyTypesParam, ") AND ", valueCol, " LIKE '", pattern, "') /* ",
                filter.toString(), " */ \n");
        return builder;
    }

    private String propertyTypesParam(final PropertyName property) {

        final String propertyTypesParam;
        final Set<PropertyType> propertyTypes;

        String propertyName = property.getPropertyName();
        propertyTypes = dbMappings.getPropertyTypes(queryType, propertyName);

        Preconditions.checkState(!propertyTypes.isEmpty(), "Found no mapping for property '"
                + property + "' of type " + queryType.getName());

        List<Integer> propTypeIds = new ArrayList<Integer>(propertyTypes.size());
        for (PropertyType pt : propertyTypes) {
            Integer propertyTypeId = pt.getOid();
            propTypeIds.add(propertyTypeId);
        }
        propertyTypesParam = newParam("ptype", propTypeIds);
        return propertyTypesParam;
    }

    /**
     * @param string
     * @param propertyTypes
     * @return
     */
    private String newParam(String paramNamePrefix, Object paramValue) {
        int sufix = 0;
        while (true) {
            String paramName = paramNamePrefix + sufix;
            if (!namedParams.containsKey(paramName)) {
                namedParams.put(paramName, paramValue);
                return paramName;
            }
            sufix++;
        }
    }

    /**
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsNotEqualTo,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsNotEqualTo filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.And, java.lang.Object)
     */
    @Override
    public Object visit(And filter, Object extraData) {
        StringBuilder sql = (StringBuilder) extraData;

        List<Filter> children = filter.getChildren();
        checkArgument(children.size() > 0);
        sql.append("(\n\t");

        for (Iterator<Filter> it = children.iterator(); it.hasNext();) {
            Filter child = it.next();
            sql = (StringBuilder) child.accept(this, sql);
            if (it.hasNext()) {
                sql = append(extraData, "\tAND\n\t");
            }
        }
        sql.append(")");
        return sql;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.Or, java.lang.Object)
     */
    @Override
    public Object visit(Or filter, Object extraData) {
        StringBuilder sql = (StringBuilder) extraData;

        List<Filter> children = filter.getChildren();
        checkArgument(children.size() > 0);
        sql.append("(");
        for (Iterator<Filter> it = children.iterator(); it.hasNext();) {
            Filter child = it.next();
            sql = (StringBuilder) child.accept(this, sql);
            if (it.hasNext()) {
                sql = append(extraData, "\tOR\n\t");
            }
        }
        sql.append(")");
        return sql;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.Id, java.lang.Object)
     */
    @Override
    public Object visit(Id filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.Not, java.lang.Object)
     */
    @Override
    public Object visit(Not filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsBetween,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsBetween filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsGreaterThan,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsGreaterThan filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsGreaterThanOrEqualTo,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsGreaterThanOrEqualTo filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsLessThan,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsLessThan filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsLessThanOrEqualTo,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsLessThanOrEqualTo filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsNull,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsNull filter, Object extraData) {
        final PropertyName propertyName = (PropertyName) filter.getExpression();
        final String propertyTypesParam = propertyTypesParam(propertyName);

        StringBuilder builder = append(extraData,
                "oid IN (select oid from object_property where property_type in (:",
                propertyTypesParam, ") and value IS NULL) /* ", filter.toString(), " */ \n");
        return builder;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.PropertyIsNil,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyIsNil filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.BBOX,
     *      java.lang.Object)
     */
    @Override
    public Object visit(BBOX filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.Beyond,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Beyond filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.Contains,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Contains filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.Crosses,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Crosses filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.Disjoint,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Disjoint filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.DWithin,
     *      java.lang.Object)
     */
    @Override
    public Object visit(DWithin filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.Equals,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Equals filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.Intersects,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Intersects filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.Overlaps,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Overlaps filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.Touches,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Touches filter, Object extraData) {

        return extraData;
    }

    /**
     * @param filter
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.spatial.Within,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Within filter, Object extraData) {

        return extraData;
    }

    /**
     * @param after
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.After,
     *      java.lang.Object)
     */
    @Override
    public Object visit(After after, Object extraData) {

        return extraData;
    }

    /**
     * @param anyInteracts
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.AnyInteracts,
     *      java.lang.Object)
     */
    @Override
    public Object visit(AnyInteracts anyInteracts, Object extraData) {

        return extraData;
    }

    /**
     * @param before
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.Before,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Before before, Object extraData) {

        return extraData;
    }

    /**
     * @param begins
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.Begins,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Begins begins, Object extraData) {

        return extraData;
    }

    /**
     * @param begunBy
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.BegunBy,
     *      java.lang.Object)
     */
    @Override
    public Object visit(BegunBy begunBy, Object extraData) {

        return extraData;
    }

    /**
     * @param during
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.During,
     *      java.lang.Object)
     */
    @Override
    public Object visit(During during, Object extraData) {

        return extraData;
    }

    /**
     * @param endedBy
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.EndedBy,
     *      java.lang.Object)
     */
    @Override
    public Object visit(EndedBy endedBy, Object extraData) {

        return extraData;
    }

    /**
     * @param ends
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.Ends,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Ends ends, Object extraData) {

        return extraData;
    }

    /**
     * @param meets
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.Meets,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Meets meets, Object extraData) {

        return extraData;
    }

    /**
     * @param metBy
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.MetBy,
     *      java.lang.Object)
     */
    @Override
    public Object visit(MetBy metBy, Object extraData) {

        return extraData;
    }

    /**
     * @param overlappedBy
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.OverlappedBy,
     *      java.lang.Object)
     */
    @Override
    public Object visit(OverlappedBy overlappedBy, Object extraData) {

        return extraData;
    }

    /**
     * @param contains
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.TContains,
     *      java.lang.Object)
     */
    @Override
    public Object visit(TContains contains, Object extraData) {

        return extraData;
    }

    /**
     * @param equals
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.TEquals,
     *      java.lang.Object)
     */
    @Override
    public Object visit(TEquals equals, Object extraData) {

        return extraData;
    }

    /**
     * @param contains
     * @param extraData
     * @return
     * @see org.opengis.filter.FilterVisitor#visit(org.opengis.filter.temporal.TOverlaps,
     *      java.lang.Object)
     */
    @Override
    public Object visit(TOverlaps contains, Object extraData) {

        return extraData;
    }

    /**
     * @param expression
     * @param extraData
     * @return
     * @see org.opengis.filter.expression.ExpressionVisitor#visit(org.opengis.filter.expression.NilExpression,
     *      java.lang.Object)
     */
    @Override
    public Object visit(NilExpression expression, Object extraData) {

        throw new UnsupportedOperationException();
    }

    /**
     * @param expression
     * @param extraData
     * @return
     * @see org.opengis.filter.expression.ExpressionVisitor#visit(org.opengis.filter.expression.Add,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Add expression, Object extraData) {

        throw new UnsupportedOperationException();
    }

    /**
     * @param expression
     * @param extraData
     * @return
     * @see org.opengis.filter.expression.ExpressionVisitor#visit(org.opengis.filter.expression.Divide,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Divide expression, Object extraData) {

        throw new UnsupportedOperationException();
    }

    /**
     * @param expression
     * @param extraData
     * @return
     * @see org.opengis.filter.expression.ExpressionVisitor#visit(org.opengis.filter.expression.Function,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Function expression, Object extraData) {

        throw new UnsupportedOperationException();
    }

    /**
     * @param expression
     * @param extraData
     * @return
     * @see org.opengis.filter.expression.ExpressionVisitor#visit(org.opengis.filter.expression.Literal,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Literal expression, Object extraData) {

        throw new UnsupportedOperationException();
    }

    /**
     * @param expression
     * @param extraData
     * @return
     * @see org.opengis.filter.expression.ExpressionVisitor#visit(org.opengis.filter.expression.Multiply,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Multiply expression, Object extraData) {

        throw new UnsupportedOperationException();
    }

    /**
     * @param expression
     * @param extraData
     * @return
     * @see org.opengis.filter.expression.ExpressionVisitor#visit(org.opengis.filter.expression.PropertyName,
     *      java.lang.Object)
     */
    @Override
    public Object visit(PropertyName expression, Object extraData) {

        throw new UnsupportedOperationException();
    }

    /**
     * @param expression
     * @param extraData
     * @return
     * @see org.opengis.filter.expression.ExpressionVisitor#visit(org.opengis.filter.expression.Subtract,
     *      java.lang.Object)
     */
    @Override
    public Object visit(Subtract expression, Object extraData) {

        throw new UnsupportedOperationException();
    }

}
