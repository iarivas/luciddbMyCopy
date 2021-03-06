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
package org.eigenbase.sql;

import org.eigenbase.sql.parser.*;
import org.eigenbase.sql.validate.*;
import org.eigenbase.util.*;


/**
 * Parse tree node representing an UPDATE statement.
 */
public class SqlUpdate
    extends SqlDml
{
    //~ Static fields/initializers ---------------------------------------------

    // constants representing operand positions
    public static final int TARGET_TABLE_OPERAND = 0;
    public static final int SOURCE_EXPRESSION_LIST_OPERAND = 1;
    public static final int TARGET_COLUMN_LIST_OPERAND = 2;
    public static final int CONDITION_OPERAND = 3;
    public static final int SOURCE_SELECT_OPERAND = 4;
    public static final int ALIAS_OPERAND = 5;
    public static final int OPERAND_COUNT = 6;

    //~ Constructors -----------------------------------------------------------

    public SqlUpdate(
        SqlSpecialOperator operator,
        SqlIdentifier targetTable,
        SqlNodeList targetColumnList,
        SqlNodeList sourceExpressionList,
        SqlNode condition,
        SqlIdentifier alias,
        SqlParserPos pos)
    {
        super(
            operator,
            new SqlNode[OPERAND_COUNT],
            pos);
        operands[TARGET_TABLE_OPERAND] = targetTable;
        operands[SOURCE_EXPRESSION_LIST_OPERAND] = sourceExpressionList;
        operands[TARGET_COLUMN_LIST_OPERAND] = targetColumnList;
        operands[CONDITION_OPERAND] = condition;
        operands[ALIAS_OPERAND] = alias;
        assert (sourceExpressionList.size() == targetColumnList.size());
    }

    //~ Methods ----------------------------------------------------------------

    public SqlIdentifier getTargetTable()
    {
        return (SqlIdentifier) operands[TARGET_TABLE_OPERAND];
    }

    @Override
    public SqlIdentifier getAlias()
    {
        return (SqlIdentifier) operands[ALIAS_OPERAND];
    }

    public SqlNodeList getTargetColumnList()
    {
        return (SqlNodeList) operands[TARGET_COLUMN_LIST_OPERAND];
    }

    /**
     * @return the list of source expressions
     */
    public SqlNodeList getSourceExpressionList()
    {
        return (SqlNodeList) operands[SOURCE_EXPRESSION_LIST_OPERAND];
    }

    /**
     * Gets the filter condition for rows to be updated.
     *
     * @return the condition expression for the data to be updated, or null for
     * all rows in the table
     */
    public SqlNode getCondition()
    {
        return operands[CONDITION_OPERAND];
    }

    /**
     * {@inheritDoc}
     *
     * In an UPDATE statement, it is always a SELECT. Returns
     * null before the statement has been expanded by
     * {@link SqlValidatorImpl#performUnconditionalRewrites}.
     *
     * @return the source SELECT for the data to be updated
     */
    public SqlSelect getSource()
    {
        return (SqlSelect) operands[SOURCE_SELECT_OPERAND];
    }

    // implement SqlNode
    public void unparse(
        SqlWriter writer,
        int leftPrec,
        int rightPrec)
    {
        final SqlWriter.Frame frame =
            writer.startList(SqlWriter.FrameTypeEnum.Select, "UPDATE", "");
        getTargetTable().unparse(
            writer,
            getOperator().getLeftPrec(),
            getOperator().getRightPrec());
        if (getTargetColumnList() != null) {
            getTargetColumnList().unparse(
                writer,
                getOperator().getLeftPrec(),
                getOperator().getRightPrec());
        }
        if (getAlias() != null) {
            writer.keyword("AS");
            getAlias().unparse(
                writer,
                getOperator().getLeftPrec(),
                getOperator().getRightPrec());
        }
        final SqlWriter.Frame setFrame =
            writer.startList(SqlWriter.FrameTypeEnum.UpdateSetList, "SET", "");
        Iterable<Pair<SqlIdentifier, SqlNode>> pairIterable =
            Pair.of(
                Util.cast(getTargetColumnList().getList(), SqlIdentifier.class),
                getSourceExpressionList().getList());
        for (Pair<SqlIdentifier, SqlNode> pair : pairIterable) {
            writer.sep(",");
            pair.left.unparse(
                writer,
                getOperator().getLeftPrec(),
                getOperator().getRightPrec());
            writer.keyword("=");
            pair.right.unparse(
                writer,
                getOperator().getLeftPrec(),
                getOperator().getRightPrec());
        }
        writer.endList(setFrame);
        if (getCondition() != null) {
            writer.sep("WHERE");
            getCondition().unparse(
                writer,
                getOperator().getLeftPrec(),
                getOperator().getRightPrec());
        }
        writer.endList(frame);
    }

    public void validate(SqlValidator validator, SqlValidatorScope scope)
    {
        validator.validateUpdate(this);
    }
}

// End SqlUpdate.java
