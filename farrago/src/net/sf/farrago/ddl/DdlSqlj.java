/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2005 SQLstream, Inc.
// Copyright (C) 2005 Dynamo BI Corporation
// Portions Copyright (C) 2004 John V. Sichi
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
package net.sf.farrago.ddl;

import java.sql.*;

import org.eigenbase.sql.*;
import org.eigenbase.sql.util.SqlBuilder;
import org.eigenbase.util.*;


/**
 * DdlSqlj contains the system-defined implementations for the standard SQLJ
 * system procedures such as INSTALL_JAR.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public abstract class DdlSqlj
{
    //~ Methods ----------------------------------------------------------------

    /**
     * @sql.2003 Part 13 Section 11.1
     */
    public static void install_jar(
        String url,
        String jar,
        int deploy)
        throws SQLException
    {
        url = url.trim();
        jar = jar.trim();
        SqlBuilder sql = new SqlBuilder(SqlDialect.EIGENBASE);
        sql.append("CREATE JAR ");
        // REVIEW: We can't use sql.identifier(jar), because
        // the jar argument to install_jar is already quoted
        // if needed.  But is there some sanitization we need
        // to do, or is no SQL injection attack possible?
        sql.append(jar);
        sql.append(" library ");
        sql.literal(url);
        sql.append(" options(");
        sql.append(deploy);
        sql.append(")");
        executeSql(sql.getSql());
    }

    /**
     * @sql.2003 Part 13 Section 11.2
     */
    public static void replace_jar(
        String url,
        String jar)
        throws SQLException
    {
        url = url.trim();
        jar = jar.trim();

        // TODO jvs 18-Jan-2005
        throw Util.needToImplement("replace_jar");
    }

    /**
     * @sql.2003 Part 13 Section 11.3
     */
    public static void remove_jar(
        String jar,
        int undeploy)
        throws SQLException
    {
        jar = jar.trim();

        SqlBuilder sql = new SqlBuilder(SqlDialect.EIGENBASE);
        sql.append("DROP JAR ");
        // REVIEW: see comments in install_jar regarding possible
        // sanitization needed
        sql.append(jar);
        sql.append(" options (");
        sql.append(undeploy);
        sql.append(") RESTRICT");
        executeSql(sql.getSql());
    }

    /**
     * @sql.2003 Part 13 Section 11.4
     */
    public static void alter_java_path(
        String jar,
        String path)
        throws SQLException
    {
        jar = jar.trim();
        path = path.trim();
        throw Util.needToImplement("alter_java_path");
    }

    private static void executeSql(
        String sql)
        throws SQLException
    {
        Connection conn =
            DriverManager.getConnection(
                "jdbc:default:connection");
        Statement stmt = conn.createStatement();
        stmt.executeUpdate(sql);

        // NOTE jvs 19-Jan-2005:  no need for cleanup; default connection
        // is cleaned up automatically.
    }
}

// End DdlSqlj.java
