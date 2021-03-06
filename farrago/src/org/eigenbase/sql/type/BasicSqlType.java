/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2004 SQLstream, Inc.
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
package org.eigenbase.sql.type;

import java.nio.charset.*;

import org.eigenbase.sql.*;
import org.eigenbase.util.*;


/**
 * BasicSqlType represents a standard atomic SQL type (excluding interval
 * types).
 *
 * @author jhyde
 * @version $Id$
 */
public class BasicSqlType
    extends AbstractSqlType
{
    //~ Static fields/initializers ---------------------------------------------

    public static final int SCALE_NOT_SPECIFIED = Integer.MIN_VALUE;
    public static final int PRECISION_NOT_SPECIFIED = -1;

    //~ Instance fields --------------------------------------------------------

    private int precision;
    private int scale;
    private SqlCollation collation;
    private SerializableCharset wrappedCharset;

    //~ Constructors -----------------------------------------------------------

    /**
     * Constructs a type with no parameters. This should only be called from a
     * factory method.
     *
     * @param typeName Type name
     *
     * @pre typeName.allowsNoPrecNoScale(false,false)
     */
    public BasicSqlType(SqlTypeName typeName)
    {
        super(typeName, false, null);
        Util.pre(
            typeName.allowsPrecScale(false, false),
            "typeName.allowsPrecScale(false,false), typeName="
            + typeName.name());
        this.precision = PRECISION_NOT_SPECIFIED;
        this.scale = SCALE_NOT_SPECIFIED;
        computeDigest();
    }

    /**
     * Constructs a type with precision/length but no scale.
     *
     * @param typeName Type name
     *
     * @pre typeName.allowsPrecNoScale(true,false)
     */
    public BasicSqlType(
        SqlTypeName typeName,
        int precision)
    {
        super(typeName, false, null);
        Util.pre(
            typeName.allowsPrecScale(true, false),
            "typeName.allowsPrecScale(true,false)");
        this.precision = precision;
        this.scale = SCALE_NOT_SPECIFIED;
        computeDigest();
    }

    /**
     * Constructs a type with precision/length and scale.
     *
     * @param typeName Type name
     *
     * @pre typeName.allowsPrecScale(true,true)
     */
    public BasicSqlType(
        SqlTypeName typeName,
        int precision,
        int scale)
    {
        super(typeName, false, null);
        Util.pre(
            typeName.allowsPrecScale(true, true),
            "typeName.allowsPrecScale(true,true)");
        this.precision = precision;
        this.scale = scale;
        computeDigest();
    }

    //~ Methods ----------------------------------------------------------------

    /**
     * Constructs a type with nullablity
     */
    BasicSqlType createWithNullability(boolean nullable)
    {
        BasicSqlType ret;
        try {
            ret = (BasicSqlType) this.clone();
        } catch (CloneNotSupportedException e) {
            throw Util.newInternal(e);
        }
        ret.isNullable = nullable;
        ret.computeDigest();
        return ret;
    }

    /**
     * Constructs a type with charset and collation
     *
     * @pre SqlTypeUtil.inCharFamily(this)
     */
    BasicSqlType createWithCharsetAndCollation(
        Charset charset,
        SqlCollation collation)
    {
        Util.pre(SqlTypeUtil.inCharFamily(this), "Not an chartype");
        BasicSqlType ret;
        try {
            ret = (BasicSqlType) this.clone();
        } catch (CloneNotSupportedException e) {
            throw Util.newInternal(e);
        }
        ret.wrappedCharset = SerializableCharset.forCharset(charset);
        ret.collation = collation;
        ret.computeDigest();
        return ret;
    }

    //implement RelDataType
    public int getPrecision()
    {
        if (precision == PRECISION_NOT_SPECIFIED) {
            switch (typeName) {
            case BOOLEAN:
                return 1;
            case TINYINT:
                return 3;
            case SMALLINT:
                return 5;
            case INTEGER:
                return 10;
            case BIGINT:
                return 19;
            case DECIMAL:
                return SqlTypeName.MAX_NUMERIC_PRECISION;
            case REAL:
                return 7;
            case FLOAT:
            case DOUBLE:
                return 15;
            case TIME:
                return 0; // SQL99 part 2 section 6.1 syntax rule 30
            case TIMESTAMP:

                // farrago supports only 0 (see
                // SqlTypeName.getDefaultPrecision), but it should be 6
                // (microseconds) per SQL99 part 2 section 6.1 syntax rule 30.
                return 0;
            case DATE:
                return 0;
            case CHAR:
            case VARCHAR:
            case BINARY:
            case VARBINARY:
                return 1; // SQL2003 part 2 section 6.1 syntax rule 5
            default:
                throw Util.newInternal(
                    "type " + typeName + " does not have a precision");
            }
        }
        return precision;
    }

    // implement RelDataType
    public int getScale()
    {
        if (scale == SCALE_NOT_SPECIFIED) {
            switch (typeName) {
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case DECIMAL:
                return 0;
            default:
                throw Util.newInternal(
                    "type " + typeName + " does not have a scale");
            }
        }
        return scale;
    }

    // implement RelDataType
    public Charset getCharset()
        throws RuntimeException
    {
        return (wrappedCharset == null) ? null : wrappedCharset.getCharset();
    }

    // implement RelDataType
    public SqlCollation getCollation()
        throws RuntimeException
    {
        return collation;
    }

    // implement RelDataTypeImpl
    protected void generateTypeString(StringBuilder sb, boolean withDetail)
    {
        // Called to make the digest, which equals() compares;
        // so equivalent data types must produce identical type strings.

        sb.append(typeName.name());
        boolean printPrecision = (precision != PRECISION_NOT_SPECIFIED);
        boolean printScale = (scale != SCALE_NOT_SPECIFIED);

        // for the digest, print the precision when defaulted,
        // since (for instance) TIME is equivalent to TIME(0).
        if (withDetail) {
            // -1 means there is no default value for precision
            if (typeName.getDefaultPrecision() > -1) {
                printPrecision = true;
            }
            if (typeName.getDefaultScale() > -1) {
                printScale = true;
            }
        }

        if (printPrecision) {
            sb.append('(');
            sb.append(getPrecision());
            if (printScale) {
                sb.append(", ");
                sb.append(getScale());
            }
            sb.append(')');
        }
        if (!withDetail) {
            return;
        }
        if (wrappedCharset != null) {
            sb.append(" CHARACTER SET \"");
            sb.append(wrappedCharset.getCharset().name());
            sb.append("\"");
        }
        if (collation != null) {
            sb.append(" COLLATE \"");
            sb.append(collation.getCollationName());
            sb.append("\"");
        }
    }

    /**
     * Returns a value which is a limit for this type.
     *
     * <p>For example,
     *
     * <table border="1">
     * <tr>
     * <th>Datatype</th>
     * <th>sign</th>
     * <th>limit</th>
     * <th>beyond</th>
     * <th>precision</th>
     * <th>scale</th>
     * <th>Returns</th>
     * </tr>
     * <tr>
     * <td>Integer</th>
     * <td>true</td>
     * <td>true</td>
     * <td>false</td>
     * <td>-1</td>
     * <td>-1</td>
     * <td>2147483647 (2 ^ 31 -1 = MAXINT)</td>
     * </tr>
     * <tr>
     * <td>Integer</th>
     * <td>true</td>
     * <td>true</td>
     * <td>true</td>
     * <td>-1</td>
     * <td>-1</td>
     * <td>2147483648 (2 ^ 31 = MAXINT + 1)</td>
     * </tr>
     * <tr>
     * <td>Integer</th>
     * <td>false</td>
     * <td>true</td>
     * <td>false</td>
     * <td>-1</td>
     * <td>-1</td>
     * <td>-2147483648 (-2 ^ 31 = MININT)</td>
     * </tr>
     * <tr>
     * <td>Boolean</th>
     * <td>true</td>
     * <td>true</td>
     * <td>false</td>
     * <td>-1</td>
     * <td>-1</td>
     * <td>TRUE</td>
     * </tr>
     * <tr>
     * <td>Varchar</th>
     * <td>true</td>
     * <td>true</td>
     * <td>false</td>
     * <td>10</td>
     * <td>-1</td>
     * <td>'ZZZZZZZZZZ'</td>
     * </tr>
     * </table>
     *
     * @param sign If true, returns upper limit, otherwise lower limit
     * @param limit If true, returns value at or near to overflow; otherwise
     * value at or near to underflow
     * @param beyond If true, returns the value just beyond the limit, otherwise
     * the value at the limit
     *
     * @return Limit value
     */
    public Object getLimit(
        boolean sign,
        SqlTypeName.Limit limit,
        boolean beyond)
    {
        int precision = typeName.allowsPrec() ? this.getPrecision() : -1;
        int scale = typeName.allowsScale() ? this.getScale() : -1;
        return typeName.getLimit(
            sign,
            limit,
            beyond,
            precision,
            scale);
    }
}

// End BasicSqlType.java
