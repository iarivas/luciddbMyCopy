/*
// $Id$
// Fennel is a library of data storage and processing components.
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

#ifndef Fennel_ExecStreamGenerator_Included
#define Fennel_ExecStreamGenerator_Included

#include "fennel/exec/MockProducerExecStream.h"
#include "fennel/exec/DynamicParam.h"
#include <boost/shared_ptr.hpp>
#include <algorithm>
#include <numeric>

FENNEL_BEGIN_NAMESPACE

using boost::shared_ptr;
using std::vector;

/**
 * Test data generators, usually for a 45-degree ramp
 * (output value equals input row number).
 *
 * @author John V. Sichi
 * @version $Id$
 */
class RampExecStreamGenerator : public MockProducerExecStreamGenerator
{
protected:
    int offset;
    int factor;
public:
    RampExecStreamGenerator(int offsetInit, int factorInit) {
        offset = offsetInit;
        factor = factorInit;
    }

    RampExecStreamGenerator(int offsetInit) {
        offset = offsetInit;
        factor = 1;
    }

    RampExecStreamGenerator() {
        offset = 0;
        factor = 1;
    }

    virtual int64_t generateValue(uint iRow, uint iCol)
    {
        return iRow * factor + offset;
    }
};

class RampPartitionedExecStreamGenerator
          : public MockProducerExecStreamGenerator
{
protected:
    int partitionSize;
public:

    RampPartitionedExecStreamGenerator(uint partSize) {
        partitionSize = partSize;
    }

    virtual int64_t generateValue(uint iRow, uint iCol)
    {
        if (iCol == 0) {
            return iRow / partitionSize;
        }
        return iRow;
    }
};
/**
 * @author John V. Sichi
 */
class PermutationGenerator : public MockProducerExecStreamGenerator
{
    std::vector<int64_t> values;
    uint partitionSize;

public:
    PermutationGenerator(uint nRows)
    {
        values.resize(nRows);
        std::iota(values.begin(), values.end(), 0);
        std::random_shuffle(values.begin(), values.end());
        partitionSize = 0;
    }

    PermutationGenerator(uint nRows, uint partSize)
    {
        partitionSize = partSize;
        values.resize(nRows);
        std::iota(values.begin(), values.end(), 0);
        int i;
        assert(nRows % partitionSize == 0);
        for (i = 0; i < nRows / partitionSize; i++) {
            int start = i * partitionSize;
            int end = start + partitionSize - 1;
            std::random_shuffle(
                values.begin() + start,
                values.begin() + end);
        }
    }

    virtual int64_t generateValue(uint iRow, uint iCol)
    {
        if (!partitionSize) {
            // iCol ignored
            return values[iRow];
        } else {
            if (iCol == 0) {
                return iRow / partitionSize;
            } else {
                return values[iRow];
            }
        }
    }
};

/**
 * A Staircase Generator.
 *
 * Outputs numbers according to the formula:
 * Height * (row / (int) Width)
 *
 * @author Wael Chatila
 * @version $Id$
 */
class StairCaseExecStreamGenerator : public MockProducerExecStreamGenerator
{
    int s;
    int h;
    int w;
public:
    StairCaseExecStreamGenerator(int height, uint width, int start = 0)
        : s(start),
        h(height),
        w(width)
    {
        // empty
    }

    virtual int64_t generateValue(uint iRow, uint iCol)
    {
        return s + h * (iRow / w);
    }
};

/**
 * Outputs the value of a specified dynamic param, reinterpreted as int64_t.
 *
 * @author Wael Chatila
 */
class DynamicParamExecStreamGenerator : public MockProducerExecStreamGenerator
{
    DynamicParamId dynamicParamId;
    SharedDynamicParamManager paramManager;

public:
    DynamicParamExecStreamGenerator(
        DynamicParamId dynamicParamId_,
        SharedDynamicParamManager paramManager_)
        : dynamicParamId(dynamicParamId_),
          paramManager(paramManager_)
    {
        // empty
    }

    virtual int64_t generateValue(uint iRow, uint iCol)
    {
        int64_t value = *reinterpret_cast<int64_t const *>(
            paramManager->getParam(dynamicParamId).getDatum().pData);
        return value;
    }
};

/**
 * Column generator which produces values which are uniformly distributed
 * between 0 and N - 1.
 */
class RandomColumnGenerator : public ColumnGenerator<int64_t>
{
    std::subtractive_rng rng;
    int max;

public:
    RandomColumnGenerator(int max) : rng(42), max(max)
        {}

