/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005 The Eigenbase Project
// Copyright (C) 2010 SQLstream, Inc.
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
package org.luciddb.lcs;

import org.luciddb.session.*;

import java.util.*;

import net.sf.farrago.fem.fennel.*;
import net.sf.farrago.fem.med.*;
import net.sf.farrago.fem.sql2003.*;
import net.sf.farrago.fennel.rel.*;
import net.sf.farrago.query.*;

import openjava.ptree.Literal;

import org.eigenbase.rel.*;
import org.eigenbase.rel.metadata.*;
import org.eigenbase.relopt.*;
import org.eigenbase.reltype.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.type.*;
import org.eigenbase.util.*;


/*
 * LcsRowScanRelBase is a base class for a relational expression corresponding
 * to a scan on a column store table.
 *
 * @author Zelaine Fong
 * @version $Id$
 */
public abstract class LcsRowScanRelBase
    extends FennelMultipleRel
{
    //~ Instance fields --------------------------------------------------------

    private LcsIndexGuide indexGuide;

    // Clusters to use for access.
    final List<FemLocalIndex> clusteredIndexes;

    // Refinement for super.table.
    final LcsTable lcsTable;

    // TODO: keep the connection property (originally of TableAccessRelbase)
    // remove this when LcsIndexScan is changed to derive from SingleRel.
    RelOptConnection connection;

    /**
     * Array of 0-based flattened column ordinals to project; if null, project
     * all columns. Note that these ordinals are relative to the table.
     */
    final Integer [] projectedColumns;

    /**
     * Types of scans to perform.
     */
    boolean isFullScan;

    /**
     * Array of 0-based flattened filter column ordinals.
     */
    final Integer [] residualColumns;

    /**
     * Selectivity from filtering using the inputs. Possible inputs are index
     * searches and residual filter values.
     */
    double inputSelectivity;

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a new LcsRowScanRel object.
     *
     * @param cluster RelOptCluster for this rel
     * @param children children inputs into the row scan
     * @param lcsTable table being scanned
     * @param clusteredIndexes list of clusters to use for table access, in
     * the order in which the clusters are to be scanned
     * @param connection connection
     * @param projectedColumns array of 0-based table-relative column ordinals,
     * or null to project all columns
     * @param isFullScan true if doing a full scan of the table
     * @param resCols residual filter columns (0-length array if none)
     * @param inputSelectivity estimate of input selectivity
     */
    public LcsRowScanRelBase(
        RelOptCluster cluster,
        RelNode [] children,
        LcsTable lcsTable,
        List<FemLocalIndex> clusteredIndexes,
        RelOptConnection connection,
        Integer [] projectedColumns,
        boolean isFullScan,
        Integer [] resCols,
        double inputSelectivity)
    {
        super(cluster, children);
        this.lcsTable = lcsTable;
        this.clusteredIndexes = clusteredIndexes;
        this.projectedColumns = projectedColumns;
        this.connection = connection;
        this.isFullScan = isFullScan;
        this.residualColumns = resCols;

        assert (lcsTable.getPreparingStmt()
            == FennelRelUtil.getPreparingStmt(this));

        this.inputSelectivity = inputSelectivity;
    }

    //~ Methods ----------------------------------------------------------------

    // implement RelNode
    public RelOptCost computeSelfCost(RelOptPlanner planner)
    {
        return computeCost(
            planner,
            RelMetadataQuery.getRowCount(this));
    }

    // overwrite SingleRel
    public double getRows()
    {
        double tableRowCount = lcsTable.getRowCount();
        return tableRowCount * inputSelectivity;
    }

    // implement RelNode
    protected RelDataType deriveRowType()
    {
        RelDataType flattenedRowType = getIndexGuide().getFlattenedRowType();
        if (projectedColumns == null) {
            return flattenedRowType;
        } else {
            final RelDataTypeField [] fields = flattenedRowType.getFields();
            return getCluster().getTypeFactory().createStructType(
                new RelDataTypeFactory.FieldInfo() {
                    public int getFieldCount()
                    {
                        return projectedColumns.length;
                    }

                    public String getFieldName(int index)
                    {
                        final int i = projectedColumns[index].intValue();
                        if (LucidDbOperatorTable.ldbInstance()
                                                .isSpecialColumnId(i))
                        {
                            return LucidDbOperatorTable.ldbInstance()
                                .getSpecialOpName(i);
                        } else {
                            return fields[i].getName();
                        }
                    }

                    public RelDataType getFieldType(int index)
                    {
                        final int i = projectedColumns[index].intValue();
                        LucidDbOperatorTable ldbInstance =
                            LucidDbOperatorTable.ldbInstance();
                        if (ldbInstance.isSpecialColumnId(i)) {
                            RelDataTypeFactory typeFactory =
                                getCluster().getTypeFactory();
                            SqlTypeName typeName =
                                ldbInstance.getSpecialOpRetTypeName(i);
                            return typeFactory.createTypeWithNullability(
                                typeFactory.createSqlType(typeName),
                                ldbInstance.isNullable(i));
                        } else {
                            return fields[i].getType();
                        }
                    }
                });
        }
    }

    // override TableAccess
    public void explain(RelOptPlanWriter pw)
    {
        explain(pw, null, null);
    }

    /**
     * Explains this {@link LcsRowScanRelBase}, appending the additional terms
     * and values from a subclass.
     *
     * @param pw RelOptPrintWriter, as in {@link #explain(RelOptPlanWriter)}
     * @param subclassTerms additional terms from a subclass
     * @param subclassValues additional values from a subclass
     */
    protected void explain(
        RelOptPlanWriter pw,
        String [] subclassTerms,
        Object [] subclassValues)
    {
        assert (((subclassTerms == null) && (subclassValues == null))
            || (subclassTerms.length == subclassValues.length));

        Object projection;

        if (projectedColumns == null) {
            projection = "*";
        } else {
            Object [] modifiedProj = new Object[projectedColumns.length];
            System.arraycopy(
                projectedColumns,
                0,
                modifiedProj,
                0,
                projectedColumns.length);
            projection = Arrays.asList(modifiedProj);

            // replace the numbers for the special columns so they're more
            // readable
            List<Object> projList = (List) projection;
            for (int i = 0; i < projList.size(); i++) {
                Integer colId = (Integer) projList.get(i);
                if (LucidDbOperatorTable.ldbInstance().isSpecialColumnId(
                        colId))
                {
                    projList.set(
                        i,
                        LucidDbOperatorTable.ldbInstance().getSpecialOpName(
                            colId));
                }
            }
        }

        List<String> indexNames = new ArrayList<String>();
        for (FemLocalIndex index : clusteredIndexes) {
            indexNames.add(index.getName());
        }

        // REVIEW jvs 27-Dec-2005: See http://issues.eigenbase.org/browse/FRG-8;
        // the "clustered indexes" attribute is an example of a derived
        // attribute which doesn't need to be part of the digest (it's implied
        // by a combination of the column projection and residual columns,
        // since we don't allow clusters to overlap), but is useful in verbose
        // mode. Can't resolve this comment until FRG-8 is completed.

        int nExtraTerms = hasResidualFilters() ? 1 : 0;
        if (pw.getDetailLevel() == SqlExplainLevel.DIGEST_ATTRIBUTES) {
            ++nExtraTerms;
        }
        int nSubclassTerms = (subclassTerms != null) ? subclassTerms.length : 0;
        Object [] objects = new Object[3 + nExtraTerms + nSubclassTerms];
        String [] nameList =
            new String[inputs.length + 3 + nExtraTerms + nSubclassTerms];
        for (int i = 0; i < inputs.length; i++) {
            nameList[i] = "child";
        }
        nameList[inputs.length] = "table";
        nameList[inputs.length + 1] = "projection";
        nameList[inputs.length + 2] = "clustered indexes";
        objects[0] = Arrays.asList(lcsTable.getQualifiedName());
        objects[1] = projection;
        objects[2] = indexNames;
        int iExtraTerm = 3;
        if (hasResidualFilters()) {
            nameList[inputs.length + iExtraTerm] = "residual columns";
            objects[iExtraTerm] = Arrays.asList(residualColumns);
            ++iExtraTerm;
        }
        if (pw.getDetailLevel() == SqlExplainLevel.DIGEST_ATTRIBUTES) {
            nameList[inputs.length + iExtraTerm] = "isFullScan";
            objects[iExtraTerm] = isFullScan;
            ++iExtraTerm;
        }
        if (subclassTerms != null) {
            for (int i = 0; i < subclassTerms.length; i++) {
                nameList[inputs.length + 3 + nExtraTerms + i] =
                    subclassTerms[i];
                objects[3 + nExtraTerms + i] = subclassValues[i];
            }
        }
        pw.explain(
            this,
            nameList,
            objects);
    }

    // overwrite FennelSingleRel
    public Object implementFennelChild(FennelRelImplementor implementor)
    {
        if (inputs.length == 0) {
            return Literal.constantNull();
        } else {
            return super.implementFennelChild(implementor);
        }
    }

    // implement FennelRel
    public FemExecutionStreamDef toStreamDef(FennelRelImplementor implementor)
    {
        FarragoPreparingStmt stmt = FennelRelUtil.getPreparingStmt(this);
        if (stmt.getSession().isReentrantAlterTableAddColumn()) {
            // This assert will fail if we are doing ALTER TABLE ADD COLUMN
            // but somehow we ended up subtracting off deleted rows.
            Util.permAssert(
                (inputs.length == 1)
                && (inputs[0] instanceof FennelValuesRel),
                "ALTER TABLE ADD COLUMN should preserve deleted rows");
        } else {
            // This assert will fail if the LucidDbSessionPersonality was not
            // used and therefore LcsAddDeletionScanRule wasn't fired.
            Util.permAssert(
                (inputs.length > 0)
                && ((inputs[0] instanceof LcsIndexSearchRel)
                    || (inputs[0] instanceof LcsIndexMinusRel)),
                "Column store row scans are only available in the LucidDb"
                + " personality");
        }
        return createScanStream(implementor);
    }

    protected FemLcsRowScanStreamDef createScanStream(
        FennelRelImplementor implementor)
    {
        FemLcsRowScanStreamDef scanStream =
            getIndexGuide().newRowScan(this, projectedColumns, residualColumns);

        // Sampling is disabled by default.
        scanStream.setSamplingMode(TableSamplingModeEnum.SAMPLING_OFF);

        for (int i = 0; i < inputs.length; i++) {
            FemExecutionStreamDef inputStream =
                implementor.visitFennelChild((FennelRel) inputs[i], i);
            implementor.addDataFlowFromProducerToConsumer(
                inputStream,
                scanStream);
        }

        return scanStream;
    }

    public LcsIndexGuide getIndexGuide()
    {
        if (indexGuide == null) {
            indexGuide =
                new LcsIndexGuide(
                    lcsTable.getPreparingStmt().getFarragoTypeFactory(),
                    lcsTable.getCwmColumnSet(),
                    clusteredIndexes);
        }
        return indexGuide;
    }

    // implement RelNode
    public RelOptCost computeCost(
        RelOptPlanner planner,
        double dRows)
    {
        // TODO:  compute page-based I/O cost
        // CPU cost is proportional to number of columns projected
        // I/O cost is proportional to pages of index scanned
        double dCpu = dRows * getRowType().getFieldList().size();

        int nIndexCols = 0;
        for (FemLocalIndex index : clusteredIndexes) {
            nIndexCols += getIndexGuide().getNumFlattenedClusterCols(index);
        }

        double dIo = dRows * nIndexCols;

        RelOptCost cost = planner.makeCost(dRows, dCpu, dIo);

        if (inputs.length != 0) {
            // table scan from RID stream is less costly.
            // Once we have good cost, the calculation should be
            // cost * (# of inputRIDs/# of totaltableRows).
            cost = cost.multiplyBy(0.1);
        }
        return cost;
    }

    /**
     * Gets the column referenced by a FieldAccess relative to this scan.
     *
     * @param fieldOrdinal 0-based ordinal of an output field of the scan
     *
     * @return underlying column if the the column is a real one; otherwise,
     * null is returned (e.g., if the column corresponds to the rid column)
     */
    public FemAbstractColumn getColumnForFieldAccess(int fieldOrdinal)
    {
        assert fieldOrdinal >= 0;
        if (projectedColumns != null) {
            fieldOrdinal = projectedColumns[fieldOrdinal].intValue();
        }

        if (LucidDbOperatorTable.ldbInstance().isSpecialColumnId(
                fieldOrdinal))
        {
            return null;
        } else {
            int columnOrdinal = getIndexGuide().unFlattenOrdinal(fieldOrdinal);
            return (FemAbstractColumn) lcsTable.getCwmColumnSet().getFeature()
                .get(
                    columnOrdinal);
        }
    }

    /**
     * Gets the original column pos(relative to the table) at projOrdinal
     *
     * @param projOrdinal 0-based ordinal of an output field of the scan
     *
     * @return if table has projection, return the original column pos relative
     * to the table. Otherwise, return the same value as the input.
     */
    public int getOriginalColumnOrdinal(int projOrdinal)
    {
        int origOrdinal = projOrdinal;
        if (projectedColumns != null) {
            origOrdinal = projectedColumns[projOrdinal];
        }
        return origOrdinal;
    }

    /**
     * Returns the projected column ordinal for a given column ordinal, relative
     * to this scan.
     *
     * @param origColOrdinal original column ordinal (without projection)
     *
     * @return column ordinal corresponding to the column in the projection for
     * this scan; -1 if column is not in the projection list
     */
    public int getProjectedColumnOrdinal(int origColOrdinal)
    {
        // TODO zfong 5/29/06 - The code below does not account for UDTs.
        // origColOrdinal represents an unflattened column ordinal.  It needs
        // to be converted to a flattened ordinal.  Furthermore, the flattened
        // ordinal may map to multiple fields.
        if (projectedColumns == null) {
            return origColOrdinal;
        }
        for (int i = 0; i < projectedColumns.length; i++) {
            if (projectedColumns[i] == origColOrdinal) {
                return i;
            }
        }
        return -1;
    }

    public RelOptTable getTable()
    {
        return lcsTable;
    }

    public LcsTable getLcsTable()
    {
        return lcsTable;
    }

    public RelOptConnection getConnection()
    {
        return connection;
    }

    public RelFieldCollation [] getCollations()
    {
        // if the rid column is projected, then the scan result is sorted
        // on that column
        if (projectedColumns != null) {
            for (int i = 0; i < projectedColumns.length; i++) {
                if (LucidDbSpecialOperators.isLcsRidColumnId(
                        projectedColumns[i]))
                {
                    return new RelFieldCollation[] { new RelFieldCollation(i) };
                }
            }
        }
        return RelFieldCollation.emptyCollationArray;
    }

    public double getInputSelectivity()
    {
        return inputSelectivity;
    }

    public boolean hasResidualFilters()
    {
        return (residualColumns.length > 0);
    }

    public Integer [] getResidualColumns()
    {
        return residualColumns;
    }

    public List<FemLocalIndex> getClusteredIndexes()
    {
        return clusteredIndexes;
    }

    public Integer [] getProjectedColumns()
    {
        return projectedColumns;
    }

    public boolean isFullScan()
    {
        return isFullScan;
    }
}

// End LcsRowScanRelBase.java
