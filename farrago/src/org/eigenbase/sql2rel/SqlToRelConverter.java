/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2002 SQLstream, Inc.
// Copyright (C) 2005 Dynamo BI Corporation
// Portions Copyright (C) 2003 John V. Sichi
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package org.eigenbase.sql2rel;

import java.math.*;

import java.util.*;
import java.util.logging.*;

import openjava.mop.*;

import org.eigenbase.rel.*;
import org.eigenbase.rel.metadata.*;
import org.eigenbase.relopt.*;
import org.eigenbase.reltype.*;
import org.eigenbase.resource.*;
import org.eigenbase.rex.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.fun.*;
import org.eigenbase.sql.parser.*;
import org.eigenbase.sql.type.*;
import org.eigenbase.sql.util.*;
import org.eigenbase.sql.validate.*;
import org.eigenbase.trace.*;
import org.eigenbase.util.*;
import org.eigenbase.util14.*;


/**
 * Converts a SQL parse tree (consisting of {@link org.eigenbase.sql.SqlNode}
 * objects) into a relational algebra expression (consisting of {@link
 * org.eigenbase.rel.RelNode} objects).
 *
 * <p>The public entry points are: {@link #convertQuery}, {@link
 * #convertExpression(SqlNode)}.
 *
 * @author jhyde
 * @version $Id$
 * @since Oct 10, 2003
 */
public class SqlToRelConverter
{
    //~ Static fields/initializers ---------------------------------------------

    protected static final Logger sqlToRelTracer =
        EigenbaseTrace.getSqlToRelTracer();

    //~ Instance fields --------------------------------------------------------

    protected final SqlValidator validator;
    protected final RexBuilder rexBuilder;
    private final RelOptConnection connection;
    protected final RelOptSchema schema;
    protected final RelOptCluster cluster;
    private DefaultValueFactory defaultValueFactory;
    private SubqueryConverter subqueryConverter;
    protected final List<RelNode> leaves = new ArrayList<RelNode>();
    private final List<SqlDynamicParam> dynamicParamSqlNodes =
        new ArrayList<SqlDynamicParam>();
    private final SqlOperatorTable opTab;
    private boolean shouldConvertTableAccess;
    protected final RelDataTypeFactory typeFactory;
    private final SqlNodeToRexConverter exprConverter;
    private boolean decorrelationEnabled;
    private boolean trimUnusedFields;
    private boolean shouldCreateValuesRel;
    private boolean isExplain;
    private int nDynamicParamsInExplain;

    /**
     * Fields used in name resolution for correlated subqueries.
     */
    private final Map<String, DeferredLookup> mapCorrelToDeferred =
        new HashMap<String, DeferredLookup>();
    private int nextCorrel = 0;
    private final String correlPrefix = "$cor";

    /**
     * Fields used in decorrelation.
     */
    private final Map<String, RelNode> mapCorrelToRefRel =
        new HashMap<String, RelNode>();

    private final SortedMap<CorrelatorRel.Correlation, CorrelatorRel>
        mapCorVarToCorRel =
            new TreeMap<CorrelatorRel.Correlation, CorrelatorRel>();

    private final Map<RelNode, SortedSet<CorrelatorRel.Correlation>>
        mapRefRelToCorVar =
            new HashMap<RelNode, SortedSet<CorrelatorRel.Correlation>>();

    private final Map<RexFieldAccess, CorrelatorRel.Correlation>
        mapFieldAccessToCorVar =
            new HashMap<RexFieldAccess, CorrelatorRel.Correlation>();

    /**
     * Stack of names of datasets requested by the <code>
     * TABLE(SAMPLE(&lt;datasetName&gt;, &lt;query&gt;))</code> construct.
     */
    private final Stack<String> datasetStack = new Stack<String>();

    /**
     * Mapping of non-correlated subqueries that have been converted to their
     * equivalent constants. Used to avoid re-evaluating the subquery if it's
     * already been evaluated.
     */
    private final Map<SqlNode, RexNode> mapConvertedNonCorrSubqs =
        new HashMap<SqlNode, RexNode>();

    /**
     * Number of system fields.
     */
    protected final int sysFieldCount;

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a converter.
     *
     * @param validator Validator
     * @param schema Schema
     * @param env Environment
     * @param connection Connection
     * @param rexBuilder Rex builder
     *
     * @pre connection != null
     */
    public SqlToRelConverter(
        SqlValidator validator,
        RelOptSchema schema,
        Environment env,
        RelOptPlanner planner,
        RelOptConnection connection,
        RexBuilder rexBuilder)
    {
        Util.pre(connection != null, "connection != null");
        this.opTab =
            (validator
                == null) ? SqlStdOperatorTable.instance()
            : validator.getOperatorTable();
        this.validator = validator;
        this.schema = schema;
        this.connection = connection;
        this.defaultValueFactory = new NullDefaultValueFactory();
        this.subqueryConverter = new NoOpSubqueryConverter();
        this.rexBuilder = rexBuilder;
        this.typeFactory = rexBuilder.getTypeFactory();
        RelOptQuery query = new RelOptQuery(planner);
        this.cluster = query.createCluster(env, typeFactory, rexBuilder);
        this.shouldConvertTableAccess = true;
        this.exprConverter =
            new SqlNodeToRexConverterImpl(new StandardConvertletTable());
        decorrelationEnabled = true;
        trimUnusedFields = false;
        shouldCreateValuesRel = true;
        isExplain = false;
        nDynamicParamsInExplain = 0;
        sysFieldCount = validator.getSystemFields().size();
    }

    //~ Methods ----------------------------------------------------------------

    /**
     * @return the RelOptCluster in use.
     */
    public RelOptCluster getCluster()
    {
        return cluster;
    }

    /**
     * Returns the row-expression builder.
     */
    public RexBuilder getRexBuilder()
    {
        return rexBuilder;
    }

    /**
     * Returns the number of dynamic parameters encountered during translation;
     * this must only be called after {@link #convertQuery}.
     *
     * @return number of dynamic parameters
     */
    public int getDynamicParamCount()
    {
        return dynamicParamSqlNodes.size();
    }

    /**
     * Returns the type inferred for a dynamic parameter.
     *
     * @param index 0-based index of dynamic parameter
     *
     * @return inferred type, never null
     */
    public RelDataType getDynamicParamType(int index)
    {
        SqlNode sqlNode = dynamicParamSqlNodes.get(index);
        if (sqlNode == null) {
            throw Util.needToImplement("dynamic param type inference");
        }
        return validator.getValidatedNodeType(sqlNode);
    }

    /**
     * Returns the current count of the number of dynamic parameters in an
     * EXPLAIN PLAN statement.
     *
     * @param increment if true, increment the count
     *
     * @return the current count before the optional increment
     */
    public int getDynamicParamCountInExplain(boolean increment)
    {
        int retVal = nDynamicParamsInExplain;
        if (increment) {
            ++nDynamicParamsInExplain;
        }
        return retVal;
    }

    /**
     * @return mapping of non-correlated subqueries that have been converted to
     * the constants that they evaluate to
     */
    public Map<SqlNode, RexNode> getMapConvertedNonCorrSubqs()
    {
        return mapConvertedNonCorrSubqs;
    }

    /**
     * Adds to the current map of non-correlated converted subqueries the
     * elements from another map that contains non-correlated subqueries that
     * have been converted by another SqlToRelConverter.
     *
     * @param alreadyConvertedNonCorrSubqs the other map
     */
    public void addConvertedNonCorrSubqs(
        Map<SqlNode, RexNode> alreadyConvertedNonCorrSubqs)
    {
        mapConvertedNonCorrSubqs.putAll(alreadyConvertedNonCorrSubqs);
    }

    /**
     * Set a new DefaultValueFactory. To have any effect, this must be called
     * before any convert method.
     *
     * @param factory new DefaultValueFactory
     */
    public void setDefaultValueFactory(DefaultValueFactory factory)
    {
        defaultValueFactory = factory;
    }

    /**
     * Sets a new SubqueryConverter. To have any effect, this must be called
     * before any convert method.
     *
     * @param converter new SubqueryConverter
     */
    public void setSubqueryConverter(SubqueryConverter converter)
    {
        subqueryConverter = converter;
    }

    /**
     * Indicates that the current statement is part of an EXPLAIN PLAN statement
     *
     * @param nDynamicParams number of dynamic parameters in the statement
     */
    public void setIsExplain(int nDynamicParams)
    {
        isExplain = true;
        nDynamicParamsInExplain = nDynamicParams;
    }

    /**
     * Controls whether table access references are converted to physical rels
     * immediately. The optimizer doesn't like leaf rels to have {@link
     * CallingConvention#NONE}. However, if we are doing further conversion
     * passes (e.g. {@link RelStructuredTypeFlattener}), then we may need to
     * defer conversion. To have any effect, this must be called before any
     * convert method.
     *
     * @param enabled true for immediate conversion (the default); false to
     * generate logical TableAccessRel instances
     */
    public void enableTableAccessConversion(boolean enabled)
    {
        shouldConvertTableAccess = enabled;
    }

    /**
     * Controls whether instances of {@link ValuesRel} are generated. These may
     * not be supported by all physical implementations. To have any effect,
     * this must be called before any convert method.
     *
     * @param enabled true to allow ValuesRel to be generated (the default);
     * false to force substitution of ProjectRel+OneRowRel instead
     */
    public void enableValuesRelCreation(boolean enabled)
    {
        shouldCreateValuesRel = enabled;
    }

    private void checkConvertedType(SqlNode query, RelNode result)
    {
        if (!query.isA(SqlKind.DML)) {
            // Verify that conversion from SQL to relational algebra did
            // not perturb any type information.  (We can't do this if the
            // SQL statement is something like an INSERT which has no
            // validator type information associated with its result,
            // hence the namespace check above.)
            RelDataType convertedRowType = result.getRowType();
            if (!checkConvertedRowType(query, convertedRowType)) {
                RelDataType validatedRowType =
                    validator.getValidatedNodeType(query);
                validatedRowType = uniquifyFields(validatedRowType);
                throw Util.newInternal(
                    "Conversion to relational algebra failed to preserve "
                    + "datatypes:" + Util.lineSeparator
                    + "validated type:" + Util.lineSeparator
                    + validatedRowType.getFullTypeString() + Util.lineSeparator
                    + "converted type:" + Util.lineSeparator
                    + convertedRowType.getFullTypeString() + Util.lineSeparator
                    + "rel:" + Util.lineSeparator
                    + RelOptUtil.toString(result));
            }
        }
    }

    public RelNode flattenTypes(
        RelNode rootRel,
        boolean restructure)
    {
        RelStructuredTypeFlattener typeFlattener =
            new RelStructuredTypeFlattener(rexBuilder);
        RelNode newRootRel = typeFlattener.rewrite(rootRel, restructure);

        // There are three maps constructed during convertQuery which need to to
        // be maintained for use in decorrelation. 1. mapRefRelToCorVar: - map a
        // rel node to the coorrelated variables it references 2.
        // mapCorVarToCorRel: - map a correlated variable to the correlatorRel
        // providing it 3. mapFieldAccessToCorVar: - map a rex field access to
        // the cor var it represents. because typeFlattener does not clone or
        // modify a correlated field access this map does not need to be
        // updated.
        typeFlattener.updateRelInMap(mapRefRelToCorVar);
        typeFlattener.updateRelInMap(mapCorVarToCorRel);

        return newRootRel;
    }

    /**
     * If subquery is correlated and decorrelation is enabled, performs
     * decorrelation.
     *
     * @param query Query
     * @param rootRel Root relational expression
     * @return New root relational expression after decorrelation
     */
    public RelNode decorrelate(SqlNode query, RelNode rootRel)
    {
        RelNode result = rootRel;
        if (enableDecorrelation()
            && hasCorrelation())
        {
            result = decorrelateQuery(result);
            checkConvertedType(query, result);
        }
        return result;
    }

    /**
     * Walks over a tree of relational expressions, replacing each
     * {@link RelNode} with a 'slimmed down' relational expression that projects
     * only the fields required by its consumer.
     *
     * <p>This may make things easier for the optimizer, by removing crud that
     * would expand the search space, but is difficult for the optimizer itself
     * to do it, because optimizer rules must preserve the number and type of
     * fields. Hence, this transform that operates on the entire tree, similar
     * to the {@link RelStructuredTypeFlattener type-flattening transform}.
     *
     * <p>Currently this functionality is disabled in farrago/luciddb; the
     * default implementation of this method does nothing.
     *
     * @param rootRel Relational expression that is at the root of the tree
     * @return Trimmed relational expression
     */
    public RelNode trimUnusedFields(RelNode rootRel)
    {
        // Trim fields that are not used by their consumer.
        if (isTrimUnusedFields()) {
            final RelFieldTrimmer trimmer = newFieldTrimmer();
            rootRel = trimmer.trim(rootRel);
            boolean dumpPlan = sqlToRelTracer.isLoggable(Level.FINE);
            if (dumpPlan) {
                sqlToRelTracer.fine(
                    RelOptUtil.dumpPlan(
                        "Plan after trimming unused fields",
                        rootRel,
                        false,
                        SqlExplainLevel.EXPPLAN_ATTRIBUTES));
            }
        }
        return rootRel;
    }

    /**
     * Creates a RelFieldTrimmer.
     *
     * @return Field trimmer
     */
    protected RelFieldTrimmer newFieldTrimmer()
    {
        return new RelFieldTrimmer(validator);
    }

    /**
     * Converts an unvalidated query's parse tree into a relational expression.
     *
     * @param query Query to convert
     * @param needsValidation Whether to validate the query before converting;
     * <code>false</code> if the query has already been validated.
     * @param context Whether the query is top-level, say if its result will
     *     become a JDBC result set; <code>false</code> if the query will be
     *     part of a view.
     */
    public RelNode convertQuery(
        SqlNode query,
        final boolean needsValidation,
        final QueryContext context)
    {
        if (needsValidation) {
            query = validator.validate(query);
        }

        RelNode result = convertQueryRecursive(query, context);
        checkConvertedType(query, result);

        boolean dumpPlan = sqlToRelTracer.isLoggable(Level.FINE);
        if (dumpPlan) {
            sqlToRelTracer.fine(
                RelOptUtil.dumpPlan(
                    "Plan after converting SqlNode to RelNode",
                    result,
                    false,
                    SqlExplainLevel.EXPPLAN_ATTRIBUTES));
        }

        return result;
    }

    protected boolean checkConvertedRowType(
        SqlNode query,
        RelDataType convertedRowType)
    {
        RelDataType validatedRowType = validator.getRootNodeType(query);
        validatedRowType = uniquifyFields(validatedRowType);

        return RelOptUtil.equal(
            "validated row type",
            validatedRowType,
            "converted row type",
            convertedRowType,
            false);
    }

    protected RelDataType uniquifyFields(RelDataType rowType)
    {
        return validator.getTypeFactory().createStructType(
            RelOptUtil.getFieldTypeList(rowType),
            SqlValidatorUtil.uniquify(RelOptUtil.getFieldNameList(rowType)));
    }

    /**
     * Converts a SELECT statement's parse tree into a relational expression.
     */
    public RelNode convertSelect(SqlSelect select)
    {
        final SqlValidatorScope selectScope = validator.getWhereScope(select);
        final Blackboard bb = createBlackboard(selectScope, null);
        convertSelectImpl(bb, select);
        return bb.root;
    }

    /**
     * Factory method for creating translation workspace.
     */
    protected Blackboard createBlackboard(
        SqlValidatorScope scope,
        Map<String, RexNode> nameToNodeMap)
    {
        return new Blackboard(scope, nameToNodeMap);
    }

    /**
     * Implementation of {@link #convertSelect(SqlSelect)}; derived class may
     * override.
     */
    protected void convertSelectImpl(
        final Blackboard bb,
        SqlSelect select)
    {
        convertFrom(
            bb,
            select.getFrom());
        convertWhere(
            bb,
            select.getWhere());

        List<SqlNode> orderExprList = new ArrayList<SqlNode>();
        List<RelFieldCollation> collationList =
            new ArrayList<RelFieldCollation>();
        gatherOrderExprs(
            bb,
            select,
            select.getOrderList(),
            orderExprList,
            collationList);

        if (validator.isAggregate(select)) {
            convertAgg(
                bb,
                select,
                orderExprList);
        } else {
            convertSelectList(
                bb,
                select,
                orderExprList);
        }

        if (select.isDistinct()) {
            distinctify(bb, true);
        }
        convertOrder(select, bb, collationList, orderExprList);
        bb.setRoot(bb.root, true);
    }

