/*
// $Id$
// Fennel is a library of data storage and processing components.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2005 SQLstream, Inc.
// Copyright (C) 2005 Dynamo BI Corporation
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

#ifndef Fennel_InvalidParamExcn_Included
#define Fennel_InvalidParamExcn_Included

#include "fennel/common/FennelExcn.h"

using namespace std;

FENNEL_BEGIN_NAMESPACE

/**
 * Exception class for invalid parameter settings
 */
class FENNEL_COMMON_EXPORT InvalidParamExcn : public FennelExcn
{
public:
    /**
     * Constructs a new InvalidParamExcn.
     *
     * @param min minimum valid value
     *
     * @param max maximum valid value
     */
    explicit InvalidParamExcn(string min, string max);
};

FENNEL_END_NAMESPACE

#endif

// End InvalidParamExcn.h
