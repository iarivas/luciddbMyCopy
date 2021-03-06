/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2005 SQLstream, Inc.
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
package net.sf.farrago.fennel.rel;

import java.util.*;

import net.sf.farrago.catalog.*;
import net.sf.farrago.fem.fennel.*;
import net.sf.farrago.query.*;

import org.eigenbase.rel.*;
import org.eigenbase.relopt.*;
import org.eigenbase.reltype.RelDataTypeField;


/**
 * FennelAggRel represents the Fennel implementation of aggregation.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class FennelAggRel
    extends AggregateRelBase
    implements FennelRel
{
    //~ Instance fields --------------------------------------------------------

    protected final FarragoRepos repos;

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a FennelAggRel.
     *
     * @param cluster Cluster
     * @param child Child
     * @param systemFieldList List of system fields
     * @param groupSet Bitset of grouping fields
     * @param aggCalls Collection of calls to aggregate functions
     */
    public FennelAggRel(
        RelOptCluster cluster,
        RelNode child,
        List<RelDataTypeField> systemFieldList,
        BitSet groupSet,
        List<AggregateCall> aggCalls)
    {
        super(
            cluster,
            FennelRel.FENNEL_EXEC_CONVENTION.singletonSet,
            child, systemFieldList, groupSet,
            aggCalls);
        repos = FennelRelUtil.getRepos(this);
    }

    //~ Methods ----------------------------------------------------------------

    public FennelAggRel clone()
    {
        FennelAggRel clone =
            new FennelAggRel(
                getCluster(),
                getChild().clone(),
                systemFieldList,
                (BitSet) groupSet.clone(),
                aggCalls);
        clone.inheritTraitsFrom(this);
        return clone;
    }

    // implement FennelRel
    public RelFieldCollation [] getCollations()
    {
        // TODO jvs 5-Oct-2005: if group keys are already sorted,
        // non-hash agg will preserve them; full-table agg is
        // trivially sorted (only one row of output)
        return RelFieldCollation.emptyCollationArray;
    }

    // implement FennelRel
    public Object implementFennelChild(FennelRelImplementor implementor)
    {
        return implementor.visitChild(
            this,
            0,
            getChild());
    }

    // implement FennelRel
    public FemExecutionStreamDef toStreamDef(FennelRelImplementor implementor)
    {
        FemSortedAggStreamDef aggStream = repos.newFemSortedAggStreamDef();
        FennelRelUtil.defineAggStream(aggCalls, groupSet, repos, aggStream);
        implementor.addDataFlowFromProducerToConsumer(
            implementor.visitFennelChild((FennelRel) getChild(), 0),
            aggStream);

        return aggStream;
    }
}

// End FennelAggRel.java
