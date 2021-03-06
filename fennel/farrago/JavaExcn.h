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

#ifndef Fennel_JavaExcn_Included
#define Fennel_JavaExcn_Included

#include "fennel/common/FennelExcn.h"

#include "fennel/farrago/JniUtil.h"

FENNEL_BEGIN_NAMESPACE

/**
 * Exception class for wrapping Java exceptions.
 *
 *<p>
 *
 * REVIEW jvs 23-Aug-2007:  If any code actually handles one of these
 * and carries on, it may need to delete the local jthrowable reference
 * to avoid a leak.
 */
class FENNEL_FARRAGO_EXPORT JavaExcn
    : public FennelExcn
{
    jthrowable javaException;

public:
    /**
     * Constructs a new JavaExcn.
     *
     * @param javaExceptionInit the wrapped Java exception
     */
    explicit JavaExcn(
        jthrowable javaExceptionInit);

    /**
     * @return the wrapped Java exception
     */
    jthrowable getJavaException() const;

    /**
     * @return the stack trace
     */
    const std::string& getStackTrace() const;

    // override FennelExcn
    virtual void throwSelf();
};

FENNEL_END_NAMESPACE

#endif

// End JavaExcn.h
