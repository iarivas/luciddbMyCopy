/*
// $Id$
// Fennel is a library of data storage and processing components.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2003 SQLstream, Inc.
// Copyright (C) 2009 Dynamo BI Corporation
// Portions Copyright (C) 1999 John V. Sichi
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

#ifndef Fennel_CalcExcn_Included
#define Fennel_CalcExcn_Included

#include "fennel/common/FennelExcn.h"

FENNEL_BEGIN_NAMESPACE

class TupleDescriptor;
class TupleData;

/**
 * Exception class for errors encountered during calculator execution.
 */
class FENNEL_CALCULATOR_EXPORT CalcExcn
    : public FennelExcn
{
public:
    /**
     * Construct a new CalcExcn.
     */
    explicit CalcExcn(
        std::string warnings,
        TupleDescriptor const &inputDescriptor,
        TupleData const &inputData);
};

FENNEL_END_NAMESPACE

#endif

// End CalcExcn.h
