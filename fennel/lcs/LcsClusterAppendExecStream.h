/*
// $Id$
// Fennel is a library of data storage and processing components.
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

#ifndef Fennel_LcsClusterAppendExecStream_Included
#define Fennel_LcsClusterAppendExecStream_Included

#include "fennel/exec/ConduitExecStream.h"
#include "fennel/tuple/TupleData.h"
#include "fennel/tuple/TupleDescriptor.h"
#include "fennel/tuple/TupleAccessor.h"
#include "fennel/ftrs/BTreeExecStream.h"
#include "fennel/lcs/LcsClusterNodeWriter.h"
#include "fennel/lcs/LcsHash.h"
#include "fennel/lcs/LcsClusterDump.h"
#include <boost/scoped_array.hpp>

FENNEL_BEGIN_NAMESPACE

struct LcsClusterAppendExecStreamParams
    : public BTreeExecStreamParams, public ConduitExecStreamParams
{
    /**
     * Projection list projecting out columns for this cluster
     */
    TupleProjection inputProj;
};

/**
 * Given a stream of tuples corresponding to the column values in a cluster,
 * loads the cluster pages
 */
class FENNEL_LCS_EXPORT LcsClusterAppendExecStream
    : public BTreeExecStream, public ConduitExecStream
{
protected:

    /**
     * Space available on page blocks for writing cluster data
     */
    uint blockSize;

    /**
     * Tuple descriptor for the tuple representing all cluster columns across
     * the table that this cluster is a part of
     */
    TupleDescriptor tableColsTupleDesc;

    /**
     * Tuple data for the tuple datums representing only this cluster
     */
    TupleData clusterColsTupleData;

    /**
     * Tuple descriptors for the columns that are part of this cluster
     */
    TupleDescriptor clusterColsTupleDesc;

    /**
     * Individual tuple descriptors for each column in the cluster
     */
    boost::scoped_array<TupleDescriptor> colTupleDesc;

    /**
     * Scratch accessor for allocating large buffer pages
     */
    SegmentAccessor scratchAccessor;

    /**
     * Lock on scratch page
     */
    ClusterPageLock bufferLock;

    /**
     * True if overwriting all existing data
     */
    bool overwrite;

    /**
     * Whether row count has been produced.
     */
    bool isDone;

    /**
     * Output tuple containing count of number of rows loaded
     */
    TupleData outputTuple;

    /**
     * A reference to the output accessor
     * contained in SingleOutputExecStream::pOutAccessor
     */
    TupleAccessor* outputTupleAccessor;

    /**
     * buffer holding the outputTuple to provide to the consumers
     */
    boost::scoped_array<FixedBuffer> outputTupleBuffer;

    /**
     * True if execute has been called at least once
     */
    bool compressCalled;

    /**
     * Array of hashes, one per cluster column
     */
    boost::scoped_array<LcsHash> hash;

    /**
     * Number of columns in the cluster
     */
    uint numColumns;

    /**
     * Array of temporary blocks for row array
     */
    boost::scoped_array<PBuffer> rowBlock;

    /**
     * Maximum number of values that can be stored in m_rowBlock
     */
    uint nRowsMax;

    /**
     * Array of temporary blocks for hash table
     */
    boost::scoped_array<PBuffer> hashBlock;

    /**
     * Array of temporary blocks used by ClusterNodeWriter
     */
    boost::scoped_array<PBuffer> builderBlock;

    /**
     * Number of rows loaded into the current set of batches
     */
    uint rowCnt;

    /**
     * True if index blocks need to be written to disk
     */
    bool indexBlockDirty;

    /**
     * Starting rowid in a cluster page
     */
    LcsRid firstRow;

    /**
     * Last rowid in the last batch
     */
    LcsRid lastRow;

    /* First rowid in current load
     */
    LcsRid startRow;

    /**
     * Page builder object
     */
    SharedLcsClusterNodeWriter lcsBlockBuilder;

    /**
     * Row value ordinal returned from hash, one per cluster column
     */
    boost::scoped_array<LcsHashValOrd> hashValOrd;

    /**
     * Temporary buffers used by WriteBatch
     */
    boost::scoped_array<boost::scoped_array<FixedBuffer> > tempBuf;

    /**
     * Max size for each column cluster used by WriteBatch
     */
    boost::scoped_array<uint> maxValueSize;

    /**
     * Indicates where or not we have already allocated arrays
     */
    bool arraysAlloced;

    /**
     * Buffer pointing to cluster page that will actually be written
     */
    PLcsClusterNode pIndexBlock;

    /**
     * Total number of rows loaded by this object
     */
    RecordNum numRowCompressed;

    /**
     * Allocate memory for arrays
     */
    void allocArrays();

    /**
     * Initializes the load.  This method should only be called when the
     * input stream has data available to read.
     */
    void initLoad();

    /**
     * Populates row and hash arrays from existing index block
     */
    void loadExistingBlock();

    /**
     * Prepare to write a fresh block
     */
    void startNewBlock();

    /**
     * Given a TupleData representing all columns in a cluster,
     * converts each column into its own TupleData
     */
    void convertTuplesToCols();

    /**
     * Adds value ordinal to row array for new row
     */
    void addValueOrdinal(uint column, uint16_t vOrd);

    /**
     * True if row array is full
     */
    bool isRowArrayFull();

    /**
     * Writes a batch(run) to index block.
     * Batches have a multiple of 8 rows.
     *
     * @param lastBatch true if last batch
     */
    void writeBatch(bool lastBatch);

    /**
     * Writes block to index when the block is full or this is the last block
     * in the load
     */
    void writeBlock();

    /**
     * Gets last block written to disk so we can append to it, reading in the
     * first rid value stored on the page
     *
     * @param pBlock returns pointer to last cluster block
     *
     * @return true if cluster is non-empty
     */
    bool getLastBlock(PLcsClusterNode &pBlock);

    /**
     * Initializes and sets up object with content specific to the load that
     * will be carried out
     */
    void init();

    /**
     * Processes rows for loading.  Calls WriteBatch once values cannot fit
     * into a page
     *
     * @param quantum ExecStream quantum
     *
     * @return ExecStreamResult value
     */
    ExecStreamResult compress(ExecStreamQuantum const &quantum);

    /**
     * Writes out the last pending batches and btree pages.  Deallocates
     * temporary memory and buffer pages.  Allows resources to be freed
     * before the execution stream is actually closed.
     */
    virtual void close();

    /**
     * Initializes member fields corresponding to the data to be loaded.
     *
     * @param inputProj projection of the input tuple that's relevant to
     * this cluster append
     */
    virtual void initTupleLoadParams(const TupleProjection &inputProj);

    /**
     * Retrieves the tuple that will be loaded into the cluster.
     *
     * @return EXECRC_BUF_UNDERFLOW if the input buffer needs to be replenished;
     * EXECRC_EOS if there are no more tuples to load; else, EXECRC_YIELD
     */
    virtual ExecStreamResult getTupleForLoad();

    /**
     * Performs post-processing after a tuple has been loaded.
     */
    virtual void postProcessTuple();

public:
    virtual void prepare(LcsClusterAppendExecStreamParams const &params);
    virtual void open(bool restart);
    virtual ExecStreamResult execute(ExecStreamQuantum const &quantum);
    virtual void getResourceRequirements(
        ExecStreamResourceQuantity &minQuantity,
        ExecStreamResourceQuantity &optQuantity);
    virtual void closeImpl();
    virtual ExecStreamBufProvision getOutputBufProvision() const;
};


FENNEL_END_NAMESPACE

#endif

// End LcsClusterAppendExecStream.h
