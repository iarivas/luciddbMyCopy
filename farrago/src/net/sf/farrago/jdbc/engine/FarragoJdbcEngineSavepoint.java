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
package net.sf.farrago.jdbc.engine;

import java.sql.*;

import net.sf.farrago.fem.fennel.*;
import net.sf.farrago.session.*;


/**
 * FarragoJdbcEngineSavepoint implements the {@link java.sql.Savepoint}
 * interface for Farrago.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class FarragoJdbcEngineSavepoint
    implements Savepoint
{
    //~ Instance fields --------------------------------------------------------

    FarragoSessionSavepoint farragoSavepoint;

    //~ Constructors -----------------------------------------------------------

    FarragoJdbcEngineSavepoint(FarragoSessionSavepoint farragoSavepoint)
    {
        this.farragoSavepoint = farragoSavepoint;
    }

    //~ Methods ----------------------------------------------------------------

    // implement Savepoint
    public int getSavepointId()
        throws SQLException
    {
        if (farragoSavepoint.getName() != null) {
            throw new SQLException("Can't getSavepointId for named Savepoint");
        }
        return farragoSavepoint.getId();
    }

    // implement Savepoint
    public String getSavepointName()
        throws SQLException
    {
        if (farragoSavepoint.getName() == null) {
            throw new SQLException(
                "Can't getSavepointName for unnamed Savepoint");
        }
        return farragoSavepoint.getName();
    }
}

// End FarragoJdbcEngineSavepoint.java
