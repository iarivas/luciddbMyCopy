/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2004 The Eigenbase Project
// Copyright (C) 2004 SQLstream, Inc.
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
package org.eigenbase.sql.validate;

import org.eigenbase.sql.*;
import org.eigenbase.sql.parser.*;


/**
 * A generic implementation of {@link SqlMoniker}.
 *
 * @author tleung
 * @version $Id$
 * @since May 31, 2005
 */
public class SqlMonikerImpl
    implements SqlMoniker
{
    //~ Instance fields --------------------------------------------------------

    private final String [] names;
    private final SqlMonikerType type;

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a moniker with an array of names.
     */
    public SqlMonikerImpl(String [] names, SqlMonikerType type)
    {
        assert names != null;
        assert type != null;
        for (String name : names) {
            assert name != null;
        }
        this.names = names;
        this.type = type;
    }

    /**
     * Creates a moniker with a single name.
     */
    public SqlMonikerImpl(String name, SqlMonikerType type)
    {
        this(
            new String[] { name },
            type);
    }

    //~ Methods ----------------------------------------------------------------

    public SqlMonikerType getType()
    {
        return type;
    }

    public String [] getFullyQualifiedNames()
    {
        return names;
    }

    public SqlIdentifier toIdentifier()
    {
        return new SqlIdentifier(names, SqlParserPos.ZERO);
    }

    public String toString()
    {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                result.append('.');
            }
            result.append(names[i]);
        }
        return result.toString();
    }

    public String id()
    {
        StringBuilder result = new StringBuilder(type.name());
        result.append("(");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                result.append('.');
            }
            result.append(names[i]);
        }
        result.append(")");
        return result.toString();
    }
}

// End SqlMonikerImpl.java