    /**
     * Having translated 'SELECT ... FROM ... [GROUP BY ...] [HAVING ...]', adds
     * a relational expression to make the results unique.
     *
     * <p>If the SELECT clause contains duplicate expressions, adds {@link
     * ProjectRel}s so that we are grouping on the minimal set of keys. The
     * performance gain isn't huge, but it is difficult to detect these
     * duplicate expressions later.
     *
     * @param bb Blackboard
     * @param checkForDupExprs Check for duplicate expressions
     */
    private void distinctify(
        Blackboard bb,
        boolean checkForDupExprs)
    {
        // Look for duplicate expressions in the project.
        // Say we have 'select x, y, x, z'.
        // Then dups will be {[2, 0]}
        // and oldToNew will be {[0, 0], [1, 1], [2, 0], [3, 2]}
        RelNode rel = bb.root;
        if (checkForDupExprs && (rel instanceof ProjectRel)) {
            ProjectRel project = (ProjectRel) rel;
            final RexNode [] projectExprs = project.getProjectExps();
            List<Integer> origins = new ArrayList<Integer>();
            int dupCount = 0;
            for (int i = 0; i < projectExprs.length; i++) {
                int x = findExpr(projectExprs[i], projectExprs, i);
                if (x >= 0) {
                    origins.add(x);
                    ++dupCount;
                } else {
                    origins.add(i);
                }
            }
            if (dupCount == 0) {
                distinctify(bb, false);
                return;
            }

            Map<Integer, Integer> squished = new HashMap<Integer, Integer>();
            final RelDataTypeField [] fields = rel.getRowType().getFields();
            int fieldCount = fields.length - dupCount;
            RexNode [] newProjectExprs = new RexNode[fieldCount];
            String [] newFieldNames = new String[fieldCount];
            int k = 0; // number of non-dup exprs seen so far
            for (int i = 0; i < fields.length; i++) {
                if (origins.get(i).intValue() == i) {
                    newProjectExprs[k] =
                        new RexInputRef(
                            i,
                            fields[i].getType());
                    newFieldNames[k] = fields[i].getName();
                    squished.put(i, k);
                    ++k;
                }
            }
            rel =
                new ProjectRel(
                    cluster,
                    rel,
                    newProjectExprs,
                    newFieldNames,
                    ProjectRel.Flags.Boxed);

            bb.root = rel;
            distinctify(bb, false);
            rel = bb.root;

            // Create the expressions to reverse the mapping.
            // Project($0, $1, $0, $2).
            RexNode [] undoProjectExprs = new RexNode[fields.length];
            String [] undoFieldNames = new String[fields.length];
            for (int i = 0; i < fields.length; i++) {
                final Integer origin = origins.get(i);
                undoProjectExprs[i] =
                    new RexInputRef(
                        squished.get(origin),
                        fields[i].getType());
                undoFieldNames[i] = fields[i].getName();
            }

            rel =
                new ProjectRel(
                    cluster,
                    rel,
                    undoProjectExprs,
                    undoFieldNames,
                    ProjectRel.Flags.Boxed);

            bb.setRoot(
                rel,
                false);

            return;
        }

        // Usual case: all of the expressions in the SELECT clause are
        // different.
        rel =
            createAggregate(
                bb,
                Util.bitSetBetween(
                    sysFieldCount, rel.getRowType().getFieldCount()),
                Collections.<AggregateCall>emptyList());

        bb.setRoot(
            rel,
            false);
    }

