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
package org.eigenbase.rex;

import org.eigenbase.reltype.*;


/**
 * Reference to the current row of a correlating relational expression.
 *
 * <p>Correlating variables are introduced when performing nested loop joins.
 * Each row is received from one side of the join, a correlating variable is
 * assigned a value, and the other side of the join is restarted.</p>
 *
 * @author jhyde
 * @version $Id$
 * @since Nov 24, 2003
 */
public class RexCorrelVariable
    extends RexVariable
{
    //~ Constructors -----------------------------------------------------------

    RexCorrelVariable(
        String varName,
        RelDataType type)
    {
        super(varName, type);
    }

    //~ Methods ----------------------------------------------------------------

    public RexCorrelVariable clone()
    {
        return new RexCorrelVariable(name, type);
    }

    public <R> R accept(RexVisitor<R> visitor)
    {
        return visitor.visitCorrelVariable(this);
    }
}

// End RexCorrelVariable.java
