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

import java.io.*;

import java.net.*;

import java.sql.*;

import java.util.*;

import java.util.jar.*;

import net.sf.farrago.catalog.*;
import net.sf.farrago.cwm.behavioral.*;
import net.sf.farrago.cwm.core.*;
import net.sf.farrago.cwm.relational.*;
import net.sf.farrago.cwm.relational.enumerations.*;
import net.sf.farrago.defimpl.*;
import net.sf.farrago.fem.med.*;
import net.sf.farrago.fem.sql2003.*;
import net.sf.farrago.plugin.*;
import net.sf.farrago.query.*;
import net.sf.farrago.session.*;
import net.sf.farrago.type.*;
import net.sf.farrago.resource.*;
import net.sf.farrago.util.*;

import org.eigenbase.reltype.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.pretty.*;
import org.eigenbase.sql.type.*;
import org.eigenbase.sql.util.*;
import org.eigenbase.sql.validate.*;
import org.eigenbase.util.*;
import org.eigenbase.sql.parser.*;

/**
 * DdlRoutineHandler defines DDL handler methods for user-defined routines and
 * related objects such as types and jars. TODO: rename this class to
 * DdlUserDefHandler
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class DdlRoutineHandler
    extends DdlHandler
{
    public static final int NOT_DEPLOYED = 0;
    public static final int DEPLOYMENT_PENDING = 1;
    public static final int DEPLOYED = 2;
    public static final int DEPLOYED_PARTIAL = 3;
    public static final int UNDEPLOYMENT_PENDING = 4;

    //~ Constructors -----------------------------------------------------------

    public DdlRoutineHandler(FarragoSessionDdlValidator validator)
    {
        super(validator);
    }

    //~ Methods ----------------------------------------------------------------

    // implement FarragoSessionDdlHandler
    public void validateDefinition(FemRoutine routine)
    {
        int iOrdinal = 0;
        FemRoutineParameter returnParam = null;
        for (
            FemRoutineParameter param
            : Util.cast(routine.getParameter(), FemRoutineParameter.class))
        {
            if (param.getKind() == ParameterDirectionKindEnum.PDK_RETURN) {
                returnParam = param;
            } else {
                if (routine.getType().equals(ProcedureTypeEnum.FUNCTION)) {
                    if ((param.getKind() != null)
                        && (param.getType() == null))
                    {
                        throw validator.newPositionalError(
                            param,
                            res.ValidatorFunctionOutputParam.ex(
                                repos.getLocalizedObjectName(routine)));
                    }
                    param.setKind(ParameterDirectionKindEnum.PDK_IN);
                } else {
                    if (param.getKind() == null) {
                        param.setKind(ParameterDirectionKindEnum.PDK_IN);
                    }
                    if (param.getKind() != ParameterDirectionKindEnum.PDK_IN) {
                        // TODO jvs 25-Feb-2005:  implement OUT and INOUT
                        // params to procedures
                        throw Util.needToImplement(param.getKind());
                    }
                }
                param.setOrdinal(iOrdinal);
                ++iOrdinal;
            }
            validateRoutineParam(param);

            if (param.getType().getName().equals("CURSOR")) {
                if (!(FarragoCatalogUtil.isTableFunction(routine))) {
                    throw validator.newPositionalError(
                        routine,
                        res.ValidatorRoutineIllegalCursorParam.ex(
                            repos.getLocalizedObjectName(routine),
                            repos.getLocalizedObjectName(param)));
                }
            }
        }

        // validate column list parameters, now that we've set the types of
        // the source cursor parameters
        validateColumnListParams(routine);

        // if the return contains cursors, make sure they exist
        List<CwmFeature> routineFeatures = routine.getFeature();
        for (CwmFeature feature : routineFeatures) {
            SqlNode typeNode = validator.getSqlDefinition(feature);
            if (typeNode instanceof SqlIdentifier) {
                SqlIdentifier id = (SqlIdentifier) typeNode;
                if (id.getSimple().equals("CURSOR")) {
                    boolean match = false;
                    for (
                        FemRoutineParameter param
                        : Util.cast(
                            routine.getParameter(),
                            FemRoutineParameter.class))
                    {
                        if (param.getKind()
                            == ParameterDirectionKindEnum.PDK_RETURN)
                        {
                            continue;
                        }
                        if (param.getName().equals(feature.getName())) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) {
                        throw res.NonExistentCursorParam.ex(
                            repos.getLocalizedObjectName(feature.getName()));
                    }
                }
            }
        }

        if (FarragoCatalogUtil.isTableFunction(routine)) {
            routine.setUdx(true);
            validateAttributeSet(routine);
        }
        if (routine.getDataAccess() == null) {
            throw validator.newPositionalError(
                routine,
                res.ValidatorRoutineDataAccessUnspecified.ex(
                    repos.getLocalizedObjectName(routine)));
        }

        if (routine.getLanguage() == null) {
            routine.setLanguage(
                ExtensionLanguageEnum.SQL.toString());
        }
        if (routine.getLanguage().equals(
                ExtensionLanguageEnum.SQL.toString()))
        {
            validateSqlRoutine(routine, returnParam);
        } else {
            validateJavaRoutine(routine, returnParam);
        }

        // make sure routine signature doesn't conflict with other routines
        FarragoUserDefinedRoutineLookup lookup =
            new FarragoUserDefinedRoutineLookup(
                validator.getStmtValidator(),
                null,
                routine);
        FarragoUserDefinedRoutine prototype = lookup.convertRoutine(routine);
        SqlIdentifier invocationName = prototype.getSqlIdentifier();
        invocationName.names[invocationName.names.length - 1] =
            routine.getInvocationName();
        List<SqlFunction> list =
            SqlUtil.lookupSubjectRoutines(
                lookup,
                invocationName,
                prototype.getParamTypes(),
                routine.getType().equals(ProcedureTypeEnum.PROCEDURE)
                ? SqlFunctionCategory.UserDefinedProcedure
                : SqlFunctionCategory.UserDefinedFunction);

        // should find at least this routine!
        assert (!list.isEmpty());

        if (list.size() > 1) {
            throw validator.newPositionalError(
                routine,
                res.ValidatorRoutineConflict.ex(
                    repos.getLocalizedObjectName(routine)));
        }

        if (FarragoCatalogUtil.isRoutineConstructor(routine)) {
            CwmClassifier classifier = routine.getSpecification().getOwner();
            if (!routine.getInvocationName().equals(classifier.getName())) {
                throw validator.newPositionalError(
                    routine,
                    res.ValidatorConstructorName.ex(
                        repos.getLocalizedObjectName(routine),
                        repos.getLocalizedObjectName(classifier)));
            }
            if (!routine.getLanguage().equals(
                    ExtensionLanguageEnum.SQL.toString()))
            {
                throw Util.needToImplement(
                    "constructor methods with language "
                    + routine.getLanguage());
            }
            if (!returnParam.getType().equals(classifier)) {
                throw validator.newPositionalError(
                    routine,
                    res.ValidatorConstructorType.ex(
                        repos.getLocalizedObjectName(routine)));
            }
        }
    }

    // implement FarragoSessionDdlHandler
    public void validateModification(FemRoutine routine)
    {
        validateDefinition(routine);
    }

    private void validateSqlRoutine(
        FemRoutine routine,
        FemRoutineParameter returnParam)
    {
        if (FarragoCatalogUtil.isTableFunction(routine)) {
            throw validator.newPositionalError(
                routine,
                res.ValidatorRoutineReturnTableUnsupported.ex(
                    repos.getLocalizedObjectName(routine)));
        }
        if (routine.getDataAccess() == RoutineDataAccessEnum.RDA_NO_SQL) {
            throw validator.newPositionalError(
                routine,
                res.ValidatorRoutineNoSql.ex(
                    repos.getLocalizedObjectName(routine)));
        }
        if (routine.getParameterStyle() != null) {
            throw validator.newPositionalError(
                routine,
                res.ValidatorRoutineNoParamStyle.ex(
                    repos.getLocalizedObjectName(routine)));
        }
        if (routine.getBody() == null) {
            if (routine.getExternalName() == null) {
                // must be a method declaration and we haven't seen
                // the definition yet
                CwmProcedureExpression dummyBody =
                    repos.newCwmProcedureExpression();
                dummyBody.setLanguage(
                    ExtensionLanguageEnum.SQL.toString());
                dummyBody.setBody(";");
                routine.setBody(dummyBody);
                return;
            } else {
                throw validator.newPositionalError(
                    routine,
                    res.ValidatorRoutineBodyMissing.ex(
                        repos.getLocalizedObjectName(routine)));
            }
        }
        FarragoSession session = validator.newReentrantSession();
        session.disableSubqueryReduction();
        try {
            validateRoutineBody(session, routine, returnParam);
        } catch (FarragoUnvalidatedDependencyException ex) {
            // pass this one through
            throw ex;
        } catch (Throwable ex) {
            throw res.ValidatorInvalidObjectDefinition.ex(
                repos.getLocalizedObjectName(routine),
                ex);
        } finally {
            validator.releaseReentrantSession(session);
        }
    }

    private void validateJavaRoutine(
        FemRoutine routine,
        FemRoutineParameter returnParam)
    {
        if (routine.getBody() != null) {
            if ((routine.getBody().getLanguage() != null)
                && !routine.getBody().getLanguage().equals(
                    ExtensionLanguageEnum.JAVA.toString()))
            {
                throw validator.newPositionalError(
                    routine,
                    res.ValidatorRoutineExternalNoBody.ex(
                        repos.getLocalizedObjectName(routine)));
            }
        } else {
            CwmProcedureExpression dummyBody =
                repos.newCwmProcedureExpression();
            dummyBody.setLanguage(ExtensionLanguageEnum.JAVA.toString());
            dummyBody.setBody(";");
            routine.setBody(dummyBody);
        }

        if (!routine.getLanguage().equals(
                ExtensionLanguageEnum.JAVA.toString()))
        {
            throw validator.newPositionalError(
                routine,
                res.ValidatorRoutineExternalJavaOnly.ex(
                    repos.getLocalizedObjectName(routine)));
        }
        if (routine.getParameterStyle() == null) {
            routine.setParameterStyle(
                RoutineParameterStyleEnum.RPS_JAVA.toString());
        }
        if (!routine.getParameterStyle().equals(
                RoutineParameterStyleEnum.RPS_JAVA.toString())
            && !routine.getParameterStyle().equals(
                RoutineParameterStyleEnum.RPS_JAVA_FARRAGO.toString()))
        {
            throw validator.newPositionalError(
                routine,
                res.ValidatorRoutineJavaParamStyleOnly.ex(
                    repos.getLocalizedObjectName(routine)));
        }

        if (FarragoCatalogUtil.isTableFunction(routine)) {
            if (!routine.getParameterStyle().equals(
                    RoutineParameterStyleEnum.RPS_JAVA_FARRAGO.toString()))
            {
                throw validator.newPositionalError(
                    routine,
                    res.ValidatorRoutineReturnTableUnsupported.ex(
                        repos.getLocalizedObjectName(routine)));
            }
        }

        if (routine.getExternalName() == null) {
            // must be a method declaration and we haven't seen
            // the definition yet
            return;
        }

        FarragoUserDefinedRoutineLookup lookup =
            new FarragoUserDefinedRoutineLookup(
                validator.getStmtValidator(),
                null,
                null);
        FarragoUserDefinedRoutine sqlRoutine = lookup.convertRoutine(routine);
        try {
            sqlRoutine.getJavaMethod();
        } catch (SqlValidatorException ex) {
            throw validator.newPositionalError(routine, ex);
        }
        if (sqlRoutine.getJar() != null) {
            // Fully expand reference to jar in string
            SqlIdentifier jarId =
                FarragoCatalogUtil.getQualifiedName(sqlRoutine.getJar());
            SqlPrettyWriter pw =
                new SqlPrettyWriter(
                    SqlDialect.create(
                        validator.getStmtValidator().getSession()
                                 .getDatabaseMetaData()));
            String fqjn = pw.format(jarId);

            // replace 'jar_name:method_spec' with
            // 'fully.qualified.jar_name:method_spec'
            String expandedExternalName = routine.getExternalName();
            expandedExternalName =
                fqjn
                + expandedExternalName.substring(
                    expandedExternalName.indexOf(':'));
            routine.setExternalName(expandedExternalName);
            validator.createDependency(
                routine,
                Collections.singleton(sqlRoutine.getJar()));
        }
    }

    private void validateRoutineBody(
        FarragoSession session,
        final FemRoutine routine,
        FemRoutineParameter returnParam)
        throws Throwable
    {
        final FarragoTypeFactory typeFactory = validator.getTypeFactory();
        final List<FemRoutineParameter> params =
            Util.cast(routine.getParameter(), FemRoutineParameter.class);

        RelDataType paramRowType =
            typeFactory.createStructType(
                new RelDataTypeFactory.FieldInfo() {
                    public int getFieldCount()
                    {
                        return FarragoCatalogUtil.getRoutineParamCount(routine);
                    }

                    public String getFieldName(int index)
                    {
                        FemRoutineParameter param = params.get(index);
                        return param.getName();
                    }

                    public RelDataType getFieldType(int index)
                    {
                        FemRoutineParameter param = params.get(index);
                        return typeFactory.createCwmElementType(param);
                    }
                });

        tracer.fine(routine.getBody().getBody());

        if (FarragoUserDefinedRoutine.hasReturnPrefix(
                routine.getBody().getBody()))
        {
            validateReturnBody(
                routine,
                session,
                paramRowType,
                returnParam);
        } else {
            validateConstructorBody(
                routine,
                session,
                paramRowType,
                (FemSqlobjectType) returnParam.getType());
        }
    }

    private void validateReturnBody(
        FemRoutine routine,
        FarragoSession session,
        RelDataType paramRowType,
        FemRoutineParameter returnParam)
        throws Throwable
    {
        FarragoTypeFactory typeFactory = validator.getTypeFactory();
        FarragoSessionAnalyzedSql analyzedSql;
        try {
            analyzedSql =
                session.analyzeSql(
                    FarragoUserDefinedRoutine.removeReturnPrefix(
                        routine.getBody().getBody()),
                    typeFactory,
                    paramRowType,
                    false);
        } catch (Throwable ex) {
            throw adjustExceptionParserPosition(routine, ex);
        }

        validator.createDependency(routine, analyzedSql.dependencies);

        routine.getBody().setBody(
            FarragoUserDefinedRoutine.addReturnPrefix(
                analyzedSql.canonicalString.getSql()));

        if (analyzedSql.hasDynamicParams) {
            // TODO jvs 29-Dec-2004:  add a test for this; currently
            // hits an earlier assertion in SqlValidator
            throw res.ValidatorInvalidRoutineDynamicParam.ex();
        }

        // TODO jvs 28-Dec-2004:  CAST FROM

        RelDataType declaredReturnType =
            typeFactory.createCwmElementType(returnParam);
        RelDataType actualReturnType = analyzedSql.resultType;
        if (!SqlTypeUtil.canAssignFrom(declaredReturnType, actualReturnType)) {
            throw res.ValidatorFunctionReturnType.ex(
                actualReturnType.toString(),
                repos.getLocalizedObjectName(routine),
                declaredReturnType.toString());
        }
    }

    private void validateConstructorBody(
        FemRoutine routine,
        FarragoSession session,
        RelDataType paramRowType,
        FemSqlobjectType objectType)
    {
        FarragoTypeFactory typeFactory = validator.getTypeFactory();
        SqlDialect sqlDialect =
            SqlDialect.create(session.getDatabaseMetaData());
        FarragoSessionParser parser =
            session.getPersonality().newParser(session);
        SqlNodeList nodeList =
            (SqlNodeList) parser.parseSqlText(
                validator.getStmtValidator(),
                validator,
                routine.getBody().getBody(),
                true);
        StringBuilder newBody = new StringBuilder();
        newBody.append("BEGIN ");
        Set<CwmModelElement> dependencies = new HashSet<CwmModelElement>();
        for (SqlNode node : nodeList) {
            SqlCall call = (SqlCall) node;
            SqlIdentifier lhs = (SqlIdentifier) call.getOperands()[0];
            SqlNode expr = call.getOperands()[1];
            FemSqltypeAttribute attribute =
                (FemSqltypeAttribute) FarragoCatalogUtil.getModelElementByName(
                    objectType.getFeature(),
                    lhs.getSimple());
            if (attribute == null) {
                throw res.ValidatorConstructorAssignmentUnknown.ex(
                    repos.getLocalizedObjectName(lhs.getSimple()));
            }
            FarragoSessionAnalyzedSql analyzedSql;

            // TODO jvs 26-Feb-2005:  need to figure out how to
            // adjust parser pos in error msgs
            analyzedSql =
                session.analyzeSql(
                    expr.toSqlString(sqlDialect).getSql(),
                    typeFactory,
                    paramRowType,
                    false);
            if (analyzedSql.hasDynamicParams) {
                throw res.ValidatorInvalidRoutineDynamicParam.ex();
            }
            RelDataType lhsType = typeFactory.createCwmElementType(attribute);
            RelDataType rhsType = analyzedSql.resultType;
            if (!SqlTypeUtil.canAssignFrom(lhsType, rhsType)) {
                throw res.ValidatorConstructorAssignmentType.ex(
                    rhsType.toString(),
                    lhsType.toString(),
                    repos.getLocalizedObjectName(attribute));
            }
            newBody.append("SET SELF.");
            newBody.append(lhs.toSqlString(sqlDialect));
            newBody.append(" = ");
            newBody.append(analyzedSql.canonicalString);
            newBody.append("; ");
            dependencies.addAll(analyzedSql.dependencies);
        }
        newBody.append("RETURN SELF; END");
        routine.getBody().setBody(newBody.toString());
        validator.createDependency(
            routine,
            dependencies);
    }

    public void validateRoutineParam(FemRoutineParameter param)
    {
        validateTypedElement(param, (FemRoutine) param.getBehavioralFeature());
    }

    private void validateColumnListParams(FemRoutine routine)
    {
        for (
            FemRoutineParameter param
            : Util.cast(routine.getParameter(), FemRoutineParameter.class))
        {
            if (param.getType().getName().equals("COLUMN_LIST")) {
                // for COLUMN_LIST parameters, make sure the routine contains
                // a CURSOR parameter matching the COLUMN_LIST parameter's
                // source cursor
                FemColumnListRoutineParameter colListParam =
                    (FemColumnListRoutineParameter) param;
                String sourceCursor = colListParam.getSourceCursorName();
                boolean found = false;
                for (
                    FemRoutineParameter p
                    : Util.cast(
                        routine.getParameter(),
                        FemRoutineParameter.class))
                {
                    if (p.getName().equals(sourceCursor)) {
                        if (p.getType().getName().equals("CURSOR")) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    throw validator.newPositionalError(
                        routine,
                        res.ValidatorNoMatchingSourceCursor.ex(
                            repos.getLocalizedObjectName(sourceCursor),
                            repos.getLocalizedObjectName(param)));
                }
            }
        }
    }

    // implement FarragoSessionDdlHandler
    public void validateDefinition(FemJar jar)
    {
        // TODO jvs 19-Jan-2005: implement deployment descriptors
        String urlString = FarragoCatalogUtil.getJarUrl(jar);
        if (FarragoPluginClassLoader.isLibraryClass(urlString)) {
            // Verify that we can load the class.
            String className =
                FarragoPluginClassLoader.getLibraryClassReference(
                    urlString);
            try {
                Class.forName(className);
            } catch (Throwable ex) {
                throw res.ValidatorInvalidJarUrl.ex(
                    repos.getLocalizedObjectName(urlString),
                    repos.getLocalizedObjectName(jar),
                    ex);
            }
            return;
        }
        URL url;
        try {
            url = new URL(urlString);

            // verify that we can actually access the jar
            InputStream stream = null;
            try {
                stream = url.openStream();
            } catch (Throwable ex) {
                throw res.ValidatorInvalidJarUrl.ex(
                    repos.getLocalizedObjectName(urlString),
                    repos.getLocalizedObjectName(jar),
                    ex);
            } finally {
                Util.squelchStream(stream);
            }
        } catch (MalformedURLException ex) {
            throw res.PluginMalformedJarUrl.ex(
                repos.getLocalizedObjectName(urlString),
                repos.getLocalizedObjectName(jar),
                ex);
        }
    }

    // implement FarragoSessionDdlHandler
    public void validateDrop(FemJar jar)
    {
        if (jar.isModelExtension()) {
            throw res.ValidatorJarExtensionModelDrop.ex(
                repos.getLocalizedObjectName(jar));
        }
    }

    // implement FarragoSessionDdlHandler
    public void validateDefinition(FemSqlobjectType typeDef)
    {
        typeDef.setTypeNumber(Types.STRUCT);
        validateAttributeSet(typeDef);
        validateUserDefinedType(typeDef);
    }

    // implement FarragoSessionDdlHandler
    public void validateDefinition(FemSqldistinguishedType typeDef)
    {
        typeDef.setTypeNumber(Types.DISTINCT);
        validateUserDefinedType(typeDef);
        validateTypedElement(typeDef, typeDef);
        if (!(typeDef.getType() instanceof CwmSqlsimpleType)) {
            throw validator.newPositionalError(
                typeDef,
                res.ValidatorDistinctTypePredefined.ex(
                    repos.getLocalizedObjectName(typeDef)));
        }
        CwmSqlsimpleType predefinedType = (CwmSqlsimpleType) typeDef.getType();
        typeDef.setSqlSimpleType(predefinedType);
    }

    // implement FarragoSessionDdlHandler
    public void validateDefinition(FemUserDefinedOrdering orderingDef)
    {
        if (orderingDef.getName() == null) {
            orderingDef.setName(orderingDef.getType().getName());
        }
        if (orderingDef.getType().getOrdering().size() > 1) {
            throw validator.newPositionalError(
                orderingDef,
                res.ValidatorMultipleOrderings.ex(
                    repos.getLocalizedObjectName(orderingDef.getType())));
        }
        FemRoutine routine =
            FarragoCatalogUtil.getRoutineForOrdering(
                orderingDef);
        CwmClassifier returnType = null;
        if (routine != null) {
            if (routine.getType() != ProcedureTypeEnum.FUNCTION) {
                throw validator.newPositionalError(
                    orderingDef,
                    res.ValidatorOrderingFunction.ex(
                        repos.getLocalizedObjectName(routine),
                        repos.getLocalizedObjectName(orderingDef.getType())));
            }
            if (!routine.isDeterministic()) {
                throw validator.newPositionalError(
                    orderingDef,
                    res.ValidatorOrderingDeterministic.ex(
                        repos.getLocalizedObjectName(routine),
                        repos.getLocalizedObjectName(orderingDef.getType())));
            }
            if (routine.getDataAccess()
                == RoutineDataAccessEnum.RDA_MODIFIES_SQL_DATA)
            {
                throw validator.newPositionalError(
                    orderingDef,
                    res.ValidatorOrderingReadOnly.ex(
                        repos.getLocalizedObjectName(routine),
                        repos.getLocalizedObjectName(orderingDef.getType())));
            }
            for (Object o : routine.getParameter()) {
                FemRoutineParameter param = (FemRoutineParameter) o;
                if (param.getKind() == ParameterDirectionKindEnum.PDK_RETURN) {
                    returnType = param.getType();
                } else {
                    if (!param.getType().equals(orderingDef.getType())) {
                        throw validator.newPositionalError(
                            orderingDef,
                            res.ValidatorOrderingParamType.ex(
                                repos.getLocalizedObjectName(routine),
                                repos.getLocalizedObjectName(
                                    orderingDef.getType())));
                    }
                }
            }
        }
        if (orderingDef.getCategory()
            == UserDefinedOrderingCategoryEnum.UDOC_RELATIVE)
        {
            if (FarragoCatalogUtil.getRoutineParamCount(routine) != 2) {
                throw validator.newPositionalError(
                    orderingDef,
                    res.ValidatorRelativeOrderingDyadic.ex(
                        repos.getLocalizedObjectName(routine),
                        repos.getLocalizedObjectName(orderingDef.getType())));
            }

            // TODO jvs 22-Mar-2005:  better INTEGER identity check
            if (!returnType.getName().equals("INTEGER")) {
                throw validator.newPositionalError(
                    orderingDef,
                    res.ValidatorRelativeOrderingResult.ex(
                        repos.getLocalizedObjectName(routine),
                        repos.getLocalizedObjectName(orderingDef.getType())));
            }
        } else if (
            orderingDef.getCategory()
            == UserDefinedOrderingCategoryEnum.UDOC_MAP)
        {
            if (FarragoCatalogUtil.getRoutineParamCount(routine) != 1) {
                throw validator.newPositionalError(
                    orderingDef,
                    res.ValidatorMapOrderingMonadic.ex(
                        repos.getLocalizedObjectName(routine),
                        repos.getLocalizedObjectName(orderingDef.getType())));
            }
            if (!(returnType instanceof CwmSqlsimpleType)) {
                throw validator.newPositionalError(
                    orderingDef,
                    res.ValidatorMapOrderingResult.ex(
                        repos.getLocalizedObjectName(routine),
                        repos.getLocalizedObjectName(orderingDef.getType())));
            }
        }
    }

    private void validateUserDefinedType(FemUserDefinedType typeDef)
    {
        if (typeDef.isFinal() && typeDef.isAbstract()) {
            throw validator.newPositionalError(
                typeDef,
                res.ValidatorFinalAbstractType.ex(
                    repos.getLocalizedObjectName(typeDef)));
        }

        // NOTE jvs 13-Feb-2005: Once we support inheritance, we will allow
        // abstract and non-final for FemSqlobjectTypes.

        if (!typeDef.isFinal()) {
            throw validator.newPositionalError(
                typeDef,
                res.ValidatorNonFinalType.ex(
                    repos.getLocalizedObjectName(typeDef)));
        }

        if (typeDef.isAbstract()) {
            throw validator.newPositionalError(
                typeDef,
                res.ValidatorNonInstantiableType.ex(
                    repos.getLocalizedObjectName(typeDef)));
        }
    }

    public void executeCreation(FemJar jar)
    {
        String url = jar.getUrl().trim();
        String expandedUrl = FarragoProperties.instance().expandProperties(url);
        if (expandedUrl.startsWith(
                FarragoPluginClassLoader.LIBRARY_CLASS_PREFIX2))
        {
            throw validator.newPositionalError(
                jar,
                res.CannotDoDeploymentOnClass
                   . ex(repos.getLocalizedObjectName(jar)));
        }

        if (jar.getDeploymentState() != DEPLOYMENT_PENDING) {
            return;
        }
        String jarName = getQualifiedJarName(jar);
        String jarSchema = getJarSchema(jarName);

        FarragoSession session = validator.getStmtValidator().getSession();
        session.getSessionVariables().set(
            FarragoDefaultSessionPersonality.SQLJ_THISJAR,
            jarName);
        List<String> deployFiles = getAllDeployFiles(expandedUrl);
        if (deployFiles.size() > 0) {
            for (String deployFile : deployFiles) {
                List<String> deploySQLs = getDeploySqlStatements(
                    expandedUrl,
                    deployFile,
                    "INSTALL");
                if (deploySQLs.size() > 0) {
                    int deployState = executeDeployDescriptor(
                        jar,
                        session,
                        jarSchema,
                        deploySQLs,
                        deployFile);
                    jar.setDeploymentState(deployState);
                }
            }
        }
    }

    public void executeDrop(FemJar jar)
    {
        String url = jar.getUrl().trim();
        String expandedUrl = FarragoProperties.instance().expandProperties(url);
        if (expandedUrl
                  .startsWith(FarragoPluginClassLoader.LIBRARY_CLASS_PREFIX2))
        {
            throw validator.newPositionalError(
                jar,
                res.CannotDoDeploymentOnClass
                   .ex(repos.getLocalizedObjectName(jar)));
        }
        if (jar.getDeploymentState() != UNDEPLOYMENT_PENDING) {
            return;
        }
        // Disable mass deletion for this DROP so that it
        // doesn't interfere with nested DDL transactions.
        validator.enableMassDeletion(false);
        //  Also disable it for the nested transactions themselves.
        FarragoSession session = validator.getStmtValidator().getSession();
        boolean savedMassDeletion = session.getSessionVariables().getBoolean(
            FarragoDefaultSessionPersonality.USE_ENKI_MASS_DELETION);
        String jarName = getQualifiedJarName(jar);
        String jarSchema = getJarSchema(jarName);
        try {
            session.getSessionVariables().setBoolean(
                FarragoDefaultSessionPersonality.USE_ENKI_MASS_DELETION,
                false);
            List<String> deployFiles = getAllDeployFiles(expandedUrl);
            // Undeployment goes in reverse order of files
            Collections.reverse(deployFiles);
            if (deployFiles.size() > 0) {
                for (String deployFile : deployFiles) {
                    List<String> deploySQLs = getDeploySqlStatements(
                        expandedUrl,
                        deployFile,
                        "REMOVE");
                    if (deploySQLs.size() > 0) {
                        int deployState = executeDeployDescriptor(
                            jar,
                            session,
                            jarSchema,
                            deploySQLs,
                            deployFile);
                        jar.setDeploymentState(deployState);
                    }
                }
            }
        } finally {
            session.getSessionVariables().setBoolean(
                FarragoDefaultSessionPersonality.USE_ENKI_MASS_DELETION,
                savedMassDeletion);
        }
    }

    private String getQualifiedJarName(FemJar jar)
    {
        SqlIdentifier jarId = FarragoCatalogUtil.getQualifiedName(jar);
        SqlPrettyWriter pw = new SqlPrettyWriter(
            SqlDialect.create(
                validator.getStmtValidator()
                .getSession()
                .getDatabaseMetaData()));
        String fqjn = pw.format(jarId);
        return fqjn;
    }

    private String getJarSchema(String qualifiedJarName)
    {
        String schema = "";
        try {
            SqlParser sqlParser = new SqlParser(qualifiedJarName);
            SqlIdentifier tableId = (SqlIdentifier) sqlParser.parseExpression();
            String[] ss = tableId.names;
            schema = ss[1];
        } catch (Exception ex) {
        }
        return schema;
    }

    private int executeDeployDescriptor(
        FemJar jar,
        FarragoSession session,
        String defaultSchema,
        List<String> sqls,
        String deployFile)
    {
        Connection conn = null;
        Statement stmt = null;
        SqlBuilder sb = new SqlBuilder(SqlDialect.EIGENBASE);
        sb.append("SET SCHEMA ");
        sb.literal(sb.getDialect().quoteIdentifier(defaultSchema));
        String setDefaultSchema = sb.getSql();
        String lastSql = "";
        try {
            conn = session.getConnectionSource().newConnection();
            stmt = conn.createStatement();
            stmt.executeUpdate(setDefaultSchema);
            for (String sql : sqls) {
                lastSql = sql;
                stmt.executeUpdate(sql);
            }
        } catch (Throwable ex) {
            throw FarragoResource.instance().DeploymentActionFailed.ex(
                repos.getLocalizedObjectName(jar),
                lastSql,
                ex);
        } finally {
            Util.squelchStmt(stmt);
            Util.squelchConnection(conn);
        }
        return DEPLOYED;
    }

    private List<String> getAllDeployFiles(String jarUrl)
    {
        List<String> retValue = new ArrayList<String>();
        try {
            JarFile jarfile = getJarFile(jarUrl);
            Manifest manifest = jarfile.getManifest();
            if (manifest != null) {
                Map map = manifest.getEntries();
                for (Iterator it = map.keySet().iterator(); it.hasNext();) {
                    String entryName = (String) it.next();
                    Attributes attrs = (Attributes) map.get(entryName);
                    for (Iterator it2 =
                                    attrs.keySet().iterator(); it2.hasNext();)
                    {
                        Attributes.Name attrName = (Attributes.Name) it2.next();
                        String attrValue = attrs.getValue(attrName);
                        if ("SQLJDeploymentDescriptor".equals(
                                attrName.toString())
                            && "TRUE".equals(attrValue))
                        {
                            retValue.add(entryName);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            throw FarragoResource.instance().PluginManifestSqljInvalid.ex(
                jarUrl,
                ex);
        }
        return retValue;
    }

    private List<String> getDeploySqlStatements(
        String jarUrl,
        String deployFile,
        String operation)
    {
        JarFile jarfile = null;
        try {
            jarfile = getJarFile(jarUrl);
            JarEntry entry = jarfile.getJarEntry(deployFile);
            if (entry != null) {
                InputStream in = jarfile.getInputStream(entry);
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(in));
                String src = Util.readAllAsString(br);
                Map<String, List<String>> map =
                    validator.getParser().parseDeploymentDescriptor(src);
                return map.get(operation);
            }
        } catch (Exception ex) {
            throw FarragoResource.instance().PluginDeploymentFileInvalid.ex(
                deployFile,
                jarUrl,
                ex);
        } finally {
            Util.squelchJar(jarfile);
        }
        return new ArrayList<String>();
    }

    private JarFile getJarFile(String jarUrl)
        throws MalformedURLException, IOException
    {
        URL url = new URL(jarUrl);
        JarFile jf = new JarFile(url.getFile());
        return jf;
    }

}

// End DdlRoutineHandler.java
