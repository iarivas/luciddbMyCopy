/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2004 The Eigenbase Project
// Copyright (C) 2004 SQLstream, Inc.
// Copyright (C) 2005 Dynamo BI Corporation
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
package org.eigenbase.sql.validate;

import org.eigenbase.reltype.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.parser.SqlParserPos;
import org.eigenbase.sql.type.*;

/**
 * Namespace offered by a subquery.
 *
 * @author jhyde
 * @version $Id$
 * @see SelectScope
 * @see SetopNamespace
 * @since Mar 25, 2003
 */
public class SelectNamespace
    extends AbstractNamespace
{
    //~ Instance fields --------------------------------------------------------

    private final SqlSelect select;

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a SelectNamespace.
     *
     * @param validator Validate
     * @param select Select node
     * @param enclosingNode Enclosing node
     */
    public SelectNamespace(
        SqlValidatorImpl validator,
        SqlSelect select,
        SqlNode enclosingNode)
    {
        super(validator, enclosingNode);
        this.select = select;
    }

    //~ Methods ----------------------------------------------------------------

    // implement SqlValidatorNamespace, overriding return type
    public SqlSelect getNode()
    {
        return select;
    }

    public RelDataType validateImpl()
    {
        validator.validateSelect(select, validator.unknownType);
        return rowType;
    }

    public SqlMonotonicity getMonotonicity(String columnName)
    {
        final RelDataType rowType = this.getRowTypeAsWritten();
        final int field = SqlTypeUtil.findField(rowType, columnName);
        final SqlNode selectItem;
        if (field < 0) {
            // Column is not in the explicit select list, so presumably it's
            // a system field.
            selectItem = new SqlIdentifier(columnName, SqlParserPos.ZERO);
        } else {
            selectItem = select.getSelectList().get(field);
        }
        return validator.getSelectScope(select).getMonotonicity(selectItem);
    }
}

// End SelectNamespace.java
