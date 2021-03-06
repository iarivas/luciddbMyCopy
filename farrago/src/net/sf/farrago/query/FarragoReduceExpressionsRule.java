/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2006 The Eigenbase Project
// Copyright (C) 2006 SQLstream, Inc.
// Copyright (C) 2006 Dynamo BI Corporation
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
package net.sf.farrago.query;

import java.util.*;
import java.util.regex.*;

import net.sf.farrago.session.*;

import org.eigenbase.rel.*;
import org.eigenbase.relopt.*;
import org.eigenbase.reltype.*;
import org.eigenbase.rex.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.fun.*;
import org.eigenbase.util.*;


/**
 * Collection of planner rules that apply various simplifying transformations on
 * RexNode trees. Currently, there are two transformations:
 *
 * <ul>
 * <li>Constant reduction, which evaluates constant subtrees, replacing them
 * with a corresponding RexLiteral
 * <li>Removal of redundant casts, which occurs when the argument into the cast
 * is the same as the type of the resulting cast expression
 * </ul>
 *
 * @author John V. Sichi
 * @version $Id$
 */
public abstract class FarragoReduceExpressionsRule
    extends RelOptRule
{
    //~ Static fields/initializers ---------------------------------------------

    /**
     * Regular expression which matches the description of all instances of this
     * rule and {@link net.sf.farrago.query.FarragoReduceValuesRule} also. Use
     * it to prevent the planner from invoking these rules.
     */
    public static final Pattern EXCLUSION_PATTERN =
        Pattern.compile("FarragoReduce(Expressions|Values)Rule.*");

    /**
     * Singleton rule which reduces constants inside a {@link FilterRel}. If the
     * condition is a constant, the filter is removed (if TRUE) or replaced with
     * {@link EmptyRel} (if FALSE or NULL).
     */
    public static final FarragoReduceExpressionsRule filterInstance =
        new FarragoReduceExpressionsRule(FilterRel.class) {
            public void onMatch(RelOptRuleCall call)
            {
                FilterRel filter = (FilterRel) call.rels[0];
                List<RexNode> expList =
                    new ArrayList<RexNode>(
                        Arrays.asList(filter.getChildExps()));
                RexNode newConditionExp;
                boolean reduced;
                if (reduceExpressions(filter, expList)) {
                    assert (expList.size() == 1);
                    newConditionExp = expList.get(0);
                    reduced = true;
                } else {
                    // No reduction, but let's still test the original
                    // predicate to see if it was already a constant,
                    // in which case we don't need any runtime decision
                    // about filtering.
                    newConditionExp = filter.getChildExps()[0];
                    reduced = false;
                }
                if (newConditionExp.isAlwaysTrue()) {
                    call.transformTo(
                        filter.getChild());
                } else if (
                    (newConditionExp instanceof RexLiteral)
                    || RexUtil.isNullLiteral(newConditionExp, true))
                {
                    call.transformTo(
                        new EmptyRel(
                            filter.getCluster(),
                            filter.getRowType()));
                } else if (reduced) {
                    call.transformTo(
                        CalcRel.createFilter(
                            filter.getChild(),
                            expList.get(0)));
                } else {
                    if (newConditionExp instanceof RexCall) {
                        RexCall rexCall = (RexCall) newConditionExp;
                        boolean reverse =
                            (rexCall.getOperator()
                             == SqlStdOperatorTable.notOperator);
                        if (reverse) {
                            rexCall = (RexCall) rexCall.getOperands()[0];
                        }
                        reduceNotNullableFilter(
                            call,
                            filter,
                            rexCall,
                            reverse);
                    }
                    return;
                }

                // New plan is absolutely better than old plan.
                call.getPlanner().setImportance(filter, 0.0);
            }

            private void reduceNotNullableFilter(
                RelOptRuleCall call,
                FilterRel filter,
                RexCall rexCall,
                boolean reverse)
            {
                // If the expression is a IS [NOT] NULL on a non-nullable
                // column, then we can either remove the filter or replace
                // it with an EmptyRel.
                SqlOperator op = rexCall.getOperator();
                boolean alwaysTrue;
                if (op == SqlStdOperatorTable.isNullOperator
                    || op == SqlStdOperatorTable.isUnknownOperator)
                {
                    alwaysTrue = false;
                } else if (op == SqlStdOperatorTable.isNotNullOperator) {
                    alwaysTrue = true;
                } else {
                    return;
                }
                if (reverse) {
                    alwaysTrue = !alwaysTrue;
                }
                RexNode operand = rexCall.getOperands()[0];
                if (operand instanceof RexInputRef) {
                    RexInputRef inputRef = (RexInputRef) operand;
                    if (!inputRef.getType().isNullable()) {
                        if (alwaysTrue) {
                            call.transformTo(filter.getChild());
                        } else {
                            call.transformTo(
                                new EmptyRel(
                                    filter.getCluster(),
                                    filter.getRowType()));
                        }
                    }
                }
            }
        };

    public static final FarragoReduceExpressionsRule projectInstance =
        new FarragoReduceExpressionsRule(ProjectRel.class) {
            public void onMatch(RelOptRuleCall call)
            {
                ProjectRel project = (ProjectRel) call.rels[0];
                List<RexNode> expList =
                    new ArrayList<RexNode>(
                        Arrays.asList(project.getChildExps()));
                if (reduceExpressions(project, expList)) {
                    call.transformTo(
                        new ProjectRel(
                            project.getCluster(),
                            project.getChild(),
                            expList.toArray(new RexNode[expList.size()]),
                            project.getRowType(),
                            ProjectRel.Flags.Boxed,
                            Collections.<RelCollation>emptyList()));

                    // New plan is absolutely better than old plan.
                    call.getPlanner().setImportance(project, 0.0);
                }
            }
        };

    public static final FarragoReduceExpressionsRule joinInstance =
        new FarragoReduceExpressionsRule(JoinRel.class) {
            public void onMatch(RelOptRuleCall call)
            {
                JoinRel join = (JoinRel) call.rels[0];
                List<RexNode> expList =
                    new ArrayList<RexNode>(
                        Arrays.asList(join.getChildExps()));
                if (reduceExpressions(join, expList)) {
                    call.transformTo(
                        new JoinRel(
                            join.getCluster(),
                            join.getLeft(),
                            join.getRight(),
                            expList.get(0),
                            join.getJoinType(),
                            join.getVariablesStopped()));

                    // New plan is absolutely better than old plan.
                    call.getPlanner().setImportance(join, 0.0);
                }
            }
        };

    public static final FarragoReduceExpressionsRule calcInstance =
        new FarragoReduceExpressionsRule(CalcRel.class) {
            public void onMatch(RelOptRuleCall call)
            {
                CalcRel calc = (CalcRel) call.getRels()[0];
                RexProgram program = calc.getProgram();
                final List<RexNode> exprList = program.getExprList();

                // Form a list of expressions with sub-expressions fully
                // expanded.
                final List<RexNode> expandedExprList =
                    new ArrayList<RexNode>(exprList.size());
                final RexShuttle shuttle =
                    new RexShuttle() {
                        public RexNode visitLocalRef(RexLocalRef localRef)
                        {
                            return expandedExprList.get(localRef.getIndex());
                        }
                    };
                for (RexNode expr : exprList) {
                    expandedExprList.add(expr.accept(shuttle));
                }
                if (reduceExpressions(calc, expandedExprList)) {
                    final RexProgramBuilder builder =
                        new RexProgramBuilder(
                            calc.getChild().getRowType(),
                            calc.getCluster().getRexBuilder());
                    List<RexLocalRef> list = new ArrayList<RexLocalRef>();
                    for (RexNode expr : expandedExprList) {
                        list.add(builder.registerInput(expr));
                    }
                    if (program.getCondition() != null) {
                        final int conditionIndex =
                            program.getCondition().getIndex();
                        final RexNode newConditionExp =
                            expandedExprList.get(conditionIndex);
                        if (newConditionExp.isAlwaysTrue()) {
                            // condition is always TRUE - drop it
                        } else if (
                            (newConditionExp instanceof RexLiteral)
                            || RexUtil.isNullLiteral(newConditionExp, true))
                        {
                            // condition is always NULL or FALSE - replace calc
                            // with empty
                            call.transformTo(
                                new EmptyRel(
                                    calc.getCluster(),
                                    calc.getRowType()));
                            return;
                        } else {
                            builder.addCondition(list.get(conditionIndex));
                        }
                    }
                    int k = 0;
                    for (RexLocalRef projectExpr : program.getProjectList()) {
                        final int index = projectExpr.getIndex();
                        builder.addProject(
                            list.get(index).getIndex(),
                            program.getOutputRowType().getFieldList().get(k++)
                                   .getName());
                    }
                    call.transformTo(
                        new CalcRel(
                            calc.getCluster(),
                            calc.getTraits(),
                            calc.getChild(),
                            calc.getRowType(),
                            builder.getProgram(),
                            calc.getCollationList()));

                    // New plan is absolutely better than old plan.
                    call.getPlanner().setImportance(calc, 0.0);
                }
            }
        };

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a new FarragoReduceExpressionsRule object.
     *
     * @param relClass class of rels to which this rule should apply
     */
    private FarragoReduceExpressionsRule(Class<? extends RelNode> relClass)
    {
        super(
            new RelOptRuleOperand(
                relClass,
                ANY),
            "FarragoReduceExpressionsRule:"
            + ReflectUtil.getUnqualifiedClassName(relClass));
    }

    //~ Methods ----------------------------------------------------------------

    /**
     * Reduces a list of expressions.
     *
     * @param rel Relational expression
     * @param expList List of expressions, modified in place
     *
     * @return whether reduction found something to change, and succeeded
     */
    static boolean reduceExpressions(
        RelNode rel,
        List<RexNode> expList)
    {
        RexBuilder rexBuilder = rel.getCluster().getRexBuilder();

        // Find reducible expressions.
        FarragoSessionPlanner planner =
            (FarragoSessionPlanner) rel.getCluster().getPlanner();
        FarragoSessionPreparingStmt preparingStmt = planner.getPreparingStmt();
        List<RexNode> constExps = new ArrayList<RexNode>();
        List<Boolean> addCasts = new ArrayList<Boolean>();
        List<RexNode> removableCasts = new ArrayList<RexNode>();
        findReducibleExps(
            preparingStmt,
            expList,
            constExps,
            addCasts,
            removableCasts);
        if (constExps.isEmpty() && removableCasts.isEmpty()) {
            return false;
        }

        // Remove redundant casts before reducing constant expressions.
        // If the argument to the redundant cast is a reducible constant,
        // reducing that argument to a constant first will result in not being
        // able to locate the original cast expression.
        if (!removableCasts.isEmpty()) {
            List<RexNode> reducedExprs = new ArrayList<RexNode>();
            List<Boolean> noCasts = new ArrayList<Boolean>();
            for (RexNode exp : removableCasts) {
                RexCall call = (RexCall) exp;
                reducedExprs.add(call.getOperands()[0]);
                noCasts.add(false);
            }
            RexReplacer replacer =
                new RexReplacer(
                    rexBuilder,
                    removableCasts,
                    reducedExprs,
                    noCasts);
            replacer.apply(expList);
        }

        if (constExps.isEmpty()) {
            return true;
        }

        // Compute the values they reduce to.
        List<RexNode> reducedValues = new ArrayList<RexNode>();
        ReentrantValuesStmt reentrantStmt =
            new ReentrantValuesStmt(
                preparingStmt.getRootStmtContext(),
                rexBuilder,
                constExps,
                reducedValues);
        FarragoSession session = getSession(rel);
        reentrantStmt.execute(session, true);
        if (reentrantStmt.failed) {
            return false;
        }

        // For ProjectRel, we have to be sure to preserve the result
        // types, so always cast regardless of the expression type.
        // For other RelNodes like FilterRel, in general, this isn't necessary,
        // and the presence of casts could hinder other rules such as sarg
        // analysis, which require bare literals.  But there are special cases,
        // like when the expression is a UDR argument, that need to be
        // handled as special cases.
        if (rel instanceof ProjectRel) {
            for (int i = 0; i < reducedValues.size(); i++) {
                addCasts.set(i, true);
            }
        }

        RexReplacer replacer =
            new RexReplacer(
                rexBuilder,
                constExps,
                reducedValues,
                addCasts);
        replacer.apply(expList);
        return true;
    }

    static FarragoSession getSession(RelNode rel)
    {
        FarragoSessionPlanner planner =
            (FarragoSessionPlanner) rel.getCluster().getPlanner();
        FarragoSessionPreparingStmt preparingStmt = planner.getPreparingStmt();
        return preparingStmt.getSession();
    }

    /**
     * Locates expressions that can be reduced to literals or converted to
     * expressions with redundant casts removed.
     *
     * @param preparingStmt the statement containing the expressions
     * @param exps list of candidate expressions to be examined for reduction
     * @param constExps returns the list of expressions that can be constant
     * reduced
     * @param addCasts indicator for each expression that can be constant
     * reduced, whether a cast of the resulting reduced expression is
     * potentially necessary
     * @param removableCasts returns the list of cast expressions where the cast
     * can be removed
     */
    private static void findReducibleExps(
        FarragoSessionPreparingStmt preparingStmt,
        List<RexNode> exps,
        List<RexNode> constExps,
        List<Boolean> addCasts,
        List<RexNode> removableCasts)
    {
        ReducibleExprLocator gardener =
            new ReducibleExprLocator(
                preparingStmt,
                constExps,
                addCasts,
                removableCasts);
        for (RexNode exp : exps) {
            gardener.analyze(exp);
        }
        assert (constExps.size() == addCasts.size());
    }

    //~ Inner Classes ----------------------------------------------------------

    /**
     * Replaces expressions with their reductions. Note that we only have to
     * look for RexCall, since nothing else is reducible in the first place.
     */
    private static class RexReplacer
        extends RexShuttle
    {
        private final RexBuilder rexBuilder;
        private final List<RexNode> reducibleExps;
        private final List<RexNode> reducedValues;
        private final List<Boolean> addCasts;

        RexReplacer(
            RexBuilder rexBuilder,
            List<RexNode> reducibleExps,
            List<RexNode> reducedValues,
            List<Boolean> addCasts)
        {
            this.rexBuilder = rexBuilder;
            this.reducibleExps = reducibleExps;
            this.reducedValues = reducedValues;
            this.addCasts = addCasts;
        }

        // override RexShuttle
        public RexNode visitCall(final RexCall call)
        {
            int i = reducibleExps.indexOf(call);
            if (i == -1) {
                return super.visitCall(call);
            }
            RexNode replacement = reducedValues.get(i);
            if (addCasts.get(i)
                && (replacement.getType() != call.getType()))
            {
                // Handle change from nullable to NOT NULL by claiming
                // that the result is still nullable, even though
                // we know it isn't.
                //
                // Also, we cannot reduce CAST('abc' AS VARCHAR(4)) to 'abc'.
                // If we make 'abc' of type VARCHAR(4), we may later encounter
                // the same expression in a ProjectRel's digest where it has
                // type VARCHAR(3), and that's wrong.
                replacement =
                    rexBuilder.makeCast(
                        call.getType(),
                        replacement);
            }
            return replacement;
        }
    }

    /**
     * Evaluates constant expressions via a reentrant query of the form "VALUES
     * (exp1, exp2, exp3, ...)".
     */
    static class ReentrantValuesStmt
        extends FarragoReentrantStmtExecutor
    {
        private final List<RexNode> exprs;

        ReentrantValuesStmt(
            FarragoSessionStmtContext rootStmtContext,
            RexBuilder rexBuilder,
            List<RexNode> exprs,
            List<RexNode> results)
        {
            super(rootStmtContext, rexBuilder, results);
            this.exprs = exprs;
        }

        protected void executeImpl()
            throws Exception
        {
            RelNode oneRowRel =
                new OneRowRel(
                    getPreparingStmt().getRelOptCluster());
            RelNode projectRel =
                CalcRel.createProject(
                    oneRowRel,
                    exprs,
                    null);
            executePlan(projectRel, exprs, false, true);
        }
    }

    /**
     * Helper class used to locate expressions that either can be reduced to
     * literals or contain redundant casts.
     */
    private static class ReducibleExprLocator
        extends RexVisitorImpl<Void>
    {
        enum Constancy
        {
            NON_CONSTANT, REDUCIBLE_CONSTANT, IRREDUCIBLE_CONSTANT
        }

        private final FarragoSessionPreparingStmt preparingStmt;

        private final List<Constancy> stack;

        private final List<RexNode> constExprs;

        private final List<Boolean> addCasts;

        private final List<RexNode> removableCasts;

        private final List<SqlOperator> parentCallTypeStack;

        ReducibleExprLocator(
            FarragoSessionPreparingStmt preparingStmt,
            List<RexNode> constExprs,
            List<Boolean> addCasts,
            List<RexNode> removableCasts)
        {
            // go deep
            super(true);
            this.preparingStmt = preparingStmt;
            this.constExprs = constExprs;
            this.addCasts = addCasts;
            this.removableCasts = removableCasts;
            this.stack = new ArrayList<Constancy>();
            this.parentCallTypeStack = new ArrayList<SqlOperator>();
        }

        public void analyze(RexNode exp)
        {
            assert (stack.isEmpty());

            exp.accept(this);

            // Deal with top of stack
            assert (stack.size() == 1);
            assert (parentCallTypeStack.isEmpty());
            Constancy rootConstancy = stack.get(0);
            if (rootConstancy == Constancy.REDUCIBLE_CONSTANT) {
                // The entire subtree was constant, so add it to the result.
                addResult(exp);
            }
            stack.clear();
        }

        private Void pushVariable()
        {
            stack.add(Constancy.NON_CONSTANT);
            return null;
        }

        private void addResult(RexNode exp)
        {
            // Cast of literal can't be reduced, so skip those (otherwise we'd
            // go into an infinite loop as we add them back).
            if (exp.getKind() == RexKind.Cast) {
                RexCall cast = (RexCall) exp;
                RexNode operand = cast.getOperands()[0];
                if (operand instanceof RexLiteral) {
                    return;
                }
            }
            constExprs.add(exp);

            // In the case where the expression corresponds to a UDR argument,
            // we need to preserve casts.  Note that this only applies to
            // the topmost argument, not expressions nested within the UDR
            // call.
            //
            // REVIEW zfong 6/13/08 - Are there other expressions where we
            // also need to preserve casts?
            if (parentCallTypeStack.isEmpty()) {
                addCasts.add(false);
            } else {
                addCasts.add(
                    parentCallTypeStack.get(parentCallTypeStack.size() - 1)
                    instanceof FarragoUserDefinedRoutine);
            }
        }

        public Void visitInputRef(RexInputRef inputRef)
        {
            return pushVariable();
        }

        public Void visitLiteral(RexLiteral literal)
        {
            stack.add(Constancy.IRREDUCIBLE_CONSTANT);
            return null;
        }

        public Void visitOver(RexOver over)
        {
            // assume non-constant (running SUM(1) looks constant but isn't)
            analyzeCall(over, Constancy.NON_CONSTANT);
            return null;
        }

        public Void visitCorrelVariable(RexCorrelVariable correlVariable)
        {
            return pushVariable();
        }

        public Void visitCall(RexCall call)
        {
            // assume REDUCIBLE_CONSTANT until proven otherwise
            analyzeCall(call, Constancy.REDUCIBLE_CONSTANT);
            return null;
        }

        private void analyzeCall(RexCall call, Constancy callConstancy)
        {
            parentCallTypeStack.add(call.getOperator());

            // visit operands, pushing their states onto stack
            super.visitCall(call);

            // look for NON_CONSTANT operands
            int nOperands = call.getOperands().length;
            List<Constancy> operandStack =
                stack.subList(
                    stack.size() - nOperands,
                    stack.size());
            for (Constancy operandConstancy : operandStack) {
                if (operandConstancy == Constancy.NON_CONSTANT) {
                    callConstancy = Constancy.NON_CONSTANT;
                }
            }

            // Even if all operands are constant, the call itself may
            // be non-deterministic.
            if (!call.getOperator().isDeterministic()) {
                callConstancy = Constancy.NON_CONSTANT;
            } else if (call.getOperator().isDynamicFunction()) {
                // We can reduce the call to a constant, but we can't
                // cache the plan if the function is dynamic
                preparingStmt.disableStatementCaching();
            }

            // Row operator itself can't be reduced to a literal, but if
            // the operands are constants, we still want to reduce those
            if ((callConstancy == Constancy.REDUCIBLE_CONSTANT)
                && (call.getOperator() instanceof SqlRowOperator))
            {
                callConstancy = Constancy.NON_CONSTANT;
            }

            if (callConstancy == Constancy.NON_CONSTANT) {
                // any REDUCIBLE_CONSTANT children are now known to be maximal
                // reducible subtrees, so they can be added to the result
                // list
                for (int iOperand = 0; iOperand < nOperands; ++iOperand) {
                    Constancy constancy = operandStack.get(iOperand);
                    if (constancy == Constancy.REDUCIBLE_CONSTANT) {
                        addResult(call.getOperands()[iOperand]);
                    }
                }

                // if this cast expression can't be reduced to a literal,
                // then see if we can remove the cast
                if (call.getOperator() == SqlStdOperatorTable.castFunc) {
                    reduceCasts(call);
                }
            }

            // pop operands off of the stack
            operandStack.clear();

            // pop this parent call operator off the stack
            parentCallTypeStack.remove(parentCallTypeStack.size() - 1);

            // push constancy result for this call onto stack
            stack.add(callConstancy);
        }

        private void reduceCasts(RexCall outerCast)
        {
            RexNode [] operands = outerCast.getOperands();
            if (operands.length != 1) {
                return;
            }
            RelDataType outerCastType = outerCast.getType();
            RelDataType operandType = operands[0].getType();
            if (operandType.equals(outerCastType)) {
                removableCasts.add(outerCast);
                return;
            }

            // See if the reduction
            // CAST((CAST x AS type) AS type NOT NULL)
            // -> CAST(x AS type NOT NULL)
            // applies.  TODO jvs 15-Dec-2008:  consider
            // similar cases for precision changes.
            if (!(operands[0] instanceof RexCall)) {
                return;
            }
            RexCall innerCast = (RexCall) operands[0];
            if (innerCast.getOperator() != SqlStdOperatorTable.castFunc) {
                return;
            }
            if (innerCast.getOperands().length != 1) {
                return;
            }
            RelDataTypeFactory typeFactory =
                preparingStmt.getFarragoTypeFactory();
            RelDataType outerTypeNullable =
                typeFactory.createTypeWithNullability(
                    outerCastType,
                    true);
            RelDataType innerTypeNullable =
                typeFactory.createTypeWithNullability(
                    operandType,
                    true);
            if (outerTypeNullable != innerTypeNullable) {
                return;
            }
            if (operandType.isNullable()) {
                removableCasts.add(innerCast);
            }
        }

        public Void visitDynamicParam(RexDynamicParam dynamicParam)
        {
            return pushVariable();
        }

        public Void visitRangeRef(RexRangeRef rangeRef)
        {
            return pushVariable();
        }

        public Void visitFieldAccess(RexFieldAccess fieldAccess)
        {
            return pushVariable();
        }
    }
}

// End FarragoReduceExpressionsRule.java
