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
package net.sf.farrago.session;

import java.util.*;

import net.sf.farrago.catalog.*;
import net.sf.farrago.cwm.core.*;
import net.sf.farrago.cwm.relational.*;
import net.sf.farrago.cwm.relational.enumerations.*;
import net.sf.farrago.fem.med.*;
import net.sf.farrago.fem.sql2003.*;
import net.sf.farrago.fennel.*;
import net.sf.farrago.namespace.util.*;
import net.sf.farrago.type.*;
import net.sf.farrago.util.*;

import org.eigenbase.resgen.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.parser.*;
import org.eigenbase.sql.validate.*;
import org.eigenbase.trace.*;


/**
 * FarragoSessionStmtValidator defines a generic interface for statement
 * validation services. It is not as specific as the other validator-related
 * interfaces ({@link FarragoSessionDdlValidator} and {@link
 * FarragoSessionPreparingStmt}).
 *
 * @author John V. Sichi
 * @version $Id$
 */
public interface FarragoSessionStmtValidator
    extends FarragoAllocationOwner
{
    //~ Methods ----------------------------------------------------------------

    /**
     * @return session invoking stmt to be validated
     */
    public FarragoSession getSession();

    /**
     * @return the parser parsing the statement being validated
     */
    public FarragoSessionParser getParser();

    /**
     * @return repos to use for validating object references
     */
    public FarragoRepos getRepos();

    /**
     * @return FennelDbHandle storing local data to be accessed by validated
     * stmt
     */
    public FennelDbHandle getFennelDbHandle();

    /**
     * @return type factory to use for validation
     */
    public FarragoTypeFactory getTypeFactory();

    /**
     * @return connection defaults to use for validation
     */
    public FarragoSessionVariables getSessionVariables();

    /**
     * @return cache to use for code lookups during validation
     */
    public FarragoObjectCache getCodeCache();

    /**
     * @return private cache to use for validating references to data wrappers
     */
    public FarragoDataWrapperCache getDataWrapperCache();

    /**
     * @return index map to use for validation
     */
    public FarragoSessionIndexMap getIndexMap();

    /**
     * @return shared cache to use for validating references to data wrappers
     */
    public FarragoObjectCache getSharedDataWrapperCache();

    /**
     * @return the privilege checker for this validator
     */
    public FarragoSessionPrivilegeChecker getPrivilegeChecker();

    /**
     * @return the DDL lock manager for this validator
     */
    public FarragoDdlLockManager getDdlLockManager();

    /**
     * Gets the warning queue to be used by this validator
     *
     * @return warning queue
     */
    public FarragoWarningQueue getWarningQueue();

    /**
     * Sets the warning queue to be used by this validator
     *
     * @param warningQueue target queue to use
     */
    public void setWarningQueue(FarragoWarningQueue warningQueue);

    /**
     * Submits a request for access from the current user and/or role to a
     * catalog object via this validator's privilege checker. Actual checking of
     * the request may be deferred.
     *
     * @param obj object to be accessed
     * @param action the action to be performed on obj (see {@link
     * net.sf.farrago.fem.security.PrivilegedActionEnum} for base set)
     */
    public void requestPrivilege(
        CwmModelElement obj,
        String action);

    /**
     * Looks up a table's column by name, throwing a validation error if not
     * found.
     *
     * @param namedColumnSet the table to search
     * @param columnName name of column to find
     *
     * @return column found
     */
    public CwmColumn findColumn(
        CwmNamedColumnSet namedColumnSet,
        String columnName);

    /**
     * Looks up a catalog by name, throwing a validation error if not found.
     *
     * @param catalogName name of catalog to look up
     *
     * @return catalog found
     */
    public CwmCatalog findCatalog(String catalogName);

    /**
     * Gets the default catalog for unqualified schema names.
     *
     * @return default catalog
     */
    public CwmCatalog getDefaultCatalog();

    /**
     * Looks up a schema by name, throwing a validation error if not found.
     *
     * @param schemaName name of schema to look up
     *
     * @return schema found
     */
    public FemLocalSchema findSchema(SqlIdentifier schemaName);

    /**
     * Looks up a data wrapper by name, throwing a validation error if not
     * found.
     *
     * @param wrapperName name of wrapper to look up (must be simple)
     * @param isForeign true for foreign data wrapper; false for local data
     * wrapper
     *
     * @return wrapper found
     */
    public FemDataWrapper findDataWrapper(
        SqlIdentifier wrapperName,
        boolean isForeign);

    /**
     * Looks up a data server by name, throwing a validation error if not found.
     *
     * @param serverName name of server to look up (must be simple)
     *
     * @return server found
     */
    public FemDataServer findDataServer(SqlIdentifier serverName);

    /**
     * @return default data server to use if none specified in local table
     * definition
     */
    public FemDataServer getDefaultLocalDataServer();

    /**
     * Looks up a schema object by name, throwing a validation error if not
     * found.
     *
     * @param qualifiedName name of object to look up
     * @param clazz expected class of object; if the object exists with a
     * different class, it will be treated as if it did not exist
     *
     * @return schema object found
     */
    public <T extends CwmModelElement> T findSchemaObject(
        SqlIdentifier qualifiedName,
        Class<T> clazz);

    /**
     * Looks up a top-level object (e.g. a catalog) by name, throwing a
     * validation error if not found.
     *
     * @param unqualifiedName SqlIdentifier which returns true for isSimple()
     * @param clazz class of object to find
     *
     * @return object found
     */
    public <T extends CwmModelElement> T findUnqualifiedObject(
        SqlIdentifier unqualifiedName,
        Class<T> clazz);

    /**
     * Looks up all matching routine overloads by invocation name.
     *
     * @param invocationName invocation name of routine to look up
     * @param routineType type of routine to look up, or null for any type
     *
     * @return list of matching FemRoutine objects (empty if no matches)
     */
    public List<FemRoutine> findRoutineOverloads(
        SqlIdentifier invocationName,
        ProcedureType routineType);

    /**
     * Looks up a SQL datatype by name, throwing an exception if not found.
     *
     * @param typeName name of type to find
     *
     * @return type definition
     */
    public CwmSqldataType findSqldataType(SqlIdentifier typeName);

    /**
     * Looks up a jar from a string literal representing its name (typically
     * from a LIBRARY clause), throwing an exception if not found.
     *
     * @param jarName string literal representing name of jar
     *
     * @return jar found
     */
    public FemJar findJarFromLiteralName(String jarName);

    /**
     * Resolve a (possibly qualified) name of a schema object.
     *
     * @param names array of 1 or more name components, from most general to
     * most specific
     * @param clazz type of object to resolve
     *
     * @return FarragoSessionResolvedObject, or null if object definitely
     * doesn't exist
     */
    public <T extends CwmModelElement> FarragoSessionResolvedObject<T>
    resolveSchemaObjectName(
        String [] names,
        Class<T> clazz);

    /**
     * Gets schema object names as specified. They can be schema or table
     * object. If names array contain 1 element, return all schema names and all
     * table names under the default schema (if that is set) If names array
     * contain 2 elements, treat 1st element as schema name and return all table
     * names in this schema
     *
     * @param names the array contains either 2 elements representing a
     * partially qualified object name in the format of 'schema.object', or an
     * unqualified name in the format of 'object'
     *
     * @return the list of all {@link SqlMoniker} object (schema and table)
     * names under the above criteria
     */
    public List<SqlMoniker> getAllSchemaObjectNames(List<String> names);

    /**
     * Sets the parser position to use for context in error messages.
     *
     * @param pos new position to set, or null to clear
     */
    public void setParserPosition(SqlParserPos pos);

    /**
     * Validates that a particular feature is enabled.
     *
     * @param feature feature being used, represented as a resource definition
     * from {@link org.eigenbase.resource.EigenbaseResource}
     * @param context parser position context for error reporting, or null if
     * none available
     */
    public void validateFeature(
        ResourceDefinition feature,
        SqlParserPos context);

    /**
     * Sets the timing tracer associated with this statement
     *
     * @param timingTracer tracer to use
     */
    public void setTimingTracer(
        EigenbaseTimingTracer timingTracer);

    /**
     * @return the timing tracer associated with this statement
     */
    public EigenbaseTimingTracer getTimingTracer();

    /**
     * Looks up a sample dataset for a given schema object, or returns null if
     * none is found.
     *
     * @param columnSet Schema object
     * @param datasetName Name of dataset, not null
     *
     * @return Sample dataset, or null if not found
     */
    public CwmNamedColumnSet getSampleDataset(
        CwmNamedColumnSet columnSet,
        String datasetName);

    /**
     * Validates a data type expression.
     */
    public void validateDataType(SqlDataTypeSpec dataType)
        throws SqlValidatorException;

    /**
     * Sets the repository transaction context associated with this statement.
     *
     * @param reposTxnContext repos txn context to use
     */
    public void setReposTxnContext(FarragoReposTxnContext reposTxnContext);

    /**
     * @return the repository transaction context associated with this
     * statement.
     */
    public FarragoReposTxnContext getReposTxnContext();
}

// End FarragoSessionStmtValidator.java
