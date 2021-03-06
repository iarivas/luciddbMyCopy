/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2005 SQLstream, Inc.
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
package net.sf.farrago.ddl;

import net.sf.farrago.cwm.core.*;
import net.sf.farrago.session.*;


/**
 * DdlDropStmt represents a DDL DROP statement of any kind.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class DdlDropStmt
    extends DdlStmt
{
    //~ Instance fields --------------------------------------------------------

    private boolean restrict;

    //~ Constructors -----------------------------------------------------------

    /**
     * Constructs a new DdlDropStmt.
     *
     * @param droppedElement top-level element dropped by this stmt
     * @param restrict whether DROP RESTRICT is in effect
     */
    public DdlDropStmt(
        CwmModelElement droppedElement,
        boolean restrict)
    {
        super(droppedElement);
        this.restrict = restrict;
    }

    //~ Methods ----------------------------------------------------------------

    // override DdlStmt
    public boolean isDropRestricted()
    {
        return restrict;
    }

    // override DdlStmt
    public void preValidate(FarragoSessionDdlValidator ddlValidator)
    {
        super.preValidate(ddlValidator);

        // Delete the top-level object.  DdlValidator will take care of the
        // rest.
        ddlValidator.deleteObject(getModelElement());
    }

    // implement DdlStmt
    public void visit(DdlVisitor visitor)
    {
        visitor.visit(this);
    }
}

// End DdlDropStmt.java
