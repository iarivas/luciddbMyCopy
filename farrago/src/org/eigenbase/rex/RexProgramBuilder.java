/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2005-2006 The Eigenbase Project
// Copyright (C) 2002-2006 Disruptive Tech
// Copyright (C) 2005-2006 LucidEra, Inc.
// Portions Copyright (C) 2003-2006 John V. Sichi
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
package org.eigenbase.rex;

import org.eigenbase.relopt.RelOptUtil;
import org.eigenbase.reltype.RelDataType;
import org.eigenbase.reltype.RelDataTypeFactory;
import org.eigenbase.reltype.RelDataTypeField;
import org.eigenbase.sql.fun.SqlStdOperatorTable;
import org.eigenbase.util.Util;

import java.util.*;

/**
 * Workspace for constructing a {@link RexProgram}.
 *
 * <p>RexProgramBuilder is necessary because a {@link RexProgram} is immutable.
 * (The {@link String} class has the same problem: it is immutable, so they
 * introduced {@link StringBuffer}.)
 *
 * @see RexProgramBuilder
 * @author jhyde
 * @since Aug 18, 2005
 * @version $Id$
 */
public class RexProgramBuilder
{
    private final RexBuilder rexBuilder;
    private final RelDataType inputRowType;
    private final List<RexNode> exprList = new ArrayList<RexNode>();
    private final Map<String, RexLocalRef> exprMap = new HashMap<String, RexLocalRef>();
    private final List<RexLocalRef> localRefList = new ArrayList<RexLocalRef>();
    private final List<RexLocalRef> projectRefList = new ArrayList<RexLocalRef>();
    private final List<String> projectNameList = new ArrayList<String>();
    private RexLocalRef conditionRef = null;
    private boolean validating;

    /**
     * Creates a program-builder.
     */
    public RexProgramBuilder(RelDataType inputRowType, RexBuilder rexBuilder)
    {
        assert inputRowType != null;
        assert rexBuilder != null;
        this.inputRowType = inputRowType;
        this.rexBuilder = rexBuilder;
        this.validating = assertionsAreEnabled();
        // Pre-create an expression for each input field.
        if (inputRowType.isStruct()) {
            final RelDataTypeField[] fields = inputRowType.getFields();
            for (int i = 0; i < fields.length; i++) {
                RelDataTypeField field = fields[i];
                registerInternal(new RexInputRef(i, field.getType()));
            }
        }
    }

    /**
     * Returns whether assertions are enabled in this class.
     */
    private static boolean assertionsAreEnabled()
    {
        boolean assertionsEnabled = false;
        assert (assertionsEnabled = true) == true;
        return assertionsEnabled;
    }

    private void validate(final RexNode expr, final int fieldOrdinal)
    {
        final RexVisitor validator = new RexVisitorImpl(true) {
            public void visitInputRef(RexInputRef input)
            {
                final int index = input.getIndex();
                final RelDataTypeField[] fields = inputRowType.getFields();
                if (index < fields.length) {
                    final RelDataTypeField inputField = fields[index];
                    if (input.getType() != inputField.getType()) {
                        throw Util.newInternal("in expression " + expr +
                            ", field reference " + input +
                            " has inconsistent type");
                    }
                } else {
                    if (index >= fieldOrdinal) {
                        throw Util.newInternal("in expression " + expr +
                            ", field reference " + input +
                            " is out of bounds");
                    }
                    RexNode refExpr = exprList.get(index);
                    if (refExpr.getType() != input.getType()) {
                        throw Util.newInternal("in expression " + expr +
                            ", field reference " + input +
                            " has inconsistent type");
                    }
                }
            }
        };
        expr.accept(validator);
    }

    /**
     * Adds a project expression to the program.
     *
     * <p>The expression specified in terms of the input fields.
     * If not, call {@link #registerOutput(RexNode)} first.
     *
     * @param expr Expression to add
     * @param name Name of field in output row type; if null, a unique name
     *   will be generated when the program is created
     * @return the ref created
     */
    public RexLocalRef addProject(RexNode expr, String name)
    {
        final RexLocalRef ref = registerInput(expr);
        projectRefList.add(ref);
        projectNameList.add(name);
        return ref;
    }

    /**
     * Adds a projection based upon the <code>index</code>th expression.
     *
     * @param ordinal Index of expression to project
     * @param name
     * @return the ref created
     */
    public RexLocalRef addProject(int ordinal, final String name)
    {
        return addProject(localRefList.get(ordinal), name);
    }

