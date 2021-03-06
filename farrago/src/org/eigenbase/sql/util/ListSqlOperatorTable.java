/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2006 The Eigenbase Project
// Copyright (C) 2006 SQLstream, Inc.
// Copyright (C) 2006 Dynamo BI Corporation
// Portions Copyright (C) 2006 John V. Sichi
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
package org.eigenbase.sql.util;

import java.util.*;

import org.eigenbase.sql.*;


/**
 * Implementation of the {@link SqlOperatorTable} interface by using a list of
 * {@link SqlOperator operators}.
 *
 * @author jhyde
 * @version $Id$
 */
public class ListSqlOperatorTable
    implements SqlOperatorTable
{
    //~ Instance fields --------------------------------------------------------

    private final List<SqlOperator> operatorList = new ArrayList<SqlOperator>();

    //~ Constructors -----------------------------------------------------------

    public ListSqlOperatorTable()
    {
    }

    //~ Methods ----------------------------------------------------------------

    public void add(SqlOperator op)
    {
        operatorList.add(op);
    }

    public List<SqlOperator> lookupOperatorOverloads(
        SqlIdentifier opName,
        SqlFunctionCategory category,
        SqlSyntax syntax)
    {
        final ArrayList<SqlOperator> list = new ArrayList<SqlOperator>();
        for (SqlOperator operator : operatorList) {
            if (operator.getSyntax() != syntax) {
                continue;
            }
            if (!opName.isSimple()
                || !operator.isName(opName.getSimple()))
            {
                continue;
            }
            SqlFunctionCategory functionCategory;
            if (operator instanceof SqlFunction) {
                functionCategory = ((SqlFunction) operator).getFunctionType();
            } else {
                functionCategory = SqlFunctionCategory.System;
            }
            if (category != functionCategory) {
                continue;
            }
            list.add(operator);
        }
        return list;
    }

    public List<SqlOperator> getOperatorList()
    {
        return operatorList;
    }
}

// End ListSqlOperatorTable.java
