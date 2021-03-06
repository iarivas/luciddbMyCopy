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
package net.sf.farrago.fennel;

import java.sql.*;

import net.sf.farrago.fem.fennel.*;


/**
 * FennelCmdExecutor defines a mechanism for extending and modifying the command
 * set understood by Fennel. {@link FennelCmdExecutorImpl} provides a default
 * implementation. Extensions can be created by writing a JNI DLL which links
 * with Farrago's JNI DLL and provides an alternative for {@link
 * FennelStorage#executeJavaCmd}.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public interface FennelCmdExecutor
{
    //~ Methods ----------------------------------------------------------------

    /**
     * Executes one FemCmd with an optional execution handle.
     *
     * @param cmd the command to be executed
     * @param execHandle the execution handle used to communicate state
     * information from Farrago to Fennel; set to null if there is no handle
     *
     * @return result handle as primitive
     */
    public long executeJavaCmd(FemCmd cmd, FennelExecutionHandle execHandle)
        throws SQLException;
}

// End FennelCmdExecutor.java