    /**
     * Adds a project expression to the program at a given position.
     *
     * <p>The expression specified in terms of the input fields.
     * If not, call {@link #registerOutput(RexNode)} first.
     *
     * @param at Position in project list to add expression
     * @param expr Expression to add
     * @param name Name of field in output row type; if null, a unique name
     *   will be generated when the program is created
     * @return the ref created
     */
    public RexLocalRef addProject(int at, RexNode expr, String name)
    {
        final RexLocalRef ref = registerInput(expr);
        projectRefList.add(at, ref);
        projectNameList.add(at, name);
        return ref;
    }

    /**
     * Adds a projection based upon the <code>index</code>th expression
     * at a given position.
     *
     * @param at Position in project list to add expression
     * @param ordinal Index of expression to project
     * @param name
     * @return the ref created
     */
    public RexLocalRef addProject(int at, int ordinal, final String name)
    {
        return addProject(at, localRefList.get(ordinal), name);
    }

    /**
     * Sets the condition of the program.<p/>
     *
     * The expression must be specified in terms of the input fields.
     * If not, call {@link #registerOutput(RexNode)} first.
     */
    public void addCondition(RexNode expr)
    {
        assert expr != null;
        if (conditionRef == null) {
            conditionRef = registerInput(expr);
        } else {
            // AND the new condition with the existing condition.
            RexLocalRef ref = registerInput(expr);
            final RexLocalRef andRef = registerInput(
                rexBuilder.makeCall(
                    SqlStdOperatorTable.andOperator,
                    new RexNode[] {conditionRef, ref}));
            conditionRef = andRef;
        }
    }

    /**
     * Registers an expression in the list of common sub-expressions,
     * and returns a reference to that expression.<p/>
     *
     * The expression must be expressed in terms of the <em>inputs</em> of
     * this program.
     */
    public RexLocalRef registerInput(RexNode expr)
    {
        final RexShuttle shuttle = new RegisterInputShuttle(true);
        final RexNode ref = expr.accept(shuttle);
        return (RexLocalRef) ref;
    }

    /**
     * Converts an expression expressed in terms of the <em>outputs</em> of
     * this program into an expression expressed in terms of the
     * <em>inputs</em>, registers it in the list of common sub-expressions,
     * and returns a reference to that expression.
     *
     * @param expr Expression to register
     */
    public RexLocalRef registerOutput(RexNode expr)
    {
        final RexShuttle shuttle = new RegisterOutputShuttle(exprList);
        final RexNode ref = expr.accept(shuttle);
        return (RexLocalRef) ref;
    }

    /**
     * Registers an expression in the list of common sub-expressions,
     * and returns a reference to that expression.
     */
    private RexLocalRef registerInternal(RexNode expr)
    {
        String key = RexUtil.makeKey(expr);
        RexLocalRef ref = exprMap.get(key);
        if (ref != null) {
            return ref;
        }
        if (validating) {
            validate(expr, exprList.size());
        }
        final int index = exprList.size();
        exprList.add(expr);
        ref = new RexLocalRef(index, expr.getType());
        localRefList.add(ref);
        exprMap.put(key, ref);
        return ref;
    }


    /**
     * Converts the state of the program builder to an immutable program.
     *
     * <p>It is OK to call this method, modify the program specification (by
     * adding projections, and so forth), and call this method again.
     */
    public RexProgram getProgram()
    {
        assert projectRefList.size() == projectNameList.size();
        // Make sure all fields have a name.
        generateMissingNames();
        RelDataType outputRowType = rexBuilder.typeFactory.createStructType(
            new RelDataTypeFactory.FieldInfo()
            {
                public int getFieldCount()
                {
                    return projectRefList.size();
                }

                public String getFieldName(int index)
                {
                    return projectNameList.get(index);
                }

                public RelDataType getFieldType(int index)
                {
                    return projectRefList.get(index).getType();
                }
            }
        );
        // Clone expressions, so builder can modify them after they have
        // been put into the program. The projects and condition do not need
        // to be cloned, because RexLocalRef is immutable.
        List<RexNode> exprs = new ArrayList<RexNode>(exprList);
        for (int i = 0; i < exprList.size(); i++) {
            exprs.set(i, (RexNode) exprList.get(i).clone());
        }
        return new RexProgram(
            inputRowType,
            exprs,
            projectRefList,
            conditionRef,
            outputRowType);
    }