    int64_t next()
    {
        return rng(max);
    }
};

/**
 * Column generator which generates values with a Poisson distribution.
 *
 * The Poisson distribution is a statistical distribution which characterizes
 * the intervals between successive events. For example, consider a large
 * sample of a radioactive isotope with a long half-life, and a Geiger counter
 * measuring decay events. The number of events N between time t=0 and t=1 will
 * be Poisson distributed.
 *
 * This generator generates an ascending sequence of values with a given mean
 * distance between values. For example, the sequence [3, 17, 24, 39, 45] might
 * be the first five values generated if startValue = 0 and meanDistance = 10.
 *
 * The generator generates a better statistical distribution if you give it a
 * larger value of batchSize.
 *
 * @author Julian Hyde
 */
template <class T = int64_t>
class PoissonColumnGenerator : public ColumnGenerator<T>
{
    T currentValue;
    /// batch of pre-generated values
    vector<T> nextValues;
    /// position in the batch of the last value we returned
    int ordinalInBatch;
    /// upper bound of current batch, will be the lower bound of the next
    T batchUpper;
    std::subtractive_rng rng;
    double meanDistance;

public:
    explicit PoissonColumnGenerator(
        T startValue,
        double meanDistance,
        int batchSize,
        uint seed) : rng(seed)
    {
        assert(batchSize > 0);
        assert(meanDistance > 0);
        assert(meanDistance * batchSize >= 1);
        this->batchUpper = startValue;
        nextValues.resize(batchSize);
        this->ordinalInBatch = batchSize;
        this->meanDistance = meanDistance;
    }

    virtual ~PoissonColumnGenerator()
        {}

    T next()
    {
        if (ordinalInBatch >= nextValues.size()) {
            generateBatch();
        }
        return nextValues[ordinalInBatch++];
    }

private:
    /// Populates the next batch of values.
    void generateBatch() {
        // The next batch will contain nextValues.size() values with a mean
        // inter-value distance of meanDistance, hence its values will range
        // from batchLower to batchLower + nextValues.size() * meanDistance.
        T batchLower = this->batchUpper;
        int batchRange = (int) (meanDistance * nextValues.size());
        T batchUpper = batchLower + batchRange;
        assert(batchUpper > batchLower);
        for (int i = 0; i < nextValues.size(); i++) {
            nextValues[i] = batchLower + static_cast<T>(rng(batchRange));
        }
        std::sort(nextValues.begin(), nextValues.end());
        this->batchUpper = batchUpper;
        this->ordinalInBatch = 0;
    }
};


/**
 * Generates a result set consisting of columns each generated by its own
 * generator.
 *
 * @author Julian Hyde
 */
class CompositeExecStreamGenerator : public MockProducerExecStreamGenerator
{
    vector<boost::shared_ptr<ColumnGenerator<int64_t> > > generators;
    uint currentRow;
    uint currentCol;

public:
    explicit CompositeExecStreamGenerator(
        vector<shared_ptr<ColumnGenerator<int64_t> > > const &generatorsInit)
        : generators(generatorsInit)
    {
        currentRow = uint(-1);
        currentCol = columnCount() - 1;
    }

    virtual int64_t generateValue(uint iRow, uint iCol)
    {
        // Check that access is sequential.
        if (iCol == 0) {
            assert(iRow == currentRow + 1);
            assert(currentCol == columnCount() - 1);
        } else {
            assert(iRow == currentRow);
            assert(iCol == currentCol + 1);
        }
        currentRow = iRow;
        currentCol = iCol;

        return generators[iCol]->next();
    }

private:
    uint columnCount()
    {
        return generators.size();
    }
};

/**
 * Duplicate stream generator
 *
 * Generates two duplicates rows per value
 *
 * @author Zelaine Fong
 * @version $Id$
 */
class RampDuplicateExecStreamGenerator : public MockProducerExecStreamGenerator
{

public:
    virtual int64_t generateValue(uint iRow, uint iCol)
    {
        return iRow / 2;
    }
};


class ConstExecStreamGenerator : public MockProducerExecStreamGenerator
{
    uint constVal;

public:
    ConstExecStreamGenerator(uint constValInit)
    {
        constVal = constValInit;
    }

    virtual int64_t generateValue(uint iRow, uint iCol)
    {
        return constVal;
    }
};

/**
 * Column generator which produces values in sequence, starting at start,
 * optionally with a fixed offset between each value.
 */
