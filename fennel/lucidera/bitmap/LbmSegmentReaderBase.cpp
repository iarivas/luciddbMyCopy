/*
// $Id$
// Fennel is a library of data storage and processing components.
// Copyright (C) 2006-2006 LucidEra, Inc.
// Copyright (C) 2006-2006 The Eigenbase Project
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

#include "fennel/common/CommonPreamble.h"
#include "fennel/exec/ExecStreamBufAccessor.h"
#include "fennel/lucidera/bitmap/LbmSegmentReader.h"

FENNEL_BEGIN_CPPFILE("$Id$");

void LbmSegmentReaderBase::init(
    SharedExecStreamBufAccessor &pInAccessorInit,
    TupleData &bitmapSegTuple)
{
    pInAccessor = pInAccessorInit;
    pBitmapSegTuple = &bitmapSegTuple;
    byteSegLen = 0;
    byteSegOffset = LcsRid(0);
    pSegStart = NULL;
    pSegDescStart = NULL;
    pSegDescEnd = NULL;
    zeroBytes = 0;
}

ExecStreamResult LbmSegmentReaderBase::readBitmapSegTuple()
{
    if (pInAccessor->getState() == EXECBUF_EOS) {
        return EXECRC_EOS;
    }

    // consume the previous input if there was one
    if (pInAccessor->isTupleConsumptionPending()) {
        pInAccessor->consumeTuple();
    }
    if (!pInAccessor->demandData()) {
        return EXECRC_BUF_UNDERFLOW;
    }

    pInAccessor->unmarshalTuple(*pBitmapSegTuple);

    // extract starting rid and compute its equivalent byte segment number
    startRID = *reinterpret_cast<LcsRid const *> ((*pBitmapSegTuple)[0].pData);
    byteSegOffset = startRID / LbmOneByteSize;
    zeroBytes = 0;

    // determine where the segment descriptor starts and ends, if there is
    // one
    pSegDescStart = (PBuffer) (*pBitmapSegTuple)[1].pData;
    // descriptor can be NULL
    if (pSegDescStart != NULL) {
        pSegDescEnd = pSegDescStart + (*pBitmapSegTuple)[1].cbData;
    } else {
        pSegDescEnd = NULL;
    }

    // determine where the bitmap segment starts and its length
    if ((*pBitmapSegTuple)[2].pData) {
        // note that bit segment is stored backwards
        byteSegLen = (*pBitmapSegTuple)[2].cbData;
        pSegStart = (PBuffer) ((*pBitmapSegTuple)[2].pData + byteSegLen - 1);
    } else {
        // singletons do not have a corresponding bitmap, so create one
        byteSegLen = 1;
        pSegStart = &singleton;
        singleton = (uint8_t)(1 << (opaqueToInt(startRID) % LbmOneByteSize));
    }

    return EXECRC_YIELD;
}

void LbmSegmentReaderBase::advanceSegment()
{
    // first, advance byte segment offset and segment pointer by the
    // length of the remaining part of the previous segment and the
    // trailing zero bytes
    byteSegOffset += byteSegLen + zeroBytes;
    pSegStart -= byteSegLen;

    // then, read the segment descriptor to determine where the segment
    // starts and its length; also advance the segment descriptor to the
    // next descriptor
    readSegDescAndAdvance(pSegDescStart, byteSegLen, zeroBytes);
}

FENNEL_END_CPPFILE("$Id$");

// End LbmSegmentReaderBase.cpp