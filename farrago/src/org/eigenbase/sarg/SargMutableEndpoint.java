/*
// $Id$
// Package org.eigenbase is a class library of data management components.
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
package org.eigenbase.sarg;

import org.eigenbase.reltype.*;
import org.eigenbase.rex.*;


/**
 * SargMutableEndpoint exposes methods for modifying a {@link SargEndpoint}.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class SargMutableEndpoint
    extends SargEndpoint
{
    //~ Constructors -----------------------------------------------------------

    /**
     * @see SargFactory#newEndpoint
     */
    SargMutableEndpoint(SargFactory factory, RelDataType dataType)
    {
        super(factory, dataType);
    }

    //~ Methods ----------------------------------------------------------------

    // publicize SargEndpoint
    public void setInfinity(int infinitude)
    {
        super.setInfinity(infinitude);
    }

    // publicize SargEndpoint
    public void setFinite(
        SargBoundType boundType,
        SargStrictness strictness,
        RexNode coordinate)
    {
        super.setFinite(boundType, strictness, coordinate);
    }
}

// End SargMutableEndpoint.java