class SeqColumnGenerator : public ColumnGenerator<int64_t>
{
    int offset;
    int curr;

public:
    explicit SeqColumnGenerator()
    {
        offset = 1;
        curr = -1;
    }
    explicit SeqColumnGenerator(int startInit)
    {
        offset = 1;
        curr = startInit - 1;
    }

    explicit SeqColumnGenerator(int startInit, int offsetInit)
    {
        offset = offsetInit;
        curr = startInit - offset;
    }

    int64_t next()
    {
        curr += offset;
        return curr;
    }
};


/**
 * Constant column generator
 *
 * Generates the same value for a column
 *
 * @author Zelaine Fong
 * @version $Id$
 */
class ConstColumnGenerator : public ColumnGenerator<int64_t>
{
    int64_t constvalue;

public:
    explicit ConstColumnGenerator(int constInit) {
        constvalue = constInit;
    }

    int64_t next()
    {
        return constvalue;
    }
};

/**
 * Duplicate column generator
 *
 * Generates numDups duplicate rows per value for a column, in sequence,
 * starting at initValue
 */
class DupColumnGenerator : public ColumnGenerator<int64_t>
{
    int numDups;
    int64_t curValue;

public:
    explicit DupColumnGenerator(int numDupsInit, int startValue = 0) {
        assert(numDupsInit > 0);
        numDups = numDupsInit;
        curValue = startValue * numDups;
    }

    int64_t next()
    {
        return (curValue++ / numDups);
    }
};

/**
 * A duplicating repeating column sequence generator.
 *
 * Generates column values in a repeating sequence.  Values are duplicated for
 * each sequence value, and repeat after nSequence values.  E.g.,
 * 0, 0, 0, ..., 1, 1, 1, ... 2, 2, 2, ..., n-1, n-1, n-1, ..., 0, 0, 0, ...
 */
class DupRepeatingSeqColumnGenerator : public ColumnGenerator<int64_t>
{
    int numDups;
    int numSequence;
    int64_t curValue;

public:
    explicit DupRepeatingSeqColumnGenerator(
        int numSequenceInit,
        int numDupsInit)
    {
        assert(numSequenceInit > 0);
        assert(numDupsInit > 0);
        numSequence = numSequenceInit;
        numDups = numDupsInit;
        curValue = 0;
    }

    int64_t next()
    {
        return (curValue++ % (numDups * numSequence)) / numDups;
    }
};

/**
 * A repeating column sequence generator.
 *
 * Generates column values in a repeating sequence.  Values repeat after
 * nSequence values.  E.g., 0, 1, 2, ..., nSequence-1, 0, 1, 2, ...,
 * nSequence-1, 0, ...
 */
class RepeatingSeqColumnGenerator : public ColumnGenerator<int64_t>
{
    int nSequence;
    int64_t curValue;

public:
    explicit RepeatingSeqColumnGenerator(int nSequenceInit) {
        assert(nSequenceInit > 0);
        nSequence = nSequenceInit;
        curValue = 0;
    }

    int64_t next()
    {
        return curValue++ % nSequence;
    }
};

/**
 * Mixed Duplicate column generator
 *
 * Generates a mixture of unique rows or duplicate rows of numDups
 * per value for a column, in sequence, starting at initValue:
 *
 *  0, 1, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 7, 8, ...
 */
class MixedDupColumnGenerator : public ColumnGenerator<int64_t>
{
    int numDups;
    int64_t curValue;
    int width;
    int nextValue;
    int initialValue;

public:
    explicit MixedDupColumnGenerator(
        int numDupsInit,
        int startValue = 0,
        int wid = 1)
    {
        assert(numDupsInit > 0);
        numDups = numDupsInit;
        curValue = 0;
        width = wid;
        initialValue = nextValue = startValue;
    }

    int64_t next()
    {
        int res;

        if ((((nextValue - initialValue) / width) % 2)) {
            res = nextValue + curValue++ / numDups;
            if (curValue == numDups) {
                curValue = 0;
                nextValue++;
            }
        } else {
            res = nextValue++;
        }

        return res;
    }
};

/**
 * Same as StairCaseExecStreamGenerator except for columns
 */
class StairCaseColumnGenerator : public ColumnGenerator<int64_t>
{
    int s;
    int h;
    int w;
    uint iRow;

public:
    StairCaseColumnGenerator(int height, uint width, int start = 0)
        : s(start),
        h(height),
        w(width),
        iRow(0)
    {
        // empty
    }

    int64_t next()
    {
        return s + h * (iRow++ / w);
    }
};

FENNEL_END_NAMESPACE

#endif

// End ExecStreamGenerator.h
