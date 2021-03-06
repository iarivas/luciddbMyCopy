/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2006 The Eigenbase Project
// Copyright (C) 2010 SQLstream, Inc.
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
package org.luciddb.lcs;

import net.sf.farrago.fem.fennel.*;
import net.sf.farrago.fennel.rel.*;
import net.sf.farrago.query.*;

import org.eigenbase.rel.*;
import org.eigenbase.rel.metadata.*;
import org.eigenbase.relopt.*;


/**
 * LcsIndexIntersectRel is a relation for intersecting the results of two index
 * scans. The input to this relation must be more than one.
 *
 * @author John Pham
 * @version $Id$
 */
public class LcsIndexIntersectRel
    extends LcsIndexBitOpRel
{
    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a new LcsIndexIntersectRel object.
     */
    public LcsIndexIntersectRel(
        RelOptCluster cluster,
        RelNode [] inputs,
        LcsTable lcsTable,
        FennelRelParamId startRidParamId,
        FennelRelParamId rowLimitParamId)
    {
        super(cluster, inputs, lcsTable, startRidParamId, rowLimitParamId);
    }

    //~ Methods ----------------------------------------------------------------

    // implement Cloneable
    public LcsIndexIntersectRel clone()
    {
        return new LcsIndexIntersectRel(
            getCluster(),
            RelOptUtil.clone(getInputs()),
            lcsTable,
            startRidParamId,
            rowLimitParamId);
    }

    // implement RelNode
    public double getRows()
    {
        // get the minimum number of rows across the children and then make
        // the cost inversely proportional to the number of children
        double minChildRows = 0;
        for (int i = 0; i < inputs.length; i++) {
            if ((minChildRows == 0) || (inputs[i].getRows() < minChildRows)) {
                minChildRows = RelMetadataQuery.getRowCount(inputs[i]);
            }
        }
        return minChildRows / inputs.length;
    }

    public FemExecutionStreamDef toStreamDef(FennelRelImplementor implementor)
    {
        FemLbmIntersectStreamDef intersectStream =
            lcsTable.getIndexGuide().newBitmapIntersect(
                implementor.translateParamId(startRidParamId),
                implementor.translateParamId(rowLimitParamId));

        setBitOpChildStreams(implementor, intersectStream);

        return intersectStream;
    }
}

// End LcsIndexIntersectRel.java
