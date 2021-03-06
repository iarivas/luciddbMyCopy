/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2005 SQLstream, Inc.
// Copyright (C) 2005 Dynamo BI Corporation
// Portions Copyright (C) 2005 John V. Sichi
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

import java.math.*;

import java.util.*;

import net.sf.farrago.query.*;

import org.eigenbase.rel.*;
import org.eigenbase.rel.convert.*;
import org.eigenbase.relopt.*;
import org.eigenbase.reltype.*;
import org.eigenbase.rex.*;


/**
 * FennelOneRowRule provides an implementation for {@link OneRowRel} in terms of
 * {@link FennelValuesRel}.
 *
 * @author Wael Chatila
 * @version $Id$
 * @since Feb 4, 2005
 */
public class FennelOneRowRule
    extends ConverterRule
{
    public static final FennelOneRowRule instance =
        new FennelOneRowRule();

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a FennelOneRowRule.
     */
    private FennelOneRowRule()
    {
        super(
            OneRowRel.class,
            CallingConvention.NONE,
            FennelRel.FENNEL_EXEC_CONVENTION,
            "FennelOneRowRule");
    }

    //~ Methods ----------------------------------------------------------------

    public RelNode convert(RelNode rel)
    {
        OneRowRel oneRowRel = (OneRowRel) rel;

        RexBuilder rexBuilder = oneRowRel.getCluster().getRexBuilder();
        RexLiteral literalZero = rexBuilder.makeExactLiteral(new BigDecimal(0));

        List<List<RexLiteral>> tuples =
            Collections.singletonList(
                Collections.singletonList(
                    literalZero));

        RelDataType rowType =
            OneRowRel.deriveOneRowType(oneRowRel.getCluster().getTypeFactory());

        FennelValuesRel valuesRel =
            new FennelValuesRel(
                oneRowRel.getCluster(),
                rowType,
                tuples);
        return valuesRel;
    }
}

// End FennelOneRowRule.java
