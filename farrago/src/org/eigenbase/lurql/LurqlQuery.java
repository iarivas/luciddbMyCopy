/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2009 SQLstream, Inc.
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
package org.eigenbase.lurql;

import java.io.*;

import java.util.*;

import org.eigenbase.util.*;


/**
 * LurqlQuery represents the parsed form of a LURQL query.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class LurqlQuery
    extends LurqlQueryNode
{
    //~ Instance fields --------------------------------------------------------

    private final List<String> selectList;

    private final LurqlQueryNode root;

    //~ Constructors -----------------------------------------------------------

    public LurqlQuery(List<String> selectList, LurqlQueryNode root)
    {
        this.selectList = Collections.unmodifiableList(selectList);
        this.root = root;
    }

    //~ Methods ----------------------------------------------------------------

    public List<String> getSelectList()
    {
        return selectList;
    }

    public LurqlQueryNode getRoot()
    {
        return root;
    }

    static void unparseSelectList(PrintWriter pw, List<String> selectList)
    {
        int k = 0;
        for (String id : selectList) {
            if (k++ > 0) {
                pw.print(", ");
            }
            if (id.equals("*")) {
                pw.print(id);
            } else {
                StackWriter.printSqlIdentifier(pw, id);
            }
        }
    }

    // implement LurqlQueryNode
    public void unparse(PrintWriter pw)
    {
        pw.print("select ");
        unparseSelectList(pw, selectList);
        pw.println();
        pw.println("from");
        root.unparse(pw);
        pw.println(";");
    }
}

// End LurqlQuery.java
