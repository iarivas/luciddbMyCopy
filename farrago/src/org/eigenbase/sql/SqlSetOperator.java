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

import org.eigenbase.sql.type.*;
import org.eigenbase.sql.validate.*;


/**
 * SqlSetOperator represents a relational set theory operator (UNION, INTERSECT,
 * MINUS). These are binary operators, but with an extra boolean attribute
 * tacked on for whether to remove duplicates (e.g. UNION ALL does not remove
 * duplicates).
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class SqlSetOperator
    extends SqlBinaryOperator
{
    //~ Instance fields --------------------------------------------------------

    private final boolean all;

    //~ Constructors -----------------------------------------------------------

    public SqlSetOperator(
        String name,
        SqlKind kind,
        int prec,
        boolean all)
    {
        super(
            name,
            kind,
            prec,
            true,
            SqlTypeStrategies.rtiLeastRestrictive,
            null,
            SqlTypeStrategies.otcSetop);
        this.all = all;
    }

    public SqlSetOperator(
        String name,
        SqlKind kind,
        int prec,
        boolean all,
        SqlReturnTypeInference returnTypeInference,
        SqlOperandTypeInference operandTypeInference,
        SqlOperandTypeChecker operandTypeChecker)
    {
        super(
            name,
            kind,
            prec,
            true,
            returnTypeInference,
            operandTypeInference,
            operandTypeChecker);
        this.all = all;
    }

    //~ Methods ----------------------------------------------------------------

    public boolean isAll()
    {
        return all;
    }

    public boolean isDistinct()
    {
        return !all;
    }

    public void validateCall(
        SqlCall call,
        SqlValidator validator,
        SqlValidatorScope scope,
        SqlValidatorScope operandScope)
    {
        validator.validateQuery(call, operandScope);
    }
}

// End SqlSetOperator.java
