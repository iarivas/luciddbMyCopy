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
package net.sf.farrago.catalog.codegen;

import java.io.*;

import java.lang.reflect.*;

import java.util.*;


// TODO jvs 28-April-2004: move this to a repos-independent codegen utility
// package and add a main method so it can be used from ant; this is just a
// temporary parking space

/**
 * CppEnumGen is a tool for generating a C++ enumeration based on the public
 * static final data members of a Java class.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class CppEnumGen
{
    //~ Instance fields --------------------------------------------------------

    private PrintWriter pw;

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a new CppEnumGen.
     *
     * @param pw PrintWriter to which enumeration definitions should be written
     */
    public CppEnumGen(PrintWriter pw)
    {
        this.pw = pw;
    }

    //~ Methods ----------------------------------------------------------------

    /**
     * Generates a single enumeration. Enumeration values (and their names) is
     * based on the subset of non-inherited public static final data members
     * contained by enumClass and having exact type enumSymbolType. Enumeration
     * order (and hence implied ordinals) is on the current locale's collation
     * order for the enum field names. This ordering may not hold in the future,
     * so no C++ code should be written which depends on the current
     * deterministic ordering.
     *
     * <p>TODO: Support integer ordinals. Also, we'd prefer to preserve the
     * original metamodel ordering in order to relax the ordering condition
     * above.
     *
     * @param enumName name to give C++ enum
     * @param enumClass Java class to be interpreted as an enumeration; this
     * class's name is used as the enumeration name
     * @param enumSymbolType Java class used to determine enumeration membership
     */
    public void generateEnumForClass(
        String enumName,
        Class enumClass,
        Class enumSymbolType)
        throws Exception
    {
        List<String> symbols = new ArrayList<String>();

        Field [] fields = enumClass.getDeclaredFields();
        for (int i = 0; i < fields.length; ++i) {
            Field field = fields[i];
            Class fieldType = field.getType();
            if (!(fieldType.equals(enumSymbolType))) {
                continue;
            }
            symbols.add(field.getName());
        }

        // Force deterministic ordering
        Collections.sort(symbols);

        pw.print("enum ");
        pw.print(enumName);
        pw.println(" {");

        Iterator<String> iter = symbols.iterator();
        while (iter.hasNext()) {
            String symbol = iter.next();
            pw.print("    ");
            pw.print(symbol);
            if (iter.hasNext()) {
                pw.print(",");
            }
            pw.println();
        }

        pw.println("};");
        pw.println();

        // TODO jvs 28-April-2004:  declare as extern rather than static
        pw.print("static std::string ");
        pw.print(enumName);
        pw.print("_names[] = {");

        iter = symbols.iterator();
        while (iter.hasNext()) {
            String symbol = iter.next();
            pw.print('"');
            pw.print(symbol);
            pw.print('"');
            pw.print(",");
        }

        pw.println("\"\"};");
        pw.println();
    }
}

// End CppEnumGen.java