    private void generateMissingNames()
    {
        int i = -1, j = 0;
        for (String projectName : projectNameList) {
            ++i;
            if (projectName == null) {
                while (true) {
                    final String candidateName = "$" + j++;
                    if (!projectNameList.contains(candidateName)) {
                        projectNameList.set(i, candidateName);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Creates a program builder and initializes it from an existing
     * program.
     *
     * <p>Calling {@link #getProgram()} immediately after creation will
     * return a program equivalent (in terms of external behavior) to the
     * existing program.
     *
     * <p>The existing program will not be changed. (It cannot: programs are
     * immutable.)
     *
     * @param program Existing program
     * @param rexBuilder Rex builder
     * @return A program builder initialized with an equivalent program
     */
    public static RexProgramBuilder forProgram(
        RexProgram program,
        RexBuilder rexBuilder)
    {
        assert program.isValid(true);
        final RelDataType inputRowType = program.getInputRowType();
        final List<RexLocalRef> projectRefs = program.getProjectList();
        final RexLocalRef conditionRef = program.getCondition();
        final List<RexNode> exprs = program.getExprList();
        final RelDataType outputRowType = program.getOutputRowType();
        return create(
            rexBuilder, inputRowType, exprs, projectRefs, conditionRef,
            outputRowType);
    }

    /**
     * Creates a program builder with the same contents as a program.
     *
     * @param rexBuilder Rex builder
     * @param inputRowType Input row type
     * @param exprList Common expressions
     * @param projectRefList Projections
     * @param conditionRef Condition, or null
     * @param outputRowType Output row type
     * @return A program builder
     */
    public static RexProgramBuilder create(
        RexBuilder rexBuilder,
        final RelDataType inputRowType,
        final List<RexNode> exprList,
        final List<RexLocalRef> projectRefList,
        final RexLocalRef conditionRef,
        final RelDataType outputRowType)
    {
        final RelDataTypeField[] outFields = outputRowType.getFields();
        final RexProgramBuilder progBuilder =
            new RexProgramBuilder(inputRowType, rexBuilder);
        // First register the common expressions; projects and conditions may
        // depend upon these recursively.
        for (RexNode expr : exprList) {
            final RexLocalRef ref = progBuilder.registerInput(expr);
            Util.discard(ref);
        }
        // Register project expressions (they should be registered already)
        // and create a named project item.
        int i = 0;
        for (RexLocalRef projectRef : projectRefList) {
            final RexNode expr = exprList.get(projectRef.getIndex());
            final RexLocalRef ref = progBuilder.registerInput(expr);
            progBuilder.addProject(ref, outFields[i].getName());
            ++i;
        }
        // Register the condition, if there is one.
        if (conditionRef != null) {
            final RexNode expr = exprList.get(conditionRef.getIndex());
            final RexLocalRef ref = progBuilder.registerInput(expr);
            progBuilder.addCondition(ref);
        }
        return progBuilder;
    }

    /**
     * Creates a program builder with the same contents as a program,
     * applying a shuttle first.
     *
     * <p>TODO: Refactor the above create method in terms of this one.
     *
     * @param rexBuilder Rex builder
     * @param inputRowType Input row type
     * @param exprList Common expressions
     * @param projectRefList Projections
     * @param conditionRef Condition, or null
     * @param outputRowType Output row type
     * @param shuttle Shuttle to apply to each expression before adding it to
     *   the program builder
     * @return A program builder
     */
    public static RexProgramBuilder create(
        RexBuilder rexBuilder,
        final RelDataType inputRowType,
        final List<RexNode> exprList,
        final List<RexLocalRef> projectRefList,
        final RexLocalRef conditionRef,
        final RelDataType outputRowType,
        final RexShuttle shuttle)
    {
        final RexProgramBuilder progBuilder =
            new RexProgramBuilder(inputRowType, rexBuilder);
        progBuilder.add(exprList, projectRefList, conditionRef, outputRowType, shuttle);
        return progBuilder;
    }

    /**
     * Adds a set of expressions, projections and filters, applying a shuttle
     * first.
     *
     * @param exprList Common expressions
     * @param projectRefList Projections
     * @param conditionRef Condition, or null
     * @param outputRowType Output row type
     * @param shuttle Shuttle to apply to each expression before adding it to
     *   the program builder
     */
    private void add(
        List<RexNode> exprList,
        List<RexLocalRef> projectRefList,
        RexLocalRef conditionRef,
        final RelDataType outputRowType,
        RexShuttle shuttle)
    {
        final RelDataTypeField[] outFields = outputRowType.getFields();
        final RexShuttle registerInputShuttle = new RegisterInputShuttle(false);

        // For each common expression, first apply the user's shuttle, then
        // register the result.
        for (RexNode expr : exprList) {
            RexNode newExpr = expr = expr.accept(shuttle);
            RexNode ref = newExpr.accept(registerInputShuttle);
            Util.discard(ref);
        }
        int i = -1;
        for (RexLocalRef projectRef : projectRefList) {
            ++i;
            final RexLocalRef ref = (RexLocalRef) projectRef.accept(shuttle);
            this.projectRefList.add(ref);
            final String name = outFields[i].getName();
            assert name != null;
            projectNameList.add(name);
        }
        if (conditionRef != null) {
            conditionRef = (RexLocalRef) conditionRef.accept(shuttle);
        }
    }

    /**
     * Eliminate unused expressions.
     */
    public void eliminateUnused()
    {
        // Figure out which expressions are used.
        final int exprCount = exprList.size();
        final UsageVisitor usageVisitor = new UsageVisitor();
        for (int i = 0; i < projectRefList.size(); i++) {
              projectRefList.get(i).accept(usageVisitor);
        }
        if (conditionRef != null) {
            conditionRef.accept(usageVisitor);
        }
        // Are all fields still used?
        if (usageVisitor.unusedCount == 0) {
            return;
        }
        // There are some unused fields. Figure out which fields need to be
        // eliminated, and how much the other fields should shift down.
        final int newExprCount = exprCount - usageVisitor.unusedCount;
        final int[] targetOrdinals = new int[exprCount];
        final int[] sourceOrdinals = new int[newExprCount];
        int j = 0;
        for (int i = 0; i < exprCount; i++) {
            if (usageVisitor.usedExprs[i]) {
                targetOrdinals[i] = j;
                sourceOrdinals[j] = i;
                ++j;
            } else {
                targetOrdinals[i] = -1;
            }
        }
        assert j == newExprCount;
        // Relocate the fields.
        final RexShuttle shuttle = new RexShuttle() {
            public RexNode visitLocalRef(RexLocalRef localRef)
            {
                return new RexLocalRef(
                    targetOrdinals[localRef.index], localRef.getType());
            }
        };

        List<RexNode> oldExprList = new ArrayList<RexNode>(exprList);
        exprList.clear();
        for (int i = 0; i < newExprCount; i++) {
            final RexNode oldExpr =
                oldExprList.get(sourceOrdinals[i]);
            exprList.add(oldExpr.accept(shuttle));
        }
        for (int i = 0; i < projectRefList.size(); i++) {
            RexLocalRef ref = projectRefList.get(i);
            projectRefList.set(i, (RexLocalRef) ref.accept(shuttle));
        }
        if (conditionRef != null) {
            conditionRef = (RexLocalRef) conditionRef.accept(shuttle);
        }
    }

    /**
     * Merges two programs together.
     *
     * <p>All expressions become common sub-expressions. For example, the query
     *
     * <pre>{@code
     * SELECT x + 1 AS p, x + y AS q FROM (
     *   SELECT a + b AS x, c AS y
     *   FROM t
     *   WHERE c = 6)}</pre>
     *
     * would be represented as the programs
     *
     * <pre>
     *   Calc:
     *       Projects={$2, $3},
     *       Condition=null,
     *       Exprs={$0, $1, $0 + 1, $0 + $1})
     *   Calc(
     *       Projects={$3, $2},
     *       Condition={$4}
     *       Exprs={$0, $1, $2, $0 + $1, $2 = 6}
     * </pre>
     *
     * <p>The merged program is
     *
     * <pre>
     *   Calc(
     *      Projects={$4, $5}
     *      Condition=$6
     *      Exprs={0: $0       // a
     *             1: $1        // b
     *             2: $2        // c
     *             3: ($0 + $1) // x = a + b
     *             4: ($3 + 1)  // p = x + 1
     *             5: ($3 + $2) // q = x + y
     *             6: ($2 = 6)  // c = 6
     * </pre>
     *
     * <p>Another example:
     *
     * </blockquote><pre>SELECT *
     * FROM (
     *   SELECT a + b AS x, c AS y
     *   FROM t
     *   WHERE c = 6)
     * WHERE x = 5</pre></blockquote>
     *
     * becomes
     *
     * <blockquote><pre>SELECT a + b AS x, c AS y
     * FROM t
     * WHERE c = 6 AND (a + b) = 5</pre></blockquote>
     *
     * @param topProgram Top program. Its expressions are in terms of the
     *            outputs of the bottom program.
     * @param bottomProgram Bottom program. Its expressions are in terms of the
     *            result fields of the relational expression's input
     * @param rexBuilder
     * @return Merged program
     */
    public static RexProgram mergePrograms(
        RexProgram topProgram,
        RexProgram bottomProgram,
        RexBuilder rexBuilder)
    {
        // Initialize a program builder with the same expressions, outputs
        // and condition as the bottom program.
        assert bottomProgram.isValid(true);
        assert topProgram.isValid(true);
        final RexProgramBuilder progBuilder =
            RexProgramBuilder.forProgram(bottomProgram, rexBuilder);

        // Drive from the outputs of the top program. Register each expression
        // used as an output.
        final List<RexLocalRef> projectRefList =
            progBuilder.registerProjectsAndCondition(topProgram);

        // Switch to the projects needed by the top program. The original
        // projects of the bottom program are no longer needed.
        progBuilder.clearProjects();
        final RelDataType outputRowType = topProgram.getOutputRowType();
        final RelDataTypeField[] outputFields = outputRowType.getFields();
        assert outputFields.length == projectRefList.size();
        for (int i = 0; i < projectRefList.size(); i++) {
            RexLocalRef ref = projectRefList.get(i);
            progBuilder.addProject(ref, outputFields[i].getName());
        }
        return progBuilder.getProgram();
    }

    private List<RexLocalRef> registerProjectsAndCondition(RexProgram program)
    {
        final List<RexNode> exprList = program.getExprList();
        final List<RexLocalRef> projectRefList = new ArrayList<RexLocalRef>();
        final RexShuttle shuttle = new RegisterOutputShuttle(exprList);

        // For each project, lookup the expr and expand it so it is in terms of
        // bottomCalc's input fields
        for (RexLocalRef topProject : program.getProjectList()) {
            final RexNode topExpr = exprList.get(topProject.getIndex());
            final RexLocalRef expanded = (RexLocalRef) topExpr.accept(shuttle);
            // Remember the expr, but don't add to the project list yet.
            projectRefList.add(expanded);
        }
        // Similarly for the condition.
        final RexLocalRef topCondition = program.getCondition();
        if (topCondition != null) {
            final RexNode topExpr = exprList.get(topCondition.getIndex());
            final RexLocalRef expanded = (RexLocalRef) topExpr.accept(shuttle);
            addCondition(registerInput(expanded));
        }
        return projectRefList;
    }

    /**
     * Removes all project items.
     */
    public void clearProjects()
    {
        projectRefList.clear();
        projectNameList.clear();
    }

    /**
     * Adds a project item for every input field.
     *
     * <p>You cannot call this method if there are other project items.
     *
     * @pre projectRefList.isEmpty()
     */
    public void addIdentity()
    {
        assert projectRefList.isEmpty();
        final RelDataTypeField[] fields = inputRowType.getFields();
        for (int i = 0; i < fields.length; i++) {
            final RelDataTypeField field = fields[i];
            addProject(new RexInputRef(i, field.getType()), field.getName());
        }
    }

    /**
     * Creates a reference to a given input field
     *
     * @param index Ordinal of input field, must be less than the number of
     *   fields in the input type
     * @return Reference to input field
     */
    public RexLocalRef makeInputRef(int index)
    {
        final RelDataTypeField[] fields = inputRowType.getFields();
        assert index < fields.length;
        final RelDataTypeField field = fields[index];
        return new RexLocalRef(index, field.getType());
    }

    /**
     * Returns the rowtype of the input to the program
     */ 
    public RelDataType getInputRowType()
    {
        return inputRowType;
    }

    /**
     * Shuttle which walks over an expression, registering each sub-expression.
     * Each {@link RexInputRef} is assumed to refer to an <em>input</em> of the
     * program.
     */
    private class RegisterInputShuttle extends RexShuttle
    {
        private final boolean valid;

        public RegisterInputShuttle(boolean valid)
        {
            this.valid = valid;
        }

        public RexNode visitInputRef(RexInputRef input)
        {
            final int index = input.getIndex();
            if (valid) {
                // The expression should already be valid. Check that its
                // index is within bounds.
                assert index >= 0;
                assert index < inputRowType.getFieldList().size();
                // Check that the type is consistent with the referenced
                // field. If it is an object type, the rules are different, so
                // skip the check.
                assert input.getType().isStruct() ||
                    RelOptUtil.eq("type1", input.getType(),
                        "type2", inputRowType.getFields()[index].getType(),
                        true);
            }
            // Return a reference to the N'th expression, which should be
            // equivalent.
            final RexLocalRef ref = localRefList.get(index);
            return ref;
        }

        public RexNode visitLocalRef(RexLocalRef local)
        {
            if (valid) {
                // The expression should already be valid.
                final int index = local.getIndex();
                assert index >= 0;
                assert index < exprList.size();
                assert RelOptUtil.eq(
                    "expr type", exprList.get(index).getType(),
                    "ref type", local.getType(), true);
            }
            return local;
        }

        public RexNode visitCall(RexCall call)
        {
            final RexNode expr = super.visitCall(call);
            return registerInternal(expr);
        }

        public RexNode visitOver(RexOver over)
        {
            final RexNode expr = super.visitOver(over);
            return registerInternal(expr);
        }

        public RexNode visitLiteral(RexLiteral literal)
        {
            return registerInternal(literal);
        }

        public RexNode visitFieldAccess(RexFieldAccess fieldAccess)
        {
            final RexNode expr = super.visitFieldAccess(fieldAccess);
            return registerInternal(expr);
        }

        public RexNode visitDynamicParam(RexDynamicParam dynamicParam)
        {
            final RexNode expr = super.visitDynamicParam(dynamicParam);
            return registerInternal(expr);
        }

        public RexNode visitCorrelVariable(RexCorrelVariable variable)
        {
            final RexNode expr = super.visitCorrelVariable(variable);
            return registerInternal(expr);
        }
    }

    /**
     * Shuttle which walks over an expression, registering each sub-expression.
     * Each {@link RexInputRef} is assumed to refer to an <em>output</em> of
     * the program.
     */
    private class RegisterOutputShuttle extends RexShuttle
    {
        private final List<RexNode> localExprList;

        public RegisterOutputShuttle(List<RexNode> localExprList)
        {
            super();
            this.localExprList = localExprList;
        }

        public RexNode visitInputRef(RexInputRef input)
        {
            // This expression refers to the Nth project column. Lookup that
            // column and find out what common sub-expression IT refers to.
            final int index = input.getIndex();
            final RexLocalRef local = projectRefList.get(index);
            assert RelOptUtil.eq("type1", local.getType(), "type2", input.getType(), true);
            return local;
        }

        public RexNode visitLocalRef(RexLocalRef local)
        {
            // Convert a local ref into the common-subexpression it references.
            final int index = local.getIndex();
            return localExprList.get(index).accept(this);
        }

        public RexNode visitCall(RexCall call)
        {
            final RexNode expr = super.visitCall(call);
            return registerInternal(expr);
        }

        public RexNode visitOver(RexOver over)
        {
            final RexNode expr = super.visitOver(over);
            return registerInternal(expr);
        }

        public RexNode visitFieldAccess(RexFieldAccess fieldAccess)
        {
            final RexNode expr = super.visitFieldAccess(fieldAccess);
            return registerInternal(expr);
        }

        public RexNode visitLiteral(RexLiteral literal)
        {
            final RexNode expr = super.visitLiteral(literal);
            return registerInternal(expr);
        }

        public RexNode visitDynamicParam(RexDynamicParam dynamicParam)
        {
            final RexNode expr = super.visitDynamicParam(dynamicParam);
            return registerInternal(expr);
        }

        public RexNode visitCorrelVariable(RexCorrelVariable variable)
        {
            final RexNode expr = super.visitCorrelVariable(variable);
            return registerInternal(expr);
        }
    }

    /**
     * Visitor which marks which expressions are used.
     */
    private class UsageVisitor extends RexVisitorImpl
    {
        final boolean[] usedExprs;
        int unusedCount;

        public UsageVisitor()
        {
            super(true);
            usedExprs = new boolean[exprList.size()];
            final int inputFieldCount = inputRowType.getFields().length;
            for (int i = 0; i < inputFieldCount; i++) {
                usedExprs[i] = true;
            }
            unusedCount = exprList.size() - inputFieldCount;
        }

        public void visitLocalRef(RexLocalRef localRef)
        {
            final int index = localRef.getIndex();
            if (!usedExprs[index]) {
                usedExprs[index] = true;
                --unusedCount;
                // We have just discovered an indirect use of this
                // expression. Recurse, and we may find further indirect
                // uses.
                exprList.get(index).accept(this);
            }
        }
    }

}

// End RexProgramBuilder.java