    private int findExpr(RexNode seek, RexNode [] exprs, int count)
    {
        for (int i = 0; i < count; i++) {
            RexNode expr = exprs[i];
            if (expr.toString().equals(seek.toString())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Converts a query's ORDER BY clause, if any.
     *
     * @param select Query
     * @param bb Blackboard
     * @param collationList Collation list
     * @param orderExprList Method populates this list with orderBy expressions
     *   not present in selectList
     */
    protected void convertOrder(
        SqlSelect select,
        Blackboard bb,
        List<RelFieldCollation> collationList,
        List<SqlNode> orderExprList)
    {
        if (select.getOrderList() == null) {
            assert collationList.isEmpty();
            return;
        }

        // Create a sorter using the previously constructed collations.
        bb.setRoot(
            new SortRel(
                cluster,
                bb.root,
                collationList.toArray(
                    new RelFieldCollation[collationList.size()])),
            false);

        // If extra exressions were added to the project list for sorting,
        // add another project to remove them.
        if (orderExprList.size() > 0) {
            List<RexNode> exprs = new ArrayList<RexNode>();
            List<String> names = new ArrayList<String>();
            final RelDataType rowType = bb.root.getRowType();
            int fieldCount = rowType.getFieldCount() - orderExprList.size();
            for (int i = 0; i < fieldCount; i++) {
                exprs.add(RelOptUtil.createInputRef(bb.root, i));
                names.add(rowType.getFields()[i].getName());
            }
            bb.setRoot(
                CalcRel.createProject(bb.root, exprs, names),
                false);
        }
    }

    /**
     * Returns whether a given node contains a {@link SqlInOperator}.
     *
     * @param node a RexNode tree
     */
    private static boolean containsInOperator(
        SqlNode node)
    {
        try {
            SqlVisitor<Void> visitor =
                new SqlBasicVisitor<Void>() {
                    public Void visit(SqlCall call)
                    {
                        if (call.getOperator() instanceof SqlInOperator) {
                            throw new Util.FoundOne(call);
                        }
                        return super.visit(call);
                    }
                };
            node.accept(visitor);
            return false;
        } catch (Util.FoundOne e) {
            Util.swallow(e, null);
            return true;
        }
    }

    /**
     * Pushdown allthe NOT logical operators into any IN/NOT IN operators.
     *
     * @param sqlNode the root node from which to look for NOT operators
     *
     * @return the transformed SqlNode representation with NOT pushed down.
     */
    private static SqlNode pushdownNotForIn(
        SqlNode sqlNode)
    {
        if ((sqlNode instanceof SqlCall) && containsInOperator(sqlNode)) {
            SqlCall sqlCall = (SqlCall) sqlNode;
            if ((sqlCall.getOperator() == SqlStdOperatorTable.andOperator)
                || (sqlCall.getOperator() == SqlStdOperatorTable.orOperator))
            {
                SqlNode [] sqlOperands = sqlCall.getOperands();
                for (int i = 0; i < sqlOperands.length; i++) {
                    sqlOperands[i] = pushdownNotForIn(sqlOperands[i]);
                }
                return sqlNode;
            } else if (
                sqlCall.getOperator()
                == SqlStdOperatorTable.notOperator)
            {
                SqlNode childNode = sqlCall.getOperands()[0];
                assert (childNode instanceof SqlCall);
                SqlCall childSqlCall = (SqlCall) childNode;
                if (childSqlCall.getOperator()
                    == SqlStdOperatorTable.andOperator)
                {
                    SqlNode [] andOperands = childSqlCall.getOperands();
                    SqlNode [] orOperands = new SqlNode[andOperands.length];
                    for (int i = 0; i < orOperands.length; i++) {
                        orOperands[i] =
                            SqlStdOperatorTable.notOperator.createCall(
                                SqlParserPos.ZERO,
                                andOperands[i]);
                    }
                    for (int i = 0; i < orOperands.length; i++) {
                        orOperands[i] = pushdownNotForIn(orOperands[i]);
                    }
                    SqlNode orNode =
                        SqlStdOperatorTable.orOperator.createCall(
                            SqlParserPos.ZERO,
                            orOperands[0],
                            orOperands[1]);
                    return orNode;
                } else if (
                    childSqlCall.getOperator()
                    == SqlStdOperatorTable.orOperator)
                {
                    SqlNode [] orOperands = childSqlCall.getOperands();
                    SqlNode [] andOperands = new SqlNode[orOperands.length];
                    for (int i = 0; i < andOperands.length; i++) {
                        andOperands[i] =
                            SqlStdOperatorTable.notOperator.createCall(
                                SqlParserPos.ZERO,
                                orOperands[i]);
                    }
                    for (int i = 0; i < andOperands.length; i++) {
                        andOperands[i] = pushdownNotForIn(andOperands[i]);
                    }
                    SqlNode andNode =
                        SqlStdOperatorTable.andOperator.createCall(
                            SqlParserPos.ZERO,
                            andOperands[0],
                            andOperands[1]);
                    return andNode;
                } else if (
                    childSqlCall.getOperator()
                    == SqlStdOperatorTable.notOperator)
                {
                    SqlNode [] notOperands = childSqlCall.getOperands();
                    assert (notOperands.length == 1);
                    return pushdownNotForIn(notOperands[0]);
                } else if (
                    childSqlCall.getOperator()
                    instanceof SqlInOperator)
                {
                    SqlNode [] inOperands = childSqlCall.getOperands();
                    SqlInOperator inOp =
                        (SqlInOperator) childSqlCall.getOperator();
                    if (inOp.isNotIn()) {
                        return SqlStdOperatorTable.inOperator.createCall(
                            SqlParserPos.ZERO,
                            inOperands[0],
                            inOperands[1]);
                    } else {
                        return SqlStdOperatorTable.notInOperator.createCall(
                            SqlParserPos.ZERO,
                            inOperands[0],
                            inOperands[1]);
                    }
                } else {
                    // childSqlCall is "leaf" node in a logical expression tree
                    // (only considering AND, OR, NOT)
                    return sqlNode;
                }
            } else {
                // sqlNode is "leaf" node in a logical expression tree
                // (only considering AND, OR, NOT)
                return sqlNode;
            }
        } else {
            // tree rooted at sqlNode does not contain inOperator
            return sqlNode;
        }
    }

    /**
     * Converts a WHERE clause.
     *
     * @param bb Blackboard
     * @param where WHERE clause, may be null
     */
    private void convertWhere(
        final Blackboard bb,
        final SqlNode where)
    {
        if (where == null) {
            return;
        }
        SqlNode newWhere = pushdownNotForIn(where);
        replaceSubqueries(bb, newWhere);
        final RexNode convertedWhere = bb.convertExpression(newWhere);

        // only allocate filter if the condition is not TRUE
        if (!convertedWhere.isAlwaysTrue()) {
            RelNode inputRel = bb.root;
            Set<String> correlatedVariablesBefore =
                RelOptUtil.getVariablesUsed(inputRel);

            bb.setRoot(
                CalcRel.createFilter(bb.root, convertedWhere),
                false);
            Set<String> correlatedVariables =
                RelOptUtil.getVariablesUsed(bb.root);

            correlatedVariables.removeAll(correlatedVariablesBefore);

            // Associate the correlated variables with the new filter rel.
            for (String correl : correlatedVariables) {
                mapCorrelToRefRel.put(correl, bb.root);
            }
        }
    }

    private void replaceSubqueries(
        final Blackboard bb,
        final SqlNode expr)
    {
        findSubqueries(bb, expr, false);
        for (SqlNode node : bb.subqueryList) {
            substituteSubquery(bb, node);
        }
    }

    private void substituteSubquery(Blackboard bb, SqlNode node)
    {
        JoinRelType joinType = JoinRelType.INNER;
        RexNode [] leftJoinKeysForIn = null;
        boolean isNotIn;
        boolean subqueryNeedsOuterJoin = bb.subqueryNeedsOuterJoin;
        SqlCall call;
        SqlSelect select;

        final RexNode expr = bb.mapSubqueryToExpr.get(node);
        if (expr != null) {
            // Already done.
            return;
        }
        RelNode converted;
        switch (node.getKind()) {
        case CURSOR:
            convertCursor(bb, (SqlCall) node);
            return;
        case MULTISET_QUERY_CONSTRUCTOR:
        case MULTISET_VALUE_CONSTRUCTOR:
            converted = convertMultisets(
                new SqlNode[] { node },
                bb);
            break;
        case IN:
            call = (SqlCall) node;
            final SqlNode [] operands = call.getOperands();

            isNotIn = ((SqlInOperator) call.getOperator()).isNotIn();
            SqlNode leftKeyNode = operands[0];
            SqlNode seek = operands[1];

            if ((leftKeyNode instanceof SqlCall)
                && (((SqlCall) leftKeyNode).getOperator()
                    instanceof SqlRowOperator))
            {
                SqlCall keyCall = (SqlCall) leftKeyNode;
                SqlNode [] keyCallOperands = keyCall.getOperands();
                int rowLength = keyCallOperands.length;
                leftJoinKeysForIn = new RexNode[rowLength];

                for (int i = 0; i < rowLength; i++) {
                    SqlNode sqlExpr = keyCallOperands[i];
                    leftJoinKeysForIn[i] = bb.convertExpression(sqlExpr);
                }
            } else {
                leftJoinKeysForIn = new RexNode[1];
                leftJoinKeysForIn[0] = bb.convertExpression(leftKeyNode);
            }

            if (seek instanceof SqlNodeList) {
                SqlNodeList valueList = (SqlNodeList) seek;
                boolean seenNull = false;

                // check for nulls
                for (int i = 0; i < valueList.size(); i++) {
                    SqlNode sqlNode = valueList.getList().get(i);
                    if (sqlNode instanceof SqlLiteral) {
                        SqlLiteral lit = (SqlLiteral) (sqlNode);
                        if (lit.getValue() == null) {
                            seenNull = true;
                        }
                    }
                }

                if (!seenNull
                    && (valueList.size() < getInSubqueryThreshold()))
                {
                    // We're under the threshold, so convert to OR.
                    RexNode expression =
                        convertInToOr(
                            bb,
                            leftJoinKeysForIn,
                            valueList,
                            isNotIn);
                    bb.mapSubqueryToExpr.put(node, expression);
                    return;
                } else {
                    // Otherwise, let convertExists translate
                    // values list into an inline table for the
                    // reference to Q below.
                }
            }

            // Project out the search columns from the left side

            //  Q1:
            // "select from emp where emp.deptno in (select col1 from T)"
            //
            // is converted to
            //
            // "select from
            //   emp inner join (select distinct col1 from T)) q
            //   on emp.deptno = q.col1
            //
            // Q2:
            // "select from emp where emp.deptno not in (Q)"
            //
            // is converted to
            //
            // "select from
            //   emp left outer join (select distinct col1, TRUE from T) q
            //   on emp.deptno = q.col1
            //   where emp.deptno <> null
            //         and q.indicator <> TRUE"
            //
            converted =
                convertExists(
                    seek,
                    true,
                    false,
                    subqueryNeedsOuterJoin || isNotIn);
            if (subqueryNeedsOuterJoin || isNotIn) {
                joinType = JoinRelType.LEFT;
            } else {
                joinType = JoinRelType.INNER;
            }
            break;
        case EXISTS:
            // "select from emp where exists (select a from T)"
            //
            // is converted to the following if the subquery is correlated:
            //
            // "select from emp left outer join (select AGG_TRUE() as indicator
            // from T group by corr_var) q where q.indicator is true"
            //
            // If there is no correlation, the expression is replaced with a
            // boolean indicating whether the subquery returned 0 or >= 1 row.
            call = (SqlCall) node;
            select = (SqlSelect) call.getOperands()[0];
            converted = convertExists(select, false, true, true);
            if (convertNonCorrelatedSubq(call, bb, converted, true)) {
                return;
            }
            joinType = JoinRelType.LEFT;
            break;
        case SCALAR_QUERY:

            // Convert the subquery.  If it's non-correlated, convert it
            // to a constant expression.
            call = (SqlCall) node;
            select = (SqlSelect) call.getOperands()[0];
            converted = convertExists(select, false, false, true);
            if (convertNonCorrelatedSubq(call, bb, converted, false)) {
                return;
            }
            converted = convertToSingleValueSubq(select, converted);
            joinType = JoinRelType.LEFT;
            break;
        case SELECT:

            // This is used when converting multiset queries:
            //
            // select * from unnest(select multiset[deptno] from emps);
            //
            converted = convertExists(node, false, false, true);
            joinType = JoinRelType.LEFT;
            break;
        default:
            throw Util.newInternal(
                "unexpected kind of subquery :" + node);
        }
        final RexNode expression =
            bb.register(
                converted,
                joinType,
                leftJoinKeysForIn);
        bb.mapSubqueryToExpr.put(node, expression);
    }

    /**
     * Determines if a subquery is non-correlated and if so, converts it to a
     * constant.
     *
     * @param call the call that references the subquery
     * @param bb blackboard used to convert the subquery
     * @param converted RelNode tree corresponding to the subquery
     * @param isExists true if the subquery is part of an EXISTS expression
     *
     * @return if the subquery can be converted to a constant
     */
    private boolean convertNonCorrelatedSubq(
        SqlCall call,
        Blackboard bb,
        RelNode converted,
        boolean isExists)
    {
        if (subqueryConverter.canConvertSubquery()
            && isSubqNonCorrelated(converted, bb))
        {
            // First check if the subquery has already been converted
            // because it's a nested subquery.  If so, don't re-evaluate
            // it again.
            RexNode constExpr = mapConvertedNonCorrSubqs.get(call);
            if (constExpr == null) {
                constExpr =
                    subqueryConverter.convertSubquery(
                        call,
                        this,
                        isExists,
                        isExplain);
            }
            if (constExpr != null) {
                bb.mapSubqueryToExpr.put(call, constExpr);
                mapConvertedNonCorrSubqs.put(call, constExpr);
                return true;
            }
        }
        return false;
    }

    /**
     * Converts the RelNode tree for a select statement to a select that
     * produces a single value.
     *
     * @param select the statement
     * @param plan the original RelNode tree corresponding to the statement
     *
     * @return the converted RelNode tree
     */
    public RelNode convertToSingleValueSubq(
        SqlSelect select,
        RelNode plan)
    {
        SqlNodeList selectList = select.getSelectList();
        SqlNodeList groupList = select.getGroup();

        // Check whether query is guaranteed to produce a single value.
        if ((selectList.size() == 1)
            && ((groupList == null) || (groupList.size() == 0)))
        {
            SqlNode selectExpr = selectList.get(0);
            if (selectExpr instanceof SqlCall) {
                SqlCall selectExprCall = (SqlCall) selectExpr;
                if (selectExprCall.getOperator()
                    instanceof SqlAggFunction)
                {
                    return plan;
                }
            }
        }

        // If not, project SingleValueAgg
        return RelOptUtil.createSingleValueAggRel(
            cluster,
            plan,
            validator.getSystemFields());
    }

    /**
     * Converts "x IN (1, 2, ...)" to "x=1 OR x=2 OR ...".
     *
     * @param leftKeys LHS
     * @param valuesList RHS
     * @param isNotIn is this a NOT IN operator
     *
     * @return converted expression
     */
    private RexNode convertInToOr(
        Blackboard bb,
        RexNode [] leftKeys,
        SqlNodeList valuesList,
        boolean isNotIn)
    {
        RexNode result = null;
        for (SqlNode rightVals : valuesList) {
            RexNode rexComparison = null;
            if (leftKeys.length == 1) {
                rexComparison =
                    rexBuilder.makeCall(
                        SqlStdOperatorTable.equalsOperator,
                        leftKeys[0],
                        bb.convertExpression(rightVals));
            } else {
                assert (rightVals instanceof SqlCall);
                SqlCall call = (SqlCall) rightVals;
                assert ((call.getOperator() instanceof SqlRowOperator)
                    && (call.getOperands().length == leftKeys.length));
                for (int i = 0; i < leftKeys.length; i++) {
                    RexNode equi =
                        rexBuilder.makeCall(
                            SqlStdOperatorTable.equalsOperator,
                            leftKeys[i],
                            bb.convertExpression(call.getOperands()[i]));
                    if (rexComparison == null) {
                        rexComparison = equi;
                    } else {
                        rexComparison =
                            rexBuilder.makeCall(
                                SqlStdOperatorTable.andOperator,
                                rexComparison,
                                equi);
                    }
                }
            }

            if (result == null) {
                result = rexComparison;
            } else {
                // TODO jvs 1-May-2006: Generalize
                // RexUtil.andRexNodeList and use that.
                result =
                    rexBuilder.makeCall(
                        SqlStdOperatorTable.orOperator,
                        result,
                        rexComparison);
            }
        }

        assert (result != null);

        if (isNotIn) {
            result =
                rexBuilder.makeCall(
                    SqlStdOperatorTable.notOperator,
                    result);
        }

        return result;
    }

    /**
     * Gets the list size threshold under which {@link #convertInToOr} is used.
     * Lists of this size or greater will instead be converted to use a join
     * against an inline table ({@link ValuesRel}) rather than a predicate. A
     * threshold of 0 forces usage of an inline table in all cases; a threshold
     * of Integer.MAX_VALUE forces usage of OR in all cases
     *
     * @return threshold, default 20
     */
    protected int getInSubqueryThreshold()
    {
        return 20;
    }

    /**
     * Creates the condition for a join implementing an IN clause.
     *
     * @param bb blackboard to use, bb.root points to the LHS
     * @param leftKeys LHS of IN
     * @param rightRel Relational expression on RHS
     *
     * @return join condition
     */
    private RexNode createJoinConditionForIn(
        Blackboard bb,
        RexNode [] leftKeys,
        RelNode rightRel)
    {
        List<RexNode> joinConditions = new ArrayList<RexNode>();

        // right fields appear after the LHS fields.
        int rightInputOffset = bb.root.getRowType().getFieldCount();

        RelDataTypeField [] rightTypeFields = rightRel.getRowType().getFields();

        assert (leftKeys.length <= rightTypeFields.length);

        for (int i = 0; i < leftKeys.length; i++) {
            RexNode conditionNode = leftKeys[i];
            joinConditions.add(
                rexBuilder.makeCall(
                    SqlStdOperatorTable.equalsOperator,
                    conditionNode,
                    rexBuilder.makeInputRef(
                        rightTypeFields[i].getType(),
                        rightInputOffset + i)));
        }

        return RexUtil.andRexNodeList(rexBuilder, joinConditions);
    }

    /**
     * Converts an EXISTS or IN predicate into a join. For EXISTS, the subquery
     * produces an indicator variable, and the result is a relational expression
     * which outer joins that indicator to the original query. After performing
     * the outer join, the condition will be TRUE if the EXISTS condition holds,
     * NULL otherwise.
     *
     * <p>FIXME jvs 1-May-2006: null semantics for IN are currently broken
     *
     * @param seek A query, for example 'select * from emp' or 'values (1,2,3)'
     * or '('Foo', 34)'.
     * @param isIn Whether is an IN predicate
     * @param isExists Whether is an EXISTS predicate
     * @param needsOuterJoin Whether an outer join is needed
     *
     * @return join expression
     *
     * @pre extraExpr == null || extraName != null
     */
    private RelNode convertExists(
        SqlNode seek,
        boolean isIn,
        boolean isExists,
        boolean needsOuterJoin)
    {
        assert (!isIn || !isExists);
        final SqlValidatorScope seekScope =
            (seek instanceof SqlSelect)
            ? validator.getSelectScope((SqlSelect) seek)
            : null;
        final Blackboard seekBb = createBlackboard(seekScope, null);
        RelNode seekRel = convertQueryOrInList(seekBb, seek);

        return RelOptUtil.createExistsPlan(
            cluster,
            seekRel,
            isIn,
            isExists,
            needsOuterJoin);
    }

    private RelNode convertQueryOrInList(
        Blackboard bb,
        SqlNode seek)
    {
        // NOTE: Once we start accepting single-row queries as row constructors,
        // there will be an ambiguity here for a case like X IN ((SELECT Y FROM
        // Z)).  The SQL standard resolves the ambiguity by saying that a lone
        // select should be interpreted as a table expression, not a row
        // expression.  The semantic difference is that a table expression can
        // return multiple rows.
        if (seek instanceof SqlNodeList) {
            return convertRowValues(
                bb,
                seek,
                ((SqlNodeList) seek).getList(),
                false);
        } else {
            return convertQueryRecursive(seek, QueryContext.SUBQUERY);
        }
    }

    private RelNode convertRowValues(
        Blackboard bb,
        SqlNode rowList,
        Collection<SqlNode> rows,
        boolean allowLiteralsOnly)
    {
        // NOTE jvs 30-Apr-2006: We combine all rows consisting entirely of
        // literals into a single ValuesRel; this gives the optimizer a smaller
        // input tree.  For everything else (computed expressions, row
        // subqueries), we union each row in as a projection on top of a
        // OneRowRel.

        List<List<RexLiteral>> tupleList = new ArrayList<List<RexLiteral>>();
        RelDataType rowType =
            validator.getNamespace(rowList) != null
                ? validator.getNamespace(rowList).getRowType()
                : validator.getValidatedNodeType(rowList);
        rowType =
            SqlTypeUtil.promoteToRowType(
                typeFactory,
                rowType,
                null);
        List<RelNode> unionInputs = new ArrayList<RelNode>();
        for (SqlNode node : rows) {
            SqlCall call;
            if (isRowConstructor(node)) {
                call = (SqlCall) node;
                List<RexLiteral> tuple = new ArrayList<RexLiteral>();
                for (RelDataTypeField field : validator.getSystemFields()) {
                    tuple.add(
                        rexBuilder.makeZeroLiteral(field.getType()));
                }
                for (SqlNode operand : call.operands) {
                    RexLiteral rexLiteral =
                        convertLiteralInValuesList(
                            operand,
                            bb,
                            rowType,
                            tuple.size());
                    if ((rexLiteral == null) && allowLiteralsOnly) {
                        return null;
                    }
                    if ((rexLiteral == null) || !shouldCreateValuesRel) {
                        // fallback to convertRowConstructor
                        tuple = null;
                        break;
                    }
                    tuple.add(rexLiteral);
                }
                if (tuple != null) {
                    tupleList.add(tuple);
                    continue;
                }
            } else {
                RexLiteral rexLiteral =
                    convertLiteralInValuesList(
                        node,
                        bb,
                        rowType,
                        0);
                if ((rexLiteral != null) && shouldCreateValuesRel) {
                    tupleList.add(
                        Collections.singletonList(rexLiteral));
                    continue;
                } else {
                    if ((rexLiteral == null) && allowLiteralsOnly) {
                        return null;
                    }
                }

                // convert "1" to "row(1)"
                call =
                    SqlStdOperatorTable.rowConstructor.createCall(
                        SqlParserPos.ZERO,
                        node);
            }
            unionInputs.add(convertRowConstructor(bb, call));
        }
        ValuesRel valuesRel =
            new ValuesRel(
                cluster,
                rowType,
                tupleList);
        RelNode resultRel;
        if (unionInputs.isEmpty()) {
            resultRel = valuesRel;
        } else {
            if (!tupleList.isEmpty()) {
                unionInputs.add(valuesRel);
            }
            UnionRel unionRel =
                new UnionRel(
                    cluster,
                    unionInputs.toArray(new RelNode[unionInputs.size()]),
                    true);
            resultRel = unionRel;
        }
        leaves.add(resultRel);
        return resultRel;
    }

    private RexLiteral convertLiteralInValuesList(
        SqlNode sqlNode,
        Blackboard bb,
        RelDataType rowType,
        int iField)
    {
        // We can handle literals and CAST(NULL AS <type>), nothing else.
        final SqlLiteral sqlLiteral;
        if (sqlNode instanceof SqlLiteral) {
            sqlLiteral = (SqlLiteral) sqlNode;
        } else if (SqlUtil.isNull(sqlNode)) {
            sqlLiteral = SqlLiteral.createNull(sqlNode.getParserPosition());
            validator.setValidatedNodeType(
                sqlLiteral,
                validator.getValidatedNodeType(sqlNode));
        } else {
            return null;
        }

        RelDataTypeField field = rowType.getFieldList().get(iField);
        RelDataType type = field.getType();
        if (type.isStruct()) {
            // null literals for weird stuff like UDT's need
            // special handling during type flattening, so
            // don't use ValuesRel for those
            return null;
        }

        RexNode literalExpr =
            exprConverter.convertLiteral(
                bb, sqlLiteral);

        if (!(literalExpr instanceof RexLiteral)) {
            assert (literalExpr.isA(RexKind.Cast));
            RexNode child = ((RexCall) literalExpr).getOperands()[0];
            assert (RexLiteral.isNullLiteral(child));

            // NOTE jvs 22-Nov-2006:  we preserve type info
            // in ValuesRel digest, so it's OK to lose it here
            return (RexLiteral) child;
        }

        RexLiteral literal = (RexLiteral) literalExpr;

        Comparable value = literal.getValue();

        if (SqlTypeUtil.isExactNumeric(type)) {
            BigDecimal roundedValue =
                NumberUtil.rescaleBigDecimal(
                    (BigDecimal) value,
                    type.getScale());
            return rexBuilder.makeExactLiteral(
                roundedValue,
                type);
        }

        if ((value instanceof NlsString)
            && (type.getSqlTypeName() == SqlTypeName.CHAR))
        {
            // pad fixed character type
            NlsString unpadded = (NlsString) value;
            return rexBuilder.makeCharLiteral(
                new NlsString(
                    Util.rpad(unpadded.getValue(), type.getPrecision()),
                    unpadded.getCharsetName(),
                    unpadded.getCollation()));
        }
        return literal;
    }

    private boolean isRowConstructor(SqlNode node)
    {
        if (!(node.getKind() == SqlKind.ROW)) {
            return false;
        }
        SqlCall call = (SqlCall) node;
        return call.getOperator().getName().equalsIgnoreCase("row");
    }

    /**
     * Builds a list of all <code>IN</code> or <code>EXISTS</code> operators
     * inside SQL parse tree. Does not traverse inside queries.
     *
     * @param bb blackboard
     * @param node the SQL parse tree
     * @param registerOnlyScalarSubqueries if set to true and the parse tree
     * corresponds to a variation of a select node, only register it if it's a
     * scalar subquery
     */
    private void findSubqueries(
        Blackboard bb,
        SqlNode node,
        boolean registerOnlyScalarSubqueries)
    {
        final SqlKind kind = node.getKind();
        switch (kind) {
        case EXISTS:
        case SELECT:
        case MULTISET_QUERY_CONSTRUCTOR:
        case MULTISET_VALUE_CONSTRUCTOR:
        case CURSOR:
        case SCALAR_QUERY:
            if (!registerOnlyScalarSubqueries
                || (kind == SqlKind.SCALAR_QUERY))
            {
                bb.registerSubquery(node);
            }
            return;
        default:
            if (node instanceof SqlCall) {
                SqlOperator operator = ((SqlCall) node).getOperator();
                if (kind == SqlKind.OR
                    || kind == SqlKind.NOT)
                {
                    // It's always correct to outer join subquery with
                    // containing query; however, when predicates involve Or
                    // or NOT, outer join might be necessary.
                    bb.subqueryNeedsOuterJoin = true;
                }
                final SqlNode [] operands = ((SqlCall) node).getOperands();
                for (SqlNode operand : operands) {
                    if (operand != null) {
                        // In the case of an IN expression, locate scalar
                        // subqueries so we can convert them to constants
                        findSubqueries(
                            bb,
                            operand,
                            kind == SqlKind.IN || registerOnlyScalarSubqueries);
                    }
                }
            } else if (node instanceof SqlNodeList) {
                final SqlNodeList nodes = (SqlNodeList) node;
                for (int i = 0; i < nodes.size(); i++) {
                    SqlNode child = nodes.get(i);
                    findSubqueries(
                        bb,
                        child,
                        kind == SqlKind.IN || registerOnlyScalarSubqueries);
                }
            }

            // Now that we've located any scalar subqueries inside the IN
            // expression, register the IN expression itself.  We need to
            // register the scalar subqueries first so they can be converted
            // before the IN expression is converted.
            if (kind == SqlKind.IN) {
                bb.registerSubquery(node);
            }
        }
    }

    /**
     * Converts an expression from {@link SqlNode} to {@link RexNode} format.
     *
     * @param node Expression to translate
     *
     * @return Converted expression
     */
    public RexNode convertExpression(
        SqlNode node)
    {
        Map<String, RelDataType> nameToTypeMap = Collections.emptyMap();
        Blackboard bb =
            createBlackboard(
                new ParameterScope((SqlValidatorImpl) validator, nameToTypeMap),
                null);
        return bb.convertExpression(node);
    }

    /**
     * Converts an expression from {@link SqlNode} to {@link RexNode} format,
     * mapping identifier references to predefined expressions.
     *
     * @param node Expression to translate
     * @param nameToNodeMap map from String to {@link RexNode}; when an {@link
     * SqlIdentifier} is encountered, it is used as a key and translated to the
     * corresponding value from this map
     *
     * @return Converted expression
     */
    public RexNode convertExpression(
        SqlNode node,
        Map<String, RexNode> nameToNodeMap)
    {
        final Map<String, RelDataType> nameToTypeMap =
            new HashMap<String, RelDataType>();
        for (Map.Entry<String, RexNode> entry : nameToNodeMap.entrySet()) {
            nameToTypeMap.put(entry.getKey(), entry.getValue().getType());
        }
        Blackboard bb =
            createBlackboard(
                new ParameterScope((SqlValidatorImpl) validator, nameToTypeMap),
                nameToNodeMap);
        return bb.convertExpression(node);
    }

    /**
     * Converts a non-standard expression.
     *
     * <p>This method is an extension-point for derived classes can override. If
     * this method returns a null result, the normal expression translation
     * process will proceeed. The default implementation always returns null.
     *
     * @param node Expression
     * @param bb Blackboard
     *
     * @return null to proceed with the usual expression translation process
     */
    protected RexNode convertExtendedExpression(
        SqlNode node,
        Blackboard bb)
    {
        return null;
    }

    private RexNode convertOver(Blackboard bb, SqlNode node)
    {
        SqlCall call = (SqlCall) node;
        SqlCall aggCall = (SqlCall) call.operands[0];
        SqlNode windowOrRef = call.operands[1];
        final SqlWindow window =
            validator.resolveWindow(windowOrRef, bb.scope, true);
        final SqlNodeList partitionList = window.getPartitionList();
        final RexNode [] partitionKeys = new RexNode[partitionList.size()];
        for (int i = 0; i < partitionKeys.length; i++) {
            partitionKeys[i] = bb.convertExpression(partitionList.get(i));
        }
        SqlNodeList orderList = window.getOrderList();
        if ((orderList.size() == 0) && !window.isRows()) {
            // A logical range requires an ORDER BY clause. Use the implicit
            // ordering of this relation. There must be one, otherwise it would
            // have failed validation.
            orderList = bb.scope.getOrderList();
            Util.permAssert(
                orderList != null,
                "Relation should have sort key for implicit ORDER BY");
            Util.permAssert(orderList.size() > 0, "sort key must not be empty");
        }
        final RexNode [] orderKeys = new RexNode[orderList.size()];
        for (int i = 0; i < orderKeys.length; i++) {
            orderKeys[i] = bb.convertExpression(orderList.get(i));
        }
        RexNode rexAgg;
        bb.setRexOverContext(window, partitionKeys, orderKeys);
        rexAgg = exprConverter.convertCall(bb, aggCall);
        bb.clearRexOverContext();
        return (new RexShuttle()).apply(rexAgg);
    }

    /**
     * Converts a FROM clause into a relational expression.
     *
     * @param bb Scope within which to resolve identifiers
     * @param from FROM clause of a query. Examples include:
     *
     * <ul>
     * <li>a single table ("SALES.EMP"),
     * <li>an aliased table ("EMP AS E"),
     * <li>a list of tables ("EMP, DEPT"),
     * <li>an ANSI Join expression ("EMP JOIN DEPT ON EMP.DEPTNO =
     * DEPT.DEPTNO"),
     * <li>a VALUES clause ("VALUES ('Fred', 20)"),
     * <li>a query ("(SELECT * FROM EMP WHERE GENDER = 'F')"),
     * <li>or any combination of the above.
     * </ul>
     *
     * @post return != null
     */
    protected void convertFrom(
        Blackboard bb,
        SqlNode from)
    {
        SqlCall call;
        final SqlNode [] operands;
        switch (from.getKind()) {
        case AS:
            operands = ((SqlCall) from).getOperands();
            convertFrom(bb, operands[0]);
            return;

        case TABLESAMPLE:
            operands = ((SqlCall) from).getOperands();
            SqlSampleSpec sampleSpec = SqlLiteral.sampleValue(operands[1]);
            if (sampleSpec instanceof SqlSampleSpec.SqlSubstitutionSampleSpec) {
                String sampleName =
                    ((SqlSampleSpec.SqlSubstitutionSampleSpec) sampleSpec)
                    .getName();
                datasetStack.push(sampleName);
                convertFrom(bb, operands[0]);
                datasetStack.pop();
            } else if (sampleSpec instanceof SqlSampleSpec.SqlTableSampleSpec) {
                SqlSampleSpec.SqlTableSampleSpec tableSampleSpec =
                    (SqlSampleSpec.SqlTableSampleSpec) sampleSpec;
                convertFrom(bb, operands[0]);
                RelOptSamplingParameters params =
                    new RelOptSamplingParameters(
                        tableSampleSpec.isBernoulli(),
                        tableSampleSpec.getSamplePercentage(),
                        tableSampleSpec.isRepeatable(),
                        tableSampleSpec.getRepeatableSeed());
                bb.setRoot(new SamplingRel(cluster, bb.root, params), false);
            } else {
                throw Util.newInternal(
                    "unknown TABLESAMPLE type: " + sampleSpec);
            }
            return;

        case IDENTIFIER:
            final SqlValidatorNamespace fromNamespace =
                validator.getNamespace(from);
            final String datasetName =
                datasetStack.isEmpty() ? null : datasetStack.peek();
            boolean [] usedDataset = { false };
            RelOptTable table =
                SqlValidatorUtil.getRelOptTable(
                    fromNamespace,
                    schema,
                    datasetName,
                    usedDataset);
            final RelNode tableRel;
            if (shouldConvertTableAccess) {
                tableRel = table.toRel(cluster, connection);
            } else {
                tableRel = new TableAccessRel(cluster, table, connection);
            }
            RelNode projected = projectSysFields(tableRel);
            bb.setRoot(projected, true);
            if (usedDataset[0]) {
                bb.setDataset(datasetName);
            }
            return;

        case JOIN:
            final SqlJoin join = (SqlJoin) from;
            final Blackboard fromBlackboard =
                createBlackboard(validator.getJoinScope(from), null);
            SqlNode left = join.getLeft();
            SqlNode right = join.getRight();
            boolean isNatural = join.isNatural();
            SqlJoinOperator.JoinType joinType = join.getJoinType();
            final Blackboard leftBlackboard =
                createBlackboard(validator.getJoinScope(left), null);
            final Blackboard rightBlackboard =
                createBlackboard(validator.getJoinScope(right), null);
            convertFrom(leftBlackboard, left);
            RelNode leftRel = leftBlackboard.root;
            convertFrom(rightBlackboard, right);
            RelNode rightRel = rightBlackboard.root;
            JoinRelType convertedJoinType = convertJoinType(joinType);
            RexNode conditionExp;
            if (isNatural) {
                final List<String> columnList =
                    SqlValidatorUtil.deriveNaturalJoinColumnList(
                        validator.getNamespace(left).getRowType(),
                        validator.getNamespace(right).getRowType());
                conditionExp = convertUsing(leftRel, rightRel, columnList);
            } else {
                conditionExp =
                    convertJoinCondition(
                        fromBlackboard,
                        join.getCondition(),
                        join.getConditionType(),
                        leftRel,
                        rightRel);
            }

            final RelNode joinRel =
                createJoin(
                    fromBlackboard,
                    leftRel,
                    rightRel,
                    conditionExp,
                    convertedJoinType);
            bb.setRoot(joinRel, false);
            return;

        case SELECT:
        case INTERSECT:
        case EXCEPT:
        case UNION:
            final RelNode rel =
                convertQueryRecursive(from, QueryContext.SUBQUERY);
            bb.setRoot(rel, true);
            return;

        case VALUES:
            convertValues(bb, (SqlCall) from);
            return;

        case UNNEST:
            call = (SqlCall) ((SqlCall) from).operands[0];
            replaceSubqueries(bb, call);
            RexNode [] exprs = { bb.convertExpression(call) };
            final String [] fieldNames = { validator.deriveAlias(call, 0) };
            final RelNode childRel =
                CalcRel.createProject(
                    (null != bb.root)
                    ? bb.root
                    : new OneRowRel(cluster),
                    exprs,
                    fieldNames,
                    true);

            UncollectRel uncollectRel = new UncollectRel(cluster, childRel);
            bb.setRoot(uncollectRel, true);
            return;

        case COLLECTION_TABLE:
            call = (SqlCall) from;

            // Dig out real call; TABLE() wrapper is just syntactic.
            assert (call.getOperands().length == 1);
            call = (SqlCall) call.getOperands()[0];
            convertCollectionTable(bb, call);
            return;

        default:
            throw Util.newInternal("not a join operator " + from);
        }
    }

    /**
     * Creates a projection to ensure that a relational expression's rowtype
     * starts with the system fields.
     *
     * @param rel Relational expression
     * @return Returns a relational expression with system fields prefixed
     */
    private RelNode projectSysFields(RelNode rel)
    {
        final List<RelDataTypeField> systemFieldList =
            typeFactory.getSystemFieldList();
        if (systemFieldList.isEmpty()) {
            // Deal with the trivial case quickly.
            return rel;
        }
        final RelDataType rowType = rel.getRowType();
        final List<RelDataTypeField> rowFields = rowType.getFieldList();
        final List<RexNode> exprs = new ArrayList<RexNode>();
        final List<String> names = new ArrayList<String>();
        int n = 0;
        for (RelDataTypeField field : systemFieldList) {
            if (rowFields.get(n).getName().equals(field.getName())) {
                exprs.add(
                    rexBuilder.makeInputRef(
                        field.getType(), n));
                ++n;
            } else {
                // Provide a dummy field so that the relation created by
                // converting a table has the full complement of system fields,
                // even if this particular table doesn't support those system
                // fields. It's simpler that way.
                //
                // RelFieldTrimmer will later prove that the field is not used
                // (otherwise validation would have failed: using an invalid
                // system field) and will remove the field.
                //
                // The field has a dummy expression, typically zero. We cannot
                // use NULL because sometimes a field has a NOT NULL constraint.
                exprs.add(
                    rexBuilder.makeZeroLiteral(field.getType(), true));
            }
            names.add(field.getName());
        }
        if (n == systemFieldList.size()) {
            return rel;
        }
        for (RelDataTypeField field : rowFields.subList(n, rowFields.size())) {
            exprs.add(
                rexBuilder.makeInputRef(
                    field.getType(), n));
            ++n;
            names.add(field.getName());
        }
        return CalcRel.createProject(rel, exprs, names, true);
    }

    protected void convertCollectionTable(
        Blackboard bb,
        SqlCall call)
    {
        if (call.getOperator() == SqlStdOperatorTable.sampleFunction) {
            final String sampleName = SqlLiteral.stringValue(call.operands[0]);
            datasetStack.push(sampleName);
            SqlCall cursorCall = (SqlCall) call.operands[1];
            SqlNode query = cursorCall.operands[0];
            RelNode converted =
                convertQuery(query, false, QueryContext.SUBQUERY);
            bb.setRoot(converted, false);
            datasetStack.pop();
            return;
        }
        replaceSubqueries(bb, call);
        RexNode rexCall = bb.convertExpression(call);
        RelNode [] inputs = bb.retrieveCursors();
        RelDataType rowType = validator.getNamespace(call).getRowType();
        List<RelDataType> inputRowTypes = new ArrayList<RelDataType>();
        for (RelNode input : inputs) {
            inputRowTypes.add(input.getRowType());
        }
        TableFunctionRel callRel =
            new TableFunctionRel(
                cluster,
                rexCall,
                rowType,
                inputs,
                inputRowTypes);
        Set<RelColumnMapping> columnMappings =
            getColumnMappings(call.getOperator());
        callRel.setColumnMappings(columnMappings);
        bb.setRoot(callRel, true);
        afterTableFunction(bb, call, callRel);
    }

    protected void afterTableFunction(
        SqlToRelConverter.Blackboard bb,
        SqlCall call,
        TableFunctionRel callRel)
    {
    }

    private Set<RelColumnMapping> getColumnMappings(SqlOperator op)
    {
        SqlReturnTypeInference rti = op.getReturnTypeInference();
        if (rti == null) {
            return null;
        }
        if (rti instanceof TableFunctionReturnTypeInference) {
            TableFunctionReturnTypeInference tfrti =
                (TableFunctionReturnTypeInference) rti;
            return tfrti.getColumnMappings();
        } else {
            return null;
        }
    }

    protected RelNode createJoin(
        Blackboard bb,
        RelNode leftRel,
        RelNode rightRel,
        RexNode joinCond,
        JoinRelType joinType)
    {
        Set<String> correlatedVariables = RelOptUtil.getVariablesUsed(rightRel);

        if (joinCond == null) {
            joinCond = rexBuilder.makeLiteral(true);
        }

        if (correlatedVariables.size() > 0) {
            List<CorrelatorRel.Correlation> correlations =
                new ArrayList<CorrelatorRel.Correlation>();

            for (String correlName : correlatedVariables) {
                DeferredLookup lookup = mapCorrelToDeferred.get(correlName);
                RexFieldAccess fieldAccess = lookup.getFieldAccess(correlName);
                String originalRelName = lookup.getOriginalRelName();
                String originalFieldName = fieldAccess.getField().getName();

                int [] nsIndexes = { -1 };
                final SqlValidatorScope [] ancestorScopes = { null };
                SqlValidatorNamespace foundNs =
                    lookup.bb.scope.resolve(
                        originalRelName,
                        ancestorScopes,
                        nsIndexes);

                assert (foundNs != null);
                assert (nsIndexes.length == 1);

                int childNamespaceIndex = nsIndexes[0];

                SqlValidatorScope ancestorScope = ancestorScopes[0];
                boolean correlInCurrentScope = (ancestorScope == bb.scope);

                if (correlInCurrentScope) {
                    int namespaceOffset = 0;
                    if (childNamespaceIndex > 0) {
                        // If not the first child, need to figure out the width
                        // of output types from all the preceding namespaces
                        assert (ancestorScope instanceof ListScope);
                        List<SqlValidatorNamespace> children =
                            ((ListScope) ancestorScope).getChildren();

                        for (int i = 0; i < childNamespaceIndex; i++) {
                            SqlValidatorNamespace child = children.get(i);
                            namespaceOffset +=
                                child.getRowType().getFieldCount();
                        }
                    }

                    int pos =
                        namespaceOffset
                        + foundNs.getRowType().getFieldOrdinal(
                            originalFieldName);

                    assert (foundNs.getRowType().getField(originalFieldName)
                                   .getType()
                        == lookup.getFieldAccess(correlName).getField()
                                 .getType());

                    assert (pos != -1);

                    if (bb.mapRootRelToFieldProjection.containsKey(bb.root)) {
                        // bb.root is an aggregate and only projects group by
                        // keys.
                        Map<Integer, Integer> exprProjection =
                            bb.mapRootRelToFieldProjection.get(bb.root);

                        // subquery can reference group by keys projected from
                        // the root of the outer relation.
                        if (exprProjection.containsKey(pos)) {
                            pos = exprProjection.get(pos);
                        } else {
                            // correl not grouped
                            throw Util.newInternal(
                                "Identifier '" + originalRelName + "."
                                + originalFieldName
                                + "' is not a group expr");
                        }
                    }

                    CorrelatorRel.Correlation newCorVar =
                        new CorrelatorRel.Correlation(
                            getCorrelOrdinal(correlName),
                            pos);

                    correlations.add(newCorVar);

                    mapFieldAccessToCorVar.put(fieldAccess, newCorVar);

                    RelNode refRel = mapCorrelToRefRel.get(correlName);

                    SortedSet<CorrelatorRel.Correlation> corVarList;

                    if (!mapRefRelToCorVar.containsKey(refRel)) {
                        corVarList = new TreeSet<CorrelatorRel.Correlation>();
                    } else {
                        corVarList = mapRefRelToCorVar.get(refRel);
                    }
                    corVarList.add(newCorVar);
                    mapRefRelToCorVar.put(refRel, corVarList);
                }
            }

            if (!correlations.isEmpty()) {
                CorrelatorRel rel =
                    new CorrelatorRel(
                        rightRel.getCluster(),
                        leftRel,
                        rightRel,
                        joinCond,
                        correlations,
                        joinType);
                for (CorrelatorRel.Correlation correlation : correlations) {
                    mapCorVarToCorRel.put(correlation, rel);
                }
                return rel;
            }
        }

        return createJoin(
            leftRel,
            rightRel,
            joinCond,
            joinType,
            Collections.<String>emptySet());
    }

    /**
     * Determines whether a subquery is non-correlated. Note that a
     * non-correlated subquery can contain correlated references, provided those
     * references do not reference select statements that are parents of the
     * subquery.
     *
     * @param subq the subquery
     * @param bb blackboard used while converting the subquery, i.e., the
     * blackboard of the parent query of this subquery
     *
     * @return true if the subquery is non-correlated.
     */
    private boolean isSubqNonCorrelated(RelNode subq, Blackboard bb)
    {
        Set<String> correlatedVariables = RelOptUtil.getVariablesUsed(subq);
        for (String correlName : correlatedVariables) {
            DeferredLookup lookup = mapCorrelToDeferred.get(correlName);
            String originalRelName = lookup.getOriginalRelName();

            int [] nsIndexes = { -1 };
            final SqlValidatorScope [] ancestorScopes = { null };
            SqlValidatorNamespace foundNs =
                lookup.bb.scope.resolve(
                    originalRelName,
                    ancestorScopes,
                    nsIndexes);

            assert (foundNs != null);
            assert (nsIndexes.length == 1);

            SqlValidatorScope ancestorScope = ancestorScopes[0];

            // If the correlated reference is in a scope that's "above" the
            // subquery, then this is a correlated subquery.
            SqlValidatorScope parentScope = bb.scope;
            do {
                if (ancestorScope == parentScope) {
                    return false;
                }
                if (parentScope instanceof DelegatingScope) {
                    parentScope = ((DelegatingScope) parentScope).getParent();
                } else {
                    break;
                }
            } while (parentScope != null);
        }
        return true;
    }

    private RexNode convertJoinCondition(
        Blackboard bb,
        SqlNode condition,
        SqlJoinOperator.ConditionType conditionType,
        RelNode leftRel,
        RelNode rightRel)
    {
        if (condition == null) {
            return rexBuilder.makeLiteral(true);
        }
        switch (conditionType) {
        case On:
            bb.setRoot(new RelNode[] { leftRel, rightRel });
            return bb.convertExpression(condition);
        case Using:
            SqlNodeList list = (SqlNodeList) condition;
            List<String> nameList = new ArrayList<String>();
            for (SqlNode columnName : list) {
                final SqlIdentifier id = (SqlIdentifier) columnName;
                String name = id.getSimple();
                nameList.add(name);
            }
            return convertUsing(leftRel, rightRel, nameList);
        default:
            throw Util.unexpected(conditionType);
        }
    }

    /**
     * Returns an expression for matching columns of a USING clause or inferred
     * from NATURAL JOIN. "a JOIN b USING (x, y)" becomes "a.x = b.x AND a.y =
     * b.y". Returns null if the column list is empty.
     *
     * @param leftRel Left input to the join
     * @param rightRel Right input to the join
     * @param nameList List of column names to join on
     *
     * @return Expression to match columns from name list, or null if name list
     * is empty
     */
    private RexNode convertUsing(
        RelNode leftRel,
        RelNode rightRel,
        List<String> nameList)
    {
        RexNode conditionExp = null;
        for (String name : nameList) {
            final RelDataType leftRowType = leftRel.getRowType();
            RelDataTypeField
                leftField = SqlValidatorUtil.lookupField(leftRowType, name);
            RexNode left =
                rexBuilder.makeInputRef(
                    leftField.getType(),
                    sysFieldCount
                    + leftField.getIndex());
            final RelDataType rightRowType = rightRel.getRowType();
            RelDataTypeField
                rightField = SqlValidatorUtil.lookupField(rightRowType, name);
            RexNode right =
                rexBuilder.makeInputRef(
                    rightField.getType(),
                    sysFieldCount
                    + leftRowType.getFieldList().size()
                    + rightField.getIndex());
            RexNode equalsCall =
                rexBuilder.makeCall(
                    SqlStdOperatorTable.equalsOperator,
                    left,
                    right);
            if (conditionExp == null) {
                conditionExp = equalsCall;
            } else {
                conditionExp =
                    rexBuilder.makeCall(
                        SqlStdOperatorTable.andOperator,
                        conditionExp,
                        equalsCall);
            }
        }
        return conditionExp;
    }

    private static JoinRelType convertJoinType(
        SqlJoinOperator.JoinType joinType)
    {
        switch (joinType) {
        case Comma:
        case Inner:
        case Cross:
            return JoinRelType.INNER;
        case Full:
            return JoinRelType.FULL;
        case Left:
            return JoinRelType.LEFT;
        case Right:
            return JoinRelType.RIGHT;
        default:
            throw Util.unexpected(joinType);
        }
    }

    /**
     * Converts the SELECT, GROUP BY and HAVING clauses of an aggregate query.
     *
     * <p>This method extracts SELECT, GROUP BY and HAVING clauses, and creates
     * an {@link AggConverter}, then delegates to {@link #createAggImpl}.
     * Derived class may override this method to change any of those clauses or
     * specify a different {@link AggConverter}.
     *
     * @param bb Scope within which to resolve identifiers
     * @param select Query
     * @param orderExprList Additional expressions needed to implement ORDER BY
     */
    protected void convertAgg(
        Blackboard bb,
        SqlSelect select,
        List<SqlNode> orderExprList)
    {
        assert bb.root != null : "precondition: child != null";
        SqlNodeList groupList = select.getGroup();
        SqlNodeList selectList = select.getSelectList();
        SqlNode having = select.getHaving();

        createAggImpl(
            bb,
            select,
            selectList,
            groupList,
            having,
            orderExprList);
    }

    protected final void createAggImpl(
        Blackboard bb,
        SqlSelect select,
        SqlNodeList selectList,
        SqlNodeList groupList,
        SqlNode having,
        List<SqlNode> orderExprList)
    {
        SqlNodeList aggList = new SqlNodeList(SqlParserPos.ZERO);

        for (SqlNode selectNode : selectList) {
            if (validator.isAggregate(selectNode)) {
                aggList.add(selectNode);
            }
        }

        // first replace the subqueries inside the aggregates
        // because they will provide input rows to the aggregates.
        replaceSubqueries(bb, aggList);

        // If group-by clause is missing, pretend that it has zero elements.
        if (groupList == null) {
            groupList = SqlNodeList.Empty;
        }

        // register the group exprs

        // build a map to remember the projections from the top scope to the
        // output of the current root.
        //
        // Currently farrago allows expressions, not just column references in
        // group by list. This is not SQL 2003 compliant.

        Map<Integer, Integer> groupExprProjection =
            new HashMap<Integer, Integer>();

        AggConverter aggConverter = new AggConverter(bb, select);

        int i = -1;
        for (SqlNode groupExpr : groupList) {
            ++i;
            final SqlNode expandedGroupExpr =
                validator.expand(groupExpr, bb.scope);
            aggConverter.addGroupExpr(expandedGroupExpr);

            if (expandedGroupExpr instanceof SqlIdentifier) {
                // SQL 2003 does not allow expressions of column references
                SqlIdentifier expr = (SqlIdentifier) expandedGroupExpr;

                // column references should be fully qualified.
                assert (expr.names.length == 2);
                String originalRelName = expr.names[0];
                String originalFieldName = expr.names[1];

                int [] nsIndexes = { -1 };
                final SqlValidatorScope [] ancestorScopes = { null };
                SqlValidatorNamespace foundNs =
                    bb.scope.resolve(
                        originalRelName,
                        ancestorScopes,
                        nsIndexes);

                assert (foundNs != null);
                assert (nsIndexes.length == 1);
                int childNamespaceIndex = nsIndexes[0];

                int namespaceOffset = 0;

                if (childNamespaceIndex > 0) {
                    // If not the first child, need to figure out the width of
                    // output types from all the preceding namespaces
                    assert (ancestorScopes[0] instanceof ListScope);
                    List<SqlValidatorNamespace> children =
                        ((ListScope) ancestorScopes[0]).getChildren();

                    for (int j = 0; j < childNamespaceIndex; j++) {
                        namespaceOffset +=
                            children.get(j).getRowType().getFieldCount();
                    }
                }

                int origPos =
                    namespaceOffset
                    + foundNs.getRowType().getFieldOrdinal(originalFieldName);

                groupExprProjection.put(origPos, i);
            }
        }

        RexNode havingExpr = null;
        List<RexNode> selectExprs = new ArrayList<RexNode>();
        List<String> selectNames = new ArrayList<String>();

        try {
            Util.permAssert(bb.agg == null, "already in agg mode");
            bb.agg = aggConverter;

            // convert the select and having expressions, so that the
            // agg converter knows which aggregations are required

            selectList.accept(aggConverter);
            for (SqlNode expr : orderExprList) {
                expr.accept(aggConverter);
            }
            if (having != null) {
                having.accept(aggConverter);
            }

            // compute inputs to the aggregator
            List<RexNode> preExprs = aggConverter.getPreExprs();
            List<String> preNames = aggConverter.getPreNames();

            if (preExprs.size() == 0) {
                // Special case for COUNT(*), where we can end up with no inputs
                // at all.  The rest of the system doesn't like 0-tuples, so we
                // select a dummy constant here.
                preExprs =
                    Collections.<RexNode>singletonList(
                        rexBuilder.makeLiteral(true));
                preNames = Collections.singletonList(null);
            }

            RelNode inputRel = bb.root;
            Set<String> correlatedVariablesBefore =
                RelOptUtil.getVariablesUsed(inputRel);

            // Project the expressions required by agg and having.
            bb.setRoot(
                CalcRel.createProject(
                    inputRel,
                    preExprs,
                    preNames,
                    true),
                false);
            bb.mapRootRelToFieldProjection.put(bb.root, groupExprProjection);

            Set<String> correlatedVariables =
                RelOptUtil.getVariablesUsed(bb.root);
            correlatedVariables.removeAll(correlatedVariablesBefore);

            // Associate the correlated variables with the new project rel.
            for (String correl : correlatedVariables) {
                mapCorrelToRefRel.put(correl, bb.root);
            }

            // REVIEW jvs 31-Oct-2007:  doesn't the declaration of
            // monotonicity here assume sort-based aggregation at
            // the physical level?

            // Tell bb which of group columns are sorted.
            bb.columnMonotonicities.clear();
            int j = this.sysFieldCount;
            for (SqlNode groupItem : groupList) {
                bb.columnMonotonicities.put(
                    j++,
                    bb.scope.getMonotonicity(groupItem));
            }

            // Add the aggregator
            bb.setRoot(
                createAggregate(
                    bb,
                    Util.bitSetBetween(
                        sysFieldCount,
                        sysFieldCount + aggConverter.groupExprs.size()),
                    aggConverter.getAggCalls()),
                false);

            bb.mapRootRelToFieldProjection.put(bb.root, groupExprProjection);

            // Replace subqueries in having here and modify having to use
            // the replaced expressions
            if (having != null) {
                SqlNode newHaving = pushdownNotForIn(having);
                replaceSubqueries(bb, newHaving);
                havingExpr = bb.convertExpression(newHaving);
                if (havingExpr.isAlwaysTrue()) {
                    havingExpr = null;
                }
            }

            // Now convert the other subqueries in the select list.
            // This needs to be done separately from the subquery inside
            // any aggregate in the select list, and after the aggregate rel
            // is allocated.
            replaceSubqueries(bb, selectList);

            // Now subqueries in the entire select list have been converted.
            // Convert the select expressions to get the final list to be
            // projected.
            int k = 0;

            // For select expressions, use the field names previously assigned
            // by the validator. If we derive afresh, we might generate names
            // like "EXPR$2" that don't match the names generated by the
            // validator. This is especially the case when there are system
            // fields; system fields appear in the relnode's rowtype but do not
            // (yet) appear in the validator type.
            final SelectScope selectScope =
                SqlValidatorUtil.getEnclosingSelectScope(bb.scope);
            final SqlValidatorNamespace selectNamespace =
                validator.getNamespace(selectScope.getNode());
            final List<String> names =
                RelOptUtil.getFieldNameList(
                    selectNamespace.getRowType());
            int sysFieldCount = selectList.size() - names.size();
            for (SqlNode expr : selectList) {
                selectExprs.add(bb.convertExpression(expr));
                selectNames.add(
                    k < sysFieldCount
                    ? validator.deriveAlias(expr, k++)
                    : names.get(k++ - sysFieldCount));
            }

            for (SqlNode expr : orderExprList) {
                selectExprs.add(bb.convertExpression(expr));
                selectNames.add(validator.deriveAlias(expr, k++));
            }
        } finally {
            bb.agg = null;
        }

        // implement HAVING (we have already checked that it is non-trivial)
        if (havingExpr != null) {
            bb.setRoot(CalcRel.createFilter(bb.root, havingExpr), false);
        }

        // implement the SELECT list
        bb.setRoot(
            CalcRel.createProject(
                bb.root,
                selectExprs,
                selectNames,
                true),
            false);

        // Tell bb which select columns are sorted.
        bb.columnMonotonicities.clear();
        int j = 0;
        for (SqlNode selectItem : selectList) {
            bb.columnMonotonicities.put(
                j++,
                bb.scope.getMonotonicity(selectItem));
        }
    }

    /**
     * Creates an AggregateRel.
     *
     * <p>In case the aggregate rel changes the order in which it projects
     * fields, the <code>groupExprProjection</code> parameter is provided, and
     * the implementation of this method may modify it.
     *
     * <p>The <code>sortedCount</code> parameter is the number of expressions
     * known to be monotonic. These expressions must be on the leading edge of
     * the grouping keys. The default implementation of this method ignores this
     * parameter.
     *
     * @param bb Blackboard
     * @param groupSet Bitset of grouping fields
     * @param aggCalls Array of calls to aggregate functions
     * @return AggregateRel
     */
    protected RelNode createAggregate(
        Blackboard bb,
        BitSet groupSet,
        List<AggregateCall> aggCalls)
    {
        return new AggregateRel(
            cluster,
            bb.root,
            validator.getSystemFields(),
            groupSet,
            aggCalls);
    }

    public RexDynamicParam convertDynamicParam(
        final SqlDynamicParam dynamicParam)
    {
        // REVIEW jvs 8-Jan-2005:  dynamic params may be encountered out of
        // order.  Should probably cross-check with the count from the parser
        // at the end and make sure they all got filled in.  Why doesn't List
        // have a resize() method?!?  Make this a utility.
        while (dynamicParam.getIndex() >= dynamicParamSqlNodes.size()) {
            dynamicParamSqlNodes.add(null);
        }

        dynamicParamSqlNodes.set(
            dynamicParam.getIndex(),
            dynamicParam);
        return rexBuilder.makeDynamicParam(
            getDynamicParamType(dynamicParam.getIndex()),
            dynamicParam.getIndex());
    }

    /**
     * Creates a list of collations required to implement the ORDER BY clause,
     * if there is one. Populates <code>extraOrderExprs</code> with any sort
     * expressions which are not in the select clause.
     *
     * @param bb Scope within which to resolve identifiers
     * @param select Select clause, null if order by is applied to set operation
     * (UNION etc.)
     * @param orderList Order by clause, may be null
     * @param extraOrderExprs Sort expressions which are not in the select
     * clause (output)
     * @param collationList List of collations (output)
     *
     * @pre bb.root != null
     */
    protected void gatherOrderExprs(
        Blackboard bb,
        SqlSelect select,
        SqlNodeList orderList,
        List<SqlNode> extraOrderExprs,
        List<RelFieldCollation> collationList)
    {
        // TODO:  add validation rules to SqlValidator also
        assert bb.root != null : "precondition: child != null";
        if (orderList == null) {
            return;
        }
        for (SqlNode orderItem : orderList) {
            collationList.add(
                convertOrderItem(
                    select,
                    orderItem,
                    extraOrderExprs,
                    RelFieldCollation.Direction.Ascending));
        }
    }

    protected RelFieldCollation convertOrderItem(
        SqlSelect select,
        SqlNode orderItem,
        List<SqlNode> extraExprs,
        RelFieldCollation.Direction direction)
    {
        // DESC keyword, e.g. 'select a, b from t order by a desc'.
        if (orderItem instanceof SqlCall) {
            SqlCall call = (SqlCall) orderItem;
            if (call.getOperator() == SqlStdOperatorTable.descendingOperator) {
                return convertOrderItem(
                    select,
                    call.operands[0],
                    extraExprs,
                    RelFieldCollation.Direction.Descending);
            }
        }

        SqlNode converted = validator.expandOrderExpr(select, orderItem);

        // Scan the select list and order exprs for an identical expression.
        if (select != null) {
            SelectScope selectScope = validator.getRawSelectScope(select);
            int ordinal = getExtraSystemFieldPrefixCount(select) - 1;
            for (SqlNode selectItem : selectScope.getExpandedSelectList()) {
                ++ordinal;
                if (selectItem.getKind() == SqlKind.AS) {
                    selectItem = ((SqlCall) selectItem).operands[0];
                }
                if (converted.equalsDeep(selectItem, false)) {
                    return new RelFieldCollation(ordinal, direction);
                }
            }

            for (SqlNode extraExpr : extraExprs) {
                ++ordinal;
                if (converted.equalsDeep(extraExpr, false)) {
                    return new RelFieldCollation(ordinal, direction);
                }
            }
        }

        // TODO:  handle collation sequence
        // TODO: flag expressions as non-standard

        int ordinal = select.getSelectList().size() + extraExprs.size();
        extraExprs.add(converted);
        return new RelFieldCollation(ordinal, direction);
    }

    /**
     * Returns the number of system fields that will be prefixed to the SELECT
     * clause from this query when this query is converted.
     *
     * @param select Query
     *
     * @return Number of system fields that will be added to the select clause
     * when this query is converted
     */
    protected int getExtraSystemFieldPrefixCount(SqlSelect select)
    {
        return validator.getSystemFields().size();
    }

    /**
     * Returns whether to decorrelate the query as part of the translation
     * process.
     *
     * @return Whether decorrelation is enabled
     */
    protected boolean enableDecorrelation()
    {
        // disable subquery decorrelation when needed.
        // e.g. if outer joins are not supported.
        return decorrelationEnabled;
    }

    /**
     * Returns whether there are any correlating variables in this statement.
     *
     * @return whether there are any correlating variables
     */
    public boolean hasCorrelation()
    {
        return !mapCorVarToCorRel.isEmpty();
    }

    protected RelNode decorrelateQuery(RelNode rootRel)
    {
        RelDecorrelator decorrelator =
            new RelDecorrelator(
                rexBuilder,
                mapRefRelToCorVar,
                mapCorVarToCorRel,
                mapFieldAccessToCorVar);
        boolean dumpPlan = sqlToRelTracer.isLoggable(Level.FINE);

        RelNode newRootRel = decorrelator.removeCorrelationViaRule(rootRel);

        if (dumpPlan) {
            sqlToRelTracer.fine(
                RelOptUtil.dumpPlan(
                    "Plan after removing CorrelatorRel",
                    newRootRel,
                    false,
                    SqlExplainLevel.EXPPLAN_ATTRIBUTES));
        }

        if (!mapCorVarToCorRel.isEmpty()) {
            newRootRel = decorrelator.decorrelate(newRootRel);
        }

        return newRootRel;
    }

    /**
     * Sets whether to trim unused fields as part of the conversion process.
     *
     * @param trim Whether to trim unused fields
     */
    public void setTrimUnusedFields(boolean trim)
    {
        this.trimUnusedFields = trim;
    }

    /**
     * Returns whether to trim unused fields as part of the conversion process.
     *
     * @return Whether to trim unused fields
     */
    public boolean isTrimUnusedFields()
    {
        return trimUnusedFields;
    }

    /**
     * Recursively converts a query to a relational expression.
     *
     * @param query Query
     * @param context Position of query within the SQL statement
     * @return Relational expression
     */
    protected RelNode convertQueryRecursive(SqlNode query, QueryContext context)
    {
        switch (query.getKind()) {
        case SELECT:
            return convertSelect((SqlSelect) query);
        case INSERT:
            return convertInsert((SqlInsert) query);
        case DELETE:
            return convertDelete((SqlDelete) query);
        case UPDATE:
            return convertUpdate((SqlUpdate) query);
        case MERGE:
            return convertMerge((SqlMerge) query);
        case UNION:
        case INTERSECT:
        case EXCEPT:
            return convertSetOp((SqlCall) query);
        default:
            throw Util.newInternal("not a query: " + query);
        }
    }

    /**
     * Converts a set operation (UNION, INTERSECT, MINUS) into relational
     * expressions.
     *
     * @param call Call to set operator
     * @return Relational expression
     */
    protected RelNode convertSetOp(SqlCall call)
    {
        final SqlNode[] operands = call.getOperands();
        final RelNode left =
            convertQueryRecursive(operands[0], QueryContext.SUBQUERY);
        final RelNode right =
            convertQueryRecursive(operands[1], QueryContext.SUBQUERY);
        boolean all = false;
        if (call.getOperator() instanceof SqlSetOperator) {
            all = ((SqlSetOperator) (call.getOperator())).isAll();
        }
        switch (call.getKind()) {
        case UNION:
            return new UnionRel(
                cluster,
                new RelNode[] { left, right },
                all);

        case INTERSECT:
            // TODO:  all
            if (!all) {
                return new IntersectRel(
                    cluster,
                    new RelNode[] { left, right },
                    all);
            } else {
                throw Util.newInternal(
                    "set operator INTERSECT ALL not suported");
            }

        case EXCEPT:
            // TODO:  all
            if (!all) {
                return new MinusRel(
                    cluster,
                    new RelNode[] { left, right },
                    all);
            } else {
                throw Util.newInternal(
                    "set operator EXCEPT ALL not suported");
            }

        default:
            throw Util.unexpected(call.getKind());
        }
    }

    protected RelNode convertInsert(SqlInsert call)
    {
        RelOptTable targetTable = getTargetTable(call);

        RelNode sourceRel =
            convertQueryRecursive(
                call.getSource(),
                QueryContext.SUBQUERY);
        RelNode massagedRel = convertInsertColumnList(call, sourceRel);

        return new TableModificationRel(
            cluster,
            targetTable,
            connection,
            massagedRel,
            TableModificationRel.Operation.INSERT,
            null,
            false);
    }

    protected RelOptTable getTargetTable(SqlNode call)
    {
        SqlValidatorNamespace targetNs = validator.getNamespace(call);
        RelOptTable targetTable =
            SqlValidatorUtil.getRelOptTable(targetNs, schema, null, null);
        return targetTable;
    }

    /**
     * Creates a source for an INSERT statement.
     *
     * <p>If the column list is not specified, source expressions match target
     * columns in order.
     *
     * <p>If the column list is specified, Source expressions are mapped to
     * target columns by name via targetColumnList, and may not cover the entire
     * target table. So, we'll make up a full row, using a combination of
     * default values and the source expressions provided.
     *
     * @param call Insert expression
     * @param sourceRel Source relational expression
     *
     * @return Converted INSERT statement
     */
    protected RelNode convertInsertColumnList(
        SqlInsert call,
        RelNode sourceRel)
    {
        RelDataType sourceRowType = sourceRel.getRowType();
        final RexNode sourceRef =
            rexBuilder.makeRangeReference(sourceRowType, 0, false);
        final List<String> targetColumnNames = new ArrayList<String>();
        final List<RexNode> columnExprs = new ArrayList<RexNode>();
        collectInsertTargets(call, sourceRef, targetColumnNames, columnExprs);

        final RelOptTable targetTable = getTargetTable(call);
        final RelDataType targetRowType =
            RelOptUtil.getRowTypeIncludingSystemFields(
                typeFactory, targetTable, true);
        int expCount = targetRowType.getFieldCount();
        RexNode [] sourceExps = new RexNode[expCount];
        String [] fieldNames = new String[expCount];

        // Walk the name list and place the associated value in the
        // expression list according to the ordinal value returned from
        // the table construct, leaving nulls in the list for columns
        // that are not referenced.
        for (int i = 0; i < targetColumnNames.size(); i++) {
            String targetColumnName = targetColumnNames.get(i);
            int iTarget = targetRowType.getFieldOrdinal(targetColumnName);
            assert iTarget != -1 : "column " + targetColumnName + " not found";
            sourceExps[iTarget] = columnExprs.get(i);
        }

        // Walk the expresion list and get default values for any columns
        // that were not supplied in the statement. Get field names too.
        for (int i = 0; i < expCount; ++i) {
            fieldNames[i] = targetRowType.getFields()[i].getName();
            if (sourceExps[i] != null) {
                if (defaultValueFactory.isGeneratedAlways(targetTable, i)) {
                    throw EigenbaseResource.instance().InsertIntoAlwaysGenerated
                    .ex(
                        fieldNames[i]);
                }
                continue;
            }
            sourceExps[i] =
                defaultValueFactory.newColumnDefaultValue(targetTable, i);

            // bare nulls are dangerous in the wrong hands
            sourceExps[i] =
                castNullLiteralIfNeeded(
                    sourceExps[i],
                    targetRowType.getFields()[i].getType());
        }

        return CalcRel.createProject(sourceRel, sourceExps, fieldNames, true);
    }

    private RexNode castNullLiteralIfNeeded(RexNode node, RelDataType type)
    {
        if (!RexLiteral.isNullLiteral(node)) {
            return node;
        }
        return rexBuilder.makeCast(type, node);
    }

    /**
     * Given an INSERT statement, collects the list of names to be populated and
     * the expressions to put in them.
     *
     * @param call Insert statement
     * @param sourceRef Expression representing a row from the source relational
     * expression
     * @param targetColumnNames List of target column names, to be populated
     * @param columnExprs List of expressions, to be populated
     */
    protected void collectInsertTargets(
        SqlInsert call,
        final RexNode sourceRef,
        final List<String> targetColumnNames,
        List<RexNode> columnExprs)
    {
        final RelOptTable targetTable = getTargetTable(call);
        final RelDataType targetRowType = targetTable.getRowType();
        SqlNodeList targetColumnList = call.getTargetColumnList();
        if (targetColumnList == null) {
            targetColumnNames.addAll(
                RelOptUtil.getFieldNameList(targetRowType));
        } else {
            for (int i = 0; i < targetColumnList.size(); i++) {
                SqlIdentifier id = (SqlIdentifier) targetColumnList.get(i);
                targetColumnNames.add(id.getSimple());
            }
        }

        for (int i = 0; i < targetColumnNames.size(); i++) {
            final RexNode expr = rexBuilder.makeFieldAccess(sourceRef, i);
            columnExprs.add(expr);
        }
    }

    protected RelNode convertUpdateColumnList(
        SqlDml call,
        RelNode sourceRel)
    {
        RelDataType sourceRowType = sourceRel.getRowType();

        final List<String> targetColumnNames = new ArrayList<String>();
        final List<RexNode> columnExprs = new ArrayList<RexNode>();
        final int offset = validator.getSystemFields().size();
        int i = 0;
        for (RelDataTypeField field : sourceRowType.getFieldList()) {
            if (i++ < offset) {
                continue;
            }
            targetColumnNames.add(field.getName());
            columnExprs.add(
                rexBuilder.makeInputRef(
                    field.getType(), field.getIndex()));
        }
        return CalcRel.createProject(
            sourceRel, columnExprs, targetColumnNames, true);
    }

    private RelNode convertDelete(SqlDelete call)
    {
        RelOptTable targetTable = getTargetTable(call);
        RelNode sourceRel = convertSelect(call.getSource());
        final RelNode massagedRel = convertUpdateColumnList(call, sourceRel);
        return new TableModificationRel(
            cluster,
            targetTable,
            connection,
            massagedRel,
            TableModificationRel.Operation.DELETE,
            null,
            false);
    }

    private RelNode convertUpdate(SqlUpdate call)
    {
        RelOptTable targetTable = getTargetTable(call);

        // convert update column list from SqlIdentifier to String
        List<String> targetColumnNameList = new ArrayList<String>();
        for (SqlNode node : call.getTargetColumnList()) {
            SqlIdentifier id = (SqlIdentifier) node;
            String name = id.getSimple();
            targetColumnNameList.add(name);
        }

        RelNode sourceRel =
            convertQueryRecursive(
                call.getSource(), QueryContext.SUBQUERY);
        final RelNode massagedRel = convertUpdateColumnList(call, sourceRel);

        return new TableModificationRel(
            cluster,
            targetTable,
            connection,
            massagedRel,
            TableModificationRel.Operation.UPDATE,
            targetColumnNameList,
            false);
    }

    private RelNode convertMerge(SqlMerge call)
    {
        RelOptTable targetTable = getTargetTable(call);

        // convert update column list from SqlIdentifier to String
        List<String> targetColumnNameList = new ArrayList<String>();
        SqlUpdate updateCall = call.getUpdateCall();
        if (updateCall != null) {
            for (SqlNode targetColumn : updateCall.getTargetColumnList()) {
                SqlIdentifier id = (SqlIdentifier) targetColumn;
                String name = id.getSimple();
                targetColumnNameList.add(name);
            }
        }

        // replace the projection of the source select with a
        // projection that contains the following:
        // 1) the expressions corresponding to the new insert row (if there is
        //    an insert)
        // 2) all columns from the target table (if there is an update)
        // 3) the set expressions in the update call (if there is an update)

        // first, convert the merge's source select to construct the columns
        // from the target table and the set expressions in the update call
        RelNode mergeSourceRel = convertSelect(call.getSourceSelect());

        // then, convert the insert statement so we can get the insert
        // values expressions
        SqlInsert insertCall = call.getInsertCall();
        int nLevel1Exprs = 0;
        RexNode [] level1InsertExprs = null;
        RexNode [] level2InsertExprs = null;
        if (insertCall != null) {
            RelNode insertRel = convertInsert(insertCall);

            // if there are 2 level of projections in the insert source, combine
            // them into a single project; level1 refers to the topmost project;
            // the level1 projection contains references to the level2
            // expressions, except in the case where no target expression was
            // provided, in which case, the expression is the default value for
            // the column; or if the expressions directly map to the source
            // table
            level1InsertExprs =
                ((ProjectRel) insertRel.getInput(0)).getProjectExps();
            if (insertRel.getInput(0).getInput(0) instanceof ProjectRel) {
                level2InsertExprs =
                    ((ProjectRel) insertRel.getInput(0).getInput(0))
                    .getProjectExps();
            }
            nLevel1Exprs = level1InsertExprs.length;
        }

        JoinRel joinRel = (JoinRel) mergeSourceRel.getInput(0);
        int nSourceFields = joinRel.getLeft().getRowType().getFieldCount();
        int numProjExprs = nLevel1Exprs;
        if (updateCall != null) {
            numProjExprs +=
                mergeSourceRel.getRowType().getFieldCount() - nSourceFields;
        }
        RexNode [] projExprs = new RexNode[numProjExprs];
        for (int level1Idx = 0; level1Idx < nLevel1Exprs; level1Idx++) {
            if ((level2InsertExprs != null)
                && (level1InsertExprs[level1Idx] instanceof RexInputRef))
            {
                int level2Idx =
                    ((RexInputRef) level1InsertExprs[level1Idx]).getIndex();
                projExprs[level1Idx] = level2InsertExprs[level2Idx];
            } else {
                projExprs[level1Idx] = level1InsertExprs[level1Idx];
            }
        }
        if (updateCall != null) {
            RexNode [] updateExprs =
                ((ProjectRel) mergeSourceRel).getProjectExps();
            System.arraycopy(
                updateExprs,
                nSourceFields,
                projExprs,
                nLevel1Exprs,
                numProjExprs - nLevel1Exprs);
        }

        RelNode massagedRel =
            CalcRel.createProject(joinRel, projExprs, null, true);

        return new TableModificationRel(
            cluster,
            targetTable,
            connection,
            massagedRel,
            TableModificationRel.Operation.MERGE,
            targetColumnNameList,
            false);
    }

    /**
     * Converts an identifier into an expression in a given scope. For example,
     * the "empno" in "select empno from emp join dept" becomes "emp.empno".
     */
    private RexNode convertIdentifier(
        Blackboard bb,
        SqlIdentifier identifier)
    {
        // first check for reserved identifiers like CURRENT_USER
        final SqlCall call = SqlUtil.makeCall(opTab, identifier);
        if (call != null) {
            return bb.convertExpression(call);
        }

        if (bb.agg != null) {
            throw Util.newInternal(
                "Identifier '" + identifier
                + "' is not a group expr");
        }

        SqlValidatorNamespace namespace = null;
        if (bb.scope != null) {
            identifier = bb.scope.fullyQualify(identifier);
            namespace = bb.scope.resolve(identifier.names[0], null, null);
        }
        RexNode e = bb.lookupExp(identifier.names[0]);
        final String correlationName;
        if (e instanceof RexCorrelVariable) {
            correlationName = ((RexCorrelVariable) e).getName();
        } else {
            correlationName = null;
        }

        for (int i = 1; i < identifier.names.length; i++) {
            String name = identifier.names[i];
            if (namespace != null) {
                name = namespace.translate(name);
                namespace = null;
            }
            e = rexBuilder.makeFieldAccess(e, name);
        }
        if (e instanceof RexInputRef) {
            // adjust the type to account for nulls introduced by outer joins
            e = adjustInputRef(bb, (RexInputRef) e);
        }

        if (null != correlationName) {
            // REVIEW: make mapCorrelateVariableToRexNode map to RexFieldAccess
            assert e instanceof RexFieldAccess;
            final RexNode prev =
                bb.mapCorrelateVariableToRexNode.put(correlationName, e);
            assert prev == null;
        }
        return e;
    }

    /**
     * Adjusts the type of a reference to an input field to account for nulls
     * introduced by outer joins; and adjusts the offset to match the physical
     * implementation.
     *
     * @param bb Blackboard
     * @param inputRef Input ref
     *
     * @return Adjusted input ref
     */
    protected RexInputRef adjustInputRef(
        Blackboard bb,
        RexInputRef inputRef)
    {
        RelDataTypeField field = bb.getRootField(inputRef);

        // Adjust whether type is nullable.
        if (field != null) {
            inputRef =
                rexBuilder.makeInputRef(
                    field.getType(),
                    inputRef.getIndex());
        }

        // Adjust offset for system fields.
        if (bb.scope instanceof JoinScope) {
            final List<RelDataTypeField> systemFields =
                validator.getSystemFields();
            inputRef = new RexInputRef(
                inputRef.getIndex() + systemFields.size(),
                inputRef.getType());
        }

        return inputRef;
    }

    /**
     * Converts a row constructor into a relational expression.
     *
     * @param bb Blackboard
     * @param rowConstructor Row constructor expression
     *
     * @return Relational expression which returns a single row.
     *
     * @pre isRowConstructor(rowConstructor)
     */
    private RelNode convertRowConstructor(
        Blackboard bb,
        SqlCall rowConstructor)
    {
        assert isRowConstructor(rowConstructor) : rowConstructor;
        final SqlNode [] operands = rowConstructor.getOperands();
        return convertMultisets(operands, bb);
    }

    private RelNode convertCursor(Blackboard bb, SqlCall cursorCall)
    {
        assert (cursorCall.operands.length == 1);
        SqlNode query = cursorCall.operands[0];
        RelNode converted =
            convertQuery(query, false, SqlToRelConverter.QueryContext.CURSOR);
        int iCursor = bb.cursors.size();
        bb.cursors.add(converted);
        RexNode expr =
            new RexInputRef(
                iCursor,
                converted.getRowType());
        bb.mapSubqueryToExpr.put(cursorCall, expr);
        return converted;
    }

    private RelNode convertMultisets(final SqlNode [] operands, Blackboard bb)
    {
        // NOTE: Wael 2/04/05: this implementation is not the most efficent in
        // terms of planning since it generates XOs that can be reduced.
        List<Object> joinList = new ArrayList<Object>();
        List<SqlNode> lastList = new ArrayList<SqlNode>();
        for (int i = 0; i < operands.length; i++) {
            SqlNode operand = operands[i];
            if (!(operand instanceof SqlCall)) {
                lastList.add(operand);
                continue;
            }

            final SqlCall call = (SqlCall) operand;
            final SqlOperator op = call.getOperator();
            if ((op != SqlStdOperatorTable.multisetValueConstructor)
                && (op != SqlStdOperatorTable.multisetQueryConstructor))
            {
                lastList.add(operand);
                continue;
            }
            final RelNode input;
            if (op == SqlStdOperatorTable.multisetValueConstructor) {
                final SqlNodeList list = SqlUtil.toNodeList(call.operands);
                assert bb.scope instanceof SelectScope : bb.scope;
                CollectNamespace nss =
                    (CollectNamespace) validator.getNamespace(call);
                Blackboard usedBb;
                if (null != nss) {
                    usedBb = createBlackboard(nss.getScope(), null);
                } else {
                    usedBb =
                        createBlackboard(
                            new ListScope(bb.scope) {
                                public SqlNode getNode()
                                {
                                    return call;
                                }
                            },
                            null);
                }
                RelDataType multisetType = validator.getValidatedNodeType(call);
                validator.setValidatedNodeType(
                    list,
                    multisetType.getComponentType());
                input = convertQueryOrInList(usedBb, list);
            } else {
                input =
                    convertQuery(
                        call.operands[0],
                        false,
                        SqlToRelConverter.QueryContext.OTHER);
            }

            if (lastList.size() > 0) {
                joinList.add(lastList);
            }
            lastList = new ArrayList<SqlNode>();
            CollectRel collectRel =
                new CollectRel(
                    cluster,
                    input,
                    validator.deriveAlias(call, i));
            joinList.add(collectRel);
        }

        if (joinList.size() == 0) {
            joinList.add(lastList);
        }

        for (int i = 0; i < joinList.size(); i++) {
            Object o = joinList.get(i);
            if (o instanceof List) {
                List<SqlNode> projectList = (List<SqlNode>) o;
                final List<RexNode> selectList = new ArrayList<RexNode>();
                final List<String> fieldNameList = new ArrayList<String>();
                for (int j = 0; j < projectList.size(); j++) {
                    SqlNode operand = projectList.get(j);
                    selectList.add(bb.convertExpression(operand));

                    // REVIEW angel 5-June-2005: Use deriveAliasFromOrdinal
                    // instead of deriveAlias to match field names from
                    // SqlRowOperator. Otherwise, get error   Type
                    // 'RecordType(INTEGER EMPNO)' has no field 'EXPR$0' when
                    // doing   select * from unnest(     select multiset[empno]
                    // from sales.emps);

                    fieldNameList.add(SqlUtil.deriveAliasFromOrdinal(j));
                }

                RelNode projRel =
                    CalcRel.createProject(
                        new OneRowRel(cluster),
                        selectList,
                        fieldNameList);

                Set<String> correlatedVariables =
                    RelOptUtil.getVariablesUsed(projRel);

                // Associate the correlated variables with the new project rel.
                for (String correl : correlatedVariables) {
                    mapCorrelToRefRel.put(correl, projRel);
                }

                joinList.set(i, projRel);
            }
        }

        RelNode ret = (RelNode) joinList.get(0);
        for (int i = 1; i < joinList.size(); i++) {
            RelNode relNode = (RelNode) joinList.get(i);
            ret =
                createJoin(
                    ret,
                    relNode,
                    rexBuilder.makeLiteral(true),
                    JoinRelType.INNER,
                    Collections.<String>emptySet());
        }
        return ret;
    }

    /**
     * Factory method that creates a join.
     * A subclass can override to use a different kind of join.
     *
     * @param left Left input
     * @param right Right input
     * @param condition Join condition
     * @param joinType Join type
     * @param variablesStopped Set of names of variables which are set by the
     * LHS and used by the RHS and are not available to nodes above this JoinRel
     * in the tree
     * @return A relational expression representing a join
     */
    protected RelNode createJoin(
        RelNode left,
        RelNode right,
        RexNode condition,
        JoinRelType joinType,
        Set<String> variablesStopped)
    {
        return new JoinRel(
            cluster,
            left,
            right,
            condition,
            joinType,
            variablesStopped);
    }

    private void convertSelectList(
        Blackboard bb,
        SqlSelect select,
        List<SqlNode> orderList)
    {
        SqlNodeList selectList = validator.getExpandedSelectList(select);

        replaceSubqueries(bb, selectList);

        List<String> fieldNames = new ArrayList<String>();
        List<RexNode> exprs = new ArrayList<RexNode>();
        Collection<String> aliases = new TreeSet<String>();

        // Project any system fields. (Must be done before regular select items,
        // because offsets may be affected.)
        final List<SqlMonotonicity> columnMonotonicityList =
            new ArrayList<SqlMonotonicity>();
        extraSelectItems(
            bb,
            select,
            exprs,
            fieldNames,
            aliases,
            columnMonotonicityList);

        // Project select clause.
        int i = -1;
        for (SqlNode expr : selectList) {
            ++i;
            exprs.add(bb.convertExpression(expr));
            fieldNames.add(deriveAlias(expr, aliases, i));
        }

        // Project extra fields for sorting.
        for (SqlNode expr : orderList) {
            ++i;
            SqlNode expr2 = validator.expandOrderExpr(select, expr);
            exprs.add(bb.convertExpression(expr2));
            fieldNames.add(deriveAlias(expr, aliases, i));
        }

        fieldNames = SqlValidatorUtil.uniquify(fieldNames);

        RelNode inputRel = bb.root;
        Set<String> correlatedVariablesBefore =
            RelOptUtil.getVariablesUsed(inputRel);

        bb.setRoot(
            CalcRel.createProject(bb.root, exprs, fieldNames),
            false);

        assert bb.columnMonotonicities.isEmpty();
        int j = 0;
        for (SqlMonotonicity monotonicity : columnMonotonicityList) {
            bb.columnMonotonicities.put(j++, monotonicity);
        }
        for (SqlNode selectItem : selectList) {
            bb.columnMonotonicities.put(
                j++,
                selectItem.getMonotonicity(bb.scope));
        }

        Set<String> correlatedVariables = RelOptUtil.getVariablesUsed(bb.root);
        correlatedVariables.removeAll(correlatedVariablesBefore);

        // Associate the correlated variables with the new project rel.
        for (String correl : correlatedVariables) {
            mapCorrelToRefRel.put(correl, bb.root);
        }
    }

    /**
     * Adds extra select items. The default implementation adds nothing; derived
     * classes may add columns to exprList, nameList, aliasList and
     * columnMonotonicityList.
     *
     * @param bb Blackboard
     * @param select Select statement being translated
     * @param exprList List of expressions in select clause
     * @param nameList List of names, one per column
     * @param aliasList Collection of aliases that have been used already
     * @param columnMonotonicityList List of monotonicity, one per column
     */
    protected void extraSelectItems(
        Blackboard bb,
        SqlSelect select,
        List<RexNode> exprList,
        List<String> nameList,
        Collection<String> aliasList,
        List<SqlMonotonicity> columnMonotonicityList)
    {
    }

    private String deriveAlias(
        final SqlNode node,
        Collection<String> aliases,
        final int ordinal)
    {
        String alias = validator.deriveAlias(node, ordinal);
        if ((alias == null) || aliases.contains(alias)) {
            String aliasBase = (alias == null) ? "EXPR$" : alias;
            for (int j = 0;; j++) {
                alias = aliasBase + j;
                if (!aliases.contains(alias)) {
                    break;
                }
            }
        }
        aliases.add(alias);
        return alias;
    }

    /**
     * Converts a values clause (as in "INSERT INTO T(x,y) VALUES (1,2)") into a
     * relational expression.
     *
     * @param bb Blackboard
     * @param values Call to SQL VALUES operator
     */
    protected void convertValues(
        Blackboard bb,
        SqlCall values)
    {
        // Attempt direct conversion to ValuesRel; if that fails, deal with
        // fancy stuff like subqueries below.
        RelNode valuesRel =
            convertRowValues(
                bb,
                values,
                Arrays.asList(values.getOperands()),
                true);
        if (valuesRel != null) {
            bb.setRoot(valuesRel, true);
            return;
        }

        SqlNode [] rowConstructorList = values.getOperands();
        List<RelNode> unionRels = new ArrayList<RelNode>();
        for (SqlNode rowConstructor1 : rowConstructorList) {
            SqlCall rowConstructor = (SqlCall) rowConstructor1;
            Blackboard tmpBb = createBlackboard(bb.scope, null);
            replaceSubqueries(tmpBb, rowConstructor);
            RexNode [] exps = new RexNode[rowConstructor.operands.length];
            String [] fieldNames = new String[rowConstructor.operands.length];
            for (int j = 0; j < rowConstructor.operands.length; j++) {
                final SqlNode node = rowConstructor.operands[j];
                exps[j] = tmpBb.convertExpression(node);
                fieldNames[j] = validator.deriveAlias(node, j);
            }
            RelNode in =
                (null == tmpBb.root)
                ? new OneRowRel(cluster)
                : tmpBb.root;
            unionRels.add(
                CalcRel.createProject(
                    in,
                    exps,
                    fieldNames,
                    true));
        }

        if (unionRels.size() == 0) {
            throw Util.newInternal("empty values clause");
        } else if (unionRels.size() == 1) {
            bb.setRoot(
                unionRels.get(0),
                true);
        } else {
            bb.setRoot(
                new UnionRel(
                    cluster,
                    unionRels.toArray(new RelNode[unionRels.size()]),
                    true),
                true);
        }

        // REVIEW jvs 22-Jan-2004:  should I add
        // mapScopeToLux.put(validator.getScope(values),bb.root);
        // ?
    }

    public RexNode convertField(
        RelDataType inputRowType,
        RelDataTypeField field)
    {
        final RelDataTypeField inputField =
            inputRowType.getField(field.getName());
        if (inputField == null) {
            throw Util.newInternal("field not found: " + field);
        }
        return RexUtil.maybeCast(
            rexBuilder,
            field.getType(),
            rexBuilder.makeInputRef(
                inputField.getType(),
                inputField.getIndex()));
    }

    private String createCorrel()
    {
        int n = nextCorrel++;
        return correlPrefix + n;
    }

    private int getCorrelOrdinal(String correlName)
    {
        assert (correlName.startsWith(correlPrefix));
        return Integer.parseInt(correlName.substring(correlPrefix.length()));
    }

    //~ Inner Classes ----------------------------------------------------------

    /**
     * A <code>SchemaCatalogReader</code> looks up catalog information from a
     * {@link org.eigenbase.relopt.RelOptSchema schema object}.
     */
    public static class SchemaCatalogReader
        implements SqlValidatorCatalogReader
    {
        private final RelOptSchema schema;
        private final boolean upperCase;

        public SchemaCatalogReader(
            RelOptSchema schema,
            boolean upperCase)
        {
            this.schema = schema;
            this.upperCase = upperCase;
        }

        public SqlValidatorTable getTable(String [] names)
        {
            if (names.length != 1) {
                return null;
            }
            final RelOptTable table =
                schema.getTableForMember(new String[] { maybeUpper(names[0]) });
            if (table != null) {
                return new SqlValidatorTable() {
                    public RelDataType getRowType()
                    {
                        return table.getRowType();
                    }

                    public String [] getQualifiedName()
                    {
                        return null;
                    }

                    public SqlMonotonicity getMonotonicity(String columnName)
                    {
                        return SqlMonotonicity.NotMonotonic;
                    }

                    public SqlAccessType getAllowedAccess()
                    {
                        return SqlAccessType.ALL;
                    }
                };
            }
            return null;
        }

        public RelDataType getNamedType(SqlIdentifier typeName)
        {
            return null;
        }

        public List<SqlMoniker> getAllSchemaObjectNames(List<String> names)
        {
            throw new UnsupportedOperationException();
        }

        public String getSchemaName()
        {
            throw new UnsupportedOperationException();
        }

        private String maybeUpper(String s)
        {
            return upperCase ? s.toUpperCase() : s;
        }
    }

    /**
     * Workspace for translating an individual SELECT statement (or sub-SELECT).
     */
    protected class Blackboard
        implements SqlRexContext,
            SqlVisitor<RexNode>
    {
        /**
         * Collection of {@link RelNode} objects which correspond to a SELECT
         * statement.
         */
        public final SqlValidatorScope scope;
        private final Map<String, RexNode> nameToNodeMap;
        public RelNode root;
        private RelNode [] inputs;
        private final Map<String, RexNode> mapCorrelateVariableToRexNode =
            new HashMap<String, RexNode>();

        List<RelNode> cursors;

        /**
         * List of <code>IN</code> and <code>EXISTS</code> nodes inside this
         * <code>SELECT</code> statement (but not inside sub-queries).
         */
        private final List<SqlNode> subqueryList = new ArrayList<SqlNode>();

        /**
         * Maps IN and EXISTS {@link SqlSelect sub-queries} to the expressions
         * which will be used to access them.
         */
        private final Map<SqlNode, RexNode> mapSubqueryToExpr =
            new HashMap<SqlNode, RexNode>();

        private boolean subqueryNeedsOuterJoin;

        /**
         * Workspace for building aggregates.
         */
        AggConverter agg;

        SqlRexOverContext rexOverContext;

        /**
         * Project the groupby expressions out of the root of this sub-select.
         * Subqueries can reference group by expressions projected from the
         * "right" to the subquery.
         */
        private final Map<RelNode, Map<Integer, Integer>>
            mapRootRelToFieldProjection =
                new HashMap<RelNode, Map<Integer, Integer>>();

        private final Map<Integer, SqlMonotonicity> columnMonotonicities =
            new HashMap<Integer, SqlMonotonicity>();

        private final List<RelDataTypeField> systemFieldList =
            new ArrayList<RelDataTypeField>();

        /**
         * Creates a Blackboard.
         *
         * @param scope Name-resolution scope for expressions validated within
         * this query. Can be null if this Blackboard is for a leaf node, say
         * @param nameToNodeMap Map which translates the expression to map a
         * given parameter into, if translating expressions; null otherwise
         */
        protected Blackboard(
            SqlValidatorScope scope,
            Map<String, RexNode> nameToNodeMap)
        {
            this.scope = scope;
            this.nameToNodeMap = nameToNodeMap;
            this.cursors = new ArrayList<RelNode>();
            subqueryNeedsOuterJoin = false;
        }

        public RexNode register(
            RelNode rel,
            JoinRelType joinType)
        {
            return register(rel, joinType, null);
        }

        /**
         * Registers a relational expression.
         *
         * @param rel Relational expression
         * @param joinType Join type
         * @param leftJoinKeysForIn LHS of IN clause, or null for expressions
         * other than IN
         *
         * @return Expression with which to refer to the row (or partial row)
         * coming from this relational expression's side of the join. rchen
         * 2006-08-17: temporarily translate select * from X where a not in
         * (select b form Y); to select X.* from X, (select distinct b from Y)
         * where not (a = b);
         */
        public RexNode register(
            RelNode rel,
            JoinRelType joinType,
            RexNode [] leftJoinKeysForIn)
        {
            assert joinType != null;
            if (root == null) {
                assert (leftJoinKeysForIn == null);
                setRoot(rel, false);
                return rexBuilder.makeRangeReference(
                    root.getRowType(),
                    0,
                    false);
            } else {
                RexNode joinCond = null;

                int origLeftInputCount = root.getRowType().getFieldCount();
                if (leftJoinKeysForIn != null) {
                    RexNode [] newLeftInputExpr =
                        new RexNode[origLeftInputCount
                            + leftJoinKeysForIn.length];

                    for (int i = 0; i < origLeftInputCount; i++) {
                        newLeftInputExpr[i] =
                            rexBuilder.makeInputRef(
                                root.getRowType().getFields()[i].getType(),
                                i);
                    }

                    System.arraycopy(
                        leftJoinKeysForIn,
                        origLeftInputCount - origLeftInputCount,
                        newLeftInputExpr,
                        origLeftInputCount,
                        newLeftInputExpr.length - origLeftInputCount);

                    ProjectRel newLeftInput =
                        (ProjectRel) CalcRel.createProject(
                            root,
                            newLeftInputExpr,
                            null,
                            true);

                    // maintain the group by mapping in the new ProjectRel
                    if (mapRootRelToFieldProjection.containsKey(root)) {
                        mapRootRelToFieldProjection.put(
                            newLeftInput,
                            mapRootRelToFieldProjection.get(root));
                    }

                    setRoot(newLeftInput, false);

                    RexNode [] newLeftJoinKeysForIn =
                        new RexNode[leftJoinKeysForIn.length];

                    for (int i = 0; i < leftJoinKeysForIn.length; i++) {
                        newLeftJoinKeysForIn[i] =
                            rexBuilder.makeInputRef(
                                newLeftInput.getProjectExps()[origLeftInputCount
                                    + i].getType(),
                                origLeftInputCount + i);
                    }

                    joinCond =
                        createJoinConditionForIn(
                            this,
                            newLeftJoinKeysForIn,
                            rel);
                }

                int leftFieldCount = root.getRowType().getFieldCount();
                RelNode join =
                    createJoin(
                        this,
                        root,
                        rel,
                        joinCond,
                        joinType);

                setRoot(join, false);

                if ((leftJoinKeysForIn != null)
                    && (joinType == JoinRelType.LEFT))
                {
                    int rightFieldLength = rel.getRowType().getFieldCount();
                    assert (leftJoinKeysForIn.length == (rightFieldLength - 1));

                    int rexRangeRefLength =
                        leftJoinKeysForIn.length + rightFieldLength;
                    List<RelDataTypeField> fields =
                        new ArrayList<RelDataTypeField>();
                    for (int i = 0; i < rexRangeRefLength; i++) {
                        fields.add(
                            join.getRowType().getFields()[
                                origLeftInputCount + i]);
                    }

                    RelDataType returnType =
                        typeFactory.createStructType(fields);

                    return rexBuilder.makeRangeReference(
                        returnType,
                        origLeftInputCount,
                        false);
                } else {
                    return rexBuilder.makeRangeReference(
                        rel.getRowType(),
                        leftFieldCount
                        + join.getSystemFieldList().size(),
                        joinType.generatesNullsOnRight());
                }
            }
        }

        /**
         * Sets a new root relational expression, as the translation process
         * backs its way further up the tree.
         *
         * @param root New root relational expression
         * @param leaf Whether the relational expression is a leaf, that is,
         * derived from an atomic relational expression such as a table name in
         * the from clause, or the projection on top of a select-subquery. In
         * particular, relational expressions derived from JOIN operators are
         * not leaves, but set expressions are.
         */
        public void setRoot(RelNode root, boolean leaf)
        {
            setRoot(new RelNode[] { root }, root);
            if (leaf) {
                leaves.add(root);
            }
            this.columnMonotonicities.clear();
        }

        private void setRoot(
            RelNode[] inputs,
            RelNode root)
        {
            this.inputs = inputs;
            this.root = root;
            this.systemFieldList.clear();
            this.systemFieldList.addAll(validator.getSystemFields());
        }

        public void setRexOverContext(
            final SqlWindow window,
            final RexNode [] partitionKeys,
            final RexNode [] orderKeys)
        {
            assert rexOverContext == null ;

            rexOverContext = new SqlRexOverContext(
                window, partitionKeys, orderKeys);
        }

        public void clearRexOverContext() {
            rexOverContext = null;
        }

        /**
         * Notifies this Blackboard that the root just set using {@link
         * #setRoot(RelNode, boolean)} was derived using dataset substitution.
         *
         * <p>The default implementation is not interested in such
         * notifications, and does nothing.
         *
         * @param datasetName Dataset name
         */
        public void setDataset(String datasetName)
        {
        }

        void setRoot(RelNode [] inputs)
        {
            setRoot(inputs, null);
        }

        /**
         * Returns an expression with which to reference a from-list item.
         *
         * @param name the alias of the from item
         *
         * @return a {@link RexFieldAccess} or {@link RexRangeRef}, or null if
         * not found
         */
        RexNode lookupExp(String name)
        {
            if (nameToNodeMap != null) {
                RexNode node = nameToNodeMap.get(name);
                if (node == null) {
                    throw Util.newInternal(
                        "Unknown identifier '" + name
                        + "' encountered while expanding expression"
                        + node);
                }
                return node;
            }
            int [] offsets = { -1 };
            final SqlValidatorScope [] ancestorScopes = { null };
            SqlValidatorNamespace foundNs =
                scope.resolve(name, ancestorScopes, offsets);
            if (foundNs == null) {
                return null;
            }

            // Found in current query's from list.  Find which from item.
            // We assume that the order of the from clause items has been
            // preserved.
            SqlValidatorScope ancestorScope = ancestorScopes[0];
            boolean isParent = ancestorScope != scope;
            if ((inputs != null) && !isParent) {
                int offset = offsets[0];
                final List<Pair<RelNode, Integer>> relOffsetList =
                    new ArrayList<Pair<RelNode, Integer>>();
                final int[] start = {0};
                flatten(
                    inputs, systemFieldList.size(), start, relOffsetList);
                Pair<RelNode, Integer> pair = relOffsetList.get(offset);
                return rexBuilder.makeRangeReference(
                    pair.left.getRowType(), pair.right, false);
            } else {
                // We're referencing a relational expression which has not been
                // converted yet. This occurs when from items are correlated,
                // e.g. "select from emp as emp join emp.getDepts() as dept".
                // Create a temporary expression.
                assert isParent;
                DeferredLookup lookup = new DeferredLookup(this, name);
                String correlName = createCorrel();
                mapCorrelToDeferred.put(correlName, lookup);
                final RelDataType rowType = foundNs.getRowType();
                return rexBuilder.makeCorrel(rowType, correlName);
            }
        }

        RelDataTypeField getRootField(RexInputRef inputRef)
        {
            int fieldOffset = inputRef.getIndex();
            for (RelNode input : inputs) {
                RelDataType rowType = input.getRowType();
                if (rowType == null) {
                    // TODO:  remove this once leastRestrictive
                    // is correctly implemented
                    return null;
                }
                if (fieldOffset < rowType.getFieldCount()) {
                    return rowType.getFields()[fieldOffset];
                }
                fieldOffset -= rowType.getFieldCount();
            }
            throw new AssertionError();
        }

        public void flatten(
            RelNode[] rels,
            int systemFieldCount,
            int[] start,
            List<Pair<RelNode, Integer>> relOffsetList)
        {
            for (RelNode rel : rels) {
                if (leaves.contains(rel)) {
                    relOffsetList.add(
                        Pair.of(rel, start[0]));
                    start[0] += rel.getRowType().getFieldCount();
                } else {
                    if (rel instanceof JoinRel
                        || rel instanceof AggregateRel)
                    {
                        start[0] += systemFieldCount;
                    }
                    flatten(
                        rel.getInputs(),
                        systemFieldCount,
                        start,
                        relOffsetList);
                }
            }
        }

        void registerSubquery(SqlNode node)
        {
            subqueryList.add(node);
        }

        RelNode [] retrieveCursors()
        {
            RelNode [] cursorArray =
                cursors.toArray(new RelNode[cursors.size()]);
            cursors.clear();
            return cursorArray;
        }

        // implement SqlRexContext
        public RexNode convertExpression(SqlNode expr)
        {
            // If we're in aggregation mode and this is an expression in the
            // GROUP BY clause, return a reference to the field.
            if (agg != null) {
                final SqlNode expandedGroupExpr = validator.expand(expr, scope);
                RexNode rex = agg.lookupGroupExpr(expandedGroupExpr);
                if (rex != null) {
                    return rex;
                }
                if (expr instanceof SqlCall) {
                    rex = agg.lookupAggregates((SqlCall) expr);
                    if (rex != null) {
                        return rex;
                    }
                }
            }

            // Allow the derived class chance to override the standard
            // behavior for special kinds of expressions.
            RexNode rex = convertExtendedExpression(expr, this);
            if (rex != null) {
                return rex;
            }

            boolean needTruthTest;

            // Sub-queries and OVER expressions are not like ordinary
            // expressions.
            final SqlKind kind = expr.getKind();
            switch (kind) {
            case CURSOR:
            case SELECT:
            case EXISTS:
            case SCALAR_QUERY:
                rex = mapSubqueryToExpr.get(expr);

                assert rex != null : "rex != null";

                if (kind == SqlKind.CURSOR) {
                    // cursor reference is pre-baked
                    return rex;
                }
                if (((kind == SqlKind.SCALAR_QUERY)
                        || (kind == SqlKind.EXISTS))
                    && isConvertedSubq(rex))
                {
                    // scalar subquery or EXISTS has been converted to a
                    // constant
                    return rex;
                }

                RexNode fieldAccess;
                needTruthTest = false;

                // The indicator column is the last field of the subquery.
                fieldAccess =
                    rexBuilder.makeFieldAccess(
                        rex,
                        rex.getType().getFieldCount() - 1);

                // The indicator column will be nullable if it comes from
                // the null-generating side of the join. For EXISTS, add an
                // "IS TRUE" check so that the result is "BOOLEAN NOT NULL".
                if (fieldAccess.getType().isNullable()) {
                    if (kind == SqlKind.EXISTS) {
                        needTruthTest = true;
                    }
                }

                if (needTruthTest) {
                    fieldAccess =
                        rexBuilder.makeCall(
                            SqlStdOperatorTable.isTrueOperator,
                            fieldAccess);
                }
                return fieldAccess;

            case IN:
                rex = mapSubqueryToExpr.get(expr);

                assert rex != null : "rex != null";

                RexNode rexNode;
                boolean isNotInFilter;
                if (rex instanceof RexRangeRef) {
                    // IN was converted to subquery.
                    isNotInFilter =
                        ((SqlInOperator) ((SqlCall) expr).getOperator())
                        .isNotIn();
                    needTruthTest = subqueryNeedsOuterJoin || isNotInFilter;
                } else {
                    // IN was converted to OR; nothing more needed.
                    return rex;
                }

                if (needTruthTest) {
                    assert (rex instanceof RexRangeRef);
                    rexNode =
                        rexBuilder.makeFieldAccess(
                            rex,
                            rex.getType().getFieldCount() - 1);
                    if (!isNotInFilter) {
                        rexNode =
                            rexBuilder.makeCall(
                                SqlStdOperatorTable.isTrueOperator,
                                rexNode);
                    } else {
                        rexNode =
                            rexBuilder.makeCall(
                                SqlStdOperatorTable.notOperator,
                                rexBuilder.makeCall(
                                    SqlStdOperatorTable.isTrueOperator,
                                    rexNode));

                        // then append the IS NOT NULL(leftKeysForIn)
                        // RexRangeRef contains the following fields:
                        // leftKeysForIn, rightKeysForIn(the original subquery
                        // select list), nullIndicator The first two lists
                        // contain the same number of fields
                        for (
                            int i = 0;
                            i < ((rex.getType().getFieldCount() - 1) / 2);
                            i++)
                        {
                            rexNode =
                                rexBuilder.makeCall(
                                    SqlStdOperatorTable.andOperator,
                                    rexNode,
                                    rexBuilder.makeCall(
                                        SqlStdOperatorTable.isNotNullOperator,
                                        rexBuilder.makeFieldAccess(rex, i)));
                        }
                    }
                } else {
                    rexNode = rexBuilder.makeLiteral(true);
                }
                return rexNode;

            case OVER:
                return convertOver(this, expr);

            default:
                // fall through
            }

            // Apply standard conversions.
            rex = expr.accept(this);
            Util.permAssert(rex != null, "conversion result not null");
            return rex;
        }

        /**
         * Determines whether a RexNode corresponds to a subquery that's been
         * converted to a constant.
         *
         * @param rex the expression to be examined
         *
         * @return true if the expression is a dynamic parameter, a literal, or
         * a literal that is being cast
         */
        private boolean isConvertedSubq(RexNode rex)
        {
            if ((rex instanceof RexLiteral)
                || (rex instanceof RexDynamicParam))
            {
                return true;
            }
            if (rex instanceof RexCall) {
                RexCall call = (RexCall) rex;
                if (call.getOperator() == SqlStdOperatorTable.castFunc) {
                    RexNode operand = (RexNode) call.getOperands()[0];
                    if (operand instanceof RexLiteral) {
                        return true;
                    }
                }
            }
            return false;
        }

        // implement SqlRexContext
        public RexBuilder getRexBuilder()
        {
            return rexBuilder;
        }

        // implement SqlRexContext
        public RexRangeRef getSubqueryExpr(SqlCall call)
        {
            return (RexRangeRef) mapSubqueryToExpr.get(call);
        }

        // implement SqlRexContext
        public RelDataTypeFactory getTypeFactory()
        {
            return typeFactory;
        }

        // implement SqlRexContext
        public DefaultValueFactory getDefaultValueFactory()
        {
            return defaultValueFactory;
        }

        // implement SqlRexContext
        public SqlValidator getValidator()
        {
            return validator;
        }

        // implement SqlRexContext
        public RexNode convertLiteral(SqlLiteral literal)
        {
            return exprConverter.convertLiteral(this, literal);
        }

        public RexNode convertInterval(SqlIntervalQualifier intervalQualifier)
        {
            return exprConverter.convertInterval(this, intervalQualifier);
        }

        // implement SqlVisitor
        public RexNode visit(SqlLiteral literal)
        {
            return exprConverter.convertLiteral(this, literal);
        }

        // implement SqlVisitor
        public RexNode visit(SqlCall call)
        {
            if (agg != null) {
                final SqlOperator op = call.getOperator();
                if (op.isAggregator()) {
                    Util.permAssert(
                        agg != null,
                        "aggregate fun must occur in aggregation mode");
                    return agg.lookupAggregates(call);
                }
            }
            return exprConverter.convertCall(this, call);
        }

        // implement SqlVisitor
        public RexNode visit(SqlNodeList nodeList)
        {
            throw new UnsupportedOperationException();
        }

        // implement SqlVisitor
        public RexNode visit(SqlIdentifier id)
        {
            return convertIdentifier(this, id);
        }

        // implement SqlVisitor
        public RexNode visit(SqlDataTypeSpec type)
        {
            throw new UnsupportedOperationException();
        }

        // implement SqlVisitor
        public RexNode visit(SqlDynamicParam param)
        {
            return convertDynamicParam(param);
        }

        // implement SqlVisitor
        public RexNode visit(SqlIntervalQualifier intervalQualifier)
        {
            return convertInterval(intervalQualifier);
        }

        public Map<Integer, SqlMonotonicity> getColumnMonotonicities()
        {
            return columnMonotonicities;
        }

        public SqlRexOverContext getRexOverContext()
        {
            return rexOverContext;
        }
    }

    private static class DeferredLookup
    {
        Blackboard bb;
        String originalRelName;

        DeferredLookup(
            Blackboard bb,
            String originalRelName)
        {
            this.bb = bb;
            this.originalRelName = originalRelName;
        }

        public RexFieldAccess getFieldAccess(String name)
        {
            return (RexFieldAccess) bb.mapCorrelateVariableToRexNode.get(name);
        }

        public String getOriginalRelName()
        {
            return originalRelName;
        }
    }

    /**
     * An implementation of DefaultValueFactory which always supplies NULL.
     */
    class NullDefaultValueFactory
        implements DefaultValueFactory
    {
        public boolean isGeneratedAlways(
            RelOptTable table,
            int iColumn)
        {
            return false;
        }

        public RexNode newColumnDefaultValue(
            RelOptTable table,
            int iColumn)
        {
            return rexBuilder.constantNull();
        }

        public RexNode newAttributeInitializer(
            RelDataType type,
            SqlFunction constructor,
            int iAttribute,
            RexNode [] constructorArgs)
        {
            return rexBuilder.constantNull();
        }
    }

    /**
     * A default implementation of SubqueryConverter that does no conversion.
     */
    private class NoOpSubqueryConverter
        implements SubqueryConverter
    {
        // implement SubqueryConverter
        public boolean canConvertSubquery()
        {
            return false;
        }

        // implement SubqueryConverter
        public RexNode convertSubquery(
            SqlCall subquery,
            SqlToRelConverter parentConverter,
            boolean isExists,
            boolean isExplain)
        {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Converts expressions to aggregates.
     *
     * <p>Consider the expression SELECT deptno, SUM(2 * sal) FROM emp GROUP BY
     * deptno Then
     *
     * <ul>
     * <li>groupExprs = {SqlIdentifier(deptno)}</li>
     * <li>convertedInputExprs = {RexInputRef(deptno), 2 *
     * RefInputRef(sal)}</li>
     * <li>inputRefs = {RefInputRef(#0), RexInputRef(#1)}</li>
     * <li>aggCalls = {AggCall(SUM, {1})}</li>
     * </ul>
     */
    protected class AggConverter
        implements SqlVisitor<Void>
    {
        private final Blackboard bb;

        private final Map<String, String> nameMap =
            new HashMap<String, String>();

        /**
         * The group-by expressions, in {@link SqlNode} format.
         */
        private final SqlNodeList groupExprs =
            new SqlNodeList(SqlParserPos.ZERO);

        /**
         * Input expressions for the group columns and aggregates, in {@link
         * RexNode} format. The first elements of the list correspond to the
         * elements in {@link #groupExprs}; the remaining elements are for
         * aggregates.
         */
        private final List<RexNode> convertedInputExprs =
            new ArrayList<RexNode>();

        /**
         * Names of {@link #convertedInputExprs}, where the expressions are
         * simple mappings to input fields.
         */
        private final List<String> convertedInputExprNames =
            new ArrayList<String>();

        private final List<RexInputRef> inputRefs =
            new ArrayList<RexInputRef>();
        private final List<AggregateCall> aggCalls =
            new ArrayList<AggregateCall>();
        private final Map<SqlNode, RexNode> aggMapping =
            new HashMap<SqlNode, RexNode>();
        private final Map<AggregateCall, RexNode> aggCallMapping =
            new HashMap<AggregateCall, RexNode>();

        /**
         * Creates an AggConverter.
         *
         * <p>The <code>select</code> parameter provides enough context to name
         * aggregate calls which are top-level select list items.
         *
         * @param bb Blackboard
         * @param select Query being translated; provides context to give
         */
        public AggConverter(Blackboard bb, SqlSelect select)
        {
            this.bb = bb;

            // Collect all expressions used in the select list so that aggregate
            // calls can be named correctly.
            final SqlNodeList selectList = select.getSelectList();
            for (int i = 0; i < selectList.size(); i++) {
                SqlNode selectItem = selectList.get(i);
                String name = null;
                if (SqlUtil.isCallTo(
                        selectItem,
                        SqlStdOperatorTable.asOperator))
                {
                    final SqlNode [] operands =
                        ((SqlCall) selectItem).getOperands();
                    selectItem = operands[0];
                    name = operands[1].toString();
                }
                if (name == null) {
                    name = validator.deriveAlias(selectItem, i);
                }
                nameMap.put(selectItem.toString(), name);
            }

            // Add input expressions for system fields.
            int i = 0;
            for (RelDataTypeField field : validator.getSystemFields()) {
                final RexInputRef inputRef =
                    rexBuilder.makeInputRef(field.getType(), i++);
                addExpr(inputRef, field.getName());
                inputRefs.add(inputRef);
            }
        }

        private int computeOffset()
        {
            return bb.getValidator().getSystemFields().size()
                   * (1 + bb.subqueryList.size());
        }

        public void addGroupExpr(SqlNode expr)
        {
            RexNode convExpr = bb.convertExpression(expr);
            final RexNode rex = lookupGroupExpr(expr);
            if (rex != null) {
                return; // don't add duplicates, in e.g. "GROUP BY x, y, x"
            }
            groupExprs.add(expr);
            String name = nameMap.get(expr.toString());
            addExpr(convExpr, name);
            final RelDataType type = convExpr.getType();
            inputRefs.add(rexBuilder.makeInputRef(type, inputRefs.size()));
        }

        /**
         * Adds an expression, deducing an appropriate name if possible.
         *
         * @param expr Expression
         * @param name Suggested name
         */
        private void addExpr(RexNode expr, String name)
        {
            convertedInputExprs.add(expr);
            if ((name == null) && (expr instanceof RexInputRef)) {
                final int i = ((RexInputRef) expr).getIndex();
                name = bb.root.getRowType().getFields()[i].getName();
            }
            if (convertedInputExprNames.contains(name)) {
                // In case like 'SELECT ... GROUP BY x, y, x', don't add
                // name 'x' twice.
                name = null;
            }
            convertedInputExprNames.add(name);
        }

        // implement SqlVisitor
        public Void visit(SqlIdentifier id)
        {
            return null;
        }

        // implement SqlVisitor
        public Void visit(SqlNodeList nodeList)
        {
            for (int i = 0; i < nodeList.size(); i++) {
                nodeList.get(i).accept(this);
            }
            return null;
        }

        // implement SqlVisitor
        public Void visit(SqlLiteral lit)
        {
            return null;
        }

        // implement SqlVisitor
        public Void visit(SqlDataTypeSpec type)
        {
            return null;
        }

        // implement SqlVisitor
        public Void visit(SqlDynamicParam param)
        {
            return null;
        }

        // implement SqlVisitor
        public Void visit(SqlIntervalQualifier intervalQualifier)
        {
            return null;
        }

        public Void visit(SqlCall call)
        {
            if (call.getOperator().isAggregator()) {
                assert bb.agg == this;
                List<Integer> args = new ArrayList<Integer>();
                try {
                    // switch out of agg mode
                    bb.agg = null;
                    for (int i = 0; i < call.operands.length; i++) {
                        SqlNode operand = call.operands[i];
                        RexNode convertedExpr = null;

                        // special case for COUNT(*):  delete the *
                        if (operand instanceof SqlIdentifier) {
                            SqlIdentifier id = (SqlIdentifier) operand;
                            if (id.isStar()) {
                                assert (call.operands.length == 1);
                                assert args.isEmpty();
                                break;
                            }
                        }
                        convertedExpr = bb.convertExpression(operand);
                        assert convertedExpr != null;
                        args.add(lookupOrCreateGroupExpr(convertedExpr));
                    }
                } finally {
                    // switch back into agg mode
                    bb.agg = this;
                }

                final Aggregation aggregation =
                    (Aggregation) call.getOperator();
                RelDataType type = validator.deriveType(bb.scope, call);
                boolean distinct = false;
                SqlLiteral quantifier = call.getFunctionQuantifier();
                if ((null != quantifier)
                    && (quantifier.getValue() == SqlSelectKeyword.Distinct))
                {
                    distinct = true;
                }
                final AggregateCall aggCall =
                    new AggregateCall(
                        aggregation,
                        distinct,
                        args,
                        type,
                        nameMap.get(call.toString()));
                RexNode rex =
                    rexBuilder.addAggCall(
                        aggCall,
                        computeOffset() + groupExprs.size(), aggCalls,
                        aggCallMapping);
                aggMapping.put(call, rex);
            } else if (call instanceof SqlSelect) {
                // rchen 2006-10-17:
                // for now do not detect aggregates in subqueries.
                return null;
            } else {
                for (SqlNode operand : call.operands) {
                    operand.accept(this);
                }
            }
            return null;
        }

        private int lookupOrCreateGroupExpr(RexNode expr)
        {
            for (int i = 0; i < convertedInputExprs.size(); i++) {
                RexNode convertedInputExpr = convertedInputExprs.get(i);
                if (expr.toString().equals(convertedInputExpr.toString())) {
                    return i;
                }
            }

            // not found -- add it
            int index = convertedInputExprs.size();
            addExpr(expr, null);
            return index;
        }

        /**
         * If an expression is structurally identical to one of the group-by
         * expressions, returns a reference to the expression, otherwise returns
         * null.
         */
        public RexNode lookupGroupExpr(SqlNode expr)
        {
            for (int i = 0; i < groupExprs.size(); i++) {
                SqlNode groupExpr = groupExprs.get(i);
                if (expr.equalsDeep(groupExpr, false)) {
                    return inputRefs.get(sysFieldCount + i);
                }
            }
            return null;
        }

        public RexNode lookupAggregates(SqlCall call)
        {
            // assert call.getOperator().isAggregator();
            assert bb.agg == this;

            return aggMapping.get(call);
        }

        public List<RexNode> getPreExprs()
        {
            return convertedInputExprs;
        }

        public List<String> getPreNames()
        {
            return convertedInputExprNames;
        }

        public List<AggregateCall> getAggCalls()
        {
            return aggCalls;
        }

        public RelDataTypeFactory getTypeFactory()
        {
            return typeFactory;
        }
    }

    public enum QueryContext {
        TOP,
        SUBQUERY,
        VIEW,
        CURSOR,
        OTHER
    }
}
// End SqlToRelConverter.java
